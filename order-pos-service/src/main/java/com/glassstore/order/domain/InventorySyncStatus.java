package com.glassstore.order.domain;

/** Stable database/API values; independent of order and payment status. */
public final class InventorySyncStatus {
    public static final String PENDING = "PENDING";
    public static final String SYNCED = "SYNCED";
    public static final String FAILED = "FAILED";
    public static final String RECONCILIATION_REQUIRED = "RECONCILIATION_REQUIRED";
    public static final String NOT_REQUIRED = "NOT_REQUIRED";

    private InventorySyncStatus() {}
}
