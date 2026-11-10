package com.glassstore.catalog.dto;

import com.glassstore.catalog.entity.ProductType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Các DTO phục vụ nghiệp vụ Admin CRUD Sản phẩm, Biến thể và Giá (FR-PRO-01..09, FR-ADM-05).
 */
public final class ProductManagementDtos {

    private ProductManagementDtos() {
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductCreateRequest {
        @NotBlank(message = "Tên sản phẩm không được để trống")
        @Size(max = 255, message = "Tên sản phẩm không được vượt quá 255 ký tự")
        private String name;

        @Size(max = 255, message = "Slug không được vượt quá 255 ký tự")
        private String slug;

        @NotNull(message = "Danh mục sản phẩm không được để trống")
        private Long categoryId;

        @NotNull(message = "Thương hiệu không được để trống")
        private Long brandId;

        @NotNull(message = "Loại sản phẩm không được để trống")
        private ProductType productType;

        private String description;

        private String status;

        @Valid
        private List<VariantCreateRequest> variants;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductUpdateRequest {
        @NotBlank(message = "Tên sản phẩm không được để trống")
        @Size(max = 255, message = "Tên sản phẩm không được vượt quá 255 ký tự")
        private String name;

        @Size(max = 255, message = "Slug không được vượt quá 255 ký tự")
        private String slug;

        @NotNull(message = "Danh mục sản phẩm không được để trống")
        private Long categoryId;

        @NotNull(message = "Thương hiệu không được để trống")
        private Long brandId;

        @NotNull(message = "Loại sản phẩm không được để trống")
        private ProductType productType;

        private String description;

        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductStatusRequest {
        @NotBlank(message = "Trạng thái không được để trống")
        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VariantCreateRequest {
        @NotBlank(message = "Mã SKU không được để trống")
        @Size(max = 100, message = "Mã SKU không được vượt quá 100 ký tự")
        private String sku;

        @NotBlank(message = "Mã vạch Barcode không được để trống")
        @Size(max = 100, message = "Mã Barcode không được vượt quá 100 ký tự")
        private String barcode;

        @NotBlank(message = "Tên biến thể không được để trống")
        @Size(max = 255, message = "Tên biến thể không được vượt quá 255 ký tự")
        private String name;

        @Size(max = 50, message = "Màu sắc không được vượt quá 50 ký tự")
        private String color;

        @Size(max = 50, message = "Hình dáng gọng không được vượt quá 50 ký tự")
        private String frameShape;

        @Size(max = 50, message = "Chất liệu không được vượt quá 50 ký tự")
        private String material;

        @Size(max = 20, message = "Chiết suất tròng không được vượt quá 20 ký tự")
        private String lensIndex;

        @NotNull(message = "Giá bán không được để trống")
        @DecimalMin(value = "0.0", inclusive = true, message = "Giá bán không được âm")
        private BigDecimal price;

        @NotNull(message = "Giá vốn không được để trống")
        @DecimalMin(value = "0.0", inclusive = true, message = "Giá vốn không được âm")
        private BigDecimal costPrice;

        @Size(max = 500, message = "URL ảnh không được vượt quá 500 ký tự")
        private String imageUrl;

        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VariantUpdateRequest {
        @NotBlank(message = "Mã SKU không được để trống")
        @Size(max = 100, message = "Mã SKU không được vượt quá 100 ký tự")
        private String sku;

        @NotBlank(message = "Mã vạch Barcode không được để trống")
        @Size(max = 100, message = "Mã Barcode không được vượt quá 100 ký tự")
        private String barcode;

        @NotBlank(message = "Tên biến thể không được để trống")
        @Size(max = 255, message = "Tên biến thể không được vượt quá 255 ký tự")
        private String name;

        @Size(max = 50, message = "Màu sắc không được vượt quá 50 ký tự")
        private String color;

        @Size(max = 50, message = "Hình dáng gọng không được vượt quá 50 ký tự")
        private String frameShape;

        @Size(max = 50, message = "Chất liệu không được vượt quá 50 ký tự")
        private String material;

        @Size(max = 20, message = "Chiết suất tròng không được vượt quá 20 ký tự")
        private String lensIndex;

        @NotNull(message = "Giá bán không được để trống")
        @DecimalMin(value = "0.0", inclusive = true, message = "Giá bán không được âm")
        private BigDecimal price;

        @NotNull(message = "Giá vốn không được để trống")
        @DecimalMin(value = "0.0", inclusive = true, message = "Giá vốn không được âm")
        private BigDecimal costPrice;

        @Size(max = 500, message = "URL ảnh không được vượt quá 500 ký tự")
        private String imageUrl;

        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VariantStatusRequest {
        @NotBlank(message = "Trạng thái không được để trống")
        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VariantPriceRequest {
        @NotNull(message = "Giá bán không được để trống")
        @DecimalMin(value = "0.0", inclusive = true, message = "Giá bán không được âm")
        private BigDecimal price;

        @DecimalMin(value = "0.0", inclusive = true, message = "Giá vốn không được âm")
        private BigDecimal costPrice;
    }
}

