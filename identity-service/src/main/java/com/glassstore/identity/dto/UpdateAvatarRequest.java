package com.glassstore.identity.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateAvatarRequest {
    private String avatarUrl;
}
