package com.glassstore.order.service;

import com.glassstore.order.client.LabClient;
import com.glassstore.order.domain.*;
import com.glassstore.order.entity.*;
import com.glassstore.order.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Keeps paid customer-input orders visible while clinical approval is still missing. */
@Service @RequiredArgsConstructor
public class LabOrderReviewService {
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final LabDispatchOutboxRepository outboxes;
    private final OrderStatusHistoryRepository history;
    private final LabClient lab;
    private final LabDispatchService dispatch;
    private final OrderStatusService statuses;
    private static final Set<OrderStatus> ELIGIBLE = Set.of(OrderStatus.PAID, OrderStatus.PENDING_CONFIRMATION,
            OrderStatus.CONFIRMED, OrderStatus.PREPARING, OrderStatus.LAB_DISPATCH_FAILED);
    public record Pending(Long id, String orderCode, Long customerId, String customerName, Long prescriptionId,
            OrderPrescription prescription, String state, String message, String products) {}
    public record ReviewResult(Long orderId, Long prescriptionId, Long prescriptionVersion, String status) {}
    private boolean clinical(Order order) {
        return order.getPrescriptionId() != null && order.getPrescription() != null
                && "CLINICAL_REVISION".equals(order.getPrescription().getSource());
    }
    private void requireEligible(Order order) {
        if (!ELIGIBLE.contains(order.currentStatus()) || order.getOrderType() == null || !order.getOrderType().requiresPrescription()
                || !payments.existsByOrderIdAndStatus(order.getId(), PaymentStatus.SUCCESS))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ gửi Lab cho đơn kính thuốc đã thanh toán và chưa hoàn tất/hủy.");
    }
    @Transactional(readOnly = true)
    public List<Pending> pending() {
        List<Pending> result = new ArrayList<>();
        for (Order order : orders.findAllByOrderByCreatedAtDesc()) {
            if (!ELIGIBLE.contains(order.currentStatus()) || order.getOrderType() == null || !order.getOrderType().requiresPrescription()
                    || !payments.existsByOrderIdAndStatus(order.getId(), PaymentStatus.SUCCESS)) continue;
            var outbox = outboxes.findByOrderId(order.getId()).orElse(null);
            if (outbox != null && "SYNCED".equals(outbox.getStatus()) && order.getLabConfirmedAt() != null) continue;
            String state = order.getLabConfirmedAt() == null ? "WAITING_POS" : "DISPATCH_PENDING";
            String message = outbox != null && outbox.getLastError() != null
                    ? "Chưa đồng bộ được lệnh Lab. Bấm gửi Lab để đồng bộ ngay."
                    : "Chờ nhân viên POS kiểm tra khách hàng và thông số trước khi gửi xưởng";
            String products = order.getItems().stream().map(OrderItem::getProductName).filter(Objects::nonNull).reduce((a,b) -> a + " · " + b).orElse("");
            result.add(new Pending(order.getId(), order.getOrderCode(), order.getCustomerId(), order.getCustomerName(), order.getPrescriptionId(), order.getPrescription(), state, message, products));
        }
        return result;
    }
    @Transactional
    public ReviewResult approve(Long id, Long prescriptionId, Long expectedVersion, boolean confirmed, String actor) {
        if (!confirmed)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vui lòng xác nhận gửi lệnh gia công sang Lab.");
        Order order = orders.lockById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireEligible(order);
        if (order.getLabConfirmedAt() != null) {
            dispatch.enqueue(order);
            return new ReviewResult(id, order.getPrescriptionId(), order.getPrescription().getVersion(),
                    outboxes.findByOrderId(id).map(entry -> entry.getStatus()).orElse("PENDING"));
        }
        var outbox = outboxes.findByOrderId(id).orElse(null);
        Long finalRxId = prescriptionId != null ? prescriptionId : order.getPrescriptionId();
        Long finalVersion = expectedVersion != null ? expectedVersion : (order.getPrescription() != null && order.getPrescription().getVersion() != null ? order.getPrescription().getVersion() : 0L);

        if (prescriptionId != null) {
            Map<String,Object> data = null;
            try { var response = lab.getPrescription(prescriptionId); data = response == null ? null : response.getData(); }
            catch (RuntimeException error) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Không xác minh được toa đã chọn. Vui lòng thử lại."); }
            if (data == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không tìm thấy toa đã chọn.");
            if (data != null) {
                OrderPrescription reviewed = ClinicalPrescriptionSnapshot.from(data, prescriptionId, order.getCustomerId());
                if (expectedVersion != null && !expectedVersion.equals(reviewed.getVersion()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Toa kính đã đổi phiên bản. Tải lại trước khi xác nhận.");
                if (!ClinicalPrescriptionSnapshot.differences(order.getPrescription(), reviewed).isEmpty())
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Toa đã chọn khác thông số đặt hàng. Cần xác minh lại; đơn chưa được gửi Lab.");
                order.setPrescriptionId(prescriptionId);
                order.setPrescription(reviewed);
                finalVersion = reviewed.getVersion();
                order.getItems().stream().filter(item -> item.getPrescriptionId() != null
                        || Objects.toString(item.getSkuId(), "").toUpperCase(Locale.ROOT).startsWith("LNS-")
                        || Objects.toString(item.getProductName(), "").toLowerCase(Locale.ROOT).contains("tròng"))
                        .forEach(item -> item.setPrescriptionId(prescriptionId));
            }
        } else {
            OrderPrescription existingRx = order.getPrescription();
            validateMeasurements(existingRx);
            order.setPrescription(existingRx);
        }
        validateMeasurements(order.getPrescription());
        order.setLabConfirmedAt(java.time.LocalDateTime.now());
        order.setLabConfirmedBy(actor);
        orders.save(order);
        if (order.currentStatus() != OrderStatus.PREPARING) statuses.transition(order, OrderStatus.PREPARING, actor, "POS xác nhận khách hàng và thông số; gửi lệnh gia công sang Lab");
        else history.save(OrderStatusHistory.builder().order(order).fromStatus(order.currentStatus()).toStatus(order.currentStatus())
                .actor(actor).reason("POS xác nhận khách hàng và thông số; gửi lệnh gia công sang Lab").build());
        if (outbox != null && !"SYNCED".equals(outbox.getStatus())) {
            outbox.setStatus("PENDING");
            outbox.setLastError(null);
            outboxes.save(outbox);
        }
        dispatch.enqueue(order);
        var refreshedOutbox = outboxes.findByOrderId(id).orElse(outbox);
        return new ReviewResult(id, finalRxId != null ? finalRxId : 0L, finalVersion, refreshedOutbox != null && "SYNCED".equals(refreshedOutbox.getStatus()) ? "SYNCED" : "PENDING");
    }

    static void validateMeasurements(OrderPrescription rx) {
        if (rx == null || rx.getOdSph() == null || rx.getOdCyl() == null || rx.getOdAxis() == null || rx.getOdAdd() == null
                || rx.getOsSph() == null || rx.getOsCyl() == null || rx.getOsAxis() == null || rx.getOsAdd() == null || rx.getPd() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Đơn thiếu thông số kính. Cần bổ sung và xác minh trước khi gửi Lab.");
        if (rx.getOdAxis() < 0 || rx.getOdAxis() > 180 || rx.getOsAxis() < 0 || rx.getOsAxis() > 180
                || rx.getPd().compareTo(new java.math.BigDecimal("40")) < 0 || rx.getPd().compareTo(new java.math.BigDecimal("90")) > 0
                || rx.getOdSph().abs().doubleValue() > 30 || rx.getOsSph().abs().doubleValue() > 30
                || rx.getOdCyl().abs().doubleValue() > 10 || rx.getOsCyl().abs().doubleValue() > 10
                || rx.getOdAdd().signum() < 0 || rx.getOsAdd().signum() < 0 || rx.getOdAdd().doubleValue() > 4 || rx.getOsAdd().doubleValue() > 4)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thông số kính hoặc PD ngoài miền hợp lệ. Cần xác minh tại POS trước khi gửi Lab.");
    }
}
