package com.glassstore.order.service;
import com.glassstore.order.domain.*;

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
import org.springframework.http.HttpStatus;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StockOutboxWorkerTest {
 @Mock StockDeductionOutboxRepository outboxRepository;
 @Mock OrderRepository orderRepository;
 @Mock PaymentRepository paymentRepository;
 @Mock CatalogInventoryClient catalogInventoryClient;
 @Mock OrderCancellationService cancellations;
 @InjectMocks StockOutboxWorker worker;
    @Test
    void successfulPaymentOutboxSendsOrderItemsAndMarksOrderSynced() {
        Order order = Order.builder()
                .id(42L)
                .status(OrderStatus.PAID)
                .branchName("Chi nhánh 01 - Lê Thánh Tôn")
                .items(List.of(OrderItem.builder().skuId("FRAME-001").quantity(2).build()))
                .build();
        Payment payment = Payment.builder()
                .orderId(42L)
                .idempotencyKey("pos-request-42")
                .transactionCode("TXN-42")
                .status(PaymentStatus.SUCCESS)
                .build();
        StockDeductionOutbox outbox = StockDeductionOutbox.builder()
                .id(7L)
                .orderId(42L)
                .idempotencyKey("pos-request-42")
                .status("PENDING")
                .attempts(0)
                .build();

        when(outboxRepository.findOrderIdById(7L)).thenReturn(Optional.of(42L));
        when(outboxRepository.lockById(7L)).thenReturn(Optional.of(outbox));
        when(orderRepository.lockById(42L)).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderId(42L)).thenReturn(List.of(payment));
        when(catalogInventoryClient.deductPaidOrder(any()))
                .thenReturn(new CatalogStockDeductionResponse(42L, "BRANCH_01", false));

        assertEquals("SYNCED", worker.process(7L));


        assertEquals("SYNCED", outbox.getStatus());
        ArgumentCaptor<com.glassstore.order.dto.CatalogStockDeductionRequest> requestCaptor =
                ArgumentCaptor.forClass(com.glassstore.order.dto.CatalogStockDeductionRequest.class);
        verify(catalogInventoryClient).deductPaidOrder(requestCaptor.capture());
        assertEquals(42L, requestCaptor.getValue().orderId());
        assertEquals("FRAME-001", requestCaptor.getValue().items().get(0).sku());
        verify(outboxRepository).save(outbox);
    }

    @Test
    void timeoutAfterCatalogCommitRetriesWithSameKeyAndThenMarksSynced() {
        Order order = Order.builder()
                .id(42L)
                .status(OrderStatus.PAID)
                .branchCode("BRANCH_01")
                .items(List.of(OrderItem.builder().skuId("FRAME-001").quantity(2).build()))
                .build();
        Payment payment = Payment.builder()
                .orderId(42L)
                .idempotencyKey("pos-request-42")
                .transactionCode("TXN-42")
                .status(PaymentStatus.SUCCESS)
                .build();
        StockDeductionOutbox outbox = StockDeductionOutbox.builder()
                .id(7L)
                .orderId(42L)
                .idempotencyKey("pos-request-42")
                .status("PENDING")
                .attempts(0)
                .build();
        feign.FeignException timeout = org.mockito.Mockito.mock(feign.FeignException.class);
        when(timeout.status()).thenReturn(-1);
        when(timeout.getMessage()).thenReturn("request timed out after catalog committed");

        when(outboxRepository.findOrderIdById(7L)).thenReturn(Optional.of(42L));
        when(outboxRepository.lockById(7L)).thenReturn(Optional.of(outbox));
        when(orderRepository.lockById(42L)).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderId(42L)).thenReturn(List.of(payment));
        when(catalogInventoryClient.deductPaidOrder(any()))
                .thenThrow(timeout)
                .thenReturn(new CatalogStockDeductionResponse(42L, "BRANCH_01", true));

        assertEquals("PENDING", worker.process(7L));
        assertEquals(1, outbox.getAttempts());
        assertEquals("SYNCED", worker.process(7L));

        assertEquals(2, outbox.getAttempts());
        assertEquals("SYNCED", outbox.getStatus());
        verify(catalogInventoryClient, times(2)).deductPaidOrder(any());
    }

    @Test
    void retryableFailureAtAttemptLimitRequiresReconciliation() {
        Order order = Order.builder()
                .id(42L)
                .status(OrderStatus.PAID)
                .branchCode("BRANCH_01")
                .items(List.of(OrderItem.builder().skuId("FRAME-001").quantity(1).build()))
                .build();
        Payment payment = Payment.builder()
                .orderId(42L)
                .idempotencyKey("pos-request-42")
                .transactionCode("TXN-42")
                .status(PaymentStatus.SUCCESS)
                .build();
        StockDeductionOutbox outbox = StockDeductionOutbox.builder()
                .id(7L)
                .orderId(42L)
                .idempotencyKey("pos-request-42")
                .status("PENDING")
                .attempts(9)
                .build();
        feign.FeignException unavailable = org.mockito.Mockito.mock(feign.FeignException.class);
        when(unavailable.status()).thenReturn(HttpStatus.SERVICE_UNAVAILABLE.value());
        when(unavailable.getMessage()).thenReturn("catalog unavailable");

        when(outboxRepository.findOrderIdById(7L)).thenReturn(Optional.of(42L));
        when(outboxRepository.lockById(7L)).thenReturn(Optional.of(outbox));
        when(orderRepository.lockById(42L)).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderId(42L)).thenReturn(List.of(payment));
        when(catalogInventoryClient.deductPaidOrder(any())).thenThrow(unavailable);

        assertEquals("RECONCILIATION_REQUIRED", worker.process(7L));

        assertEquals(10, outbox.getAttempts());
        assertEquals("RECONCILIATION_REQUIRED", outbox.getStatus());
        assertEquals("catalog unavailable", outbox.getLastError());
    }

    @Test
    void insufficientStockFailureIsNotAutomaticallyRetried() {
        Order order = Order.builder()
                .id(42L)
                .status(OrderStatus.PAID)
                .branchCode("BRANCH_01")
                .items(List.of(OrderItem.builder().skuId("FRAME-001").quantity(1).build()))
                .build();
        Payment payment = Payment.builder()
                .orderId(42L)
                .idempotencyKey("pos-request-42")
                .transactionCode("TXN-42")
                .status(PaymentStatus.SUCCESS)
                .build();
        StockDeductionOutbox outbox = StockDeductionOutbox.builder()
                .id(7L)
                .orderId(42L)
                .idempotencyKey("pos-request-42")
                .status("PENDING")
                .attempts(0)
                .build();
        feign.FeignException conflict = org.mockito.Mockito.mock(feign.FeignException.class);
        when(conflict.status()).thenReturn(HttpStatus.CONFLICT.value());
        when(conflict.getMessage()).thenReturn("insufficient branch stock");

        when(outboxRepository.findOrderIdById(7L)).thenReturn(Optional.of(42L));
        when(outboxRepository.lockById(7L)).thenReturn(Optional.of(outbox));
        when(orderRepository.lockById(42L)).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderId(42L)).thenReturn(List.of(payment));
        when(catalogInventoryClient.deductPaidOrder(any())).thenThrow(conflict);

        assertEquals("FAILED", worker.process(7L));

        assertEquals(1, outbox.getAttempts());
        assertEquals("FAILED", outbox.getStatus());
        verify(catalogInventoryClient, times(1)).deductPaidOrder(any());
    }
}
