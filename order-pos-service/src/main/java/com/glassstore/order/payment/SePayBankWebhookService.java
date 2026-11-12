package com.glassstore.order.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.order.domain.OrderStatus;
import com.glassstore.order.domain.PaymentMethod;
import com.glassstore.order.domain.PaymentStatus;
import com.glassstore.order.entity.Order;
import com.glassstore.order.entity.Payment;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.repository.PaymentRepository;
import com.glassstore.order.service.OrderCheckoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Verifies inbound SePay Bank Transaction Webhooks (my.sepay.vn) and settles matching pending orders.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SePayBankWebhookService {

    public static final String PROVIDER = "SEPAY_BANK";
    private static final int MIN_NORMALIZED_CODE_LENGTH = 6;

    private final SePayProperties sePayProperties;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final OrderCheckoutService checkoutService;
    private final ObjectMapper objectMapper;

    public record WebhookOutcome(
            boolean success,
            boolean replayed,
            boolean ignored,
            String orderCode,
            String externalTransactionId,
            String message
    ) {}

    public boolean isConfigured() {
        String configuredKey = sePayProperties.getWebhookApiKey();
        return configuredKey != null && !configuredKey.isBlank() && !configuredKey.startsWith("YOUR_");
    }

    @Transactional
    public WebhookOutcome handleWebhook(String authHeader, String xApiKey, String xSecretKey, String rawPayload) {
        validateApiKey(authHeader, xApiKey, xSecretKey);

        JsonNode root = parseObject(rawPayload);
        String transferType = textOrEmpty(root, "transferType");
        if (!transferType.isEmpty() && !"in".equalsIgnoreCase(transferType)) {
            return new WebhookOutcome(true, false, true, null, null, "Ignored outgoing transferType=" + transferType);
        }

        String rawTxnId = textOrEmpty(root, "id");
        String referenceCode = textOrEmpty(root, "referenceCode");
        String uniqueId = !rawTxnId.isEmpty() ? rawTxnId : referenceCode;
        if (uniqueId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook thiếu id và referenceCode.");
        }
        String externalTxnId = "SEPAY-BANK-" + uniqueId;

        Optional<Payment> existingTxn = paymentRepository.findByProviderAndExternalTransactionId(PROVIDER, externalTxnId);
        if (existingTxn.isPresent() && existingTxn.get().getStatus() == PaymentStatus.SUCCESS) {
            Order existingOrder = orderRepository.findById(existingTxn.get().getOrderId()).orElse(null);
            return new WebhookOutcome(true, true, false,
                    existingOrder != null ? existingOrder.getOrderCode() : null,
                    externalTxnId, "Giao dịch đã được ghi nhận trước đó");
        }

        BigDecimal transferAmount = parsePositiveAmount(root.path("transferAmount"));
        String codeField = textOrEmpty(root, "code");
        String content = textOrEmpty(root, "content");
        String description = textOrEmpty(root, "description");
        String gateway = textOrEmpty(root, "gateway");

        Optional<Order> matchedPending = resolveOrder(codeField, content, description, OrderStatus.PENDING_PAYMENT);
        if (matchedPending.isEmpty()) {
            Optional<Order> alreadyPaid = resolveOrder(codeField, content, description, OrderStatus.PAID);
            if (alreadyPaid.isPresent()) {
                return new WebhookOutcome(true, true, false, alreadyPaid.get().getOrderCode(), externalTxnId,
                        "Đơn hàng đã được thanh toán trước đó");
            }
            log.info("SePay bank webhook ignored (no matching pending order): txnId={}, amount={}, content={}",
                    externalTxnId, transferAmount, content);
            return new WebhookOutcome(true, false, true, null, externalTxnId,
                    "Không tìm thấy đơn chờ thanh toán khớp nội dung chuyển khoản");
        }

        Order order = orderRepository.lockByOrderCode(matchedPending.get().getOrderCode())
                .orElseThrow(() -> ApiException.of(ErrorCode.ORDER_NOT_FOUND));

        if (order.getStatus() != OrderStatus.PENDING_PAYMENT
                || paymentRepository.existsByOrderIdAndStatus(order.getId(), PaymentStatus.SUCCESS)) {
            return new WebhookOutcome(true, true, false, order.getOrderCode(), externalTxnId,
                    "Đơn hàng đã được xác nhận thanh toán trước đó");
        }

        if (transferAmount == null || order.getFinalAmount() == null
                || transferAmount.compareTo(order.getFinalAmount()) < 0) {
            log.warn("SePay bank webhook amount mismatch for order {}: received={}, expected={}",
                    order.getOrderCode(), transferAmount, order.getFinalAmount());
            return new WebhookOutcome(true, false, true, order.getOrderCode(), externalTxnId, "AMOUNT_MISMATCH");
        }

        Payment pending = paymentRepository.findByOrderIdOrderByCreatedAtDesc(order.getId()).stream()
                .filter(p -> p.getStatus() == PaymentStatus.PENDING)
                .findFirst()
                .orElseGet(() -> Payment.builder()
                        .orderId(order.getId())
                        .paymentMethod(order.getPaymentMethod() != null ? order.getPaymentMethod() : PaymentMethod.VIETQR)
                        .amount(order.getFinalAmount())
                        .status(PaymentStatus.PENDING)
                        .build());

        String ref = !referenceCode.isEmpty() ? referenceCode : externalTxnId;
        pending.setProvider(PROVIDER);
        pending.setExternalTransactionId(externalTxnId);
        pending.setProviderOrderId(rawTxnId.isEmpty() ? uniqueId : rawTxnId);
        pending.setPaymentReference(ref);
        pending.setTransactionCode(ref);
        pending = paymentRepository.saveAndFlush(pending);

        String bankLabel = gateway.isEmpty() ? "Ngân hàng" : gateway;
        String reason = "SePay tự động xác thực chuyển khoản " + bankLabel + " (Ref: " + ref + ")";
        checkoutService.markPaid(order, "SEPAY_WEBHOOK", reason, PROVIDER, pending.getId());

        Payment settled = paymentRepository.findFirstByOrderIdAndStatus(order.getId(), PaymentStatus.SUCCESS).orElse(pending);
        settled.setExternalTransactionId(externalTxnId);
        settled.setProvider(PROVIDER);
        settled.setPaymentReference(ref);
        paymentRepository.save(settled);

        log.info("SePay bank webhook settled order {} via {} (amount={}, txn={})",
                order.getOrderCode(), bankLabel, transferAmount, externalTxnId);
        return new WebhookOutcome(true, false, false, order.getOrderCode(), externalTxnId,
                "Đã tự động xác thực thanh toán SePay thành công");
    }

    private void validateApiKey(String authHeader, String xApiKey, String xSecretKey) {
        if (!isConfigured()) {
            throw ApiException.of(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED,
                    "Chưa cấu hình SEPAY_WEBHOOK_API_KEY cho SePay Bank Webhook.");
        }
        String candidate = extractToken(authHeader);
        if (candidate.isEmpty() && xApiKey != null && !xApiKey.isBlank()) {
            candidate = xApiKey.trim();
        }
        if (candidate.isEmpty() && xSecretKey != null && !xSecretKey.isBlank()) {
            candidate = xSecretKey.trim();
        }
        byte[] expectedBytes = sePayProperties.getWebhookApiKey().trim().getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = candidate.getBytes(StandardCharsets.UTF_8);
        if (candidate.isEmpty() || !MessageDigest.isEqual(expectedBytes, providedBytes)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid SePay Webhook API Key.");
        }
    }

    private String extractToken(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) return "";
        String trimmed = authHeader.trim();
        if (trimmed.length() > 7 && trimmed.regionMatches(true, 0, "Apikey ", 0, 7)) {
            return trimmed.substring(7).trim();
        }
        if (trimmed.length() > 7 && trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return trimmed.substring(7).trim();
        }
        return trimmed;
    }

    private JsonNode parseObject(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook payload rỗng.");
        }
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            if (root == null || !root.isObject()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook payload phải là JSON object.");
            }
            return root;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook payload không phải JSON hợp lệ.");
        }
    }

    private Optional<Order> resolveOrder(String codeField, String content, String description, OrderStatus targetStatus) {
        if (!codeField.isEmpty()) {
            Optional<Order> exact = orderRepository.findByOrderCode(codeField.trim())
                    .filter(o -> o.getStatus() == targetStatus);
            if (exact.isPresent()) {
                return exact;
            }
        }
        String haystack = normalizeAlnum(codeField + " " + content + " " + description);
        if (haystack.length() < MIN_NORMALIZED_CODE_LENGTH) {
            return Optional.empty();
        }
        List<Order> candidates = orderRepository.findByStatusOrderByCreatedAtDesc(targetStatus);
        return candidates.stream()
                .filter(o -> o.getOrderCode() != null)
                .map(o -> new CandidateMatch(o, normalizeAlnum(o.getOrderCode())))
                .filter(m -> m.normalizedCode().length() >= MIN_NORMALIZED_CODE_LENGTH && haystack.contains(m.normalizedCode()))
                .max(Comparator.comparingInt(m -> m.normalizedCode().length()))
                .map(CandidateMatch::order);
    }

    private String normalizeAlnum(String value) {
        if (value == null) return "";
        return value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private BigDecimal parsePositiveAmount(JsonNode amountNode) {
        if (amountNode == null || amountNode.isMissingNode() || amountNode.isNull()) {
            return null;
        }
        String text = amountNode.asText("").trim();
        if (text.isEmpty()) return null;
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String textOrEmpty(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return (node.isMissingNode() || node.isNull()) ? "" : node.asText("").trim();
    }

    private record CandidateMatch(Order order, String normalizedCode) {}
}
