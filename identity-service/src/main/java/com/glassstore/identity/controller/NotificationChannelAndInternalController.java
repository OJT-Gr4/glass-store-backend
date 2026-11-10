package com.glassstore.identity.controller;

import com.glassstore.common.dto.ApiResponse;
import com.glassstore.identity.dto.NotificationChannelConfigDTO;
import com.glassstore.identity.dto.TransactionNotificationDTO;
import com.glassstore.identity.dto.UpdateNotificationChannelConfigRequest;
import com.glassstore.identity.dto.NotificationEventRequest;
import com.glassstore.identity.security.UserPrincipal;
import com.glassstore.identity.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class NotificationChannelAndInternalController {

    private final NotificationService notificationService;

    @GetMapping({"/api/notification-channels", "/api/v1/notification-channels"})
    public ResponseEntity<ApiResponse<NotificationChannelConfigDTO>> getChannelConfig() {
        return ResponseEntity.ok(ApiResponse.success("Cấu hình kênh thông báo", notificationService.getChannelConfig()));
    }

    @PutMapping({"/api/notification-channels", "/api/v1/notification-channels"})
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<NotificationChannelConfigDTO>> updateChannelConfig(
            @Valid @RequestBody UpdateNotificationChannelConfigRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        String actor = principal != null ? principal.getUsername() : "ADMIN";
        return ResponseEntity.ok(ApiResponse.success("Cập nhật cấu hình kênh thông báo thành công",
                notificationService.updateChannelConfig(request, actor)));
    }

    @PostMapping("/internal/notifications/events")
    public ResponseEntity<ApiResponse<List<TransactionNotificationDTO>>> publishInternalEvent(
            @Valid @RequestBody NotificationEventRequest request) {
        List<TransactionNotificationDTO> created = notificationService.publishEvent(request);
        return ResponseEntity.ok(ApiResponse.success("Đã ghi nhận sự kiện thông báo", created));
    }
}
