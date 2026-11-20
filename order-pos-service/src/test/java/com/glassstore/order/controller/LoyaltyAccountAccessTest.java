package com.glassstore.order.controller;

import com.glassstore.order.entity.LoyaltyAccount;
import com.glassstore.order.repository.*;
import com.glassstore.order.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoyaltyAccountAccessTest {
    private final PosDataService data = mock(PosDataService.class);
    private final LoyaltyAccountRepository accounts = mock(LoyaltyAccountRepository.class);
    private final LoyaltyLifecycleService lifecycle=mock(LoyaltyLifecycleService.class);
    private final PromotionController controller = new PromotionController(mock(PromotionService.class), data,
            new LoyaltyPolicy(), accounts, mock(LoyaltyEntryRepository.class), mock(VoucherRepository.class),lifecycle);

    private void authenticate(String id, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(id, "", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void customerReadsOnlyOwnBalance() {
        authenticate("15", "CUSTOMER");
        LoyaltyAccount account = new LoyaltyAccount(); account.setCustomerId(15L); account.setBalance(6200);
        when(accounts.findById(15L)).thenReturn(Optional.of(account));
        when(lifecycle.current(15L)).thenReturn(account);
        assertEquals(6200L, controller.account(15L, "Bearer token").get("balance"));
        assertThrows(ResponseStatusException.class, () -> controller.account(16L, "Bearer token"));
        verifyNoInteractions(data);
    }

    @Test void staffStillRequiresValidatedActorAndCustomer() {
        when(lifecycle.current(15L)).thenReturn(new LoyaltyAccount());
        authenticate("1", "SALES_STAFF");
        controller.account(15L, "Bearer token");
        verify(data).actor("Bearer token", false);
        verify(data).customer("Bearer token", 15L);
    }
}
