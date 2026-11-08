package com.glassstore.lab.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** A controlled replacement: every measurement must be supplied, including explicit zero. */
@Getter
@Setter
public class UpdatePrescriptionRequest extends CreatePrescriptionRequest {
    @NotNull
    @PositiveOrZero
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictLongDeserializer.class)
    private Long expectedVersion;

    @NotBlank
    @Size(max = 500)
    private String reason;
}
