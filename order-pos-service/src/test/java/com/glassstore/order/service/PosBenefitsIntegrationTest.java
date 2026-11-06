package com.glassstore.order.service;
import com.glassstore.order.domain.*;
import com.glassstore.order.entity.*;
import com.glassstore.order.repository.*;
import com.glassstore.order.client.CatalogInventoryClient;
import com.glassstore.order.config.OrderDataInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:benefits;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect","inventory.outbox.poll-interval-ms=3600000"})
@Transactional
class PosBenefitsIntegrationTest {
    @Autowired OrderService orders;
    @Autowired PromotionService promotions;
    @Autowired OrderCancellationService cancellations;
    @Autowired VoucherRepository vouchers;
    @Autowired LoyaltyAccountRepository accounts;
    @Autowired LoyaltyEntryRepository entries;
    @Autowired PaymentRepository payments;
    @Autowired OrderRepository orderRepository;
    @Autowired jakarta.persistence.EntityManager entities;
    @MockBean PosDataService data;
    @MockBean OrderDataInitializer demoSeed;
    @MockBean CatalogInventoryClient catalog;
    @MockBean StockOutboxWorker worker;
    @MockBean OrderCheckoutService teamCheckout;
    @MockBean LabDispatchService labDispatch;
    @MockBean WarrantyActivationService warrantyActivation;
    @BeforeEach void setup() throws Exception {
        when(data.actor(any(),anyBoolean())).thenReturn(new ObjectMapper().readTree("{\"id\":1,\"username\":\"qa\",\"roles\":[\"ROLE_ADMIN\"]}"));
        when(data.catalogItems()).thenReturn(Map.of("TEST-SKU", new PosDataService.CatalogItem(new BigDecimal("1000000"), "Catalog lens", "LENS", true),
                "FRAME-SKU", new PosDataService.CatalogItem(new BigDecimal("500000"), "Catalog frame", "FRAME", true)));
        Voucher v=new Voucher();v.setCode("QA10");v.setName("QA voucher");v.setType("PERCENT");v.setAmount(new BigDecimal("10"));v.setMinimumAmount(new BigDecimal("500000"));v.setMaximumDiscount(new BigDecimal("100000"));v.setUsageLimit(1);v.setExpiresAt(LocalDateTime.now().plusDays(1));vouchers.save(v);
        LoyaltyAccount account=new LoyaltyAccount();account.setCustomerId(10L);account.setBalance(100);accounts.save(account);
    }
    private Order order() {
        return Order.builder().channel(SalesChannel.POS).orderType(OrderType.ACCESSORY).branchCode("BRANCH_01").customerId(10L).customerName("QA").customerPhone("").paymentMethod(PaymentMethod.CASH)
            .items(new ArrayList<>(List.of(OrderItem.builder().skuId("TEST-SKU").productName("Test glasses").quantity(1).unitPrice(new BigDecimal("1000000")).build()))).build();
    }
    @Test void quoteDoesNotSpendAndCheckoutAndCancellationAreIdempotent() {
        Order request=order();request.setVoucherCode("QA10");request.setPointsToUse(50L);request.setCashReceived(new BigDecimal("2000000"));
        PromotionService.Quote quote=promotions.calculate(request,"Bearer test",false);
        assertEquals(0,quote.finalAmount().compareTo(new BigDecimal("918000")));
        assertEquals(100,accounts.findById(10L).orElseThrow().getBalance());assertEquals(0,vouchers.findByCode("QA10").orElseThrow().getUsedCount());
        Order saved=orders.create(request,"QA-payment-1","Bearer test").getBody();
        assertEquals(50,accounts.findById(10L).orElseThrow().getBalance());assertEquals(1,vouchers.findByCode("QA10").orElseThrow().getUsedCount());
        Order replay=order();replay.setVoucherCode("QA10");replay.setPointsToUse(50L);replay.setCashReceived(new BigDecimal("2000000"));
        assertEquals(saved.getId(),orders.create(replay,"QA-payment-1","Bearer test").getBody().getId());
        assertEquals(1,payments.findByOrderId(saved.getId()).size());assertEquals(50,accounts.findById(10L).orElseThrow().getBalance());
        cancellations.cancel(saved.getId(),new OrderCancellationService.Request("Khách đổi ý",false),"Bearer test");
        cancellations.complete(saved.getId());cancellations.complete(saved.getId());
        cancellations.cancel(saved.getId(),new OrderCancellationService.Request("Lặp yêu cầu",false),"Bearer test");
        assertEquals(100,accounts.findById(10L).orElseThrow().getBalance());assertEquals(0,vouchers.findByCode("QA10").orElseThrow().getUsedCount());
        assertEquals(2,entries.findByCustomerIdOrderByCreatedAtDesc(10L).size());
        assertEquals(PaymentStatus.REFUND_PENDING,payments.findByOrderId(saved.getId()).get(0).getStatus());
        cancellations.confirmRefund(saved.getId(),"Bearer test");assertEquals(PaymentStatus.REFUNDED,payments.findByOrderId(saved.getId()).get(0).getStatus());
    }
    @Test void rejectsGuestPointsExpiredVoucherAndInsufficientCashWithoutSpending() {
        Order guest=order();guest.setCustomerId(null);guest.setPointsToUse(1L);assertThrows(org.springframework.web.server.ResponseStatusException.class,()->promotions.calculate(guest,"Bearer test",false));
        Voucher expired=vouchers.findByCode("QA10").orElseThrow();expired.setExpiresAt(LocalDateTime.now().minusDays(1));vouchers.save(expired);
        Order request=order();request.setVoucherCode("QA10");assertThrows(org.springframework.web.server.ResponseStatusException.class,()->promotions.calculate(request,"Bearer test",false));
        Order poor=order();poor.setCashReceived(BigDecimal.ONE);assertThrows(org.springframework.web.server.ResponseStatusException.class,()->orders.create(poor,"QA-poor","Bearer test"));
        assertEquals(100,accounts.findById(10L).orElseThrow().getBalance());assertEquals(0,orderRepository.count());
    }

