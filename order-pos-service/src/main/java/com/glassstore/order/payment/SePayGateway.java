package com.glassstore.order.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.order.domain.PaymentMethod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** SePay Payment Gateway sandbox: signed checkout form and SECRET_KEY-authenticated IPN. */
@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "sepay")
@RequiredArgsConstructor
@Slf4j
public class SePayGateway implements PaymentGateway {
    public static final String PROVIDER = "SEPAY";
    public static final String CHECKOUT_URL = "https://pay-sandbox.sepay.vn/v1/checkout/init";
    private final SePayProperties properties;
    private final ObjectMapper json;

    @Override
    public boolean supports(PaymentMethod method) {
        return method == PaymentMethod.VIETQR;
    }

    public List<String> missingConfiguration() {
        List<String> missing = new ArrayList<>();
        if (isPlaceholder(properties.getMerchantId())) missing.add("SEPAY_MERCHANT_ID");
        if (isPlaceholder(properties.getSecretKey())) {
            missing.add("SEPAY_SECRET_KEY");
        }
        if (isPlaceholder(properties.getIpnSecret()) || properties.getIpnSecret().length() < 32
                || properties.getIpnSecret().equals(properties.getSecretKey())) {
            missing.add("SEPAY_IPN_SECRET (separate key, at least 32 characters)");
        }
        if (!validReturnBase()) missing.add("SEPAY_RETURN_BASE_URL");
        return List.copyOf(missing);
    }

    private boolean isPlaceholder(String value) {
        return value == null || value.isBlank() || value.startsWith("YOUR_");
    }

    private boolean validReturnBase() {
        try {
            URI uri = URI.create(properties.getReturnBaseUrl());
            return uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null
                    && uri.getFragment() == null && ("https".equals(uri.getScheme())
                    || "http".equals(uri.getScheme()) && List.of("localhost", "127.0.0.1").contains(uri.getHost()));
        } catch (IllegalArgumentException | NullPointerException error) {
            return false;
        }
    }

    private void requireConfigured() {
        if (!missingConfiguration().isEmpty()) {
            throw ApiException.of(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED,
                    "SePay Sandbox chưa đủ cấu hình. Kiểm tra trạng thái cấu hình trước khi thanh toán.");
        }
    }

    @Override
    public InitiationResult initiate(InitiationRequest request) {
        requireConfigured();
        String amount = requireVndAmount(request.amount()).toPlainString();
        String invoice = requireText(request.orderCode(), "order_invoice_number");
        // Insertion order is part of SePay's signing contract and must survive JSON serialization.
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("merchant", properties.getMerchantId());
        fields.put("operation", "PURCHASE");
        fields.put("payment_method", "BANK_TRANSFER");
        fields.put("order_invoice_number", invoice);
        fields.put("order_amount", amount);
        fields.put("currency", "VND");
        fields.put("order_description", "Thanh toan don hang " + invoice);
        String base = properties.getReturnBaseUrl().replaceAll("/+$", "");
        for (String outcome : List.of("success", "error", "cancel")) {
            String returnUrl = UriComponentsBuilder.fromUriString(base)
                    .pathSegment("orders", invoice).queryParam("payment", outcome)
                    .build().encode().toUriString();
            fields.put(outcome + "_url", returnUrl);
        }
        fields.put("signature", sign(fields));
        String checkoutUrl = properties.getSecretKey() != null && properties.getSecretKey().startsWith("spsk_live_")
                ? "https://pay.sepay.vn/v1/checkout/init"
                : CHECKOUT_URL;
        // SePay assigns the external transaction ID in its IPN, not when we build a form.
        return new InitiationResult(PROVIDER, null, checkoutUrl, null, true, "POST", fields);
    }

