package com.glassstore.order.service;

import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.order.domain.PaymentMethod;
import com.glassstore.order.domain.PaymentStatus;
import com.glassstore.order.entity.Order;
import com.glassstore.order.entity.Payment;
import com.glassstore.order.payment.ConfigurableSandboxPaymentGateway;
import com.glassstore.order.payment.PaymentGateway;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SandboxPaymentGatewayTest {

    private ConfigurableSandboxPaymentGateway gateway;
    private PaymentRepository paymentRepository;
    private OrderRepository orderRepository;
    private OrderCheckoutService checkoutService;
    private OrderStatusService orderStatusService;
    private PaymentService paymentService;

    private static final String SECRET = "TestSecretKey1234567890!@#Hmac512";

    @BeforeEach
    void setUp() {
        gateway = new ConfigurableSandboxPaymentGateway();
        ReflectionTestUtils.setField(gateway, "enabled", true);
        ReflectionTestUtils.setField(gateway, "secretKey", SECRET);
        ReflectionTestUtils.setField(gateway, "vietqrBankId", "970422");
        ReflectionTestUtils.setField(gateway, "vietqrAccountNo", "0987654321");
        ReflectionTestUtils.setField(gateway, "vietqrAccountName", "OPTIVISION GLASS STORE");
        ReflectionTestUtils.setField(gateway, "vietqrTemplate", "compact2");
        ReflectionTestUtils.setField(gateway, "vnpayTmnCode", "OPTIVIS1");
        ReflectionTestUtils.setField(gateway, "vnpayHashSecret", SECRET);
        ReflectionTestUtils.setField(gateway, "vnpayUrl", "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
        ReflectionTestUtils.setField(gateway, "vnpayReturnUrl", "http://localhost:5173/payment-return");

        paymentRepository = mock(PaymentRepository.class);
        orderRepository = mock(OrderRepository.class);
        checkoutService = mock(OrderCheckoutService.class);
        orderStatusService = mock(OrderStatusService.class);

        when(orderRepository.lockById(any())).thenAnswer(inv -> orderRepository.findById(inv.getArgument(0)));
        when(orderRepository.lockByOrderCode(any())).thenAnswer(inv -> orderRepository.findByOrderCode(inv.getArgument(0)));

        paymentService = new PaymentService(paymentRepository, orderRepository, gateway, checkoutService, orderStatusService);
    }

    @Test
    @DisplayName("S3-02: Khi payment.sandbox.enabled=false, initiate fail với 503 PAYMENT_PROVIDER_NOT_CONFIGURED")
    void initiate_whenDisabled_throws503() {
        gateway.setEnabled(false);

        assertThatThrownBy(() -> gateway.initiate(new PaymentGateway.InitiationRequest(
                "ORD-20261110-001", new BigDecimal("500000"), "Thanh toan")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED));
    }

    @Test
    @DisplayName("S3-02: Khi payment.sandbox.enabled=false, verifyCallback fail với 503 PAYMENT_PROVIDER_NOT_CONFIGURED")
    void verifyCallback_whenDisabled_throws503() {
        gateway.setEnabled(false);

        assertThatThrownBy(() -> gateway.verifyCallback("{}", "invalid-sig"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED));
    }

    @Test
    @DisplayName("S3-02: Khi bật sandbox, initiate sinh mã giao dịch, VietQR QuickLink URL và nội dung QR")
    void initiate_whenEnabled_generatesValidVietQR() {
        PaymentGateway.InitiationResult result = gateway.initiate(new PaymentGateway.InitiationRequest(
                "ORD-20261110-001", new BigDecimal("750000"), "Don hang ORD-001"));

        assertThat(result).isNotNull();
        assertThat(result.provider()).isEqualTo("SANDBOX_GATEWAY");
        assertThat(result.externalTransactionId()).startsWith("TXN-SANDBOX-ORD-20261110-001-");
        assertThat(result.paymentUrl()).contains("https://img.vietqr.io/image/970422-0987654321-compact2.png");
        assertThat(result.paymentUrl()).contains("amount=750000");
        assertThat(result.qrContent()).contains("970422");
        assertThat(result.qrContent()).contains("0987654321");
        assertThat(result.requiresCustomerAction()).isTrue();
    }

    @Test
    @DisplayName("S3-02: verifyCallback với JSON và chữ ký HMAC-SHA512 hợp lệ trả về valid=true, success=true")
    void verifyCallback_validJsonHmac_success() {
        String orderCode = "ORD-20261110-888";
        BigDecimal amount = new BigDecimal("1200000");
        String externalTxnId = "TXN-SANDBOX-888";
        String status = "SUCCESS";

        String sig = gateway.signCanonical(orderCode, amount, externalTxnId, status);
        String payload = String.format("{\"orderCode\":\"%s\",\"amount\":\"%s\",\"externalTransactionId\":\"%s\",\"status\":\"%s\",\"signature\":\"%s\"}",
                orderCode, amount.toPlainString(), externalTxnId, status, sig);

        PaymentGateway.VerificationResult res = gateway.verifyCallback(payload, sig);
        assertThat(res.valid()).isTrue();
        assertThat(res.success()).isTrue();
        assertThat(res.orderCode()).isEqualTo(orderCode);
        assertThat(res.amount()).isEqualByComparingTo(amount);
        assertThat(res.externalTransactionId()).isEqualTo(externalTxnId);
    }

    @Test
    @DisplayName("S3-02: verifyCallback với chữ ký giả/sai trả về valid=false (chặn gian lận callback)")
    void verifyCallback_invalidSignature_returnsInvalid() {
        String payload = "{\"orderCode\":\"ORD-1\",\"amount\":\"500000\",\"externalTransactionId\":\"TXN-1\",\"status\":\"SUCCESS\"}";
        PaymentGateway.VerificationResult res = gateway.verifyCallback(payload, "invalid_forged_signature_hex");
        assertThat(res.valid()).isFalse();
    }

    @Test
    @DisplayName("S3-02: Luồng PaymentService.handleCallback xác thực callback thật, chuyển đơn PAID")
    void paymentService_handleCallback_marksOrderPaid() {
        String orderCode = "ORD-REAL-100";
        BigDecimal amount = new BigDecimal("850000.00");
        String externalTxnId = "TXN-REAL-100";
        String status = "SUCCESS";

        Order order = Order.builder()
                .id(100L)
                .orderCode(orderCode)
                .finalAmount(amount)
                .paymentMethod(PaymentMethod.VIETQR)
                .build();

        Payment pendingPayment = Payment.builder()
                .id(1L)
                .orderId(100L)
                .paymentMethod(PaymentMethod.VIETQR)
                .status(PaymentStatus.PENDING)
                .amount(amount)
                .build();

        when(orderRepository.findByOrderCode(orderCode)).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderIdOrderByCreatedAtDesc(100L)).thenReturn(Collections.singletonList(pendingPayment));
        when(paymentRepository.findFirstByOrderIdAndStatus(100L, PaymentStatus.SUCCESS))
                .thenReturn(Optional.of(Payment.builder().id(2L).orderId(100L).status(PaymentStatus.SUCCESS).amount(amount).build()));

        String sig = gateway.signCanonical(orderCode, amount, externalTxnId, status);
        String payload = String.format("{\"orderCode\":\"%s\",\"amount\":\"%s\",\"externalTransactionId\":\"%s\",\"status\":\"%s\"}",
                orderCode, amount.toPlainString(), externalTxnId, status);

        PaymentService.CallbackOutcome outcome = paymentService.handleCallback(payload, sig);

        assertThat(outcome).isNotNull();
        assertThat(outcome.replayed()).isFalse();
        verify(checkoutService, times(1)).markPaid(eq(order), eq("GATEWAY"), any(), eq("SANDBOX_GATEWAY"), any());
    }

    @Test
    @DisplayName("S3-02: Callback sai số tiền ném PAYMENT_AMOUNT_MISMATCH")
    void paymentService_handleCallback_amountMismatch_throwsError() {
        String orderCode = "ORD-MISMATCH-1";
        BigDecimal expectedAmount = new BigDecimal("500000.00");
        BigDecimal paidAmount = new BigDecimal("400000.00"); // mismatch!
        String externalTxnId = "TXN-MISMATCH-1";

        Order order = Order.builder()
                .id(200L)
                .orderCode(orderCode)
                .finalAmount(expectedAmount)
                .paymentMethod(PaymentMethod.VIETQR)
                .build();

        when(orderRepository.findByOrderCode(orderCode)).thenReturn(Optional.of(order));

        String sig = gateway.signCanonical(orderCode, paidAmount, externalTxnId, "SUCCESS");
        String payload = String.format("{\"orderCode\":\"%s\",\"amount\":\"%s\",\"externalTransactionId\":\"%s\",\"status\":\"SUCCESS\"}",
                orderCode, paidAmount.toPlainString(), externalTxnId);

        assertThatThrownBy(() -> paymentService.handleCallback(payload, sig))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH));
    }
}
