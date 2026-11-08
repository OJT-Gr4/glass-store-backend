package com.glassstore.lab.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateLabJobRequest {

    @NotNull(message = "Mã đơn hàng (orderId) không được để trống")
    private Long orderId;

    private Long orderItemId;

    private Long prescriptionId;
    private Long prescriptionVersion;

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

    // Chi tiết thông số đơn thuốc/khúc xạ
    private PrescriptionDto prescription;

    // Các trường phẳng tương thích
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
    public static class PrescriptionDto {
        private Long id;

        @JsonAlias({"odSph", "od_sph", "odSphere"})
        private BigDecimal odSphere;

        @JsonAlias({"od_cyl", "odCyl"})
        private BigDecimal odCyl;

        @JsonAlias({"od_axis", "odAxis"})
        private Integer odAxis;

        @JsonAlias({"od_add", "odAdd"})
        private BigDecimal odAdd;

        @JsonAlias({"osSph", "os_sph", "osSphere"})
        private BigDecimal osSphere;

        @JsonAlias({"os_cyl", "osCyl"})
        private BigDecimal osCyl;

        @JsonAlias({"os_axis", "osAxis"})
        private Integer osAxis;

        @JsonAlias({"os_add", "osAdd"})
        private BigDecimal osAdd;

        private BigDecimal pd;
        private String notes;
    }

    public String getEffectiveIdempotencyKey() {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return idempotencyKey.trim();
        }
        return "LAB_REQ_" + (orderId != null ? orderId : "0") + "_" + (orderItemId != null ? orderItemId : "0");
    }

    public BigDecimal getEffectiveOdSphere() {
        if (prescription != null && prescription.getOdSphere() != null) {
            return prescription.getOdSphere();
        }
        return odSphere;
    }

    public BigDecimal getEffectiveOdCyl() {
        if (prescription != null && prescription.getOdCyl() != null) {
            return prescription.getOdCyl();
        }
        return odCyl;
    }

    public Integer getEffectiveOdAxis() {
        if (prescription != null && prescription.getOdAxis() != null) {
            return prescription.getOdAxis();
        }
        return odAxis;
    }

    public BigDecimal getEffectiveOdAdd() {
        if (prescription != null && prescription.getOdAdd() != null) {
            return prescription.getOdAdd();
        }
        return odAdd;
    }

    public BigDecimal getEffectiveOsSphere() {
        if (prescription != null && prescription.getOsSphere() != null) {
            return prescription.getOsSphere();
        }
        return osSphere;
    }

    public BigDecimal getEffectiveOsCyl() {
        if (prescription != null && prescription.getOsCyl() != null) {
            return prescription.getOsCyl();
        }
        return osCyl;
    }

    public Integer getEffectiveOsAxis() {
        if (prescription != null && prescription.getOsAxis() != null) {
            return prescription.getOsAxis();
        }
        return osAxis;
    }

    public BigDecimal getEffectiveOsAdd() {
        if (prescription != null && prescription.getOsAdd() != null) {
            return prescription.getOsAdd();
        }
        return osAdd;
    }

    public BigDecimal getEffectivePd() {
        if (prescription != null && prescription.getPd() != null) {
            return prescription.getPd();
        }
        return pd;
    }

    public Long getEffectivePrescriptionId() {
        if (prescriptionId != null) {
            return prescriptionId;
        }
        if (prescription != null && prescription.getId() != null) {
            return prescription.getId();
        }
        return null;
    }

    public String getEffectiveTrayNumber() {
        if (trayNumber != null && !trayNumber.isBlank()) {
            return trayNumber.trim();
        }
        if (orderCode != null && !orderCode.isBlank()) {
            return "KHAY-" + orderCode.trim();
        }
        if (orderId != null) {
            return "KHAY-" + orderId;
        }
        return null;
    }
}
