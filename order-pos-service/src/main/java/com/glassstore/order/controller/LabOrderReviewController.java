package com.glassstore.order.controller;

import com.glassstore.common.dto.ApiResponse;
import com.glassstore.common.security.CurrentActor;
import com.glassstore.order.service.LabOrderReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.List;

@RestController @RequestMapping("/api/orders") @RequiredArgsConstructor
public class LabOrderReviewController {
    private final LabOrderReviewService reviews;
    @GetMapping("/lab-pending")
    public ApiResponse<List<LabOrderReviewService.Pending>> pending() { return ApiResponse.success("OK", reviews.pending()); }
    public record ReviewRequest(Long prescriptionId, Long expectedVersion, boolean confirmed) {}
    @PutMapping("/{id}/lab-prescription")
    public ApiResponse<LabOrderReviewService.ReviewResult> approve(@PathVariable("id") Long id, @RequestBody ReviewRequest request) {
        if (!CurrentActor.hasRole("ADMIN") && !CurrentActor.hasRole("SALES_STAFF")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return ApiResponse.success("Đã xác nhận toa; hệ thống đang gửi lệnh sang Lab", reviews.approve(id, request.prescriptionId(), request.expectedVersion(), request.confirmed(), CurrentActor.name()));
    }
}
