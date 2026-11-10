package com.glassstore.lab.repository;

import com.glassstore.lab.entity.WarrantyClaimHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WarrantyClaimHistoryRepository extends JpaRepository<WarrantyClaimHistory, Long> {
    List<WarrantyClaimHistory> findByWarrantyIdOrderByChangedAtAsc(Long warrantyId);
}

