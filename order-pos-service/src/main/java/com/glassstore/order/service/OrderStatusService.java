package com.glassstore.order.service;

import com.glassstore.order.domain.*;
import com.glassstore.order.entity.Order;
import com.glassstore.order.entity.OrderStatusHistory;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.repository.OrderStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only place that writes {@code Order.status} (BR-14, BR-17, BR-45).
 *
 * <p>Centralising it means every change is validated by {@link OrderStateMachine} and
 * recorded in the history table, so no controller can bypass the rules.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderStatusService {

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;

    @Autowired(required = false)
    private OrderNotificationPublisher orderNotificationPublisher;

    public void setOrderNotificationPublisher(OrderNotificationPublisher orderNotificationPublisher) {
        this.orderNotificationPublisher = orderNotificationPublisher;
    }

    /**
     * Applies a validated transition and appends history.
     *
     * @return the persisted order
     * @throws com.glassstore.common.exception.ApiException when the transition is illegal
     */
    @Transactional
    public Order transition(Order order, OrderStatus target, String actor, String reason) {
        OrderStatus current = order.currentStatus();
        OrderStateMachine.requireTransition(current, target);

        order.setStatus(target);
        if (target == OrderStatus.COMPLETED && order.getCompletedAt() == null) {
            order.setCompletedAt(java.time.LocalDateTime.now());
        }
        order.setUpdatedAt(java.time.LocalDateTime.now());
        Order saved = orderRepository.save(order);

        historyRepository.save(OrderStatusHistory.builder()
                .order(saved)
                .fromStatus(current)
                .toStatus(target)
                .actor(actor == null || actor.isBlank() ? "SYSTEM" : actor)
                .reason(reason)
                .build());

        if (orderNotificationPublisher != null) {
            try {
                orderNotificationPublisher.publishOrderStatus(saved, target);
            } catch (Exception ex) {
                log.warn("Non-fatal error publishing order notification {}: {}", saved.getOrderCode(), ex.getMessage());
            }
        }

        log.info("Order {} transitioned {} -> {} by {}", saved.getOrderCode(), current, target, actor);
        return saved;
    }

    /** Convenience used when only the order id is known. */
    @Transactional
    public Order transition(Long orderId, OrderStatus target, String actor, String reason) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> com.glassstore.common.exception.ApiException.of(
                        com.glassstore.common.exception.ErrorCode.ORDER_NOT_FOUND,
                        "Không tìm thấy đơn hàng ID: " + orderId));
        return transition(order, target, actor, reason);
    }
}