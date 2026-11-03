package com.glassstore.identity.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthResponse {
    private String accessToken;
    private String refreshToken;
    @Builder.Default
    private String tokenType = "Bearer";
    @Builder.Default
    private Long expiresIn = 86400L; // 24h in seconds
    private UserDto user;
}
