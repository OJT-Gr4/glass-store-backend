package com.glassstore.order.service;

import com.glassstore.order.entity.*;
import com.glassstore.order.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.*;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
public class PromotionService {
    private final VoucherRepository vouchers;
    private final LoyaltyAccountRepository accounts;
    private final LoyaltyEntryRepository entries;
    private final VoucherRedemptionRepository redemptions;
    private final PosDataService data;
    private final LoyaltyPolicy policy;
    public record Quote(BigDecimal subtotal, String voucherCode, BigDecimal voucherDiscount, long redeemedPoints,
                        BigDecimal pointsDiscount, BigDecimal taxableAmount, BigDecimal vatAmount, BigDecimal finalAmount,
                        long pointsBalance, long maximumPoints, BigDecimal pointValue) {}
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

    @Transactional
    public Quote calculate(Order order, String token, boolean consumeLocks) {
        data.actor(token, false);
        if (order.getItems() == null || order.getItems().isEmpty()) throw invalid("Giỏ hàng đang trống.");
        if (order.getCustomerId() != null) data.customer(token, order.getCustomerId());
        Map<String, PosDataService.CatalogItem> catalog = data.catalogItems();
        Set<Long> prescriptions = new HashSet<>();
        if (order.getPrescriptionId() != null) prescriptions.add(order.getPrescriptionId());
        for (OrderItem item : order.getItems()) if (item != null && item.getPrescriptionId() != null) prescriptions.add(item.getPrescriptionId());
        if (prescriptions.size() > 1) throw invalid("Mỗi đơn POS chỉ được liên kết một toa kính.");
        Long prescriptionId = prescriptions.stream().findFirst().orElse(null);
        if (prescriptionId != null) {
            var verified = data.requirePrescription(prescriptionId, order.getCustomerId(), token);
            if (consumeLocks) {
                Map<String, Object> values = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(verified, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                order.setPrescription(ClinicalPrescriptionSnapshot.from(values, prescriptionId, order.getCustomerId()));
            }
        }
        if (consumeLocks && order.getOrderType() != null && order.getOrderType().requiresPrescription() && prescriptionId == null)
            throw invalid("Đơn kính thuốc cần liên kết toa kính của khách.");
        boolean hasPrescriptionLens = false;
        BigDecimal subtotal = BigDecimal.ZERO;
        for (OrderItem item : order.getItems()) {
            if (item == null || item.getQuantity() == null || item.getQuantity() < 1 || item.getQuantity() > 10000 || !catalog.containsKey(item.getSkuId())) throw invalid("SKU hoặc số lượng không hợp lệ.");
            PosDataService.CatalogItem product = catalog.get(item.getSkuId());
            if (!product.sellable()) throw invalid("Sản phẩm " + item.getSkuId() + " hiện không được bán.");
            BigDecimal price = product.price();
            if (price.signum() < 0) throw invalid("Đơn giá không hợp lệ.");
            BigDecimal expected = item.getExpectedUnitPrice() != null ? item.getExpectedUnitPrice() : item.getUnitPrice();
            if (consumeLocks && (expected == null || expected.compareTo(price) != 0))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Giá SKU " + item.getSkuId() + " đã thay đổi hoặc không hợp lệ. Tải lại giá và xác nhận trước khi thanh toán.");
            if (item.getPrescriptionId() != null && !"LENS".equals(product.productType()))
                throw invalid("Chỉ dòng tròng kính cần gia công được gắn toa kính.");
            if (prescriptionId != null && "LENS".equals(product.productType())) {
                item.setPrescriptionId(prescriptionId);
                hasPrescriptionLens = true;
            }
            item.setProductName(product.name());
            item.setUnitPrice(price); item.setSubtotal(price.multiply(BigDecimal.valueOf(item.getQuantity())));
            subtotal = subtotal.add(item.getSubtotal());
        }
        if (prescriptionId != null && !hasPrescriptionLens) throw invalid("Đơn có toa kính phải có dòng tròng kính cần gia công.");
        order.setPrescriptionId(prescriptionId);
        String code = order.getVoucherCode() == null ? "" : order.getVoucherCode().trim().toUpperCase(Locale.ROOT);
        Voucher voucher = null;
        BigDecimal voucherDiscount = BigDecimal.ZERO;
        if (!code.isEmpty()) {
            voucher = (consumeLocks ? vouchers.lockByCode(code) : vouchers.findByCode(code)).orElseThrow(() -> invalid("Mã voucher không tồn tại."));
            if (voucher.getAmount() == null || voucher.getAmount().signum() <= 0
                    || "PERCENT".equals(voucher.getType()) && voucher.getAmount().compareTo(new BigDecimal("100")) > 0
                    || voucher.getMinimumAmount().signum() < 0
                    || voucher.getMaximumDiscount() != null && voucher.getMaximumDiscount().signum() < 0)
                throw invalid("Cấu hình voucher không hợp lệ, vui lòng liên hệ quản lý.");
            LocalDateTime now = LocalDateTime.now();
            if (voucher.getChannel() != null && !Set.of("ALL", "POS").contains(voucher.getChannel().toUpperCase(Locale.ROOT))) throw invalid("Voucher không áp dụng tại quầy POS.");
            if (!voucher.isActive() || voucher.getStartsAt() != null && now.isBefore(voucher.getStartsAt()) || voucher.getExpiresAt() != null && !now.isBefore(voucher.getExpiresAt())) throw invalid("Voucher chưa có hiệu lực hoặc đã hết hạn.");
            if (voucher.isMembersOnly() && order.getCustomerId() == null) throw invalid("Voucher chỉ dành cho thành viên.");
            if (voucher.getUsageLimit() != null && voucher.getUsedCount() >= voucher.getUsageLimit()) throw invalid("Voucher đã hết lượt sử dụng.");
            if (voucher.getPerCustomerLimit() != null && (order.getCustomerId() == null || redemptions.countByVoucherIdAndCustomerIdAndReversedFalse(voucher.getId(), order.getCustomerId()) >= voucher.getPerCustomerLimit())) throw invalid("Voucher giới hạn lượt dùng theo thành viên.");
            if (subtotal.compareTo(voucher.getMinimumAmount()) < 0) throw invalid("Đơn hàng chưa đạt giá trị tối thiểu của voucher.");
            if ("PERCENT".equals(voucher.getType())) voucherDiscount = subtotal.multiply(voucher.getAmount()).divide(new BigDecimal("100"), 0, RoundingMode.HALF_UP);
            else if ("FIXED".equals(voucher.getType())) voucherDiscount = voucher.getAmount();
            else throw invalid("Loại voucher không hợp lệ.");
            if (voucher.getMaximumDiscount() != null) voucherDiscount = voucherDiscount.min(voucher.getMaximumDiscount());
            voucherDiscount = voucherDiscount.min(subtotal).setScale(0, RoundingMode.DOWN);
        }
        long points = order.getPointsToUse() == null ? 0 : order.getPointsToUse();
        if (points < 0) throw invalid("Số điểm không hợp lệ.");
        LoyaltyAccount account = order.getCustomerId() == null ? null : (consumeLocks ? accounts.lockByCustomerId(order.getCustomerId()) : accounts.findById(order.getCustomerId())).orElse(null);
        long balance = account == null ? 0 : account.getBalance();
        BigDecimal remaining = subtotal.subtract(voucherDiscount);
        if (policy.getPointValue().signum() <= 0 || policy.getMaxRedemptionPercent() < 0 || policy.getMaxRedemptionPercent() > 100) throw invalid("Cấu hình điểm chưa hợp lệ.");
        boolean canUsePoints = policy.isEnabled() && order.getCustomerId() != null && (voucher == null || policy.isAllowVoucherStack() && voucher.isStackPoints());
        long maximum = canUsePoints ? remaining.multiply(BigDecimal.valueOf(policy.getMaxRedemptionPercent())).divide(new BigDecimal("100")).divide(policy.getPointValue(), 0, RoundingMode.DOWN).min(BigDecimal.valueOf(balance)).longValueExact() : 0;
        if (points > maximum) throw invalid(order.getCustomerId() == null ? "Khách vãng lai không được dùng điểm." : "Số điểm vượt số dư hoặc giới hạn cho phép.");
        BigDecimal pointsDiscount = policy.getPointValue().multiply(BigDecimal.valueOf(points));
        BigDecimal taxable = remaining.subtract(pointsDiscount);
        BigDecimal tax = taxable.multiply(new BigDecimal("0.08")).setScale(0, RoundingMode.HALF_UP);
        order.setVoucherId(voucher == null ? null : voucher.getId()); order.setVoucherCode(code.isEmpty() ? null : code);
        order.setVoucherDiscount(voucherDiscount); order.setRedeemedPoints(points); order.setPointsDiscount(pointsDiscount); order.setPointUnitValue(policy.getPointValue());
        order.setTotalAmount(subtotal); order.setDiscountAmount(voucherDiscount.add(pointsDiscount)); order.setTaxAmount(tax); order.setFinalAmount(taxable.add(tax));
        return new Quote(subtotal, order.getVoucherCode(), voucherDiscount, points, pointsDiscount, taxable, tax, taxable.add(tax), balance, maximum, policy.getPointValue());
    }

    public void consume(Order order) {
        if (order.getVoucherCode() != null) {
            Voucher voucher = vouchers.lockByCode(order.getVoucherCode()).orElseThrow();
            voucher.setUsedCount(voucher.getUsedCount()+1); vouchers.save(voucher);
            VoucherRedemption redemption = new VoucherRedemption(); redemption.setVoucherId(voucher.getId()); redemption.setCustomerId(order.getCustomerId()); redemption.setOrderId(order.getId()); redemptions.save(redemption);
        }
        if (order.getRedeemedPoints() != null && order.getRedeemedPoints() > 0) {
            LoyaltyAccount account = accounts.lockByCustomerId(order.getCustomerId()).orElseThrow();
            account.setBalance(account.getBalance()-order.getRedeemedPoints()); accounts.save(account);
            LoyaltyEntry entry = new LoyaltyEntry(); entry.setCustomerId(order.getCustomerId()); entry.setOrderId(order.getId()); entry.setEntryType("REDEEM"); entry.setPoints(-order.getRedeemedPoints()); entry.setReason("POS checkout"); entries.save(entry);
        }
    }

    public void reverse(Order order) {
        // Lock in the same voucher -> account order as checkout.
        if (order.getVoucherCode() != null) redemptions.findByOrderId(order.getId()).filter(r -> !r.isReversed()).ifPresent(redemption -> {
            Voucher voucher = vouchers.lockByCode(order.getVoucherCode()).orElseThrow(); voucher.setUsedCount(Math.max(0,voucher.getUsedCount()-1)); vouchers.save(voucher); redemption.setReversed(true); redemptions.save(redemption);
        });
        if (order.getRedeemedPoints() != null && order.getRedeemedPoints() > 0) {
            LoyaltyAccount account = accounts.lockByCustomerId(order.getCustomerId()).orElseThrow(); account.setBalance(Math.addExact(account.getBalance(),order.getRedeemedPoints())); accounts.save(account);
            LoyaltyEntry entry = new LoyaltyEntry(); entry.setCustomerId(order.getCustomerId()); entry.setOrderId(order.getId()); entry.setEntryType("CANCEL_REFUND"); entry.setPoints(order.getRedeemedPoints()); entry.setReason("POS cancellation"); entries.save(entry);
        }
    }
}
