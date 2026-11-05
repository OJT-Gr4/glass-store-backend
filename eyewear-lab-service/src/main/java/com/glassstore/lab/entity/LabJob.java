package com.glassstore.lab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "lab_jobs", indexes = {
        @Index(name = "idx_lab_jobs_order", columnList = "order_id"),
        @Index(name = "idx_lab_jobs_idempotency", columnList = "idempotency_key", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", unique = true, nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "order_item_id")
    private Long orderItemId;

    @Column(name = "order_code", length = 60)
    private String orderCode;

    @Column(name = "tray_number", length = 60)
    private String trayNumber;

    @Column(name = "prescription_id")
    private Long prescriptionId;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "sku", length = 100)
    private String sku;

    @Column(name = "lens_sku", length = 100)
    private String lensSku;

    @Column(name = "lens_name")
    private String lensName;

    @Column(name = "frame_sku", length = 100)
    private String frameSku;

    @Column(name = "frame_name")
    private String frameName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 50, nullable = false)
    @Builder.Default
    private LabJobStatus status = LabJobStatus.CREATED;

    @Column(name = "technician_id")
    private Long technicianId;

    @Column(name = "technician_name")
    private String technicianName;

    @Column(name = "technician_notes", length = 500)
    private String technicianNotes;

    @Column(name = "qc_notes", length = 500)
    private String qcNotes;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.updatedAt == null) {
            this.updatedAt = LocalDateTime.now();
        }
        if (this.status == null) {
            this.status = LabJobStatus.CREATED;
        }
        if (this.idempotencyKey == null || this.idempotencyKey.isBlank()) {
            this.idempotencyKey = "LAB_REQ_" + (this.orderId != null ? this.orderId : "0")
                    + "_" + (this.orderItemId != null ? this.orderItemId : "0");
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
