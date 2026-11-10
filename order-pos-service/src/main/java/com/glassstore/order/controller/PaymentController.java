package com.glassstore.order.controller;

import com.glassstore.common.dto.ApiResponse;
import com.glassstore.order.dto.OrderDtos;
import com.glassstore.order.entity.Order;
import com.glassstore.order.entity.Payment;
import com.glassstore.order.payment.ConfigurableSandboxPaymentGateway;
import com.glassstore.order.payment.PaymentGateway;
import com.glassstore.order.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentGateway paymentGateway;
    private final com.glassstore.order.repository.OrderRepository orders;

    private void requireOwner(Long id) {
        var order = orders.findById(id).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        com.glassstore.common.security.CurrentActor.requireOwner(order.getCustomerId());
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<ApiResponse<List<Payment>>> history(@PathVariable("orderId") Long orderId) {
        requireOwner(orderId);
        return ResponseEntity.ok(ApiResponse.success("OK", paymentService.history(orderId)));
    }

    @PostMapping("/order/{orderId}/sync")
    public ResponseEntity<ApiResponse<List<Payment>>> sync(@PathVariable("orderId") Long orderId) {
        requireOwner(orderId);
        return ResponseEntity.ok(ApiResponse.success("OK", paymentService.history(orderId)));
    }

    @PostMapping("/order/{orderId}/initiate")
    public ResponseEntity<ApiResponse<PaymentGateway.InitiationResult>> initiate(
            @PathVariable("orderId") Long orderId) {
        requireOwner(orderId);
        return ResponseEntity.ok(ApiResponse.success("Đã khởi tạo thanh toán", paymentService.initiate(orderId)));
    }

    @PostMapping("/callback")
    public ResponseEntity<ApiResponse<Payment>> callback(
            @RequestHeader(value = "X-Payment-Signature", required = false) String signature,
            @RequestBody String rawPayload) {
        PaymentService.CallbackOutcome outcome = paymentService.handleCallback(rawPayload, signature);
        return ResponseEntity.ok(ApiResponse.success(
                outcome.replayed() ? "Giao dịch đã được ghi nhận trước đó" : "Đã xác thực thanh toán",
                outcome.payment()));
    }

    @PostMapping("/sandbox/simulate-callback")
    public ResponseEntity<ApiResponse<Payment>> simulateCallback(
            @RequestParam(name = "orderCode") String orderCode,
            @RequestParam(name = "amount", required = false) BigDecimal amount,
            @RequestParam(name = "status", defaultValue = "SUCCESS") String status) {
        Order order = orders.findByOrderCode(orderCode).orElseThrow(() ->
                new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy đơn: " + orderCode));
        BigDecimal effectiveAmount = amount != null ? amount : order.getFinalAmount();
        String externalTxnId = "TXN-SIM-" + orderCode + "-" + System.currentTimeMillis();

        String sig = "";
        if (paymentGateway instanceof ConfigurableSandboxPaymentGateway sandbox) {
            sig = sandbox.signCanonical(orderCode, effectiveAmount, externalTxnId, status);
        }
        String payload = String.format("{\"orderCode\":\"%s\",\"amount\":\"%s\",\"externalTransactionId\":\"%s\",\"status\":\"%s\",\"signature\":\"%s\"}",
                orderCode, effectiveAmount.stripTrailingZeros().toPlainString(), externalTxnId, status, sig);

        PaymentService.CallbackOutcome outcome = paymentService.handleCallback(payload, sig);
        return ResponseEntity.ok(ApiResponse.success(
                outcome.replayed() ? "Giao dịch đã được ghi nhận trước đó" : "Đã giả lập callback thanh toán thành công",
                outcome.payment()));
    }

    @PostMapping(value = {"/sepay/simulate-ipn", "/sepay/simulate-callback"})
    public ResponseEntity<ApiResponse<Payment>> simulateSepayIpn(
            @RequestParam(name = "orderCode") String orderCode,
            @RequestParam(name = "amount", required = false) BigDecimal amount) {
        return simulateCallback(orderCode, amount, "SUCCESS");
    }

    @GetMapping(value = "/sandbox/quick-pay", produces = org.springframework.http.MediaType.TEXT_HTML_VALUE)
    @org.springframework.web.bind.annotation.ResponseBody
    public String quickPayHtml(@RequestParam(name = "orderCode") String orderCode) {
        try {
            simulateCallback(orderCode, null, "SUCCESS");
            return """
                <!DOCTYPE html>
                <html lang="vi">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>SePay Sandbox - Thanh toán thành công</title>
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; display: flex; align-items: center; justify-content: center; min-height: 100vh; margin: 0; background-color: #f0fdf4; color: #166534; padding: 20px; box-sizing: border-box; }
                        .card { background: white; border-radius: 16px; padding: 32px 24px; text-align: center; box-shadow: 0 10px 25px -5px rgba(0,0,0,0.1); max-width: 400px; width: 100%; border: 1px solid #bbf7d0; }
                        .icon { width: 64px; height: 64px; background: #dcfce7; border-radius: 50%; display: flex; align-items: center; justify-content: center; margin: 0 auto 16px; font-size: 32px; font-weight: bold; }
                        h1 { font-size: 20px; margin: 0 0 8px; color: #15803d; }
                        p { font-size: 14px; color: #4b5563; margin: 0 0 20px; line-height: 1.5; }
                        .badge { display: inline-block; background: #f0fdf4; color: #166534; padding: 6px 14px; border-radius: 999px; font-weight: 600; font-size: 13px; border: 1px solid #86efac; }
                    </style>
                </head>
                <body>
                    <div class="card">
                        <div class="icon">✓</div>
                        <h1>Thanh Toán Thành Công!</h1>
                        <p>Đơn hàng <strong>%s</strong> đã được xác nhận thanh toán giả lập qua SePay Sandbox.</p>
                        <div class="badge">Màn hình POS đã tự động ghi nhận</div>
                    </div>
                </body>
                </html>
                """.formatted(orderCode);
        } catch (Exception e) {
            return """
                <!DOCTYPE html>
                <html lang="vi">
                <head><meta charset="UTF-8"><title>Lỗi</title></head>
                <body style="font-family: sans-serif; text-align: center; padding: 40px;">
                    <h2 style="color: #dc2626;">Không thể xác nhận đơn hàng</h2>
                    <p>%s</p>
                </body>
                </html>
                """.formatted(e.getMessage());
        }
    }

    @PostMapping("/order/{orderId}/refund-request")
    public ResponseEntity<ApiResponse<Order>> refundRequest(
            @PathVariable("orderId") Long orderId,
            @RequestParam(name = "actor", required = false) String actor,
            @RequestBody(required = false) OrderDtos.CancelRequest body) {
        requireOwner(orderId);
        actor = com.glassstore.common.security.CurrentActor.name();
        String reason = body == null ? null : body.getReason();
        return ResponseEntity.ok(ApiResponse.success("Đã ghi nhận yêu cầu hoàn tiền",
                paymentService.requestRefund(orderId, actor, reason)));
    }

    @PostMapping("/order/{orderId}/refund-confirm")
    public ResponseEntity<ApiResponse<Order>> refundConfirm(
            @PathVariable("orderId") Long orderId,
            @RequestBody(required = false) OrderDtos.CancelRequest body) {
        if (com.glassstore.common.security.CurrentActor.customer()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "Chỉ nhân viên được phép xác nhận hoàn tiền");
        }
        String actor = com.glassstore.common.security.CurrentActor.name();
        String reason = body == null ? null : body.getReason();
        return ResponseEntity.ok(ApiResponse.success("Đã xác nhận hoàn tiền thành công",
                paymentService.confirmRefund(orderId, actor, reason)));
    }
}
