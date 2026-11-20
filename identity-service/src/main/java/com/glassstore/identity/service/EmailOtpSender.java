package com.glassstore.identity.service;

import java.time.LocalDateTime;

public interface EmailOtpSender {
    void sendOtpEmail(String recipientEmail, String recipientName, String otpCode, String purpose, LocalDateTime expiresAt);
}
