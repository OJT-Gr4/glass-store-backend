package com.glassstore.lab.controller;

import com.glassstore.common.dto.ApiResponse;
import com.glassstore.common.security.CurrentActor;
import com.glassstore.lab.client.PrescriptionCustomerClient;
import com.glassstore.lab.client.CatalogLocationClient;
import com.glassstore.lab.dto.CreateAppointmentRequest;
import com.glassstore.lab.dto.UpdateAppointmentStatusRequest;
import com.glassstore.lab.entity.EyeExamAppointment;
import com.glassstore.lab.repository.EyeExamAppointmentRepository;
import com.glassstore.lab.repository.PrescriptionRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping({"/api/appointments", "/api/v1/appointments"})
@RequiredArgsConstructor
public class EyeExamAppointmentController {
    private static final ZoneId BOOKING_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final EyeExamAppointmentRepository appointments;
    private final PrescriptionRepository prescriptions;
    private final PrescriptionCustomerClient customers;
    private final CatalogLocationClient locations;

    @GetMapping("/location/{locationCode}/exists")
    public ResponseEntity<ApiResponse<Boolean>> hasAppointmentsForLocation(@PathVariable String locationCode) {
        return ResponseEntity.ok(ApiResponse.success("Appointment location reference",
                appointments.existsByLocationCodeIgnoreCase(locationCode)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<EyeExamAppointment>>> getAllAppointments() {
        List<EyeExamAppointment> list = appointments.findAllByOrderByPriorityLevelAscCreatedAtAsc();
        if (CurrentActor.customer()) list = list.stream()
                .filter(a -> Objects.equals(a.getCustomerId(), CurrentActor.id())).toList();
        return ResponseEntity.ok(ApiResponse.success("Lấy danh sách lịch hẹn thành công", list));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<EyeExamAppointment>> getAppointmentById(@PathVariable Long id) {
        EyeExamAppointment appointment = requireAppointment(id);
        CurrentActor.requireOwner(appointment.getCustomerId());
        return ResponseEntity.ok(ApiResponse.success("Lấy lịch hẹn thành công", appointment));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<ApiResponse<EyeExamAppointment>> createAppointment(@Valid @RequestBody CreateAppointmentRequest request) {
        boolean customer = CurrentActor.customer();
        Long customerId = customer ? CurrentActor.id() : request.getCustomerId();
        if (customerId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vui lòng chọn khách hàng");
        LocalDateTime scheduled = parseTime(request.getScheduledTime());
        if (customer && !scheduled.isAfter(LocalDateTime.now(BOOKING_ZONE)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Giờ đặt lịch phải ở tương lai");
        String customerName = customers.requireCustomer(customerId);
        String locationCode = request.getLocationCode();
        if (locationCode != null && !locationCode.isBlank()) {
            locationCode = locations.requireActiveLocation(locationCode.trim());
        }
        EyeExamAppointment appointment = EyeExamAppointment.builder()
                .customerId(customerId).customerName(customerName).customerPhone(request.getCustomerPhone())
                .locationCode(locationCode).scheduledDate(scheduled.toLocalDate())
                .ticketCode("EP-" + UUID.randomUUID().toString().toUpperCase())
                .ticketType(customer ? "ONLINE" : "WALK_IN")
                .roomName(customer ? null : request.getRoomName())
                .scheduledTime(scheduled.toString()).notes(request.getNotes())
                .status("WAITING").statusLabel("Đang chờ").createdAt(LocalDateTime.now(BOOKING_ZONE)).build();
        return ResponseEntity.ok(ApiResponse.success("Tạo lịch hẹn thành công", appointments.saveAndFlush(appointment)));
    }

    @PatchMapping("/{id}/status")
    @Transactional
    public ResponseEntity<ApiResponse<EyeExamAppointment>> updateStatus(@PathVariable Long id,
            @Valid @RequestBody UpdateAppointmentStatusRequest request) {
        EyeExamAppointment appointment = requireAppointment(id);
        if (!Objects.equals(request.getExpectedVersion(), appointment.getVersion()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Lịch hẹn đã thay đổi. Vui lòng tải lại");
        boolean clinician = CurrentActor.hasRole("ADMIN") || CurrentActor.hasRole("OPTOMETRIST");
        String status = request.getStatus();
        if (!"CANCELLED".equals(status) && !clinician)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ người đo mắt được bắt đầu hoặc hoàn tất đo");
        boolean allowed = "WAITING".equals(appointment.getStatus()) && ("IN_EXAM".equals(status) || "CANCELLED".equals(status))
                || "IN_EXAM".equals(appointment.getStatus()) && ("COMPLETED".equals(status) || "CANCELLED".equals(status)) && clinician;
        if (!allowed) throw new ResponseStatusException(HttpStatus.CONFLICT, "Không thể chuyển trạng thái lịch hẹn");
        if ("IN_EXAM".equals(appointment.getStatus()) && !CurrentActor.hasRole("ADMIN")
                && !Objects.equals(appointment.getOptometristId(), CurrentActor.id()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Lịch hẹn đang được người đo khác xử lý");
        if ("IN_EXAM".equals(status)) {
            if (request.getRoomName() == null || request.getRoomName().isBlank())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vui lòng chọn phòng đo");
            appointment.setOptometristId(CurrentActor.id());
            appointment.setOptometristName(CurrentActor.name());
        }
        if ("COMPLETED".equals(status)) {
            if (request.getPrescriptionId() == null)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cần toa kính đã lưu để hoàn tất đo");
            var prescription = prescriptions.findById(request.getPrescriptionId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không tìm thấy toa kính"));
            if (!Objects.equals(prescription.getCustomerId(), appointment.getCustomerId()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Toa kính không thuộc khách hàng của lịch hẹn");
            if (!CurrentActor.hasRole("ADMIN") && !Objects.equals(prescription.getOptometristId(), CurrentActor.id()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Toa kính phải được người đo hiện tại lưu");
            appointment.setPrescriptionId(prescription.getId());
        }
        appointment.setStatus(status);
        appointment.setStatusLabel(switch (status) {
            case "IN_EXAM" -> "Đang đo";
            case "COMPLETED" -> "Đã xong";
            default -> "Đã hủy";
        });
        if (request.getRoomName() != null) appointment.setRoomName(request.getRoomName());
        if (request.getExamStep() != null) appointment.setExamStep(request.getExamStep());
        return ResponseEntity.ok(ApiResponse.success("Cập nhật lịch hẹn thành công", appointments.saveAndFlush(appointment)));
    }

    private EyeExamAppointment requireAppointment(Long id) {
        return appointments.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lịch hẹn"));
    }

    private LocalDateTime parseTime(String value) {
        try { return OffsetDateTime.parse(value).atZoneSameInstant(BOOKING_ZONE).toLocalDateTime(); }
        catch (DateTimeParseException ignored) {
            try { return LocalDateTime.parse(value); }
            catch (DateTimeParseException error) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ngày giờ lịch hẹn không hợp lệ (ISO 8601)");
            }
        }
    }
}
