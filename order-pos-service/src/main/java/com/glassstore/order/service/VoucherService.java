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

    private final VoucherRepository voucherRepository;

    public List<Voucher> getAllVouchers() {
        return voucherRepository.findAll();
    }

    public Voucher createVoucher(Voucher voucher) {
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
        if (updated.getDiscountType() != null) existing.setDiscountType(updated.getDiscountType());
        if (updated.getDiscountValue() != null) existing.setDiscountValue(updated.getDiscountValue());
        if (updated.getMinOrderAmount() != null) existing.setMinOrderAmount(updated.getMinOrderAmount());
        if (updated.getMaxDiscountAmount() != null) existing.setMaxDiscountAmount(updated.getMaxDiscountAmount());
        if (updated.getValidFrom() != null) existing.setValidFrom(updated.getValidFrom());
        if (updated.getValidTo() != null) existing.setValidTo(updated.getValidTo());
        if (updated.getChannel() != null) existing.setChannel(updated.getChannel());
        existing.setActive(updated.isActive());

        return voucherRepository.save(existing);
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
        voucherRepository.deleteById(id);
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
        return raw.max(ZERO).setScale(2, RoundingMode.HALF_UP);
    }
}
