package com.glassstore.lab.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Yêu cầu mài lại cho lệnh gia công kính (FR-LAB-07/08/10).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReworkJobRequest {

    @NotBlank(message = "Phải ghi rõ lý do khi yêu cầu mài lại kính")
    private String reason;

    private Long technicianId;

    private String technicianName;

    private String notes;

    private String idempotencyKey;
}

