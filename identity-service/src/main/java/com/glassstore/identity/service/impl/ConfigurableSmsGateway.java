package com.glassstore.identity.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glassstore.identity.service.SmsGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ConfigurableSmsGateway implements SmsGateway {

    private final ObjectMapper objectMapper;

    @Value("${sms.provider.url:}")
    private String providerUrl;

    @Value("${sms.provider.api-key:}")
    private String apiKey;

    @Value("${sms.provider.sender:OptiVision}")
    private String sender;

    @Override
    public void sendOtp(String phoneNumber, String otpCode, String purpose, LocalDateTime expiresAt) {
        if (providerUrl == null || providerUrl.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SMS_PROVIDER_NOT_CONFIGURED: Nhà cung cấp SMS OTP chưa được cấu hình trên môi trường này"
            );
        }

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "to", phoneNumber,
                    "sender", sender,
                    "purpose", purpose,
                    "otpCode", otpCode,
                    "expiresAt", expiresAt.toString()
            ));

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(providerUrl.trim()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));

            if (apiKey != null && !apiKey.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + apiKey.trim());
            }

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "SMS_PROVIDER_REJECTED: Nhà cung cấp SMS từ chối gửi OTP (HTTP " + response.statusCode() + ")"
                );
            }
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "SMS_PROVIDER_UNAVAILABLE: Không thể kết nối tới cổng SMS OTP"
            );
        }
    }
}

