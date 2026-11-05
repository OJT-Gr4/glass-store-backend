package com.glassstore.lab.service;

import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.lab.dto.CreateLabJobRequest;
import com.glassstore.lab.dto.LabJobResponse;
import com.glassstore.lab.dto.UpdateJobStatusRequest;
import com.glassstore.lab.entity.LabJob;
import com.glassstore.lab.entity.LabJobHistory;
import com.glassstore.lab.entity.LabJobStatus;
import com.glassstore.lab.entity.Prescription;
import com.glassstore.lab.repository.LabJobHistoryRepository;
import com.glassstore.lab.repository.LabJobRepository;
import com.glassstore.lab.repository.PrescriptionRepository;
import com.glassstore.lab.service.impl.LabJobServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Kiểm thử vòng đời gia công, kiểm định QC, Idempotency và Audit Log tại eyewear-lab-service.
 */
@ExtendWith(MockitoExtension.class)
class LabJobLifecycleAndIdempotencyTest {

    @Mock
    private LabJobRepository labJobRepository;

    @Mock
    private PrescriptionRepository prescriptionRepository;

    @Mock
    private LabJobHistoryRepository historyRepository;

    @InjectMocks
    private LabJobServiceImpl labJobService;

    private Prescription validPrescription;
    private CreateLabJobRequest validRequest;

    @BeforeEach
    void setUp() {
        validPrescription = Prescription.builder()
                .id(200L)
                .customerId(50L)
                .customerName("Đỗ Hoàng Long")
                .optometristId(1L)
                .optometristName("BS. Khúc Xạ")
                .odSph(new BigDecimal("-2.50"))
                .odCyl(new BigDecimal("-0.75"))
                .odAxis(90)
                .osSph(new BigDecimal("-2.25"))
                .osCyl(new BigDecimal("-0.50"))
                .osAxis(85)
                .pd(new BigDecimal("64.0"))
                .build();

        validRequest = CreateLabJobRequest.builder()
                .orderId(100L)
                .orderItemId(1L)
                .prescriptionId(200L)
                .customerId(50L)
                .customerName("Đỗ Hoàng Long")
                .sku("LNS-HOYA-167")
                .idempotencyKey("LAB_REQ_100_1")
                .build();
    }

    @Nested
    @DisplayName("Yêu cầu 1: Đơn thanh toán hợp lệ + đủ toa -> tạo đúng 1 LabJob và 1 LabJobHistory")
    class ValidJobCreationTests {

        @Test
        @DisplayName("Tạo mới thành công: lưu đúng 1 LabJob và ghi nhận 1 bản ghi LabJobHistory với status CREATED")
        void createLabJob_ValidPaidOrder_SavesOneJobAndOneHistory() {
            when(labJobRepository.findByIdempotencyKey("LAB_REQ_100_1")).thenReturn(Optional.empty());
            when(prescriptionRepository.findById(200L)).thenReturn(Optional.of(validPrescription));

            LabJob savedJob = LabJob.builder()
                    .id(1L)
                    .orderId(100L)
                    .orderItemId(1L)
                    .idempotencyKey("LAB_REQ_100_1")
                    .prescriptionId(200L)
                    .customerName("Đỗ Hoàng Long")
                    .sku("LNS-HOYA-167")
                    .status(LabJobStatus.CREATED)
                    .createdAt(LocalDateTime.now())
                    .build();

            when(labJobRepository.save(any(LabJob.class))).thenReturn(savedJob);

            LabJobResponse response = labJobService.createLabJob(validRequest);

            // Kiểm tra response
            assertThat(response).isNotNull();
            assertThat(response.getId()).isEqualTo(1L);
            assertThat(response.getStatus()).isEqualTo(LabJobStatus.CREATED);
            assertThat(response.getIdempotencyKey()).isEqualTo("LAB_REQ_100_1");
            assertThat(response.getOdSphere()).isEqualByComparingTo(new BigDecimal("-2.50"));
            assertThat(response.getOsSphere()).isEqualByComparingTo(new BigDecimal("-2.25"));
            assertThat(response.getPd()).isEqualByComparingTo(new BigDecimal("64.0"));

            // Xác nhận LabJob chỉ được lưu đúng 1 lần
            verify(labJobRepository, times(1)).save(any(LabJob.class));

            // Xác nhận LabJobHistory được ghi nhận đúng 1 lần với status ban đầu CREATED
            ArgumentCaptor<LabJobHistory> historyCaptor = ArgumentCaptor.forClass(LabJobHistory.class);
            verify(historyRepository, times(1)).save(historyCaptor.capture());

            LabJobHistory savedHistory = historyCaptor.getValue();
            assertThat(savedHistory.getLabJobId()).isEqualTo(1L);
            assertThat(savedHistory.getFromStatus()).isNull();
            assertThat(savedHistory.getToStatus()).isEqualTo(LabJobStatus.CREATED.name());
        }
    }

