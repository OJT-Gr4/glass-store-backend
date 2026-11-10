package com.glassstore.identity.service;

import com.glassstore.identity.dto.*;

import java.util.List;
import java.util.Map;

public interface NotificationService {

    List<TransactionNotificationDTO> publishEvent(NotificationEventRequest request);

    NotificationInboxResponse getInbox(Long currentUserId, Long requestedUserId, Boolean unreadOnly, String category);

    NotificationUnreadResponse getUnreadSummary(Long currentUserId);

    TransactionNotificationDTO markAsRead(Long notificationId, Long currentUserId);

    Map<String, Object> markAllAsRead(Long currentUserId);

    NotificationChannelConfigDTO getChannelConfig();

    NotificationChannelConfigDTO updateChannelConfig(UpdateNotificationChannelConfigRequest request, String updatedBy);
}
