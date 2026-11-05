package com.glassstore.order.controller;

import com.glassstore.order.config.OrderDataInitializer;
import com.glassstore.order.domain.*;
import com.glassstore.order.entity.*;
import com.glassstore.order.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Acceptance criteria S2-D3-04; failures identify outstanding implementation work. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:oms-day3-acceptance;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "inventory.outbox.poll-interval-ms=3600000", "warranty.outbox.poll-interval-ms=3600000",
    "lab.outbox.initial-delay-ms=3600000", "lab.outbox.poll-interval-ms=3600000"
})
@AutoConfigureMockMvc
@org.springframework.security.test.context.support.WithMockUser(username="1", roles="ADMIN")
class OmsDay3AcceptanceTest {
    @Autowired MockMvc mvc;
    @Autowired OrderRepository orders;
    @Autowired PaymentRepository payments;
    @Autowired OrderStatusHistoryRepository history;
    @MockBean OrderDataInitializer demoSeed;
    @MockBean com.glassstore.order.client.LabClient lab;

    @BeforeEach void resetIsolatedDatabase() {
        history.deleteAll(); payments.deleteAll(); orders.deleteAll();
        org.mockito.Mockito.when(lab.getByOrder(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(call -> {
            var response=new com.glassstore.order.client.CatalogContracts.ApiEnvelope<java.util.Map<String,Object>>();
            response.setData(java.util.Map.of("orderId",call.getArgument(0),"status","IN_PROGRESS")); return response;
        });
    }
    private Order order(OrderStatus status, OrderType type, SalesChannel channel) {
        return orders.saveAndFlush(Order.builder().orderCode("QA-D3-"+System.nanoTime())
            .customerName("QA Day 3").customerPhone("0901234567").channel(channel).orderType(type)
            .paymentMethod(PaymentMethod.CASH).status(status).totalAmount(BigDecimal.TEN).finalAmount(BigDecimal.TEN).build());
    }
    private String payload(String status) {
        return "{\"status\":\""+status+"\",\"actor\":\"QA-OMS\",\"reason\":\"Day 3 acceptance\"}";
    }
    @Test void validTransitionPersistsAndRecordsHistory() throws Exception {
        var order=order(OrderStatus.PAID,OrderType.FRAME_ONLY,SalesChannel.ONLINE);
        mvc.perform(patch("/api/orders/"+order.getId()+"/status").contentType(MediaType.APPLICATION_JSON).content(payload("PREPARING")))
            .andExpect(status().isOk());
        assertEquals(OrderStatus.PREPARING,orders.findById(order.getId()).orElseThrow().getStatus());
        var audit=history.findByOrderIdOrderByChangedAtAsc(order.getId());
        assertEquals(1,audit.size()); assertNotNull(audit.get(0).getChangedAt());
        assertEquals("1",audit.get(0).getActor()); assertEquals("Day 3 acceptance",audit.get(0).getReason());
    }
    @Test void invalidJumpKeepsOriginalOrderAndHistory() throws Exception {
        var order=order(OrderStatus.PENDING_PAYMENT,OrderType.FRAME_ONLY,SalesChannel.ONLINE);
        mvc.perform(patch("/api/orders/"+order.getId()+"/status").contentType(MediaType.APPLICATION_JSON).content(payload("SHIPPING")))
            .andExpect(status().is4xxClientError());
        assertEquals(OrderStatus.PENDING_PAYMENT,orders.findById(order.getId()).orElseThrow().getStatus());
        assertTrue(history.findByOrderIdOrderByChangedAtAsc(order.getId()).isEmpty());
    }
    @Test @org.springframework.security.test.context.support.WithAnonymousUser
    void unauthenticatedCallerCannotChangeStatus() throws Exception {
        var order=order(OrderStatus.PAID,OrderType.FRAME_ONLY,SalesChannel.ONLINE);
        mvc.perform(patch("/api/orders/"+order.getId()+"/status").contentType(MediaType.APPLICATION_JSON).content(payload("PREPARING")))
            .andExpect(status().isUnauthorized());
    }
    @Test void prescriptionGlassesCannotShipWithoutVerifiedQc() throws Exception {
        var order=order(OrderStatus.PREPARING,OrderType.COMPLETE_GLASSES,SalesChannel.ONLINE);
        order.setPrescriptionId(999L); orders.saveAndFlush(order);
        payments.saveAndFlush(Payment.builder().orderId(order.getId()).paymentMethod(PaymentMethod.CASH)
            .amount(BigDecimal.TEN).status(PaymentStatus.SUCCESS).build());
        mvc.perform(patch("/api/orders/"+order.getId()+"/status").contentType(MediaType.APPLICATION_JSON).content(payload("SHIPPING")))
            .andExpect(status().is4xxClientError());
    }
    @Test void rejectedCompletionDoesNotMarkPaymentSuccessful() throws Exception {
        var order=order(OrderStatus.PENDING_PAYMENT,OrderType.FRAME_ONLY,SalesChannel.ONLINE);
        var payment=payments.saveAndFlush(Payment.builder().orderId(order.getId()).paymentMethod(PaymentMethod.CASH)
            .amount(BigDecimal.TEN).status(PaymentStatus.PENDING).build());
        mvc.perform(patch("/api/orders/"+order.getId()+"/status").contentType(MediaType.APPLICATION_JSON).content(payload("COMPLETED")))
            .andExpect(status().is4xxClientError());
        assertEquals(PaymentStatus.PENDING,payments.findById(payment.getId()).orElseThrow().getStatus());
    }
    @Test void posCancellationCannotBypassCompensationByChangingActor() throws Exception {
        var order=order(OrderStatus.PAID,OrderType.FRAME_ONLY,SalesChannel.POS);
        payments.saveAndFlush(Payment.builder().orderId(order.getId()).paymentMethod(PaymentMethod.CASH)
            .amount(BigDecimal.TEN).status(PaymentStatus.SUCCESS).build());
        mvc.perform(patch("/api/orders/"+order.getId()+"/status").contentType(MediaType.APPLICATION_JSON).content(payload("CANCELLED")))
            .andExpect(status().is4xxClientError());
    }

    @Test void verifiedQcAllowsHandoverAndPersistsTheTrackingCode() throws Exception {
        var order=order(OrderStatus.PREPARING,OrderType.COMPLETE_GLASSES,SalesChannel.ONLINE);
        var response=new com.glassstore.order.client.CatalogContracts.ApiEnvelope<java.util.Map<String,Object>>();
        response.setData(java.util.Map.of("orderId",order.getId(),"status","QC_PASSED"));
        org.mockito.Mockito.when(lab.getByOrder(order.getId())).thenReturn(response);
        mvc.perform(patch("/api/orders/"+order.getId()+"/status").contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"SHIPPING\",\"carrierName\":\"QA carrier\",\"trackingCode\":\"QA-123\",\"actor\":\"forged\",\"reason\":\"QC verified\"}"))
            .andExpect(status().isOk());
        var saved=orders.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.SHIPPING,saved.getStatus()); assertEquals("QA-123",saved.getTrackingCode());
        assertEquals("1",history.findByOrderIdOrderByChangedAtAsc(order.getId()).get(0).getActor());
    }
}
