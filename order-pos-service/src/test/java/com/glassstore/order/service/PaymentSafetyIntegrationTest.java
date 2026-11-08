package com.glassstore.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glassstore.order.client.*;
import com.glassstore.order.config.OrderDataInitializer;
import com.glassstore.order.domain.*;
import com.glassstore.order.dto.*;
import com.glassstore.order.entity.*;
import com.glassstore.order.payment.*;
import com.glassstore.order.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:payment-safety;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "inventory.outbox.poll-interval-ms=3600000","inventory.reservation.initial-delay-ms=3600000","inventory.reservation.poll-interval-ms=3600000",
    "lab.outbox.initial-delay-ms=3600000","warranty.outbox.poll-interval-ms=3600000"})
class PaymentSafetyIntegrationTest {
    @Autowired OrderService pos;
    @Autowired OrderCheckoutService online;
    @Autowired PaymentService paymentService;
    @Autowired OrderRepository orders;
    @Autowired PaymentRepository payments;
    @Autowired StockDeductionOutboxRepository stock;
    @Autowired StockDeductionService stockSync;
    @Autowired OrderInventoryTaskRepository tasks;
    @Autowired OrderInventoryWorker inventoryWorker;
    @Autowired OrderInventoryService inventory;
    @Autowired CheckoutIdempotencyService idempotency;
    @Autowired com.glassstore.order.controller.OrderCancellationController cancellation;
    @Autowired LabDispatchOutboxRepository labTasks;
    @MockBean PosDataService data;
    @MockBean CatalogInventoryClient counterCatalog;
    @MockBean CatalogClient catalog;
    @MockBean LabClient lab;
    @MockBean LabServiceClient labDetails;
    @MockBean WarrantyActivationService warranties;
    @MockBean OrderDataInitializer seed;
    @MockBean PaymentGateway gateway;

    @BeforeEach void setup() throws Exception {
        when(data.actor(any(),anyBoolean())).thenReturn(new ObjectMapper().readTree("{\"id\":1,\"username\":\"cashier\"}"));
        when(data.catalogItems()).thenReturn(Map.of("FRM-QA",new PosDataService.CatalogItem(new BigDecimal("1000"),"QA frame","FRAME",true)));
        when(counterCatalog.deductPaidOrder(any())).thenAnswer(inv -> new CatalogStockDeductionResponse(((CatalogStockDeductionRequest)inv.getArgument(0)).orderId(),"BRANCH_01",false));
        when(catalog.getBySku(any())).thenAnswer(inv -> {
            var item=new CatalogContracts.VariantInfo();item.setSku(inv.getArgument(0));item.setName("QA frame");item.setPrice(new BigDecimal("1000"));item.setStatus("ACTIVE");item.setProductStatus("ACTIVE");item.setStockQuantity(100);item.setAvailableQuantity(100);
            var result=new CatalogContracts.ApiEnvelope<CatalogContracts.VariantInfo>();result.setData(item);return result;
        });
        when(catalog.reserve(any())).thenReturn(result("ACTIVE"));
        when(catalog.commit(any())).thenReturn(result("COMMITTED"));
        when(catalog.release(any())).thenReturn(result("RELEASED"));
    }
    private CatalogContracts.ApiEnvelope<CatalogContracts.ReservationResult> result(String status) {
        var result=new CatalogContracts.ApiEnvelope<CatalogContracts.ReservationResult>();var value=new CatalogContracts.ReservationResult();value.setStatus(status);result.setData(value);return result;
    }
    private OrderDtos.CheckoutRequest request(String channel,String method) {
        return OrderDtos.CheckoutRequest.builder().channel(channel).orderType("FRAME_ONLY").customerId(10L).customerName("QA")
            .customerPhone("0901234567").branchCode("BRANCH_01").paymentMethod(method).cashReceived(new BigDecimal("2000"))
            .items(List.of(OrderDtos.CheckoutLine.builder().sku("FRM-QA").quantity(1).expectedUnitPrice(new BigDecimal("1000")).build())).build();
    }
    private String key() { return UUID.randomUUID().toString(); }

