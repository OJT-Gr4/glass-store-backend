package com.glassstore.identity.service.impl;

import com.glassstore.identity.dto.*;
import com.glassstore.identity.entity.NotificationChannelConfig;
import com.glassstore.identity.entity.Role;
import com.glassstore.identity.entity.TransactionNotification;
import com.glassstore.identity.entity.User;
import com.glassstore.identity.repository.NotificationChannelConfigRepository;
import com.glassstore.identity.repository.TransactionNotificationRepository;
import com.glassstore.identity.repository.UserRepository;
import com.glassstore.identity.service.NotificationChannelDispatcher;
import com.glassstore.identity.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    private final TransactionNotificationRepository notificationRepository;
    private final NotificationChannelConfigRepository channelConfigRepository;
    private final UserRepository userRepository;
    private final NotificationChannelDispatcher channelDispatcher;
    private final PlatformTransactionManager transactionManager;

    @Override
    public List<TransactionNotificationDTO> publishEvent(NotificationEventRequest request) {
        if (request == null || request.getEventKey() == null || request.getEventKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "eventKey không được để trống");
        }

        NotificationChannelConfig config = ensureChannelConfig();
        Map<Long, RecipientTarget> recipients = resolveRecipients(request);
        if (recipients.isEmpty()) {
            return List.of();
        }

        List<TransactionNotificationDTO> savedItems = new ArrayList<>();
        String normalizedEventKey = request.getEventKey().trim();

        for (RecipientTarget target : recipients.values()) {
            String dedupKey = normalizedEventKey + ":USER:" + target.user().getId();
            Optional<TransactionNotification> existing = notificationRepository.findByDedupKey(dedupKey);
            if (existing.isPresent()) {
                savedItems.add(TransactionNotificationDTO.from(existing.get()));
                continue;
            }

            TransactionNotification entity = buildNotification(request, config, target, dedupKey);
            TransactionNotification persisted = saveIdempotently(entity, dedupKey);
            savedItems.add(TransactionNotificationDTO.from(persisted));
        }

        return savedItems;
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationInboxResponse getInbox(Long currentUserId, Long requestedUserId, Boolean unreadOnly, String category) {
        requireOwnerAccess(currentUserId, requestedUserId);

        List<TransactionNotification> rows = Boolean.TRUE.equals(unreadOnly)
                ? notificationRepository.findByRecipientUserIdAndReadFalseOrderByCreatedAtDescIdDesc(currentUserId)
                : notificationRepository.findByRecipientUserIdOrderByCreatedAtDescIdDesc(currentUserId);

        if (category != null && !category.isBlank() && !"ALL".equalsIgnoreCase(category.trim())) {
            String catFilter = category.trim().toUpperCase();
            rows = rows.stream()
                    .filter(item -> catFilter.equalsIgnoreCase(item.getCategory()))
                    .toList();
        }

        NotificationChannelConfigDTO channels = getChannelConfig();
        long unreadCount = notificationRepository.countByRecipientUserIdAndReadFalse(currentUserId);

        return NotificationInboxResponse.builder()
                .ownerUserId(currentUserId)
                .unreadCount(unreadCount)
                .pollIntervalSeconds(channels.getPollIntervalSeconds())
                .channels(channels)
                .items(rows.stream().map(TransactionNotificationDTO::from).toList())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationUnreadResponse getUnreadSummary(Long currentUserId) {
        if (currentUserId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Chưa xác thực người dùng");
        }
        NotificationChannelConfig config = ensureChannelConfig();
        long unreadCount = notificationRepository.countByRecipientUserIdAndReadFalse(currentUserId);
        LocalDateTime latestCreatedAt = notificationRepository
                .findTopByRecipientUserIdOrderByCreatedAtDescIdDesc(currentUserId)
                .map(TransactionNotification::getCreatedAt)
                .orElse(null);

        return NotificationUnreadResponse.builder()
                .ownerUserId(currentUserId)
                .unreadCount(unreadCount)
                .pollIntervalSeconds(config.getPollIntervalSeconds())
                .latestCreatedAt(latestCreatedAt)
                .build();
    }

    @Override
    @Transactional
    public TransactionNotificationDTO markAsRead(Long notificationId, Long currentUserId) {
        if (currentUserId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Chưa xác thực người dùng");
        }
        TransactionNotification entity = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thông báo #" + notificationId));

        if (!Objects.equals(entity.getRecipientUserId(), currentUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền đọc hoặc cập nhật thông báo của người dùng khác!");
        }

        if (!entity.isRead()) {
            entity.setRead(true);
            entity.setReadAt(LocalDateTime.now());
            entity = notificationRepository.saveAndFlush(entity);
        }

        return TransactionNotificationDTO.from(entity);
    }

    @Override
    @Transactional
    public Map<String, Object> markAllAsRead(Long currentUserId) {
        if (currentUserId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Chưa xác thực người dùng");
        }
        int updated = notificationRepository.markAllReadForRecipient(currentUserId, LocalDateTime.now());
        long remaining = notificationRepository.countByRecipientUserIdAndReadFalse(currentUserId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ownerUserId", currentUserId);
        result.put("updatedCount", updated);
        result.put("unreadCount", remaining);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationChannelConfigDTO getChannelConfig() {
        return toChannelDto(ensureChannelConfig());
    }

    @Override
    @Transactional
    public NotificationChannelConfigDTO updateChannelConfig(UpdateNotificationChannelConfigRequest request, String updatedBy) {
        NotificationChannelConfig config = ensureChannelConfig();
        if (request != null) {
            if (request.getSmsEnabled() != null) {
                config.setSmsEnabled(request.getSmsEnabled());
            }
            if (request.getEmailEnabled() != null) {
                config.setEmailEnabled(request.getEmailEnabled());
            }
            if (request.getPollIntervalSeconds() != null) {
                int clamped = Math.max(5, Math.min(120, request.getPollIntervalSeconds()));
                config.setPollIntervalSeconds(clamped);
            }
        }
        config.setInAppEnabled(true);
        config.setUpdatedBy(updatedBy != null && !updatedBy.isBlank() ? updatedBy : "ADMIN");
        config.setUpdatedAt(LocalDateTime.now());
        NotificationChannelConfig saved = channelConfigRepository.saveAndFlush(config);
        return toChannelDto(saved);
    }

    private NotificationChannelConfig ensureChannelConfig() {
        return channelConfigRepository.findById(NotificationChannelConfig.DEFAULT_ID)
                .orElseGet(() -> {
                    NotificationChannelConfig created = NotificationChannelConfig.builder()
                            .id(NotificationChannelConfig.DEFAULT_ID)
                            .inAppEnabled(true)
                            .smsEnabled(true)
                            .emailEnabled(false)
                            .pollIntervalSeconds(15)
                            .updatedBy("SYSTEM")
                            .updatedAt(LocalDateTime.now())
                            .build();
                    try {
                        return saveChannelConfigNewTx(created);
                    } catch (DataIntegrityViolationException ignored) {
                        return channelConfigRepository.findById(NotificationChannelConfig.DEFAULT_ID).orElse(created);
                    }
                });
    }

    private NotificationChannelConfig saveChannelConfigNewTx(NotificationChannelConfig config) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(status -> channelConfigRepository.saveAndFlush(config));
    }

    private TransactionNotification saveIdempotently(TransactionNotification entity, String dedupKey) {
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return tx.execute(status -> notificationRepository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException ex) {
            log.info("Deduplicated transaction notification with dedupKey={}", dedupKey);
            return notificationRepository.findByDedupKey(dedupKey)
                    .orElseThrow(() -> ex);
        }
    }

    private TransactionNotification buildNotification(
            NotificationEventRequest request,
            NotificationChannelConfig config,
            RecipientTarget target,
            String dedupKey) {
        User user = target.user();
        boolean customerRecipient = target.customerRecipient();

        String resolvedTitle = customerRecipient
                ? firstNonBlank(request.getCustomerTitle(), request.getTitle(), "Cập nhật giao dịch")
                : firstNonBlank(request.getStaffTitle(), request.getTitle(), "Cập nhật giao dịch");

        String resolvedMessage = customerRecipient
                ? firstNonBlank(request.getCustomerMessage(), request.getMessage(), resolvedTitle)
                : firstNonBlank(request.getStaffMessage(), request.getMessage(), resolvedTitle);

        NotificationChannelDispatcher.DispatchOutcome smsOutcome = channelDispatcher.dispatchSms(
                config.isSmsEnabled(),
                customerRecipient,
                user.getPhoneNumber(),
                request.getReferenceCode(),
                resolvedTitle,
                resolvedMessage);

        NotificationChannelDispatcher.DispatchOutcome emailOutcome = channelDispatcher.dispatchEmail(
                config.isEmailEnabled(),
                customerRecipient,
                user.getEmail(),
                request.getReferenceCode(),
                resolvedTitle,
                resolvedMessage);

        return TransactionNotification.builder()
                .dedupKey(dedupKey)
                .recipientUserId(user.getId())
                .recipientRole(target.roleName())
                .category(request.getCategory().trim().toUpperCase())
                .eventType(request.getEventType().trim().toUpperCase())
                .referenceType(request.getReferenceType())
                .referenceId(request.getReferenceId())
                .referenceCode(request.getReferenceCode())
                .title(resolvedTitle)
                .message(resolvedMessage)
                .actionTab(request.getActionTab())
                .actionRoute(request.getActionRoute())
                .read(false)
                .inAppStatus(NotificationChannelDispatcher.STATUS_DELIVERED)
                .smsStatus(smsOutcome.status())
                .smsDetail(smsOutcome.detail())
                .emailStatus(emailOutcome.status())
                .emailDetail(emailOutcome.detail())
                .sourceService(request.getSourceService())
                .createdAt(LocalDateTime.now())
                .build();
    }

    private Map<Long, RecipientTarget> resolveRecipients(NotificationEventRequest request) {
        Map<Long, RecipientTarget> map = new LinkedHashMap<>();

        if (request.getCustomerId() != null) {
            userRepository.findById(request.getCustomerId()).ifPresent(user -> {
                if (isActive(user)) {
                    map.put(user.getId(), new RecipientTarget(user, true, "ROLE_CUSTOMER"));
                }
            });
        }

        if (request.getExplicitUserIds() != null) {
            for (Long uid : request.getExplicitUserIds()) {
                if (uid == null || map.containsKey(uid)) continue;
                userRepository.findById(uid).ifPresent(user -> {
                    if (isActive(user)) {
                        boolean isCust = hasOnlyCustomerRole(user);
                        map.put(user.getId(), new RecipientTarget(user, isCust, primaryRoleOf(user)));
                    }
                });
            }
        }

        if (request.getTargetRoles() != null) {
            for (String roleName : request.getTargetRoles()) {
                if (roleName == null || roleName.isBlank()) continue;
                String normalizedRole = roleName.startsWith("ROLE_") ? roleName : "ROLE_" + roleName;
                for (User user : userRepository.findByRoleName(normalizedRole)) {
                    if (isActive(user) && !map.containsKey(user.getId())) {
                        boolean isCust = "ROLE_CUSTOMER".equals(normalizedRole) && hasOnlyCustomerRole(user);
                        map.put(user.getId(), new RecipientTarget(user, isCust, normalizedRole));
                    }
                }
            }
        }

        return map;
    }

    private void requireOwnerAccess(Long currentUserId, Long requestedUserId) {
        if (currentUserId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Chưa xác thực người dùng");
        }
        if (requestedUserId != null && !Objects.equals(requestedUserId, currentUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không được phép xem hộp thư thông báo của người dùng khác!");
        }
    }

    private NotificationChannelConfigDTO toChannelDto(NotificationChannelConfig config) {
        boolean smsReady = channelDispatcher.isSmsProviderConfigured();
        boolean emailReady = channelDispatcher.isEmailProviderConfigured();
        return NotificationChannelConfigDTO.builder()
                .inAppEnabled(config.isInAppEnabled())
                .inAppStatus("ACTIVE")
                .smsEnabled(config.isSmsEnabled())
                .smsProviderConfigured(smsReady)
                .smsProviderStatus(smsReady
                        ? "CONFIGURED"
                        : NotificationChannelDispatcher.STATUS_BLOCKED_UNCONFIGURED)
                .emailEnabled(config.isEmailEnabled())
                .emailProviderConfigured(emailReady)
                .emailProviderStatus(emailReady
                        ? "CONFIGURED"
                        : NotificationChannelDispatcher.STATUS_BLOCKED_UNCONFIGURED)
                .pollIntervalSeconds(config.getPollIntervalSeconds())
                .updatedBy(config.getUpdatedBy())
                .updatedAt(config.getUpdatedAt())
                .build();
    }

    private static boolean isActive(User user) {
        return user.getStatus() == null || "ACTIVE".equalsIgnoreCase(user.getStatus());
    }

    private static boolean hasOnlyCustomerRole(User user) {
        if (user.getRoles() == null || user.getRoles().isEmpty()) return false;
        return user.getRoles().stream().allMatch(r -> "ROLE_CUSTOMER".equals(r.getName()));
    }

    private static String primaryRoleOf(User user) {
        if (user.getRoles() == null || user.getRoles().isEmpty()) return "ROLE_USER";
        return user.getRoles().stream().map(Role::getName).findFirst().orElse("ROLE_USER");
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }

    private record RecipientTarget(User user, boolean customerRecipient, String roleName) {}
}
