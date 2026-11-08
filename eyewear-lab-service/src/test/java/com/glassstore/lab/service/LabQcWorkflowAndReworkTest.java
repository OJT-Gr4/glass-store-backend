package com.glassstore.lab.service;

import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.lab.dto.CreateLabQcRequest;
import com.glassstore.lab.dto.LabJobResponse;
import com.glassstore.lab.dto.LabQcResponse;
import com.glassstore.lab.dto.ReworkJobRequest;
import com.glassstore.lab.entity.LabJob;
import com.glassstore.lab.entity.LabJobHistory;
import com.glassstore.lab.entity.LabJobStatus;
import com.glassstore.lab.entity.LabQcHistory;
import com.glassstore.lab.entity.Prescription;
import com.glassstore.lab.entity.QcResult;
import com.glassstore.lab.repository.LabJobHistoryRepository;
import com.glassstore.lab.repository.LabJobRepository;
import com.glassstore.lab.repository.LabQcHistoryRepository;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit test chuyên sâu cho [S2-D4-07]: Lưu QC và lý do mài lại, không tự trừ phôi theo nhãn lỗi.
 * Truy vết: FR-LAB-06/07/08/10; NFR-SEC-05.
 */
@ExtendWith(MockitoExtension.class)
class LabQcWorkflowAndReworkTest {

    @Mock
    private LabJobRepository labJobRepository;

    @Mock
    private PrescriptionRepository prescriptionRepository;

    @Mock
    private LabJobHistoryRepository historyRepository;

    @Mock
    private LabQcHistoryRepository qcHistoryRepository;

    @InjectMocks
    private LabJobServiceImpl labJobService;

    private LabJob activeJob;
    private Prescription confirmedPrescription;

    @BeforeEach
    void setUp() {
        confirmedPrescription = Prescription.builder()
                .odAdd(BigDecimal.ZERO).osAdd(BigDecimal.ZERO)
                .id(200L)
                .customerId(50L)
                .customerName("Đỗ Hoàng Long")
                .odSph(new BigDecimal("-2.50"))
                .odCyl(new BigDecimal("-0.75"))
                .odAxis(90)
                .osSph(new BigDecimal("-2.25"))
                .osCyl(new BigDecimal("-0.50"))
                .osAxis(85)
                .pd(new BigDecimal("64.0"))
                .build();

        activeJob = LabJob.builder()
                .id(10L)
                .orderId(100L)
                .orderItemId(1L)
                .orderCode("ORD-20261108-0001")
                .trayNumber("KHAY-100")
                .prescriptionId(200L)
                .customerName("Đỗ Hoàng Long")
                .sku("LNS-HOYA-167")
                .lensSku("LNS-HOYA-167")
                .frameSku("FRM-TITAN-01")
                .status(LabJobStatus.IN_PROGRESS)
                .reworkCount(0)
                .materialDamaged(false)
                .createdAt(LocalDateTime.now().minusHours(2))
                .build();
    }

    @Nested
    @DisplayName("Subtask 7.1: Lưu kết quả QC, từng kiểm tra cần thiết, người kiểm định, FAIL/REWORK bắt buộc lý do")
    class QcInspectionChecklistTests {

        @Test
        @DisplayName("QC PASS: Lưu đủ từng kiểm tra cần thiết, ghi chú và người kiểm định; trạng thái chuyển QC_PASSED")
        void submitQc_Pass_SavesAllChecklistItemsAndUpdatesStatus() {
            when(labJobRepository.findById(10L)).thenReturn(Optional.of(activeJob));
            when(labJobRepository.save(any(LabJob.class))).thenAnswer(i -> i.getArgument(0));
            when(qcHistoryRepository.save(any(LabQcHistory.class))).thenAnswer(i -> {
                LabQcHistory qc = i.getArgument(0);
                qc.setId(101L);
                return qc;
            });

            CreateLabQcRequest request = CreateLabQcRequest.builder()
                    .result(QcResult.PASS)
                    .powerCheckPassed(true)
                    .opticalCenterCheckPassed(true)
                    .surfaceQualityCheckPassed(true)
                    .bevelEdgingCheckPassed(true)
                    .frameFittingCheckPassed(true)
                    .notes("Tròng kính mài sắc nét, đúng trục loạn và tâm mắt")
                    .inspectorId(5L)
                    .inspectorName("KTV_KiemDinh_01")
                    .build();

            LabQcResponse response = labJobService.submitQcEvaluation(10L, request);

            assertThat(response).isNotNull();
            assertThat(response.getQcResult()).isEqualTo(QcResult.PASS);
            assertThat(response.getPowerCheckPassed()).isTrue();
            assertThat(response.getOpticalCenterCheckPassed()).isTrue();
            assertThat(response.getSurfaceQualityCheckPassed()).isTrue();
            assertThat(response.getBevelEdgingCheckPassed()).isTrue();
            assertThat(response.getFrameFittingCheckPassed()).isTrue();
            assertThat(response.getInspectorName()).isEqualTo("KTV_KiemDinh_01");

            assertThat(activeJob.getStatus()).isEqualTo(LabJobStatus.QC_PASSED);
            assertThat(activeJob.getCompletedAt()).isNotNull();
            assertThat(activeJob.getQcResult()).isEqualTo("PASS");

            ArgumentCaptor<LabQcHistory> qcCaptor = ArgumentCaptor.forClass(LabQcHistory.class);
            verify(qcHistoryRepository).save(qcCaptor.capture());
            LabQcHistory savedQc = qcCaptor.getValue();
            assertThat(savedQc.getLabJobId()).isEqualTo(10L);
            assertThat(savedQc.getOrderId()).isEqualTo(100L);
            assertThat(savedQc.getPrescriptionId()).isEqualTo(200L);
            assertThat(savedQc.getQcResult()).isEqualTo(QcResult.PASS);

            verify(historyRepository, times(1)).save(any(LabJobHistory.class));
        }

