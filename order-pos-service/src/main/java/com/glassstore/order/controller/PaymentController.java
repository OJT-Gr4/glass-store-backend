package com.glassstore.order.controller;

import com.glassstore.common.dto.ApiResponse;
import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.common.security.CurrentActor;
import com.glassstore.order.dto.OrderDtos;
import com.glassstore.order.entity.Order;
import com.glassstore.order.entity.Payment;
import com.glassstore.order.payment.PaymentGateway;
import com.glassstore.order.payment.SePayGateway;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
        if (!(paymentGateway instanceof SePayGateway)) {
            throw ApiException.of(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED);
        }
        PaymentService.CallbackOutcome outcome = paymentService.handleCallback(rawPayload, ipnSecret);
        return ResponseEntity.ok(Map.of("success", true, "replayed", outcome.replayed()));
    }

    /** Configuration presence only; does not claim merchant credentials have passed a live sandbox test. */
    @GetMapping("/sepay/configuration")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sepayConfiguration() {
        List<String> missing = paymentGateway instanceof SePayGateway sepay
                ? sepay.missingConfiguration() : List.of("PAYMENT_PROVIDER=sepay");
        return ResponseEntity.ok(ApiResponse.success("SePay Sandbox configuration",
                Map.of("provider", "SEPAY", "environment", "sandbox", "configured", missing.isEmpty(),
                        "status", missing.isEmpty() ? "CONFIGURED" : "BLOCKED", "missing", missing)));
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
}
