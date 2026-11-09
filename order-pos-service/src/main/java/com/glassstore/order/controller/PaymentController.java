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
