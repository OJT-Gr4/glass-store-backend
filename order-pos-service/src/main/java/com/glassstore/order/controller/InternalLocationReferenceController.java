package com.glassstore.order.controller;

import com.glassstore.common.dto.ApiResponse;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.repository.PosSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class InternalLocationReferenceController {

    private final OrderRepository orderRepository;
    private final PosSessionRepository posSessionRepository;

    @GetMapping("/internal/location-references/{locationCode}")
    public ResponseEntity<ApiResponse<Boolean>> isLocationReferenced(
            @PathVariable String locationCode,
            @RequestParam String name) {
        boolean referenced = orderRepository.existsByBranchCodeIgnoreCaseOrBranchNameIgnoreCase(
                locationCode, locationCode)
                || orderRepository.existsByBranchCodeIgnoreCaseOrBranchNameIgnoreCase(name, name)
                || posSessionRepository.existsByBranchNameIgnoreCase(locationCode)
                || posSessionRepository.existsByBranchNameIgnoreCase(name);
        return ResponseEntity.ok(ApiResponse.success("Kiểm tra tham chiếu đơn hàng thành công", referenced));
    }
}
