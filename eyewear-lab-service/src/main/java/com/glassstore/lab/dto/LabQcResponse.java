package com.glassstore.lab.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.glassstore.lab.entity.LabQcHistory;
import com.glassstore.lab.entity.QcResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LabQcResponse {

    private Long id;
    private String idempotencyKey;
    private Long labJobId;
    private Long orderId;
    private Long prescriptionId;
    private QcResult qcResult;
    private String qcResultLabel;

    private Boolean powerCheckPassed;
    private Boolean opticalCenterCheckPassed;
    private Boolean surfaceQualityCheckPassed;
    private Boolean bevelEdgingCheckPassed;
    private Boolean frameFittingCheckPassed;

    private String failureReason;
    private String defectType;
    private Boolean materialDamaged;
    private String notes;
    private Long inspectorId;
    private String inspectorName;
    private Integer reworkCount;
    private LocalDateTime inspectedAt;

    public static LabQcResponse from(LabQcHistory history) {
        if (history == null) {
            return null;
        }
        return LabQcResponse.builder()
                .id(history.getId())
                .idempotencyKey(history.getIdempotencyKey())
                .labJobId(history.getLabJobId())
                .orderId(history.getOrderId())
                .prescriptionId(history.getPrescriptionId())
                .qcResult(history.getQcResult())
                .qcResultLabel(history.getQcResult() != null ? history.getQcResult().getDescription() : null)
                .powerCheckPassed(history.getPowerCheckPassed())
                .opticalCenterCheckPassed(history.getOpticalCenterCheckPassed())
                .surfaceQualityCheckPassed(history.getSurfaceQualityCheckPassed())
                .bevelEdgingCheckPassed(history.getBevelEdgingCheckPassed())
                .frameFittingCheckPassed(history.getFrameFittingCheckPassed())
                .failureReason(history.getFailureReason())
                .defectType(history.getDefectType())
                .materialDamaged(history.getMaterialDamaged())
                .notes(history.getNotes())
                .inspectorId(history.getInspectorId())
                .inspectorName(history.getInspectorName())
                .reworkCount(history.getReworkCount())
                .inspectedAt(history.getInspectedAt())
                .build();
    }
}

