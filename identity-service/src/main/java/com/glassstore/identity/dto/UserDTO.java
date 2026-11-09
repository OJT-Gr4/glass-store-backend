package com.glassstore.identity.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDTO {
    private Long id;
    private String username;
    private String email;
    private String fullName;
    private String phoneNumber;
    private boolean phoneVerified;
    private String employeeCode;
    private String branchName;
    private String status;
    private String address;
    private String gender;
    private boolean marketingConsent;
    private LocalDateTime marketingConsentUpdatedAt;
    private Set<String> roles;
    private LocalDateTime createdAt;
}
