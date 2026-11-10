package com.glassstore.identity.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OtpSendResponse {
    private String challengeId;
    private String purpose;
    private String maskedPhone;
    private int expiresInSeconds;
    private LocalDateTime expiresAt;
    private int maxAttempts;
}

