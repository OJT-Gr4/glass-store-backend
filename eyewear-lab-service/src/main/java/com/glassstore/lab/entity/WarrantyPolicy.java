package com.glassstore.lab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "warranty_policies", indexes = {
        @Index(name = "idx_warranty_policy_benefit_active", columnList = "benefit_type, active, version")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarrantyPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_code", nullable = false, length = 64)
    private String policyCode;

    @Column(name = "benefit_type", nullable = false, length = 40)
    private String benefitType;

    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(name = "duration_days")
    private Integer durationDays;

    @Column(name = "coverage_terms", nullable = false, length = 1000)
    private String coverageTerms;

    @Column(name = "eligible_sku_prefixes", length = 120)
    private String eligibleSkuPrefixes;

    @Column(name = "service_fee", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal serviceFee = BigDecimal.ZERO;

    @Column(name = "effective_from", nullable = false)
    @Builder.Default
    private LocalDateTime effectiveFrom = LocalDateTime.now();

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}

