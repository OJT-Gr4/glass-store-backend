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

@RestController
@RequestMapping("/api/lab-jobs")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class LabJobController {

    private final LabJobService labJobService;
    private final LabJobRepository labJobRepository;
    private final LabJobHistoryRepository historyRepository;

    @GetMapping("/by-order/{orderId}")
    public ResponseEntity<ApiResponse<LabJobResponse>> getByOrder(@PathVariable("orderId") Long orderId) {
        var jobs = labJobRepository.findAllByOrderIdOrderByIdAsc(orderId);
        if (jobs.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND);
        // Report an unfinished job first: one passed item cannot release the whole order.
        var passed = java.util.Set.of(LabJobStatus.QC_PASSED, LabJobStatus.READY_FOR_DELIVERY,
                LabJobStatus.DELIVERED, LabJobStatus.COMPLETED);
        var job = jobs.stream().filter(item -> !passed.contains(item.getStatus())).findFirst().orElse(jobs.getFirst());
        return ResponseEntity.ok(ApiResponse.success("OK", labJobService.getLabJobById(job.getId())));
    }


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
            @Valid @RequestBody CreateLabJobRequest request) {
        request.setTechnicianName(com.glassstore.common.security.CurrentActor.name());
        request.setTechnicianId(null);
        LabJobResponse job = labJobService.createLabJob(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo lệnh gia công thành công", job));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<LabJobResponse>> createLabJob(
            @Valid @RequestBody CreateLabJobRequest request) {
        log.info("API POST /api/lab-jobs - Request: {}", request);
        request.setTechnicianName(com.glassstore.common.security.CurrentActor.name());
        request.setTechnicianId(null);
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
        request.setTechnicianName(com.glassstore.common.security.CurrentActor.name());
        request.setTechnicianId(com.glassstore.common.security.CurrentActor.id());
        LabJobResponse updated = labJobService.updateJobStatus(id, request);
        return ResponseEntity.ok(ApiResponse.success("Cập nhật trạng thái gia công QC thành công", updated));
    }

}
