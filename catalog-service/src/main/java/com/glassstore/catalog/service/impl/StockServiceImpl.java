package com.glassstore.catalog.service.impl;

import com.glassstore.catalog.dto.StockDtos;
import com.glassstore.catalog.entity.*;
import com.glassstore.catalog.repository.ProductVariantRepository;
import com.glassstore.catalog.repository.StockMovementRepository;
import com.glassstore.catalog.repository.StockReservationRepository;
import com.glassstore.catalog.service.StockService;
import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Stock mutation engine.
 *
 * <p>Ordering rules that keep this safe under concurrency:</p>
 * <ol>
 *   <li>Aggregate duplicate SKUs in the request before touching the database, otherwise the
 *       same row would be written twice with stale in-memory values.</li>
 *   <li>Lock every affected variant in a single query, sorted by SKU, so two concurrent
 *       checkouts acquire locks in the same order and cannot deadlock.</li>
 *   <li>Validate the whole basket before mutating anything, so a rejected line leaves no
 *       partial reservation behind (EX-INV-01).</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StockServiceImpl implements StockService {

    private static final String SYSTEM_ACTOR = "SYSTEM";

    private final ProductVariantRepository variantRepository;
    private final StockReservationRepository reservationRepository;
    private final StockMovementRepository movementRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.glassstore.catalog.repository.InventoryBalanceRepository balanceRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.glassstore.catalog.repository.InventoryMovementRepository inventoryMovementRepository;

    @Override
    @Transactional
    public StockDtos.ReservationResult reserve(StockDtos.ReserveRequest request) {
        String key = requireKey(request.getReservationKey());

        // Idempotency: a retried checkout returns the original hold instead of doubling it.
        List<StockReservation> existing = reservationRepository.findAllByReservationKey(key);
        if (!existing.isEmpty()) {
            return replay(existing.get(0), request, key);
        }

        Map<String, Integer> wanted = aggregate(request.getLines());
        List<String> skus = new ArrayList<>(wanted.keySet());
        Collections.sort(skus);

        List<ProductVariant> variants = variantRepository.findAllBySkuInForUpdate(skus);
        Map<String, ProductVariant> bySku = variants.stream()
                .collect(Collectors.toMap(ProductVariant::getSku, v -> v));

        // ---- validate everything first (all-or-nothing) ----
        for (String sku : skus) {
            ProductVariant variant = bySku.get(sku);
            if (variant == null) {
                throw ApiException.of(ErrorCode.VARIANT_NOT_FOUND, "Không tìm thấy SKU: " + sku);
            }
            requireSellable(variant);
            int need = wanted.get(sku);
            int available = variant.getAvailableQuantity();
            if (available < need) {
                throw ApiException.of(ErrorCode.INSUFFICIENT_STOCK,
                        String.format("SKU %s chỉ còn %d sản phẩm khả dụng, cần %d",
                                sku, available, need),
                        Map.of("sku", sku, "requested", need, "available", available));
            }
        }

        // ---- apply ----
        List<StockReservation> saved = new ArrayList<>();
        for (String sku : skus) {
            ProductVariant variant = bySku.get(sku);
            int need = wanted.get(sku);

            variant.setReservedQuantity(variant.getReservedQuantity() + need);
            variant.setUpdatedAt(LocalDateTime.now());
            variantRepository.save(variant);

            StockReservation reservation = StockReservation.builder()
                    .reservationKey(key)
                    .orderCode(request.getOrderCode())
                    .sku(sku)
                    .quantity(need)
                    .status(ReservationStatus.ACTIVE)
                    .build();
            saved.add(reservationRepository.save(reservation));

            recordMovement(variant, StockMovementType.RESERVE, 0, need,
                    request.getOrderCode(), actor(request.getActor()),
                    "Giữ tồn kho cho đơn " + nullSafe(request.getOrderCode()));
        }

        log.info("Reserved {} SKU(s) for key={} order={}", saved.size(), key, request.getOrderCode());
        return StockDtos.ReservationResult.builder()
                .reservationKey(key)
                .status(ReservationStatus.ACTIVE.name())
                .replayed(false)
                .lines(request.getLines())
                .build();
    }

    @Override
    @Transactional
    public StockDtos.ReservationResult release(StockDtos.ReleaseRequest request) {
        String key = requireKey(request.getReservationKey());
        List<StockReservation> holds = reservationRepository.findByReservationKeyAndStatus(
                key, ReservationStatus.ACTIVE);
        // Multi-SKU holds are stored with a ":sku" suffix, so also look up by prefix.
        if (holds.isEmpty()) {
            holds = reservationRepository.findByOrderCodeAndStatus(key, ReservationStatus.ACTIVE);
        }

        if (holds.isEmpty()) {
            // Releasing something already released/committed is a successful no-op.
            log.info("Release for key={} found no active hold; treating as already released", key);
            return StockDtos.ReservationResult.builder()
                    .reservationKey(key)
                    .status(ReservationStatus.RELEASED.name())
                    .replayed(true)
                    .lines(List.of())
                    .build();
        }

        List<String> skus = holds.stream().map(StockReservation::getSku).sorted().collect(Collectors.toList());
        Map<String, ProductVariant> bySku = variantRepository.findAllBySkuInForUpdate(skus).stream()
                .collect(Collectors.toMap(ProductVariant::getSku, v -> v));

        for (StockReservation hold : holds) {
            ProductVariant variant = bySku.get(hold.getSku());
            if (variant != null) {
                int newReserved = Math.max(0, variant.getReservedQuantity() - hold.getQuantity());
                variant.setReservedQuantity(newReserved);
                variant.setUpdatedAt(LocalDateTime.now());
                variantRepository.save(variant);
                recordMovement(variant, StockMovementType.RELEASE, 0, -hold.getQuantity(),
                        hold.getOrderCode(), actor(request.getActor()),
                        request.getReason() != null ? request.getReason() : "Hủy giữ tồn kho");
            }
            hold.setStatus(ReservationStatus.RELEASED);
            hold.setUpdatedAt(LocalDateTime.now());
            reservationRepository.save(hold);
        }

        log.info("Released {} hold(s) for key={}", holds.size(), key);
        return StockDtos.ReservationResult.builder()
                .reservationKey(key)
                .status(ReservationStatus.RELEASED.name())
                .replayed(false)
                .lines(holds.stream()
                        .map(h -> StockDtos.ReservationLine.builder()
                                .sku(h.getSku()).quantity(h.getQuantity()).build())
                        .collect(Collectors.toList()))
                .build();
    }

    @Override
    @Transactional
    public StockDtos.ReservationResult commit(StockDtos.CommitRequest request) {
        String key = requireKey(request.getReservationKey());
        List<StockReservation> holds = reservationRepository.findByReservationKeyAndStatus(
                key, ReservationStatus.ACTIVE);
        if (holds.isEmpty()) {
            holds = reservationRepository.findByOrderCodeAndStatus(key, ReservationStatus.ACTIVE);
        }

        if (holds.isEmpty()) {
            // Already committed: replay, do not deduct twice (BR-15 analogue for stock).
            log.info("Commit for key={} found no active hold; treating as already committed", key);
            return StockDtos.ReservationResult.builder()
                    .reservationKey(key)
                    .status(ReservationStatus.COMMITTED.name())
                    .replayed(true)
                    .lines(List.of())
                    .build();
        }

        List<String> skus = holds.stream().map(StockReservation::getSku).sorted().collect(Collectors.toList());
        Map<String, ProductVariant> bySku = variantRepository.findAllBySkuInForUpdate(skus).stream()
                .collect(Collectors.toMap(ProductVariant::getSku, v -> v));

        // Validate before mutating: actual stock must cover the commitment.
        for (StockReservation hold : holds) {
            ProductVariant variant = bySku.get(hold.getSku());
            if (variant == null) {
                throw ApiException.of(ErrorCode.VARIANT_NOT_FOUND, "Không tìm thấy SKU: " + hold.getSku());
            }
            if (variant.getStockQuantity() < hold.getQuantity()) {
                throw ApiException.of(ErrorCode.INSUFFICIENT_STOCK,
                        String.format("SKU %s không đủ tồn kho thực tế để xuất kho", hold.getSku()));
            }
        }

        for (StockReservation hold : holds) {
            ProductVariant variant = bySku.get(hold.getSku());
            variant.setStockQuantity(variant.getStockQuantity() - hold.getQuantity());
            variant.setReservedQuantity(Math.max(0, variant.getReservedQuantity() - hold.getQuantity()));
            variant.setUpdatedAt(LocalDateTime.now());
            variantRepository.save(variant);

            hold.setStatus(ReservationStatus.COMMITTED);
            hold.setUpdatedAt(LocalDateTime.now());
            reservationRepository.save(hold);

            syncLocationBalanceForOnlineCommit(hold.getSku(), hold.getQuantity(), hold.getOrderCode());

            recordMovement(variant, StockMovementType.COMMIT, -hold.getQuantity(), -hold.getQuantity(),
                    hold.getOrderCode(), actor(request.getActor()), "Xuất kho bán hàng");
        }

        log.info("Committed {} hold(s) for key={}", holds.size(), key);
        return StockDtos.ReservationResult.builder()
                .reservationKey(key)
                .status(ReservationStatus.COMMITTED.name())
                .replayed(false)
                .lines(holds.stream()
                        .map(h -> StockDtos.ReservationLine.builder()
                                .sku(h.getSku()).quantity(h.getQuantity()).build())
                        .collect(Collectors.toList()))
                .build();
    }

    @Override
    @Transactional
    public void adjust(String sku, int delta, String actor, String reason) {
        if (reason == null || reason.isBlank()) {
            // BR-12: an adjustment without a reason is not auditable, so refuse it.
            throw ApiException.of(ErrorCode.VALIDATION_ERROR,
                    "Điều chỉnh tồn kho bắt buộc phải có lý do");
        }
        ProductVariant variant = variantRepository.findAllBySkuInForUpdate(List.of(sku)).stream()
                .findFirst()
                .orElseThrow(() -> ApiException.of(ErrorCode.VARIANT_NOT_FOUND, "Không tìm thấy SKU: " + sku));

        int updated = variant.getStockQuantity() + delta;
        if (updated < 0) {
            throw ApiException.of(ErrorCode.INSUFFICIENT_STOCK,
                    "Tồn kho thực tế không thể âm (EX-INV-01)");
        }
        variant.setStockQuantity(updated);
        variant.setUpdatedAt(LocalDateTime.now());
        variantRepository.save(variant);

        StockMovementType type = delta < 0 ? StockMovementType.ADJUST : StockMovementType.ADJUST;
        recordMovement(variant, type, delta, 0, null, actor(actor), reason);
    }

    // ------------------------------------------------------------------ helpers

    private StockDtos.ReservationResult replay(StockReservation existing, StockDtos.ReserveRequest request,
                                               String key) {
        log.info("Reserve replayed for key={} (no new hold created)", key);
        return StockDtos.ReservationResult.builder()
                .reservationKey(key)
                .status(existing.getStatus().name())
                .replayed(true)
                .lines(request.getLines())
                .build();
    }

    /** Merges duplicate SKUs so the same row is never written twice in one transaction. */
    private Map<String, Integer> aggregate(List<StockDtos.ReservationLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw ApiException.of(ErrorCode.CART_EMPTY, "Không có sản phẩm nào để giữ tồn kho");
        }
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (StockDtos.ReservationLine line : lines) {
            String sku = line.getSku() == null ? "" : line.getSku().trim();
            if (sku.isEmpty()) {
                throw ApiException.of(ErrorCode.VALIDATION_ERROR, "SKU không được để trống");
            }
            int qty = line.getQuantity() == null ? 0 : line.getQuantity();
            if (qty <= 0) {
                throw ApiException.of(ErrorCode.INVALID_QUANTITY,
                        "Số lượng phải lớn hơn 0 cho SKU " + sku);
            }
            merged.merge(sku, qty, Integer::sum);
        }
        return merged;
    }

    /** BR-04 / BR-09: discontinued products and inactive SKUs cannot be sold. */
    private void requireSellable(ProductVariant variant) {
        if (variant.getProduct() != null && "DISCONTINUED".equalsIgnoreCase(variant.getProduct().getStatus())) {
            throw ApiException.of(ErrorCode.PRODUCT_DISCONTINUED,
                    "Sản phẩm của SKU " + variant.getSku() + " đã ngừng kinh doanh");
        }
        if (variant.getStatus() != null && !"ACTIVE".equalsIgnoreCase(variant.getStatus())) {
            throw ApiException.of(ErrorCode.VARIANT_INACTIVE,
                    "SKU " + variant.getSku() + " hiện không được bán");
        }
    }

    private void syncLocationBalanceForOnlineCommit(String sku, int quantity, String orderCode) {
        if (balanceRepository == null) return;
        for (String loc : List.of("LAB", "BRANCH_01", "BRANCH_02")) {
            int updated = balanceRepository.decrementIfAvailable(sku, loc, quantity);
            if (updated == 1) {
                if (inventoryMovementRepository != null) {
                    inventoryMovementRepository.save(InventoryMovement.builder()
                            .movementType("SALE")
                            .sku(sku)
                            .sourceLocationCode(loc)
                            .referenceType("ONLINE_ORDER")
                            .referenceId(orderCode != null ? orderCode : "ONLINE")
                            .quantity(quantity)
                            .performedBy("ORDER_SERVICE")
                            .build());
                }
                return;
            }
        }
    }

    private void recordMovement(ProductVariant variant, StockMovementType type, int stockDelta,
                                int reservedDelta, String reference, String actor, String reason) {
        movementRepository.save(StockMovement.builder()
                .sku(variant.getSku())
                .movementType(type)
                .quantityDelta(type == StockMovementType.RESERVE || type == StockMovementType.RELEASE
                        ? reservedDelta : stockDelta)
                .availableAfter(variant.getAvailableQuantity())
                .reference(reference)
                .actor(actor)
                .reason(reason)
                .build());
    }

    private String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "reservationKey không được để trống");
        }
        return key.trim();
    }

    private String actor(String actor) {
        return actor == null || actor.isBlank() ? SYSTEM_ACTOR : actor;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}