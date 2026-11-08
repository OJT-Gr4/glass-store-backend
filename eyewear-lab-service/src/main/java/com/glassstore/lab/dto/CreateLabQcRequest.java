package com.glassstore.lab.dto;

import com.glassstore.lab.entity.QcResult;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Yêu cầu đánh giá kiểm định QC cho lệnh mài kính (FR-LAB-06/07/08/10).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateLabQcRequest {

    @NotNull(message = "Kết quả QC không được để trống (PASS, FAIL, REWORK)")
    private QcResult result;

    // Từng kiểm tra cần thiết (Checklist)
    private Boolean powerCheckPassed; // Kiểm tra độ khúc xạ SPH/CYL/AXIS

    private Boolean opticalCenterCheckPassed; // Kiểm tra khoảng cách đồng tử / tâm mắt PD

    private Boolean surfaceQualityCheckPassed; // Bề mặt không trầy xước, không bọt khí

    private Boolean bevelEdgingCheckPassed; // Mài vát cạnh / đường biên đạt chuẩn

    private Boolean frameFittingCheckPassed; // Lắp ráp khớp và cố định trên gọng kính

    private String failureReason; // Bắt buộc khi FAIL hoặc REWORK

    private String defectType; // Mã phân loại lỗi (lệch trục, trầy xước, nứt mép, ...)

    @Builder.Default
    private Boolean materialDamaged = false; // Phân biệt cần mài lại với thực sự hỏng vật tư

    private String notes;

    private Long inspectorId;

    private String inspectorName;

    private String idempotencyKey;

    public String getEffectiveReason() {
        if (failureReason != null && !failureReason.trim().isEmpty()) {
            return failureReason.trim();
        }
        return (notes != null && !notes.trim().isEmpty()) ? notes.trim() : null;
    }
}

