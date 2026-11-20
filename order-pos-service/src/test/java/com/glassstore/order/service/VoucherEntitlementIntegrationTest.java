package com.glassstore.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glassstore.order.client.*;
import com.glassstore.order.config.OrderDataInitializer;
import com.glassstore.order.domain.*;
import com.glassstore.order.dto.*;
import com.glassstore.order.entity.*;
import com.glassstore.order.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:voucher-grants;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "inventory.outbox.poll-interval-ms=3600000", "inventory.reservation.initial-delay-ms=3600000",
    "inventory.reservation.poll-interval-ms=3600000", "lab.outbox.initial-delay-ms=3600000", "warranty.outbox.poll-interval-ms=3600000"})
@AutoConfigureMockMvc
class VoucherEntitlementIntegrationTest {
    @Autowired VoucherEntitlementService entitlements;
    @Autowired VoucherService voucherService;
    @Autowired VoucherRepository vouchers;
    @Autowired VoucherGrantRepository grants;
    @Autowired LoyaltyAccountRepository accounts;
    @Autowired LoyaltyEntryRepository entries;
    @Autowired VoucherRedemptionRepository redemptions;
    @Autowired OrderRepository orders;
    @Autowired OrderCheckoutService online;
    @Autowired OrderService pos;
    @Autowired OrderStatusService statuses;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MockMvc mvc;
    @MockBean PosDataService people;
    @MockBean CatalogClient catalog;
    @MockBean CatalogInventoryClient counterCatalog;
    @MockBean LabClient lab;
    @MockBean LabServiceClient labDetails;
    @MockBean WarrantyActivationService warranties;
    @MockBean LabDispatchService labDispatch;
    @MockBean OrderDataInitializer seed;
    @MockBean StockOutboxWorker worker;
    private static final AtomicLong ids = new AtomicLong(100);

    @BeforeEach void setup() throws Exception {
        when(people.actor(any(), anyBoolean())).thenReturn(new ObjectMapper().readTree("{\"id\":1,\"username\":\"cashier\"}"));
        when(people.customer(any(), anyLong())).thenReturn(new ObjectMapper().readTree("{\"id\":15}"));
        when(people.catalogItems()).thenReturn(Map.of("VOUCHER-QA", new PosDataService.CatalogItem(new BigDecimal("100000"), "QA accessory", "ACCESSORY", true)));
        when(catalog.getBySku(any())).thenAnswer(inv -> {
            var info = new CatalogContracts.VariantInfo(); info.setSku(inv.getArgument(0)); info.setName("QA accessory");
            info.setPrice(new BigDecimal("100000")); info.setStatus("ACTIVE"); info.setProductStatus("ACTIVE"); info.setStockQuantity(1000); info.setAvailableQuantity(1000);
            var result = new CatalogContracts.ApiEnvelope<CatalogContracts.VariantInfo>(); result.setData(info); return result;
        });
        when(catalog.reserve(any())).thenReturn(stockResult("ACTIVE"));
        when(catalog.commit(any())).thenReturn(stockResult("COMMITTED"));
        when(catalog.release(any())).thenReturn(stockResult("RELEASED"));
    }
    private CatalogContracts.ApiEnvelope<CatalogContracts.ReservationResult> stockResult(String state) {
        var result = new CatalogContracts.ApiEnvelope<CatalogContracts.ReservationResult>();
        var row = new CatalogContracts.ReservationResult(); row.setStatus(state); result.setData(row); return result;
    }
    private long customer(long balance) { long id = ids.incrementAndGet(); var a = new LoyaltyAccount(); a.setCustomerId(id); a.setBalance(balance); accounts.save(a); return id; }
    private Voucher voucher(String tier) {
        Voucher v = Voucher.builder().code("QA" + UUID.randomUUID().toString().replace("-", "")).name("QA assigned voucher")
            .discountType("PERCENT").discountValue(new BigDecimal("10")).minOrderAmount(BigDecimal.ZERO).requiredTier(tier).channel("ALL").active(true).build();
        return voucherService.createVoucher(v);
    }
    private OrderDtos.CheckoutRequest request(long customerId, Voucher v) {
        return OrderDtos.CheckoutRequest.builder().channel("ONLINE").orderType("ACCESSORY").customerId(customerId)
            .customerName("QA").customerPhone("0901234567").shippingAddress("QA address").paymentMethod("VIETQR")
            .voucherCode(v.getCode()).items(List.of(OrderDtos.CheckoutLine.builder().sku("VOUCHER-QA").quantity(1).expectedUnitPrice(new BigDecimal("100000")).build())).build();
    }

