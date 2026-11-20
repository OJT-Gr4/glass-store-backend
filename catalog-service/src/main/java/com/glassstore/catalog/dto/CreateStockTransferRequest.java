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
public class CreateStockTransferRequest {

    @NotBlank(message = "Kho nguồn không được để trống")
    private String sourceLocationCode;

    @NotBlank(message = "Kho đích không được để trống")
    private String destinationLocationCode;

    @Size(max = 500, message = "Lý do điều chuyển tối đa 500 ký tự")
    private String reason;

    @NotEmpty(message = "Danh sách hàng điều chuyển không được để trống")
    private List<@Valid Item> items;

    @Data
    public static class Item {
        @NotBlank(message = "Mã SKU không được để trống")
        private String sku;

        @NotNull(message = "Số lượng không được để trống")
        @Positive(message = "Số lượng điều chuyển phải lớn hơn 0")
        private Integer quantity;
    }
}
