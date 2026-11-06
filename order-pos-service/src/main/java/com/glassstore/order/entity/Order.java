package com.glassstore.order.entity;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.glassstore.order.domain.*;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A purchase, from either the online channel or the in-store POS (SRS §4.9 — one domain).
 *
 * <p>Monetary fields are frozen at confirmation: {@code totalAmount}/{@code discountAmount}/
 * {@code finalAmount} are computed server-side and never accept a client value (BR-05,
 * BR-16). Order and payment status are separate fields and must never be conflated.</p>
 */
@Entity
@Table(name = "orders", indexes = {
        @Index(name = "idx_orders_customer", columnList = "customer_id"),
        @Index(name = "idx_orders_status", columnList = "status"),
        @Index(name = "idx_orders_code", columnList = "order_code", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_code", nullable = false, unique = true, length = 60)
    private String orderCode;

    /**
     * Client-supplied retry token. UNIQUE so a replayed checkout cannot create a second
     * order (EX-ORD-04, EX-ORD-05).
     */
    @Column(name = "idempotency_key", unique = true, length = 120)
    private String idempotencyKey;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "customer_name", nullable = false, length = 120)
    private String customerName;

    @Column(name = "customer_phone", nullable = false, length = 30)
    private String customerPhone;

    @Column(name = "shipping_address", length = 500)
    private String shippingAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    @Builder.Default
    private SalesChannel channel = SalesChannel.ONLINE;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", length = 30)
    @Builder.Default
    private OrderType orderType = OrderType.COMPLETE_GLASSES;

    @Column(name = "total_amount", precision = 14, scale = 2, nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "discount_amount", precision = 14, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "final_amount", precision = 14, scale = 2, nullable = false)
    private BigDecimal finalAmount;

    /** Order lifecycle only — see {@link PaymentStatus} for money state. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32, columnDefinition = "varchar(32)")
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING_PAYMENT;

    @Column(name = "tax_amount", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20)
    private PaymentMethod paymentMethod;

    @Column(name = "voucher_code", length = 60)
    private String voucherCode;

    /** Id of the prescription used for lens fabrication (BR-25 prescription versioning). */
    @Column(name = "prescription_id")
    private Long prescriptionId;

    @Embedded
    private OrderPrescription prescription;

    @Column(name = "staff_name", length = 120)
    private String staffName;

    @Column(name = "branch_name", length = 120)
    private String branchName;

    private String branchCode;

    private Long voucherId;
    private BigDecimal voucherDiscount;
    private Long pointsToUse;
    private Long redeemedPoints;
    private BigDecimal pointsDiscount;
    private BigDecimal pointUnitValue;
    private String cancelReason;
    private String cancelledBy;
    private LocalDateTime cancelledAt;
    private String refundStatus;
    private BigDecimal cashReceived;
    @Transient private boolean manualPaymentConfirmed;
    @Transient private String paymentReference;
    @Transient @com.fasterxml.jackson.annotation.JsonIgnore private String checkoutRequestHash;
    private String refundConfirmedBy;
    private LocalDateTime refundConfirmedAt;


    @Column(name = "tracking_code", length = 80)
    private String trackingCode;

    @Column(name = "carrier_name", length = 60)
    private String carrierName;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "pos_session_id")
    private Long posSessionId;

    @Column(name = "invoice_number", length = 80)
    private String invoiceNumber;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonManagedReference
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonManagedReference
    @Builder.Default
    private List<OrderStatusHistory> statusHistory = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    /** Convenience for guards; keeps callers from parsing the string themselves. */
    @Transient
    public OrderStatus currentStatus() {
        return status == null ? OrderStatus.PENDING_PAYMENT : status;
    }

    @Transient
    private String inventorySyncStatus;
    @Transient private String inventorySyncError;
    @Transient private String inventorySyncOperation;
}