    @Nested
    @DisplayName("Yêu cầu 2: Idempotency - Bắn 3 request liên tiếp cùng idempotencyKey -> chỉ lưu duy nhất 1 LabJob")
    class IdempotencyTests {

        @Test
        @DisplayName("Bắn 3 request liên tiếp cùng idempotencyKey chỉ insert DB 1 lần, các lần sau trả về job đã tồn tại")
        void createLabJob_ThreeConsecutiveRequestsSameKey_SavesOnlyOneLabJob() {
            String idempotencyKey = "LAB_REQ_100_1";

            LabJob existingJob = LabJob.builder()
                    .id(1L)
                    .orderId(100L)
                    .orderItemId(1L)
                    .idempotencyKey(idempotencyKey)
                    .prescriptionId(200L)
                    .customerName("Đỗ Hoàng Long")
                    .sku("LNS-HOYA-167")
                    .status(LabJobStatus.CREATED)
                    .createdAt(LocalDateTime.now())
                    .build();

            // Request 1: Chưa có trong DB -> thực hiện insert
            when(labJobRepository.findByIdempotencyKey(idempotencyKey))
                    .thenReturn(Optional.empty()) // Lần 1
                    .thenReturn(Optional.of(existingJob)) // Lần 2
                    .thenReturn(Optional.of(existingJob)); // Lần 3

            when(prescriptionRepository.findById(200L)).thenReturn(Optional.of(validPrescription));
            when(labJobRepository.save(any(LabJob.class))).thenReturn(existingJob);

            // Bắn Request 1
            LabJobResponse res1 = labJobService.createLabJob(validRequest);
            assertThat(res1.getId()).isEqualTo(1L);

            // Bắn Request 2 (cùng key)
            LabJobResponse res2 = labJobService.createLabJob(validRequest);
            assertThat(res2.getId()).isEqualTo(1L);

            // Bắn Request 3 (cùng key)
            LabJobResponse res3 = labJobService.createLabJob(validRequest);
            assertThat(res3.getId()).isEqualTo(1L);

            // Chỉ lưu đúng 1 lần duy nhất vào LabJobRepository
            verify(labJobRepository, times(1)).save(any(LabJob.class));
            // History cũng chỉ ghi 1 lần khởi tạo ban đầu
            verify(historyRepository, times(1)).save(any(LabJobHistory.class));
        }
    }

    @Nested
    @DisplayName("Yêu cầu 3: Chặn tạo việc và trả về lỗi 400 khi thiếu toa hoặc sai khách hàng")
    class ValidationRejectionTests {

        @Test
        @DisplayName("Thiếu toa kính (không tìm thấy trong PrescriptionRepository) -> ném ApiException VALIDATION_ERROR (HTTP 400)")
        void createLabJob_MissingPrescription_ThrowsValidationError() {
            when(labJobRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
            when(prescriptionRepository.findById(200L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> labJobService.createLabJob(validRequest))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                        assertThat(apiEx.getErrorCode().getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    });

            verify(labJobRepository, never()).save(any(LabJob.class));
        }

        @Test
        @DisplayName("Sai khách hàng (customerId trong đơn khác customerId trong toa) -> ném ApiException VALIDATION_ERROR (HTTP 400)")
        void createLabJob_MismatchedCustomer_ThrowsValidationError() {
            when(labJobRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());

            // Toa của khách hàng 99L, nhưng request gửi customerId 50L
            Prescription wrongCustomerPrescription = Prescription.builder()
                    .id(200L)
                    .customerId(99L)
                    .customerName("Khách Hàng Khác")
                    .odSph(new BigDecimal("-1.00"))
                    .odCyl(BigDecimal.ZERO)
                    .odAxis(0)
                    .osSph(new BigDecimal("-1.00"))
                    .osCyl(BigDecimal.ZERO)
                    .osAxis(0)
                    .pd(new BigDecimal("60.0"))
                    .build();

            when(prescriptionRepository.findById(200L)).thenReturn(Optional.of(wrongCustomerPrescription));

            assertThatThrownBy(() -> labJobService.createLabJob(validRequest))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                        assertThat(apiEx.getErrorCode().getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    });

            verify(labJobRepository, never()).save(any(LabJob.class));
        }

        @Test
        @DisplayName("Toa kính thiếu thông số đo bắt buộc (ví dụ thiếu SPH/CYL/AXIS/PD) -> ném ApiException VALIDATION_ERROR (HTTP 400)")
        void createLabJob_IncompletePrescription_ThrowsValidationError() {
            when(labJobRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());

            // Toa thiếu PD
            Prescription incompleteRx = Prescription.builder()
                    .id(200L)
                    .customerId(50L)
                    .odSph(new BigDecimal("-2.00"))
                    .odCyl(BigDecimal.ZERO)
                    .odAxis(0)
                    .osSph(new BigDecimal("-2.00"))
                    .osCyl(BigDecimal.ZERO)
                    .osAxis(0)
                    .pd(null) // Thiếu PD
                    .build();

            when(prescriptionRepository.findById(200L)).thenReturn(Optional.of(incompleteRx));

            assertThatThrownBy(() -> labJobService.createLabJob(validRequest))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                        assertThat(apiEx.getErrorCode().getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    });

