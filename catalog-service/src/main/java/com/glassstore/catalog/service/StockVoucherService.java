package com.glassstore.catalog.service;

import com.glassstore.catalog.dto.DefectiveInventoryDTO;
import com.glassstore.catalog.dto.StockVoucherDtos;
import com.glassstore.catalog.entity.*;
import com.glassstore.catalog.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StockVoucherService {

    private final StockVoucherRepository voucherRepository;
    private final SupplierRepository supplierRepository;
    private final InventoryLocationRepository locationRepository;
    private final InventoryBalanceRepository balanceRepository;
    private final ProductVariantRepository variantRepository;
    private final InventoryMovementRepository movementRepository;

    @Transactional(readOnly = true)
    public List<StockVoucherDtos.Response> getVouchers() {
        return voucherRepository.findAllByOrderByCreatedAtDesc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public StockVoucherDtos.Response getVoucher(Long id) {
        StockVoucher voucher = voucherRepository.findWithDetailsById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiếu kho."));
        return toResponse(voucher);
    }

    @Transactional
    public StockVoucherDtos.Response create(
            StockVoucherDtos.CreateRequest request, String idempotencyKey, String actor) {
        String key = requireText(idempotencyKey, "Idempotency-Key là bắt buộc.");
        if (key.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key vượt quá 100 ký tự.");
        }
        if (request.type() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loại phiếu là bắt buộc.");
        }
        StockVoucherType type = request.type();
        String locationCode = normalizeLocationCode(request.locationCode());
        String reason = requireText(request.reason(), "Lý do là bắt buộc.");
        String reference = trimToNull(request.reference());
        if (reference != null && reference.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thông tin tham chiếu vượt quá 120 ký tự.");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phiếu phải có ít nhất một SKU.");
        }

        List<NormalizedLine> lines = normalizeLines(type, request.items());
        Optional<StockVoucher> replay = voucherRepository.findByIdempotencyKey(key);
        if (replay.isPresent()) {
            StockVoucher existing = replay.get();
            if (!sameRequest(existing, type, locationCode, request.supplierId(), reason, reference, lines)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Idempotency-Key đã được dùng cho một nội dung phiếu khác.");
            }
            return toResponse(existing);
        }

        InventoryLocation location = locationRepository.findByCodeAndActiveTrue(locationCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kho không hợp lệ hoặc đã ngừng hoạt động."));
        Supplier supplier = resolveSupplier(type, request.supplierId());
        List<StockVoucherItem> items = new ArrayList<>();
        for (NormalizedLine line : lines) {
            ProductVariant variant = variantRepository.findBySku(line.sku())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy SKU " + line.sku() + "."));
            if (balanceRepository.findBySkuAndLocationCode(line.sku(), locationCode).isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "SKU " + line.sku() + " chưa được cấu hình tại kho đã chọn.");
            }
            items.add(StockVoucherItem.builder()
                    .variant(variant)
                    .sku(line.sku())
                    .productName(variant.getProduct().getName())
                    .quantity(line.quantity())
                    .quarantinedQuantity(line.quarantinedQuantity())
                    .build());
        }

        StockVoucher voucher = StockVoucher.builder()
                .voucherCode(codeFor(type))
                .type(type)
                .status(StockVoucherStatus.PENDING)
                .location(location)
                .supplier(supplier)
                .reason(reason)
                .reference(reference)
                .idempotencyKey(key)
                .createdBy(requireText(actor, "Không xác định được người tạo phiếu."))
                .items(items)
                .build();
        items.forEach(item -> item.setVoucher(voucher));
        try {
            return toResponse(voucherRepository.saveAndFlush(voucher));
        } catch (DataIntegrityViolationException duplicate) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Mã phiếu hoặc Idempotency-Key đã được sử dụng.", duplicate);
        }
    }

    @Transactional
    public StockVoucherDtos.Response approve(Long id, String actor) {
        StockVoucher voucher = voucherRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiếu kho."));
        requirePending(voucher);
        if (!voucher.getLocation().isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Kho của phiếu đã ngừng hoạt động.");
        }
        List<StockVoucherItem> items = sortedItems(voucher);
        List<InventoryBalance> lockedBalances = new ArrayList<>();
        for (StockVoucherItem item : items) {
            InventoryBalance balance = balanceRepository.findBySkuAndLocationCodeForUpdate(
                            item.getSku(), voucher.getLocation().getCode())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT, "Không tìm thấy tồn kho cho SKU " + item.getSku() + "."));
            lockedBalances.add(balance);
        }
        List<String> skus = items.stream().map(StockVoucherItem::getSku).toList();
        Map<String, ProductVariant> variants = variantRepository.findAllBySkuInForUpdate(skus).stream()
                .collect(Collectors.toMap(ProductVariant::getSku, Function.identity()));

        Supplier supplier = voucher.getSupplier();
        if (voucher.getType() == StockVoucherType.RECEIPT && supplier != null && !supplier.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nhà cung cấp đã ngừng hoạt động.");
        }
        validateApproval(voucher, items, lockedBalances, variants);

        LocalDateTime decidedAt = LocalDateTime.now();
        for (int index = 0; index < items.size(); index++) {
            StockVoucherItem item = items.get(index);
            InventoryBalance balance = lockedBalances.get(index);
            ProductVariant variant = variants.get(item.getSku());
            applyApprovedLine(voucher, item, balance, variant, actor);
        }
        voucher.setStatus(StockVoucherStatus.APPROVED);
        voucher.setDecidedBy(requireText(actor, "Không xác định được người duyệt phiếu."));
        voucher.setDecidedAt(decidedAt);
        return toResponse(voucher);
    }

    @Transactional
    public StockVoucherDtos.Response reject(Long id, String reason, String actor) {
        StockVoucher voucher = voucherRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiếu kho."));
        requirePending(voucher);
        voucher.setStatus(StockVoucherStatus.REJECTED);
        voucher.setDecisionReason(requireText(reason, "Lý do từ chối là bắt buộc."));
        if (voucher.getDecisionReason().length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lý do từ chối vượt quá 500 ký tự.");
        }
        voucher.setDecidedBy(requireText(actor, "Không xác định được người từ chối phiếu."));
        voucher.setDecidedAt(LocalDateTime.now());
        return toResponse(voucher);
    }

    @Transactional(readOnly = true)
    public List<DefectiveInventoryDTO> getDefectiveInventory() {
        return balanceRepository.findAllWithQuarantinedStock().stream()
                .map(balance -> new DefectiveInventoryDTO(
                        balance.getVariant().getSku(),
                        balance.getVariant().getProduct().getName(),
                        balance.getLocation().getCode(),
                        balance.getLocation().getName(),
                        balance.getQuantity(),
                        balance.getQuarantinedQuantity()))
                .toList();
    }

    private void validateApproval(
            StockVoucher voucher,
            List<StockVoucherItem> items,
            List<InventoryBalance> balances,
            Map<String, ProductVariant> variants) {
        for (int index = 0; index < items.size(); index++) {
            StockVoucherItem item = items.get(index);
            InventoryBalance balance = balances.get(index);
            ProductVariant variant = variants.get(item.getSku());
            if (variant == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Không tìm thấy SKU " + item.getSku() + ".");
            }
            if (balance.getQuantity() == null || balance.getQuarantinedQuantity() == null
                    || balance.getQuantity() < 0 || balance.getQuarantinedQuantity() < 0
                    || balance.getQuarantinedQuantity() > balance.getQuantity()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Số dư kho không hợp lệ cho SKU " + item.getSku() + ".");
            }
            if (voucher.getType() == StockVoucherType.ISSUE
                    && (balance.getSellableQuantity() < item.getQuantity()
                    || variant.getAvailableQuantity() < item.getQuantity())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Không đủ tồn bán được cho SKU " + item.getSku() + ".");
            }
            if (voucher.getType() == StockVoucherType.QUARANTINE
                    && (balance.getSellableQuantity() < item.getQuantity()
                    || variant.getAvailableQuantity() < item.getQuantity())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Không đủ tồn bán được để cách ly SKU " + item.getSku() + ".");
            }
            if (voucher.getType() == StockVoucherType.RECEIPT) {
                try {
                    Math.addExact(balance.getQuantity(), item.getQuantity());
                    Math.addExact(variant.getStockQuantity(), item.getQuantity());
                    Math.addExact(balance.getQuarantinedQuantity(), item.getQuarantinedQuantity());
                    Math.addExact(variant.getNonSellableQuantity(), item.getQuarantinedQuantity());
                } catch (ArithmeticException overflow) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Số lượng vượt giới hạn lưu trữ.");
                }
            }
        }
    }

    private void applyApprovedLine(
            StockVoucher voucher,
            StockVoucherItem item,
            InventoryBalance balance,
            ProductVariant variant,
            String actor) {
        String locationCode = voucher.getLocation().getCode();
        switch (voucher.getType()) {
            case RECEIPT -> {
                balance.setQuantity(balance.getQuantity() + item.getQuantity());
                variant.setStockQuantity(variant.getStockQuantity() + item.getQuantity());
                if (item.getQuarantinedQuantity() > 0) {
                    balance.setQuarantinedQuantity(balance.getQuarantinedQuantity() + item.getQuarantinedQuantity());
                    variant.setNonSellableQuantity(variant.getNonSellableQuantity() + item.getQuarantinedQuantity());
                    recordMovement("DEFECT_IN", item.getSku(), null, locationCode,
                            voucher, item.getQuarantinedQuantity(), actor);
                }
                int sellableReceipt = item.getQuantity() - item.getQuarantinedQuantity();
                if (sellableReceipt > 0) {
                    recordMovement("STOCK_IN", item.getSku(), null, locationCode,
                            voucher, sellableReceipt, actor);
                }
                balanceRepository.save(balance);
                variantRepository.save(variant);
            }
            case ISSUE -> {
                balance.setQuantity(balance.getQuantity() - item.getQuantity());
                variant.setStockQuantity(variant.getStockQuantity() - item.getQuantity());
                balanceRepository.save(balance);
                variantRepository.save(variant);
                recordMovement("STOCK_OUT", item.getSku(), locationCode, null,
                        voucher, item.getQuantity(), actor);
            }
            case QUARANTINE -> {
                balance.setQuarantinedQuantity(balance.getQuarantinedQuantity() + item.getQuantity());
                variant.setNonSellableQuantity(variant.getNonSellableQuantity() + item.getQuantity());
                balanceRepository.save(balance);
                variantRepository.save(variant);
                recordMovement("DEFECT_QUARANTINE", item.getSku(), locationCode, locationCode,
                        voucher, item.getQuantity(), actor);
            }
        }
    }

    private void recordMovement(
            String movementType,
            String sku,
            String sourceLocationCode,
            String destinationLocationCode,
            StockVoucher voucher,
            int quantity,
            String actor) {
        movementRepository.save(InventoryMovement.builder()
                .movementType(movementType)
                .sku(sku)
                .sourceLocationCode(sourceLocationCode)
                .destinationLocationCode(destinationLocationCode)
                .referenceType("STOCK_VOUCHER")
                .referenceId(voucher.getVoucherCode())
                .quantity(quantity)
                .performedBy(actor)
                .build());
    }

    private Supplier resolveSupplier(StockVoucherType type, Long supplierId) {
        if (type != StockVoucherType.RECEIPT && supplierId != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chỉ phiếu nhập được gắn nhà cung cấp.");
        }
        if (supplierId == null) {
            return null;
        }
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nhà cung cấp không hợp lệ."));
        if (!supplier.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nhà cung cấp đã ngừng hoạt động.");
        }
        return supplier;
    }

    private List<NormalizedLine> normalizeLines(StockVoucherType type, List<StockVoucherDtos.ItemRequest> requestedItems) {
        List<NormalizedLine> lines = new ArrayList<>();
        Set<String> seenSkus = new HashSet<>();
        for (StockVoucherDtos.ItemRequest item : requestedItems) {
            if (item == null || item.sku() == null || item.sku().isBlank()
                    || item.quantity() == null || item.quantity() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SKU và số lượng phải hợp lệ, số lượng lớn hơn 0.");
            }
            String sku = item.sku().trim();
            if (!seenSkus.add(sku)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mỗi SKU chỉ được thêm một lần vào phiếu.");
            }
            int quarantinedQuantity = item.quarantinedQuantity() == null ? 0 : item.quarantinedQuantity();
            if (type == StockVoucherType.RECEIPT) {
                if (quarantinedQuantity < 0 || quarantinedQuantity > item.quantity()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Số lượng cách ly phải từ 0 đến số lượng nhập.");
                }
            } else if (type == StockVoucherType.QUARANTINE) {
                quarantinedQuantity = item.quantity();
            } else if (quarantinedQuantity != 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phiếu xuất không thể chứa số lượng cách ly.");
            }
            lines.add(new NormalizedLine(sku, item.quantity(), quarantinedQuantity));
        }
        lines.sort(Comparator.comparing(NormalizedLine::sku));
        return lines;
    }

    private boolean sameRequest(
            StockVoucher voucher,
            StockVoucherType type,
            String locationCode,
            Long supplierId,
            String reason,
            String reference,
            List<NormalizedLine> lines) {
        if (voucher.getType() != type
                || !voucher.getLocation().getCode().equals(locationCode)
                || !Objects.equals(voucher.getSupplier() == null ? null : voucher.getSupplier().getId(), supplierId)
                || !voucher.getReason().equals(reason)
                || !Objects.equals(voucher.getReference(), reference)) {
            return false;
        }
        List<NormalizedLine> existingLines = sortedItems(voucher).stream()
                .map(item -> new NormalizedLine(item.getSku(), item.getQuantity(), item.getQuarantinedQuantity()))
                .toList();
        return existingLines.equals(lines);
    }

    private List<StockVoucherItem> sortedItems(StockVoucher voucher) {
        return voucher.getItems().stream()
                .sorted(Comparator.comparing(StockVoucherItem::getSku))
                .toList();
    }

    private void requirePending(StockVoucher voucher) {
        if (voucher.getStatus() != StockVoucherStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ phiếu PENDING mới được duyệt hoặc từ chối.");
        }
    }

    private StockVoucherDtos.Response toResponse(StockVoucher voucher) {
        Supplier supplier = voucher.getSupplier();
        return new StockVoucherDtos.Response(
                voucher.getId(),
                voucher.getVoucherCode(),
                voucher.getType(),
                voucher.getStatus().name(),
                voucher.getLocation().getCode(),
                voucher.getLocation().getName(),
                supplier == null ? null : new StockVoucherDtos.SupplierSummary(
                        supplier.getId(), supplier.getCode(), supplier.getName()),
                voucher.getReason(),
                voucher.getReference(),
                voucher.getCreatedBy(),
                voucher.getDecidedBy(),
                voucher.getDecisionReason(),
                voucher.getCreatedAt(),
                voucher.getDecidedAt(),
                sortedItems(voucher).stream()
                        .map(item -> new StockVoucherDtos.ItemResponse(
                                item.getSku(), item.getProductName(), item.getQuantity(), item.getQuarantinedQuantity()))
                        .toList());
    }

    private String codeFor(StockVoucherType type) {
        String prefix = switch (type) {
            case RECEIPT -> "IN";
            case ISSUE -> "OUT";
            case QUARANTINE -> "DEF";
        };
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    }

    private String normalizeLocationCode(String value) {
        return requireText(value, "Kho là bắt buộc.").toUpperCase(Locale.ROOT);
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record NormalizedLine(String sku, int quantity, int quarantinedQuantity) {}
}
