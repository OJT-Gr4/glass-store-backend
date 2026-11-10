package com.glassstore.catalog.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "stock_voucher_items", uniqueConstraints = {
        @UniqueConstraint(name = "uk_stock_voucher_sku", columnNames = {"voucher_id", "sku"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockVoucherItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "voucher_id", nullable = false)
    private StockVoucher voucher;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    @Column(nullable = false, length = 100)
    private String sku;

    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "quarantined_quantity", nullable = false)
    @Builder.Default
    private Integer quarantinedQuantity = 0;
}
