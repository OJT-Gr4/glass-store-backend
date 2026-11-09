package com.glassstore.identity.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "customer_interactions", indexes = {
    @Index(name = "idx_ci_customer_id", columnList = "customer_id"),
    @Index(name = "idx_ci_created_at", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerInteraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "staff_id")
    private Long staffId;

    @Column(name = "staff_name", length = 100)
    private String staffName;

    @Column(name = "channel", nullable = false, length = 50)
    @Builder.Default
    private String channel = "DIRECT"; // CALL, ZALO, SMS, DIRECT, EMAIL

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
