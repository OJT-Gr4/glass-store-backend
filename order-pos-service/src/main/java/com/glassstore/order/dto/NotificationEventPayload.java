package com.glassstore.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEventPayload {
    private String eventKey;
    private String eventType;
    private String category;
    private String referenceType;
    private Long referenceId;
    private String referenceCode;
    private String title;
    private String message;
    private String customerTitle;
    private String customerMessage;
    private String staffTitle;
    private String staffMessage;
    private String actionTab;
    private String actionRoute;
    private String sourceService;
    private Long customerId;
    private List<Long> explicitUserIds;
    private List<String> targetRoles;
}