    private String sign(Map<String, String> fields) {
        String canonical = fields.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining(","));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getSecretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Cannot sign SePay checkout", error);
        }
    }

    @Override
    public VerificationResult verifyCallback(String rawPayload, String ipnSecret) {
        requireConfigured();
        if (ipnSecret == null || !MessageDigest.isEqual(properties.getIpnSecret().getBytes(StandardCharsets.UTF_8),
                ipnSecret.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid SePay IPN authentication.");
        }
        JsonNode body;
        try {
            body = json.readTree(rawPayload);
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw invalid("IPN không phải JSON hợp lệ.");
        }
        if (body == null || !body.isObject()) throw invalid("IPN phải là JSON object.");
        JsonNode order = body.path("order");
        JsonNode transaction = body.path("transaction");
        requireValue(body, "notification_type", "ORDER_PAID");
        requireValue(order, "order_status", "CAPTURED");
        requireValue(order, "order_currency", "VND");
        requireValue(transaction, "transaction_type", "PAYMENT");
        requireValue(transaction, "transaction_status", "APPROVED");
        requireValue(transaction, "transaction_currency", "VND");
        requireValue(transaction, "payment_method", "BANK_TRANSFER");
        BigDecimal amount = readAmount(transaction, "transaction_amount");
        if (amount.compareTo(readAmount(order, "order_amount")) != 0) {
            throw ApiException.of(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
        if (!body.path("timestamp").isIntegralNumber() || !body.path("timestamp").canConvertToLong()
                || body.path("timestamp").asLong() <= 0) {
            throw invalid("Thiếu timestamp hợp lệ.");
        }
        return new VerificationResult(true, PROVIDER, text(transaction, "id"),
                text(order, "order_invoice_number"), amount, true, "SePay IPN authenticated",
                text(order, "id"), text(transaction, "transaction_id"));
    }

    @Override
    public Optional<VerificationResult> queryStatus(String orderCode) {
        if (orderCode == null || orderCode.isBlank() || !missingConfiguration().isEmpty()) {
            return Optional.empty();
        }
        try {
            String baseUrl = properties.getSecretKey() != null && properties.getSecretKey().startsWith("spsk_live_")
                    ? "https://pgapi.sepay.vn/v1/order/detail/"
                    : "https://pgapi-sandbox.sepay.vn/v1/order/detail/";
            String url = baseUrl + orderCode;
            String auth = properties.getMerchantId() + ":" + properties.getSecretKey();
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Basic " + encodedAuth)
                    .GET()
                    .timeout(Duration.ofSeconds(6))
                    .build();

            HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return Optional.empty();
            }

            JsonNode root = json.readTree(resp.body());
            JsonNode data = root.path("data");
            if (data == null || data.isMissingNode() || data.isNull()) {
                return Optional.empty();
            }

            String orderStatus = data.path("order_status").asText("");
            if (!"CAPTURED".equalsIgnoreCase(orderStatus)) {
                return Optional.empty();
            }

            JsonNode transactions = data.path("transactions");
            if (!transactions.isArray() || transactions.isEmpty()) {
                return Optional.empty();
            }

            JsonNode approvedTxn = null;
            for (JsonNode txn : transactions) {
                if ("APPROVED".equalsIgnoreCase(txn.path("transaction_status").asText(""))) {
                    approvedTxn = txn;
                    break;
                }
            }
            if (approvedTxn == null) {
                return Optional.empty();
            }

            BigDecimal amount = readAmount(approvedTxn, "transaction_amount");
            String txnId = text(approvedTxn, "id");
            String providerOrderId = data.path("order_id").asText(null);

            return Optional.of(new VerificationResult(
                    true, PROVIDER, txnId, orderCode, amount, true,
                    "SePay PG API query verified", providerOrderId, txnId));
        } catch (Exception ex) {
            log.warn("Lỗi khi truy vấn trạng thái từ SePay PG API cho đơn {}: {}", orderCode, ex.getMessage());
            return Optional.empty();
        }
    }

    private void requireValue(JsonNode node, String field, String expected) {
        if (!expected.equals(node.path(field).asText())) throw invalid("IPN không hợp lệ: " + field);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return requireText(value.isTextual() ? value.textValue() : null, field);
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 120 || !value.equals(value.trim())) {
            throw invalid("Trường không hợp lệ: " + field);
        }
        return value;
    }

    private BigDecimal readAmount(JsonNode node, String field) {
        try {
            return requireVndAmount(new BigDecimal(node.path(field).asText()));
        } catch (NumberFormatException error) {
            throw invalid("Số tiền không hợp lệ: " + field);
        }
    }

    private BigDecimal requireVndAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 0
                || amount.compareTo(new BigDecimal("999999999999")) > 0) {
            throw invalid("Số tiền VND phải là số nguyên dương trong giới hạn đơn hàng.");
        }
        return amount.setScale(0);
    }

    private ApiException invalid(String message) {
        return ApiException.of(ErrorCode.VALIDATION_ERROR, message);
    }
}
