package com.glassstore.identity.dto;

import com.glassstore.identity.entity.UserStatus;
import lombok.*;

import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDto {
    private Long id;
    private String username;
    private String email;
    private String fullName;
    private String phoneNumber;
    private UserStatus status;
    private Set<String> roles;
}

