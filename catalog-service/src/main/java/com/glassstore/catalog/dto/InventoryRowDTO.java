package com.glassstore.catalog.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class InventoryRowDTO {
    private String sku;
    private String productName;
    private String productType;
    private Map<String, Integer> quantities;
    private Map<String, Integer> sellableQuantities;
}
