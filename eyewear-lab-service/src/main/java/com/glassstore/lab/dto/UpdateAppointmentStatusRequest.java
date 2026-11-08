package com.glassstore.lab.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class UpdateAppointmentStatusRequest {
    @NotBlank @Pattern(regexp = "IN_EXAM|COMPLETED|CANCELLED") private String status;
    @NotNull @PositiveOrZero private Long expectedVersion;
    @Size(max = 100) private String roomName;
    @Size(max = 255) private String examStep;
    @Positive private Long prescriptionId;
}
