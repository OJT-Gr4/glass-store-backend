package com.glassstore.identity.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "transaction_notifications",
    indexes = {
        @Index(name = "idx_tx_notif_recipient_created", columnList = "recipient_user_id, created_at"),
        @Index(name = "idx_tx_notif_recipient_read", columnList = "recipient_user_id, is_read")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_transaction_notifications_dedup", columnNames = "dedup_key")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dedup_key", nullable = false, unique = true, length = 180)
    private String dedupKey;

    @Column(name = "recipient_user_id", nullable = false)
    private Long recipientUserId;

    @Column(name = "recipient_role", length = 50)
    private String recipientRole;

    @Column(name = "category", nullable = false, length = 30)
    private String category;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "reference_type", length = 40)
    private String referenceType;

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "reference_code", length = 80)
    private String referenceCode;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "message", nullable = false, length = 1000)
    private String message;

    @Column(name = "action_tab", length = 80)
    private String actionTab;

    @Column(name = "action_route", length = 160)
    private String actionRoute;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private boolean read = false;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "in_app_status", nullable = false, length = 40)
    @Builder.Default
    private String inAppStatus = "DELIVERED";

    @Column(name = "sms_status", nullable = false, length = 40)
    @Builder.Default
    private String smsStatus = "SKIPPED";

    @Column(name = "sms_detail", length = 300)
    private String smsDetail;

    @Column(name = "email_status", nullable = false, length = 40)
    @Builder.Default
    private String emailStatus = "SKIPPED";

    @Column(name = "email_detail", length = 300)
    private String emailDetail;

    @Column(name = "source_service", length = 60)
    private String sourceService;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.inAppStatus == null || this.inAppStatus.isBlank()) {
            this.inAppStatus = "DELIVERED";
        }
        if (this.smsStatus == null || this.smsStatus.isBlank()) {
            this.smsStatus = "SKIPPED";
        }
        if (this.emailStatus == null || this.emailStatus.isBlank()) {
            this.emailStatus = "SKIPPED";
        }
    }
}
