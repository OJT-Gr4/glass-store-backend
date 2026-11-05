package com.glassstore.lab.controller;

import com.glassstore.common.dto.ApiResponse;
import com.glassstore.lab.entity.EyeExamAppointment;
import com.glassstore.lab.repository.EyeExamAppointmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/appointments", "/api/v1/appointments"})
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class EyeExamAppointmentController {

    private final EyeExamAppointmentRepository appointmentRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<List<EyeExamAppointment>>> getAllAppointments() {
        List<EyeExamAppointment> list = appointmentRepository.findAllByOrderByPriorityLevelAscCreatedAtAsc();
        if (com.glassstore.common.security.CurrentActor.customer()) list=list.stream()
            .filter(a -> java.util.Objects.equals(a.getCustomerId(),com.glassstore.common.security.CurrentActor.id())).toList();
        return ResponseEntity.ok(ApiResponse.success("Lấy danh sách hàng đợi đo mắt thành công", list));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<EyeExamAppointment>> getAppointmentById(@PathVariable("id") Long id) {
        return appointmentRepository.findById(id)
                .map(a -> {
                    com.glassstore.common.security.CurrentActor.requireOwner(a.getCustomerId());
                    return ResponseEntity.ok(ApiResponse.success("Lấy chi tiết lịch hẹn thành công", a));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<EyeExamAppointment>> createAppointment(@RequestBody EyeExamAppointment appointment) {
        appointment.setId(null);
        if (com.glassstore.common.security.CurrentActor.customer()) {
            appointment.setCustomerId(com.glassstore.common.security.CurrentActor.id());
            appointment.setStatus("WAITING"); appointment.setStatusLabel("Đang chờ");
            appointment.setPriorityLevel(2); appointment.setWaitMinutes(0);
        }
        if (appointment.getTicketCode() == null || appointment.getTicketCode().isEmpty()) {
            appointment.setTicketCode("EP-" + (1000 + (int)(Math.random() * 9000)));
        }
        appointment.setCreatedAt(LocalDateTime.now());
        if (appointment.getStatus() == null) {
            appointment.setStatus("WAITING");
            appointment.setStatusLabel("Đang chờ");
        }
        EyeExamAppointment saved = appointmentRepository.save(appointment);
        log.info("Tiếp nhận lượt khám mắt thành công: {}", saved.getTicketCode());
        return ResponseEntity.ok(ApiResponse.success("Tiếp nhận lượt khám mắt thành công", saved));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<EyeExamAppointment>> updateStatus(
            @PathVariable("id") Long id,
            @RequestBody Map<String, String> body) {
        return appointmentRepository.findById(id).map(a -> {
            if (body.containsKey("status")) {
                String newStatus = body.get("status");
                a.setStatus(newStatus);
                // Đồng bộ statusLabel phù hợp với SRS
                if ("WAITING".equalsIgnoreCase(newStatus)) {
                    a.setStatusLabel("Đang chờ");
                } else if ("IN_EXAM".equalsIgnoreCase(newStatus)) {
                    a.setStatusLabel("Đang đo");
                } else if ("COMPLETED".equalsIgnoreCase(newStatus)) {
                    a.setStatusLabel("Đã xong");
                }
            }
            if (body.containsKey("statusLabel")) a.setStatusLabel(body.get("statusLabel"));
            if (body.containsKey("roomName")) a.setRoomName(body.get("roomName"));
            if (body.containsKey("examStep")) a.setExamStep(body.get("examStep"));
            EyeExamAppointment updated = appointmentRepository.save(a);
            log.info("Cập nhật trạng thái lịch hẹn ID {} -> {}", id, updated.getStatus());
            return ResponseEntity.ok(ApiResponse.success("Cập nhật trạng thái lịch hẹn thành công", updated));
        }).orElse(ResponseEntity.notFound().build());
    }
}
