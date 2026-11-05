package com.glassstore.lab.repository;

import com.glassstore.lab.entity.LabJobHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LabJobHistoryRepository extends JpaRepository<LabJobHistory, Long> {
    List<LabJobHistory> findByLabJobIdOrderByChangedAtAsc(Long labJobId);
}

