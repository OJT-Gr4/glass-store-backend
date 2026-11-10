package com.glassstore.order.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.glassstore.order.client.*;
import com.glassstore.order.config.OrderDataInitializer;
import com.glassstore.order.domain.*;
import com.glassstore.order.dto.OrderDtos;
import com.glassstore.order.entity.Order;
import com.glassstore.order.repository.*;
import com.glassstore.order.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sepay-api;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "payment.provider=sepay", "payment.sepay.merchant-id=TEST_MERCHANT", "payment.sepay.secret-key=sandbox-unit-test-only",
        "payment.sepay.ipn-secret=unit-test-ipn-secret-independent-123456", "payment.sepay.return-base-url=http://localhost:5184",
        "inventory.outbox.poll-interval-ms=3600000", "inventory.reservation.initial-delay-ms=3600000",
        "inventory.reservation.poll-interval-ms=3600000", "lab.outbox.initial-delay-ms=3600000",
        "warranty.outbox.poll-interval-ms=3600000"})
@AutoConfigureMockMvc
class SePayApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OrderCheckoutService checkout;
    @Autowired PaymentService paymentService;
    @Autowired OrderRepository orders;
    @Autowired PaymentRepository payments;
    @Autowired OrderInventoryTaskRepository inventoryTasks;
    @Autowired SePayProperties properties;
    @MockBean CatalogClient catalog;
    @MockBean LabClient lab;
    @MockBean LabServiceClient labDetails;
    @MockBean WarrantyActivationService warranties;
    @MockBean OrderDataInitializer seed;

    @BeforeEach
    void setup() {
        properties.setMerchantId("TEST_MERCHANT");
        when(catalog.getBySku(any())).thenAnswer(inv -> {
            var item = new CatalogContracts.VariantInfo();
            item.setSku(inv.getArgument(0)); item.setName("QA frame"); item.setPrice(new BigDecimal("10000"));
            item.setStatus("ACTIVE"); item.setProductStatus("ACTIVE"); item.setStockQuantity(100); item.setAvailableQuantity(100);
            var result = new CatalogContracts.ApiEnvelope<CatalogContracts.VariantInfo>(); result.setData(item); return result;
        });
        when(catalog.reserve(any())).thenReturn(stockResult("ACTIVE"));
        when(catalog.commit(any())).thenReturn(stockResult("COMMITTED"));
    }

    @Test
    @WithMockUser(username = "10", roles = "CUSTOMER")
    void initiateUsesStoredAmountAndChecksOwnership() throws Exception {
        Order order = order();
        mvc.perform(post(initiateUrl(order))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.httpMethod").value("POST"))
                .andExpect(jsonPath("$.data.formFields.order_amount").value(order.getFinalAmount().toBigIntegerExact().toString()))
                .andExpect(jsonPath("$.data.formFields.signature").isString());
        mvc.perform(post(initiateUrl(order))).andExpect(status().isOk());
        assertThat(payments.findByOrderId(order.getId())).hasSize(1);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        order.setCustomerId(20L); orders.save(order);
        mvc.perform(post(initiateUrl(order))).andExpect(status().isForbidden());
    }

    @Test
    void initiateRequiresLoginAndMissingMerchantDoesNotSettle() throws Exception {
        Order order = order();
        mvc.perform(post(initiateUrl(order))).andExpect(status().isUnauthorized());
        properties.setMerchantId("");
        try {
            assertThatThrownBy(() -> paymentService.initiate(order.getId()))
                    .isInstanceOf(com.glassstore.common.exception.ApiException.class);
            assertThat(payments.findByOrderId(order.getId()).get(0).getStatus()).isEqualTo(PaymentStatus.PENDING);
        } finally { properties.setMerchantId("TEST_MERCHANT"); }
    }

    @Test
    void concurrentAuthenticatedIpnsCommitOnePaymentAndStockMovement() throws Exception {
        Order order = order(); paymentService.initiate(order.getId());
        String payload = payload(order).toString();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) results.add(pool.submit(() -> {
                start.await(); return paymentService.handleCallback(payload, SePayGatewayTest.IPN_SECRET).payment().getId();
            }));
            start.countDown(); Set<Long> ids = new HashSet<>();
            for (Future<Long> result : results) ids.add(result.get(30, TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
        } finally { pool.shutdownNow(); }
        mvc.perform(post("/api/payments/sepay/ipn").header("X-Secret-Key", SePayGatewayTest.IPN_SECRET)
                .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.replayed").value(true));
        var saved = payments.findByOrderId(order.getId());
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(saved.get(0).getProviderOrderId()).isEqualTo("sepay-" + order.getId());
        assertThat(saved.get(0).getPaymentReference()).isEqualTo("bank-" + order.getId());
        assertThat(saved.get(0).getConfirmationMode()).isEqualTo("GATEWAY_VERIFIED");
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(inventoryTasks.findById(order.getId()).orElseThrow().getStatus()).isEqualTo("SYNCED");
        verify(catalog, times(1)).commit(any());
        ObjectNode tampered = payload(order);
        ((ObjectNode) tampered.get("order")).put("order_amount", "1");
        ((ObjectNode) tampered.get("transaction")).put("transaction_amount", "1");
        mvc.perform(post("/api/payments/sepay/ipn").header("X-Secret-Key", SePayGatewayTest.IPN_SECRET)
                .contentType(MediaType.APPLICATION_JSON).content(tampered.toString())).andExpect(status().isConflict());
    }

    @Test
    void invalidAndUnsolicitedCallbacksCannotSettleOrReopenCancelledOrders() throws Exception {
        Order order = order();
        String payload = payload(order).toString();
        mvc.perform(post("/api/payments/sepay/ipn").header("X-Secret-Key", "wrong")
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/payments/sepay/ipn").header("X-Secret-Key", SePayGatewayTest.IPN_SECRET)
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isNotFound());
        paymentService.initiate(order.getId());
        order.setStatus(OrderStatus.CANCELLED); orders.save(order);
        mvc.perform(post("/api/payments/sepay/ipn").header("X-Secret-Key", SePayGatewayTest.IPN_SECRET)
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isConflict());
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(payments.findByOrderId(order.getId()).get(0).getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(catalog, never()).commit(any());
    }

    @Test
    @WithMockUser(username = "10", roles = "CUSTOMER")
    void configurationShowsBlockedWithoutExposingSecrets() throws Exception {
        properties.setMerchantId("");
        try {
            String response = mvc.perform(get("/api/payments/sepay/configuration"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("BLOCKED"))
                    .andExpect(jsonPath("$.data.configured").value(false))
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain(properties.getSecretKey(), properties.getIpnSecret());
        } finally { properties.setMerchantId("TEST_MERCHANT"); }
    }

    @Test
    void rejectsTransactionReassignmentAndConflictingReferencesOnReplay() throws Exception {
        Order original = order(); paymentService.initiate(original.getId());
        paymentService.handleCallback(payload(original).toString(), SePayGatewayTest.IPN_SECRET);
        ObjectNode replay = payload(original);
        ((ObjectNode) replay.get("transaction")).put("transaction_id", "different-reference");
        mvc.perform(post("/api/payments/sepay/ipn").header("X-Secret-Key", SePayGatewayTest.IPN_SECRET)
                .contentType(MediaType.APPLICATION_JSON).content(replay.toString())).andExpect(status().isConflict());

        Order other = order(); paymentService.initiate(other.getId());
        ObjectNode reassigned = payload(other);
        ((ObjectNode) reassigned.get("transaction")).put("id", "txn-" + original.getId());
        mvc.perform(post("/api/payments/sepay/ipn").header("X-Secret-Key", SePayGatewayTest.IPN_SECRET)
                .contentType(MediaType.APPLICATION_JSON).content(reassigned.toString())).andExpect(status().isBadRequest());
        assertThat(orders.findById(other.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(payments.findByOrderId(other.getId())).hasSize(1);
        assertThat(payments.findByOrderId(other.getId()).get(0).getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(catalog, times(1)).commit(any());
    }

    @Test
    void inventoryOutageKeepsVerifiedMoneyAndDuplicateIpnDoesNotStartAnotherCommit() {
        Order order = order(); paymentService.initiate(order.getId());
        when(catalog.commit(any())).thenThrow(new IllegalStateException("Inventory acknowledgement unavailable"));
        String body = payload(order).toString();
        var first = paymentService.handleCallback(body, SePayGatewayTest.IPN_SECRET);
        assertThat(first.payment().getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        var task = inventoryTasks.findById(order.getId()).orElseThrow();
        assertThat(task.getStatus()).isIn("PENDING", "RECONCILIATION_REQUIRED");
        assertThat(task.getLastError()).contains("Inventory acknowledgement unavailable");
        assertThat(paymentService.handleCallback(body, SePayGatewayTest.IPN_SECRET).replayed()).isTrue();
        assertThat(payments.findByOrderId(order.getId())).hasSize(1);
        verify(catalog, times(1)).commit(any());
    }

    private Order order() {
        var request = OrderDtos.CheckoutRequest.builder().channel("ONLINE").orderType("FRAME_ONLY")
                .customerId(10L).customerName("QA").customerPhone("0901234567").paymentMethod("VIETQR")
                .items(List.of(OrderDtos.CheckoutLine.builder().sku("FRM-QA").quantity(1)
                        .expectedUnitPrice(new BigDecimal("10000")).build())).build();
        return checkout.checkout(request, UUID.randomUUID().toString()).order();
    }

    private String initiateUrl(Order order) { return "/api/payments/order/" + order.getId() + "/initiate"; }

    private ObjectNode payload(Order order) {
        ObjectNode body = json.createObjectNode().put("timestamp", 1794182400L).put("notification_type", "ORDER_PAID");
        body.putObject("order").put("id", "sepay-" + order.getId()).put("order_invoice_number", order.getOrderCode())
                .put("order_status", "CAPTURED").put("order_currency", "VND").put("order_amount", order.getFinalAmount().toPlainString());
        body.putObject("transaction").put("id", "txn-" + order.getId()).put("transaction_id", "bank-" + order.getId())
                .put("transaction_type", "PAYMENT").put("transaction_status", "APPROVED").put("transaction_currency", "VND")
                .put("payment_method", "BANK_TRANSFER").put("transaction_amount", order.getFinalAmount().toPlainString());
        return body;
    }

    private CatalogContracts.ApiEnvelope<CatalogContracts.ReservationResult> stockResult(String status) {
        var value = new CatalogContracts.ReservationResult(); value.setStatus(status);
        var result = new CatalogContracts.ApiEnvelope<CatalogContracts.ReservationResult>(); result.setData(value); return result;
    }
}
