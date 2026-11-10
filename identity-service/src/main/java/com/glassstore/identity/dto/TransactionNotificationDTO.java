package com.glassstore.identity.dto;

import com.glassstore.identity.entity.TransactionNotification;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionNotificationDTO {

    private Long id;
    private String dedupKey;
    private Long recipientUserId;
    private String recipientRole;
    private String category;
    private String eventType;
    private String referenceType;
    private Long referenceId;
    private String referenceCode;
    private String title;
    private String message;
    private String actionTab;
    private String actionRoute;
    private boolean read;
    private LocalDateTime readAt;
    private String inAppStatus;
    private String smsStatus;
    private String smsDetail;
    private String emailStatus;
    private String emailDetail;
    private String sourceService;
    private LocalDateTime createdAt;

    public static TransactionNotificationDTO from(TransactionNotification entity) {
        return TransactionNotificationDTO.builder()
                .id(entity.getId())
                .dedupKey(entity.getDedupKey())
                .recipientUserId(entity.getRecipientUserId())
                .recipientRole(entity.getRecipientRole())
                .category(entity.getCategory())
                .eventType(entity.getEventType())
                .referenceType(entity.getReferenceType())
                .referenceId(entity.getReferenceId())
                .referenceCode(entity.getReferenceCode())
                .title(entity.getTitle())
                .message(entity.getMessage())
                .actionTab(entity.getActionTab())
                .actionRoute(entity.getActionRoute())
                .read(entity.isRead())
                .readAt(entity.getReadAt())
                .inAppStatus(entity.getInAppStatus())
                .smsStatus(entity.getSmsStatus())
                .smsDetail(entity.getSmsDetail())
                .emailStatus(entity.getEmailStatus())
                .emailDetail(entity.getEmailDetail())
                .sourceService(entity.getSourceService())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
