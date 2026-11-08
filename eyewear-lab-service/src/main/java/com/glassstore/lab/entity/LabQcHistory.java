package com.glassstore.lab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Lịch sử kiểm định chất lượng (QC) và phân loại lỗi mài tròng kính (FR-LAB-06/07/08/10).
 */
@Entity
@Table(name = "lab_qc_histories", indexes = {
        @Index(name = "idx_lab_qc_histories_job", columnList = "lab_job_id"),
        @Index(name = "idx_lab_qc_histories_order", columnList = "order_id"),
        @Index(name = "idx_lab_qc_histories_idempotency", columnList = "idempotency_key", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabQcHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Column(name = "lab_job_id", nullable = false)
    private Long labJobId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "prescription_id")
    private Long prescriptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "qc_result", nullable = false, length = 20)
    private QcResult qcResult;

    // Từng kiểm tra cần thiết (Checklist)
    @Column(name = "power_check_passed")
    private Boolean powerCheckPassed; // Kiểm tra độ khúc xạ SPH/CYL/AXIS

    @Column(name = "optical_center_check_passed")
    private Boolean opticalCenterCheckPassed; // Kiểm tra tâm mắt PD

    @Column(name = "surface_quality_check_passed")
    private Boolean surfaceQualityCheckPassed; // Bề mặt không trầy xước/bọt khí

    @Column(name = "bevel_edging_check_passed")
    private Boolean bevelEdgingCheckPassed; // Mài vát cạnh / đường biên

    @Column(name = "frame_fitting_check_passed")
    private Boolean frameFittingCheckPassed; // Lắp ráp khớp gọng kính

    @Column(name = "failure_reason", length = 500)
    private String failureReason; // Bắt buộc khi FAIL hoặc REWORK

    @Column(name = "defect_type", length = 50)
    private String defectType; // Phân loại lỗi (sai trục, trầy xước, vỡ mép, ...)

    @Column(name = "material_damaged", nullable = false)
    @Builder.Default
    private Boolean materialDamaged = false; // Phân biệt cần mài lại với thực sự hỏng vật tư

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "inspector_id")
    private Long inspectorId;

    @Column(name = "inspector_name", length = 100)
    private String inspectorName;

    @Column(name = "rework_count")
    @Builder.Default
    private Integer reworkCount = 0;

    @Column(name = "inspected_at", nullable = false)
    @Builder.Default
    private LocalDateTime inspectedAt = LocalDateTime.now();

    @PrePersist
    protected void onCreate() {
        if (this.inspectedAt == null) {
            this.inspectedAt = LocalDateTime.now();
        }
        if (this.materialDamaged == null) {
            this.materialDamaged = false;
        }
        if (this.reworkCount == null) {
            this.reworkCount = 0;
        }
    }
}

