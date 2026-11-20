package com.glassstore.order.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** A single customer's entitlement, reserved while an order is awaiting payment. */
@Entity
@Table(name = "voucher_grants", uniqueConstraints = @UniqueConstraint(name = "uk_voucher_grant_customer", columnNames = {"voucher_id", "customer_id"}))
@Getter @Setter
public class VoucherGrant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "voucher_id", nullable = false)
    private Long voucherId;
    @Column(name = "customer_id", nullable = false)
    private Long customerId;
    @Column(name = "reserved_order_id")
    private Long reservedOrderId;
    @Column(name = "used_order_id")
    private Long usedOrderId;
    @Column(nullable = false)
    private boolean revoked;
    @Column(name = "granted_at", nullable = false)
    private LocalDateTime grantedAt = LocalDateTime.now();
}
