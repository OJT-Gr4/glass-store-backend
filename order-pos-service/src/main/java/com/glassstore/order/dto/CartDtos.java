package com.glassstore.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;

public final class CartDtos {

    private CartDtos() {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AddItemRequest {
        @NotBlank(message = "Mã SKU không được để trống")
        private String sku;
        @NotNull(message = "Số lượng không được để trống")
        @Min(value = 1, message = "Số lượng phải từ 1 trở lên")
        private Integer quantity;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UpdateQtyRequest {
        @NotNull(message = "Số lượng không được để trống")
        @Min(value = 1, message = "Số lượng phải từ 1 trở lên")
        private Integer quantity;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CartView {
        private Long cartId;
        private Long customerId;
        private String sessionToken;
        private List<CartLineView> items;
        private BigDecimal totalAmount;
        private List<OrderDtos.PriceChange> priceChanges;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CartLineView {
        private Long itemId;
        private String sku;
        private String productName;
        private Integer quantity;
        private BigDecimal quotedUnitPrice;
        private BigDecimal currentUnitPrice;
        private BigDecimal subtotal;
        private Integer availableQuantity;
    }
}
