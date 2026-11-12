package com.glassstore.order.controller;

import com.glassstore.common.dto.ApiResponse;
import com.glassstore.order.config.VietQrProperties;
import com.glassstore.order.payment.ConfigurableSandboxPaymentGateway;
import com.glassstore.order.payment.SePayBankWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes public VietQR beneficiary metadata and inbound SePay Bank Transaction Webhooks (my.sepay.vn).
 */
@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SePayBankWebhookController {

    private final SePayBankWebhookService webhookService;
    private final VietQrProperties vietQrProperties;
    private final ObjectProvider<ConfigurableSandboxPaymentGateway> sandboxGatewayProvider;

    @PostMapping(value = {"/api/payments/sepay/webhook", "/api/payments/sepay/bank-webhook"})
    public ResponseEntity<Map<String, Object>> receiveBankWebhook(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Api-Key", required = false) String xApiKey,
            @RequestHeader(value = "X-Secret-Key", required = false) String xSecretKey,
            @RequestBody String rawPayload) {
        SePayBankWebhookService.WebhookOutcome outcome = webhookService.handleWebhook(
                authHeader, xApiKey, xSecretKey, rawPayload);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", outcome.success());
        body.put("replayed", outcome.replayed());
        body.put("ignored", outcome.ignored());
        if (outcome.orderCode() != null) {
            body.put("orderCode", outcome.orderCode());
        }
        if (outcome.externalTransactionId() != null) {
            body.put("externalTransactionId", outcome.externalTransactionId());
        }
        body.put("message", outcome.message());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/payments/sepay/webhook/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> webhookStatus() {
        Map<String, String> bank = resolveBankInfo();
        String accountNo = bank.getOrDefault("accountNo", "");
        String maskedAccount = accountNo.length() <= 4 ? accountNo : "****" + accountNo.substring(accountNo.length() - 4);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configured", webhookService.isConfigured());
        data.put("webhookPath", "/api/payments/sepay/webhook");
        data.put("authHeader", "Authorization: Apikey <SEPAY_WEBHOOK_API_KEY>");
        data.put("bankName", bank.getOrDefault("bankName", ""));
        data.put("accountNoMasked", maskedAccount);
        return ResponseEntity.ok(ApiResponse.success("SePay Bank Webhook status", data));
    }

    @GetMapping("/api/payments/vietqr/account-info")
    public ResponseEntity<ApiResponse<Map<String, String>>> vietQrAccountInfo() {
        return ResponseEntity.ok(ApiResponse.success("OK", resolveBankInfo()));
    }

    private Map<String, String> resolveBankInfo() {
        ConfigurableSandboxPaymentGateway gateway = sandboxGatewayProvider.getIfAvailable();
        Map<String, String> info = new LinkedHashMap<>();
        if (gateway != null) {
            info.putAll(gateway.getBankAccountInfo());
        } else {
            info.put("bankId", "970422");
            info.put("bankName", "MBBank (Ngân Hàng Quân Đội)");
            info.put("accountNo", vietQrProperties.getPartner().getBankAccount());
            info.put("accountName", vietQrProperties.getPartner().getUserBankName());
            info.put("template", "compact2");
        }
        info.put("bankCode", vietQrProperties.getPartner().getBankCode());
        return info;
    }
}
