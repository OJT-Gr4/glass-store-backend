package com.glassstore.order.controller;

import com.glassstore.common.dto.ApiResponse;
import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.order.domain.OrderStateMachine;
import com.glassstore.order.domain.OrderStatus;
import com.glassstore.order.domain.PaymentStatus;
import com.glassstore.order.dto.OrderDtos;
import com.glassstore.order.entity.Order;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.repository.PaymentRepository;
import com.glassstore.order.service.OrderInventoryService;
import com.glassstore.order.service.OrderStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Cancellation (FR-COM-11, FR-POS-14, BR-18).
 *
 * <p>Cancelling a paid order never refunds automatically — it moves the order to
 * {@code REFUND_PENDING} so the money path stays explicit (OQ-11).</p>
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class OrderCancellationController {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final OrderStatusService orderStatusService;
    private final OrderInventoryService inventoryService;
    private final com.glassstore.order.repository.OrderStatusHistoryRepository historyRepository;
    private final com.glassstore.order.repository.StockDeductionOutboxRepository stockOutboxes;
    private final com.glassstore.order.service.OrderCancellationService posCancellations;

    /** Customer asks to cancel; staff must still approve. */
    @PostMapping("/{id}/cancel-request")
    public ResponseEntity<ApiResponse<Order>> requestCancellation(
            @PathVariable("id") Long id,
            @RequestBody(required = false) OrderDtos.CancelRequest body) {

        Order order = requireOrder(id);
        com.glassstore.common.security.CurrentActor.requireOwner(order.getCustomerId());
        if (order.getChannel() == com.glassstore.order.domain.SalesChannel.POS || stockOutboxes.findByOrderId(id).isPresent()) {
            throw ApiException.of(ErrorCode.ORDER_NOT_CANCELLABLE, "Đơn POS cần hủy qua luồng hoàn kho tại quầy");
        }
        OrderStatus current = order.currentStatus();
        OrderStateMachine.requireCustomerCancellable(current);

        String reason = body == null || body.getReason() == null
                ? "Khách hàng yêu cầu hủy" : body.getReason();

        Order updated = orderStatusService.transition(order, OrderStatus.CANCEL_REQUESTED,
                com.glassstore.common.security.CurrentActor.name(), reason);
        return ResponseEntity.ok(ApiResponse.success("Đã ghi nhận yêu cầu hủy đơn", updated));
    }

    /** Staff cancels directly, with authorization checked by the state machine. */
    @PostMapping("/{id}/cancel")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<ApiResponse<Order>> cancel(
            @PathVariable("id") Long id,
            @RequestParam(name = "actor", required = false) String actor,
            @RequestBody(required = false) OrderDtos.CancelRequest body,
            @RequestHeader(value = "Authorization", required = false) String token) {

        Order order = orderRepository.lockById(id).orElseThrow(() -> ApiException.of(ErrorCode.ORDER_NOT_FOUND));
        if (order.getChannel() == com.glassstore.order.domain.SalesChannel.POS || stockOutboxes.findByOrderId(id).isPresent()) {
            String reason = body == null ? null : body.getReason();
            return ResponseEntity.ok(ApiResponse.success("Hủy đơn tại quầy",
                    posCancellations.cancel(id, new com.glassstore.order.service.OrderCancellationService.Request(reason, false), token)));
        }
        OrderStatus current = order.currentStatus();
        OrderStateMachine.requireStaffCancellable(current);

        String reason = body == null || body.getReason() == null
                ? "Hủy bởi nhân viên" : body.getReason();

        boolean paid = paymentRepository.existsByOrderIdAndStatus(id, PaymentStatus.SUCCESS);
        OrderStatus target = paid ? OrderStatus.REFUND_PENDING : OrderStatus.CANCELLED;

        Order updated = orderStatusService.transition(order, target,
                com.glassstore.common.security.CurrentActor.name(), reason);

        if (paid) {
            paymentRepository.findFirstByOrderIdAndStatus(id, PaymentStatus.SUCCESS).ifPresent(payment -> {
                payment.setStatus(PaymentStatus.REFUND_PENDING);
                payment.setUpdatedAt(java.time.LocalDateTime.now());
                paymentRepository.save(payment);
            });
        }

        inventoryService.release(updated, reason);
        log.info("Order {} cancelled by {} -> {}", updated.getOrderCode(), actor, target);
        return ResponseEntity.ok(ApiResponse.success("Đã hủy đơn hàng", updated));
    }

    /** Staff rejects a customer cancellation request and restores the previous processing state (S3-11.2). */
    @PostMapping("/{id}/cancel-reject")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<ApiResponse<Order>> rejectCancellation(
            @PathVariable("id") Long id,
            @RequestBody(required = false) OrderDtos.CancelRequest body) {
        if (com.glassstore.common.security.CurrentActor.customer()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "Chỉ nhân viên mới có quyền từ chối yêu cầu hủy đơn");
        }
        Order order = orderRepository.lockById(id).orElseThrow(() -> ApiException.of(ErrorCode.ORDER_NOT_FOUND));
        if (order.currentStatus() != OrderStatus.CANCEL_REQUESTED) {
            throw ApiException.of(ErrorCode.ORDER_INVALID_TRANSITION,
                    "Chỉ từ chối được đơn đang ở trạng thái chờ duyệt hủy (CANCEL_REQUESTED)");
        }

        boolean paid = paymentRepository.existsByOrderIdAndStatus(id, PaymentStatus.SUCCESS);
        OrderStatus restoreStatus = historyRepository.findByOrderIdOrderByChangedAtAsc(id).stream()
                .filter(h -> h.getToStatus() == OrderStatus.CANCEL_REQUESTED && h.getFromStatus() != null)
                .reduce((first, second) -> second)
                .map(com.glassstore.order.entity.OrderStatusHistory::getFromStatus)
                .filter(s -> OrderStatus.CANCEL_REQUESTED.canTransitionTo(s))
                .orElse(paid ? OrderStatus.CONFIRMED : OrderStatus.PENDING_PAYMENT);

        String reason = body == null || body.getReason() == null || body.getReason().isBlank()
                ? "Từ chối yêu cầu hủy đơn hàng"
                : body.getReason().trim();

        Order updated = orderStatusService.transition(order, restoreStatus,
                com.glassstore.common.security.CurrentActor.name(), reason);
        return ResponseEntity.ok(ApiResponse.success("Đã từ chối yêu cầu hủy đơn và khôi phục xử lý", updated));
    }

    private Order requireOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> ApiException.of(ErrorCode.ORDER_NOT_FOUND,
                        "Không tìm thấy đơn hàng ID: " + id));
    }
}
