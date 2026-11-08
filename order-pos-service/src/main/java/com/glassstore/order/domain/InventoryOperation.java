package com.glassstore.order.domain;

/** Persisted operation names shared by inventory tasks and their workers. */
public final class InventoryOperation {
    public static final String RESERVE = "RESERVE";
    public static final String COMMIT = "COMMIT";
    public static final String RELEASE = "RELEASE";

    private InventoryOperation() {}
}
