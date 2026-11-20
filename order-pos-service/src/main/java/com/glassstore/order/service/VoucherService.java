package com.glassstore.order.service;

import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.order.domain.SalesChannel;
import com.glassstore.order.entity.Voucher;
import com.glassstore.order.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Evaluates a voucher against an order total using only the stored configuration
 * (BR-19, BR-20). No stacking and no usage counters (OQ-09).
 */
@Service
@RequiredArgsConstructor
public class VoucherService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    static final BigDecimal MAX_DISCOUNT = new BigDecimal("5000000");

    private final VoucherRepository voucherRepository;

    public List<Voucher> getAllVouchers() {
        return voucherRepository.findAll();
    }

    public Voucher createVoucher(Voucher voucher) {
        validate(voucher);
        voucher.setId(null);
        voucher.setUsedCount(0);
        voucher.setPerCustomerLimit(1);
        voucher.setRequiredTier(validateTier(voucher.getRequiredTier()));
        if (voucher.getCode() == null || voucher.getCode().isBlank()) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Mã giảm giá không được để trống");
        }
        String normalizedCode = voucher.getCode().trim().toUpperCase();
        if (voucherRepository.findByCodeIgnoreCase(normalizedCode).isPresent()) {
            throw ApiException.of(ErrorCode.CONFLICT, "Mã giảm giá '" + normalizedCode + "' đã tồn tại");
        }
        voucher.setCode(normalizedCode);
        if (voucher.getDiscountType() == null || voucher.getDiscountType().isBlank()) {
            voucher.setDiscountType("PERCENT");
        }
        if (voucher.getChannel() == null || voucher.getChannel().isBlank()) {
            voucher.setChannel("ALL");
        }
        return voucherRepository.save(voucher);
    }

    public Voucher updateVoucher(Long id, Voucher updated) {
        Voucher existing = voucherRepository.findById(id)
                .orElseThrow(() -> ApiException.of(ErrorCode.VOUCHER_NOT_FOUND, "Không tìm thấy voucher ID " + id));

        if (updated.getName() != null) existing.setName(updated.getName());
        if (updated.getRequiredTier() != null) existing.setRequiredTier(validateTier(updated.getRequiredTier()));
        if (updated.getDiscountType() != null) existing.setDiscountType(updated.getDiscountType());
        if (updated.getDiscountValue() != null) existing.setDiscountValue(updated.getDiscountValue());
        if (updated.getMinOrderAmount() != null) existing.setMinOrderAmount(updated.getMinOrderAmount());
        if (updated.getMaxDiscountAmount() != null) existing.setMaxDiscountAmount(updated.getMaxDiscountAmount());
        if (updated.getValidFrom() != null) existing.setValidFrom(updated.getValidFrom());
        if (updated.getValidTo() != null) existing.setValidTo(updated.getValidTo());
        if (updated.getChannel() != null) existing.setChannel(updated.getChannel());
        existing.setActive(updated.isActive());
        existing.setPerCustomerLimit(1);
        existing.setUsageLimit(updated.getUsageLimit());
        validate(existing);

        return voucherRepository.save(existing);
    }

    private String validateTier(String tier) {
        String normalized = tier == null ? "NONE" : tier.trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("NONE", "SILVER", "GOLD", "DIAMOND").contains(normalized)) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Hạng voucher không hợp lệ");
        }
        return normalized;
    }

    private void validate(Voucher voucher) {
        if ("FIXED".equals(voucher.getDiscountType()) && voucher.getDiscountValue() != null && voucher.getDiscountValue().compareTo(MAX_DISCOUNT) > 0
                || voucher.getMaxDiscountAmount() != null && voucher.getMaxDiscountAmount().compareTo(MAX_DISCOUNT) > 0)
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Mức giảm voucher tối đa là 5.000.000đ.");
        if (voucher.getName() == null || voucher.getName().isBlank() || voucher.getName().length() > 160
                || voucher.getCode() == null || !voucher.getCode().trim().matches("[A-Za-z0-9_-]{3,50}")
                || voucher.getDiscountValue() == null || voucher.getDiscountValue().signum() <= 0
                || !java.util.Set.of("PERCENT", "FIXED").contains(voucher.getDiscountType() == null ? "" : voucher.getDiscountType())
                || "PERCENT".equals(voucher.getDiscountType()) && voucher.getDiscountValue().compareTo(HUNDRED) > 0
                || voucher.getMinOrderAmount() != null && voucher.getMinOrderAmount().signum() < 0
                || voucher.getMaxDiscountAmount() != null && voucher.getMaxDiscountAmount().signum() < 0
                || voucher.getUsageLimit() != null && voucher.getUsageLimit() < 1
                || voucher.getValidFrom() != null && voucher.getValidTo() != null && !voucher.getValidFrom().isBefore(voucher.getValidTo())
                || voucher.getChannel() != null && !java.util.Set.of("ALL", "POS", "ONLINE").contains(voucher.getChannel()))
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Thông tin voucher không hợp lệ. Kiểm tra mã, mức giảm, thời hạn và kênh áp dụng.");
    }

    public Voucher toggleActive(Long id) {
        Voucher existing = voucherRepository.findById(id)
                .orElseThrow(() -> ApiException.of(ErrorCode.VOUCHER_NOT_FOUND, "Không tìm thấy voucher ID " + id));
        existing.setActive(!existing.isActive());
        return voucherRepository.save(existing);
    }

    public void deleteVoucher(Long id) {
        if (!voucherRepository.existsById(id)) {
            throw ApiException.of(ErrorCode.VOUCHER_NOT_FOUND, "Không tìm thấy voucher ID " + id);
        }
        Voucher existing = voucherRepository.findById(id).orElseThrow();
        existing.setActive(false);
        voucherRepository.save(existing);
    }

    public BigDecimal calculateDiscount(String voucherCode, BigDecimal total, SalesChannel channel) {
        if (voucherCode == null || voucherCode.isBlank()) {
            return ZERO;
        }
        Voucher voucher = voucherRepository.findByCodeIgnoreCase(voucherCode.trim())
                .orElseThrow(() -> ApiException.of(ErrorCode.VOUCHER_NOT_FOUND,
                        "Mã giảm giá '" + voucherCode.trim() + "' không tồn tại trên hệ thống"));
        return apply(voucher, total, channel);
    }

    public Voucher requireValid(String voucherCode, BigDecimal total, SalesChannel channel) {
        if (voucherCode == null || voucherCode.isBlank()) {
            return null;
        }
        Voucher voucher = voucherRepository.findByCodeIgnoreCase(voucherCode.trim())
                .orElseThrow(() -> ApiException.of(ErrorCode.VOUCHER_NOT_FOUND,
                        "Mã giảm giá '" + voucherCode.trim() + "' không tồn tại trên hệ thống"));
        apply(voucher, total, channel);
        return voucher;
    }

    BigDecimal apply(Voucher voucher, BigDecimal total, SalesChannel channel) {
        if (!voucher.isActive()) {
            throw ApiException.of(ErrorCode.VOUCHER_INACTIVE, "Mã giảm giá chưa được kích hoạt");
        }
        LocalDateTime now = LocalDateTime.now();
        if (voucher.getValidFrom() != null && now.isBefore(voucher.getValidFrom())) {
            throw ApiException.of(ErrorCode.VOUCHER_INACTIVE, "Mã giảm giá chưa đến thời gian hiệu lực");
        }
        if (voucher.getValidTo() != null && now.isAfter(voucher.getValidTo())) {
            throw ApiException.of(ErrorCode.VOUCHER_EXPIRED, "Mã giảm giá đã hết hạn");
        }
        if (voucher.getChannel() != null && !"ALL".equalsIgnoreCase(voucher.getChannel())) {
            if (channel != null && !voucher.getChannel().equalsIgnoreCase(channel.name())) {
                throw ApiException.of(ErrorCode.VOUCHER_NOT_APPLICABLE,
                        "Mã giảm giá không áp dụng cho kênh " + channel);
            }
        }
        BigDecimal orderTotal = total == null ? ZERO : total.setScale(2, RoundingMode.HALF_UP);
        if (voucher.getMinOrderAmount() != null && orderTotal.compareTo(voucher.getMinOrderAmount()) < 0) {
            throw ApiException.of(ErrorCode.VOUCHER_NOT_APPLICABLE,
                    "Đơn hàng chưa đạt giá trị tối thiểu để dùng mã giảm giá");
        }

        BigDecimal raw;
        if ("PERCENT".equalsIgnoreCase(voucher.getDiscountType())) {
            if(voucher.getDiscountValue().compareTo(HUNDRED)>0)
                throw ApiException.of(ErrorCode.VOUCHER_NOT_APPLICABLE,"Voucher phần trăm không được giảm quá 100%.");
            raw = orderTotal.multiply(voucher.getDiscountValue())
                    .divide(HUNDRED, 2, RoundingMode.HALF_UP);
        } else if ("FIXED".equalsIgnoreCase(voucher.getDiscountType())) {
            raw = voucher.getDiscountValue().setScale(2, RoundingMode.HALF_UP);
        } else {
            throw ApiException.of(ErrorCode.VOUCHER_NOT_APPLICABLE, "Loại giảm giá không được hỗ trợ");
        }

        if (voucher.getMaxDiscountAmount() != null && raw.compareTo(voucher.getMaxDiscountAmount()) > 0) {
            raw = voucher.getMaxDiscountAmount().setScale(2, RoundingMode.HALF_UP);
        }
        if (raw.compareTo(orderTotal) > 0) {
            raw = orderTotal;
        }
        raw = raw.min(MAX_DISCOUNT);
        return raw.max(ZERO).setScale(2, RoundingMode.HALF_UP);
    }
}