    @Test void fingerprintNormalizesNumericScaleButTracksPaymentInputs() {
        var original=request("POS","CASH");var equivalent=request("POS","CASH");
        equivalent.getItems().get(0).setExpectedUnitPrice(new BigDecimal("1000.00"));
        equivalent.getItems().get(0).setProductName("Server-owned display label");
        assertEquals(idempotency.fingerprint(original),idempotency.fingerprint(equivalent));
        equivalent.setCashReceived(new BigDecimal("3000"));
        assertNotEquals(idempotency.fingerprint(original),idempotency.fingerprint(equivalent));
        assertThrows(com.glassstore.common.exception.ApiException.class,()->pos.createCounterSale(original,null,"Bearer qa"));
    }

    @Test void simultaneousVerifiedCallbacksAndFailureReplayDoNotDuplicatePaymentOrCommit() throws Exception {
        var order=online.checkout(request("ONLINE","VNPAY"),key()).order();String txn=key();
        when(gateway.verifyCallback("failed","signed")).thenReturn(new PaymentGateway.VerificationResult(true,"QA_GATEWAY",txn,order.getOrderCode(),order.getFinalAmount(),false,"declined"));
        var failed=paymentService.handleCallback("failed","signed").payment();
        assertEquals(failed.getId(),paymentService.handleCallback("failed","signed").payment().getId());
        assertEquals(OrderStatus.PENDING_PAYMENT,orders.findById(order.getId()).orElseThrow().getStatus());
        when(gateway.verifyCallback("paid","signed")).thenReturn(new PaymentGateway.VerificationResult(true,"QA_GATEWAY",txn,order.getOrderCode(),order.getFinalAmount(),true,"verified"));
        ExecutorService pool=Executors.newFixedThreadPool(4);CountDownLatch start=new CountDownLatch(1);
        try {
            var futures=new ArrayList<Future<Long>>();
            for(int i=0;i<4;i++)futures.add(pool.submit(()->{start.await();return paymentService.handleCallback("paid","signed").payment().getId();}));
            start.countDown();for(var future:futures)assertEquals(failed.getId(),future.get(30,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
        assertEquals(1,payments.findByOrderId(order.getId()).stream().filter(p->p.getStatus()==PaymentStatus.SUCCESS).count());
        assertEquals("GATEWAY_VERIFIED",payments.findById(failed.getId()).orElseThrow().getConfirmationMode());
        assertEquals("SYNCED",tasks.findById(order.getId()).orElseThrow().getStatus());verify(catalog,times(1)).commit(any());
        when(gateway.verifyCallback("second-charge","signed")).thenReturn(new PaymentGateway.VerificationResult(true,"QA_GATEWAY",key(),order.getOrderCode(),order.getFinalAmount(),true,"verified"));
        assertThrows(com.glassstore.common.exception.ApiException.class,()->paymentService.handleCallback("second-charge","signed"));
    }

    @Test void sameKeySameContentReturnsOriginalAndDifferentContentIsRejected() {
        String key=key();long before=orders.count(),money=payments.count(),movements=stock.count();
        Order saved=pos.createCounterSale(request("POS","CASH"),key,"Bearer qa").getBody();
        assertEquals(saved.getId(),pos.createCounterSale(request("POS","CASH"),key,"Bearer qa").getBody().getId());
        var changed=request("POS","CASH");changed.getItems().get(0).setQuantity(2);
        assertEquals(com.glassstore.common.exception.ErrorCode.IDEMPOTENCY_CONFLICT,
                assertThrows(com.glassstore.common.exception.ApiException.class,()->pos.createCounterSale(changed,key,"Bearer qa")).getErrorCode());
        assertEquals(before+1,orders.count());assertEquals(money+1,payments.count());assertEquals(movements+1,stock.count());
        var payment=payments.findByOrderId(saved.getId()).get(0);
        assertEquals("CASH_RECEIVED",payment.getConfirmationMode());assertEquals("cashier",payment.getConfirmedBy());assertNotNull(payment.getConfirmedAt());
        verify(counterCatalog,times(1)).deductPaidOrder(any());
    }

    @Test void concurrentDoubleClicksCreateOneOrderPaymentAndStockRequest() throws Exception {
        String key=key();long before=orders.count(),money=payments.count(),movements=stock.count();
        ExecutorService pool=Executors.newFixedThreadPool(6);CountDownLatch start=new CountDownLatch(1);
        try {
            var futures=new ArrayList<Future<Long>>();
            for(int i=0;i<6;i++) futures.add(pool.submit(()->{start.await();return pos.createCounterSale(request("POS","CASH"),key,"Bearer qa").getBody().getId();}));
            start.countDown();Set<Long> ids=new HashSet<>();for(var future:futures)ids.add(future.get(30,TimeUnit.SECONDS));
            assertEquals(1,ids.size());assertEquals(before+1,orders.count());assertEquals(money+1,payments.count());assertEquals(movements+1,stock.count());
            verify(counterCatalog,times(1)).deductPaidOrder(any());
        } finally {pool.shutdownNow();}
    }

    @Test void manualTransferRequiresExplicitAcknowledgementAndOnlineStaysPending() {
        var transfer=request("POS","VIETQR");long before=orders.count();
        assertThrows(ResponseStatusException.class,()->pos.createCounterSale(transfer,key(),"Bearer qa"));assertEquals(before,orders.count());
        transfer.setManualPaymentConfirmed(true);transfer.setPaymentReference("BANK-QA");
        var paid=pos.createCounterSale(transfer,key(),"Bearer qa").getBody();
        assertEquals(OrderStatus.PAID,paid.getStatus());var audit=payments.findByOrderId(paid.getId()).get(0);
        assertEquals("MANUAL_TRANSFER",audit.getConfirmationMode());assertEquals("BANK-QA",audit.getPaymentReference());
        String key=key();var pending=online.checkout(request("ONLINE","VIETQR"),key).order();
        assertEquals(OrderStatus.PENDING_PAYMENT,pending.getStatus());assertEquals(PaymentStatus.PENDING,payments.findByOrderId(pending.getId()).get(0).getStatus());
        assertEquals(pending.getId(),online.checkout(request("ONLINE","VIETQR"),key).order().getId());
        verify(catalog,never()).commit(any());
        assertThrows(com.glassstore.common.exception.ApiException.class,()->new UnconfiguredPaymentGateway().initiate(new PaymentGateway.InitiationRequest("QA",BigDecimal.TEN,"QA")));
    }

    @Test void stockFailureAfterPaymentKeepsMoneyAndRetriesTheOriginalTransaction() {
        Set<Long> physicalMovements=ConcurrentHashMap.newKeySet();AtomicInteger calls=new AtomicInteger();
        doAnswer(inv->{CatalogStockDeductionRequest req=inv.getArgument(0);physicalMovements.add(req.orderId());if(calls.getAndIncrement()==0)throw feign.FeignException.errorStatus("stock",feign.Response.builder().status(503).reason("Lost acknowledgement").request(feign.Request.create(feign.Request.HttpMethod.POST,"http://catalog",Map.of(),null,java.nio.charset.StandardCharsets.UTF_8,null)).build());return new CatalogStockDeductionResponse(req.orderId(),"BRANCH_01",true);}).when(counterCatalog).deductPaidOrder(any());
        var saved=pos.createCounterSale(request("POS","CASH"),key(),"Bearer qa").getBody();
        assertEquals(OrderStatus.PAID,orders.findById(saved.getId()).orElseThrow().getStatus());assertEquals(PaymentStatus.SUCCESS,payments.findByOrderId(saved.getId()).get(0).getStatus());
        assertEquals("PENDING",stock.findByOrderId(saved.getId()).orElseThrow().getStatus());
        stockSync.retry(saved.getId(),null);assertEquals("SYNCED",stock.findByOrderId(saved.getId()).orElseThrow().getStatus());assertEquals(1,physicalMovements.size());
    }

    @Test void verifiedPaymentPersistsWhenLabIsUnavailable() {
        var prescription = new HashMap<String, Object>();
        prescription.put("id", 7L); prescription.put("customerId", 10L); prescription.put("version", 0L);
        for (String field : List.of("odSph", "odCyl", "odAxis", "odAdd", "osSph", "osCyl", "osAxis", "osAdd"))
            prescription.put(field, 0);
        prescription.put("pd", 63);
        when(labDetails.getPrescriptionById(7L))
                .thenReturn(org.springframework.http.ResponseEntity.ok(Map.of("data", prescription)));
        var request=request("ONLINE","VNPAY");request.setOrderType("LENS_ONLY");request.setPrescriptionId(7L);
        request.getItems().get(0).setSku("LNS-QA");request.getItems().get(0).setPrescriptionId(7L);
        var order=online.checkout(request,key()).order();
        when(lab.createFromOrder(any())).thenThrow(new IllegalStateException("Lab unavailable"));
        when(gateway.verifyCallback("lab-payment","signed")).thenReturn(new PaymentGateway.VerificationResult(true,"QA_GATEWAY",key(),order.getOrderCode(),order.getFinalAmount(),true,"verified"));
        paymentService.handleCallback("lab-payment","signed");
        assertEquals(OrderStatus.PAID,orders.findById(order.getId()).orElseThrow().getStatus());
        assertEquals(1,payments.findByOrderId(order.getId()).stream().filter(p->p.getStatus()==PaymentStatus.SUCCESS).count());
        assertEquals("PENDING",labTasks.findByOrderId(order.getId()).orElseThrow().getStatus());
        paymentService.handleCallback("lab-payment","signed");verify(lab,times(1)).createFromOrder(any());
    }

    @Test void onlineReservationFailureIsDurableAndExhaustionNeedsReconciliation() {
        when(catalog.reserve(any())).thenThrow(new IllegalStateException("Catalog unavailable"));
        String key=key();var saved=online.checkout(request("ONLINE","VIETQR"),key).order();
        assertEquals("PENDING",tasks.findById(saved.getId()).orElseThrow().getStatus());
        assertEquals(saved.getId(),online.checkout(request("ONLINE","VIETQR"),key).order().getId());
        for(int i=0;i<10;i++)inventoryWorker.process(saved.getId());
        var task=tasks.findById(saved.getId()).orElseThrow();assertEquals("RECONCILIATION_REQUIRED",task.getStatus());assertTrue(task.getLastError().contains("unavailable"));
        assertEquals(OrderStatus.PENDING_PAYMENT,orders.findById(saved.getId()).orElseThrow().getStatus());assertEquals(PaymentStatus.PENDING,payments.findByOrderId(saved.getId()).get(0).getStatus());
    }

    @Test void confirmedCashSurvivesStockCommitFailureAndCancellationReleasesAnUnpaidHold() {
        var unpaid=online.checkout(request("ONLINE","CASH"),key()).order();
        inventory.release(unpaid,"QA cancel");assertEquals("SYNCED",tasks.findById(unpaid.getId()).orElseThrow().getStatus());verify(catalog,times(1)).release(any());
        var order=online.checkout(request("ONLINE","CASH"),key()).order();
        when(catalog.commit(any())).thenThrow(new IllegalStateException("Commit unavailable"));
        online.settleCash(order.getId(),"cashier");
        assertEquals(PaymentStatus.SUCCESS,payments.findByOrderId(order.getId()).get(0).getStatus());assertEquals("PENDING",tasks.findById(order.getId()).orElseThrow().getStatus());
        online.settleCash(order.getId(),"cashier");assertEquals(1,payments.findByOrderId(order.getId()).size());
        inventory.release(order,"Need refund");assertEquals("RECONCILIATION_REQUIRED",tasks.findById(order.getId()).orElseThrow().getStatus());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(username="1",roles="ADMIN")
    void cancellationPersistsItsStockTaskTogetherWithOrderAndPayment() {
        var unpaid=online.checkout(request("ONLINE","CASH"),key()).order();
        cancellation.cancel(unpaid.getId(),null,null,null);
        assertEquals(OrderStatus.CANCELLED,orders.findById(unpaid.getId()).orElseThrow().getStatus());
        assertEquals("RELEASE",tasks.findById(unpaid.getId()).orElseThrow().getOperation());
        assertEquals("SYNCED",tasks.findById(unpaid.getId()).orElseThrow().getStatus());
        var paid=online.checkout(request("ONLINE","CASH"),key()).order();online.settleCash(paid.getId(),"cashier");
        cancellation.cancel(paid.getId(),null,null,null);
        assertEquals(OrderStatus.REFUND_PENDING,orders.findById(paid.getId()).orElseThrow().getStatus());
        assertEquals(PaymentStatus.REFUND_PENDING,payments.findByOrderId(paid.getId()).get(0).getStatus());
        assertEquals("RECONCILIATION_REQUIRED",tasks.findById(paid.getId()).orElseThrow().getStatus());
    }
}
