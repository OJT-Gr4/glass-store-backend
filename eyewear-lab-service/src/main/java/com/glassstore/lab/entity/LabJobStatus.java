package com.glassstore.lab.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Getter;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Getter
public enum LabJobStatus {
    CREATED("Khởi tạo"),
    QUEUED("Chờ mài"),
    IN_PROGRESS("Đang mài máy"),
    QC_PASSED("Đạt chuẩn QC"),
    QC_FAILED("Lỗi hỏng"),
    COMPLETED("Hoàn thành"),
    CANCELLED("Đã hủy"),

    // Các trạng thái tương thích
    PENDING("Khởi tạo"),
    WAITING_PROCESS("Chờ tiếp nhận"),
    RECEIVED("Đã tiếp nhận"),
    QC_PENDING("Chờ kiểm định QC"),
    QC_CHECK("Đang kiểm định QC"),
    READY_FOR_DELIVERY("Sẵn sàng bàn giao"),
    DELIVERED("Đã bàn giao");

    private final String description;

    LabJobStatus(String description) {
        this.description = description;
    }

    /**
     * Quy tắc chuyển trạng thái hợp lệ:
     * CREATED -> QUEUED -> IN_PROGRESS -> QC_PASSED / QC_FAILED -> COMPLETED / CANCELLED.
     */
    private static final Map<LabJobStatus, Set<LabJobStatus>> ALLOWED = Map.ofEntries(
            Map.entry(CREATED, EnumSet.of(QUEUED, CANCELLED)),
            Map.entry(PENDING, EnumSet.of(QUEUED, CANCELLED)),
            Map.entry(QUEUED, EnumSet.of(IN_PROGRESS, CANCELLED)),
            Map.entry(IN_PROGRESS, EnumSet.of(QC_PASSED, QC_FAILED, CANCELLED)),
            Map.entry(QC_PASSED, EnumSet.of(COMPLETED, CANCELLED)),
            Map.entry(QC_FAILED, EnumSet.of(COMPLETED, CANCELLED, QUEUED)),
            Map.entry(COMPLETED, EnumSet.noneOf(LabJobStatus.class)),
            Map.entry(CANCELLED, EnumSet.noneOf(LabJobStatus.class)),
            Map.entry(WAITING_PROCESS, EnumSet.of(QUEUED, RECEIVED, IN_PROGRESS, CANCELLED)),
            Map.entry(RECEIVED, EnumSet.of(IN_PROGRESS, CANCELLED)),
            Map.entry(QC_PENDING, EnumSet.of(QC_PASSED, QC_FAILED, CANCELLED)),
            Map.entry(QC_CHECK, EnumSet.of(QC_PASSED, QC_FAILED, CANCELLED)),
            Map.entry(READY_FOR_DELIVERY, EnumSet.of(COMPLETED, DELIVERED)),
            Map.entry(DELIVERED, EnumSet.noneOf(LabJobStatus.class))
    );

    public boolean canTransitionTo(LabJobStatus target) {
        if (target == null) {
            return false;
        }
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    @JsonCreator
    public static LabJobStatus fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String clean = value.trim().toUpperCase();
        if ("WAITING".equals(clean) || "CHO_TIEP_NHAN".equals(clean)) {
            return QUEUED;
        }
        if ("READY_FOR_HANDOVER".equals(clean)) {
            return COMPLETED;
        }
        if ("HANDED_OVER".equals(clean)) {
            return COMPLETED;
        }
        if ("REWORK".equals(clean) || "QC_REWORK".equals(clean)) {
            return QC_FAILED;
        }
        for (LabJobStatus status : LabJobStatus.values()) {
            if (status.name().equalsIgnoreCase(clean)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Trạng thái gia công không hợp lệ: " + value);
    }
}
