package com.glassstore.identity.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateCustomerRequest {
    private String fullName;
    private String phoneNumber;
    private String email;
    private String address;
    private String gender;
    private String tier;
    private Boolean marketingConsent;
}
