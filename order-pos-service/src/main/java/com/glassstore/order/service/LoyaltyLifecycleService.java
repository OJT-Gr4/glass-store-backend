package com.glassstore.order.service;

import com.glassstore.order.entity.*;
import com.glassstore.order.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.math.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class LoyaltyLifecycleService {
    private final LoyaltyAccountRepository accounts;
    private final LoyaltyEntryRepository entries;
    public static LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")).truncatedTo(java.time.temporal.ChronoUnit.SECONDS); }
    public static long decay(long balance) {
        return balance<=0?0:BigDecimal.valueOf(balance).multiply(new BigDecimal("0.05")).setScale(0,RoundingMode.CEILING).longValueExact();
    }
    private LoyaltyAccount locked(Long id, LocalDateTime at) {
        accounts.ensureRow(id);
        LoyaltyAccount account=accounts.lockByCustomerId(id).orElseThrow();
        if(account.getProtectionStartedAt()==null) {
            account.setProtectionStartedAt(at);account.setNextDecayAt(at.plusMonths(6));
            account.setBaselineTierPoints(account.getBalance());account.setTierPoints(account.getBalance());
            account.setTierCode(VoucherEntitlementService.tier(account.getBalance()));account.setTierProtectedUntil(at.plusMonths(12));
        }
        return account;
    }
    @Transactional
    public LoyaltyAccount current(Long id) {
        var account=locked(id,now());refreshTier(account,now(),false);return accounts.save(account);
    }
    private LoyaltyEntry entry(Long id, Long orderId, String type, long points, String key, String reason, LocalDateTime at) {
        var entry=new LoyaltyEntry();entry.setCustomerId(id);entry.setOrderId(orderId);entry.setEntryType(type);
        entry.setPoints(points);entry.setEventKey(key);entry.setReason(reason);entry.setCreatedAt(at);return entries.save(entry);
    }
    @Transactional
    public void purchase(Order order) {
        if(order.getCustomerId()==null || order.getId()==null || order.getFinalAmount()==null || order.getFinalAmount().signum()<=0)return;
        var at=now();var account=locked(order.getCustomerId(),at);
        String key="PURCHASE:"+order.getId();
        if(entries.findByEventKey(key).isPresent())return;
        entry(account.getCustomerId(),order.getId(),"PURCHASE_ACTIVITY",0,key,"Mua hàng đã thanh toán",at);
        touch(account,at);accounts.save(account);
    }
    private void touch(LoyaltyAccount account,LocalDateTime at) {
        if(account.getLastActivityAt()==null || at.isAfter(account.getLastActivityAt()))account.setLastActivityAt(at);
        LocalDateTime anchor=account.getLastActivityAt().isAfter(account.getProtectionStartedAt())?account.getLastActivityAt():account.getProtectionStartedAt();
        account.setNextDecayAt(anchor.plusMonths(6));
    }
    @Transactional
    public void completedService(String key,Long customerId,LocalDateTime completedAt) {
        var at=now();
        if(customerId==null || completedAt==null || completedAt.isAfter(at.plusMinutes(5)))return;
        var account=locked(customerId,at);
        String eventKey="SERVICE:"+key;
        if(entries.findByEventKey(eventKey).isPresent())return;
        entry(customerId,null,"SERVICE_ACTIVITY",0,eventKey,"Dịch vụ chăm sóc kính đã hoàn tất: "+key,completedAt);
        // Receiving historical events must never shorten an already extended protection period.
        if(account.getLastActivityAt()==null || completedAt.isAfter(account.getLastActivityAt()))touch(account,completedAt);
        accounts.save(account);
    }
    @Transactional
    public void refresh(Long id) { var at=now();var a=locked(id,at);refreshTier(a,at,false);accounts.save(a); }
    private void refreshTier(LoyaltyAccount a,LocalDateTime at,boolean refund) {
        List<LoyaltyEntry> rows=entries.findByCustomerIdOrderByCreatedAtDesc(a.getCustomerId());
        Set<Long> reversed=new HashSet<>();rows.stream().filter(e->"EARN_REVERSAL".equals(e.getEntryType())).forEach(e->reversed.add(e.getOrderId()));
        LocalDateTime cutoff=at.minusMonths(12);
        long points=a.getProtectionStartedAt().isAfter(cutoff)?Objects.requireNonNullElse(a.getBaselineTierPoints(),0L):0L;
        for(var e:rows)if("EARN".equals(e.getEntryType()) && !e.isVoided() && !reversed.contains(e.getOrderId())
                && !e.getCreatedAt().isBefore(cutoff) && !e.getCreatedAt().isBefore(a.getProtectionStartedAt()))points=Math.addExact(points,e.getPoints());
        a.setTierPoints(Math.max(0,points));
        String desired=VoucherEntitlementService.tier(a.getTierPoints());
        List<String> ranks=List.of("MEMBER","SILVER","GOLD","DIAMOND");
        if(ranks.indexOf(desired)>ranks.indexOf(a.getTierCode()) || refund || a.getTierProtectedUntil()==null || !at.isBefore(a.getTierProtectedUntil())) {
            a.setTierCode(desired);a.setTierProtectedUntil(at.plusMonths(12));
        }
    }
    @Transactional
    public void refund(Order order) {
        if(order.getCustomerId()==null)return;
        var at=now();var account=locked(order.getCustomerId(),at);
        String refundKey="REFUND_ACTIVITY:"+order.getId();
        if(entries.findByEventKey(refundKey).isPresent())return;
        if(entries.findByEventKey("PURCHASE:"+order.getId()).isEmpty() && entries.findByOrderIdAndEntryType(order.getId(),"EARN").isEmpty())return;
        entry(order.getCustomerId(),order.getId(),"REFUND_ACTIVITY",0,refundKey,"Hủy hoạt động tích lũy của đơn đã hoàn/hủy",at);
        entries.findByEventKey("PURCHASE:"+order.getId()).ifPresent(e->{e.setVoided(true);entries.save(e);});
        entries.findByOrderIdAndEntryType(order.getId(),"EARN").filter(e->e.getCreatedAt().isBefore(account.getProtectionStartedAt())).ifPresent(e->
                account.setBaselineTierPoints(Math.max(0,Objects.requireNonNullElse(account.getBaselineTierPoints(),0L)-e.getPoints())));
        LocalDateTime latest=entries.findByCustomerIdOrderByCreatedAtDesc(account.getCustomerId()).stream()
                .filter(e->!e.isVoided() && Set.of("PURCHASE_ACTIVITY","SERVICE_ACTIVITY").contains(e.getEntryType()))
                .map(LoyaltyEntry::getCreatedAt).max(LocalDateTime::compareTo).orElse(null);
        account.setLastActivityAt(latest);
        LocalDateTime anchor=latest!=null && latest.isAfter(account.getProtectionStartedAt())?latest:account.getProtectionStartedAt();
        // Refunds do not extend protection as purchases; keep a warning window if it is already due.
        LocalDateTime due=anchor.plusMonths(6);account.setNextDecayAt(due.isBefore(at.plusDays(15))?at.plusDays(15):due);
        refreshTier(account,at,true);accounts.save(account);
    }
    private void reminder(LoyaltyAccount a,LocalDateTime due,LocalDateTime at) {
        String key="LOYALTY_REMINDER:"+a.getCustomerId()+":"+due;
        if(entries.findByEventKey(key).isPresent())return;
        var notice=entry(a.getCustomerId(),null,"EXPIRY_REMINDER",0,key,"Nhắc bảo lưu điểm: dự kiến trừ "+decay(a.getBalance())+" điểm vào "+due.toLocalDate(),at);
        notice.setCycleAt(due);notice.setNoticeStatus("PENDING");entries.save(notice);
    }
    @Transactional
    public void processAt(Long id,LocalDateTime at) {
        var a=locked(id,at);refreshTier(a,at,false);
        LocalDateTime due=a.getNextDecayAt();
        if(a.getBalance()>0 && !at.isBefore(due)) {
            var warned=entries.findByEventKey("LOYALTY_REMINDER:"+id+":"+due);
            if(warned.isEmpty()) { due=at.plusDays(15);a.setNextDecayAt(due);reminder(a,due,at); }
            else {
                String key="LOYALTY_DECAY:"+id+":"+due;
                if(entries.findByEventKey(key).isEmpty()) {
                    long deduction=decay(a.getBalance());a.setBalance(Math.max(0,a.getBalance()-deduction));
                    var e=entry(id,null,"INACTIVITY_DECAY",-deduction,key,"Giảm 5% điểm do không có hoạt động hợp lệ trong thời gian bảo lưu",at);
                    e.setCycleAt(due);e.setNoticeStatus("PENDING");entries.save(e);
                }
                due=due.plusMonths(1);a.setNextDecayAt(due);
            }
        }
        if(a.getBalance()>0 && !at.isBefore(due.minusDays(15)) && at.isBefore(due))reminder(a,due,at);
        accounts.save(a);
    }
}
