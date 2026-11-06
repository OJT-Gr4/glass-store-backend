package com.glassstore.order.service;

import com.glassstore.order.domain.OrderStatus;
import com.glassstore.order.entity.Order;
import com.glassstore.order.entity.OrderItem;
import com.glassstore.order.entity.OrderInventoryTask;
import com.glassstore.order.repository.OrderInventoryTaskRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import static com.glassstore.order.domain.InventoryOperation.*;
import static com.glassstore.order.domain.InventorySyncStatus.*;
import com.glassstore.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Coordinates stock with catalog-service around an order's life (FR-INV-03/04/05).
 *
 * <p>Stock operations are intentionally <em>outside</em> the order transaction: they are
 * remote calls that can partially succeed, so calling them inside a local transaction would
 * give a false sense of atomicity. Each step is idempotent (keyed on the order code).
 * Work is persisted in the order transaction, then dispatched after commit. Remote failures
 * remain visible on the task and never roll back an already recorded payment.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderInventoryService {

    private final OrderRepository orderRepository;
    private final OrderInventoryTaskRepository tasks;
    private final ApplicationEventPublisher events;

    @Transactional
    public void reserve(Order order) {
        enqueue(order, RESERVE);
    }

    @Transactional
    public void release(Order order, String reason) {
        enqueue(order, RELEASE);
    }

    @Transactional
    public void commit(Order order) {
        enqueue(order, COMMIT);
    }

    private void enqueue(Order order, String operation) {
        orderRepository.lockById(order.getId()).orElseThrow();
        OrderInventoryTask task = tasks.lockByOrderId(order.getId())
                .orElseGet(() -> createTask(order, operation));
        if (operation.equals(task.getOperation()) && SYNCED.equals(task.getStatus())) {
            return;
        }

        if (RELEASE.equals(operation) && COMMIT.equals(task.getOperation())) {
            task.setCommitStarted(true);
        }
        if (!operation.equals(task.getOperation())) {
            task.setAttempts(0);
            task.setStatus(PENDING);
            task.setLastError(null);
        }
        task.setOperation(operation);
        task.setUpdatedAt(LocalDateTime.now());
        tasks.save(task);
        order.setInventorySyncStatus(task.getStatus());
        events.publishEvent(new OrderInventoryDispatcher.Requested(order.getId()));
    }

    private OrderInventoryTask createTask(Order order, String operation) {
        var task = new OrderInventoryTask();
        task.setOrderId(order.getId());
        task.setOperation(operation);
        if (RELEASE.equals(operation)) {
            // Legacy orders have no task history; do not assume paid stock can be released.
            task.setReserveConfirmed(true);
            task.setCommitStarted(order.getStatus() != OrderStatus.PENDING_PAYMENT
                    && order.getStatus() != OrderStatus.CANCELLED);
        }
        return tasks.save(task);
    }

    public String statusForOrder(Long orderId) {
        return tasks.findById(orderId).map(OrderInventoryTask::getStatus).orElse(NOT_REQUIRED);
    }

    /**
     * Applies the stock effect of a status change.
     *
     * <p>Rules mirrors the business decision that money and goods move together: a paid order
     * deducts stock, a cancelled unpaid order releases its hold, and a cancelled paid order
     * requires reconciliation of committed stock and a separate refund (OQ-11).</p>
     */
    public void onStatusChanged(Order order, OrderStatus from, OrderStatus to) {
        try {
            if (to == OrderStatus.PAID) {
                commit(order);
            } else if (to == OrderStatus.CANCELLED) {
                boolean wasPaid = from != null && from != OrderStatus.PENDING_PAYMENT;
                release(order, wasPaid
                        ? "Hủy đơn đã thanh toán - hoàn tồn kho"
                        : "Hủy đơn chưa thanh toán");
            }
        } catch (Exception ex) {
            // Never let a remote hiccup silently leave stock in a wrong state unnoticed.
            log.error("Stock sync failed for order {} ({} -> {}): {}",
                    order.getOrderCode(), from, to, ex.getMessage());
            throw ex;
        }
    }

    /** SKUs of an order, used for diagnostics. */
    public List<String> skusOf(Order order) {
        return order.getItems().stream().map(OrderItem::getSkuId).collect(Collectors.toList());
    }

    /** Small helper so callers can build a diagnostic payload consistently. */
    public Map<String, Object> snapshot(Order order) {
        return Map.of(
                "orderCode", order.getOrderCode(),
                "status", order.currentStatus().name(),
                "skus", skusOf(order));
    }
}
