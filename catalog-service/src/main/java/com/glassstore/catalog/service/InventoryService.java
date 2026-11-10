package com.glassstore.catalog.service;

import com.glassstore.catalog.dto.*;
import com.glassstore.catalog.entity.*;
import com.glassstore.catalog.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InventoryService {
    @Transactional(readOnly = true)
    public void checkAvailability(StockCheckRequest request) {
        if (!Set.of(BRANCH_01, BRANCH_02).contains(request.branchCode())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chi nhánh POS không hợp lệ.");
        Map<String, Integer> quantities = new TreeMap<>();
        request.items().forEach(item -> quantities.merge(item.sku(), item.quantity(), Math::addExact));
        quantities.forEach((sku, quantity) -> {
            InventoryBalance balance = balanceRepository.findBySkuAndLocationCode(sku, request.branchCode())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "SKU không có ở chi nhánh: " + sku));
            if (balance.getSellableQuantity() < quantity
                    || balance.getVariant().getAvailableQuantity() < quantity)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Không đủ tồn tại chi nhánh cho SKU " + sku);
        });
    }

    public static final String BRANCH_01 = "BRANCH_01";
    public static final String BRANCH_02 = "BRANCH_02";
    public static final String LAB = "LAB";

    private final InventoryLocationRepository locationRepository;
    private final InventoryBalanceRepository balanceRepository;
    private final InventoryMovementRepository movementRepository;
    private final InventoryDeductionRepository deductionRepository;
    private final StockTransferRepository transferRepository;
    private final ProductVariantRepository variantRepository;
    private final PaidOrderStockDeductionService paidOrderStockDeductionService;

    @Transactional(readOnly = true)
    public List<InventoryLocationDTO> getLocations() {
        return locationRepository.findAllByActiveTrueOrderByCodeAsc().stream()
                .map(location -> new InventoryLocationDTO(
                        location.getCode(), location.getName(), location.getArea(), location.getLocationType()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InventoryRowDTO> getInventory() {
        Map<String, InventoryRowDTO> rows = new TreeMap<>();
        List<InventoryLocation> locations = locationRepository.findAllByActiveTrueOrderByCodeAsc();
        for (InventoryBalance balance : balanceRepository.findAllWithDetails()) {
            ProductVariant variant = balance.getVariant();
            InventoryRowDTO row = rows.computeIfAbsent(variant.getSku(), sku ->
                    InventoryRowDTO.builder()
                            .sku(sku)
                            .productName(variant.getProduct().getName())
                            .productType(variant.getProduct().getProductType().name())
                            .quantities(locations.stream()
                                    .collect(Collectors.toMap(
                                            InventoryLocation::getCode,
                                            ignored -> 0,
                                            (left, right) -> left,
                                            LinkedHashMap::new)))
                            .build());
            row.getQuantities().put(balance.getLocation().getCode(), balance.getQuantity());
        }
        return new ArrayList<>(rows.values());
    }

    @Transactional(readOnly = true)
    public List<StockTransferDTO> getTransfers() {
        return transferRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toTransferDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<InventoryMovementDTO> getMovements(
            String sku, String locationCode, String referenceId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phân trang lịch sử không hợp lệ.");
        }
        String normalizedLocation = locationCode == null || locationCode.isBlank()
                ? null
                : normalizeLocationCode(locationCode);
        String normalizedSku = sku == null || sku.isBlank() ? null : sku.trim();
        String normalizedReferenceId = referenceId == null || referenceId.isBlank()
                ? null
                : referenceId.trim();
        return movementRepository.findByFilters(
                        normalizedSku,
                        normalizedLocation,
                        normalizedReferenceId,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(this::toMovementDto);
    }

    @Transactional
    public StockTransferDTO createTransfer(CreateStockTransferRequest request, String actor) {
        String sourceCode = normalizeLocationCode(request.getSourceLocationCode());
        String destinationCode = normalizeLocationCode(request.getDestinationLocationCode());
        if (sourceCode.equals(destinationCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kho nguồn và kho đích phải khác nhau.");
        }

        InventoryLocation source = locationRepository.findByCodeAndActiveTrue(sourceCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kho nguồn không hợp lệ."));
        InventoryLocation destination = locationRepository.findByCodeAndActiveTrue(destinationCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kho đích không hợp lệ."));

        Set<String> seenSkus = new HashSet<>();
        List<StockTransferItem> items = new ArrayList<>();
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phiếu điều chuyển phải có ít nhất một SKU.");
        }
        for (CreateStockTransferRequest.Item requestedItem : request.getItems()) {
            if (requestedItem == null || requestedItem.getSku() == null || requestedItem.getSku().isBlank()
                    || requestedItem.getQuantity() == null || requestedItem.getQuantity() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SKU và số lượng điều chuyển không hợp lệ.");
            }
            String sku = requestedItem.getSku().trim();
            if (!seenSkus.add(sku)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mỗi SKU chỉ được thêm một lần vào phiếu.");
            }
            ProductVariant variant = variantRepository.findBySku(sku)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy SKU " + sku + "."));
            InventoryBalance sourceBalance = balanceRepository.findBySkuAndLocationCode(sku, sourceCode)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT, "SKU " + sku + " chưa có số liệu tồn tại kho nguồn."));
            if (balanceRepository.findBySkuAndLocationCode(sku, destinationCode).isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "SKU " + sku + " chưa có số liệu tồn tại kho đích.");
            }
            if (requestedItem.getQuantity() > sourceBalance.getSellableQuantity()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Số lượng SKU " + sku + " vượt tồn bán được tại kho nguồn.");
            }
            items.add(StockTransferItem.builder()
                    .variant(variant)
                    .sku(sku)
                    .productName(variant.getProduct().getName())
                    .quantity(requestedItem.getQuantity())
                    .build());
        }

        StockTransfer transfer = StockTransfer.builder()
                .transferCode("TRF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT))
                .sourceLocation(source)
                .destinationLocation(destination)
                .reason(request.getReason() == null ? null : request.getReason().trim())
                .status(StockTransferStatus.PENDING)
                .createdBy(actor)
                .items(items)
                .build();
        items.forEach(item -> item.setTransfer(transfer));
        return toTransferDto(transferRepository.save(transfer));
    }

    @Transactional
    public StockTransferDTO receiveTransfer(Long id, String actor) {
        StockTransfer transfer = transferRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiếu điều chuyển."));
        if (transfer.getStatus() != StockTransferStatus.PENDING
                && transfer.getStatus() != StockTransferStatus.IN_TRANSIT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ phiếu chờ nhận mới được xác nhận nhận hàng.");
        }
        if (!transfer.getSourceLocation().isActive() || !transfer.getDestinationLocation().isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Kho nguồn hoặc kho đích không còn hoạt động.");
        }
        if (transfer.getItems() == null || transfer.getItems().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phiếu điều chuyển không có SKU hợp lệ.");
        }

        StockTransferStatus previousStatus = transfer.getStatus();
        boolean pendingTransfer = previousStatus == StockTransferStatus.PENDING;
        LocalDateTime receivedAt = LocalDateTime.now();
        if (transferRepository.transitionToReceived(
                id, previousStatus, StockTransferStatus.RECEIVED, receivedAt, actor) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phiếu điều chuyển đã được xử lý.");
        }
        for (StockTransferItem item : sortedItems(transfer.getItems())) {
            if (item.getSku() == null || item.getSku().isBlank()
                    || item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Phiếu điều chuyển chứa SKU hoặc số lượng không hợp lệ.");
            }
            String sourceCode = transfer.getSourceLocation().getCode();
            String destinationCode = transfer.getDestinationLocation().getCode();
            if (balanceRepository.findBySkuAndLocationCode(item.getSku(), destinationCode).isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "SKU " + item.getSku() + " chưa có số liệu tồn tại kho đích.");
            }

            if (pendingTransfer) {
                int sourceUpdated = balanceRepository.decrementIfAvailable(
                        item.getSku(), sourceCode, item.getQuantity());
                if (sourceUpdated != 1) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "Không đủ tồn kho nguồn cho SKU " + item.getSku() + ".");
                }
                movementRepository.save(transferMovement(
                        "TRANSFER_OUT", item, transfer, actor, sourceCode, destinationCode));
            }

            int destinationUpdated = balanceRepository.increment(
                    item.getSku(), destinationCode, item.getQuantity());
            if (destinationUpdated != 1) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Không thể cập nhật tồn kho đích cho SKU " + item.getSku() + ".");
            }
            if (!pendingTransfer && variantRepository.incrementStock(item.getSku(), item.getQuantity()) != 1) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Không thể đồng bộ tồn tổng cho SKU " + item.getSku() + ".");
            }
            movementRepository.save(transferMovement(
                    "TRANSFER_IN", item, transfer, actor, sourceCode, destinationCode));
        }

        transfer.setStatus(StockTransferStatus.RECEIVED);
        transfer.setReceivedAt(receivedAt);
        transfer.setReceivedBy(actor);
        return toTransferDto(transfer);
    }

    private String normalizeLocationCode(String code) {
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kho nguồn và kho đích là bắt buộc.");
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private InventoryMovement transferMovement(
            String movementType,
            StockTransferItem item,
            StockTransfer transfer,
            String actor,
            String sourceCode,
            String destinationCode) {
        return InventoryMovement.builder()
                .movementType(movementType)
                .sku(item.getSku())
                .sourceLocationCode(sourceCode)
                .destinationLocationCode(destinationCode)
                .referenceType("STOCK_TRANSFER")
                .referenceId(transfer.getTransferCode())
                .quantity(item.getQuantity())
                .performedBy(actor)
                .build();
    }

    private InventoryMovementDTO toMovementDto(InventoryMovement movement) {
        return InventoryMovementDTO.builder()
                .id(movement.getId())
                .movementType(movement.getMovementType())
                .sku(movement.getSku())
                .sourceLocationCode(movement.getSourceLocationCode())
                .destinationLocationCode(movement.getDestinationLocationCode())
                .referenceType(movement.getReferenceType())
                .referenceId(movement.getReferenceId())
                .quantity(movement.getQuantity())
                .performedBy(movement.getPerformedBy())
                .createdAt(movement.getCreatedAt())
                .build();
    }

    public StockDeductionResponse deductPaidOrder(StockDeductionRequest request) {
        try {
            return paidOrderStockDeductionService.deduct(request);
        } catch (DataIntegrityViolationException duplicateClaim) {
            return paidOrderStockDeductionService.replayIfAlreadyProcessed(request)
                    .orElseThrow(() -> duplicateClaim);
        }
    }

    @Transactional
    public void cancelSale(StockDeductionRequest request) {
        InventoryDeduction deduction = deductionRepository.findByOrderId(request.getOrderId()).orElse(null);
        if (deduction == null) {
            deduction = InventoryDeduction.builder().orderId(request.getOrderId()).idempotencyKey(request.getIdempotencyKey())
                    .transactionCode(request.getTransactionCode()).locationCode("NONE").build();
            deduction.setCancelled(true); deductionRepository.saveAndFlush(deduction); return;
        }
        if (!deduction.getIdempotencyKey().equals(request.getIdempotencyKey()) || !deduction.getTransactionCode().equals(request.getTransactionCode()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Thông tin hoàn kho không khớp giao dịch gốc.");
        if (Boolean.TRUE.equals(deduction.getCancelled())) return;
        TreeMap<String,Integer> items = new TreeMap<>(); request.getItems().forEach(item -> items.merge(item.getSku(),item.getQuantity(),Math::addExact));
        for (Map.Entry<String,Integer> item : items.entrySet()) {
            if (balanceRepository.increment(item.getKey(),deduction.getLocationCode(),item.getValue()) != 1 || variantRepository.incrementStock(item.getKey(),item.getValue()) != 1)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Không tìm thấy tồn gốc để hoàn SKU " + item.getKey());
            movementRepository.save(InventoryMovement.builder().movementType("SALE_CANCEL").sku(item.getKey())
                    .destinationLocationCode(deduction.getLocationCode()).referenceType("ORDER_CANCEL")
                    .referenceId(request.getOrderId().toString()).quantity(item.getValue()).performedBy("ORDER_SERVICE").build());
        }
        deduction.setCancelled(true); deductionRepository.save(deduction);
    }

    private List<StockTransferItem> sortedItems(List<StockTransferItem> items) {
        return items.stream().sorted(Comparator.comparing(StockTransferItem::getSku)).toList();
    }

    private StockTransferDTO toTransferDto(StockTransfer transfer) {
        return StockTransferDTO.builder()
                .id(transfer.getId())
                .transferCode(transfer.getTransferCode())
                .sourceLocationCode(transfer.getSourceLocation().getCode())
                .sourceLocationName(transfer.getSourceLocation().getName())
                .destinationLocationCode(transfer.getDestinationLocation().getCode())
                .destinationLocationName(transfer.getDestinationLocation().getName())
                .reason(transfer.getReason())
                .status(transfer.getStatus().name())
                .createdBy(transfer.getCreatedBy())
                .receivedBy(transfer.getReceivedBy())
                .createdAt(transfer.getCreatedAt())
                .dispatchedAt(transfer.getDispatchedAt())
                .receivedAt(transfer.getReceivedAt())
                .items(transfer.getItems().stream()
                        .map(item -> StockTransferItemDTO.builder()
                                .sku(item.getSku())
                                .productName(item.getProductName())
                                .quantity(item.getQuantity())
                                .build())
                        .toList())
                .build();
    }
}
