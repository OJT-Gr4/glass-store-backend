package com.glassstore.catalog.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class StockDeductionRequest {

    @NotNull
    @Positive
    private Long orderId;

    @NotBlank
    @Size(max = 100)
    private String idempotencyKey;

    @NotBlank
    @Size(max = 100)
    private String transactionCode;

    @NotBlank
    @Size(max = 160)
    private String branchName;

    @NotEmpty
    private List<@Valid Item> items;

    @Data
    public static class Item {
        @NotBlank
        @Size(max = 100)
        private String sku;

        @NotNull
        @Positive
        private Integer quantity;
    }
}
