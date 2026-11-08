package com.glassstore.identity.service;

import com.glassstore.identity.dto.*;

public interface OtpService {
    OtpSendResponse sendOtp(OtpSendRequest request);
    OtpVerifyResponse verifyPhoneOtp(OtpVerifyRequest request);
    OtpVerifyResponse resetPassword(PasswordResetRequest request);
}

