package com.glassstore.order.controller;
import com.glassstore.order.entity.*;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.service.LoyaltyLifecycleService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class LabCustomerTierControllerTest {
    @Test void labUsesProtectedRankEvenWhenRedeemableBalanceIsZero() {
        var orders=mock(OrderRepository.class);var lifecycle=mock(LoyaltyLifecycleService.class);
        var member=new LoyaltyAccount();member.setCustomerId(15L);member.setBalance(0);member.setTierCode("GOLD");member.setTierPoints(6200L);
        when(orders.findAllById(List.of(1L,2L))).thenReturn(List.of(Order.builder().id(1L).customerId(15L).build(),Order.builder().id(2L).build()));
        when(lifecycle.current(15L)).thenReturn(member);
        var rows=new LabCustomerTierController(orders,lifecycle).tiers(List.of(1L,2L)).getData();
        assertEquals("GOLD",rows.getFirst().tierCode());assertEquals("Hạng Vàng",rows.getFirst().tier());assertEquals("Khách vãng lai",rows.getLast().tier());
    }
    @Test void batchSizeAndIdsAreValidated() {
        var controller=new LabCustomerTierController(mock(OrderRepository.class),mock(LoyaltyLifecycleService.class));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.tiers(List.of(0L)));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.tiers(java.util.Collections.nCopies(201,1L)));
    }
}
