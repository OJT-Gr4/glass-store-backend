package com.glassstore.identity.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateCustomerRequest {
    private String fullName;
    private String phoneNumber;
    private String email;
    private String address;
    private String gender;
    private Boolean marketingConsent;
}
