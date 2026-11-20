package com.glassstore.order.repository;
import com.glassstore.order.entity.LoyaltyEntry;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface LoyaltyEntryRepository extends JpaRepository<LoyaltyEntry,Long> {
 List<LoyaltyEntry> findByCustomerIdOrderByCreatedAtDesc(Long customerId);
 Optional<LoyaltyEntry> findByOrderIdAndEntryType(Long orderId, String entryType);
 Optional<LoyaltyEntry> findByEventKey(String eventKey);
 List<LoyaltyEntry> findTop100ByNoticeStatusOrderByCreatedAtAsc(String noticeStatus);
}

