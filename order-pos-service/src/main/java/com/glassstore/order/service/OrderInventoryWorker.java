package com.glassstore.order.service;

import com.glassstore.order.client.CatalogClient;
import com.glassstore.order.client.CatalogContracts;
import com.glassstore.order.domain.OrderStatus;
import com.glassstore.order.domain.PaymentStatus;
import com.glassstore.order.entity.Order;
import com.glassstore.order.entity.OrderInventoryTask;
import com.glassstore.order.repository.OrderInventoryTaskRepository;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.repository.PaymentRepository;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

import static com.glassstore.order.domain.InventoryOperation.*;
import static com.glassstore.order.domain.InventorySyncStatus.*;

@Service
@RequiredArgsConstructor
public class OrderInventoryWorker {
    private static final int MAX_ATTEMPTS = 10;
    private static final int MAX_ERROR_LENGTH = 1000;
    private static final String SYSTEM_ACTOR = "SYSTEM";
    private static final Set<OrderStatus> CANCELLATION_STATUSES = Set.of(
            OrderStatus.CANCELLED, OrderStatus.CANCEL_PENDING,
            OrderStatus.REFUND_PENDING, OrderStatus.REFUNDED);

    private final OrderRepository orders;
    private final OrderInventoryTaskRepository tasks;
    private final CatalogClient catalog;
    private final PaymentRepository payments;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String process(Long orderId) {
        Order order = orders.lockById(orderId).orElseThrow();
        OrderInventoryTask task = tasks.lockByOrderId(orderId).orElseThrow();
        if (!PENDING.equals(task.getStatus())) {
            return task.getStatus();
        }

        task.setAttempts(task.getAttempts() + 1);
        task.setUpdatedAt(LocalDateTime.now());
        try {
            prepareCancellation(order, task);
            if (RELEASE.equals(task.getOperation())) {
                if (!releaseHold(order, task)) {
                    return task.getStatus();
                }
            } else {
                reserveAndCommit(order, task);
            }
            task.setStatus(SYNCED);
            task.setLastError(null);
        } catch (RuntimeException error) {
            recordFailure(task, error);
        }
        return task.getStatus();
    }

    private void prepareCancellation(Order order, OrderInventoryTask task) {
        if (CANCELLATION_STATUSES.contains(order.getStatus())) {
            if (COMMIT.equals(task.getOperation())) {
                task.setCommitStarted(true);
            }
            task.setOperation(RELEASE);
        }
    }

    private boolean releaseHold(Order order, OrderInventoryTask task) {
        boolean uncertainReservation = task.isReserveStarted() && !task.isReserveConfirmed();
        if (task.isCommitStarted() || uncertainReservation) {
            task.setStatus(RECONCILIATION_REQUIRED);
            task.setLastError("Kho đã xuất hoặc kết quả giữ/xuất kho chưa chắc chắn. Cần đối soát và bù trừ, không tự cộng tồn.");
            return false;
        }

        var request = new CatalogContracts.ReleaseRequest();
        request.setReservationKey(order.getOrderCode());
        request.setActor(SYSTEM_ACTOR);
        request.setReason("Hủy đơn / thanh toán thất bại");
        requireAcknowledgement(catalog.release(request), "RELEASED");
        return true;
    }

    private void reserveAndCommit(Order order, OrderInventoryTask task) {
        if (COMMIT.equals(task.getOperation())
                && !payments.existsByOrderIdAndStatus(order.getId(), PaymentStatus.SUCCESS)) {
            throw new IllegalStateException("Chưa xác nhận thanh toán nên chưa được xuất kho.");
        }
        if (!task.isReserveConfirmed()) {
            reserveHold(order, task);
        }
        if (COMMIT.equals(task.getOperation()) && !task.isCommitConfirmed()) {
            commitHold(order, task);
        }
    }

    private void reserveHold(Order order, OrderInventoryTask task) {
        var request = new CatalogContracts.ReserveRequest();
        request.setReservationKey(order.getOrderCode());
        request.setOrderCode(order.getOrderCode());
        request.setActor(SYSTEM_ACTOR);
        request.setLines(order.getItems().stream()
                .map(item -> new CatalogContracts.ReservationLine(item.getSkuId(), item.getQuantity()))
                .toList());

        task.setReserveStarted(true);
        var response = catalog.reserve(request);
        if (COMMIT.equals(task.getOperation()) && hasStatus(response, "COMMITTED")) {
            // The remote commit can survive a crash before the local outcome was saved.
            task.setCommitStarted(true);
            task.setCommitConfirmed(true);
        } else {
            requireAcknowledgement(response, "ACTIVE");
        }
        task.setReserveConfirmed(true);
    }

    private void commitHold(Order order, OrderInventoryTask task) {
        var request = new CatalogContracts.CommitRequest();
        request.setReservationKey(order.getOrderCode());
        request.setActor(SYSTEM_ACTOR);

        task.setCommitStarted(true);
        requireAcknowledgement(catalog.commit(request), "COMMITTED");
        task.setCommitConfirmed(true);
    }

    private void recordFailure(OrderInventoryTask task, RuntimeException error) {
        String message = error.getMessage() == null ? "Dịch vụ kho chưa xác nhận" : error.getMessage();
        task.setLastError(message.substring(0, Math.min(MAX_ERROR_LENGTH, message.length())));
        boolean needsReconciliation = isPermanentFailure(error) || task.getAttempts() >= MAX_ATTEMPTS;
        task.setStatus(needsReconciliation ? RECONCILIATION_REQUIRED : PENDING);
    }

    private boolean isPermanentFailure(RuntimeException error) {
        return error instanceof FeignException upstream
                && upstream.status() >= 400 && upstream.status() < 500
                && upstream.status() != 408 && upstream.status() != 429;
    }

    private void requireAcknowledgement(
            CatalogContracts.ApiEnvelope<CatalogContracts.ReservationResult> response, String expected) {
        if (!hasStatus(response, expected)) {
            throw new IllegalStateException("Kho chưa xác nhận trạng thái " + expected);
        }
    }

    private boolean hasStatus(
            CatalogContracts.ApiEnvelope<CatalogContracts.ReservationResult> response, String expected) {
        return response != null && response.getData() != null
                && expected.equals(response.getData().getStatus());
    }
}
