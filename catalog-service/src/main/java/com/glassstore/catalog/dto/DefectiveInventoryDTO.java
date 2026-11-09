package com.glassstore.catalog.dto;

public record DefectiveInventoryDTO(
        String sku,
        String productName,
        String locationCode,
        String locationName,
        Integer onHandQuantity,
        Integer quarantinedQuantity) {}
