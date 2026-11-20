package com.glassstore.order.service;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
@Component @ConfigurationProperties(prefix="pos.loyalty") @Data
public class LoyaltyPolicy {
    private boolean enabled = true;
    private boolean earnEnabled = true;
    private BigDecimal pointValue = new BigDecimal("1000");
    private BigDecimal earnUnitAmount = new BigDecimal("1000");
    private int maxRedemptionPercent = 20;
    private boolean allowVoucherStack = true;

    public String resolveTier(long balance) {
        if (balance >= 10000) return "Hạng Kim Cương";
        if (balance >= 5000) return "Hạng Vàng";
        if (balance >= 1000) return "Hạng Bạc";
        return "Thành Viên";
    }
}
