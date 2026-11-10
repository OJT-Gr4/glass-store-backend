package com.glassstore.identity.repository;

import com.glassstore.identity.entity.TransactionNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionNotificationRepository extends JpaRepository<TransactionNotification, Long> {

    Optional<TransactionNotification> findByDedupKey(String dedupKey);

    List<TransactionNotification> findByRecipientUserIdOrderByCreatedAtDescIdDesc(Long recipientUserId);

    List<TransactionNotification> findByRecipientUserIdAndReadFalseOrderByCreatedAtDescIdDesc(Long recipientUserId);

    long countByRecipientUserIdAndReadFalse(Long recipientUserId);

    Optional<TransactionNotification> findTopByRecipientUserIdOrderByCreatedAtDescIdDesc(Long recipientUserId);

    @Modifying
    @Query("UPDATE TransactionNotification n SET n.read = true, n.readAt = :readAt WHERE n.recipientUserId = :recipientUserId AND n.read = false")
    int markAllReadForRecipient(@Param("recipientUserId") Long recipientUserId, @Param("readAt") LocalDateTime readAt);
}
