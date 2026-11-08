package com.glassstore.lab.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.lab.dto.CreateLabQcRequest;
import com.glassstore.lab.dto.LabJobResponse;
import com.glassstore.lab.dto.LabQcResponse;
import com.glassstore.lab.dto.ReworkJobRequest;
import com.glassstore.lab.entity.LabJobStatus;
import com.glassstore.lab.entity.QcResult;
import com.glassstore.lab.exception.GlobalExceptionHandler;
import com.glassstore.lab.repository.LabJobHistoryRepository;
import com.glassstore.lab.repository.LabJobRepository;
import com.glassstore.lab.service.LabJobService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller integration tests cho API kiểm định QC và Rework (S2-D4-07).
 */
@WebMvcTest(LabJobController.class)
@Import({GlobalExceptionHandler.class, com.glassstore.lab.config.LabSecurityConfig.class})
@WithMockUser(username = "1", roles = "LAB_TECH")
class LabQcControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private LabJobService labJobService;

    @MockBean
    private LabJobRepository labJobRepository;

    @MockBean
    private LabJobHistoryRepository historyRepository;

    @Test
    @DisplayName("POST /api/lab-jobs/{id}/qc: Gửi kết quả QC PASS thành công trả về 200 OK")
    void submitQc_Pass_Returns200() throws Exception {
        CreateLabQcRequest request = CreateLabQcRequest.builder()
                .result(QcResult.PASS)
                .powerCheckPassed(true)
                .opticalCenterCheckPassed(true)
                .surfaceQualityCheckPassed(true)
                .bevelEdgingCheckPassed(true)
                .frameFittingCheckPassed(true)
                .notes("Đạt chuẩn 100%")
                .build();

        LabQcResponse response = LabQcResponse.builder()
                .id(1L)
                .labJobId(10L)
                .qcResult(QcResult.PASS)
                .qcResultLabel("Đạt chuẩn")
                .powerCheckPassed(true)
                .inspectedAt(LocalDateTime.now())
                .build();

        when(labJobService.submitQcEvaluation(eq(10L), any(CreateLabQcRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/lab-jobs/10/qc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.qcResult").value("PASS"))
                .andExpect(jsonPath("$.data.powerCheckPassed").value(true));
    }

    @Test
    @DisplayName("POST /api/lab-jobs/{id}/qc: QC FAIL thiếu lý do trả về HTTP 409 CONFLICT (LAB_JOB_QC_REASON_REQUIRED)")
    void submitQc_Fail_MissingReason_Returns409() throws Exception {
        CreateLabQcRequest request = CreateLabQcRequest.builder()
                .result(QcResult.FAIL)
                .failureReason("")
                .build();

        when(labJobService.submitQcEvaluation(eq(10L), any(CreateLabQcRequest.class)))
                .thenThrow(ApiException.of(ErrorCode.LAB_JOB_QC_REASON_REQUIRED,
                        "Phải ghi rõ lý do khi đánh giá QC không đạt hoặc yêu cầu mài lại"));

        mockMvc.perform(post("/api/lab-jobs/10/qc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.LAB_JOB_QC_REASON_REQUIRED.getCode()));
    }

    @Test
    @DisplayName("GET /api/lab-jobs/{id}/qc-history: Trả về danh sách lịch sử kiểm định QC")
    void getQcHistory_Returns200AndList() throws Exception {
        LabQcResponse qc1 = LabQcResponse.builder()
                .id(1L)
                .labJobId(10L)
                .qcResult(QcResult.FAIL)
                .failureReason("Lệch trục 5 độ")
                .reworkCount(0)
                .inspectedAt(LocalDateTime.now().minusHours(1))
                .build();

        LabQcResponse qc2 = LabQcResponse.builder()
                .id(2L)
                .labJobId(10L)
                .qcResult(QcResult.PASS)
                .reworkCount(1)
                .inspectedAt(LocalDateTime.now())
                .build();

        when(labJobService.getQcHistory(10L)).thenReturn(List.of(qc1, qc2));

        mockMvc.perform(get("/api/lab-jobs/10/qc-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].qcResult").value("FAIL"))
                .andExpect(jsonPath("$.data[1].qcResult").value("PASS"));
    }

    @Test
    @DisplayName("POST /api/lab-jobs/{id}/rework: Yêu cầu mài lại thành công trả về 200 OK")
    void requestRework_ValidRequest_Returns200() throws Exception {
        ReworkJobRequest request = ReworkJobRequest.builder()
                .reason("Mài chỉnh lại mép vát")
                .idempotencyKey("REWORK_10_1")
                .build();

        LabJobResponse jobResponse = LabJobResponse.builder()
                .id(10L)
                .orderId(100L)
                .prescriptionId(200L)
                .status(LabJobStatus.IN_PROGRESS)
                .reworkCount(1)
                .build();

        when(labJobService.requestRework(eq(10L), any(ReworkJobRequest.class)))
                .thenReturn(jobResponse);

        mockMvc.perform(post("/api/lab-jobs/10/rework")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.reworkCount").value(1));
    }
}

