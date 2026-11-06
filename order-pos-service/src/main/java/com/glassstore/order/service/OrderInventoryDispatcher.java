package com.glassstore.order.service;

import com.glassstore.order.repository.OrderInventoryTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import static com.glassstore.order.domain.InventorySyncStatus.PENDING;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderInventoryDispatcher {
    public record Requested(Long orderId) {}
    private final OrderInventoryTaskRepository tasks;
    private final OrderInventoryWorker worker;
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(Requested event) {
        attempt(event.orderId());
    }

    @Scheduled(fixedDelayString = "${inventory.reservation.poll-interval-ms:5000}",
            initialDelayString = "${inventory.reservation.initial-delay-ms:5000}")
    public void retry() {
        tasks.findTop20ByStatusOrderByUpdatedAtAsc(PENDING)
                .forEach(task -> attempt(task.getOrderId()));
    }

    private void attempt(Long orderId) {
        try {
            worker.process(orderId);
        } catch (RuntimeException error) {
            log.error("Inventory task for order {} remains pending; retry will use the original reservation key", orderId, error);
        }
    }
}
