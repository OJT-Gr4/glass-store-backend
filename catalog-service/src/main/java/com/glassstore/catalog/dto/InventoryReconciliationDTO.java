package com.glassstore.catalog.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryReconciliationDTO {

    private LocalDateTime checkedAt;
    private boolean completeMovementLedger;
    private String coverageNote;
    private List<BalanceVariance> balanceVariances;
    private List<TransferVariance> transferVariances;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BalanceVariance {
        private String sku;
        private String productName;
        private int variantStockQuantity;
        private int locationQuantity;
        private int difference;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransferVariance {
        private String transferCode;
        private String status;
        private String sku;
        private int expectedOutQuantity;
        private int actualOutQuantity;
        private int expectedInQuantity;
        private int actualInQuantity;
        private String issue;
    }
}
