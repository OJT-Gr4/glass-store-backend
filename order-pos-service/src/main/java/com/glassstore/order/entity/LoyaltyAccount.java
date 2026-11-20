package com.glassstore.order.entity;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Entity @Table(name="loyalty_accounts") @Data @NoArgsConstructor
public class LoyaltyAccount {
 @Id private Long customerId;
 @Column(nullable=false) private long balance=0;
 @Version private Long version;
 private LocalDateTime protectionStartedAt;
 private LocalDateTime lastActivityAt;
 private LocalDateTime nextDecayAt;
 private Long baselineTierPoints;
 private Long tierPoints;
 private String tierCode;
 private LocalDateTime tierProtectedUntil;
 public long getTierPoints() { return tierPoints == null ? balance : tierPoints; }
 public String getTierCode() { return tierCode == null ? com.glassstore.order.service.VoucherEntitlementService.tier(getTierPoints()) : tierCode; }
}

