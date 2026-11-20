package com.glassstore.order.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LoyaltyTierTest {
    @Test void membershipThresholdsIncludeTheirBoundaries() {
        LoyaltyPolicy policy = new LoyaltyPolicy();
        assertEquals("Thành Viên", policy.resolveTier(999));
        assertEquals("Hạng Bạc", policy.resolveTier(1000));
        assertEquals("Hạng Bạc", policy.resolveTier(4999));
        assertEquals("Hạng Vàng", policy.resolveTier(5000));
        assertEquals("Hạng Vàng", policy.resolveTier(9999));
        assertEquals("Hạng Kim Cương", policy.resolveTier(10000));
    }
}
