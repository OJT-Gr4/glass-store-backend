package com.glassstore.order.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.order.domain.PaymentMethod;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Configurable Sandbox Payment Gateway implementing VietQR & VNPay Sandbox (S3-02).
 *
 * <p>When {@code payment.sandbox.enabled=false}, all initiation and callback verification
 * fail explicitly with {@code PAYMENT_PROVIDER_NOT_CONFIGURED} (HTTP 503), preventing
 * any fabrication of paid orders without valid provider integration (BR-16).</p>
 */
@Component
@Primary
@Slf4j
public class ConfigurableSandboxPaymentGateway implements PaymentGateway {

    private static final String HMAC_SHA512 = "HmacSHA512";
    private static final DateTimeFormatter VNP_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${payment.sandbox.enabled:true}")
    private boolean enabled;

    @Value("${payment.sandbox.provider:VIETQR}")
    private String defaultProvider;

    @Value("${payment.sandbox.secret-key:GlassStoreSandboxSecretKey20261110!@#HMAC512}")
    private String secretKey;

    @Value("${payment.vietqr.bank-id:970422}")
    private String vietqrBankId;

    @Value("${payment.vietqr.account-no:0987654321}")
    private String vietqrAccountNo;

    @Value("${payment.vietqr.account-name:OPTIVISION GLASS STORE}")
    private String vietqrAccountName;

    @Value("${payment.vietqr.template:compact2}")
    private String vietqrTemplate;

    @Value("${payment.vnpay.tmn-code:OPTIVIS1}")
    private String vnpayTmnCode;

    @Value("${payment.vnpay.hash-secret:GlassStoreSandboxSecretKey20261110!@#HMAC512}")
    private String vnpayHashSecret;

    @Value("${payment.vnpay.url:https://sandbox.vnpayment.vn/paymentv2/vpcpay.html}")
    private String vnpayUrl;

    @Value("${payment.vnpay.return-url:http://localhost:5173/payment-return}")
    private String vnpayReturnUrl;

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    @Override
    public boolean supports(PaymentMethod method) {
        return method != null && method.requiresGateway();
    }

    @Override
    public InitiationResult initiate(InitiationRequest request) {
        if (!enabled) {
            log.warn("Payment sandbox not enabled/configured; refusing to initiate charge for order {}", request.orderCode());
            throw ApiException.of(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED,
                    "Cổng thanh toán trực tuyến chưa được cấu hình. Vui lòng chọn thanh toán tiền mặt tại quầy.");
        }

        if (request == null || request.orderCode() == null || request.amount() == null) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Thông tin khởi tạo thanh toán không hợp lệ");
        }

        String externalTxnId = "TXN-SANDBOX-" + request.orderCode() + "-" + System.currentTimeMillis();
        long amountVnd = request.amount().setScale(0, RoundingMode.HALF_UP).longValue();
        String description = request.description() != null ? request.description() : ("Thanh toan don " + request.orderCode());

        // VietQR QuickLink (https://img.vietqr.io/image/<BANK_ID>-<ACCOUNT_NO>-<TEMPLATE>.png)
        String encodedDesc = URLEncoder.encode(description, StandardCharsets.UTF_8);
        String encodedAccName = URLEncoder.encode(vietqrAccountName, StandardCharsets.UTF_8);
        String vietqrUrl = String.format("https://img.vietqr.io/image/%s-%s-%s.png?amount=%d&addInfo=%s&accountName=%s",
                vietqrBankId, vietqrAccountNo, vietqrTemplate, amountVnd, encodedDesc, encodedAccName);
        String qrContent = String.format("2.00#01#%s#%s#%d#%s", vietqrBankId, vietqrAccountNo, amountVnd, request.orderCode());

        // VNPay Sandbox Payment URL
        String vnpayPaymentUrl = buildVnpayPaymentUrl(request.orderCode(), amountVnd, description);

