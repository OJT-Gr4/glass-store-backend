package com.glassstore.order.domain;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Canonical order lifecycle (SRS §4.6).
 *
 * <p>Statuses are stored as strings in the existing {@code orders.status} column, so the
 * enum also owns the read-side tolerance for legacy values written by earlier builds.</p>
 */
public enum OrderStatus {

    /** Created, awaiting payment. */
    PENDING_PAYMENT,
    /** Payment verified by the backend. */
    PAID,
    /** Dispatch to eyewear-lab-service failed after retries. */
    LAB_DISPATCH_FAILED,
    /** Paid, waiting for staff to accept. */
    PENDING_CONFIRMATION,
    /** Staff accepted. */
    CONFIRMED,
    /** Stock committed, lenses/frames being prepared. */
    PREPARING,
    /** Out for delivery. */
    SHIPPING,
    /** Ready for in-store pickup. */
    READY_FOR_PICKUP,
    /** Handed over to the customer. */
    COMPLETED,
    /** Customer asked to cancel; awaiting decision. */
    CANCEL_REQUESTED,
    /** POS cancellation is waiting for its inventory outbox acknowledgement. */
    CANCEL_PENDING,
    /** Cancelled. */
    CANCELLED,
    /** Cancelled after payment; money not yet returned. */
    REFUND_PENDING,
    /** Money returned. */
    REFUNDED;

    /**
     * Allowed forward transitions. Cancellation paths are handled separately in
     * {@link #canTransitionTo} because they depend on payment state, not just on status.
     */
    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.ofEntries(
            Map.entry(PENDING_PAYMENT, EnumSet.of(PAID, CANCELLED, CANCEL_REQUESTED)),
            Map.entry(PAID, EnumSet.of(PENDING_CONFIRMATION, CONFIRMED, PREPARING, CANCEL_REQUESTED, CANCEL_PENDING, REFUND_PENDING, LAB_DISPATCH_FAILED)),
            Map.entry(LAB_DISPATCH_FAILED, EnumSet.of(PAID, PENDING_CONFIRMATION, CONFIRMED, CANCEL_REQUESTED, CANCEL_PENDING, REFUND_PENDING, CANCELLED)),
            Map.entry(CANCEL_PENDING, EnumSet.of(CANCELLED)),
            Map.entry(PENDING_CONFIRMATION, EnumSet.of(CONFIRMED, CANCEL_REQUESTED, REFUND_PENDING)),
            Map.entry(CONFIRMED, EnumSet.of(PREPARING, SHIPPING, READY_FOR_PICKUP, CANCEL_REQUESTED, REFUND_PENDING)),
            Map.entry(PREPARING, EnumSet.of(SHIPPING, READY_FOR_PICKUP, CANCEL_REQUESTED, REFUND_PENDING)),
            Map.entry(SHIPPING, EnumSet.of(COMPLETED)),
            Map.entry(READY_FOR_PICKUP, EnumSet.of(COMPLETED)),
            Map.entry(CANCEL_REQUESTED, EnumSet.of(CANCELLED, REFUND_PENDING, PENDING_PAYMENT, PAID, LAB_DISPATCH_FAILED, PENDING_CONFIRMATION, CONFIRMED)),
            Map.entry(REFUND_PENDING, EnumSet.of(REFUNDED)),
            Map.entry(COMPLETED, EnumSet.noneOf(OrderStatus.class)),
            Map.entry(CANCELLED, EnumSet.noneOf(OrderStatus.class)),
            Map.entry(REFUNDED, EnumSet.noneOf(OrderStatus.class))
    );

    /** Terminal states never change again (BR-17: no direct edits afterwards). */
    private static final Set<OrderStatus> TERMINAL =
            Collections.unmodifiableSet(EnumSet.of(COMPLETED, CANCELLED, REFUNDED));

    /** States in which a customer may still request cancellation (OQ-10). */
    private static final Set<OrderStatus> CUSTOMER_CANCELLABLE = Collections.unmodifiableSet(
            EnumSet.of(PENDING_PAYMENT, PAID, LAB_DISPATCH_FAILED, PENDING_CONFIRMATION, CONFIRMED));

    /** States in which staff may still cancel (OQ-10). */
    private static final Set<OrderStatus> STAFF_CANCELLABLE = Collections.unmodifiableSet(
            EnumSet.of(PENDING_PAYMENT, PAID, LAB_DISPATCH_FAILED, PENDING_CONFIRMATION, CONFIRMED, PREPARING, CANCEL_REQUESTED));

    public boolean canTransitionTo(OrderStatus target) {
        if (target == null) {
            return false;
        }
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean isCustomerCancellable() {
        return CUSTOMER_CANCELLABLE.contains(this);
    }

    public boolean isStaffCancellable() {
        return STAFF_CANCELLABLE.contains(this);
    }

    /** True when money has been taken and must therefore go through the refund path. */
    public boolean isPaidLike() {
        return this != PENDING_PAYMENT && this != CANCELLED;
    }

    /**
     * Parses a stored status. Legacy rows used {@code PROCESSING} for what is now
     * {@code PREPARING}; that value is mapped rather than rejected so old data keeps loading.
     */
    public static OrderStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return PENDING_PAYMENT;
        }
        String normalized = raw.trim().toUpperCase();
        if ("PROCESSING".equals(normalized)) {
            return PREPARING;
        }
        return Arrays.stream(values())
                .filter(s -> s.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Trạng thái đơn hàng không hợp lệ: " + raw));
    }
}
