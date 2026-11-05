package com.glassstore.order.service;

import com.glassstore.order.client.CatalogInventoryClient;
import com.glassstore.order.domain.OrderStatus;
import com.glassstore.order.domain.PaymentMethod;
import com.glassstore.order.domain.PaymentStatus;
import com.glassstore.order.domain.SalesChannel;
import com.glassstore.order.dto.CatalogStockDeductionResponse;
import com.glassstore.order.entity.Order;
import com.glassstore.order.entity.Payment;
import com.glassstore.order.entity.StockDeductionOutbox;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.repository.PaymentRepository;
import com.glassstore.order.repository.StockDeductionOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:stock-outbox-concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "inventory.outbox.poll-interval-ms=3600000"
})
class StockOutboxWorkerConcurrencyTest {

    @Autowired
    private StockOutboxWorker worker;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private StockDeductionOutboxRepository outboxRepository;
    @MockBean
    private CatalogInventoryClient catalogInventoryClient;

    @BeforeEach
    void cleanDatabase() {
        outboxRepository.deleteAll();
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        reset(catalogInventoryClient);
    }

    @Test
    void concurrentWorkersProcessAnOutboxEventOnlyOnce() throws Exception {
        Order order = orderRepository.saveAndFlush(Order.builder()
                .orderCode("STOCK-OUTBOX-" + System.nanoTime())
                .channel(SalesChannel.POS)
                .status(OrderStatus.PAID)
                .customerName("Stock test")
                .customerPhone("")
                .branchCode("BRANCH_01")
                .totalAmount(BigDecimal.ONE)
                .finalAmount(BigDecimal.ONE)
                .items(new ArrayList<>())
                .build());
        paymentRepository.saveAndFlush(Payment.builder()
                .orderId(order.getId())
                .paymentMethod(PaymentMethod.CASH)
                .status(PaymentStatus.SUCCESS)
                .amount(BigDecimal.ONE)
                .idempotencyKey("stock-outbox-" + order.getId())
                .transactionCode("TXN-" + order.getId())
                .build());
        StockDeductionOutbox outbox = outboxRepository.saveAndFlush(StockDeductionOutbox.builder()
                .orderId(order.getId())
                .idempotencyKey("stock-outbox-" + order.getId())
                .status("PENDING")
                .build());
        when(catalogInventoryClient.deductPaidOrder(any()))
                .thenReturn(new CatalogStockDeductionResponse(order.getId(), "BRANCH_01", false));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> processAfter(start, outbox.getId()));
            Future<String> second = executor.submit(() -> processAfter(start, outbox.getId()));
            start.countDown();
            assertEquals("SYNCED", first.get());
            assertEquals("SYNCED", second.get());
        } finally {
            executor.shutdownNow();
        }

        StockDeductionOutbox completed = outboxRepository.findById(outbox.getId()).orElseThrow();
        assertEquals("SYNCED", completed.getStatus());
        assertEquals(1, completed.getAttempts());
        verify(catalogInventoryClient, times(1)).deductPaidOrder(any());
    }

    private String processAfter(CountDownLatch start, Long outboxId) throws Exception {
        start.await();
        return worker.process(outboxId);
    }
}
