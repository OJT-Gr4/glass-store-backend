package com.glassstore.lab.service;

import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.lab.dto.CreateLabJobRequest;
import com.glassstore.lab.dto.LabJobResponse;
import com.glassstore.lab.dto.UpdateJobStatusRequest;
import com.glassstore.lab.entity.LabJob;
import com.glassstore.lab.entity.LabJobStatus;
import com.glassstore.lab.entity.Prescription;
import com.glassstore.lab.repository.LabJobRepository;
import com.glassstore.lab.repository.LabJobHistoryRepository;
import com.glassstore.lab.repository.PrescriptionRepository;
import com.glassstore.lab.service.impl.LabJobServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LabJobServiceTest {

    @Mock
    private LabJobRepository labJobRepository;

    @Mock
    private PrescriptionRepository prescriptionRepository;

    @Mock
    private LabJobHistoryRepository historyRepository;

    @InjectMocks
    private LabJobServiceImpl labJobService;

    private Prescription prescription;
    private LabJob pendingJob;

    @BeforeEach
    void setUp() {
        prescription = Prescription.builder()
                .id(100L)
                .customerId(10L)
                .customerName("Nguyễn Hoàng Duy")
                .optometristId(6L)
                .optometristName("BS. Lê Thị Ánh Tuyết")
                .odSph(new BigDecimal("-2.25"))
                .odCyl(new BigDecimal("-0.75"))
                .odAxis(175)
                .odAdd(BigDecimal.ZERO)
                .osSph(new BigDecimal("-2.00"))
                .osCyl(new BigDecimal("-0.50"))
                .osAxis(180)
                .osAdd(BigDecimal.ZERO)
                .pd(new BigDecimal("63.5"))
                .notes("Cận loạn")
                .build();

        pendingJob = LabJob.builder()
                .id(1L)
                .orderId(501L)
                .prescriptionId(100L)
                .customerName("Nguyễn Hoàng Duy")
                .lensSku("LENS-ESSILOR-160")
                .frameSku("FRAME-RAYBAN-001")
                .status(LabJobStatus.PENDING)
                .createdAt(LocalDateTime.now().minusHours(2))
                .updatedAt(LocalDateTime.now().minusHours(2))
                .build();
    }

    @Test
    @DisplayName("Returns an empty list when no jobs exist")
    void getLabJobsWhenEmpty() {
        when(labJobRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        assertThat(labJobService.getLabJobs(null)).isEmpty();
        verify(prescriptionRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("Maps prescription measurements into the job response")
    void getLabJobsMapsPrescription() {
        when(labJobRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(pendingJob));
        when(prescriptionRepository.findAllById(Set.of(100L))).thenReturn(List.of(prescription));

        List<LabJobResponse> result = labJobService.getLabJobs(null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getOdSphere()).isEqualByComparingTo("-2.25");
        assertThat(result.get(0).getOsAxis()).isEqualTo(180);
        assertThat(result.get(0).getPd()).isEqualByComparingTo("63.5");
    }

    @Test
    @DisplayName("Filters jobs by status")
    void getLabJobsFiltersByStatus() {
        when(labJobRepository.findByStatusOrderByCreatedAtDesc(LabJobStatus.PENDING))
                .thenReturn(List.of(pendingJob));

        assertThat(labJobService.getLabJobs(LabJobStatus.PENDING)).hasSize(1);
        verify(labJobRepository, never()).findAllByOrderByCreatedAtDesc();
    }

    @Test
    @DisplayName("Creates a lab job in PENDING state")
    void createLabJob() {
        when(labJobRepository.findByIdempotencyKey("LAB_REQ_502_0")).thenReturn(Optional.empty());
        when(prescriptionRepository.findById(100L)).thenReturn(Optional.of(prescription));
        when(labJobRepository.save(any(LabJob.class))).thenAnswer(invocation -> {
            LabJob job = invocation.getArgument(0);
            job.setId(10L);
            return job;
        });

        CreateLabJobRequest request = CreateLabJobRequest.builder()
                .orderId(502L)
                .customerName("Trần Văn A")
                .prescriptionId(100L)
                .lensSku("LENS-HOYA-167")
                .frameSku("FRAME-GUCCI-99")
                .build();

        LabJobResponse response = labJobService.createLabJob(request);

        assertThat(response.getId()).isEqualTo(10L);
        assertThat(response.getStatus()).isEqualTo(LabJobStatus.CREATED);
        assertThat(response.getCustomerName()).isEqualTo("Trần Văn A");
        assertThat(response.getOdSphere()).isEqualByComparingTo("-2.25");
        verify(historyRepository).save(any());
    }

    @Test
    @DisplayName("Returns the existing job for a repeated order")
    void createFromOrderIsIdempotent() {
        when(labJobRepository.findByIdempotencyKey("LAB_REQ_501_0")).thenReturn(Optional.of(pendingJob));
        when(prescriptionRepository.findById(100L)).thenReturn(Optional.of(prescription));

        LabJobResponse result = labJobService.createFromOrder(
                501L, 100L, "FRAME-RAYBAN-001", null, "LENS-ESSILOR-160", null, "Tech");

        assertThat(result.getId()).isEqualTo(1L);
        verify(labJobRepository, never()).save(any());
    }

    @Test
    @DisplayName("Rejects an incomplete prescription before fabrication")
    void incompletePrescription() {
        when(labJobRepository.findByIdempotencyKey("LAB_REQ_501_0")).thenReturn(Optional.empty());
        when(prescriptionRepository.findById(100L)).thenReturn(Optional.of(Prescription.builder()
                .id(100L)
                .customerId(10L)
                .optometristId(6L)
                .osSph(new BigDecimal("-1.00"))
                .pd(new BigDecimal("63.5"))
                .build()));

        assertThatThrownBy(() -> labJobService.createFromOrder(
                501L, 100L, "FRAME", null, "LENS", null, "Tech"))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("Starts an accepted job and records technician details")
    void updateToInProgress() {
        pendingJob.setStatus(LabJobStatus.RECEIVED);
        when(labJobRepository.findById(1L)).thenReturn(Optional.of(pendingJob));
        when(labJobRepository.save(any(LabJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(prescriptionRepository.findById(100L)).thenReturn(Optional.of(prescription));

        UpdateJobStatusRequest request = UpdateJobStatusRequest.builder()
                .status(LabJobStatus.IN_PROGRESS)
                .technicianId(25L)
                .technicianName("Nguyễn Kỹ Thuật")
                .technicianNotes("Bắt đầu mài tròng")
                .build();

        LabJobResponse response = labJobService.updateJobStatus(1L, request);

        assertThat(response.getStatus()).isEqualTo(LabJobStatus.IN_PROGRESS);
        assertThat(response.getStartedAt()).isNotNull();
        assertThat(response.getTechnicianId()).isEqualTo(25L);
        assertThat(response.getTechnicianNotes()).isEqualTo("Bắt đầu mài tròng");
    }

    @Test
    @DisplayName("Completes QC and records the completion time")
    void updateToQcPassed() {
        pendingJob.setStatus(LabJobStatus.QC_PENDING);
        when(labJobRepository.findById(1L)).thenReturn(Optional.of(pendingJob));
        when(labJobRepository.save(any(LabJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(prescriptionRepository.findById(100L)).thenReturn(Optional.of(prescription));

        UpdateJobStatusRequest request = UpdateJobStatusRequest.builder()
                .status(LabJobStatus.QC_PASSED)
                .qcNotes("Mài lắp chuẩn tâm, không trầy xước")
                .build();

        LabJobResponse response = labJobService.updateJobStatus(1L, request);

        assertThat(response.getStatus()).isEqualTo(LabJobStatus.QC_PASSED);
        assertThat(response.getCompletedAt()).isNotNull();
        assertThat(response.getQcNotes()).isEqualTo("Mài lắp chuẩn tâm, không trầy xước");
    }

    @Test
    @DisplayName("Requires a reason for QC failure")
    void qcFailureRequiresReason() {
        pendingJob.setStatus(LabJobStatus.QC_PENDING);
        when(labJobRepository.findById(1L)).thenReturn(Optional.of(pendingJob));

        UpdateJobStatusRequest request = UpdateJobStatusRequest.builder()
                .status(LabJobStatus.QC_FAILED)
                .qcNotes(" ")
                .build();

        assertThatThrownBy(() -> labJobService.updateJobStatus(1L, request))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(ErrorCode.LAB_JOB_QC_REASON_REQUIRED);
        verify(labJobRepository, never()).save(any());
    }

    @Test
    @DisplayName("Rejects skipping QC before delivery")
    void cannotSkipQc() {
        pendingJob.setStatus(LabJobStatus.IN_PROGRESS);
        when(labJobRepository.findById(1L)).thenReturn(Optional.of(pendingJob));

        UpdateJobStatusRequest request = UpdateJobStatusRequest.builder()
                .status(LabJobStatus.READY_FOR_DELIVERY)
                .build();

        assertThatThrownBy(() -> labJobService.updateJobStatus(1L, request))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(ErrorCode.LAB_JOB_INVALID_TRANSITION);
    }

    @Test
    void failedQcCannotBeMarkedCompleted() {
        pendingJob.setStatus(LabJobStatus.QC_FAILED);
        when(labJobRepository.findById(1L)).thenReturn(Optional.of(pendingJob));
        assertThatThrownBy(() -> labJobService.updateJobStatus(1L,
                UpdateJobStatusRequest.builder().status(LabJobStatus.COMPLETED).build()))
                .isInstanceOf(ApiException.class);
        verify(labJobRepository, never()).save(any());
        verifyNoInteractions(historyRepository);
    }

    @Test
    void rawMeasurementsCannotCreateAnInventedClinicalPrescription() {
        var request = CreateLabJobRequest.builder().orderId(501L)
                .odSphere(BigDecimal.ZERO).osSphere(BigDecimal.ZERO).pd(new BigDecimal("63")).build();
        assertThatThrownBy(() -> labJobService.createLabJob(request)).isInstanceOf(ApiException.class);
        verify(prescriptionRepository, never()).save(any());
        verify(labJobRepository, never()).save(any());
    }
}
