package com.glassstore.lab.controller;

import com.glassstore.common.dto.ApiResponse;
import com.glassstore.lab.dto.CreateLabJobRequest;
import com.glassstore.lab.dto.LabJobResponse;
import com.glassstore.lab.dto.UpdateJobStatusRequest;
import com.glassstore.lab.entity.LabJobHistory;
import com.glassstore.lab.entity.LabJobStatus;
import com.glassstore.lab.repository.LabJobHistoryRepository;
import com.glassstore.lab.repository.LabJobRepository;
import com.glassstore.lab.service.LabJobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/lab-jobs")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class LabJobController {

    private final LabJobService labJobService;
    private final LabJobRepository labJobRepository;
    private final LabJobHistoryRepository historyRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<List<LabJobResponse>>> getLabJobs(
            @RequestParam(name = "status", required = false) LabJobStatus status) {
        log.info("API GET /api/lab-jobs - Lọc theo status: {}", status);
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy danh sách lệnh gia công mài kính thành công", labJobService.getLabJobs(status)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<LabJobResponse>> getLabJobById(@PathVariable("id") Long id) {
        log.info("API GET /api/lab-jobs/{}", id);
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy chi tiết lệnh mài kính thành công", labJobService.getLabJobById(id)));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<ApiResponse<List<LabJobHistory>>> history(@PathVariable("id") Long id) {
        labJobService.getLabJobById(id);
        return ResponseEntity.ok(ApiResponse.success(
                "OK", historyRepository.findByLabJobIdOrderByChangedAtAsc(id)));
    }

    @PostMapping("/from-order")
    public ResponseEntity<ApiResponse<LabJobResponse>> createFromOrder(
            @RequestBody Map<String, Object> body) {
        LabJobResponse job = labJobService.createFromOrder(
                asLong(body.get("orderId")),
                asLong(body.get("prescriptionId")),
                asString(body.get("frameSku")),
                asString(body.get("frameName")),
                asString(body.get("lensSku")),
                asString(body.get("lensName")),
                asString(body.get("technicianName")));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo lệnh gia công thành công", job));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<LabJobResponse>> createLabJob(
            @Valid @RequestBody CreateLabJobRequest request) {
        log.info("API POST /api/lab-jobs - Request: {}", request);
        String key = request.getEffectiveIdempotencyKey();
        boolean exists = key != null && labJobRepository.findByIdempotencyKey(key).isPresent();

        LabJobResponse created = labJobService.createLabJob(request);
        HttpStatus httpStatus = exists ? HttpStatus.OK : HttpStatus.CREATED;
        ApiResponse<LabJobResponse> response = ApiResponse.<LabJobResponse>builder()
                .code(httpStatus.value())
                .message(exists ? "Lệnh gia công đã tồn tại (idempotent)" : "Tạo lệnh gia công mài kính thành công")
                .data(created)
                .build();
        return ResponseEntity.status(httpStatus).body(response);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<LabJobResponse>> updateJobStatus(
            @PathVariable("id") Long id,
            @Valid @RequestBody UpdateJobStatusRequest request) {
        log.info("API PATCH /api/lab-jobs/{}/status - Request: {}", id, request);
        LabJobResponse updated = labJobService.updateJobStatus(id, request);
        return ResponseEntity.ok(ApiResponse.success("Cập nhật trạng thái gia công QC thành công", updated));
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(value.toString());
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
