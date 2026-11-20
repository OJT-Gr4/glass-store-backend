package com.glassstore.order.service;

import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.order.domain.OrderStatus;
import com.glassstore.order.domain.PaymentStatus;
import com.glassstore.order.dto.WarrantyOrderItemDTO;
import com.glassstore.order.entity.Order;
import com.glassstore.order.entity.OrderItem;
import com.glassstore.order.entity.WarrantyActivationOutbox;
import com.glassstore.order.repository.PaymentRepository;
import com.glassstore.order.repository.WarrantyActivationOutboxRepository;
import com.glassstore.order.repository.WarrantyOrderItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WarrantyOrderLookupService {

    private static final Set<OrderStatus> INVALID_PURCHASE_STATUSES =
            Set.of(OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, OrderStatus.REFUND_PENDING, OrderStatus.REFUNDED);

    private final WarrantyOrderItemRepository warrantyOrderItemRepository;
    private final PaymentRepository paymentRepository;
    private final WarrantyActivationOutboxRepository outboxRepository;

    @Transactional(readOnly = true)
    public List<WarrantyOrderItemDTO> findPurchases(String phone, String sku) {
        String cleanPhone = phone == null ? "" : phone.trim();
        String cleanSku = sku == null ? "" : sku.trim();

        if ((cleanPhone.isEmpty() || "ALL".equalsIgnoreCase(cleanPhone)) && cleanSku.isEmpty()) {
            return warrantyOrderItemRepository.findAllOrdersWithItems().stream()
                    .filter(this::isPaidPurchase)
                    .flatMap(order -> order.getItems().stream().map(item -> toDto(order, item)))
                    .toList();
        }

        if (!cleanPhone.isEmpty()) {
            List<Order> exactOrders = warrantyOrderItemRepository.findOrdersWithItemsByCustomerPhone(cleanPhone);
            List<Order> matchedOrders = exactOrders.stream().filter(this::isPaidPurchase).toList();
            if (matchedOrders.isEmpty()) {
                List<Order> allPaid = warrantyOrderItemRepository.findAllOrdersWithItems().stream()
                        .filter(this::isPaidPurchase)
                        .toList();
                matchedOrders = allPaid.stream()
                        .filter(order -> matchesPhoneOrKeyword(order, cleanPhone))
                        .toList();
                if (matchedOrders.isEmpty()) {
                    matchedOrders = allPaid.stream()
                            .filter(order -> matchesFuzzyPhone(order.getCustomerPhone(), cleanPhone))
                            .toList();
                }
            }
            return matchedOrders.stream()
                    .flatMap(order -> order.getItems().stream().map(item -> toDto(order, item)))
                    .toList();
        }

        List<Order> skuOrders = warrantyOrderItemRepository.findOrdersWithItemsBySku(cleanSku).stream()
                .filter(this::isPaidPurchase)
                .toList();
        if (!skuOrders.isEmpty()) {
            return skuOrders.stream()
                    .flatMap(order -> order.getItems().stream()
                            .filter(item -> item.getSkuId() != null && item.getSkuId().equalsIgnoreCase(cleanSku))
                            .map(item -> toDto(order, item)))
                    .toList();
        }

        String qLower = cleanSku.toLowerCase();
        return warrantyOrderItemRepository.findAllOrdersWithItems().stream()
                .filter(this::isPaidPurchase)
                .flatMap(order -> order.getItems().stream()
                        .filter(item -> (item.getSkuId() != null && item.getSkuId().toLowerCase().contains(qLower))
                                || (item.getProductName() != null && item.getProductName().toLowerCase().contains(qLower))
                                || (order.getOrderCode() != null && order.getOrderCode().toLowerCase().contains(qLower)))
                        .map(item -> toDto(order, item)))
                .toList();
    }

    private boolean matchesPhoneOrKeyword(Order order, String query) {
        String qLower = query.toLowerCase();
        String qDigits = digitsOnly(query);
        String orderDigits = digitsOnly(order.getCustomerPhone());
        if (!qDigits.isEmpty() && !orderDigits.isEmpty()) {
            if (orderDigits.equals(qDigits) || orderDigits.contains(qDigits) || (qDigits.length() >= 6 && qDigits.contains(orderDigits))) {
                return true;
            }
        }
        if (order.getCustomerName() != null && order.getCustomerName().toLowerCase().contains(qLower)) {
            return true;
        }
        if (order.getOrderCode() != null && order.getOrderCode().toLowerCase().contains(qLower)) {
            return true;
        }
        return order.getItems().stream().anyMatch(i ->
                (i.getSkuId() != null && i.getSkuId().toLowerCase().contains(qLower))
                        || (i.getProductName() != null && i.getProductName().toLowerCase().contains(qLower)));
    }

    private boolean matchesFuzzyPhone(String orderPhone, String query) {
        String a = digitsOnly(orderPhone);
        String b = digitsOnly(query);
        if (a.length() < 8 || b.length() < 8 || Math.abs(a.length() - b.length()) > 1) {
            return false;
        }
        if ("0000000000".equals(a)) {
            return false;
        }
        int prefixLen = Math.min(6, Math.min(a.length(), b.length()));
        if (!a.substring(0, prefixLen).equals(b.substring(0, prefixLen))) {
            return false;
        }
        int diffs = 0;
        int minLen = Math.min(a.length(), b.length());
        for (int i = 0; i < minLen; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                diffs++;
            }
        }
        diffs += Math.abs(a.length() - b.length());
        return diffs <= 2;
    }

    private String digitsOnly(String input) {
        if (input == null) return "";
        String d = input.replaceAll("[^0-9]", "");
        if (d.startsWith("84") && d.length() >= 11) {
            return "0" + d.substring(2);
        }
        return d;
    }

    @Transactional(readOnly = true)
    public WarrantyOrderItemDTO requirePurchaseItem(Long orderId, Long orderItemId) {
        Order order = warrantyOrderItemRepository.findById(orderId)
                .orElseThrow(() -> ApiException.of(ErrorCode.ORDER_NOT_FOUND,
                        "Không tìm thấy đơn hàng: " + orderId));
        if (!isPaidPurchase(order)) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Đơn hàng chưa thanh toán hoặc không còn hợp lệ");
        }
        OrderItem item = order.getItems().stream()
                .filter(candidate -> candidate.getId().equals(orderItemId))
                .findFirst()
                .orElseThrow(() -> ApiException.of(ErrorCode.VALIDATION_ERROR,
                        "Sản phẩm không thuộc đơn hàng đã chọn"));
        return toDto(order, item);
    }

    private boolean isPaidPurchase(Order order) {
        return !INVALID_PURCHASE_STATUSES.contains(order.currentStatus())
                && paymentRepository.existsByOrderIdAndStatus(order.getId(), PaymentStatus.SUCCESS);
    }

    private WarrantyOrderItemDTO toDto(Order order, OrderItem item) {
        List<String> benefits = WarrantyActivationServiceBenefits.forSku(item.getSkuId());
        List<WarrantyActivationOutbox> outboxEntries =
                outboxRepository.findByOrderIdOrderByOrderItemIdAsc(order.getId()).stream()
                        .filter(entry -> entry.getOrderItemId().equals(item.getId()))
                        .toList();
        if (benefits.isEmpty()) {
            return new WarrantyOrderItemDTO(order.getId(), item.getId(), order.getOrderCode(), order.getCustomerId(),
                    order.getCustomerName(), order.getCustomerPhone(), item.getSkuId(), item.getProductName(),
                    order.getCreatedAt(), order.currentStatus().name(), null, "NOT_ELIGIBLE", 0, null);
        }
        String benefit = benefits.get(0);
        WarrantyActivationOutbox activation = outboxEntries.stream()
                .filter(entry -> entry.getBenefitType().equals(benefit))
                .findFirst().orElse(null);
        return new WarrantyOrderItemDTO(order.getId(), item.getId(), order.getOrderCode(), order.getCustomerId(),
                order.getCustomerName(), order.getCustomerPhone(), item.getSkuId(), item.getProductName(),
                order.getCreatedAt(), order.currentStatus().name(), benefit,
                activation == null ? "NOT_REQUESTED" : activation.getStatus(),
                activation == null ? 0 : activation.getAttempts(),
                activation == null ? null : activation.getLastError());
    }

    private static final class WarrantyActivationServiceBenefits {
        private WarrantyActivationServiceBenefits() {
        }

        static List<String> forSku(String sku) {
            if (sku == null) {
                return List.of();
            }
            String normalized = sku.trim().toUpperCase();
            if (normalized.startsWith("FRM-")) {
                return List.of("FRAME_ADJUSTMENT");
            }
            if (normalized.startsWith("LNS-") || normalized.startsWith("LZ-")) {
                return List.of("LENS_COATING");
            }
            return List.of();
        }
    }
}
