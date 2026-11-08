package com.glassstore.lab.service;

import com.glassstore.lab.entity.LabPrescriptionSnapshot;
import com.glassstore.lab.entity.Prescription;
import com.glassstore.lab.repository.LabJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class LabPrescriptionSnapshotService {
    private final LabJobRepository jobs;

    /** Called in the prescription update transaction before changing its measurements. */
    @Transactional
    public void freezeLegacyJobs(Prescription previous) {
        jobs.findByPrescriptionIdAndPrescriptionSnapshotIsNull(previous.getId()).forEach(job -> {
            job.setPrescriptionSnapshot(LabPrescriptionSnapshot.from(previous));
            jobs.save(job);
        });
    }
}