            verify(labJobRepository, never()).save(any(LabJob.class));
        }
    }

    @Nested
    @DisplayName("Yêu cầu State Machine & Audit Logging")
    class StateMachineAndAuditLoggingTests {

        @Test
        @DisplayName("Luồng hợp lệ: CREATED -> QUEUED -> IN_PROGRESS -> QC_PASSED -> COMPLETED ghi đủ audit log mỗi bước")
        void updateJobStatus_ValidTransitions_RecordsHistoryEachStep() {
            LabJob job = LabJob.builder()
                    .id(10L)
                    .orderId(100L)
                    .status(LabJobStatus.CREATED)
                    .build();

            when(labJobRepository.findById(10L)).thenReturn(Optional.of(job));
            when(labJobRepository.save(any(LabJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Bước 1: CREATED -> QUEUED
            labJobService.updateJobStatus(10L, UpdateJobStatusRequest.builder()
                    .status(LabJobStatus.QUEUED)
                    .technicianName("KTV A")
                    .notes("Đưa vào hàng đợi mài")
                    .build());
            assertThat(job.getStatus()).isEqualTo(LabJobStatus.QUEUED);

            // Bước 2: QUEUED -> IN_PROGRESS
            labJobService.updateJobStatus(10L, UpdateJobStatusRequest.builder()
                    .status(LabJobStatus.IN_PROGRESS)
                    .technicianName("KTV B")
                    .notes("Bắt đầu mài tròng")
                    .build());
            assertThat(job.getStatus()).isEqualTo(LabJobStatus.IN_PROGRESS);
            assertThat(job.getStartedAt()).isNotNull();

            // Bước 3: IN_PROGRESS -> QC_PASSED
            labJobService.updateJobStatus(10L, UpdateJobStatusRequest.builder()
                    .status(LabJobStatus.QC_PASSED)
                    .technicianName("QC Kiểm Định")
                    .qcNotes("Tròng kính đạt chuẩn tâm mắt và góc nghiêng")
                    .build());
            assertThat(job.getStatus()).isEqualTo(LabJobStatus.QC_PASSED);
            assertThat(job.getCompletedAt()).isNotNull();

            // Bước 4: QC_PASSED -> COMPLETED
            labJobService.updateJobStatus(10L, UpdateJobStatusRequest.builder()
                    .status(LabJobStatus.COMPLETED)
                    .technicianName("KTV A")
                    .notes("Đóng khay bàn giao")
                    .build());
            assertThat(job.getStatus()).isEqualTo(LabJobStatus.COMPLETED);

            // Kiểm tra mỗi bước đều ghi đúng 1 audit log (tổng cộng 4 bước = 4 log)
            verify(historyRepository, times(4)).save(any(LabJobHistory.class));
        }

        @Test
        @DisplayName("Chuyển sai quy trình: CREATED nhảy thẳng sang COMPLETED -> ném ApiException LAB_JOB_INVALID_TRANSITION")
        void updateJobStatus_InvalidTransition_ThrowsApiException() {
            LabJob job = LabJob.builder()
                    .id(11L)
                    .orderId(101L)
                    .status(LabJobStatus.CREATED)
                    .build();

            when(labJobRepository.findById(11L)).thenReturn(Optional.of(job));

            assertThatThrownBy(() -> labJobService.updateJobStatus(11L, UpdateJobStatusRequest.builder()
                    .status(LabJobStatus.COMPLETED)
                    .build()))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.LAB_JOB_INVALID_TRANSITION);
                    });

            verify(historyRepository, never()).save(any(LabJobHistory.class));
        }

        @Test
        @DisplayName("Cập nhật QC_FAILED nhưng không có qcNotes -> ném ApiException LAB_JOB_QC_REASON_REQUIRED")
        void updateJobStatus_QcFailedWithoutNotes_ThrowsException() {
            LabJob job = LabJob.builder()
                    .id(12L)
                    .orderId(102L)
                    .status(LabJobStatus.IN_PROGRESS)
                    .build();

            when(labJobRepository.findById(12L)).thenReturn(Optional.of(job));

            assertThatThrownBy(() -> labJobService.updateJobStatus(12L, UpdateJobStatusRequest.builder()
                    .status(LabJobStatus.QC_FAILED)
                    .qcNotes("") // Rỗng
                    .build()))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.LAB_JOB_QC_REASON_REQUIRED);
                    });

            verify(historyRepository, never()).save(any(LabJobHistory.class));
        }
    }
}

