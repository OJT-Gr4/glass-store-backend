package com.glassstore.order.service;
import com.glassstore.order.domain.*;

import com.glassstore.order.client.CatalogInventoryClient;
import com.glassstore.order.dto.CatalogStockDeductionRequest;
import com.glassstore.order.entity.Order;
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
                .orElse("NOT_REQUIRED");
    }

    @Transactional
    public Order retry(Long orderId, String branchCode) {
        Order order = orderRepository.lockById(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        StockDeductionOutbox outbox = outboxRepository.findByOrderId(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Đơn không có yêu cầu đồng bộ kho."));
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
