package com.glassstore.order.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateLabJobRequest {

    private Long orderId;
    private Long orderItemId;
    private Long prescriptionId;
    private String sku;
    private String idempotencyKey;

    private String orderCode;
    private String trayNumber;
    private Long customerId;
    private String customerName;

    private String lensSku;
    private String lensName;
    private String frameSku;
    private String frameName;

    private Long technicianId;
    private String technicianName;
    private String technicianNotes;

    // Chi tiết thông số đơn thuốc khúc xạ
    private PrescriptionDto prescription;

    // Các trường phẳng
    private BigDecimal odSphere;
    private BigDecimal odCyl;
    private Integer odAxis;
    private BigDecimal odAdd;

    private BigDecimal osSphere;
    private BigDecimal osCyl;
    private Integer osAxis;
    private BigDecimal osAdd;

    private BigDecimal pd;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PrescriptionDto {
        private Long id;
        private Long customerId;
        private BigDecimal odSphere;
        private BigDecimal odCyl;
        private Integer odAxis;
        private BigDecimal odAdd;

        private BigDecimal osSphere;
        private BigDecimal osCyl;
        private Integer osAxis;
        private BigDecimal osAdd;

        private BigDecimal pd;
        private String notes;
    }
}
