package com.glassstore.order.service;
import com.glassstore.order.domain.OrderStatus;
import com.glassstore.order.domain.SalesChannel;

import com.glassstore.order.client.CatalogInventoryClient;
import com.glassstore.order.dto.CatalogStockDeductionResponse;
import com.glassstore.order.entity.Order;
import com.glassstore.order.entity.OrderItem;
import com.glassstore.order.entity.Payment;
import com.glassstore.order.entity.StockDeductionOutbox;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.repository.PaymentRepository;
import com.glassstore.order.repository.StockDeductionOutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StockDeductionServiceTest {
    @Test
    void retrySyncedOrderDoesNotDeductAgainOrChangeBranch() {
        Order order = Order.builder().id(90L).channel(SalesChannel.POS).status(OrderStatus.PAID).branchCode("BRANCH_01").build();
        StockDeductionOutbox outbox = StockDeductionOutbox.builder().orderId(90L).status("SYNCED").build();
        when(orderRepository.lockById(90L)).thenReturn(Optional.of(order));
        when(outboxRepository.findByOrderId(90L)).thenReturn(Optional.of(outbox));
        assertEquals("SYNCED", stockDeductionService.retry(90L, "BRANCH_02").getInventorySyncStatus());
        assertEquals("BRANCH_01", order.getBranchCode());
        verifyNoInteractions(catalogInventoryClient, eventPublisher, paymentRepository);
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void manualRetryRequeuesAnEventNeedingReconciliation() {
        Order order = Order.builder()
                .id(91L)
                .channel(SalesChannel.POS)
                .status(OrderStatus.PAID)
                .branchCode("BRANCH_01")
                .build();
        StockDeductionOutbox outbox = StockDeductionOutbox.builder()
                .id(8L)
                .orderId(91L)
                .status("RECONCILIATION_REQUIRED")
                .lastError("previous attempts exhausted")
                .build();
        when(orderRepository.lockById(91L)).thenReturn(Optional.of(order));
        when(outboxRepository.findByOrderId(91L)).thenReturn(Optional.of(outbox));

        stockDeductionService.retry(91L, null);

        assertEquals("PENDING", outbox.getStatus());
        assertNull(outbox.getLastError());
        verify(outboxRepository).save(outbox);
        verify(eventPublisher).publishEvent(any(StockDeductionRequestedEvent.class));
    }

    @Mock
    private StockDeductionOutboxRepository outboxRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private CatalogInventoryClient catalogInventoryClient;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private StockDeductionService stockDeductionService;

    @Mock private StockOutboxWorker worker;
    @Test void afterCommitDelegatesToTransactionalWorker() {
        Order order=Order.builder().id(42L).status(OrderStatus.PAID).build();
        when(worker.process(7L)).thenReturn("SYNCED");
        stockDeductionService.processAfterCommit(new StockDeductionRequestedEvent(7L,order));
        assertEquals("SYNCED",order.getInventorySyncStatus());verify(worker).process(7L);
    }

    @Test
    void processingFailureIsPersistedForRetryOrReconciliation() {
        Order order = Order.builder().id(43L).status(OrderStatus.PAID).build();
        IllegalStateException failure = new IllegalStateException("unexpected worker failure");
        when(worker.process(9L)).thenThrow(failure);
        when(worker.recordProcessingFailure(9L, failure)).thenReturn("RECONCILIATION_REQUIRED");

        stockDeductionService.processAfterCommit(new StockDeductionRequestedEvent(9L, order));

        assertEquals("RECONCILIATION_REQUIRED", order.getInventorySyncStatus());
        verify(worker).recordProcessingFailure(9L, failure);
    }
}
