package com.glassstore.lab.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.lab.dto.CreateLabJobRequest;
import com.glassstore.lab.dto.LabJobResponse;
import com.glassstore.lab.entity.LabJob;
import com.glassstore.lab.entity.LabJobStatus;
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
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Kiểm thử Controller API POST /api/lab-jobs về Idempotency và HTTP Status Code.
 */
@WebMvcTest(LabJobController.class)
@Import({GlobalExceptionHandler.class,com.glassstore.lab.config.LabSecurityConfig.class})
@org.springframework.security.test.context.support.WithMockUser(username="1", roles="ADMIN")
class LabJobIdempotencyAndValidationControllerTest {

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
    @DisplayName("POST /api/lab-jobs: Request mới tạo thành công trả về HTTP 201 Created")
    void createLabJob_NewRequest_ReturnsHttp201() throws Exception {
        CreateLabJobRequest request = CreateLabJobRequest.builder()
                .orderId(101L)
                .orderItemId(1L)
                .prescriptionId(50L)
                .idempotencyKey("LAB_REQ_101_1")
                .sku("LNS-CRIZAL")
                .build();

        LabJobResponse responseDto = LabJobResponse.builder()
                .id(1L)
                .orderId(101L)
                .status(LabJobStatus.CREATED)
                .idempotencyKey("LAB_REQ_101_1")
                .build();

        when(labJobRepository.findByIdempotencyKey("LAB_REQ_101_1")).thenReturn(Optional.empty());
        when(labJobService.createLabJob(any(CreateLabJobRequest.class))).thenReturn(responseDto);

        mockMvc.perform(post("/api/lab-jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(201))
                .andExpect(jsonPath("$.data.id").value(1L))
                .andExpect(jsonPath("$.data.idempotencyKey").value("LAB_REQ_101_1"));
    }

    @Test
    @DisplayName("POST /api/lab-jobs: Request trùng idempotencyKey trả về HTTP 200 OK và dữ liệu job hiện tại")
    void createLabJob_DuplicateIdempotencyKey_ReturnsHttp200() throws Exception {
        CreateLabJobRequest request = CreateLabJobRequest.builder()
                .orderId(101L)
                .orderItemId(1L)
                .prescriptionId(50L)
                .idempotencyKey("LAB_REQ_101_1")
                .sku("LNS-CRIZAL")
                .build();

        LabJob existingJob = LabJob.builder()
                .id(1L)
                .orderId(101L)
                .idempotencyKey("LAB_REQ_101_1")
                .status(LabJobStatus.CREATED)
                .build();

        LabJobResponse responseDto = LabJobResponse.builder()
                .id(1L)
                .orderId(101L)
                .status(LabJobStatus.CREATED)
                .idempotencyKey("LAB_REQ_101_1")
                .build();

        // Đã tồn tại trong DB với idempotencyKey này
        when(labJobRepository.findByIdempotencyKey("LAB_REQ_101_1")).thenReturn(Optional.of(existingJob));
        when(labJobService.createLabJob(any(CreateLabJobRequest.class))).thenReturn(responseDto);

        mockMvc.perform(post("/api/lab-jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1L))
                .andExpect(jsonPath("$.message").value("Lệnh gia công đã tồn tại (idempotent)"));
    }

    @Test
    @DisplayName("POST /api/lab-jobs: Thiếu toa kính hoặc sai khách hàng -> Service ném VALIDATION_ERROR -> trả về HTTP 400 Bad Request")
    void createLabJob_MissingPrescriptionOrWrongCustomer_ReturnsHttp400() throws Exception {
        CreateLabJobRequest request = CreateLabJobRequest.builder()
                .orderId(102L)
                .orderItemId(1L)
                .idempotencyKey("LAB_REQ_102_1")
                .build();

        when(labJobRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(labJobService.createLabJob(any(CreateLabJobRequest.class)))
                .thenThrow(ApiException.of(ErrorCode.VALIDATION_ERROR, "Thiếu thông tin toa kính cho lệnh gia công"));

        mockMvc.perform(post("/api/lab-jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Thiếu thông tin toa kính cho lệnh gia công"));
    }
}

