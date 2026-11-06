package com.glassstore.order.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "order_inventory_tasks")
@Getter
@Setter
@NoArgsConstructor
public class OrderInventoryTask {
    @Id
    private Long orderId;
    @Column(nullable = false, length = 20)
    private String operation;
    @Column(nullable = false, length = 40)
    private String status = com.glassstore.order.domain.InventorySyncStatus.PENDING;
    private int attempts;
    private boolean reserveStarted;
    private boolean reserveConfirmed;
    private boolean commitStarted;
    private boolean commitConfirmed;
    @Column(length = 1000)
    private String lastError;
    private LocalDateTime updatedAt = LocalDateTime.now();
}
