package com.glassstore.lab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "eye_exam_appointments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EyeExamAppointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    @Column(name = "prescription_id")
    private Long prescriptionId;

    @Column(name = "ticket_code", nullable = false, unique = true)
    private String ticketCode;

    @Column(name = "ticket_type")
    private String ticketType;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Column(name = "customer_tier")
    private String customerTier;

    @Column(name = "optometrist_id")
    private Long optometristId;

    @Column(name = "optometrist_name")
    private String optometristName;

    @Column(name = "room_name")
    private String roomName;

    @Column(name = "room_device")
    private String roomDevice;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "status_label")
    private String statusLabel;

    @Column(name = "priority_level")
    @Builder.Default
    private Integer priorityLevel = 2;

    @Column(name = "wait_minutes")
    @Builder.Default
    private Integer waitMinutes = 0;

    @Column(name = "exam_step")
    private String examStep;

    @Column(name = "exam_step_index")
    @Builder.Default
    private Integer examStepIndex = 1;

    @Column(name = "scheduled_time")
    private String scheduledTime;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
