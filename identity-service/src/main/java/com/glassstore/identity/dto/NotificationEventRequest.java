package com.glassstore.identity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEventRequest {

    @NotBlank(message = "eventKey không được để trống")
    private String eventKey;

    @NotBlank(message = "eventType không được để trống")
    private String eventType;

    @NotBlank(message = "category không được để trống")
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
