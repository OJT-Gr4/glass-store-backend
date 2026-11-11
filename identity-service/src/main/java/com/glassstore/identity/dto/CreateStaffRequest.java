package com.glassstore.identity.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateStaffRequest {
    private String fullName;
    private String email;
    private String phoneNumber;
    private String employeeCode;
    private String branchName;
    private String roleName;
    private String status;
    private String password;
    private String avatarUrl;
}
