package com.glassstore.identity.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationChannelConfigDTO {
    private boolean inAppEnabled;
    private String inAppStatus;
    private boolean smsEnabled;
    private boolean smsProviderConfigured;
    private String smsProviderStatus;
    private boolean emailEnabled;
    private boolean emailProviderConfigured;
    private String emailProviderStatus;
    private int pollIntervalSeconds;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
