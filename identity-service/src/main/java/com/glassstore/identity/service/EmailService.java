package com.glassstore.identity.service;

public interface EmailService {
    void sendOtpEmail(String toEmail, String otpCode);
}