        log.info("Payment initiated for order {} with externalTxnId {}", request.orderCode(), externalTxnId);
        return new InitiationResult("SANDBOX_GATEWAY", externalTxnId, vietqrUrl, qrContent, true);
    }

    @Override
    public VerificationResult verifyCallback(String rawPayload, String signature) {
        if (!enabled) {
            log.warn("Payment sandbox callback received but gateway is disabled; rejecting");
            throw ApiException.of(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED,
                    "Chưa cấu hình cổng thanh toán nên không thể xác thực giao dịch.");
        }

        if (rawPayload == null || rawPayload.isBlank()) {
            return new VerificationResult(false, "SANDBOX_GATEWAY", null, null, null, false, "Nội dung callback trống");
        }

        try {
            String trimmed = rawPayload.trim();
            if (trimmed.startsWith("{")) {
                return verifyJsonCallback(trimmed, signature);
            } else {
                return verifyUrlEncodedCallback(trimmed, signature);
            }
        } catch (Exception e) {
            log.error("Failed to parse callback payload", e);
            return new VerificationResult(false, "SANDBOX_GATEWAY", null, null, null, false, "Lỗi phân tích cú pháp callback: " + e.getMessage());
        }
    }

    private VerificationResult verifyJsonCallback(String jsonPayload, String headerSignature) {
        try {
            JsonNode root = objectMapper.readTree(jsonPayload);
            String orderCode = root.hasNonNull("orderCode") ? root.get("orderCode").asText() : null;
            String externalTxnId = root.hasNonNull("externalTransactionId") ? root.get("externalTransactionId").asText() : null;
            BigDecimal amount = root.hasNonNull("amount") ? new BigDecimal(root.get("amount").asText()) : null;
            String status = root.hasNonNull("status") ? root.get("status").asText() : "SUCCESS";
            String payloadSignature = root.hasNonNull("signature") ? root.get("signature").asText() : null;

            String sigToVerify = headerSignature != null && !headerSignature.isBlank() ? headerSignature : payloadSignature;
            if (sigToVerify == null || sigToVerify.isBlank()) {
                return new VerificationResult(false, "SANDBOX_GATEWAY", externalTxnId, orderCode, amount, false, "Thiếu chữ ký xác thực");
            }

            // Canonical string for signature: orderCode|amount|externalTransactionId|status
            String canonicalData = String.format("%s|%s|%s|%s",
                    orderCode != null ? orderCode : "",
                    amount != null ? amount.stripTrailingZeros().toPlainString() : "",
                    externalTxnId != null ? externalTxnId : "",
                    status != null ? status : "");

            boolean signatureMatches = verifyHmacSha512(canonicalData, sigToVerify, secretKey)
                    || verifyHmacSha512(jsonPayload, sigToVerify, secretKey);

            if (!signatureMatches) {
                log.warn("Invalid signature on JSON callback for order {}", orderCode);
                return new VerificationResult(false, "SANDBOX_GATEWAY", externalTxnId, orderCode, amount, false, "Chữ ký không hợp lệ");
            }

            boolean isSuccess = "SUCCESS".equalsIgnoreCase(status) || "00".equals(status) || "PAID".equalsIgnoreCase(status);
            return new VerificationResult(true, "SANDBOX_GATEWAY", externalTxnId, orderCode, amount, isSuccess,
                    isSuccess ? "Thanh toán thành công" : "Giao dịch không thành công: " + status);
        } catch (Exception e) {
            log.error("Error verifying JSON callback", e);
            return new VerificationResult(false, "SANDBOX_GATEWAY", null, null, null, false, e.getMessage());
        }
    }

    private VerificationResult verifyUrlEncodedCallback(String urlEncodedPayload, String headerSignature) {
        try {
            Map<String, String> params = new HashMap<>();
            String[] pairs = urlEncodedPayload.split("&");
            for (String pair : pairs) {
                int idx = pair.indexOf("=");
                if (idx > 0) {
                    String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                    String val = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                    params.put(key, val);
                }
            }

            String orderCode = params.getOrDefault("vnp_TxnRef", params.get("orderCode"));
            String externalTxnId = params.getOrDefault("vnp_TransactionNo", params.getOrDefault("externalTransactionId", "TXN-" + orderCode));
            String rawAmount = params.get("vnp_Amount");
            BigDecimal amount = null;
            if (rawAmount != null) {
                // VNPay amount is in VND * 100
                amount = new BigDecimal(rawAmount).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            } else if (params.get("amount") != null) {
                amount = new BigDecimal(params.get("amount"));
            }

            String responseCode = params.getOrDefault("vnp_ResponseCode", params.get("status"));
            String secureHash = params.getOrDefault("vnp_SecureHash", headerSignature);

            if (secureHash == null || secureHash.isBlank()) {
                return new VerificationResult(false, "SANDBOX_GATEWAY", externalTxnId, orderCode, amount, false, "Thiếu chữ ký xác thực");
            }

            // Build VNPay sorted fields data
            Map<String, String> vnpParams = new TreeMap<>();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (entry.getKey().startsWith("vnp_") && !entry.getKey().equals("vnp_SecureHash") && !entry.getKey().equals("vnp_SecureHashType")) {
                    vnpParams.put(entry.getKey(), entry.getValue());
                }
            }

            StringBuilder hashData = new StringBuilder();
            for (Iterator<Map.Entry<String, String>> itr = vnpParams.entrySet().iterator(); itr.hasNext(); ) {
                Map.Entry<String, String> entry = itr.next();
                hashData.append(URLEncoder.encode(entry.getKey(), StandardCharsets.US_ASCII));
                hashData.append('=');
                hashData.append(URLEncoder.encode(entry.getValue(), StandardCharsets.US_ASCII));
                if (itr.hasNext()) {
                    hashData.append('&');
                }
            }

            String effectiveSecret = (vnpayHashSecret != null && !vnpayHashSecret.isBlank()) ? vnpayHashSecret : secretKey;
            boolean signatureMatches = verifyHmacSha512(hashData.toString(), secureHash, effectiveSecret)
                    || verifyHmacSha512(urlEncodedPayload, secureHash, secretKey);

            if (!signatureMatches) {
                log.warn("Invalid signature on URL encoded callback for order {}", orderCode);
                return new VerificationResult(false, "SANDBOX_GATEWAY", externalTxnId, orderCode, amount, false, "Chữ ký không hợp lệ");
            }

            boolean isSuccess = "00".equals(responseCode) || "SUCCESS".equalsIgnoreCase(responseCode);
            return new VerificationResult(true, "SANDBOX_GATEWAY", externalTxnId, orderCode, amount, isSuccess,
                    isSuccess ? "Thanh toán VNPay thành công" : "Giao dịch VNPay thất bại mã: " + responseCode);
        } catch (Exception e) {
            log.error("Error verifying URL encoded callback", e);
            return new VerificationResult(false, "SANDBOX_GATEWAY", null, null, null, false, e.getMessage());
        }
    }

    private String buildVnpayPaymentUrl(String orderCode, long amountVnd, String description) {
        try {
            Map<String, String> vnpParams = new TreeMap<>();
            vnpParams.put("vnp_Version", "2.1.0");
            vnpParams.put("vnp_Command", "pay");
            vnpParams.put("vnp_TmnCode", vnpayTmnCode);
            vnpParams.put("vnp_Amount", String.valueOf(amountVnd * 100)); // VNPay amount x 100
            vnpParams.put("vnp_CurrCode", "VND");
            vnpParams.put("vnp_TxnRef", orderCode);
            vnpParams.put("vnp_OrderInfo", description);
            vnpParams.put("vnp_OrderType", "other");
            vnpParams.put("vnp_Locale", "vn");
            vnpParams.put("vnp_ReturnUrl", vnpayReturnUrl);
            vnpParams.put("vnp_IpAddr", "127.0.0.1");
            vnpParams.put("vnp_CreateDate", LocalDateTime.now().format(VNP_DATE_FORMAT));

            StringBuilder query = new StringBuilder();
            StringBuilder hashData = new StringBuilder();
            for (Iterator<Map.Entry<String, String>> itr = vnpParams.entrySet().iterator(); itr.hasNext(); ) {
                Map.Entry<String, String> entry = itr.next();
                query.append(URLEncoder.encode(entry.getKey(), StandardCharsets.US_ASCII));
                query.append('=');
                query.append(URLEncoder.encode(entry.getValue(), StandardCharsets.US_ASCII));

                hashData.append(URLEncoder.encode(entry.getKey(), StandardCharsets.US_ASCII));
                hashData.append('=');
                hashData.append(URLEncoder.encode(entry.getValue(), StandardCharsets.US_ASCII));

                if (itr.hasNext()) {
                    query.append('&');
                    hashData.append('&');
                }
            }

            String effectiveSecret = (vnpayHashSecret != null && !vnpayHashSecret.isBlank()) ? vnpayHashSecret : secretKey;
            String vnpSecureHash = hmacSha512Hex(hashData.toString(), effectiveSecret);
            query.append("&vnp_SecureHash=").append(vnpSecureHash);
            return vnpayUrl + "?" + query;
        } catch (Exception e) {
            log.error("Failed to build VNPay URL", e);
            return vnpayUrl;
        }
    }

    public String signCanonical(String orderCode, BigDecimal amount, String externalTransactionId, String status) {
        String canonicalData = String.format("%s|%s|%s|%s",
                orderCode != null ? orderCode : "",
                amount != null ? amount.stripTrailingZeros().toPlainString() : "",
                externalTransactionId != null ? externalTransactionId : "",
                status != null ? status : "");
        return hmacSha512Hex(canonicalData, secretKey);
    }

    public static String hmacSha512Hex(String data, String key) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA512);
            SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA512);
            mac.init(secretKeySpec);
            byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC SHA-512 calculation failed", e);
        }
    }

    private static boolean verifyHmacSha512(String data, String signature, String key) {
        try {
            String computed = hmacSha512Hex(data, key);
            byte[] computedBytes = computed.toLowerCase().getBytes(StandardCharsets.UTF_8);
            byte[] actualBytes = signature.toLowerCase().getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(computedBytes, actualBytes);
        } catch (Exception e) {
            return false;
        }
    }
}
