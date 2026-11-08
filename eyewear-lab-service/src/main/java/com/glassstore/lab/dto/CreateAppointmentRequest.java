package com.glassstore.lab.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

/** Public booking fields only. Workflow state and examiner come from the server. */
@Data
public class CreateAppointmentRequest {
    @Positive private Long customerId;
    @NotBlank @Size(max = 80) private String scheduledTime;
    @Size(max = 30) private String customerPhone;
    @Size(max = 100) private String roomName;
    @Size(max = 50) private String ticketType;
    @Size(max = 500) private String notes;
}
