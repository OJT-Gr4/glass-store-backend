package com.glassstore.catalog.dto;

import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductVariantDTO {
    private Long id;
    private String sku;
    private String barcode;
    private String name;
    private String color;
    private String frameShape;
    private String material;
    private String lensIndex;
    private BigDecimal price;
    private BigDecimal costPrice;
    private Integer stockQuantity;
    private Integer availableQuantity;
    private String imageUrl;
    private String status;
}

