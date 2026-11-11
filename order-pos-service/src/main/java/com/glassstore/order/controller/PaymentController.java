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

    @GetMapping("/sandbox/host-info")
    public ResponseEntity<ApiResponse<java.util.Map<String, String>>> hostInfo() {
        String host = "127.0.0.1";
        try {
            host = java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception ignored) {}
        return ResponseEntity.ok(ApiResponse.success("OK", java.util.Map.of(
            "ip", host,
            "port", "8083"
        )));
    }

    @GetMapping(value = {"/sandbox/quick-pay", "/sandbox/vietqr-app"}, produces = org.springframework.http.MediaType.TEXT_HTML_VALUE)
    @org.springframework.web.bind.annotation.ResponseBody
    public String vietQrAppHtml(@RequestParam(name = "orderCode") String orderCode) {
        Order order = orders.findByOrderCode(orderCode).orElse(null);
        if (order == null) {
            return """
                <!DOCTYPE html>
                <html lang="vi">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Lỗi đơn hàng - MBBank VietQR</title>
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #f1f5f9; display: flex; align-items: center; justify-content: center; min-height: 100vh; margin: 0; padding: 16px; }
                        .card { background: white; border-radius: 16px; padding: 32px 24px; text-align: center; max-width: 380px; width: 100%; box-shadow: 0 4px 12px rgba(0,0,0,0.08); }
                    </style>
                </head>
                <body>
                    <div class="card">
                        <div style="font-size: 48px; margin-bottom: 12px;">⚠️</div>
                        <h2 style="color: #0f172a; margin: 0 0 8px;">Không tìm thấy đơn hàng</h2>
                        <p style="color: #64748b; font-size: 14px;">Mã đơn: <strong>%s</strong> không tồn tại trong hệ thống.</p>
                    </div>
                </body>
                </html>
                """.formatted(orderCode);
        }

        String formattedAmount = java.text.NumberFormat.getInstance(java.util.Locale.forLanguageTag("vi-VN")).format(order.getFinalAmount()) + " đ";
        boolean isPaid = order.getStatus() == com.glassstore.order.domain.OrderStatus.PAID;

        return """
            <!DOCTYPE html>
            <html lang="vi">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <title>MBBank · Xác Nhận Chuyển Tiền VietQR</title>
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; }
                    body { background-color: #f1f5f9; display: flex; justify-content: center; min-height: 100vh; color: #0f172a; }
                    .app-container { width: 100%%; max-width: 420px; background-color: #f8fafc; min-height: 100vh; display: flex; flex-direction: column; position: relative; box-shadow: 0 0 24px rgba(0,0,0,0.06); }
                    
                    /* MBBank Blue Header */
                    .mb-header { background: linear-gradient(135deg, #0b2265 0%%, #1442b0 100%%); color: white; padding: 24px 20px 20px; border-bottom-left-radius: 20px; border-bottom-right-radius: 20px; }
                    .mb-top { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
                    .mb-logo { font-size: 20px; font-weight: 800; letter-spacing: 0.5px; display: flex; align-items: center; gap: 6px; }
                    .mb-badge { background: rgba(255,255,255,0.2); padding: 4px 10px; border-radius: 12px; font-size: 11px; font-weight: 600; text-transform: uppercase; }
                    .header-title { font-size: 16px; font-weight: 700; margin-bottom: 4px; }
                    .header-sub { font-size: 12px; opacity: 0.85; }

                    /* Content */
                    .content { padding: 16px; flex: 1; display: flex; flex-direction: column; gap: 14px; }

                    /* Card Source Account */
                    .card-source { background: white; border-radius: 14px; padding: 14px 16px; border: 1px solid #e2e8f0; }
                    .source-label { font-size: 11.5px; color: #64748b; font-weight: 600; text-transform: uppercase; margin-bottom: 6px; }
                    .source-name { font-size: 14px; font-weight: 700; color: #1e293b; }
                    .source-num { font-size: 12.5px; color: #475569; font-mono; margin-top: 2px; }
                    .source-balance { font-size: 12px; color: #0284c7; font-weight: 600; margin-top: 4px; }

                    /* Card Transfer Details */
                    .card-details { background: white; border-radius: 14px; padding: 16px; border: 1px solid #e2e8f0; display: flex; flex-direction: column; gap: 12px; }
                    .amount-box { text-align: center; padding: 12px 0 16px; border-bottom: 1px dashed #e2e8f0; }
                    .amount-label { font-size: 12px; color: #64748b; font-weight: 600; }
                    .amount-value { font-size: 28px; font-weight: 800; color: #1e40af; margin-top: 4px; }

                    .row-item { display: flex; justify-content: space-between; font-size: 13px; line-height: 1.4; }
                    .row-label { color: #64748b; }
                    .row-val { font-weight: 600; color: #0f172a; text-align: right; max-width: 60%%; word-break: break-word; }

                    /* Button */
                    .action-box { padding: 16px; background: white; border-top: 1px solid #e2e8f0; position: sticky; bottom: 0; }
                    .btn-transfer { width: 100%%; background: linear-gradient(135deg, #1442b0 0%%, #0b2265 100%%); color: white; border: none; padding: 14px; border-radius: 12px; font-size: 15px; font-weight: 700; cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 8px; box-shadow: 0 4px 12px rgba(20,66,176,0.3); transition: opacity 0.2s; }
                    .btn-transfer:active { opacity: 0.85; }
                    .btn-transfer:disabled { opacity: 0.6; cursor: not-allowed; }

                    /* Success Screen */
                    .success-view { display: none; text-align: center; padding: 32px 20px; flex: 1; flex-direction: column; align-items: center; justify-content: center; }
                    .success-icon { width: 72px; height: 72px; background: #dcfce7; color: #16a34a; border-radius: 50%%; display: flex; align-items: center; justify-content: center; font-size: 36px; margin-bottom: 16px; animation: pop 0.4s ease-out; }
                    @keyframes pop { 0%% { transform: scale(0.5); opacity: 0; } 100%% { transform: scale(1); opacity: 1; } }
                    .success-title { font-size: 20px; font-weight: 800; color: #15803d; margin-bottom: 6px; }
                    .success-amount { font-size: 26px; font-weight: 800; color: #0f172a; margin-bottom: 14px; }
                    .success-card { background: white; border-radius: 12px; padding: 14px; width: 100%%; border: 1px solid #bbf7d0; font-size: 12.5px; text-align: left; display: flex; flex-direction: column; gap: 8px; margin-bottom: 20px; }
                    .pos-notice { background: #eff6ff; color: #1e40af; border: 1px solid #bfdbfe; padding: 10px; border-radius: 8px; font-size: 12px; font-weight: 600; width: 100%%; text-align: center; }
                </style>
            </head>
            <body>
                <div class="app-container">
                    <div class="mb-header">
                        <div class="mb-top">
                            <div class="mb-logo">
                                <span>MB</span>
                                <span style="font-size: 13px; font-weight: 400; opacity: 0.9;">BANK</span>
                            </div>
                            <span class="mb-badge">Napas 247 · VietQR</span>
                        </div>
                        <div class="header-title">Xác Nhận Chuyển Khoản Nhanh</div>
                        <div class="header-sub">Hệ thống chuyển khoản liên ngân hàng 24/7</div>
                    </div>

                    <div id="transferForm" class="content">
                        <!-- Nguồn chuyển -->
                        <div class="card-source">
                            <div class="source-label">Tài khoản nguồn</div>
                            <div class="source-name">TRẦN HỮU TRUNG PHONG</div>
                            <div class="source-num">0988 888 888 · MBBank</div>
                            <div class="source-balance">Số dư khả dụng: 50.000.000 đ</div>
                        </div>

                        <!-- Thông tin thụ hưởng -->
                        <div class="card-details">
                            <div class="amount-box">
                                <div class="amount-label">SỐ TIỀN CHUYỂN KHOẢN</div>
                                <div class="amount-value">%s</div>
                            </div>

                            <div class="row-item">
                                <span class="row-label">Ngân hàng nhận:</span>
                                <span class="row-val">MBBank (Quân Đội)</span>
                            </div>
                            <div class="row-item">
                                <span class="row-label">Tài khoản nhận:</span>
                                <span class="row-val" style="color: #1e40af; font-family: monospace;">0938773607</span>
                            </div>
                            <div class="row-item">
                                <span class="row-label">Tên người nhận:</span>
                                <span class="row-val" style="color: #047857;">CÔNG TY KÍNH MẮT OPTIVISION</span>
                            </div>
                            <div class="row-item">
                                <span class="row-label">Nội dung chuyển:</span>
                                <span class="row-val" style="color: #b91c1c; font-family: monospace;">%s</span>
                            </div>
                            <div class="row-item">
                                <span class="row-label">Phí giao dịch:</span>
                                <span class="row-val" style="color: #059669;">0 đ (Miễn phí)</span>
                            </div>
                        </div>
                    </div>

                    <!-- Màn hình thành công -->
                    <div id="successView" class="success-view">
                        <div class="success-icon">✓</div>
                        <div class="success-title">Chuyển Tiền Thành Công!</div>
                        <div class="success-amount">%s</div>
                        <div class="success-card">
                            <div class="row-item"><span class="row-label">Người nhận:</span><span class="row-val">CÔNG TY KÍNH MẮT OPTIVISION</span></div>
                            <div class="row-item"><span class="row-label">Mã giao dịch:</span><span class="row-val" id="resTxnId" style="font-family: monospace;">FT2611...</span></div>
                            <div class="row-item"><span class="row-label">Thời gian:</span><span class="row-val" id="resTime">...</span></div>
                            <div class="row-item"><span class="row-label">Mã đơn hàng:</span><span class="row-val" style="font-family: monospace;">%s</span></div>
                        </div>
                        <div class="pos-notice">
                            ✓ Cổng SePay đã xác thực giao dịch.<br>Màn hình quầy POS đã tự động in hóa đơn!
                        </div>
                    </div>

                    <!-- Nút bấm xác nhận -->
                    <div id="actionBox" class="action-box">
                        <button type="button" id="btnConfirm" class="btn-transfer" onclick="handleConfirmPayment()">
                            <span>Xác Nhận Chuyển Tiền</span>
                            <span>➔</span>
                        </button>
                    </div>
                </div>

                <script>
                    const orderCode = '%s';
                    const isAlreadyPaid = %s;

                    if (isAlreadyPaid) {
                        showSuccessState('TXN-SETTLED', new Date().toLocaleString('vi-VN'));
                    }

                    async function handleConfirmPayment() {
                        const btn = document.getElementById('btnConfirm');
                        btn.disabled = true;
                        btn.innerHTML = '<span>Đang xử lý giao dịch Napas 247...</span>';

                        try {
                            const res = await fetch('/api/payments/sandbox/simulate-callback?orderCode=' + encodeURIComponent(orderCode), {
                                method: 'POST'
                            });
                            if (res.ok) {
                                const data = await res.json();
                                const txnId = 'FT' + Math.floor(1000000000 + Math.random() * 9000000000);
                                showSuccessState(txnId, new Date().toLocaleString('vi-VN'));
                            } else {
                                const err = await res.json().catch(() => ({}));
                                alert('Không thể hoàn tất giao dịch: ' + (err.message || 'Lỗi server'));
                                btn.disabled = false;
                                btn.innerHTML = '<span>Thử Lại</span> <span>➔</span>';
                            }
                        } catch (e) {
                            alert('Lỗi mạng khi chuyển tiền: ' + e.message);
                            btn.disabled = false;
                            btn.innerHTML = '<span>Thử Lại</span> <span>➔</span>';
                        }
                    }

                    function showSuccessState(txnId, timeStr) {
                        document.getElementById('transferForm').style.display = 'none';
                        document.getElementById('actionBox').style.display = 'none';
                        document.getElementById('successView').style.display = 'flex';
                        document.getElementById('resTxnId').innerText = txnId;
                        document.getElementById('resTime').innerText = timeStr;
                    }
                </script>
            </body>
            </html>
            """.formatted(
                formattedAmount,
                orderCode,
                formattedAmount,
                orderCode,
                orderCode,
                isPaid ? "true" : "false"
            );
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
