package com.glassstore.order.service;
import com.glassstore.order.client.*;
import com.glassstore.order.dto.NotificationEventPayload;
import com.glassstore.order.entity.*;
import com.glassstore.order.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.*;

@Service @RequiredArgsConstructor @Slf4j
public class LoyaltyLifecycleJob {
    private final LoyaltyLifecycleService lifecycle;
    private final LoyaltyAccountRepository accounts;
    private final LoyaltyEntryRepository entries;
    private final LabClient lab;
    private final IdentityNotificationClient notifications;
    @Scheduled(fixedDelayString="${loyalty.lifecycle.interval-ms:1800000}",initialDelayString="${loyalty.lifecycle.initial-delay-ms:600000}")
    public void run() {
        try { for(var activity:lab.getLoyaltyActivities())lifecycle.completedService(activity.key(),activity.customerId(),activity.completedAt()); }
        catch(Exception error) {
            log.warn("Loyalty service activity sync will retry; postpone decay until activities can be verified: {}",error.getMessage());
            sendPendingNotices();return;
        }
        for(var account:accounts.findAll()) {
            try { lifecycle.processAt(account.getCustomerId(),LoyaltyLifecycleService.now()); }
            catch(Exception error) { log.warn("Loyalty cycle {} will retry: {}",account.getCustomerId(),error.getMessage()); }
        }
        sendPendingNotices();
    }
    public void sendPendingNotices() {
        for(var e:entries.findTop100ByNoticeStatusOrderByCreatedAtAsc("PENDING")) {
            try {
                var a=accounts.findById(e.getCustomerId()).orElseThrow();
                boolean reminder="EXPIRY_REMINDER".equals(e.getEntryType());
                if(reminder && (a.getBalance()==0 || !Objects.equals(a.getNextDecayAt(),e.getCycleAt()))) {
                    e.setNoticeStatus("CANCELLED");entries.save(e);continue;
                }
                String title=reminder?"Điểm thân thiết sắp hết thời gian bảo lưu":"Cập nhật điểm thân thiết";
                String message=reminder?"Dự kiến giảm "+LoyaltyLifecycleService.decay(a.getBalance())+" điểm (5%) vào "+e.getCycleAt().toLocalDate()+". Mua hàng hoặc hoàn tất dịch vụ chăm sóc kính để bảo lưu thêm 6 tháng.":e.getReason()+": "+Math.abs(e.getPoints())+" điểm. Số dư hiện tại: "+a.getBalance()+" điểm.";
                var response=notifications.publishEvent(NotificationEventPayload.builder().eventKey(e.getEventKey()).eventType(e.getEntryType()).category("LOYALTY")
                        .referenceType("LOYALTY").referenceId(e.getId()).referenceCode("POINTS-"+e.getId()).title(title).message(message)
                        .customerTitle(title).customerMessage(message).customerId(e.getCustomerId()).explicitUserIds(List.of(e.getCustomerId()))
                        .targetRoles(List.of()).actionTab("vouchers").actionRoute("/vouchers").sourceService("order-pos-service").build());
                if(response==null || !(response.get("code") instanceof Number code) || code.intValue()!=200)
                    throw new IllegalStateException("Notification service did not acknowledge loyalty notice");
                e.setNoticeStatus("SENT");e.setNotificationSentAt(LoyaltyLifecycleService.now());entries.save(e);
            } catch(Exception error) { log.warn("Loyalty notice {} will retry",e.getEventKey(),error); }
        }
    }
}
