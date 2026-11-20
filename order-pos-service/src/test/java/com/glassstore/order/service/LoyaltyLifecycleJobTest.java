package com.glassstore.order.service;
import com.glassstore.order.client.*;
import com.glassstore.order.entity.*;
import com.glassstore.order.repository.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class LoyaltyLifecycleJobTest {
    final LoyaltyLifecycleService lifecycle=mock(LoyaltyLifecycleService.class);
    final LoyaltyAccountRepository accounts=mock(LoyaltyAccountRepository.class);
    final LoyaltyEntryRepository entries=mock(LoyaltyEntryRepository.class);
    final LabClient lab=mock(LabClient.class);
    final IdentityNotificationClient notifications=mock(IdentityNotificationClient.class);
    final LoyaltyLifecycleJob job=new LoyaltyLifecycleJob(lifecycle,accounts,entries,lab,notifications);
    private LoyaltyEntry setup() {
        var due=LoyaltyLifecycleService.now().plusDays(15);
        var a=new LoyaltyAccount();a.setCustomerId(1L);a.setBalance(100);a.setNextDecayAt(due);
        var e=new LoyaltyEntry();e.setId(2L);e.setCustomerId(1L);e.setEntryType("EXPIRY_REMINDER");e.setCycleAt(due);e.setEventKey("REMINDER:1");e.setNoticeStatus("PENDING");
        when(entries.findTop100ByNoticeStatusOrderByCreatedAtAsc("PENDING")).thenReturn(List.of(e));when(accounts.findById(1L)).thenReturn(Optional.of(a));return e;
    }
    @Test void deliveryFailureRemainsPendingAndRetryKeepsTheSameDeduplicationKey() {
        var e=setup();when(notifications.publishEvent(any())).thenThrow(new IllegalStateException("offline")).thenReturn(Map.of("code",200));
        job.sendPendingNotices();assertEquals("PENDING",e.getNoticeStatus());assertNull(e.getNotificationSentAt());
        job.sendPendingNotices();assertEquals("SENT",e.getNoticeStatus());assertNotNull(e.getNotificationSentAt());
        verify(notifications,times(2)).publishEvent(argThat(payload->payload.getEventKey().equals("REMINDER:1") && payload.getCustomerId()==1L && payload.getTargetRoles().isEmpty()));
    }
    @Test void noAcknowledgementDoesNotLoseNotice() {
        var e=setup();job.sendPendingNotices();assertEquals("PENDING",e.getNoticeStatus());
    }
    @Test void purchaseMakesOldReminderObsolete() {
        var e=setup();accounts.findById(1L).orElseThrow().setNextDecayAt(e.getCycleAt().plusMonths(6));
        job.sendPendingNotices();assertEquals("CANCELLED",e.getNoticeStatus());verifyNoInteractions(notifications);
    }
    @Test void temporaryLabFailurePostponesDeductionUntilCompletedCareCanBeVerified() {
        when(lab.getLoyaltyActivities()).thenThrow(new IllegalStateException("offline"));
        var a=new LoyaltyAccount();a.setCustomerId(1L);when(accounts.findAll()).thenReturn(List.of(a));
        job.run();verify(lifecycle,never()).processAt(any(),any());
    }
}
