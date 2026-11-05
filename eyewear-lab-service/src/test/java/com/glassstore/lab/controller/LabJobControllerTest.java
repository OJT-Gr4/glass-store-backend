package com.glassstore.lab.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glassstore.lab.dto.CreateLabJobRequest;
import com.glassstore.lab.dto.LabJobResponse;
import com.glassstore.lab.dto.UpdateJobStatusRequest;
import com.glassstore.lab.entity.LabJobStatus;
import com.glassstore.lab.repository.LabJobHistoryRepository;
import com.glassstore.lab.exception.GlobalExceptionHandler;
import com.glassstore.lab.service.LabJobService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LabJobController.class)
@Import({GlobalExceptionHandler.class,com.glassstore.lab.config.LabSecurityConfig.class})
@org.springframework.security.test.context.support.WithMockUser(username="1", roles="ADMIN")
class LabJobControllerTest {

    @Test
    @org.springframework.security.test.context.support.WithMockUser(username="service", roles="SERVICE")
    void orderQcLookupReturnsUnfinishedItemUntilEveryJobPasses() throws Exception {
        var passed = com.glassstore.lab.entity.LabJob.builder().id(1L).orderId(101L).status(LabJobStatus.QC_PASSED).build();
        var unfinished = com.glassstore.lab.entity.LabJob.builder().id(2L).orderId(101L).status(LabJobStatus.IN_PROGRESS).build();
        when(jobs.findAllByOrderIdOrderByIdAsc(101L)).thenReturn(List.of(passed, unfinished));
        when(labJobService.getLabJobById(2L)).thenReturn(LabJobResponse.builder()
                .id(2L).orderId(101L).status(LabJobStatus.IN_PROGRESS).build());
        mockMvc.perform(get("/api/lab-jobs/by-order/101"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        unfinished.setStatus(LabJobStatus.COMPLETED);
        when(labJobService.getLabJobById(1L)).thenReturn(LabJobResponse.builder()
                .id(1L).orderId(101L).status(LabJobStatus.QC_PASSED).build());
        mockMvc.perform(get("/api/lab-jobs/by-order/101"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("QC_PASSED"));
    }

    @Autowired
    private MockMvc mockMvc;
    @MockBean private com.glassstore.lab.repository.LabJobRepository jobs;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private LabJobService labJobService;

    @MockBean
    private LabJobHistoryRepository historyRepository;

    @Test
    @DisplayName("GET /api/lab-jobs: Trả về 200 OK và danh sách LabJobResponse")
    void getLabJobs_ShouldReturn200AndList() throws Exception {
        LabJobResponse job = LabJobResponse.builder()
                .id(1L)
                .orderId(101L)
                .prescriptionId(201L)
                .customerName("Nguyễn Hoàng Duy")
                .lensSku("LENS-01")
                .frameSku("FRAME-01")
                .status(LabJobStatus.PENDING)
                .statusLabel("Chờ mài")
                .odSphere(new BigDecimal("-2.25"))
                .osSphere(new BigDecimal("-2.00"))
                .pd(new BigDecimal("63.5"))
                .createdAt(LocalDateTime.now())
                .build();

        when(labJobService.getLabJobs(null)).thenReturn(List.of(job));

        mockMvc.perform(get("/api/lab-jobs")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].customerName").value("Nguyễn Hoàng Duy"))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data[0].odSphere").value(-2.25))
                .andExpect(jsonPath("$.data[0].pd").value(63.5));
    }

    @Test
    @DisplayName("POST /api/lab-jobs: Tạo lệnh mài mới trả về HTTP 201 Created")
    void createLabJob_ShouldReturn201Created() throws Exception {
        CreateLabJobRequest request = CreateLabJobRequest.builder()
                .orderId(101L)
                .customerName("Lê Thúy Kiều")
                .prescriptionId(202L)
                .lensSku("LENS-167")
                .frameSku("FRAME-GUCCI")
                .build();

        LabJobResponse response = LabJobResponse.builder()
                .id(5L)
                .orderId(101L)
                .prescriptionId(202L)
                .customerName("Lê Thúy Kiều")
                .lensSku("LENS-167")
                .frameSku("FRAME-GUCCI")
                .status(LabJobStatus.PENDING)
                .statusLabel("Chờ mài")
                .build();

        when(labJobService.createLabJob(any(CreateLabJobRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/lab-jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(201))
                .andExpect(jsonPath("$.data.id").value(5))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @DisplayName("POST /api/lab-jobs: Thiếu trường bắt buộc trả về HTTP 400 Bad Request")
    void createLabJob_ValidationFail_ShouldReturn400() throws Exception {
        CreateLabJobRequest request = CreateLabJobRequest.builder()
                .customerName("") // trống
                .build();

        mockMvc.perform(post("/api/lab-jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("PATCH /api/lab-jobs/{id}/status: Cập nhật trạng thái trả về HTTP 200 OK")
    void updateJobStatus_ShouldReturn200() throws Exception {
        UpdateJobStatusRequest request = UpdateJobStatusRequest.builder()
                .status(LabJobStatus.IN_PROGRESS)
                .technicianId(10L)
                .technicianName("Nguyễn Kỹ Thuật")
                .build();

        LabJobResponse response = LabJobResponse.builder()
                .id(1L)
                .status(LabJobStatus.IN_PROGRESS)
                .technicianId(10L)
                .technicianName("Nguyễn Kỹ Thuật")
                .startedAt(LocalDateTime.now())
                .build();

        when(labJobService.updateJobStatus(eq(1L), any(UpdateJobStatusRequest.class))).thenReturn(response);

        mockMvc.perform(patch("/api/lab-jobs/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.technicianId").value(10));
    }

    @Test
    @DisplayName("PATCH /api/lab-jobs/{id}/status: QC_FAILED thiếu ghi chú trả về HTTP 400")
    void updateJobStatus_QcFailedWithoutNotes_ShouldReturn400() throws Exception {
        UpdateJobStatusRequest request = UpdateJobStatusRequest.builder()
                .status(LabJobStatus.QC_FAILED)
                .qcNotes("")
                .build();

        when(labJobService.updateJobStatus(eq(1L), any(UpdateJobStatusRequest.class)))
                .thenThrow(new IllegalArgumentException("Ghi chú kiểm định QC (qcNotes) không được để trống khi kết quả là QC_FAILED"));

        mockMvc.perform(patch("/api/lab-jobs/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Ghi chú kiểm định QC (qcNotes) không được để trống khi kết quả là QC_FAILED"));
    }
}
