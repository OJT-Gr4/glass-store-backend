package com.glassstore.order.controller;

import com.glassstore.order.config.OrderDataInitializer;
import com.glassstore.order.domain.*;
import com.glassstore.order.entity.Order;
import com.glassstore.order.repository.*;
import com.glassstore.common.security.ServiceTokenIssuer;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:order-access;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "jwt.secret=day3-test-only-key-012345678901234567890123456789",
    "inventory.outbox.poll-interval-ms=3600000","warranty.outbox.poll-interval-ms=3600000","lab.outbox.initial-delay-ms=3600000"})
@AutoConfigureMockMvc
class OrderAccessSecurityTest {
    static final String KEY="day3-test-only-key-012345678901234567890123456789";
    @Autowired MockMvc mvc;
    @Autowired OrderRepository orders;
    @Autowired PaymentRepository payments;
    @Autowired OrderStatusHistoryRepository history;
    @MockBean OrderDataInitializer demoSeed;
    @MockBean com.glassstore.order.client.LabClient lab;
    Order mine,other;
    @BeforeEach void data() {
        history.deleteAll();payments.deleteAll();orders.deleteAll();
        mine=save(10L);other=save(20L);
    }
    private Order save(Long customer) {
        return orders.saveAndFlush(Order.builder().orderCode("QA-ACCESS-"+System.nanoTime()).customerId(customer)
            .customerName("QA").customerPhone("0901234567").channel(SalesChannel.ONLINE).orderType(OrderType.FRAME_ONLY)
            .status(OrderStatus.PAID).totalAmount(BigDecimal.TEN).finalAmount(BigDecimal.TEN).build());
    }
    static String token(String role,String type,Instant expires,String key) {
        var encoder=new NimbusJwtEncoder(new ImmutableSecret<>(key.getBytes(StandardCharsets.UTF_8)));
        var claims=JwtClaimsSet.builder().subject("10").issuedAt(Instant.now().minusSeconds(300)).expiresAt(expires)
            .claim("userId",10L).claim("username","qa-customer").claim("roles",List.of("ROLE_"+role)).claim("type",type).build();
        return "Bearer "+encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
    }
    String customer() {return token("CUSTOMER","ACCESS",Instant.now().plusSeconds(300),KEY);}
    @Test void sensitiveEndpointsRequireAuthentication() throws Exception {
        for(String path:List.of("/api/orders","/api/orders/"+mine.getId(),"/internal/warranty-items?phone=0901234567"))
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }
    @Test void expiredRefreshAndTamperedTokensAreRejected() throws Exception {
        for(String value:List.of(token("CUSTOMER","ACCESS",Instant.now().minusSeconds(120),KEY),
                token("CUSTOMER","REFRESH",Instant.now().plusSeconds(300),KEY),
                token("CUSTOMER","ACCESS",Instant.now().plusSeconds(300),KEY+"wrong")))
            mvc.perform(get("/api/orders").header("Authorization",value)).andExpect(status().isUnauthorized());
    }
    @Test void customerCanReadOnlyOwnOrdersRegardlessOfSpoofedHeaders() throws Exception {
        mvc.perform(get("/api/orders/"+mine.getId()).header("Authorization",customer())).andExpect(status().isOk());
        mvc.perform(get("/api/orders/"+other.getId()).header("Authorization",customer()).header("X-Customer-Id",20))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/orders/code/"+other.getOrderCode()).header("Authorization",customer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/orders/customer/20").header("Authorization",customer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/orders/"+other.getId()+"/payments").header("Authorization",customer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/payments/order/"+other.getId()).header("Authorization",customer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/orders").header("Authorization",customer())).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].customerId").value(10));
    }
    @Test void cartIdentityComesFromToken() throws Exception {
        mvc.perform(get("/api/cart").header("Authorization",customer()).header("X-Customer-Id",20))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.customerId").value(10));
    }
    @Test void customerCannotCheckoutUsingAnotherCustomersPrescription() throws Exception {
        var response=new com.glassstore.order.client.CatalogContracts.ApiEnvelope<java.util.Map<String,Object>>();
        response.setData(java.util.Map.of("id",999L,"customerId",20L));
        org.mockito.Mockito.when(lab.getPrescription(999L)).thenReturn(response);
        mvc.perform(post("/api/orders").header("Authorization",customer()).header("Idempotency-Key","qa-foreign-rx")
            .contentType("application/json").content("{\"channel\":\"ONLINE\",\"customerId\":20,\"customerName\":\"QA\",\"customerPhone\":\"0901234567\",\"paymentMethod\":\"VIETQR\",\"orderType\":\"COMPLETE_GLASSES\",\"prescriptionId\":999,\"items\":[{\"sku\":\"QA-SKU\",\"quantity\":1}]}"))
            .andExpect(status().isForbidden());
    }
    @Test void customerAndLabCannotUseOmsMutation() throws Exception {
        for(String role:List.of("CUSTOMER","LAB_TECH","OPTOMETRIST"))
            mvc.perform(patch("/api/orders/"+mine.getId()+"/status")
                .header("Authorization",token(role,"ACCESS",Instant.now().plusSeconds(300),KEY))
                .contentType("application/json").content("{\"status\":\"PREPARING\"}"))
                .andExpect(status().isForbidden());
    }
    @Test void internalContractsRequireAServiceToken() throws Exception {
        mvc.perform(get("/internal/warranty-items?phone=0901234567").header("Authorization",customer())).andExpect(status().isForbidden());
        mvc.perform(get("/internal/warranty-items?phone=0901234567")
            .header("Authorization","Bearer "+ServiceTokenIssuer.issue(KEY,"eyewear-lab-service"))).andExpect(status().isOk());
        mvc.perform(get("/api/orders").header("Authorization","Bearer "+ServiceTokenIssuer.issue(KEY,"eyewear-lab-service")))
            .andExpect(status().isForbidden());
    }
}
