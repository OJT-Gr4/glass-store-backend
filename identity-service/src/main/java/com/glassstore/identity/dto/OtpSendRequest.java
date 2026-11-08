package com.glassstore.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OtpSendRequest {
    @Size(max = 100)
    private String identifier;

    @Size(max = 100)
    private String email;

    @Size(max = 20)
    private String phoneNumber;

    @NotBlank(message = "Mục đích gửi OTP không được để trống")
    @Size(max = 32)
    private String purpose;

    public String getEffectiveIdentifier() {
        if (identifier != null && !identifier.isBlank()) {
            return identifier.trim();
        }
        if (email != null && !email.isBlank()) {
            return email.trim();
        }
        return null;
    }
}

