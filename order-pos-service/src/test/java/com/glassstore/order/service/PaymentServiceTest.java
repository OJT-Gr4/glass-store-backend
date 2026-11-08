package com.glassstore.order.service;

import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.order.domain.PaymentMethod;
import com.glassstore.order.domain.PaymentStatus;
import com.glassstore.order.entity.Order;
import com.glassstore.order.entity.Payment;
import com.glassstore.order.payment.PaymentGateway;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaymentServiceTest {

    private PaymentRepository paymentRepository;
    private OrderRepository orderRepository;
    private PaymentGateway paymentGateway;
    private OrderCheckoutService checkoutService;
    private OrderStatusService orderStatusService;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        orderRepository = mock(OrderRepository.class);
        paymentGateway = mock(PaymentGateway.class);
        checkoutService = mock(OrderCheckoutService.class);
        orderStatusService = mock(OrderStatusService.class);
        when(orderRepository.lockById(any())).thenAnswer(inv -> orderRepository.findByOrderCode("ORD-1").or(() -> orderRepository.findById(inv.getArgument(0))));
        when(orderRepository.lockByOrderCode(any())).thenAnswer(inv -> orderRepository.findByOrderCode(inv.getArgument(0)));
        service = new PaymentService(paymentRepository, orderRepository, paymentGateway,
                checkoutService, orderStatusService);
    }

    @Test
    @DisplayName("Duplicate successful callback is a no-op (EX-PAY-03, BR-15)")
    void duplicateCallback() {
        Payment existing = Payment.builder()
                .id(3L)
                .orderId(1L)
                .status(PaymentStatus.SUCCESS)
                .amount(new BigDecimal("10.00"))
                .provider("TEST")
                .externalTransactionId("txn-1")
                .build();
        when(paymentGateway.verifyCallback("body", "sig")).thenReturn(new PaymentGateway.VerificationResult(
                true, "TEST", "txn-1", "ORD-1", new BigDecimal("10.00"), true, "ok"));
        when(paymentRepository.findByProviderAndExternalTransactionId("TEST", "txn-1"))
                .thenReturn(Optional.of(existing));
        when(orderRepository.findByOrderCode("ORD-1")).thenReturn(Optional.of(Order.builder().id(1L).orderCode("ORD-1").build()));

        PaymentService.CallbackOutcome outcome = service.handleCallback("body", "sig");

        assertThat(outcome.replayed()).isTrue();
        verify(checkoutService, never()).markPaid(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Invalid signature is rejected and never marks paid")
    void invalidSignature() {
        when(paymentGateway.verifyCallback("body", "bad")).thenReturn(new PaymentGateway.VerificationResult(
                false, "TEST", "txn", "ORD-1", new BigDecimal("10.00"), true, "bad"));

        assertThatThrownBy(() -> service.handleCallback("body", "bad"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_SIGNATURE_INVALID);
        verify(checkoutService, never()).markPaid(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Verified amount must match the order total")
    void amountMismatch() {
        when(paymentGateway.verifyCallback("body", "sig")).thenReturn(new PaymentGateway.VerificationResult(
                true, "TEST", "txn-9", "ORD-1", new BigDecimal("99.00"), true, "ok"));
        when(paymentRepository.findByProviderAndExternalTransactionId("TEST", "txn-9"))
                .thenReturn(Optional.empty());
        when(orderRepository.findByOrderCode("ORD-1")).thenReturn(Optional.of(Order.builder()
                .id(1L).orderCode("ORD-1").finalAmount(new BigDecimal("10.00"))
                .paymentMethod(PaymentMethod.VNPAY).build()));
        when(paymentRepository.existsByOrderIdAndStatus(1L, PaymentStatus.SUCCESS)).thenReturn(false);

        assertThatThrownBy(() -> service.handleCallback("body", "sig"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
    }

    @Test
    @DisplayName("Failed verified callback records FAILED and does not mark the order paid")
    void failedPayment() {
        when(paymentGateway.verifyCallback("body", "sig")).thenReturn(new PaymentGateway.VerificationResult(
                true, "TEST", "txn-f", "ORD-1", new BigDecimal("10.00"), false, "declined"));
        when(paymentRepository.findByProviderAndExternalTransactionId("TEST", "txn-f"))
                .thenReturn(Optional.empty());
        when(orderRepository.findByOrderCode("ORD-1")).thenReturn(Optional.of(Order.builder()
                .id(1L).orderCode("ORD-1").finalAmount(new BigDecimal("10.00"))
                .paymentMethod(PaymentMethod.VNPAY).build()));
        when(paymentRepository.existsByOrderIdAndStatus(1L, PaymentStatus.SUCCESS)).thenReturn(false);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentService.CallbackOutcome outcome = service.handleCallback("body", "sig");

        assertThat(outcome.payment().getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(checkoutService, never()).markPaid(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Initiate refuses a second charge on an already-paid order")
    void initiateAlreadyPaid() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(Order.builder()
                .id(1L).orderCode("ORD-1").paymentMethod(PaymentMethod.VNPAY).build()));
        when(paymentRepository.existsByOrderIdAndStatus(1L, PaymentStatus.SUCCESS)).thenReturn(true);

        assertThatThrownBy(() -> service.initiate(1L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_ALREADY_SETTLED);
        verify(paymentGateway, never()).initiate(any());
    }

    @Test
    @DisplayName("Unconfigured gateway cannot fake success on initiate")
    void initiateUnconfigured() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(Order.builder()
                .id(1L).orderCode("ORD-1").finalAmount(new BigDecimal("10.00"))
                .paymentMethod(PaymentMethod.VNPAY).build()));
        when(paymentRepository.existsByOrderIdAndStatus(1L, PaymentStatus.SUCCESS)).thenReturn(false);
        when(paymentGateway.initiate(any())).thenThrow(ApiException.of(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED));

        assertThatThrownBy(() -> service.initiate(1L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED);
    }
}
