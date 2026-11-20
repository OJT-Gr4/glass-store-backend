package com.glassstore.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glassstore.order.client.*;
import com.glassstore.order.dto.OrderDtos;
import com.glassstore.order.repository.*;
import com.glassstore.order.entity.*;
import com.glassstore.order.entity.Order;
import com.glassstore.order.domain.*;
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
    @Autowired LabOrderReviewService reviews;
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
        when(lab.createFromOrder(any())).thenThrow(new IllegalStateException("[409] http://127.0.0.1:8084 internal diagnostic")).thenReturn(response);
        var saved=orders.createCounterSale(request(),"qa-lab-retry","Bearer qa").getBody();
        assertTrue(outbox.findByOrderId(saved.getId()).isEmpty());
        verify(lab, never()).createFromOrder(any());
        reviews.approve(saved.getId(),null,null,true,"POS staff");
        var pending=outbox.findByOrderId(saved.getId()).orElseThrow();
        assertEquals("PENDING",pending.getStatus()); assertEquals(1,pending.getAttempts());
        assertFalse(reviews.pending().getFirst().message().contains("127.0.0.1"));
        assertFalse(reviews.pending().getFirst().message().contains("[409]"));
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

    private Order customerInputOrder() {
        var rx = OrderPrescription.builder().odSph(new BigDecimal("-2")).odCyl(BigDecimal.ZERO).odAxis(0).odAdd(BigDecimal.ZERO)
                .osSph(new BigDecimal("-2")).osCyl(BigDecimal.ZERO).osAxis(0).osAdd(BigDecimal.ZERO).pd(new BigDecimal("63"))
                .source("CUSTOMER_PROVIDED").customerConfirmedAt(java.time.LocalDateTime.now()).build();
        var order = Order.builder().orderCode("QA-"+UUID.randomUUID()).customerId(10L).customerName("QA Lab").customerPhone("0901234567")
                .channel(SalesChannel.ONLINE).orderType(OrderType.LENS_ONLY).status(OrderStatus.PREPARING).paymentMethod(PaymentMethod.VIETQR)
                .totalAmount(new BigDecimal("500000")).finalAmount(new BigDecimal("500000")).prescription(rx).build();
        order.getItems().add(OrderItem.builder().order(order).skuId("LNS-QA").productName("QA lens").quantity(1).unitPrice(new BigDecimal("500000")).subtotal(new BigDecimal("500000")).build());
        order = orderRepository.save(order);
        payments.save(Payment.builder().orderId(order.getId()).paymentMethod(PaymentMethod.VIETQR).amount(order.getFinalAmount()).status(PaymentStatus.SUCCESS).build());
        return order;
    }
    private void clinicalResponse(long customerId, long version) {
        var response = new CatalogContracts.ApiEnvelope<Map<String,Object>>();
        response.setData(Map.ofEntries(Map.entry("id",7L),Map.entry("customerId",customerId),Map.entry("version",version),
                Map.entry("odSph",-2),Map.entry("odCyl",0),Map.entry("odAxis",0),Map.entry("odAdd",0),
                Map.entry("osSph",-2),Map.entry("osCyl",0),Map.entry("osAxis",0),Map.entry("osAdd",0),Map.entry("pd",63)));
        when(lab.getPrescription(7L)).thenReturn(response);
    }
    @Test void paidCustomerInputIsVisibleAndReviewedPrescriptionDispatchesExactlyOnce() {
        var order = customerInputOrder();
        assertEquals("WAITING_POS", reviews.pending().getFirst().state());
        assertTrue(outbox.findByOrderId(order.getId()).isEmpty());
        clinicalResponse(10L, 2L);
        var job = new CatalogContracts.ApiEnvelope<Map<String,Object>>(); job.setData(Map.of("id",4001)); when(lab.createFromOrder(any())).thenReturn(job);
        reviews.approve(order.getId(),7L,2L,true,"clinician");
        assertEquals("SYNCED",outbox.findByOrderId(order.getId()).orElseThrow().getStatus());
        assertTrue(reviews.pending().isEmpty());
        assertEquals("CLINICAL_REVISION",orderRepository.findById(order.getId()).orElseThrow().getPrescription().getSource());
        reviews.approve(order.getId(),7L,2L,true,"clinician");
        verify(lab,times(1)).createFromOrder(argThat(payload -> Long.valueOf(2).equals(payload.get("prescriptionVersion")) && Long.valueOf(7).equals(payload.get("prescriptionId"))));
        assertEquals(1,history.findByOrderIdOrderByChangedAtAsc(order.getId()).size());
    }
    @Test void cannotApproveWrongCustomerStaleRevisionOrMissingConfirmation() {
        var order = customerInputOrder();
        assertThrows(Exception.class,()->reviews.approve(order.getId(),7L,2L,false,"clinician"));
        clinicalResponse(999L,2L);
        assertThrows(Exception.class,()->reviews.approve(order.getId(),7L,2L,true,"clinician"));
        clinicalResponse(10L,3L);
        assertThrows(Exception.class,()->reviews.approve(order.getId(),7L,2L,true,"clinician"));
        assertTrue(outbox.findByOrderId(order.getId()).isEmpty());
        assertEquals("CUSTOMER_PROVIDED",orderRepository.findById(order.getId()).orElseThrow().getPrescription().getSource());
        verify(lab,never()).createFromOrder(any());
    }

    @Test void directOrderMeasurementsCanDispatchToLabWithoutClinicalExamMatch() {
        var order = customerInputOrder();
        var job = new CatalogContracts.ApiEnvelope<Map<String,Object>>(); job.setData(Map.of("id",4002));
        when(lab.createFromOrder(any())).thenReturn(job);
        reviews.approve(order.getId(), null, null, true, "clinician");
        var confirmed = orderRepository.findById(order.getId()).orElseThrow();
        assertNotNull(confirmed.getLabConfirmedAt());
        assertEquals("CUSTOMER_PROVIDED", confirmed.getPrescription().getSource());
        assertEquals("SYNCED", outbox.findByOrderId(order.getId()).orElseThrow().getStatus());
        verify(lab, times(1)).createFromOrder(argThat(payload -> order.getId().equals(payload.get("orderId"))));
    }
    @Test void oldPendingOutboxCannotBypassPosApproval() {
        var order = customerInputOrder();
        var entry = outbox.save(LabDispatchOutbox.builder().orderId(order.getId()).build());
        dispatch.retryPending();
        assertEquals("WAITING_POS",outbox.findById(entry.getId()).orElseThrow().getStatus());
        verify(lab,never()).createFromOrder(any());
    }
    @Test void posRejectsIncompleteOrInvalidMeasurementsWithoutInventingDefaults() {
        var order = customerInputOrder();
        order.getPrescription().setPd(new BigDecimal("1.5")); orderRepository.save(order);
        assertThrows(Exception.class,()->reviews.approve(order.getId(),null,null,true,"POS staff"));
        assertNull(orderRepository.findById(order.getId()).orElseThrow().getLabConfirmedAt());
        assertTrue(outbox.findByOrderId(order.getId()).isEmpty());
        verify(lab,never()).createFromOrder(any());
    }
}
