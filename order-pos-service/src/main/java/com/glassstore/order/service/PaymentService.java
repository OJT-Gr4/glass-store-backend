package com.glassstore.order.service;

import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.order.domain.OrderStatus;
import com.glassstore.order.domain.PaymentStatus;
import com.glassstore.order.domain.PaymentMethod;
import com.glassstore.order.domain.SalesChannel;
import com.glassstore.order.entity.Order;
import com.glassstore.order.entity.Payment;
import com.glassstore.order.payment.PaymentGateway;
import com.glassstore.order.payment.SePayGateway;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Payment initiation, verified callbacks, and refund requests (FR-PAY-*).
 *
 * <p>Success is never inferred from the client. Only {@link PaymentGateway#verifyCallback}
 * or an explicit in-store cash settlement may mark money received (BR-16).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final PaymentGateway paymentGateway;
    private final OrderCheckoutService checkoutService;
    private final OrderStatusService orderStatusService;

    @Transactional
    public List<Payment> history(Long orderId) {
        Order order = requireOrder(orderId);
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
            syncGatewayStatus(order);
        }
        return paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId);
    }

    @Transactional
    public PaymentGateway.InitiationResult initiate(Long orderId) {
        return initiate(orderId, null);
    }

    @Transactional
    public PaymentGateway.InitiationResult initiate(Long orderId, String requestedProvider) {
        Order order = orderRepository.lockById(orderId).orElseThrow(() -> ApiException.of(ErrorCode.ORDER_NOT_FOUND));
        if (paymentRepository.existsByOrderIdAndStatus(orderId, PaymentStatus.SUCCESS)) {
            throw ApiException.of(ErrorCode.PAYMENT_ALREADY_SETTLED,
                    "Đơn hàng " + order.getOrderCode() + " đã được thanh toán");
        }
        if (order.getPaymentMethod() == null || !order.getPaymentMethod().requiresGateway()) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR,
                    "Phương thức thanh toán của đơn này không dùng cổng thanh toán");
        }
        if ((order.getChannel() != SalesChannel.ONLINE && order.getChannel() != SalesChannel.POS)
                || order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw ApiException.of(ErrorCode.ORDER_INVALID_TRANSITION,
                    "Chỉ khởi tạo gateway cho đơn hàng đang chờ thanh toán.");
        }
        if (!paymentGateway.supports(order.getPaymentMethod())) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Gateway không hỗ trợ phương thức của đơn hàng.");
        }

        String effectiveProvider = requestedProvider;
        if (effectiveProvider == null || effectiveProvider.isBlank()) {
            if (order.getNotes() != null && order.getNotes().toUpperCase().contains("SEPAY")) {
                effectiveProvider = "SEPAY";
            } else if (order.getNotes() != null && order.getNotes().toUpperCase().contains("VIETQR")) {
                effectiveProvider = "VIETQR";
            }
        }

        PaymentGateway.InitiationResult result = paymentGateway.initiate(
                new PaymentGateway.InitiationRequest(
                        order.getOrderCode(),
                        order.getFinalAmount(),
                        "Thanh toán đơn " + order.getOrderCode(),
                        effectiveProvider));

        Payment pending = paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .filter(p -> p.getStatus() == PaymentStatus.PENDING)
                .findFirst()
                .orElseGet(() -> paymentRepository.save(Payment.builder()
                        .orderId(orderId)
                        .paymentMethod(order.getPaymentMethod())
                        .status(PaymentStatus.PENDING)
                        .amount(order.getFinalAmount())
                        .build()));
        pending.setProvider(result.provider());
        pending.setExternalTransactionId(result.externalTransactionId());
        pending.setUpdatedAt(LocalDateTime.now());
        paymentRepository.save(pending);
        return result;
    }

    /**
     * Proactively queries the payment gateway (e.g. SePay PG API) to check if money
     * has been settled for an order, solving race conditions and lost webhooks in local dev.
     */
    @Transactional
    public Optional<Payment> syncGatewayStatus(Order order) {
        if (order == null || order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            return Optional.empty();
        }
        try {
            Optional<PaymentGateway.VerificationResult> queryResult = paymentGateway.queryStatus(order.getOrderCode());
            if (queryResult.isPresent() && queryResult.get().success()) {
                CallbackOutcome outcome = processVerification(queryResult.get());
                return Optional.ofNullable(outcome.payment());
            }
        } catch (Exception ex) {
            log.warn("Lỗi khi chủ động truy vấn trạng thái từ cổng thanh toán cho đơn {}: {}",
                    order.getOrderCode(), ex.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Idempotent webhook handler. Duplicate callbacks for the same provider transaction
     * return the original SUCCESS row (EX-PAY-03, BR-15).
     */
    @Transactional
    public CallbackOutcome handleCallback(String rawPayload, String signature) {
        PaymentGateway.VerificationResult verified = paymentGateway.verifyCallback(rawPayload, signature);
        if (!verified.valid()) {
            throw ApiException.of(ErrorCode.PAYMENT_SIGNATURE_INVALID,
                    "Chữ ký xác thực thanh toán không hợp lệ");
        }
        return processVerification(verified);
    }

    @Transactional
    public CallbackOutcome processVerification(PaymentGateway.VerificationResult verified) {
        Order order = orderRepository.lockByOrderCode(verified.orderCode())
                .orElseThrow(() -> ApiException.of(ErrorCode.ORDER_NOT_FOUND,"Không tìm thấy đơn hàng: " + verified.orderCode()));
        Optional<Payment> existing = paymentRepository.findByProviderAndExternalTransactionId(
                verified.provider(), verified.externalTransactionId());
        if (existing.isPresent() && !order.getId().equals(existing.get().getOrderId()))
            throw ApiException.of(ErrorCode.VALIDATION_ERROR,"Mã giao dịch thuộc đơn hàng khác.");
        validateAmount(order.getFinalAmount(), verified.amount());
        Payment sepayAttempt = SePayGateway.PROVIDER.equals(verified.provider())
                ? requireSePayAttempt(order, verified, existing) : null;
        if (existing.isPresent() && List.of(PaymentStatus.SUCCESS, PaymentStatus.REFUND_PENDING,
                PaymentStatus.REFUNDED).contains(existing.get().getStatus())) {
            log.info("Duplicate payment callback ignored for txn {}", verified.externalTransactionId());
            return new CallbackOutcome(existing.get(), true);
        }

        if (paymentRepository.existsByOrderIdAndStatus(order.getId(), PaymentStatus.SUCCESS)) {
            throw ApiException.of(ErrorCode.PAYMENT_ALREADY_SETTLED,
                    "Đơn hàng " + order.getOrderCode() + " đã được thanh toán");
        }

        if (sepayAttempt != null && order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw ApiException.of(ErrorCode.ORDER_INVALID_TRANSITION,
                    "IPN cho đơn không còn chờ thanh toán; cần đối soát, không tự mở lại đơn.");
        }

        if (!verified.success()) {
            Payment failed = existing.orElseGet(() -> Payment.builder()
                    .orderId(order.getId())
                    .paymentMethod(order.getPaymentMethod())
                    .amount(order.getFinalAmount())
                    .build());
            failed.setStatus(PaymentStatus.FAILED);
            failed.setProvider(verified.provider());
            failed.setExternalTransactionId(verified.externalTransactionId());
            failed.setFailureReason(verified.message());
            failed.setUpdatedAt(LocalDateTime.now());
            paymentRepository.save(failed);
            return new CallbackOutcome(failed, false);
        }

        final Long lockedOrderId=order.getId();
        Payment pending = sepayAttempt != null ? sepayAttempt : existing.orElseGet(() -> paymentRepository.findByOrderIdOrderByCreatedAtDesc(lockedOrderId).stream()
                .filter(p -> p.getStatus() == PaymentStatus.PENDING)
                .findFirst()
                .orElseGet(() -> Payment.builder()
                        .orderId(order.getId())
                        .paymentMethod(order.getPaymentMethod())
                        .amount(order.getFinalAmount())
                        .status(PaymentStatus.PENDING)
                        .build()));
        pending.setProvider(verified.provider());
        pending.setExternalTransactionId(verified.externalTransactionId());
        pending.setStatus(PaymentStatus.PENDING);
        pending.setProviderOrderId(verified.providerOrderId());
        pending.setPaymentReference(verified.paymentReference());
        paymentRepository.save(pending);

        checkoutService.markPaid(order, "GATEWAY", "Xác thực callback cổng thanh toán", verified.provider(), pending.getId());
        Payment success = paymentRepository.findFirstByOrderIdAndStatus(order.getId(), PaymentStatus.SUCCESS)
                .orElseThrow(() -> ApiException.of(ErrorCode.PAYMENT_NOT_FOUND, "Không ghi nhận được thanh toán"));
        success.setExternalTransactionId(verified.externalTransactionId());
        success.setProvider(verified.provider());
        paymentRepository.save(success);
        return new CallbackOutcome(success, false);
    }

    private void validateAmount(BigDecimal expected, BigDecimal received) {
        if (expected == null || received == null || expected.compareTo(received) != 0) {
            throw ApiException.of(ErrorCode.PAYMENT_AMOUNT_MISMATCH,
                    "Số tiền thanh toán không khớp với giá trị đơn hàng");
        }
    }

    private Payment requireSePayAttempt(Order order, PaymentGateway.VerificationResult verified,
                                        Optional<Payment> existing) {
        if ((order.getChannel() != SalesChannel.ONLINE && order.getChannel() != SalesChannel.POS)
                || order.getPaymentMethod() != PaymentMethod.VIETQR) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "IPN SePay không khớp kênh/phương thức của đơn.");
        }
        Payment attempt = existing.orElseGet(() -> paymentRepository.findByOrderIdOrderByCreatedAtDesc(order.getId())
                .stream().filter(payment -> payment.getStatus() == PaymentStatus.PENDING
                        && SePayGateway.PROVIDER.equals(payment.getProvider())).findFirst()
                .orElseThrow(() -> ApiException.of(ErrorCode.PAYMENT_NOT_FOUND,
                        "Đơn chưa khởi tạo thanh toán SePay. Không ghi nhận callback ngoài luồng.")));
        validateAmount(attempt.getAmount(), verified.amount());
        if ((attempt.getProviderOrderId() != null && !attempt.getProviderOrderId().equals(verified.providerOrderId()))
                || (attempt.getPaymentReference() != null && !attempt.getPaymentReference().equals(verified.paymentReference()))) {
            throw ApiException.of(ErrorCode.CONFLICT, "IPN lặp có định danh giao dịch khác nội dung đã ghi nhận.");
        }
        return attempt;
    }

    @Transactional
    public Order requestRefund(Long orderId, String actor, String reason) {
        Order order = requireOrder(orderId);
        if (!paymentRepository.existsByOrderIdAndStatus(orderId, PaymentStatus.SUCCESS)
                && !paymentRepository.existsByOrderIdAndStatus(orderId, PaymentStatus.REFUND_PENDING)) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR,
                    "Đơn hàng chưa có giao dịch thành công để hoàn tiền");
        }
        Payment success = paymentRepository.findFirstByOrderIdAndStatus(orderId, PaymentStatus.SUCCESS)
                .orElseGet(() -> paymentRepository.findFirstByOrderIdAndStatus(orderId, PaymentStatus.REFUND_PENDING)
                        .orElseThrow());
        if (success.getStatus() != PaymentStatus.REFUND_PENDING) {
            success.setStatus(PaymentStatus.REFUND_PENDING);
            success.setUpdatedAt(LocalDateTime.now());
            paymentRepository.save(success);
        }
        if (order.currentStatus() != OrderStatus.REFUND_PENDING
                && order.currentStatus() != OrderStatus.REFUNDED) {
            order = orderStatusService.transition(order, OrderStatus.REFUND_PENDING,
                    actor == null ? "STAFF" : actor,
                    reason == null ? "Yêu cầu hoàn tiền" : reason);
        }
        return order;
    }

    @Transactional
    public Order confirmRefund(Long orderId, String actor, String reason) {
        Order order = orderRepository.lockById(orderId)
                .orElseThrow(() -> ApiException.of(ErrorCode.ORDER_NOT_FOUND, "Không tìm thấy đơn hàng ID: " + orderId));
        if (order.currentStatus() == OrderStatus.REFUNDED && "CONFIRMED".equals(order.getRefundStatus())) {
            return order;
        }
        if (order.currentStatus() != OrderStatus.REFUND_PENDING && order.currentStatus() != OrderStatus.CANCELLED) {
            throw ApiException.of(ErrorCode.ORDER_INVALID_TRANSITION,
                    "Chỉ có thể xác nhận hoàn tiền khi đơn ở trạng thái REFUND_PENDING hoặc CANCELLED");
        }
        String effectiveActor = (actor == null || actor.isBlank()) ? "STAFF" : actor;
        String effectiveReason = (reason == null || reason.isBlank())
                ? "Xác nhận đã hoàn tiền thủ công cho khách hàng"
                : reason.trim();

        order.setRefundStatus("CONFIRMED");
        order.setRefundConfirmedBy(effectiveActor);
        order.setRefundConfirmedAt(LocalDateTime.now());
        if (order.currentStatus() == OrderStatus.REFUND_PENDING) {
            order = orderStatusService.transition(order, OrderStatus.REFUNDED, effectiveActor, effectiveReason);
        } else {
            orderRepository.save(order);
        }

        paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId).forEach(payment -> {
            if (payment.getStatus() == PaymentStatus.SUCCESS || payment.getStatus() == PaymentStatus.REFUND_PENDING) {
                payment.setStatus(PaymentStatus.REFUNDED);
                payment.setUpdatedAt(LocalDateTime.now());
                paymentRepository.save(payment);
            }
        });
        return order;
    }

    private Order requireOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> ApiException.of(ErrorCode.ORDER_NOT_FOUND,
                        "Không tìm thấy đơn hàng ID: " + orderId));
    }

    public record CallbackOutcome(Payment payment, boolean replayed) {
    }
}
