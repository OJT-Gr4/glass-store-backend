package com.glassstore.order.service;

import com.glassstore.order.domain.SalesChannel;
import com.glassstore.order.entity.*;
import com.glassstore.order.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
public class VoucherEntitlementService {
    private final VoucherRepository vouchers;
    private final VoucherGrantRepository grants;
    private final LoyaltyAccountRepository accounts;
    private final PosDataService people;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entities;

    private Voucher lockedVoucher(String code) {
        Voucher voucher = vouchers.lockByCode(code.trim()).orElseThrow(() -> invalid("Mã voucher không tồn tại."));
        // A POS preview may have already loaded the row. Refresh after locking so MySQL's
        // repeatable-read snapshot cannot retain counters from before another checkout.
        entities.refresh(voucher, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return voucher;
    }

    public static String tier(long points) {
        return points >= 10000 ? "DIAMOND" : points >= 5000 ? "GOLD" : points >= 1000 ? "SILVER" : "MEMBER";
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private boolean matchesTier(Voucher voucher, Long customerId) {
        String required = voucher.getRequiredTier();
        return required == null || "NONE".equals(required) || required.equals(accounts.findById(customerId).map(LoyaltyAccount::getTierCode).orElse("MEMBER"));
    }
    private boolean active(Voucher voucher) {
        LocalDateTime now = LocalDateTime.now();
        return voucher.isActive() && (voucher.getValidFrom() == null || !now.isBefore(voucher.getValidFrom()))
                && (voucher.getValidTo() == null || now.isBefore(voucher.getValidTo()))
                && (voucher.getUsageLimit() == null || voucher.getUsedCount() < voucher.getUsageLimit());
    }
    public List<Voucher> available(Long customerId) {
        if (customerId == null) return List.of();
        return grants.findByCustomerIdAndRevokedFalseAndUsedOrderIdIsNullAndReservedOrderIdIsNull(customerId).stream()
                .map(g -> vouchers.findById(g.getVoucherId()).orElse(null))
                .filter(Objects::nonNull).filter(this::active).filter(v -> matchesTier(v, customerId)).toList();
    }
    @Transactional
    public Voucher requireAvailable(String code, Long customerId, SalesChannel channel) {
        if (customerId == null) throw invalid("Vui lòng chọn khách hàng đã được cấp voucher.");
        Voucher voucher = lockedVoucher(code);
        requireGrant(voucher, customerId, channel, null);
        return voucher;
    }
    private VoucherGrant requireGrant(Voucher voucher, Long customerId, SalesChannel channel, Long orderId) {
        VoucherGrant grant = grants.lockGrant(voucher.getId(), customerId).orElseThrow(() -> invalid("Voucher chưa được cấp cho khách hàng này."));
        if (grant.isRevoked()) throw invalid("Voucher đã được thu hồi.");
        if (grant.getUsedOrderId() != null) throw invalid("Voucher đã sử dụng. Mỗi khách chỉ được dùng một lần.");
        if (grant.getReservedOrderId() != null && !grant.getReservedOrderId().equals(orderId)) throw invalid("Voucher đang được giữ cho một đơn hàng chờ thanh toán.");
        if (!active(voucher)) throw invalid("Voucher chưa có hiệu lực, đã hết hạn hoặc hết lượt sử dụng.");
        if (voucher.getRequiredTier() != null && !"NONE".equals(voucher.getRequiredTier())) {
            String currentTier = accounts.lockByCustomerId(customerId).map(LoyaltyAccount::getTierCode).orElse("MEMBER");
            if (!voucher.getRequiredTier().equals(currentTier)) throw invalid("Hạng thành viên hiện tại không phù hợp với voucher.");
        }
        if (channel != null && voucher.getChannel() != null && !"ALL".equals(voucher.getChannel()) && !channel.name().equals(voucher.getChannel())) throw invalid("Voucher không áp dụng cho kênh mua hàng này.");
        if (grant.getReservedOrderId() == null && voucher.getUsageLimit() != null
                && voucher.getUsedCount() + grants.lockReservations(voucher.getId()).size() >= voucher.getUsageLimit()) throw invalid("Voucher đã hết lượt khả dụng.");
        return grant;
    }
    @Transactional
    public void reserve(Order order) {
        if (order.getVoucherCode() == null || order.getVoucherCode().isBlank()) return;
        Voucher voucher = lockedVoucher(order.getVoucherCode());
        VoucherGrant grant = requireGrant(voucher, order.getCustomerId(), order.getChannel(), order.getId());
        grant.setReservedOrderId(order.getId());
        grants.save(grant);
    }
    @Transactional
    public void consume(Order order) {
        if (order.getVoucherCode() == null || order.getVoucherCode().isBlank()) return;
        Voucher voucher = lockedVoucher(order.getVoucherCode());
        VoucherGrant grant = grants.lockGrant(voucher.getId(), order.getCustomerId()).orElseThrow(() -> invalid("Không tìm thấy voucher được cấp cho đơn hàng."));
        if (Objects.equals(grant.getUsedOrderId(), order.getId())) return;
        if (grant.getUsedOrderId() != null || !Objects.equals(grant.getReservedOrderId(), order.getId())) throw invalid("Voucher không thuộc đơn thanh toán này hoặc đã được dùng.");
        // Eligibility was checked when this order reserved the voucher; keep the quoted terms on payment.
        grant.setUsedOrderId(order.getId()); grant.setReservedOrderId(null); grants.save(grant);
    }
    @Transactional
    public void release(Order order) {
        if (order.getVoucherCode() == null || order.getVoucherCode().isBlank()) return;
        vouchers.lockByCode(order.getVoucherCode()).ifPresent(v -> grants.lockGrant(v.getId(), order.getCustomerId()).ifPresent(g -> {
            if (g.getUsedOrderId() == null && Objects.equals(g.getReservedOrderId(), order.getId())) { g.setReservedOrderId(null); grants.save(g); }
        }));
    }
    @Transactional
    public List<VoucherGrant> issue(Long voucherId, List<Long> customerIds, String token) {
        if (customerIds == null || customerIds.isEmpty() || customerIds.size() > 200 || customerIds.stream().anyMatch(Objects::isNull)) throw invalid("Chọn từ 1 đến 200 khách hàng.");
        Voucher voucher = vouchers.findById(voucherId).orElseThrow(() -> invalid("Không tìm thấy voucher."));
        // Serialize issuance and checkout for this voucher, including the no-row-yet case.
        voucher = lockedVoucher(voucher.getCode());
        if (!active(voucher)) throw invalid("Chỉ phát voucher đang có hiệu lực.");
        List<VoucherGrant> result = new ArrayList<>();
        for (Long customerId : new LinkedHashSet<>(customerIds)) {
            people.customer(token, customerId);
            if (!matchesTier(voucher, customerId)) throw invalid("Khách hàng #" + customerId + " chưa đúng hạng của voucher.");
            VoucherGrant grant = grants.lockGrant(voucherId, customerId).orElse(null);
            if (grant == null) { grant = new VoucherGrant(); grant.setVoucherId(voucherId); grant.setCustomerId(customerId); }
            if (grant.getUsedOrderId() != null) throw invalid("Khách hàng #" + customerId + " đã dùng voucher này, không thể cấp lại.");
            grant.setRevoked(false); result.add(grants.save(grant));
        }
        return result;
    }
    public List<VoucherGrant> issued(Long voucherId) { return grants.findByVoucherIdOrderByGrantedAtDesc(voucherId); }
    @Transactional
    public void revoke(Long voucherId, Long customerId) {
        Voucher voucher = vouchers.findById(voucherId).orElseThrow();
        vouchers.lockByCode(voucher.getCode()).orElseThrow();
        VoucherGrant grant = grants.lockGrant(voucherId, customerId).orElseThrow(() -> invalid("Khách hàng chưa được cấp voucher."));
        if (grant.getReservedOrderId() != null || grant.getUsedOrderId() != null) throw invalid("Không thu hồi voucher đang giữ cho đơn hàng hoặc đã sử dụng.");
        grant.setRevoked(true); grants.save(grant);
    }
}
