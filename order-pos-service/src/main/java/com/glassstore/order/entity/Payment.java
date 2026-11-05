package com.glassstore.order.entity;

import com.glassstore.order.domain.PaymentMethod;
import com.glassstore.order.domain.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One payment attempt against an order.
 *
 * <p>
 * Rows are never mutated to erase history: a failed attempt stays FAILED and a
 * new row is
 * created for the retry. The unique index on
 * {@code (provider, external_transaction_id)} makes a duplicated gateway
 * callback a no-op
 * instead of a second successful charge (BR-15, EX-PAY-03).
 * </p>
 */
@Entity
@Table(name = "payments", indexes = {
        @Index(name = "idx_payments_order", columnList = "order_id"),
        @Index(name = "idx_payments_external", columnList = "provider, external_transaction_id", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32, columnDefinition = "varchar(32)")
    @Builder.Default
    private PaymentStatus status = PaymentStatus.PENDING;

    /** Amount the backend calculated for this obligation — never a client value. */
    @Column(name = "amount", precision = 14, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "idempotency_key", unique = true, length = 100)
    private String idempotencyKey;

    /** Gateway name, e.g. VNPAY. Null for CASH, which has no external provider. */
    @Column(name = "provider", length = 40)
    private String provider;

    /**
     * Gateway's own transaction id; the idempotency key for callbacks (FR-PAY-06).
     */
    @Column(name = "external_transaction_id", length = 120)
    private String externalTransactionId;

    /** Human-facing reference printed on the invoice. */
    @Column(name = "transaction_code", length = 120)
    private String transactionCode;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
}
