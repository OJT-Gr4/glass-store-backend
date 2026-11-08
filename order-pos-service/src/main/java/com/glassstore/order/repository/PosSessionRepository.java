package com.glassstore.order.repository;

import com.glassstore.order.entity.PosSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PosSessionRepository extends JpaRepository<PosSession, Long> {
    Optional<PosSession> findBySessionCode(String sessionCode);
    List<PosSession> findByStaffNameAndStatusOrderByOpenedAtDesc(String staffName, String status);
    boolean existsByBranchNameIgnoreCase(String branchName);
}
