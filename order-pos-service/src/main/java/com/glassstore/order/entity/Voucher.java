package com.glassstore.order.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Configured promotion record. Discount arithmetic only uses fields stored here
 * (BR-19, BR-20). Stacking and per-customer caps are not encoded — see OQ-09.
 */
@Entity
@Table(name = "vouchers", indexes = {
        @Index(name = "idx_vouchers_code", columnList = "code", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Voucher {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 60)
    private String code;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "valid_from")
    private LocalDateTime validFrom;

    @Column(name = "valid_to")
    private LocalDateTime validTo;

    @Column(name = "min_order_amount", precision = 14, scale = 2)
    private BigDecimal minOrderAmount;

    /** PERCENT or FIXED. */
    @Column(name = "discount_type", nullable = false, length = 20)
    private String discountType;

    @Column(name = "discount_value", precision = 14, scale = 2, nullable = false)
    private BigDecimal discountValue;

    @Column(name = "max_discount_amount", precision = 14, scale = 2)
    private BigDecimal maxDiscountAmount;

    /** ONLINE, POS, or ALL. */
    @Column(name = "channel", length = 20)
    @Builder.Default
    private String channel = "ALL";

    @Builder.Default
    private boolean membersOnly = false;
    @Column(name = "required_tier", nullable = false, length = 16)
    @Builder.Default
    private String requiredTier = "NONE";
    @Builder.Default
    private boolean stackPoints = true;
    private Integer usageLimit;
    private Integer perCustomerLimit;
    @Builder.Default
    private int usedCount = 0;

    // POS names share the same persisted fields as the online voucher contract.
    public String getType() { return discountType; }
    public void setType(String type) { this.discountType = type; }
    public BigDecimal getAmount() { return discountValue; }
    public void setAmount(BigDecimal amount) { this.discountValue = amount; }
    public BigDecimal getMinimumAmount() { return minOrderAmount == null ? BigDecimal.ZERO : minOrderAmount; }
    public void setMinimumAmount(BigDecimal minimum) { this.minOrderAmount = minimum; }
    public BigDecimal getMaximumDiscount() { return maxDiscountAmount; }
    public void setMaximumDiscount(BigDecimal maximum) { this.maxDiscountAmount = maximum; }
    public LocalDateTime getStartsAt() { return validFrom; }
    public void setStartsAt(LocalDateTime start) { this.validFrom = start; }
    public LocalDateTime getExpiresAt() { return validTo; }
    public void setExpiresAt(LocalDateTime expiry) { this.validTo = expiry; }
}
