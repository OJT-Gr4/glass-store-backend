package com.glassstore.identity.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateNotificationChannelConfigRequest {
    private Boolean smsEnabled;
    private Boolean emailEnabled;

    @Min(value = 5, message = "Chu kỳ polling tối thiểu 5 giây")
    @Max(value = 120, message = "Chu kỳ polling tối đa 120 giây")
    private Integer pollIntervalSeconds;
}
