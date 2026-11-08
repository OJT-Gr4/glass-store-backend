package com.glassstore.lab.repository;

import com.glassstore.lab.entity.PrescriptionRevision;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrescriptionRevisionRepository extends JpaRepository<PrescriptionRevision, Long> {
    List<PrescriptionRevision> findByPrescriptionIdOrderByVersionDesc(Long prescriptionId);
    Optional<PrescriptionRevision> findByPrescriptionIdAndVersion(Long prescriptionId, Long version);
    boolean existsByPrescriptionIdAndVersion(Long prescriptionId, Long version);
}
