package com.glassstore.identity.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationInboxResponse {
    private Long ownerUserId;
    private long unreadCount;
    private int pollIntervalSeconds;
    private NotificationChannelConfigDTO channels;
    private List<TransactionNotificationDTO> items;
}
