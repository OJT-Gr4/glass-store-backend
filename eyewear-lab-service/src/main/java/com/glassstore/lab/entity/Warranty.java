package com.glassstore.lab.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "warranties", uniqueConstraints = {
        @UniqueConstraint(name = "uk_warranty_order_item_benefit",
                columnNames = {"order_item_id", "benefit_type"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Warranty {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "warranty_code", nullable = false, unique = true)
    private String warrantyCode;

    @Column(name = "original_order_code")
    private String originalOrderCode;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "order_item_id")
    private Long orderItemId;

    @Column(name = "benefit_type", length = 40)
    private String benefitType;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "product_barcode")
    private String productBarcode;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Column(name = "customer_tier")
    private String customerTier;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "product_sku")
    private String productSku;

    @Column(name = "product_details")
    private String productDetails;

    @Column(name = "issue_description", length = 1000)
    private String issueDescription;

    @Column(name = "claim_type")
    private String claimType;

    @Column(name = "policy_ref")
    private String policyRef;

    @JsonIgnore
    @Column(name = "tech_note", length = 1000)
    private String techNote;

    @JsonIgnore
    @Column(name = "cost")
    private String cost;

    @JsonIgnore
    @Column(name = "tech_name")
    private String techName;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "status_label")
    private String statusLabel;

    @Column(name = "status_color")
    private String statusColor;

    @Column(name = "time_label")
    private String timeLabel;

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
}
