package com.glassstore.order.controller;

import com.glassstore.common.dto.ApiResponse;
import com.glassstore.order.config.VietQrProperties;
import com.glassstore.order.dto.VietQrDtos.*;
import com.glassstore.order.entity.Order;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.service.VietQrService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class VietQrCallbackController {

    private final VietQrService vietQrService;
    private final VietQrProperties properties;
    private final OrderRepository orderRepository;

    /* =========================================================================
     * BƯỚC 1: API Get Token
     * VietQR gọi sang đối tác để lấy Bearer access_token
     * Method: POST
     * Endpoint: /api/vietqr/token_generate hoặc /api/vietqr/token
     * Header: Authorization: Basic Base64[username:password]
     * ========================================================================= */
    @PostMapping(value = {"/api/vietqr/token_generate", "/api/vietqr/token", "/api/token_generate"})
    public ResponseEntity<TokenResponse> getToken(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        log.info("Nhận request VietQR Bước 1: API Get Token");
        try {
            TokenResponse token = vietQrService.generatePartnerToken(authHeader);
            return ResponseEntity.ok(token);
        } catch (Exception e) {
            log.warn("VietQR Get Token thất bại: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    /* =========================================================================
     * BƯỚC 2: API Transaction Sync
     * VietQR đồng bộ thông tin biến động số dư khi có giao dịch chuyển tiền
     * Method: POST
     * Endpoint: /bank/api/transaction-sync hoặc /api/vietqr/transaction-sync
     * Header: Authorization: Bearer <access_token>
     * ========================================================================= */
    @PostMapping(value = {"/bank/api/transaction-sync", "/api/vietqr/transaction-sync"})
    public ResponseEntity<TransactionSyncResponse> transactionSync(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody TransactionSyncRequest request) {
        log.info("Nhận request VietQR Bước 2: Transaction Sync cho txId: {}", request.getTransactionId());

        // Kiểm tra Bearer Token do hệ thống của đối tác cấp ở Bước 1
        if (authHeader != null && !authHeader.isBlank()) {
            boolean valid = vietQrService.validatePartnerToken(authHeader);
            if (!valid) {
                log.warn("VietQR Transaction Sync: Bearer Token không hợp lệ hoặc đã hết hạn");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(TransactionSyncResponse.failure("INVALID_AUTH_HEADER"));
            }
        }

        TransactionSyncResponse response = vietQrService.processTransactionSync(request);
        return ResponseEntity.ok(response);
    }

    /* =========================================================================
     * BƯỚC 4: Tạo mã VietQR động theo đơn hàng POS
     * ========================================================================= */
    @PostMapping("/api/payments/vietqr/generate")
    public ResponseEntity<ApiResponse<GenerateCustomerResponse>> generateQr(
            @RequestParam("orderCode") String orderCode) {
        Order order = orderRepository.findByOrderCode(orderCode).orElseThrow(() ->
                new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn: " + orderCode));

        GenerateCustomerResponse qr = vietQrService.generateVietQrCode(order.getOrderCode(), order.getFinalAmount());
        return ResponseEntity.ok(ApiResponse.success("Tạo mã VietQR thành công", qr));
    }

    /* =========================================================================
     * BƯỚC 5: Gọi API Test Callback trên Sandbox của VietQR
     * Method: POST
     * Endpoint: /api/payments/vietqr/test-callback?orderCode=...
     * ========================================================================= */
    @PostMapping("/api/payments/vietqr/test-callback")
    public ResponseEntity<ApiResponse<TransactionSyncResponse>> testCallback(
            @RequestParam("orderCode") String orderCode) {
        Order order = orderRepository.findByOrderCode(orderCode).orElseThrow(() ->
                new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn: " + orderCode));

        TransactionSyncResponse res = vietQrService.callTestCallback(order.getOrderCode(), order.getFinalAmount());
        return ResponseEntity.ok(ApiResponse.success("Đã kích hoạt Test Callback VietQR thành công", res));
    }

    /* =========================================================================
     * THÔNG TIN CẤU HÌNH KẾT NỐI (Để đối tác gửi cho VietQR Golive / Ký hợp đồng)
     * ========================================================================= */
    @GetMapping("/api/payments/vietqr/config-info")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getConfigInfo(
            @RequestHeader(value = "Host", required = false) String host) {
        String baseHost = host != null ? host : "127.0.0.1:8080";
        String protocol = baseHost.contains("localhost") || baseHost.contains("127.0.0.1") ? "http" : "https";

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("merchantName", "CÔNG TY KÍNH MẮT OPTIVISION (GlassStore)");
        info.put("apiUrl", protocol + "://" + baseHost);
        info.put("urlPathSuffix", "/bank/api/transaction-sync");
        info.put("fullWebhookUrl", protocol + "://" + baseHost + "/bank/api/transaction-sync");
        info.put("partnerGetTokenUrl", protocol + "://" + baseHost + "/api/vietqr/token_generate");
        info.put("username", properties.getPartner().getUsername());
        info.put("password", properties.getPartner().getPassword());
        info.put("bankCode", properties.getPartner().getBankCode());
        info.put("bankAccount", properties.getPartner().getBankAccount());
        info.put("userBankName", properties.getPartner().getUserBankName());
        info.put("vietqrSandboxBaseUrl", properties.getApi().getBaseUrl());
        return ResponseEntity.ok(ApiResponse.success("Thông tin kết nối VietQR Pro Callback", info));
    }
}
