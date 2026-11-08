package com.glassstore.lab.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Getter;

@Getter
public enum QcResult {
    PASS("Đạt chuẩn"),
    FAIL("Không đạt"),
    REWORK("Cần mài lại");

    private final String description;

    QcResult(String description) {
        this.description = description;
    }

    @JsonCreator
    public static QcResult fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String clean = value.trim().toUpperCase();
        if ("PASSED".equals(clean) || "QC_PASSED".equals(clean) || "DAT".equals(clean)) {
            return PASS;
        }
        if ("FAILED".equals(clean) || "QC_FAILED".equals(clean) || "HONG".equals(clean) || "LOI".equals(clean)) {
            return FAIL;
        }
        if ("REWORK".equals(clean) || "QC_REWORK".equals(clean) || "MAI_LAI".equals(clean)) {
            return REWORK;
        }
        for (QcResult result : values()) {
            if (result.name().equalsIgnoreCase(clean)) {
                return result;
            }
        }
        throw new IllegalArgumentException("Kết quả QC không hợp lệ (hỗ trợ PASS, FAIL, REWORK): " + value);
    }
}

