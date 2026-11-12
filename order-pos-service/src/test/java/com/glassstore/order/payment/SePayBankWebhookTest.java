package com.glassstore.order.payment;

import com.glassstore.order.client.CatalogClient;
import com.glassstore.order.client.CatalogContracts;
import com.glassstore.order.client.LabClient;
import com.glassstore.order.client.LabServiceClient;
import com.glassstore.order.config.OrderDataInitializer;
import com.glassstore.order.domain.OrderStatus;
import com.glassstore.order.domain.PaymentStatus;
import com.glassstore.order.dto.OrderDtos;
import com.glassstore.order.entity.Order;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.repository.PaymentRepository;
import com.glassstore.order.service.OrderCheckoutService;
import com.glassstore.order.service.WarrantyActivationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sepay-bank-webhook;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "payment.vietqr.bank-id=970422",
        "payment.vietqr.bank-name=MBBank (Ngân Hàng Quân Đội)",
        "payment.vietqr.account-no=0938773607",
        "payment.vietqr.account-name=CONG TY OPTIVISION",
        "payment.sepay.webhook-api-key=test-sepay-bank-webhook-key-999",
        "inventory.outbox.poll-interval-ms=3600000", "inventory.reservation.initial-delay-ms=3600000",
        "inventory.reservation.poll-interval-ms=3600000", "lab.outbox.initial-delay-ms=3600000",
        "warranty.outbox.poll-interval-ms=3600000"})
@AutoConfigureMockMvc
class SePayBankWebhookTest {

    private static final String TEST_API_KEY = "test-sepay-bank-webhook-key-999";

    @Autowired MockMvc mvc;
    @Autowired OrderCheckoutService checkout;
    @Autowired OrderRepository orders;
    @Autowired PaymentRepository payments;
    @Autowired SePayProperties sePayProperties;
    @MockBean CatalogClient catalog;
    @MockBean LabClient lab;
    @MockBean LabServiceClient labDetails;
    @MockBean WarrantyActivationService warranties;
    @MockBean OrderDataInitializer seed;

    @BeforeEach
    void setup() {
        sePayProperties.setWebhookApiKey(TEST_API_KEY);
        when(catalog.getBySku(any())).thenAnswer(inv -> {
            var item = new CatalogContracts.VariantInfo();
            item.setSku(inv.getArgument(0));
            item.setName("Gọng test");
            item.setPrice(new BigDecimal("10000"));
            item.setStatus("ACTIVE");
            item.setProductStatus("ACTIVE");
            item.setStockQuantity(100);
            item.setAvailableQuantity(100);
            var envelope = new CatalogContracts.ApiEnvelope<CatalogContracts.VariantInfo>();
            envelope.setData(item);
            return envelope;
        });
        when(catalog.reserve(any())).thenAnswer(inv -> {
            var res = new CatalogContracts.ReservationResult();
            res.setStatus("ACTIVE");
            var env = new CatalogContracts.ApiEnvelope<CatalogContracts.ReservationResult>();
            env.setData(res);
            return env;
        });
        when(catalog.commit(any())).thenAnswer(inv -> {
            var res = new CatalogContracts.ReservationResult();
            res.setStatus("COMMITTED");
            var env = new CatalogContracts.ApiEnvelope<CatalogContracts.ReservationResult>();
            env.setData(res);
            return env;
        });
    }

    private Order createPendingOrder() {
        var req = OrderDtos.CheckoutRequest.builder()
                .channel("ONLINE")
                .orderType("FRAME_ONLY")
                .customerId(10L)
                .customerName("Khách test")
                .customerPhone("0901000099")
                .shippingAddress("123 Lê Lợi")
                .paymentMethod("VIETQR")
                .items(List.of(OrderDtos.CheckoutLine.builder()
                        .sku("SKU-WEBHOOK-TEST")
                        .quantity(1)
                        .expectedUnitPrice(new BigDecimal("10000"))
                        .build()))
                .build();
        return checkout.checkout(req, UUID.randomUUID().toString()).order();
    }

    @Test
    void accountInfoAndWebhookStatusExposeSafeMetadata() throws Exception {
        mvc.perform(get("/api/payments/vietqr/account-info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bankId").value("970422"))
                .andExpect(jsonPath("$.data.accountNo").value("0938773607"))
                .andExpect(jsonPath("$.data.accountName").value("CONG TY OPTIVISION"));

        mvc.perform(get("/api/payments/sepay/webhook/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.accountNoMasked").value("****3607"));
    }

    @Test
    void unconfiguredOrWrongApiKeyIsRejected() throws Exception {
        mvc.perform(post("/api/payments/sepay/webhook")
                        .header("Authorization", "Apikey wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":1,\"transferType\":\"in\",\"transferAmount\":10000,\"content\":\"TEST\"}"))
                .andExpect(status().isUnauthorized());

        sePayProperties.setWebhookApiKey("");
        mvc.perform(post("/api/payments/sepay/webhook")
                        .header("Authorization", "Apikey " + TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":1,\"transferType\":\"in\",\"transferAmount\":10000,\"content\":\"TEST\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void webhookMatchesNormalizedOrderCodeWithoutHyphensAndReplaysIdempotently() throws Exception {
        Order pending = createPendingOrder();
        String strippedCode = pending.getOrderCode().replace("-", "");
        String payload = """
                {
                  "id": 990011,
                  "gateway": "MBBank",
                  "transactionDate": "2026-11-12 07:45:00",
                  "accountNumber": "0938773607",
                  "code": null,
                  "content": "MBVCB.12345.%s.CT tu NGUYEN VAN A",
                  "transferType": "in",
                  "transferAmount": 10000,
                  "referenceCode": "FT261112990011"
                }
                """.formatted(strippedCode);

        mvc.perform(post("/api/payments/sepay/webhook")
                        .header("Authorization", "Apikey " + TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.ignored").value(false))
                .andExpect(jsonPath("$.orderCode").value(pending.getOrderCode()));

        Order reloaded = orders.findById(pending.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(payments.existsByOrderIdAndStatus(pending.getId(), PaymentStatus.SUCCESS)).isTrue();

        mvc.perform(post("/api/payments/sepay/webhook")
                        .header("Authorization", "Apikey " + TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void underpaymentOrOutgoingTransferDoesNotSettleOrder() throws Exception {
        Order pending = createPendingOrder();
        String outgoing = """
                {"id": 990012, "transferType": "out", "transferAmount": 10000, "content": "%s"}
                """.formatted(pending.getOrderCode());
        mvc.perform(post("/api/payments/sepay/webhook")
                        .header("Authorization", "Apikey " + TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(outgoing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ignored").value(true));

        String underpaid = """
                {"id": 990013, "transferType": "in", "transferAmount": 5000, "content": "%s"}
                """.formatted(pending.getOrderCode());
        mvc.perform(post("/api/payments/sepay/webhook")
                        .header("Authorization", "Apikey " + TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(underpaid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ignored").value(true))
                .andExpect(jsonPath("$.message").value("AMOUNT_MISMATCH"));

        assertThat(orders.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }
}
