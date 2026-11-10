package com.glassstore.identity.dto;

import lombok.*;

import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginResponse {
    private String token;
    private String refreshToken;
    @Builder.Default
    private String tokenType = "Bearer";
    @Builder.Default
    private long expiresIn = 86400; // 24 giờ tính bằng giây
    private Long id;
    private Long userId; // Alias for id
    private String username;
    private String fullName;
    private String email;
    private String phoneNumber;
    private boolean phoneVerified;
    private String employeeCode;
    private String branchName;
    private Set<String> roles;
    private String primaryRole;
    private String defaultRoute;
}
