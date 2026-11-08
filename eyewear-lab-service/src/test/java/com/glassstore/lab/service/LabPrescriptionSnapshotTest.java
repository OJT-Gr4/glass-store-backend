package com.glassstore.lab.service;

import com.glassstore.lab.entity.*;
import com.glassstore.lab.dto.LabJobResponse;
import com.glassstore.lab.repository.LabJobRepository;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LabPrescriptionSnapshotTest {
    @Test void committedJobReadsOriginalMeasurementsAfterClinicalEdit() {
        var rx = Prescription.builder().id(7L).version(0L).odSph(new BigDecimal("-2.00")).osSph(BigDecimal.ZERO).pd(new BigDecimal("62.5")).build();
        var job = LabJob.builder().prescriptionId(7L).prescriptionSnapshot(LabPrescriptionSnapshot.from(rx)).build();
        rx.setOdSph(new BigDecimal("-3.00")); rx.setVersion(1L);
        var readBack = LabJobResponse.from(job, rx);
        assertEquals(0L, readBack.getPrescriptionVersion());
        assertEquals(new BigDecimal("-2.00"), readBack.getOdSph());
        assertEquals(new BigDecimal("62.5"), readBack.getPd());
    }

    @Test void legacyJobsFreezeBeforeSourceMutationAndKeepTheirOriginalVersion() {
        var jobs = mock(LabJobRepository.class);
        var legacy = LabJob.builder().prescriptionId(7L).build();
        var previous = Prescription.builder().id(7L).version(0L).odSph(new BigDecimal("-2.00")).build();
        when(jobs.findByPrescriptionIdAndPrescriptionSnapshotIsNull(7L)).thenReturn(List.of(legacy));
        new LabPrescriptionSnapshotService(jobs).freezeLegacyJobs(previous);
        previous.setOdSph(new BigDecimal("-4.00")); previous.setVersion(1L);
        assertEquals(new BigDecimal("-2.00"), legacy.getPrescriptionSnapshot().getOdSph());
        assertEquals(0L, legacy.getPrescriptionSnapshot().getVersion());
        verify(jobs).save(legacy);
    }
}
