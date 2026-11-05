package com.glassstore.order.service;

import com.glassstore.order.entity.*;
import com.glassstore.order.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.*;

@Service @RequiredArgsConstructor @Slf4j
public class LabDispatchService {
    public record Requested(Long outboxId) {}
    private final LabDispatchOutboxRepository outbox;
    private final OrderRepository orders;
    private final ApplicationEventPublisher events;
    private final LabDispatchWorker worker;

    @Transactional
    public void enqueue(Order order) {
        if (order.getOrderType() == null || !order.getOrderType().requiresPrescription()) return;
        boolean hasItemPrescription = order.getItems() != null && order.getItems().stream().anyMatch(item -> item.getPrescriptionId() != null);
        if (order.getPrescriptionId() == null && !hasItemPrescription) return;
        orders.lockById(order.getId()).orElseThrow();
        var entry = outbox.findByOrderId(order.getId()).orElseGet(() -> outbox.save(
                LabDispatchOutbox.builder().orderId(order.getId()).build()));
        if ("PENDING".equals(entry.getStatus())) events.publishEvent(new Requested(entry.getId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(Requested event) { attempt(event.outboxId()); }

    @Scheduled(fixedDelayString = "${lab.outbox.poll-interval-ms:5000}", initialDelayString = "${lab.outbox.initial-delay-ms:5000}")
    public void retryPending() {
        outbox.findTop20ByStatusOrderByCreatedAtAsc("PENDING").forEach(entry -> attempt(entry.getId()));
    }

    private void attempt(Long id) {
        try { worker.process(id); }
        catch (RuntimeException error) { log.warn("Lab outbox {} will be retried",id,error); }
    }
}
