package com.glassstore.order.entity;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Entity @Table(name="loyalty_entries", uniqueConstraints=@UniqueConstraint(columnNames={"order_id","entry_type"})) @Data @NoArgsConstructor
public class LoyaltyEntry {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 private Long customerId;
 @Column(name="order_id") private Long orderId;
 @Column(name="entry_type") private String entryType;
 private long points;
 private String reason;
 private LocalDateTime createdAt=LocalDateTime.now();
 @Column(name="event_key",unique=true,length=160) private String eventKey;
 private LocalDateTime cycleAt;
 private String noticeStatus;
 private LocalDateTime notificationSentAt;
 @Column(nullable=false) private boolean voided=false;
}

