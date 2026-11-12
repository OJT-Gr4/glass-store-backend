package com.glassstore.catalog.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "stock_transfers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_code", nullable = false, unique = true, length = 40)
    private String transferCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_location_id", nullable = false)
    private InventoryLocation sourceLocation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_location_id", nullable = false)
    private InventoryLocation destinationLocation;

    @Column(length = 500)
    private String reason;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "dispatched_by", length = 100)
    private String dispatchedBy;

    @Column(name = "received_by", length = 100)
    private String receivedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    @Builder.Default
    private StockTransferStatus status = StockTransferStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    @OneToMany(mappedBy = "transfer", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<StockTransferItem> items = new ArrayList<>();
}