        @Test
        @DisplayName("QC FAIL hợp lệ: Có lý do lỗi và mã phân loại lỗi -> lưu thành công, trạng thái chuyển QC_FAILED")
        void submitQc_Fail_WithValidReason_SavesSuccessfully() {
            when(labJobRepository.findById(10L)).thenReturn(Optional.of(activeJob));
            when(labJobRepository.save(any(LabJob.class))).thenAnswer(i -> i.getArgument(0));
            when(qcHistoryRepository.save(any(LabQcHistory.class))).thenAnswer(i -> {
                LabQcHistory qc = i.getArgument(0);
                qc.setId(102L);
                return qc;
            });

            CreateLabQcRequest request = CreateLabQcRequest.builder()
                    .result(QcResult.FAIL)
                    .powerCheckPassed(true)
                    .opticalCenterCheckPassed(false)
                    .surfaceQualityCheckPassed(true)
                    .bevelEdgingCheckPassed(true)
                    .frameFittingCheckPassed(true)
                    .failureReason("Lệch khoảng cách đồng tử PD quá 2mm so với toa")
                    .defectType("PD_MISALIGNMENT")
                    .materialDamaged(false)
                    .inspectorName("QC_SENIOR")
                    .build();

            LabQcResponse response = labJobService.submitQcEvaluation(10L, request);

            assertThat(response.getQcResult()).isEqualTo(QcResult.FAIL);
            assertThat(response.getFailureReason()).isEqualTo("Lệch khoảng cách đồng tử PD quá 2mm so với toa");
            assertThat(response.getDefectType()).isEqualTo("PD_MISALIGNMENT");
            assertThat(activeJob.getStatus()).isEqualTo(LabJobStatus.QC_FAILED);
            assertThat(activeJob.getQcNotes()).isEqualTo("Lệch khoảng cách đồng tử PD quá 2mm so với toa");
        }

