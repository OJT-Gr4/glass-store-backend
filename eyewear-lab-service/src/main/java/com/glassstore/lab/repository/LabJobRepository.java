package com.glassstore.lab.repository;

import com.glassstore.lab.entity.LabJob;
import com.glassstore.lab.entity.LabJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LabJobRepository extends JpaRepository<LabJob, Long> {
    Optional<LabJob> findByIdempotencyKey(String idempotencyKey);
    Optional<LabJob> findByOrderId(Long orderId);
    List<LabJob> findAllByOrderIdOrderByIdAsc(Long orderId);
    List<LabJob> findByStatusOrderByCreatedAtDesc(LabJobStatus status);
    List<LabJob> findAllByOrderByCreatedAtDesc();
    List<LabJob> findByPrescriptionIdAndPrescriptionSnapshotIsNull(Long prescriptionId);
}