    @Test void onlyAssignedExactTierCanSeeAndUseVoucher() {
        long owner = customer(1000), other = customer(1000); Voucher v = voucher("SILVER");
        assertThrows(Exception.class, () -> entitlements.requireAvailable(v.getCode(), owner, SalesChannel.ONLINE));
        entitlements.issue(v.getId(), List.of(owner), "Bearer qa");
        entitlements.issue(v.getId(), List.of(owner), "Bearer qa");
        assertEquals(1, grants.findByVoucherIdOrderByGrantedAtDesc(v.getId()).size());
        assertTrue(entitlements.available(owner).stream().anyMatch(x -> x.getId().equals(v.getId())));
        assertTrue(entitlements.available(other).isEmpty());
        assertThrows(Exception.class, () -> entitlements.requireAvailable(v.getCode(), other, SalesChannel.ONLINE));
        var a = accounts.findById(owner).orElseThrow(); a.setBalance(5000); accounts.save(a);
        assertTrue(entitlements.available(owner).isEmpty());
        assertThrows(Exception.class, () -> online.checkout(request(owner, v), UUID.randomUUID().toString()));
    }

    @Test void onlineReservationCancellationPaymentAndReplayUseExactlyOneGrant() {
        long owner = customer(0); Voucher v = voucher("NONE"); entitlements.issue(v.getId(), List.of(owner), "Bearer qa");
        var pending = online.checkout(request(owner, v), UUID.randomUUID().toString()).order();
        assertEquals(0, pending.getFinalAmount().compareTo(new BigDecimal("90000")));
        assertTrue(entitlements.available(owner).isEmpty());
        assertThrows(Exception.class, () -> online.checkout(request(owner, v), UUID.randomUUID().toString()));
        statuses.transition(pending.getId(), OrderStatus.CANCELLED, "qa", "cancel unpaid");
        assertFalse(entitlements.available(owner).isEmpty());
        String key = UUID.randomUUID().toString(); var saved = online.checkout(request(owner, v), key).order();
        assertEquals(saved.getId(), online.checkout(request(owner, v), key).order().getId());
        online.markPaid(saved, "qa", "confirmed", "VIETQR"); online.markPaid(saved, "qa", "retry", "VIETQR");
        assertEquals(saved.getId(), grants.findByVoucherIdAndCustomerId(v.getId(), owner).orElseThrow().getUsedOrderId());
        assertEquals(1, vouchers.findById(v.getId()).orElseThrow().getUsedCount());
        assertEquals(1, entries.findByCustomerIdOrderByCreatedAtDesc(owner).stream().filter(e -> e.getEntryType().equals("EARN")).count());
        assertTrue(entitlements.available(owner).isEmpty());
        assertThrows(Exception.class, () -> online.checkout(request(owner, v), UUID.randomUUID().toString()));
        assertThrows(Exception.class, () -> entitlements.issue(v.getId(), List.of(owner), "Bearer qa"));
        statuses.transition(saved.getId(), OrderStatus.REFUND_PENDING, "qa", "refund");
        assertTrue(entitlements.available(owner).isEmpty());
    }

