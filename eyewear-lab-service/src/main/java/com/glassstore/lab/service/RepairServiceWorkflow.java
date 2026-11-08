package com.glassstore.lab.service;

import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.common.security.CurrentActor;
import com.glassstore.lab.client.WarrantyOrderClient;
import com.glassstore.lab.dto.RepairServiceCreateRequest;
import com.glassstore.lab.dto.RepairServiceResponse;
import com.glassstore.lab.dto.RepairServiceStatusHistoryDTO;
import com.glassstore.lab.dto.RepairServiceStatusRequest;
import com.glassstore.lab.dto.WarrantyOrderItemDTO;
import com.glassstore.lab.entity.RepairService;
import com.glassstore.lab.entity.RepairServiceStatusHistory;
import com.glassstore.lab.repository.RepairServiceRepository;
import com.glassstore.lab.repository.RepairServiceStatusHistoryRepository;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class RepairServiceWorkflow {
    public RepairServiceWorkflow(RepairServiceRepository repairRepository, RepairServiceStatusHistoryRepository historyRepository) {
        this(repairRepository, historyRepository, null);
    }

    private static final Set<String> VALID_SERVICES = Set.of(
            "NOSE_PAD_REPLACEMENT", "SCREW_TIGHTENING", "SCREW_REPLACEMENT",
            "FRAME_ADJUSTMENT", "TITANIUM_FRAME_ADJUSTMENT", "ULTRASONIC_CLEANING",
            "OTHER_TECHNICAL_SERVICE");

    private final RepairServiceRepository repairRepository;
    private final RepairServiceStatusHistoryRepository historyRepository;
    private final WarrantyOrderClient orderClient;

    @Transactional(readOnly = true)
    public List<RepairServiceResponse> search(String code, String phone, String status) {
        String normalizedStatus = normalizeStatus(status);
        return repairRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(item -> code == null || code.isBlank()
                        || item.getServiceCode().equalsIgnoreCase(code.trim()))
                .filter(item -> phone == null || phone.isBlank()
                        || java.util.Objects.equals(item.getCustomerPhone(), phone.trim()))
                .filter(item -> normalizedStatus == null || item.getStatus().equals(normalizedStatus))
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public RepairServiceResponse get(Long id) {
        return repairRepository.findById(id).map(this::toResponse)
                .orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy phiếu tiếp nhận"));
    }

    @Transactional
    public RepairServiceResponse create(RepairServiceCreateRequest request) {
        LocalDateTime receivedAt = LocalDateTime.now();
        if (request.expectedPickupAt().isBefore(receivedAt)) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR,
                    "Thời gian hẹn trả không được trước thời điểm tiếp nhận");
        }
        if (request.requestedServices().stream().anyMatch(service -> !VALID_SERVICES.contains(service))) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Loại dịch vụ không hợp lệ");
        }

        WarrantyOrderItemDTO purchase = resolvePurchase(request);
        Long requestedCustomerId = request.customerId() != null ? request.customerId() : purchase.customerId();
        if (!Objects.equals(requestedCustomerId, purchase.customerId())) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR,
                    "Khách hàng không thuộc đơn hàng được chọn");
        }
        if (request.customerPhone() == null || request.customerPhone().isBlank()) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Cần nhập số điện thoại khách hàng");
        }

        RepairService service = RepairService.builder()
                .customerName(trimToNull(request.customerName()))
                .customerPhone(trimToNull(request.customerPhone()))
                .productName(trimToNull(request.productName()))
                .productType(trimToNull(request.productType()))
                .productBarcode(trimToNull(request.productBarcode()))
                .frameCondition(trimToNull(request.frameCondition()))
                .lensCondition(trimToNull(request.lensCondition()))
                .reportedProblem(trimToNull(request.reportedProblem()))
                .physicalCondition(trimToNull(request.physicalCondition()))
                .requestedServices(new ArrayList<>(request.requestedServices().stream().distinct().toList()))
                .serviceType(request.requestedServices().get(0))
                .assignedTechnician(trimToNull(request.assignedTechnician()))
                .receptionEmployee(trimToNull(request.receptionEmployee()))
                .orderId(purchase.orderId())
                .orderItemId(purchase.orderItemId())
                .customerId(purchase.customerId())
                .receivedAt(receivedAt)
                .expectedPickupAt(request.expectedPickupAt())
                .status("RECEIVED")
                .notes(trimToNull(request.notes()))
                .customerNotes(trimToNull(request.customerNotes()))
                .build();
        RepairService saved = repairRepository.save(service);
        historyRepository.save(RepairServiceStatusHistory.builder()
                .repairServiceId(saved.getId())
                .newStatus("RECEIVED")
                .changedBy(saved.getReceptionEmployee())
                .changedAt(receivedAt)
                .notes("Tiếp nhận ban đầu")
                .build());
        return toResponse(saved);
    }

    @Transactional
    public RepairServiceResponse updateStatus(Long id, RepairServiceStatusRequest request) {
        String newStatus = normalizeStatus(request.status());
        RepairService service = repairRepository.findById(id)
                .orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy phiếu tiếp nhận"));
        String previous = service.getStatus();
        boolean validTransition = ("RECEIVED".equals(previous) && "IN_PROGRESS".equals(newStatus))
                || ("IN_PROGRESS".equals(previous) && "READY_FOR_PICKUP".equals(newStatus));
        if (!validTransition) {
            throw ApiException.of(ErrorCode.REPAIR_SERVICE_INVALID_TRANSITION,
                    "Không thể chuyển trạng thái từ " + previous + " sang " + newStatus);
        }
        LocalDateTime now = LocalDateTime.now();
        service.setStatus(newStatus);
        if ("READY_FOR_PICKUP".equals(newStatus)) {
            service.setCompletedAt(now);
        }
        repairRepository.save(service);
        historyRepository.save(RepairServiceStatusHistory.builder()
                .repairServiceId(service.getId())
                .previousStatus(previous)
                .newStatus(newStatus)
                .changedBy(request.changedBy().trim())
                .changedAt(now)
                .notes(trimToNull(request.notes()))
                .build());
        return toResponse(service);
    }

    private RepairServiceResponse toResponse(RepairService service) {
        List<RepairServiceStatusHistoryDTO> history =
                historyRepository.findByRepairServiceIdOrderByChangedAtAsc(service.getId()).stream()
                        .map(entry -> new RepairServiceStatusHistoryDTO(entry.getPreviousStatus(), entry.getNewStatus(),
                                entry.getChangedBy(), entry.getChangedAt(), entry.getNotes()))
                        .toList();
        return new RepairServiceResponse(
                service.getId(), service.getServiceCode(), service.getCustomerName(), service.getCustomerPhone(),
                service.getProductName(), service.getProductType(), service.getProductBarcode(),
                service.getFrameCondition(), service.getLensCondition(), service.getReportedProblem(),
                service.getPhysicalCondition(), service.getRequestedServices(), service.getAssignedTechnician(),
                service.getReceptionEmployee(), service.getReceivedAt(), service.getExpectedPickupAt(),
                service.getCompletedAt(), service.getStatus(), service.getNotes(), service.getCustomerNotes(), history);
    }

    private WarrantyOrderItemDTO resolvePurchase(RepairServiceCreateRequest request) {
        if (request.orderId() == null || request.orderItemId() == null) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR,
                    "Cần cung cấp orderId và orderItemId để tiếp nhận sửa chữa");
        }
        try {
            WarrantyOrderItemDTO item = orderClient.getPurchaseItem(request.orderId(), request.orderItemId());
            if (item == null || !request.orderId().equals(item.orderId()) || !request.orderItemId().equals(item.orderItemId())) {
                throw ApiException.of(ErrorCode.VALIDATION_ERROR,
                        "Sản phẩm không thuộc đơn hàng đã chỉ định");
            }
            if (!Objects.equals(request.customerPhone(), item.customerPhone())
                    && !Objects.equals(request.customerName(), item.customerName())) {
                throw ApiException.of(ErrorCode.VALIDATION_ERROR,
                        "Thông tin khách hàng không khớp với đơn hàng");
            }
            return item;
        } catch (FeignException.NotFound exception) {
            throw ApiException.of(ErrorCode.ORDER_NOT_FOUND,
                    "Không tìm thấy đơn hàng hoặc sản phẩm trong đơn hàng");
        } catch (FeignException exception) {
            throw ApiException.of(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Không thể xác thực thông tin đơn hàng với POS");
        }
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase();
        if (!Set.of("RECEIVED", "IN_PROGRESS", "READY_FOR_PICKUP").contains(normalized)) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Trạng thái phiếu sửa chữa không hợp lệ");
        }
        return normalized;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
