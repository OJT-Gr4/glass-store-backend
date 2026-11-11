package com.glassstore.order.controller;

import com.glassstore.order.config.VietQrProperties;
import com.glassstore.order.domain.OrderStatus;
import com.glassstore.order.domain.PaymentMethod;
import com.glassstore.order.dto.VietQrDtos.*;
import com.glassstore.order.entity.Order;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.service.OrderCheckoutService;
import com.glassstore.order.service.VietQrEmvcoUtil;
import com.glassstore.order.service.VietQrService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class VietQrIntegrationTest {

    private VietQrProperties properties;
    private Map<String, Order> ordersDb;
    private OrderRepository orderRepository;
    private StubCheckoutService checkoutService;
    private VietQrService vietQrService;
    private VietQrCallbackController controller;

    static class StubCheckoutService extends OrderCheckoutService {
        Order lastPaidOrder;
        String lastProvider;
        int callCount = 0;

        StubCheckoutService() {
            super(null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public Order markPaid(Order input, String actor, String reason, String provider) {
            this.callCount++;
            this.lastPaidOrder = input;
            this.lastProvider = provider;
            input.setStatus(OrderStatus.PAID);
            return input;
        }
    }

    @BeforeEach
    void setUp() {
        properties = new VietQrProperties();
        properties.getPartner().setUsername("glassstore_pos");
        properties.getPartner().setPassword("GlassStore@VietQr2026");
        properties.getPartner().setBankCode("MB");
        properties.getPartner().setBankAccount("0938773607");
        properties.getPartner().setUserBankName("CONG TY KINH MAT OPTIVISION");
        properties.getPartner().setTokenSecret("TestSecretKeyForVietQr1234567890!@#");

        ordersDb = new HashMap<>();
        orderRepository = (OrderRepository) Proxy.newProxyInstance(
                OrderRepository.class.getClassLoader(),
                new Class<?>[]{OrderRepository.class},
                (proxy, method, args) -> {
                    if ("findByOrderCode".equals(method.getName())) {
                        String code = (String) args[0];
                        return Optional.ofNullable(ordersDb.get(code));
                    }
                    return null;
                }
        );

        checkoutService = new StubCheckoutService();
        vietQrService = new VietQrService(properties, orderRepository, checkoutService);
        controller = new VietQrCallbackController(vietQrService, properties, orderRepository);
    }

    @Test
    void step1_getToken_withValidCredentials_returnsBearerToken() {
        String authHeader = "Basic " + Base64.getEncoder().encodeToString("glassstore_pos:GlassStore@VietQr2026".getBytes(StandardCharsets.UTF_8));
        ResponseEntity<TokenResponse> response = controller.getToken(authHeader);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getAccessToken()).isNotEmpty();
        assertThat(response.getBody().getTokenType()).isEqualTo("Bearer");
        assertThat(response.getBody().getExpiresIn()).isEqualTo(300L);
    }

    @Test
    void step1_getToken_withInvalidCredentials_returnsUnauthorized() {
        String authHeader = "Basic " + Base64.getEncoder().encodeToString("wrong_user:wrong_pass".getBytes(StandardCharsets.UTF_8));
        ResponseEntity<TokenResponse> response = controller.getToken(authHeader);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void step2_transactionSync_withValidBearerToken_marksOrderPaid() {
        // Step 1: generate token
        String authHeader = "Basic " + Base64.getEncoder().encodeToString("glassstore_pos:GlassStore@VietQr2026".getBytes(StandardCharsets.UTF_8));
        TokenResponse tokenResp = vietQrService.generatePartnerToken(authHeader);
        String bearerToken = "Bearer " + tokenResp.getAccessToken();

        Order pendingOrder = Order.builder()
                .id(101L)
                .orderCode("OPT-TEST-01")
                .finalAmount(new BigDecimal("1500000"))
                .paymentMethod(PaymentMethod.VIETQR)
                .status(OrderStatus.PENDING_PAYMENT)
                .build();
        ordersDb.put("OPT-TEST-01", pendingOrder);

        // Step 2: call transaction-sync
        TransactionSyncRequest syncReq = TransactionSyncRequest.builder()
                .bankAccount("0938773607")
                .amount(new BigDecimal("1500000"))
                .transType("C")
                .content("Thanh toan don OPT-TEST-01")
                .orderId("OPT-TEST-01")
                .transactionId("TXN-VQR-8888")
                .referenceNumber("REF-MB-9999")
                .build();

        ResponseEntity<TransactionSyncResponse> syncResp = controller.transactionSync(bearerToken, syncReq);

        assertThat(syncResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(syncResp.getBody()).isNotNull();
        assertThat(syncResp.getBody().isError()).isFalse();
        assertThat(syncResp.getBody().getToastMessage()).isEqualTo("Success");
        assertThat(syncResp.getBody().getObject().getReftransactionid()).isEqualTo("TXN-VQR-8888");

        assertThat(checkoutService.callCount).isEqualTo(1);
        assertThat(checkoutService.lastPaidOrder.getOrderCode()).isEqualTo("OPT-TEST-01");
        assertThat(checkoutService.lastProvider).isEqualTo("VIETQR");
    }

    @Test
    void step2_transactionSync_idempotentWhenOrderAlreadyPaid() {
        String authHeader = "Basic " + Base64.getEncoder().encodeToString("glassstore_pos:GlassStore@VietQr2026".getBytes(StandardCharsets.UTF_8));
        TokenResponse tokenResp = vietQrService.generatePartnerToken(authHeader);
        String bearerToken = "Bearer " + tokenResp.getAccessToken();

        Order alreadyPaidOrder = Order.builder()
                .id(102L)
                .orderCode("OPT-TEST-02")
                .finalAmount(new BigDecimal("2000000"))
                .paymentMethod(PaymentMethod.VIETQR)
                .status(OrderStatus.PAID)
                .build();
        ordersDb.put("OPT-TEST-02", alreadyPaidOrder);

        TransactionSyncRequest syncReq = TransactionSyncRequest.builder()
                .bankAccount("0938773607")
                .amount(new BigDecimal("2000000"))
                .transType("C")
                .content("OPT-TEST-02")
                .orderId("OPT-TEST-02")
                .transactionId("TXN-REPLAY-1234")
                .build();

        ResponseEntity<TransactionSyncResponse> syncResp = controller.transactionSync(bearerToken, syncReq);

        assertThat(syncResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(syncResp.getBody().isError()).isFalse();
        // markPaid must NOT be called again
        assertThat(checkoutService.callCount).isEqualTo(0);
    }

    @Test
    void step4_generateVietQrPayload_producesValidEmvcoString() {
        String emvco = VietQrEmvcoUtil.generatePayload("970422", "0938773607", new BigDecimal("3500000"), "OPT-999");
        assertThat(emvco).startsWith("00020101021238");
        assertThat(emvco).contains("970422");
        assertThat(emvco).contains("0938773607");
        assertThat(emvco).contains("3500000");
        assertThat(emvco).contains("OPT-999");
        assertThat(emvco).hasSizeGreaterThan(80);
    }

    @Test
    void configInfo_returnsMerchantDetailsForVietQrRegistration() {
        ResponseEntity<?> resp = controller.getConfigInfo("localhost:8080");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
