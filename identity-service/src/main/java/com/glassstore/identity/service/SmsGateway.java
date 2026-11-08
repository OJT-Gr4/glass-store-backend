package com.glassstore.identity.service;

import java.time.LocalDateTime;

public interface SmsGateway {
    void sendOtp(String phoneNumber, String otpCode, String purpose, LocalDateTime expiresAt);
}

