package com.glassstore.identity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Slf4j
public class NotificationChannelDispatcher {

    public static final String STATUS_DELIVERED = "DELIVERED";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_BLOCKED_UNCONFIGURED = "BLOCKED_PROVIDER_UNCONFIGURED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${notification.sms.provider-url:}")
    private String smsProviderUrl;

    @Value("${notification.sms.api-key:}")
    private String smsApiKey;

    @Value("${notification.sms.sender:OptiVision}")
    private String smsSender;

    @Value("${notification.email.provider-url:}")
    private String emailProviderUrl;

    @Value("${notification.email.api-key:}")
    private String emailApiKey;

    @Value("${notification.email.from:no-reply@optivision.vn}")
    private String emailFrom;

    @Autowired
    public NotificationChannelDispatcher(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), "", "", "OptiVision", "", "", "no-reply@optivision.vn");
    }

    public NotificationChannelDispatcher(
            ObjectMapper objectMapper,
            HttpClient httpClient,
            String smsProviderUrl,
            String smsApiKey,
            String smsSender,
            String emailProviderUrl,
            String emailApiKey,
            String emailFrom) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.smsProviderUrl = smsProviderUrl;
        this.smsApiKey = smsApiKey;
        this.smsSender = smsSender;
        this.emailProviderUrl = emailProviderUrl;
        this.emailApiKey = emailApiKey;
        this.emailFrom = emailFrom;
    }

    public boolean isSmsProviderConfigured() {
        return isRealHttpUrl(smsProviderUrl);
    }

    public boolean isEmailProviderConfigured() {
        return isRealHttpUrl(emailProviderUrl);
    }

    public DispatchOutcome dispatchSms(boolean channelEnabled, boolean isCustomerRecipient, String phoneNumber,
                                       String referenceCode, String title, String message) {
        if (!isCustomerRecipient) {
            return new DispatchOutcome(STATUS_SKIPPED, "Thông báo nội bộ sử dụng In-App Portal");
        }
        if (!channelEnabled) {
            return new DispatchOutcome(STATUS_SKIPPED, "Kênh SMS giao dịch đang tắt trong cấu hình");
        }
        if (!isSmsProviderConfigured()) {
            return new DispatchOutcome(STATUS_BLOCKED_UNCONFIGURED, "Chưa cấu hình cổng SMS ngoài");
        }
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return new DispatchOutcome(STATUS_SKIPPED, "Khách hàng chưa có số điện thoại");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("to", phoneNumber.trim());
        payload.put("sender", smsSender);
        payload.put("referenceCode", referenceCode);
        payload.put("title", title);
        payload.put("message", message);
        return sendHttp(smsProviderUrl, smsApiKey, payload, "SMS");
    }

    public DispatchOutcome dispatchEmail(boolean channelEnabled, boolean isCustomerRecipient, String email,
                                         String referenceCode, String title, String message) {
        if (!isCustomerRecipient) {
            return new DispatchOutcome(STATUS_SKIPPED, "Thông báo nội bộ sử dụng In-App Portal");
        }
        if (!channelEnabled) {
            return new DispatchOutcome(STATUS_SKIPPED, "Kênh Email giao dịch đang tắt trong cấu hình");
        }
        if (!isEmailProviderConfigured()) {
            return new DispatchOutcome(STATUS_BLOCKED_UNCONFIGURED, "Chưa cấu hình cổng Email ngoài");
        }
        if (email == null || email.isBlank()) {
            return new DispatchOutcome(STATUS_SKIPPED, "Khách hàng chưa có địa chỉ email");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("to", email.trim());
        payload.put("from", emailFrom);
        payload.put("subject", title);
        payload.put("body", message);
        payload.put("referenceCode", referenceCode);
        return sendHttp(emailProviderUrl, emailApiKey, payload, "Email");
    }

    private DispatchOutcome sendHttp(String url, String apiKey, Map<String, Object> payload, String channelName) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(url.trim()))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (apiKey != null && !apiKey.isBlank()) {
                req.header("Authorization", "Bearer " + apiKey.trim());
            }
            HttpResponse<String> response = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new DispatchOutcome(STATUS_SENT, "Đã gửi qua cổng " + channelName + " (HTTP " + response.statusCode() + ")");
            }
            log.warn("{} provider returned non-2xx HTTP {}", channelName, response.statusCode());
            return new DispatchOutcome(STATUS_FAILED, "Cổng " + channelName + " từ chối (HTTP " + response.statusCode() + ")");
        } catch (Exception ex) {
            log.warn("{} provider dispatch failed: {}", channelName, ex.getMessage());
            return new DispatchOutcome(STATUS_FAILED, "Lỗi kết nối cổng " + channelName + ": " + ex.getMessage());
        }
    }

    private static boolean isRealHttpUrl(String url) {
        if (url == null) return false;
        String clean = url.trim().toLowerCase();
        return (clean.startsWith("http://") || clean.startsWith("https://"))
                && !clean.contains("mock")
                && !clean.contains("sandbox-fake");
    }

    public record DispatchOutcome(String status, String detail) {}
}
