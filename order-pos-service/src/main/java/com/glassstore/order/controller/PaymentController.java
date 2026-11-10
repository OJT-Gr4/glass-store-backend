package com.glassstore.order.controller;

import com.glassstore.common.dto.ApiResponse;
import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.common.security.CurrentActor;
import com.glassstore.order.dto.OrderDtos;
import com.glassstore.order.entity.Order;
import com.glassstore.order.entity.Payment;
import com.glassstore.order.payment.ConfigurableSandboxPaymentGateway;
import com.glassstore.order.payment.PaymentGateway;
import com.glassstore.order.payment.SePayGateway;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentGateway paymentGateway;
    private final OrderRepository orders;
    private final org.springframework.beans.factory.ObjectProvider<SePayGateway> sePayGatewayProvider;

    private void requireOwner(Long id) {
        var order = orders.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        CurrentActor.requireOwner(order.getCustomerId());
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<ApiResponse<List<Payment>>> history(@PathVariable("orderId") Long orderId) {
        requireOwner(orderId);
        return ResponseEntity.ok(ApiResponse.success("OK", paymentService.history(orderId)));
    }

    @PostMapping("/order/{orderId}/sync")
    public ResponseEntity<ApiResponse<List<Payment>>> sync(@PathVariable("orderId") Long orderId) {
        requireOwner(orderId);
        return ResponseEntity.ok(ApiResponse.success("Đã đồng bộ trạng thái thanh toán", paymentService.history(orderId)));
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

    /** Public to SePay, authenticated by the independent IPN key inside the adapter. */
    @PostMapping("/sepay/ipn")
    public ResponseEntity<Map<String, Object>> sepayIpn(
            @RequestHeader(value = "X-Secret-Key", required = false) String ipnSecret,
            @RequestBody String rawPayload) {
        SePayGateway sepay = sePayGatewayProvider.getIfAvailable();
        if (sepay == null && !(paymentGateway instanceof SePayGateway)) {
            throw ApiException.of(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED);
        }
        PaymentService.CallbackOutcome outcome = paymentService.handleCallback(rawPayload, ipnSecret);
        return ResponseEntity.ok(Map.of("success", true, "replayed", outcome.replayed()));
    }

    /** Configuration presence only; does not claim merchant credentials have passed a live sandbox test. */
    @GetMapping("/sepay/configuration")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sepayConfiguration() {
        SePayGateway sepay = sePayGatewayProvider.getIfAvailable();
        if (sepay == null && paymentGateway instanceof SePayGateway sg) {
            sepay = sg;
        }
        List<String> missing = sepay != null ? sepay.missingConfiguration() : List.of("PAYMENT_PROVIDER=sepay");
        return ResponseEntity.ok(ApiResponse.success("SePay Sandbox configuration",
                Map.of("provider", "SEPAY", "environment", "sandbox", "configured", missing.isEmpty(),
                        "status", missing.isEmpty() ? "CONFIGURED" : "BLOCKED", "missing", missing)));
    }

    @GetMapping("/sepay/config")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sepayConfig() {
        return sepayConfiguration();
    }

    @PostMapping("/sandbox/simulate-callback")
    public ResponseEntity<ApiResponse<Payment>> simulateCallback(
            @RequestParam(name = "orderCode") String orderCode,
            @RequestParam(name = "amount", required = false) BigDecimal amount,
            @RequestParam(name = "status", defaultValue = "SUCCESS") String status) {
        Order order = orders.findByOrderCode(orderCode).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn: " + orderCode));
        BigDecimal effectiveAmount = amount != null ? amount : order.getFinalAmount();
        String externalTxnId = "TXN-SIM-" + orderCode + "-" + System.currentTimeMillis();

        String sig = "";
        ConfigurableSandboxPaymentGateway sandbox = null;
        if (paymentGateway instanceof ConfigurableSandboxPaymentGateway csg) {
            sandbox = csg;
        } else if (paymentGateway instanceof com.glassstore.order.payment.DelegatingPaymentGateway dpg
                && dpg.delegate() instanceof ConfigurableSandboxPaymentGateway csg) {
            sandbox = csg;
        }
        if (sandbox != null) {
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
        actor = CurrentActor.name();
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
