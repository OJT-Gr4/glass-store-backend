package com.glassstore.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glassstore.order.client.*;
import com.glassstore.order.dto.OrderDtos;
import com.glassstore.order.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:lab-dispatch;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "inventory.outbox.poll-interval-ms=3600000","lab.outbox.initial-delay-ms=3600000","lab.outbox.poll-interval-ms=3600000"})
class LabDispatchRecoveryTest {
    @Autowired OrderService orders;
    @Autowired LabDispatchService dispatch;
    @Autowired LabDispatchOutboxRepository outbox;
    @Autowired OrderRepository orderRepository;
    @Autowired OrderStatusHistoryRepository history;
    @Autowired PaymentRepository payments;
    @Autowired StockDeductionOutboxRepository stockOutbox;
    @Autowired PlatformTransactionManager transactions;
    @MockBean PosDataService data;
    @MockBean CatalogInventoryClient catalog;
    @MockBean StockOutboxWorker stockWorker;
    @MockBean WarrantyActivationService warranties;
    @MockBean LabClient lab;

    @BeforeEach void prepare() throws Exception {
        stockOutbox.deleteAll(); outbox.deleteAll(); history.deleteAll(); payments.deleteAll(); orderRepository.deleteAll();
        when(data.actor(any(),anyBoolean())).thenReturn(new ObjectMapper().readTree("{\"id\":1,\"roles\":[\"ROLE_ADMIN\"]}"));
        when(data.requirePrescription(eq(7L),eq(10L),any())).thenReturn(new ObjectMapper().readTree("""
                {"id":7,"version":0,"customerId":10,"odSph":-2,"odCyl":0,"odAxis":0,"odAdd":0,"osSph":-2,"osCyl":0,"osAxis":0,"osAdd":0,"pd":63}
                """));
        when(data.catalogItems()).thenReturn(Map.of("FRM-QA", new PosDataService.CatalogItem(new BigDecimal("1000000"), "QA frame", "FRAME", true),
                "LNS-QA", new PosDataService.CatalogItem(new BigDecimal("500000"), "QA lens", "LENS", true)));
        when(stockWorker.process(anyLong())).thenReturn("SYNCED");
    }
    private OrderDtos.CheckoutRequest request() throws Exception {
        return new ObjectMapper().readValue("""
            {"channel":"POS","customerId":10,"customerName":"QA Lab","customerPhone":"0901234567",
             "paymentMethod":"CASH","orderType":"COMPLETE_GLASSES","branchCode":"BRANCH_01","cashReceived":2000000,
             "prescriptionId":7,"items":[{"sku":"FRM-QA","quantity":1,"expectedUnitPrice":1000000},{"sku":"LNS-QA","quantity":1,"prescriptionId":7,"expectedUnitPrice":500000}]}
            """,OrderDtos.CheckoutRequest.class);
    }
    @Test void committedSaleRetriesDeliveryWithoutDuplicatingTheOrderOrLabRequest() throws Exception {
        var response = new CatalogContracts.ApiEnvelope<Map<String,Object>>(); response.setData(Map.of("id",4001));
        when(lab.createFromOrder(any())).thenThrow(new IllegalStateException("Lab temporarily unavailable")).thenReturn(response);
        var saved=orders.createCounterSale(request(),"qa-lab-retry","Bearer qa").getBody();
        var pending=outbox.findByOrderId(saved.getId()).orElseThrow();
        assertEquals("PENDING",pending.getStatus()); assertEquals(1,pending.getAttempts());
        dispatch.retryPending();
        var delivered=outbox.findByOrderId(saved.getId()).orElseThrow();
        assertEquals("SYNCED",delivered.getStatus()); assertEquals(2,delivered.getAttempts()); assertEquals(4001L,delivered.getLabJobId());
        assertEquals(saved.getId(),orders.createCounterSale(request(),"qa-lab-retry","Bearer qa").getBody().getId());
        dispatch.enqueue(saved); dispatch.retryPending();
        assertEquals(1,outbox.count()); assertEquals(1,orderRepository.count()); assertEquals(1,payments.count());
        verify(lab,times(2)).createFromOrder(argThat(payload -> saved.getId().equals(payload.get("orderId"))
            && Long.valueOf(7).equals(payload.get("prescriptionId")) && "LNS-QA".equals(payload.get("lensSku"))
            && Long.valueOf(0).equals(payload.get("prescriptionVersion"))
            && Long.valueOf(10).equals(payload.get("customerId")) && payload.get("orderItemId") != null
            && ("LAB_REQ_" + saved.getId() + "_" + payload.get("orderItemId")).equals(payload.get("idempotencyKey"))));
    }
    @Test void rolledBackSaleDoesNotCreateALabJob() throws Exception {
        var payload=request();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            orders.createCounterSale(payload,"qa-lab-rollback","Bearer qa"); status.setRollbackOnly();
        });
        assertEquals(0,outbox.count()); assertEquals(0,orderRepository.count()); assertEquals(0,payments.count());
        verifyNoInteractions(lab);
    }
}
