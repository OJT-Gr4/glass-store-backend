package com.glassstore.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glassstore.order.client.CatalogClient;
import com.glassstore.order.client.CatalogContracts;
import com.glassstore.order.config.OrderDataInitializer;
import com.glassstore.order.domain.*;
import com.glassstore.order.entity.Order;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.repository.PaymentRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:online-rx;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "inventory.outbox.poll-interval-ms=3600000", "warranty.outbox.poll-interval-ms=3600000"
})
@AutoConfigureMockMvc
@Transactional
@org.springframework.security.test.context.support.WithMockUser(username="1", roles="ADMIN")
class OnlinePrescriptionCheckoutTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OrderRepository orders;
    @Autowired PaymentRepository payments;
    @Autowired com.glassstore.order.repository.OrderInventoryTaskRepository inventoryTasks;
    @Autowired EntityManager entities;
    @MockBean CatalogClient catalog;
    @MockBean OrderDataInitializer demoSeed;

    @BeforeEach void catalogFixture() {
        when(catalog.getBySku(anyString())).thenAnswer(invocation -> {
            String sku = invocation.getArgument(0);
            var variant = new CatalogContracts.VariantInfo();
            variant.setSku(sku); variant.setName(sku); variant.setStatus("ACTIVE"); variant.setProductStatus("ACTIVE");
            variant.setPrice(new BigDecimal(sku.startsWith("FRM-") ? "1000000" : "500000"));
            variant.setAvailableQuantity(10); variant.setStockQuantity(10);
            var envelope = new CatalogContracts.ApiEnvelope<CatalogContracts.VariantInfo>();
            envelope.setData(variant); return envelope;
        });
    }

    private Map<String,Object> request() throws Exception {
        return json.readValue("""
            {"channel":"ONLINE","customerId":10,"customerName":"Khách kiểm thử","customerPhone":"0901234567",
             "shippingAddress":"Địa chỉ kiểm thử","paymentMethod":"VIETQR","orderType":"COMPLETE_GLASSES",
             "items":[{"sku":"FRM-TEST","quantity":1,"expectedUnitPrice":1000000},{"sku":"LNS-TEST","quantity":1,"expectedUnitPrice":500000}],
             "prescription":{"odSph":-2.25,"odCyl":0,"odAxis":0,"odAdd":0,"osSph":-1.75,"osCyl":-0.5,"osAxis":180,"osAdd":0,"pd":63.5,"confirmed":true}}
            """, Map.class);
    }

    @Test void storesCustomerMeasurementsAndReplayReturnsTheSameOrder() throws Exception {
        var payload = request();
        String first = mvc.perform(post("/api/orders").header("Idempotency-Key","online-rx-retry")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(payload)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
            .andExpect(jsonPath("$.data.finalAmount").value(1500000))
            .andExpect(jsonPath("$.data.prescription.source").value("CUSTOMER_PROVIDED"))
            .andReturn().getResponse().getContentAsString();
        long id = json.readTree(first).path("data").path("id").asLong();
        orders.flush(); entities.clear();
        var persisted = orders.findById(id).orElseThrow();
        assertEquals(0, persisted.getPrescription().getOdSph().compareTo(new BigDecimal("-2.25")));
        assertEquals(0, persisted.getPrescription().getOdCyl().compareTo(BigDecimal.ZERO));
        assertEquals(180, persisted.getPrescription().getOsAxis());
        assertNotNull(persisted.getPrescription().getCustomerConfirmedAt());
        assertNull(persisted.getPrescriptionId());
        assertEquals(PaymentStatus.PENDING, payments.findByOrderId(id).get(0).getStatus());
        mvc.perform(get("/api/orders/"+id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.prescription.pd").value(63.5));
        mvc.perform(post("/api/orders").header("Idempotency-Key","online-rx-retry")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(payload)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id));
        assertEquals(1, orders.count()); assertEquals(1, payments.count());
        var task=inventoryTasks.findById(id).orElseThrow();
        assertEquals("RESERVE",task.getOperation());assertEquals("PENDING",task.getStatus());
        verify(catalog,never()).reserve(any()); // Remote work starts only after this transaction commits.
    }

    @Test void rejectsUnconfirmedOrInvalidMeasurementsWithoutCreatingAnOrder() throws Exception {
        var payload = request();
        var rx = (Map<String,Object>)payload.get("prescription");
        rx.put("confirmed",false);
        mvc.perform(post("/api/orders").header("Idempotency-Key","rx-unconfirmed")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(payload)))
            .andExpect(status().isBadRequest());
        rx.put("confirmed",true); rx.put("odAxis",181);
        mvc.perform(post("/api/orders").header("Idempotency-Key","rx-invalid-axis")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(payload)))
            .andExpect(status().isBadRequest());
        assertEquals(0, orders.count()); assertEquals(0, payments.count());
    }

    @Test void missingPrescriptionCannotCreateCompleteGlassesOrder() throws Exception {
        var payload=request(); payload.remove("prescription");
        mvc.perform(post("/api/orders").header("Idempotency-Key","rx-missing")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(payload)))
            .andExpect(status().is4xxClientError());
        assertEquals(0,orders.count());
    }

    @Test void omsStatusChangeSurvivesReloadAndRejectsAnInvalidJump() throws Exception {
        Order order = orders.saveAndFlush(Order.builder().orderCode("QA-OMS-STATUS").customerName("QA").customerPhone("")
            .channel(SalesChannel.ONLINE).orderType(OrderType.FRAME_ONLY).paymentMethod(PaymentMethod.CASH)
            .totalAmount(BigDecimal.TEN).finalAmount(BigDecimal.TEN).status(OrderStatus.PAID).build());
        mvc.perform(patch("/api/orders/"+order.getId()+"/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CONFIRMED\",\"actor\":\"QA\",\"reason\":\"Kiểm tra OMS\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        orders.flush(); entities.clear();
        mvc.perform(get("/api/orders/"+order.getId())).andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        mvc.perform(patch("/api/orders/"+order.getId()+"/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"COMPLETED\"}"))
            .andExpect(status().is4xxClientError());
        mvc.perform(get("/api/orders/"+order.getId()+"/history"))
            .andExpect(jsonPath("$.data[0].actor").value("1"));
    }
}
