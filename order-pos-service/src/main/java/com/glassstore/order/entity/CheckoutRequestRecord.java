package com.glassstore.order.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity @Table(name = "checkout_requests") @Getter @Setter @NoArgsConstructor
public class CheckoutRequestRecord {
    @Id @Column(length = 100) private String requestKey;
    @Column(nullable = false, length = 64) private String requestHash;
    @Column(nullable = false, length = 160) private String owner;
    private Long orderId;
    @Column(nullable = false) private LocalDateTime createdAt;
}
