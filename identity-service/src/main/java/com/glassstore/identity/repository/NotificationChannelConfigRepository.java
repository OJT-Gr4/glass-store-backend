package com.glassstore.identity.repository;

import com.glassstore.identity.entity.NotificationChannelConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationChannelConfigRepository extends JpaRepository<NotificationChannelConfig, Long> {
}