    @Test void twoConcurrentOrdersCannotReserveSameVoucher() throws Exception {
        long owner = customer(5000); Voucher v = voucher("GOLD"); entitlements.issue(v.getId(), List.of(owner), "Bearer qa");
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Boolean> attempt = () -> { start.await(); try { online.checkout(request(owner, v), UUID.randomUUID().toString()); return true; } catch (Exception expected) { return false; } };
            Future<Boolean> a = pool.submit(attempt), b = pool.submit(attempt); start.countDown();
            assertEquals(1, (a.get(30, TimeUnit.SECONDS) ? 1 : 0) + (b.get(30, TimeUnit.SECONDS) ? 1 : 0));
        } finally { pool.shutdownNow(); }
    }

    @Test void posCashConsumesAndCannotReuseAtAnotherChannel() {
        long owner = customer(0); Voucher v = voucher("NONE"); entitlements.issue(v.getId(), List.of(owner), "Bearer qa");
        var req = request(owner, v); req.setChannel("POS"); req.setPaymentMethod("CASH"); req.setCashReceived(new BigDecimal("200000")); req.setBranchCode("BRANCH_01");
        var saved = pos.createCounterSale(req, UUID.randomUUID().toString(), "Bearer qa").getBody();
        assertNotNull(saved); assertEquals(OrderStatus.PAID, saved.getStatus());
        assertEquals(saved.getId(), grants.findByVoucherIdAndCustomerId(v.getId(), owner).orElseThrow().getUsedOrderId());
        assertTrue(entitlements.available(owner).isEmpty());
        assertThrows(Exception.class, () -> online.checkout(request(owner, v), UUID.randomUUID().toString()));
    }

    @Test void expiryChannelAndRevocationAreRecheckedAtCheckout() {
        long owner = customer(10000); Voucher v = voucher("DIAMOND"); entitlements.issue(v.getId(), List.of(owner), "Bearer qa");
        entitlements.revoke(v.getId(), owner);
        assertTrue(entitlements.available(owner).isEmpty());
        assertThrows(Exception.class, () -> online.checkout(request(owner, v), UUID.randomUUID().toString()));
        entitlements.issue(v.getId(), List.of(owner), "Bearer qa");
        v.setChannel("POS"); vouchers.save(v);
        assertThrows(Exception.class, () -> online.checkout(request(owner, v), UUID.randomUUID().toString()));
        v.setChannel("ALL"); v.setValidTo(LocalDateTime.now().minusMinutes(1)); vouchers.save(v);
        assertTrue(entitlements.available(owner).isEmpty());
        assertThrows(Exception.class, () -> online.checkout(request(owner, v), UUID.randomUUID().toString()));
    }

    @Test void globalLimitCountsReservationsAcrossCustomers() {
        long first = customer(0), second = customer(0); Voucher v = voucher("NONE"); v.setUsageLimit(1); vouchers.save(v);
        entitlements.issue(v.getId(), List.of(first, second), "Bearer qa");
        online.checkout(request(first, v), UUID.randomUUID().toString());
        assertThrows(Exception.class, () -> online.checkout(request(second, v), UUID.randomUUID().toString()));
    }

    @Test void globalLastUseCannotBeReservedByTwoCustomersAtOnce() throws Exception {
        long first = customer(0), second = customer(0); Voucher v = voucher("NONE"); v.setUsageLimit(1); vouchers.save(v);
        entitlements.issue(v.getId(), List.of(first, second), "Bearer qa");
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (long id : List.of(first, second)) results.add(pool.submit(() -> {
                start.await(); try { online.checkout(request(id, v), UUID.randomUUID().toString()); return true; } catch (Exception expected) { return false; }
            }));
            start.countDown();
            assertEquals(1, (results.get(0).get(30, TimeUnit.SECONDS) ? 1 : 0) + (results.get(1).get(30, TimeUnit.SECONDS) ? 1 : 0));
        } finally { pool.shutdownNow(); }
    }

    @Test @WithMockUser(username="99", roles="CUSTOMER")
    void customersCannotManageOrReadOtherRecipients() throws Exception {
        long other = customer(0); Voucher v = voucher("NONE"); entitlements.issue(v.getId(), List.of(other), "Bearer qa");
        mvc.perform(get("/api/vouchers").param("customerId", Long.toString(other)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/api/vouchers/" + v.getId() + "/grants")).andExpect(status().isForbidden());
        mvc.perform(post("/api/vouchers/" + v.getId() + "/grants").contentType("application/json").content("{\"customerIds\":[99]}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/vouchers").contentType("application/json").content("{}" )).andExpect(status().isForbidden());
        mvc.perform(get("/api/vouchers/" + v.getCode() + "/quote").param("total","100000").param("customerId",Long.toString(other)))
            .andExpect(status().is4xxClientError());
    }
}
