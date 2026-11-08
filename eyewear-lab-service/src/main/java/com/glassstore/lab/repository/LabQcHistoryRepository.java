package com.glassstore.lab.repository;

import com.glassstore.lab.entity.LabQcHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LabQcHistoryRepository extends JpaRepository<LabQcHistory, Long> {

    List<LabQcHistory> findByLabJobIdOrderByInspectedAtAsc(Long labJobId);

    List<LabQcHistory> findByOrderIdOrderByInspectedAtAsc(Long orderId);

    Optional<LabQcHistory> findByIdempotencyKey(String idempotencyKey);
}

