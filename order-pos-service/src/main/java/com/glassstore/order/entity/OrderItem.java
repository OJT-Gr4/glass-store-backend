package com.glassstore.order.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * One line of an order.
 *
 * <p>{@code productName} and {@code unitPrice} are snapshots copied server-side at
 * confirmation. Later catalog edits must never alter a completed order (BR-05, BR-06).</p>
 */
@Entity
@Table(name = "order_items", indexes = {
        @Index(name = "idx_order_items_order", columnList = "order_id"),
        @Index(name = "idx_order_items_sku", columnList = "sku_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    @JsonBackReference
    private Order order;

    @Column(name = "sku_id", nullable = false, length = 100)
    private String skuId;

    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;

    @Column(name = "prescription_id")
    private Long prescriptionId;

    @Column(name = "quantity", nullable = false)
    @Builder.Default
    private Integer quantity = 1;

    @Column(name = "unit_price", precision = 14, scale = 2, nullable = false)
    private BigDecimal unitPrice;

    @Transient
    private BigDecimal expectedUnitPrice;

    @Column(name = "subtotal", precision = 14, scale = 2, nullable = false)
    private BigDecimal subtotal;
}
