package com.glassstore.identity.controller;

import com.glassstore.common.dto.ApiResponse;
import com.glassstore.identity.dto.*;
import com.glassstore.identity.security.UserPrincipal;
import com.glassstore.identity.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping({"/api/notifications", "/api/v1/notifications"})
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<ApiResponse<NotificationInboxResponse>> getInbox(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(name = "recipientUserId", required = false) Long requestedUserId,
            @RequestParam(name = "unreadOnly", required = false) Boolean unreadOnly,
            @RequestParam(name = "category", required = false) String category) {
        Long currentUserId = principal != null ? principal.getId() : null;
        NotificationInboxResponse inbox = notificationService.getInbox(currentUserId, requestedUserId, unreadOnly, category);
        return ResponseEntity.ok(ApiResponse.success("Lấy danh sách thông báo thành công", inbox));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<NotificationUnreadResponse>> getUnreadSummary(
            @AuthenticationPrincipal UserPrincipal principal) {
        Long currentUserId = principal != null ? principal.getId() : null;
        return ResponseEntity.ok(ApiResponse.success("OK", notificationService.getUnreadSummary(currentUserId)));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<ApiResponse<TransactionNotificationDTO>> markAsRead(
            @PathVariable("id") Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long currentUserId = principal != null ? principal.getId() : null;
        TransactionNotificationDTO updated = notificationService.markAsRead(id, currentUserId);
        return ResponseEntity.ok(ApiResponse.success("Đã đánh dấu đã đọc", updated));
    }

    @PatchMapping("/read-all")
    public ResponseEntity<ApiResponse<Map<String, Object>>> markAllAsRead(
            @AuthenticationPrincipal UserPrincipal principal) {
        Long currentUserId = principal != null ? principal.getId() : null;
        return ResponseEntity.ok(ApiResponse.success("Đã đánh dấu tất cả là đã đọc", notificationService.markAllAsRead(currentUserId)));
    }

    @GetMapping("/channels")
    public ResponseEntity<ApiResponse<NotificationChannelConfigDTO>> getChannels() {
        return ResponseEntity.ok(ApiResponse.success("Cấu hình kênh thông báo", notificationService.getChannelConfig()));
    }

    @PutMapping("/channels")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<NotificationChannelConfigDTO>> updateChannels(
            @Valid @RequestBody UpdateNotificationChannelConfigRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        String actor = principal != null ? principal.getUsername() : "ADMIN";
        return ResponseEntity.ok(ApiResponse.success("Cập nhật cấu hình kênh thông báo thành công",
                notificationService.updateChannelConfig(request, actor)));
    }
}
