package com.glassstore.lab.service;

import com.glassstore.common.dto.ApiResponse;
import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.lab.client.WarrantyCatalogClient;
import com.glassstore.lab.client.WarrantyOrderClient;
import com.glassstore.lab.dto.CatalogVariantInfo;
import com.glassstore.lab.dto.WarrantyActivationRequest;
import com.glassstore.lab.dto.WarrantyLookupItemDTO;
import com.glassstore.lab.dto.WarrantyOrderItemDTO;
import com.glassstore.lab.entity.Warranty;
import com.glassstore.lab.entity.WarrantyPolicy;
import com.glassstore.lab.repository.WarrantyPolicyRepository;
import com.glassstore.lab.repository.WarrantyRepository;
import feign.FeignException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class WarrantyService {

    private final WarrantyRepository warrantyRepository;
    private final WarrantyOrderClient orderClient;
    private final WarrantyCatalogClient catalogClient;
    private final WarrantyPolicyRepository policyRepository;

    public WarrantyService(
            WarrantyRepository warrantyRepository,
            WarrantyOrderClient orderClient,
            WarrantyCatalogClient catalogClient) {
        this(warrantyRepository, orderClient, catalogClient, null);
    }

    @Autowired
    public WarrantyService(
            WarrantyRepository warrantyRepository,
            WarrantyOrderClient orderClient,
            WarrantyCatalogClient catalogClient,
            WarrantyPolicyRepository policyRepository) {
        this.warrantyRepository = warrantyRepository;
        this.orderClient = orderClient;
        this.catalogClient = catalogClient;
        this.policyRepository = policyRepository;
    }

    @Transactional
    public Warranty activate(WarrantyActivationRequest request) {
        WarrantyOrderItemDTO item = getPurchaseItem(request.orderId(), request.orderItemId());
        if (!isEligibleForBenefit(item.productSku(), request.benefitType())) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR,
                    "Sản phẩm không đủ điều kiện cho quyền lợi bảo hành này");
        }
        if (warrantyRepository.findByOrderItemIdAndBenefitType(item.orderItemId(), request.benefitType()).isPresent()) {
            return warrantyRepository.findByOrderItemIdAndBenefitType(item.orderItemId(), request.benefitType())
                    .orElseThrow();
        }

        CatalogVariantInfo variant = getVariantBySku(item.productSku());
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime purchaseDate = item.purchaseDate() == null ? now : item.purchaseDate();
        WarrantyPolicy activePolicy = resolvePolicy(request.benefitType());

        Integer durationDays = activePolicy.getDurationDays();
        LocalDateTime expiresAt = (durationDays != null && durationDays > 0)
                ? purchaseDate.plusDays(durationDays)
                : null;
        String status = expiresAt != null && expiresAt.isBefore(now) ? "EXPIRED" : "ACTIVE";
        String statusLabel = "EXPIRED".equals(status) ? "Đã hết hạn" : "Đang hiệu lực";
        String policy = activePolicy.getVersion() != null && activePolicy.getVersion() > 1
                ? activePolicy.getPolicyCode() + "_V" + activePolicy.getVersion()
                : activePolicy.getPolicyCode();
        String coverageTerms = activePolicy.getCoverageTerms();

        warrantyRepository.insertIfMissing(
                "WAR-" + UUID.randomUUID(),
                item.orderCode(),
                item.orderId(),
                item.orderItemId(),
                request.benefitType(),
                now,
                expiresAt,
                variant == null ? null : variant.barcode(),
                item.customerId(),
                item.customerName(),
                item.customerPhone(),
                item.productName(),
                item.productSku(),
                coverageTerms,
                policy,
                status,
                statusLabel,
                now,
                now);
        return warrantyRepository.findByOrderItemIdAndBenefitType(item.orderItemId(), request.benefitType())
                .orElseThrow(() -> new IllegalStateException("Không thể đọc lại bản ghi bảo hành vừa tạo"));
    }

    @Transactional(readOnly = true)
    public List<WarrantyPolicy> getActivePolicies() {
        List<WarrantyPolicy> defaults = List.of(
                defaultPolicy("EXCHANGE_30_DAYS"),
                defaultPolicy("LENS_COATING"),
                defaultPolicy("FRAME_ADJUSTMENT")
        );
        if (policyRepository == null) {
            return defaults;
        }
        return defaults.stream()
                .map(def -> policyRepository.findFirstByBenefitTypeAndActiveTrueOrderByVersionDesc(def.getBenefitType())
                        .orElse(def))
                .toList();
    }

    @Transactional
    public WarrantyPolicy savePolicyVersion(
            String benefitType,
            Integer durationDays,
            String coverageTerms,
            BigDecimal serviceFee,
            String actor) {
        if (policyRepository == null) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Chưa cấu hình kho chính sách bảo hành");
        }
        if (benefitType == null || benefitType.isBlank()) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Loại quyền lợi bảo hành không được để trống");
        }
        String normalizedBenefit = benefitType.trim().toUpperCase(Locale.ROOT);
        if (coverageTerms == null || coverageTerms.isBlank()) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Điều kiện bảo hành không được để trống");
        }
        if (durationDays != null && durationDays < 0) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Thời hạn bảo hành (ngày) không được âm");
        }

        WarrantyPolicy current = resolvePolicy(normalizedBenefit);
        int nextVersion = 1;
        Optional<WarrantyPolicy> existingActive = policyRepository
                .findFirstByBenefitTypeAndActiveTrueOrderByVersionDesc(normalizedBenefit);
        if (existingActive.isPresent()) {
            WarrantyPolicy prev = existingActive.get();
            nextVersion = (prev.getVersion() != null ? prev.getVersion() : 1) + 1;
            prev.setActive(false);
            policyRepository.save(prev);
        }

        LocalDateTime now = LocalDateTime.now();
        WarrantyPolicy created = WarrantyPolicy.builder()
                .policyCode(current.getPolicyCode())
                .benefitType(normalizedBenefit)
                .version(nextVersion)
                .durationDays(durationDays)
                .coverageTerms(coverageTerms.trim())
                .eligibleSkuPrefixes(current.getEligibleSkuPrefixes())
                .serviceFee(serviceFee != null ? serviceFee : BigDecimal.ZERO)
                .effectiveFrom(now)
                .active(true)
                .updatedBy(actor == null || actor.isBlank() ? "ADMIN" : actor)
                .createdAt(now)
                .build();
        return policyRepository.save(created);
    }

    private WarrantyPolicy resolvePolicy(String benefitType) {
        String normalized = benefitType == null ? "" : benefitType.trim().toUpperCase(Locale.ROOT);
        if (policyRepository != null) {
            Optional<WarrantyPolicy> configured = policyRepository
                    .findFirstByBenefitTypeAndActiveTrueOrderByVersionDesc(normalized);
            if (configured.isPresent()) {
                return configured.get();
            }
        }
        return defaultPolicy(normalized);
    }

    private WarrantyPolicy defaultPolicy(String benefitType) {
        boolean lensCoating = "LENS_COATING".equals(benefitType);
        boolean exchange = "EXCHANGE_30_DAYS".equals(benefitType);
        return WarrantyPolicy.builder()
                .policyCode(exchange ? "EXCHANGE_30_DAYS" : lensCoating ? "LENS_COATING_30_DAYS" : "FRAME_ADJUSTMENT_LIFETIME")
                .benefitType(benefitType)
                .version(1)
                .durationDays(lensCoating || exchange ? 30 : null)
                .coverageTerms(exchange
                        ? "Đổi 1–1 trong 30 ngày từ ngày mua, sau khi kiểm tra điều kiện bảo hành"
                        : lensCoating
                        ? "Thay thế một lần khi lớp phủ tròng kính bong/tróc do lỗi thuộc phạm vi bảo hành"
                        : "Hỗ trợ điều chỉnh gọng kính trọn đời")
                .eligibleSkuPrefixes(exchange ? "FRM-,LNS-,LZ-" : lensCoating ? "LNS-,LZ-" : "FRM-")
                .serviceFee(BigDecimal.ZERO)
                .active(true)
                .build();
    }

    @Transactional(readOnly = true)
    public List<WarrantyLookupItemDTO> lookupByPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Cần nhập số điện thoại khách hàng");
        }
        String clean = phone.trim();
        List<WarrantyOrderItemDTO> purchases = List.of();
        try {
            List<WarrantyOrderItemDTO> fetched = orderClient.findPurchases(clean, null);
            if (fetched != null) purchases = fetched;
        } catch (Exception ignored) {}
        List<WarrantyLookupItemDTO> items = toLookupItems(purchases);
        if (!items.isEmpty()) {
            return items;
        }
        return fallbackFromWarrantyRecords(clean);
    }

    @Transactional(readOnly = true)
    public List<WarrantyLookupItemDTO> lookupByBarcode(String barcode) {
        if (barcode == null || barcode.isBlank()) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Cần nhập barcode sản phẩm");
        }
        String clean = barcode.trim();
        CatalogVariantInfo variant = getVariantByBarcode(clean);
        if (variant == null) {
            variant = getVariantBySku(clean);
        }
        String skuQuery = (variant != null && variant.sku() != null) ? variant.sku() : clean;
        List<WarrantyOrderItemDTO> purchases = List.of();
        try {
            List<WarrantyOrderItemDTO> fetched = orderClient.findPurchases(null, skuQuery);
            if (fetched != null) purchases = fetched;
        } catch (Exception ignored) {}
        List<WarrantyLookupItemDTO> items = toLookupItems(purchases);
        if (!items.isEmpty()) {
            return items;
        }
        return fallbackFromWarrantyRecords(clean);
    }

    private List<WarrantyLookupItemDTO> fallbackFromWarrantyRecords(String query) {
        if (warrantyRepository == null) return List.of();
        List<Warranty> all = warrantyRepository.findAllByOrderByCreatedAtDesc();
        if (all == null || all.isEmpty()) return List.of();
        String qLower = query.toLowerCase();
        String qDigits = query.replaceAll("[^0-9]", "");
        boolean isAll = "ALL".equalsIgnoreCase(query);
        List<Warranty> matched = all.stream().filter(w -> {
            if (isAll) return true;
            String wDigits = w.getCustomerPhone() == null ? "" : w.getCustomerPhone().replaceAll("[^0-9]", "");
            if (!qDigits.isEmpty() && !wDigits.isEmpty()) {
                if (wDigits.contains(qDigits) || qDigits.contains(wDigits)) return true;
                if (wDigits.length() >= 8 && qDigits.length() >= 8 && Math.abs(wDigits.length() - qDigits.length()) <= 1) {
                    int prefix = Math.min(6, Math.min(wDigits.length(), qDigits.length()));
                    if (wDigits.substring(0, prefix).equals(qDigits.substring(0, prefix))) {
                        int diffs = 0;
                        int minLen = Math.min(wDigits.length(), qDigits.length());
                        for (int i = 0; i < minLen; i++) {
                            if (wDigits.charAt(i) != qDigits.charAt(i)) diffs++;
                        }
                        if (diffs + Math.abs(wDigits.length() - qDigits.length()) <= 2) return true;
                    }
                }
            }
            return (w.getCustomerName() != null && w.getCustomerName().toLowerCase().contains(qLower))
                    || (w.getOriginalOrderCode() != null && w.getOriginalOrderCode().toLowerCase().contains(qLower))
                    || (w.getWarrantyCode() != null && w.getWarrantyCode().toLowerCase().contains(qLower))
                    || (w.getProductBarcode() != null && w.getProductBarcode().toLowerCase().contains(qLower))
                    || (w.getProductSku() != null && w.getProductSku().toLowerCase().contains(qLower))
                    || (w.getProductName() != null && w.getProductName().toLowerCase().contains(qLower));
        }).toList();

        return matched.stream().map(w -> new WarrantyLookupItemDTO(
                w.getOrderId() != null ? w.getOrderId() : w.getId(),
                w.getOrderItemId() != null ? w.getOrderItemId() : w.getId(),
                w.getOriginalOrderCode() != null ? w.getOriginalOrderCode() : w.getWarrantyCode(),
                w.getActivatedAt() != null ? w.getActivatedAt() : w.getCreatedAt(),
                w.getCustomerName(),
                w.getCustomerPhone(),
                w.getProductName(),
                w.getProductSku(),
                w.getProductBarcode(),
                w.getBenefitType() != null ? w.getBenefitType() : "FRAME_ADJUSTMENT",
                isExpired(w) ? "EXPIRED" : "ACTIVE",
                1,
                null,
                List.of(w)
        )).toList();
    }

    private WarrantyOrderItemDTO getPurchaseItem(Long orderId, Long orderItemId) {
        try {
            WarrantyOrderItemDTO item = orderClient.getPurchaseItem(orderId, orderItemId);
            if (item == null || !orderId.equals(item.orderId()) || !orderItemId.equals(item.orderItemId())) {
                throw ApiException.of(ErrorCode.VALIDATION_ERROR,
                        "Sản phẩm không thuộc đơn hàng đã chỉ định");
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

    private List<WarrantyLookupItemDTO> toLookupItems(List<WarrantyOrderItemDTO> purchases) {
        return purchases.stream().map(item -> {
            List<Warranty> warranties = warrantyRepository.findByOrderItemIdOrderByCreatedAtDesc(item.orderItemId());
            CatalogVariantInfo variant = getVariantBySku(item.productSku());
            String activationStatus = warranties.isEmpty()
                    ? item.activationStatus()
                    : warranties.stream().anyMatch(warranty -> isExpired(warranty)) ? "EXPIRED" : "ACTIVE";
            return new WarrantyLookupItemDTO(
                    item.orderId(),
                    item.orderItemId(),
                    item.orderCode(),
                    item.purchaseDate(),
                    item.customerName(),
                    item.customerPhone(),
                    item.productName(),
                    item.productSku(),
                    variant == null ? null : variant.barcode(),
                    item.benefitType(),
                    activationStatus,
                    item.activationAttempts(),
                    item.activationError(),
                    warranties);
        }).toList();
    }

    private CatalogVariantInfo getVariantByBarcode(String barcode) {
        try {
            ApiResponse<CatalogVariantInfo> response = catalogClient.findByBarcode(barcode);
            return response == null ? null : response.getData();
        } catch (FeignException.NotFound exception) {
            return null;
        } catch (FeignException exception) {
            throw ApiException.of(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Không thể xác thực barcode với catalog service");
        }
    }

    private CatalogVariantInfo getVariantBySku(String sku) {
        try {
            ApiResponse<CatalogVariantInfo> response = catalogClient.findBySku(sku);
            return response == null ? null : response.getData();
        } catch (FeignException.NotFound exception) {
            return null;
        } catch (FeignException exception) {
            throw ApiException.of(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Không thể tra cứu sản phẩm trong catalog service");
        }
    }

    private boolean isEligibleForBenefit(String sku, String benefitType) {
        if (sku == null || benefitType == null) {
            return false;
        }
        String normalized = sku.trim().toUpperCase();
        return ("EXCHANGE_30_DAYS".equals(benefitType) && (normalized.startsWith("FRM-") || normalized.startsWith("LNS-") || normalized.startsWith("LZ-")))
                || ("FRAME_ADJUSTMENT".equals(benefitType) && normalized.startsWith("FRM-"))
                || ("LENS_COATING".equals(benefitType)
                && (normalized.startsWith("LNS-") || normalized.startsWith("LZ-")));
    }

    private boolean isExpired(Warranty warranty) {
        return warranty.getExpiresAt() != null && warranty.getExpiresAt().isBefore(LocalDateTime.now());
    }
}