    @Test void sharedCheckoutDtoPreservesPosBenefitsPrescriptionAndPaymentHooks() throws Exception {
        var request = new ObjectMapper().readValue("""
                {"channel":"POS","customerId":10,"customerName":"QA","customerPhone":"",
                 "paymentMethod":"CASH","orderType":"LENS_ONLY","branchCode":"BRANCH_01",
                 "prescriptionId":7,"voucherCode":"QA10","pointsToUse":50,"cashReceived":2000000,
                 "items":[{"skuId":"TEST-SKU","productName":"Test lens","quantity":1,"prescriptionId":7,"expectedUnitPrice":1000000}]}
                """, com.glassstore.order.dto.OrderDtos.CheckoutRequest.class);
        Order saved = orders.createCounterSale(request,"QA-shared-dto","Bearer test").getBody();
        orderRepository.flush();
        assertEquals(OrderStatus.PAID, saved.getStatus());
        assertEquals(SalesChannel.POS, saved.getChannel());
        assertEquals(7L, saved.getPrescriptionId());
        assertEquals(7L, saved.getItems().get(0).getPrescriptionId());
        assertEquals(50L, saved.getRedeemedPoints());
        assertEquals(0, saved.getFinalAmount().compareTo(new BigDecimal("918000")));
        assertEquals("QA-shared-dto", orderRepository.findByIdempotencyKey("QA-shared-dto").orElseThrow().getIdempotencyKey());
        verify(labDispatch).enqueue(saved);
        verify(warrantyActivation).enqueueForPaidOrder(saved);
        orders.createCounterSale(request,"QA-shared-dto","Bearer test");
        verifyNoMoreInteractions(labDispatch, warrantyActivation);
        assertTrue(new ObjectMapper().findAndRegisterModules().writeValueAsString(saved).contains("\"prescriptionId\":7"));
        entities.flush(); entities.clear();
        Order readBack = orderRepository.findById(saved.getId()).orElseThrow();
        assertEquals(10L, readBack.getCustomerId());
        assertEquals(7L, readBack.getPrescriptionId());
        assertEquals(7L, readBack.getItems().get(0).getPrescriptionId());
    }

    @Test void rejectsAlteredPriceMissingPriceUnknownSkuAndWrongPrescriptionWithoutWrites() {
        Order forged = order(); forged.getItems().get(0).setUnitPrice(BigDecimal.ONE);
        assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> orders.create(forged,"bad-price","Bearer test")).getStatusCode().value());
        Order missingPrice = order(); missingPrice.getItems().get(0).setUnitPrice(null);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> orders.create(missingPrice,"missing-price","Bearer test"));
        Order sku = order(); sku.getItems().get(0).setSkuId("NOT-IN-CATALOG");
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> orders.create(sku,"bad-sku","Bearer test"));
        doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Toa không thuộc khách"))
                .when(data).requirePrescription(7L,10L,"Bearer test");
        Order wrongRx = order(); wrongRx.getItems().get(0).setPrescriptionId(7L);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> orders.create(wrongRx,"bad-rx","Bearer test"));
        when(data.catalogItems()).thenReturn(Map.of("TEST-SKU",new PosDataService.CatalogItem(new BigDecimal("1000000"),"Inactive lens","LENS",false)));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> orders.create(order(),"inactive-sku","Bearer test"));
        assertEquals(0, orderRepository.count()); assertEquals(0, payments.count());
        assertEquals(100,accounts.findById(10L).orElseThrow().getBalance());
        assertEquals(0,vouchers.findByCode("QA10").orElseThrow().getUsedCount());
        verifyNoInteractions(catalog, teamCheckout, warrantyActivation);
    }

    @Test void rejectsFabricationOnFrameAndMixedPrescriptions() {
        Order frame = order(); frame.getItems().get(0).setSkuId("FRAME-SKU"); frame.getItems().get(0).setPrescriptionId(7L);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> promotions.calculate(frame,"Bearer test",false));
        Order mixed = order(); mixed.setPrescriptionId(8L); mixed.getItems().get(0).setPrescriptionId(7L);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> promotions.calculate(mixed,"Bearer test",false));
        assertEquals(0, orderRepository.count());
    }

    @Test void checkoutRevalidatesVoucherAndIgnoresForgedTotals() {
        Order request = order(); request.setVoucherCode("QA10");
        promotions.calculate(request,"Bearer test",false);
        Voucher voucher = vouchers.findByCode("QA10").orElseThrow(); voucher.setActive(false); vouchers.save(voucher);
        request.setCashReceived(new BigDecimal("2000000"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> orders.create(request,"voucher-changed","Bearer test"));
        assertEquals(0, orderRepository.count()); assertEquals(0,payments.count());
        Order valid = order(); valid.setTotalAmount(BigDecimal.ONE); valid.setFinalAmount(BigDecimal.ONE); valid.setDiscountAmount(BigDecimal.ONE);
        valid.setCashReceived(new BigDecimal("2000000"));
        Order saved = orders.create(valid,"server-totals","Bearer test").getBody();
        assertEquals(0, saved.getFinalAmount().compareTo(new BigDecimal("1080000")));
        assertEquals("Catalog lens",saved.getItems().get(0).getProductName());
    }
}
