package com.glassstore.lab.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.glassstore.lab.entity.LabJob;
import com.glassstore.lab.entity.LabJobStatus;
import com.glassstore.lab.entity.Prescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LabJobResponse {

    private Long id;
    private String idempotencyKey;
    private Long orderId;
    private Long orderItemId;
    private String orderCode;
    private String trayNumber;
    private Long prescriptionId;
    private String customerName;
    private String sku;
    private String lensSku;
    private String lensName;
    private String frameSku;
    private String frameName;
    private LabJobStatus status;
    private String statusLabel;
    private Long technicianId;
    private String technicianName;
    private String technicianNotes;
    private String qcNotes;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Thông số độ khúc xạ mắt phải (OD) và mắt trái (OS)
    private BigDecimal odSphere;
    private BigDecimal odCyl;
    private Integer odAxis;
    private BigDecimal odAdd;

    private BigDecimal osSphere;
    private BigDecimal osCyl;
    private Integer osAxis;
    private BigDecimal osAdd;

    // Khoảng cách đồng tử (PD)
    private BigDecimal pd;
    private String prescriptionNotes;

    // Getter bí danh hỗ trợ frontend tương thích odSph / osSph
    public BigDecimal getOdSph() {
        return odSphere;
    }

    public BigDecimal getOsSph() {
        return osSphere;
    }

    public static LabJobResponse from(LabJob job, Prescription rx) {
        if (job == null) {
            return null;
        }

        LabJobResponseBuilder builder = LabJobResponse.builder()
                .id(job.getId())
                .idempotencyKey(job.getIdempotencyKey())
                .orderId(job.getOrderId())
                .orderItemId(job.getOrderItemId())
                .orderCode(job.getOrderCode())
                .trayNumber(job.getTrayNumber())
                .prescriptionId(job.getPrescriptionId())
                .customerName(job.getCustomerName())
                .sku(job.getSku())
                .lensSku(job.getLensSku())
                .lensName(job.getLensName())
                .frameSku(job.getFrameSku())
                .frameName(job.getFrameName())
                .status(job.getStatus())
                .statusLabel(job.getStatus() != null ? job.getStatus().getDescription() : null)
                .technicianId(job.getTechnicianId())
                .technicianName(job.getTechnicianName())
                .technicianNotes(job.getTechnicianNotes())
                .qcNotes(job.getQcNotes())
                .startedAt(job.getStartedAt())
                .completedAt(job.getCompletedAt())
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt());

        if (rx != null) {
            builder.odSphere(rx.getOdSph())
                    .odCyl(rx.getOdCyl())
                    .odAxis(rx.getOdAxis())
                    .odAdd(rx.getOdAdd())
                    .osSphere(rx.getOsSph())
                    .osCyl(rx.getOsCyl())
                    .osAxis(rx.getOsAxis())
                    .osAdd(rx.getOsAdd())
                    .pd(rx.getPd())
                    .prescriptionNotes(rx.getNotes());

            if (job.getCustomerName() == null || job.getCustomerName().isBlank()) {
                builder.customerName(rx.getCustomerName());
            }
        }

        return builder.build();
    }
}
