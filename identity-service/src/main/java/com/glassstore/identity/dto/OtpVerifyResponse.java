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
public class OtpVerifyResponse {
    private boolean verified;
    private Long userId;
    private String purpose;
    private String maskedPhone;
    private boolean phoneVerified;
    private LocalDateTime verifiedAt;
}

