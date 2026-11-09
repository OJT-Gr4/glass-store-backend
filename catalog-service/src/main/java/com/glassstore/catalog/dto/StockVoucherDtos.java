package com.glassstore.catalog.dto;

import com.glassstore.catalog.entity.StockVoucherType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class StockVoucherDtos {
    private StockVoucherDtos() {}

    public record ItemRequest(
            @NotBlank @Size(max = 100) String sku,
            @NotNull @Min(1) Integer quantity,
            @NotNull @Min(0) Integer quarantinedQuantity) {}

    public record CreateRequest(
            @NotNull StockVoucherType type,
            @NotBlank @Size(max = 32) String locationCode,
            Long supplierId,
            @NotBlank @Size(max = 500) String reason,
            @Size(max = 120) String reference,
            @NotEmpty List<@Valid ItemRequest> items) {}

    public record DecisionRequest(@Size(max = 500) String reason) {}

    public record SupplierSummary(Long id, String code, String name) {}

    public record ItemResponse(
            String sku,
            String productName,
            Integer quantity,
            Integer quarantinedQuantity) {}

    public record Response(
            Long id,
            String voucherCode,
            StockVoucherType type,
            String status,
            String locationCode,
            String locationName,
            SupplierSummary supplier,
            String reason,
            String reference,
            String createdBy,
            String decidedBy,
            String decisionReason,
            LocalDateTime createdAt,
            LocalDateTime decidedAt,
            List<ItemResponse> items) {}
}
