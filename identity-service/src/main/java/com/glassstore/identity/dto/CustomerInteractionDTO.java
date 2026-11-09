package com.glassstore.identity.dto;

import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerInteractionDTO {
    private Long id;
    private Long customerId;
    private Long staffId;
    private String staffName;
    private String channel;
    private String content;
    private LocalDateTime createdAt;
}
