package com.glassstore.catalog.service;

import com.glassstore.catalog.dto.StockDeductionRequest;
import com.glassstore.catalog.dto.StockDeductionResponse;
import com.glassstore.catalog.entity.InventoryDeduction;
import com.glassstore.catalog.entity.InventoryMovement;
import com.glassstore.catalog.repository.InventoryBalanceRepository;
import com.glassstore.catalog.repository.InventoryDeductionRepository;
import com.glassstore.catalog.repository.InventoryMovementRepository;
import com.glassstore.catalog.repository.ProductVariantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PaidOrderStockDeductionService {

    private final InventoryBalanceRepository balanceRepository;
    private final InventoryDeductionRepository deductionRepository;
    private final InventoryMovementRepository movementRepository;
    private final ProductVariantRepository variantRepository;

    @Transactional
    public StockDeductionResponse deduct(StockDeductionRequest request) {
        DeductionCommand command = prepare(request);
        Optional<InventoryDeduction> previous = findExisting(command);
        if (previous.isPresent()) {
            return replay(command, previous.get());
        }

        for (String sku : command.quantitiesBySku().keySet()) {
            if (!variantRepository.existsBySku(sku)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy SKU " + sku + ".");
            }
            if (balanceRepository.findBySkuAndLocationCode(sku, command.locationCode()).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "SKU " + sku + " chưa được cấu hình tồn tại địa điểm " + command.locationCode() + ".");
            }
        }

        deductionRepository.saveAndFlush(InventoryDeduction.builder()
                .orderId(command.orderId())
                .idempotencyKey(command.idempotencyKey())
                .transactionCode(command.transactionCode())
                .locationCode(command.locationCode())
                .build());

        for (Map.Entry<String, Integer> entry : command.quantitiesBySku().entrySet()) {
            String sku = entry.getKey();
            int quantity = entry.getValue();
            int balanceUpdated = balanceRepository.decrementIfAvailable(
                    sku, command.locationCode(), quantity);
            int productUpdated = variantRepository.decrementStockIfAvailable(sku, quantity);
            if (balanceUpdated != 1 || productUpdated != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Không đủ tồn khả dụng cho SKU " + sku + " tại " + command.locationCode() + ".");
            }
            movementRepository.save(InventoryMovement.builder()
                    .movementType("SALE")
                    .sku(sku)
                    .sourceLocationCode(command.locationCode())
                    .referenceType("PAYMENT")
                    .referenceId(command.transactionCode())
                    .quantity(quantity)
                    .performedBy("ORDER_SERVICE")
                    .build());
        }
        return new StockDeductionResponse(command.orderId(), command.locationCode(), false);
    }

    @Transactional(readOnly = true)
    public Optional<StockDeductionResponse> replayIfAlreadyProcessed(StockDeductionRequest request) {
        DeductionCommand command = prepare(request);
        return deductionRepository.findUnlockedByOrderId(command.orderId())
                .or(() -> deductionRepository.findByIdempotencyKey(command.idempotencyKey()))
                .map(deduction -> replay(command, deduction));
    }

    private Optional<InventoryDeduction> findExisting(DeductionCommand command) {
        return deductionRepository.findByOrderId(command.orderId())
                .or(() -> deductionRepository.findByIdempotencyKey(command.idempotencyKey()));
    }

    private StockDeductionResponse replay(DeductionCommand command, InventoryDeduction deduction) {
        if (Boolean.TRUE.equals(deduction.getCancelled())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Đơn đã hủy, không được trừ kho lại.");
        }
        if (!Objects.equals(deduction.getOrderId(), command.orderId())
                || !Objects.equals(deduction.getIdempotencyKey(), command.idempotencyKey())
                || !Objects.equals(deduction.getTransactionCode(), command.transactionCode())
                || !Objects.equals(deduction.getLocationCode(), command.locationCode())
                || !sameItems(command, deduction)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency key or order was already used for a different payment.");
        }
        return new StockDeductionResponse(command.orderId(), command.locationCode(), true);
    }

    private boolean sameItems(DeductionCommand command, InventoryDeduction deduction) {
        Map<String, Integer> recordedQuantities = movementRepository
                .findAllByReferenceTypeAndReferenceId("PAYMENT", deduction.getTransactionCode())
                .stream()
                .filter(movement -> "SALE".equals(movement.getMovementType()))
                .filter(movement -> Objects.equals(movement.getSourceLocationCode(), deduction.getLocationCode()))
                .collect(Collectors.toMap(
                        InventoryMovement::getSku,
                        InventoryMovement::getQuantity,
                        Math::addExact,
                        TreeMap::new));
        return command.quantitiesBySku().equals(recordedQuantities);
    }

    private DeductionCommand prepare(StockDeductionRequest request) {
        if (request == null || request.getOrderId() == null || request.getOrderId() <= 0
                || request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()
                || request.getTransactionCode() == null || request.getTransactionCode().isBlank()
                || request.getBranchName() == null || request.getBranchName().isBlank()
                || request.getItems() == null || request.getItems().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thông tin trừ kho không hợp lệ.");
        }

        TreeMap<String, Integer> quantitiesBySku = new TreeMap<>();
        try {
            for (StockDeductionRequest.Item item : request.getItems()) {
                if (item == null || item.getSku() == null || item.getSku().isBlank()
                        || item.getQuantity() == null || item.getQuantity() <= 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "SKU và số lượng trừ kho không hợp lệ.");
                }
                quantitiesBySku.merge(item.getSku().trim(), item.getQuantity(), Math::addExact);
            }
        } catch (ArithmeticException overflow) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tổng số lượng SKU vượt giới hạn.");
        }

        return new DeductionCommand(
                request.getOrderId(),
                request.getIdempotencyKey().trim(),
                request.getTransactionCode().trim(),
                resolveLocationCode(request.getBranchName()),
                quantitiesBySku);
    }

    private String resolveLocationCode(String branchName) {
        String normalized = Normalizer.normalize(branchName.toLowerCase(java.util.Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd')
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        if (normalized.contains("lab") || normalized.contains("tong kho")) {
            return InventoryService.LAB;
        }
        if (normalized.contains("branch 01") || normalized.contains("chi nhanh 01")
                || normalized.contains("cn 01") || normalized.contains("flagship")
                || normalized.contains("le thanh ton")) {
            return InventoryService.BRANCH_01;
        }
        if (normalized.contains("branch 02") || normalized.contains("chi nhanh 02")
                || normalized.contains("cn 02") || normalized.contains("hai ba trung")
                || normalized.contains("trang tien")) {
            return InventoryService.BRANCH_02;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Không xác định được kho bán hàng từ tên chi nhánh.");
    }

    private record DeductionCommand(
            Long orderId,
            String idempotencyKey,
            String transactionCode,
            String locationCode,
            TreeMap<String, Integer> quantitiesBySku) {
    }
}
