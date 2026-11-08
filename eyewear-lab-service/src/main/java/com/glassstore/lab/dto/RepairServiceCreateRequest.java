package com.glassstore.lab.dto;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public record RepairServiceCreateRequest(
        @NotBlank @Size(max = 120) String customerName,
        @NotBlank @Pattern(regexp = "^[+]?[0-9 ()-]{7,20}$") String customerPhone,
        @NotBlank @Size(max = 255) String productName,
        @Size(max = 120) String productType,
        @Size(max = 100) String productBarcode,
        @NotBlank @Size(max = 1000) String frameCondition,
        @NotBlank @Size(max = 1000) String lensCondition,
        @NotBlank @Size(max = 1000) String reportedProblem,
        @NotBlank @Size(max = 1000) String physicalCondition,
        @NotEmpty List<@NotBlank @Pattern(regexp = "NOSE_PAD_REPLACEMENT|SCREW_TIGHTENING|SCREW_REPLACEMENT|FRAME_ADJUSTMENT|TITANIUM_FRAME_ADJUSTMENT|ULTRASONIC_CLEANING|OTHER_TECHNICAL_SERVICE") String> requestedServices,
        @Size(max = 120) String assignedTechnician,
        @NotBlank @Size(max = 120) String receptionEmployee,
        @NotNull @FutureOrPresent LocalDateTime expectedPickupAt,
        @Size(max = 1000) String notes,
        @Size(max = 1000) String customerNotes,
        Long orderId,
        Long orderItemId,
        Long customerId
) {
    public RepairServiceCreateRequest(
            String customerName,
            String customerPhone,
            String productName,
            String productType,
            String productBarcode,
            String frameCondition,
            String lensCondition,
            String reportedProblem,
            String physicalCondition,
            List<String> requestedServices,
            String assignedTechnician,
            String receptionEmployee,
            LocalDateTime expectedPickupAt,
            String notes,
            String customerNotes
    ) {
        this(customerName, customerPhone, productName, productType, productBarcode, frameCondition, lensCondition,
                reportedProblem, physicalCondition, requestedServices, assignedTechnician, receptionEmployee,
                expectedPickupAt, notes, customerNotes, null, null, null);
    }
}
