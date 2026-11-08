package com.glassstore.lab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "repair_services")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RepairService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "service_code", nullable = false, unique = true)
    private String serviceCode;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Column(name = "product_name")
    private String productName;

    @Column(name = "product_type")
    private String productType;

    @Column(name = "product_barcode")
    private String productBarcode;

    @Column(name = "frame_condition", length = 1000)
    private String frameCondition;

    @Column(name = "lens_condition", length = 1000)
    private String lensCondition;

    @Column(name = "reported_problem", length = 1000)
    private String reportedProblem;

    @Column(name = "physical_condition", length = 1000)
    private String physicalCondition;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "repair_service_requested_services",
            joinColumns = @JoinColumn(name = "repair_service_id"))
    @Column(name = "requested_service", nullable = false, length = 50)
    @Builder.Default
    private List<String> requestedServices = new ArrayList<>();

    @Column(name = "assigned_technician")
    private String assignedTechnician;

    @Column(name = "reception_employee")
    private String receptionEmployee;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    @Column(name = "expected_pickup_at")
    private LocalDateTime expectedPickupAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "customer_notes", length = 1000)
    private String customerNotes;

    @Column(name = "service_type", nullable = false)
    private String serviceType;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "order_item_id")
    private Long orderItemId;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "status", nullable = false)
    @Builder.Default
    private String status = "RECEIVED";

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PrePersist
    protected void onCreate() {
        if (this.serviceCode == null || this.serviceCode.isBlank()) {
            this.serviceCode = "RS-" + java.util.UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        }
        if (this.status == null || this.status.isBlank()) {
            this.status = "RECEIVED";
        }
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.updatedAt == null) {
            this.updatedAt = LocalDateTime.now();
        }
        if (this.receivedAt == null) {
            this.receivedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
