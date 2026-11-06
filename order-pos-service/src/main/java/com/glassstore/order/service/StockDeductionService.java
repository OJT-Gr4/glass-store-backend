package com.glassstore.order.service;
import com.glassstore.order.domain.*;

import com.glassstore.order.client.CatalogInventoryClient;
import com.glassstore.order.dto.CatalogStockDeductionRequest;
import com.glassstore.order.entity.Order;
import com.glassstore.order.entity.OrderInventoryTask;
import com.glassstore.order.repository.OrderInventoryTaskRepository;
import com.glassstore.order.entity.Payment;
import com.glassstore.order.entity.StockDeductionOutbox;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.repository.PaymentRepository;
import com.glassstore.order.repository.StockDeductionOutboxRepository;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockDeductionService {

    private final StockDeductionOutboxRepository outboxRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final CatalogInventoryClient catalogInventoryClient;
    private final ApplicationEventPublisher eventPublisher;
    private final StockOutboxWorker worker;
    private final OrderInventoryTaskRepository reservationTasks;

    public void enqueue(Order order, Payment payment) {
        StockDeductionOutbox outbox = outboxRepository.save(StockDeductionOutbox.builder()
                .orderId(order.getId())
                .idempotencyKey(payment.getIdempotencyKey())
                .status("PENDING")
                .build());
        order.setInventorySyncStatus("PENDING");
        eventPublisher.publishEvent(new StockDeductionRequestedEvent(outbox.getId(), order));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void processAfterCommit(StockDeductionRequestedEvent event) {
        String status = processOutbox(event.outboxId());
        event.order().setInventorySyncStatus(status);
        if ("SYNCED".equals(status) && (event.order().getStatus() == OrderStatus.CANCEL_PENDING)) event.order().setStatus(OrderStatus.CANCELLED);
    }

    @Scheduled(fixedDelayString = "${inventory.outbox.poll-interval-ms:5000}")
    public void retryPendingDeductions() {
        outboxRepository.findTop20ByStatusOrderByCreatedAtAsc("PENDING")
                .forEach(outbox -> processOutbox(outbox.getId()));
    }

    public String statusForOrder(Long orderId) {
        return outboxRepository.findByOrderId(orderId)
                .map(StockDeductionOutbox::getStatus)
                .orElseGet(() -> reservationTasks.findById(orderId).map(OrderInventoryTask::getStatus).orElse("NOT_REQUIRED"));
    }

    public Order inventoryStatus(Order order) {
        outboxRepository.findByOrderId(order.getId()).ifPresentOrElse(
                row -> setInventoryStatus(order, row.getStatus(), row.getLastError(), row.getOperation()),
                () -> reservationTasks.findById(order.getId()).ifPresent(
                        task -> setInventoryStatus(order, task.getStatus(), task.getLastError(), task.getOperation())));
        if (order.getInventorySyncStatus() == null) {
            order.setInventorySyncStatus(InventorySyncStatus.NOT_REQUIRED);
        }
        return order;
    }

    private void setInventoryStatus(Order order, String status, String error, String operation) {
        order.setInventorySyncStatus(status);
        order.setInventorySyncError(error);
        order.setInventorySyncOperation(operation);
    }

    private Order retryReservation(Order order) {
        OrderInventoryTask task = reservationTasks.lockByOrderId(order.getId()).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.CONFLICT, "Đơn không có yêu cầu đồng bộ kho."));
        if (InventorySyncStatus.RECONCILIATION_REQUIRED.equals(task.getStatus())
                && InventoryOperation.RELEASE.equals(task.getOperation())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cần đối soát giữ/xuất kho và bù trừ trước; không tự cộng tồn.");
        }
        if (!InventorySyncStatus.SYNCED.equals(task.getStatus())) {
            task.setStatus(InventorySyncStatus.PENDING);
            task.setAttempts(0);
            task.setLastError(null);
            reservationTasks.save(task);
            eventPublisher.publishEvent(new OrderInventoryDispatcher.Requested(order.getId()));
        }
        return inventoryStatus(order);
    }

    @Transactional
    public Order retry(Long orderId, String branchCode) {
        Order order = orderRepository.lockById(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var existing = outboxRepository.findByOrderId(orderId);
        if (existing.isEmpty()) {
            return retryReservation(order);
        }
        StockDeductionOutbox outbox = existing.get();
        if (!(order.getChannel() == SalesChannel.POS) || !(order.getStatus() == OrderStatus.PAID)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ đồng bộ đơn POS đã thanh toán.");
        if ("SYNCED".equals(outbox.getStatus())) {
            order.setInventorySyncStatus("SYNCED");
            return order;
        }
        if (branchCode != null && !branchCode.equals(order.getBranchCode())) {
            if (!"FAILED".equals(outbox.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Không đổi chi nhánh khi đồng bộ đang chạy.");
            SalesBranch branch = SalesBranch.resolve(branchCode, null);
            order.setBranchCode(branch.code()); order.setBranchName(branch.name());
            orderRepository.save(order);
        }
        SalesBranch.resolve(order.getBranchCode(), order.getBranchName());
        outbox.setStatus("PENDING"); outbox.setLastError(null);
        outboxRepository.save(outbox);
        order.setInventorySyncStatus("PENDING");
        eventPublisher.publishEvent(new StockDeductionRequestedEvent(outbox.getId(), order));
        return order;
    }

    private String processOutbox(Long id) {
        try { return worker.process(id); }
        catch (RuntimeException error) {
            log.error("Inventory task {} failed before its outcome was recorded", id, error);
            try {
                return worker.recordProcessingFailure(id, error);
            } catch (RuntimeException persistenceError) {
                log.error("Inventory task {} failure could not be persisted", id, persistenceError);
                return "PENDING";
            }
        }
    }
}