        @Test
        @DisplayName("QC FAIL thiếu lý do: Bị từ chối và ném ApiException LAB_JOB_QC_REASON_REQUIRED")
        void submitQc_Fail_MissingReason_ThrowsApiException() {
            when(labJobRepository.findById(10L)).thenReturn(Optional.of(activeJob));

            CreateLabQcRequest request = CreateLabQcRequest.builder()
                    .result(QcResult.FAIL)
                    .failureReason("") // Để trống lý do
                    .notes("   ")
                    .build();

            assertThatThrownBy(() -> labJobService.submitQcEvaluation(10L, request))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.LAB_JOB_QC_REASON_REQUIRED);
                    });

            verify(qcHistoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("QC REWORK thiếu lý do: Bị từ chối và ném ApiException LAB_JOB_QC_REASON_REQUIRED")
        void submitQc_Rework_MissingReason_ThrowsApiException() {
            when(labJobRepository.findById(10L)).thenReturn(Optional.of(activeJob));

            CreateLabQcRequest request = CreateLabQcRequest.builder()
                    .result(QcResult.REWORK)
                    .failureReason(null)
                    .notes(null)
                    .build();

            assertThatThrownBy(() -> labJobService.submitQcEvaluation(10L, request))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.LAB_JOB_QC_REASON_REQUIRED);
                    });

            verify(qcHistoryRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Subtask 7.2: Phân biệt cần mài lại với thực sự hỏng vật tư; không tự trừ một cặp phôi chỉ vì nhấn QC_FAILED")
    class DistinguishReworkFromMaterialDamagedTests {

        @Test
        @DisplayName("QC REWORK: materialDamaged luôn là false (tái sử dụng phôi kính hiện tại để mài lại)")
        void submitQc_Rework_MaterialDamagedIsFalse() {
            when(labJobRepository.findById(10L)).thenReturn(Optional.of(activeJob));
            when(labJobRepository.save(any(LabJob.class))).thenAnswer(i -> i.getArgument(0));
            when(qcHistoryRepository.save(any(LabQcHistory.class))).thenAnswer(i -> i.getArgument(0));

            CreateLabQcRequest request = CreateLabQcRequest.builder()
                    .result(QcResult.REWORK)
                    .bevelEdgingCheckPassed(false)
                    .failureReason("Góc vát cạnh hơi dày cần mài mỏng thêm 0.3mm")
                    .materialDamaged(true) // Giả sử client gửi nhầm true
                    .build();

            LabQcResponse response = labJobService.submitQcEvaluation(10L, request);

            // Phải phân biệt rõ: Rework tái sử dụng phôi -> materialDamaged = false
            assertThat(response.getMaterialDamaged()).isFalse();
            assertThat(activeJob.getMaterialDamaged()).isFalse();
            assertThat(activeJob.getStatus()).isEqualTo(LabJobStatus.REWORK);
            assertThat(activeJob.getReworkCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("QC_FAILED không tự trừ phôi: Đánh dấu materialDamaged theo đúng thực tế, không có hành vi tự trừ kho")
        void submitQc_Fail_DoesNotAutoDeductStock_MarksMaterialDamagedAccurately() {
            when(labJobRepository.findById(10L)).thenReturn(Optional.of(activeJob));
            when(labJobRepository.save(any(LabJob.class))).thenAnswer(i -> i.getArgument(0));
            when(qcHistoryRepository.save(any(LabQcHistory.class))).thenAnswer(i -> i.getArgument(0));

            CreateLabQcRequest request = CreateLabQcRequest.builder()
                    .result(QcResult.FAIL)
                    .failureReason("Tròng kính bị nứt vỡ trong quá trình kẹp máy")
                    .defectType("CRACKED_LENS")
                    .materialDamaged(true) // Thực sự hỏng phôi
                    .build();

            LabQcResponse response = labJobService.submitQcEvaluation(10L, request);

            assertThat(response.getMaterialDamaged()).isTrue();
            assertThat(activeJob.getMaterialDamaged()).isTrue();
            assertThat(activeJob.getStatus()).isEqualTo(LabJobStatus.QC_FAILED);

            // Kiểm tra: Không sinh job mới, không gọi bất kỳ Feign trừ kho nào
            verify(labJobRepository, times(1)).save(activeJob);
        }
    }

    @Nested
    @DisplayName("Subtask 7.3: Giữ liên kết lần mài lại với công việc gốc và toa đã xác nhận; ghi lịch sử, tránh nhân đôi khi retry")
    class ReworkTraceabilityAndIdempotencyTests {

        @Test
        @DisplayName("Yêu cầu mài lại: Giữ nguyên orderId, orderCode và prescriptionId từ việc gốc; tăng reworkCount")
        void requestRework_KeepsLinkToOriginalJobAndConfirmedRx() {
            activeJob.setStatus(LabJobStatus.QC_FAILED);
            when(labJobRepository.findById(10L)).thenReturn(Optional.of(activeJob));
            when(labJobRepository.save(any(LabJob.class))).thenAnswer(i -> i.getArgument(0));
            when(prescriptionRepository.findById(200L)).thenReturn(Optional.of(confirmedPrescription));

            ReworkJobRequest request = ReworkJobRequest.builder()
                    .reason("Mài lại đường vát cạnh cho khớp gọng titan")
                    .technicianName("Thợ_Mài_Chính")
                    .build();

            LabJobResponse response = labJobService.requestRework(10L, request);

            assertThat(response.getId()).isEqualTo(10L);
            assertThat(response.getOrderId()).isEqualTo(100L);
            assertThat(response.getOrderCode()).isEqualTo("ORD-20261108-0001");
            assertThat(response.getPrescriptionId()).isEqualTo(200L);
            assertThat(response.getReworkCount()).isEqualTo(1);
            assertThat(response.getStatus()).isEqualTo(LabJobStatus.IN_PROGRESS);

            // Xác nhận LabQcHistory ghi nhận lần mài lại thứ 1
            ArgumentCaptor<LabQcHistory> qcCaptor = ArgumentCaptor.forClass(LabQcHistory.class);
            verify(qcHistoryRepository).save(qcCaptor.capture());
            LabQcHistory reworkQc = qcCaptor.getValue();
            assertThat(reworkQc.getQcResult()).isEqualTo(QcResult.REWORK);
            assertThat(reworkQc.getReworkCount()).isEqualTo(1);
            assertThat(reworkQc.getFailureReason()).isEqualTo("Mài lại đường vát cạnh cho khớp gọng titan");
            assertThat(reworkQc.getPrescriptionId()).isEqualTo(200L);
        }

        @Test
        @DisplayName("Chống nhân đôi khi retry: Bắn liên tiếp 2 request rework cùng idempotencyKey chỉ tăng 1 lần")
        void requestRework_ConsecutiveRetriesSameIdempotencyKey_DoesNotDuplicate() {
            activeJob.setStatus(LabJobStatus.QC_FAILED);
            when(labJobRepository.findById(10L)).thenReturn(Optional.of(activeJob));
            when(labJobRepository.save(any(LabJob.class))).thenAnswer(i -> i.getArgument(0));
            when(prescriptionRepository.findById(200L)).thenReturn(Optional.of(confirmedPrescription));

            String retryKey = "REWORK_KEY_ORD_100_TRY_1";
            LabQcHistory existingHistory = LabQcHistory.builder()
                    .id(501L)
                    .idempotencyKey(retryKey)
                    .labJobId(10L)
                    .qcResult(QcResult.REWORK)
                    .reworkCount(1)
                    .build();

            when(qcHistoryRepository.findByIdempotencyKey(retryKey))
                    .thenReturn(Optional.empty()) // Lần 1: chưa có
                    .thenReturn(Optional.of(existingHistory)); // Lần 2: đã có

            ReworkJobRequest request = ReworkJobRequest.builder()
                    .reason("Chỉnh lại góc nghiêng gá tròng")
                    .idempotencyKey(retryKey)
                    .build();

            // Request 1: Thực hiện tăng reworkCount
            LabJobResponse res1 = labJobService.requestRework(10L, request);
            assertThat(res1.getReworkCount()).isEqualTo(1);

            // Request 2 (Retry cùng key): Trả về job hiện tại, không tăng tiếp
            LabJobResponse res2 = labJobService.requestRework(10L, request);
            assertThat(res2.getReworkCount()).isEqualTo(1);

            // Xác nhận repository chỉ save 1 lần duy nhất
            verify(labJobRepository, times(1)).save(any(LabJob.class));
            verify(qcHistoryRepository, times(1)).save(any(LabQcHistory.class));
        }

        @Test
        @DisplayName("Lệnh mài bị mất liên kết toa kính gốc -> từ chối yêu cầu mài lại")
        void requestRework_MissingPrescriptionLink_ThrowsValidationError() {
            activeJob.setPrescriptionId(null); // Mất liên kết toa
            when(labJobRepository.findById(10L)).thenReturn(Optional.of(activeJob));

            ReworkJobRequest request = ReworkJobRequest.builder()
                    .reason("Mài lại")
                    .build();

            assertThatThrownBy(() -> labJobService.requestRework(10L, request))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    });

            verify(labJobRepository, never()).save(any());
        }

        @Test
        @DisplayName("Lấy danh sách lịch sử kiểm định QC: truy vết toàn bộ các lần PASS, FAIL, REWORK")
        void getQcHistory_ReturnsAllHistoricalEvaluations() {
            when(labJobRepository.findById(10L)).thenReturn(Optional.of(activeJob));

            LabQcHistory failQc = LabQcHistory.builder()
                    .id(1L)
                    .labJobId(10L)
                    .qcResult(QcResult.FAIL)
                    .failureReason("Lệch trục 5 độ")
                    .reworkCount(0)
                    .inspectedAt(LocalDateTime.now().minusHours(1))
                    .build();

            LabQcHistory reworkQc = LabQcHistory.builder()
                    .id(2L)
                    .labJobId(10L)
                    .qcResult(QcResult.REWORK)
                    .failureReason("Mài chỉnh lại trục loạn")
                    .reworkCount(1)
                    .inspectedAt(LocalDateTime.now().minusMinutes(30))
                    .build();

            LabQcHistory passQc = LabQcHistory.builder()
                    .id(3L)
                    .labJobId(10L)
                    .qcResult(QcResult.PASS)
                    .reworkCount(1)
                    .inspectedAt(LocalDateTime.now())
                    .build();

            when(qcHistoryRepository.findByLabJobIdOrderByInspectedAtAsc(10L))
                    .thenReturn(List.of(failQc, reworkQc, passQc));

            List<LabQcResponse> history = labJobService.getQcHistory(10L);

            assertThat(history).hasSize(3);
            assertThat(history.get(0).getQcResult()).isEqualTo(QcResult.FAIL);
            assertThat(history.get(1).getQcResult()).isEqualTo(QcResult.REWORK);
            assertThat(history.get(2).getQcResult()).isEqualTo(QcResult.PASS);
        }
    }
}

