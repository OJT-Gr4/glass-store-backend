package com.glassstore.identity.service;

import com.glassstore.identity.dto.*;

public interface AuthService {
    String register(RegisterRequest request);

    AuthResponse verifyOtp(VerifyOtpRequest request);

    String resendOtp(ResendOtpRequest request);

    AuthResponse login(LoginRequest request);

    AuthResponse refreshToken(RefreshTokenRequest request);

    void logout(String refreshToken);

    UserDto getCurrentUser(String email);
}
