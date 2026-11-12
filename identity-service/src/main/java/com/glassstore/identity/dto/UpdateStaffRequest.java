package com.glassstore.identity.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateStaffRequest {
    private String fullName;
    private String phoneNumber;
    private String branchName;
    private String roleName;
    private String avatarUrl;
}
