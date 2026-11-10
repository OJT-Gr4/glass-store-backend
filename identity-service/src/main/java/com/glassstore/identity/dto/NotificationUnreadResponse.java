package com.glassstore.identity.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationUnreadResponse {
    private Long ownerUserId;
    private long unreadCount;
    private int pollIntervalSeconds;
    private LocalDateTime latestCreatedAt;
}
