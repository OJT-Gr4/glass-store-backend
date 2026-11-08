package com.glassstore.lab.dto;

import com.glassstore.lab.entity.LabJobStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateJobStatusRequest {

    @NotNull(message = "Trạng thái gia công không được để trống")
    private LabJobStatus status;

    private Long technicianId;

    private String technicianName;

    private String notes;

    private String qcNotes;

    private String technicianNotes;

    @Builder.Default
    private Boolean materialDamaged = false;

    private String idempotencyKey;

    public String getEffectiveQcNotes() {
        if (qcNotes != null && !qcNotes.trim().isEmpty()) {
            return qcNotes.trim();
        }
        return (notes != null && !notes.trim().isEmpty()) ? notes.trim() : null;
    }

    public String getEffectiveTechnicianNotes() {
        if (technicianNotes != null && !technicianNotes.trim().isEmpty()) {
            return technicianNotes.trim();
        }
        return (notes != null && !notes.trim().isEmpty()) ? notes.trim() : null;
    }
}

