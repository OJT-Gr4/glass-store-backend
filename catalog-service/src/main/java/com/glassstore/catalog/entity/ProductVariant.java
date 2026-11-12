package com.glassstore.catalog.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "product_variants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    @JsonBackReference
    private Product product;

    @Column(nullable = false, unique = true, length = 100)
    private String sku;

    @Column(nullable = false, unique = true, length = 100)
    private String barcode;

    @Column(nullable = false)
    private String name;

    @Column(length = 50)
    private String color;

    @Column(name = "frame_shape", length = 50)
    private String frameShape;

    @Column(length = 50)
    private String material;

    @Column(name = "lens_index", length = 20)
    private String lensIndex;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "cost_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal costPrice;

    @Column(name = "stock_quantity", nullable = false)
    @Builder.Default
    private Integer stockQuantity = 0;

    /**
     * Units physically held for unpaid orders. Counts against availability (BR-08).
     */
    @Column(name = "reserved_quantity", nullable = false)
    @Builder.Default
    private Integer reservedQuantity = 0;

    /**
     * Units that exist but cannot be sold (damaged, display-only). Counts against
     * availability (BR-08, EX-INV-04).
     */
    @Column(name = "non_sellable_quantity", nullable = false)
    @Builder.Default
    private Integer nonSellableQuantity = 0;

    /**
     * Optimistic-lock guard. Two customers racing for the last unit both read the same
     * version; only one commit succeeds, the other is retried or rejected (EX-INV-05).
     */
    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    /**
     * Available to sell = actual - reserved - non-sellable (BR-08).
     * Never allowed to be negative by any write path.
     */
    @Transient
    public int getAvailableQuantity() {
        int actual = stockQuantity == null ? 0 : stockQuantity;
        int reserved = reservedQuantity == null ? 0 : reservedQuantity;
        int broken = nonSellableQuantity == null ? 0 : nonSellableQuantity;
        return Math.max(0, actual - reserved - broken);
    }

    @Column(name = "image_url", columnDefinition = "MEDIUMTEXT")
    private String imageUrl;

    @Column(length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
}

