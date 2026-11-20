package com.glassstore.order.service;

import com.glassstore.order.entity.*;
import com.glassstore.order.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties={"spring.datasource.url=jdbc:h2:mem:lifecycle;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"}, showSql=false)
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(replace=org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
@Import(LoyaltyLifecycleService.class)
class LoyaltyLifecycleIntegrationTest {
    @Autowired LoyaltyLifecycleService service;
    @Autowired LoyaltyAccountRepository accounts;
    @Autowired LoyaltyEntryRepository entries;
    private final LocalDateTime start=LocalDateTime.of(2026,1,20,10,0);
    private LoyaltyAccount create(long balance) {
        var a=new LoyaltyAccount();a.setCustomerId(991L);a.setBalance(balance);accounts.saveAndFlush(a);
        service.processAt(991L,start);return accounts.findById(991L).orElseThrow();
    }
    @Test void existingBalanceGetsSixMonthsGraceAndTierProtection() {
        var a=create(6200);assertEquals(6200,a.getBalance());assertEquals(start.plusMonths(6),a.getNextDecayAt());
        assertEquals("GOLD",a.getTierCode());assertEquals(6200,a.getTierPoints());assertEquals(start.plusMonths(12),a.getTierProtectedUntil());
        service.processAt(991L,start.plusMonths(5));assertEquals(6200,a.getBalance());assertEquals(0,entries.count());
    }
    @Test void warningAndMonthlyDeductionAreIdempotentAndRoundUp() {
        var a=create(6200);var due=a.getNextDecayAt();
        service.processAt(991L,due.minusDays(15));service.processAt(991L,due.minusDays(15));assertEquals(1,entries.count());
        service.processAt(991L,due);service.processAt(991L,due);assertEquals(5890,a.getBalance());assertEquals(2,entries.count());
        assertEquals("GOLD",a.getTierCode());assertEquals(6200,a.getTierPoints());
        service.processAt(991L,due.plusMonths(1).minusDays(15));service.processAt(991L,due.plusMonths(1));
        assertEquals(5595,a.getBalance());assertEquals(due.plusMonths(2),a.getNextDecayAt());
    }
    @Test void missedWarningGivesFifteenDaysWithoutRetroactivePenalty() {
        var a=create(100);var late=a.getNextDecayAt().plusMonths(2);
        service.processAt(991L,late);assertEquals(100,a.getBalance());assertEquals(late.plusDays(15),a.getNextDecayAt());
        service.processAt(991L,late.plusDays(15));assertEquals(95,a.getBalance());
    }
    @Test void zeroAndOnePointNeverGoNegative() {
        var a=create(1);var due=a.getNextDecayAt();service.processAt(991L,due.minusDays(15));service.processAt(991L,due);
        assertEquals(0,a.getBalance());service.processAt(991L,due.plusMonths(10));assertEquals(0,a.getBalance());
        assertEquals(0,LoyaltyLifecycleService.decay(0));assertEquals(1,LoyaltyLifecycleService.decay(1));
    }
    @Test void completedServiceExtendsProtectionOnceAndOldEventsNeverShortenIt() {
        var a=create(100);var recent=LoyaltyLifecycleService.now().minusDays(1);
        service.completedService("REPAIR:1",991L,recent);service.completedService("REPAIR:1",991L,recent.plusDays(1));
        assertEquals(recent.plusMonths(6),a.getNextDecayAt());assertEquals(1,entries.count());
        service.completedService("REPAIR:OLD",991L,start.minusDays(1));assertEquals(recent.plusMonths(6),a.getNextDecayAt());
        service.completedService("REPAIR:BAD",991L,null);service.completedService("REPAIR:FUTURE",991L,recent.plusYears(1));assertEquals(2,entries.count());
    }
    @Test void paidPurchaseAndRefundActivityAreIdempotent() {
        var a=create(6200);var order=Order.builder().id(123L).customerId(991L).finalAmount(new BigDecimal("500")).build();
        service.purchase(order);service.purchase(order);assertEquals(1,entries.count());assertTrue(a.getNextDecayAt().isAfter(start.plusMonths(6)));
        service.refund(order);var due=a.getNextDecayAt();service.refund(order);
        assertEquals(due,a.getNextDecayAt());assertNull(a.getLastActivityAt());assertEquals(2,entries.count());
        assertTrue(entries.findByEventKey("PURCHASE:123").orElseThrow().isVoided());
    }
    @Test void redemptionDoesNotDowngradeAndTierReevaluatesAfterTwelveMonths() {
        var a=create(6200);a.setBalance(100);accounts.saveAndFlush(a);
        service.processAt(991L,start.plusMonths(11));assertEquals("GOLD",a.getTierCode());assertEquals(6200,a.getTierPoints());
        service.processAt(991L,start.plusMonths(12).plusDays(1));assertEquals("MEMBER",a.getTierCode());assertEquals(0,a.getTierPoints());
    }
    @Test void purchasePointsUpgradeTierButRefundedPointsDoNotCount() {
        var a=create(900);var earn=new LoyaltyEntry();earn.setCustomerId(991L);earn.setOrderId(45L);earn.setEntryType("EARN");earn.setPoints(200);earn.setCreatedAt(start.plusDays(1));entries.save(earn);
        service.processAt(991L,start.plusDays(2));assertEquals("SILVER",a.getTierCode());assertEquals(1100,a.getTierPoints());
        var reversal=new LoyaltyEntry();reversal.setCustomerId(991L);reversal.setOrderId(45L);reversal.setEntryType("EARN_REVERSAL");reversal.setPoints(-200);entries.save(reversal);
        service.refund(Order.builder().id(45L).customerId(991L).build());assertEquals("MEMBER",a.getTierCode());assertEquals(900,a.getTierPoints());
    }
    @Test void accountCanBeCreatedAtomically() {
        assertEquals(0,service.current(1234L).getBalance());assertEquals(0,service.current(1234L).getBalance());assertEquals(1,accounts.count());
    }
    @Test @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentJobExecutionsDeductOnlyOnce() throws Exception {
        long id=778899L;
        var a=new LoyaltyAccount();a.setCustomerId(id);a.setBalance(6200);accounts.saveAndFlush(a);
        service.processAt(id,start);var due=accounts.findById(id).orElseThrow().getNextDecayAt();service.processAt(id,due.minusDays(15));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var gate=new java.util.concurrent.CountDownLatch(1);
        try {
            var first=pool.submit(()->{try{gate.await();service.processAt(id,due);}catch(InterruptedException e){throw new RuntimeException(e);}});
            var second=pool.submit(()->{try{gate.await();service.processAt(id,due);}catch(InterruptedException e){throw new RuntimeException(e);}});
            gate.countDown();first.get(15,java.util.concurrent.TimeUnit.SECONDS);second.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(5890,accounts.findById(id).orElseThrow().getBalance());
            assertEquals(1,entries.findByCustomerIdOrderByCreatedAtDesc(id).stream().filter(e->"INACTIVITY_DECAY".equals(e.getEntryType())).count());
        } finally {pool.shutdownNow();entries.deleteAll(entries.findByCustomerIdOrderByCreatedAtDesc(id));accounts.deleteById(id);}
    }
}
