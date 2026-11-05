package com.glassstore.lab.service.impl;

import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.lab.dto.CreateLabJobRequest;
import com.glassstore.lab.dto.LabJobResponse;
import com.glassstore.lab.dto.UpdateJobStatusRequest;
import com.glassstore.lab.entity.LabJob;
import com.glassstore.lab.entity.LabJobHistory;
import com.glassstore.lab.entity.LabJobStatus;
import com.glassstore.lab.entity.Prescription;
import com.glassstore.lab.repository.LabJobHistoryRepository;
import com.glassstore.lab.repository.LabJobRepository;
import com.glassstore.lab.repository.PrescriptionRepository;
import com.glassstore.lab.service.LabJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LabJobServiceImpl implements LabJobService {

    private final LabJobRepository labJobRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final LabJobHistoryRepository historyRepository;

    @Override
    @Transactional(readOnly = true)
    public List<LabJobResponse> getLabJobs(LabJobStatus status) {
        List<LabJob> jobs = status == null
                ? labJobRepository.findAllByOrderByCreatedAtDesc()
                : labJobRepository.findByStatusOrderByCreatedAtDesc(status);
        if (jobs.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> prescriptionIds = jobs.stream()
                .map(LabJob::getPrescriptionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Prescription> prescriptions = prescriptionIds.isEmpty()
                ? Collections.emptyMap()
                : prescriptionRepository.findAllById(prescriptionIds).stream()
                .collect(Collectors.toMap(Prescription::getId, prescription -> prescription, (left, right) -> left));

        return jobs.stream()
                .map(job -> LabJobResponse.from(job, prescriptions.get(job.getPrescriptionId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public LabJobResponse getLabJobById(Long id) {
        LabJob job = findJob(id);
        Prescription prescription = job.getPrescriptionId() == null
                ? null
                : prescriptionRepository.findById(job.getPrescriptionId()).orElse(null);
        return LabJobResponse.from(job, prescription);
    }

    @Override
    @Transactional
    public LabJobResponse createLabJob(CreateLabJobRequest request) {
        if (request.getOrderId() == null) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Thiếu mã đơn hàng để tạo lệnh gia công");
        }

        String key = request.getEffectiveIdempotencyKey();

        // 1. Kiểm tra IdempotencyKey chống sinh trùng
        Optional<LabJob> existingByKey = labJobRepository.findByIdempotencyKey(key);
        if (existingByKey.isPresent()) {
            log.info("LabJob với idempotencyKey {} đã tồn tại, trả về job hiện tại", key);
            return getResponse(existingByKey.get());
        }

        final Long requestedRxId = request.getEffectivePrescriptionId();
        Long prescriptionId = requestedRxId;
        Prescription prescription = null;
        if (requestedRxId != null) {
            prescription = prescriptionRepository.findById(requestedRxId)
                    .orElseThrow(() -> ApiException.of(ErrorCode.VALIDATION_ERROR,
                            "Không tìm thấy toa kính với ID: " + requestedRxId));
        }

        // Tự động tạo và lưu toa kính nếu chưa có nhưng request truyền các thông số khúc xạ
        if (prescription == null && (request.getEffectiveOdSphere() != null || request.getEffectiveOsSphere() != null || request.getEffectivePd() != null)) {
            prescription = Prescription.builder()
                    .customerId(request.getCustomerId() != null ? request.getCustomerId() : 1L)
                    .customerName(request.getCustomerName() != null ? request.getCustomerName() : "Khách hàng POS")
                    .optometristId(1L)
                    .optometristName("Hệ thống POS")
                    .odSph(request.getEffectiveOdSphere() != null ? request.getEffectiveOdSphere() : BigDecimal.ZERO)
                    .odCyl(request.getEffectiveOdCyl() != null ? request.getEffectiveOdCyl() : BigDecimal.ZERO)
                    .odAxis(request.getEffectiveOdAxis() != null ? request.getEffectiveOdAxis() : 0)
                    .odAdd(request.getEffectiveOdAdd() != null ? request.getEffectiveOdAdd() : BigDecimal.ZERO)
                    .osSph(request.getEffectiveOsSphere() != null ? request.getEffectiveOsSphere() : BigDecimal.ZERO)
                    .osCyl(request.getEffectiveOsCyl() != null ? request.getEffectiveOsCyl() : BigDecimal.ZERO)
                    .osAxis(request.getEffectiveOsAxis() != null ? request.getEffectiveOsAxis() : 0)
                    .osAdd(request.getEffectiveOsAdd() != null ? request.getEffectiveOsAdd() : BigDecimal.ZERO)
                    .pd(request.getEffectivePd() != null ? request.getEffectivePd() : new BigDecimal("63.0"))
                    .notes(request.getPrescription() != null ? request.getPrescription().getNotes() : null)
                    .createdAt(LocalDateTime.now())
                    .build();
            prescription = prescriptionRepository.save(prescription);
            prescriptionId = prescription.getId();
        }

        if (prescription == null) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Thiếu thông tin toa kính cho lệnh gia công");
        }

        // Kiểm tra đúng khách hàng của đơn hàng
        if (request.getCustomerId() != null && prescription.getCustomerId() != null
                && !request.getCustomerId().equals(prescription.getCustomerId())) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Toa kính không đúng khách hàng của đơn hàng");
        }

        requireCompletePrescription(prescription);

        String resolvedCustomerName = request.getCustomerName() == null || request.getCustomerName().isBlank()
                ? (prescription != null ? prescription.getCustomerName() : "Khách hàng")
                : request.getCustomerName();

        String sku = request.getSku();
        if (sku == null || sku.isBlank()) {
            sku = request.getLensSku() != null ? request.getLensSku() : request.getFrameSku();
        }

        LabJob job = LabJob.builder()
                .idempotencyKey(key)
                .orderId(request.getOrderId())
                .orderItemId(request.getOrderItemId())
                .orderCode(request.getOrderCode())
                .trayNumber(request.getEffectiveTrayNumber())
                .prescriptionId(prescription != null ? prescription.getId() : prescriptionId)
                .customerName(resolvedCustomerName)
                .sku(sku)
                .frameSku(request.getFrameSku())
                .frameName(request.getFrameName())
                .lensSku(request.getLensSku() != null ? request.getLensSku() : sku)
                .lensName(request.getLensName())
                .technicianId(request.getTechnicianId())
                .technicianName(request.getTechnicianName())
                .technicianNotes(request.getTechnicianNotes())
                .status(LabJobStatus.CREATED)
                .build();

        LabJob saved = labJobRepository.save(job);
        recordStatus(saved, null, LabJobStatus.CREATED.name(), request.getTechnicianName(), "Tạo lệnh gia công từ đơn hàng POS");
        log.info("Created lab job {} for order {} (idempotencyKey: {}, code: {}, tray: {})",
                saved.getId(), saved.getOrderId(), saved.getIdempotencyKey(), saved.getOrderCode(), saved.getTrayNumber());

        LabJobResponse response = LabJobResponse.from(saved, prescription);
        if (prescription == null && response != null) {
            if (request.getEffectiveOdSphere() != null) response.setOdSphere(request.getEffectiveOdSphere());
            if (request.getEffectiveOdCyl() != null) response.setOdCyl(request.getEffectiveOdCyl());
            if (request.getEffectiveOdAxis() != null) response.setOdAxis(request.getEffectiveOdAxis());
            if (request.getEffectiveOdAdd() != null) response.setOdAdd(request.getEffectiveOdAdd());
            if (request.getEffectiveOsSphere() != null) response.setOsSphere(request.getEffectiveOsSphere());
            if (request.getEffectiveOsCyl() != null) response.setOsCyl(request.getEffectiveOsCyl());
            if (request.getEffectiveOsAxis() != null) response.setOsAxis(request.getEffectiveOsAxis());
            if (request.getEffectiveOsAdd() != null) response.setOsAdd(request.getEffectiveOsAdd());
            if (request.getEffectivePd() != null) response.setPd(request.getEffectivePd());
        }
        return response;
    }

    @Override
    @Transactional
    public LabJobResponse createFromOrder(
            Long orderId,
            Long prescriptionId,
            String frameSku,
            String frameName,
            String lensSku,
            String lensName,
            String technicianName) {
        CreateLabJobRequest req = CreateLabJobRequest.builder()
                .orderId(orderId)
                .prescriptionId(prescriptionId)
                .frameSku(frameSku)
                .frameName(frameName)
                .lensSku(lensSku)
                .lensName(lensName)
                .technicianName(technicianName)
                .build();
        return createLabJob(req);
    }

    @Override
    @Transactional
    public LabJobResponse updateJobStatus(Long id, UpdateJobStatusRequest request) {
        LabJob job = findJob(id);
        LabJobStatus requestedStatus = request.getStatus();
        if (requestedStatus == null) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Trạng thái cập nhật không được để trống");
        }

        LabJobStatus current = job.getStatus();
        // Kiểm tra State Machine: CREATED -> QUEUED -> IN_PROGRESS -> QC_PASSED / QC_FAILED -> COMPLETED / CANCELLED
        if (current == requestedStatus || !current.canTransitionTo(requestedStatus)) {
            throw ApiException.of(ErrorCode.LAB_JOB_INVALID_TRANSITION,
                    "Không thể chuyển lệnh gia công từ " + current + " sang " + requestedStatus);
        }

        String qcNotes = request.getEffectiveQcNotes();
        if (requestedStatus == LabJobStatus.QC_FAILED && (qcNotes == null || qcNotes.isBlank())) {
            throw ApiException.of(ErrorCode.LAB_JOB_QC_REASON_REQUIRED,
                    "Phải ghi rõ lý do khi đánh giá QC không đạt");
        }

        LocalDateTime now = LocalDateTime.now();
        if (requestedStatus == LabJobStatus.IN_PROGRESS) {
            job.setStartedAt(now);
            String technicianNotes = request.getEffectiveTechnicianNotes();
            if (technicianNotes != null) {
                job.setTechnicianNotes(technicianNotes);
            }
        }
        if (requestedStatus == LabJobStatus.QC_PASSED
                || requestedStatus == LabJobStatus.COMPLETED
                || requestedStatus == LabJobStatus.DELIVERED) {
            job.setCompletedAt(now);
        }
        if (qcNotes != null) {
            job.setQcNotes(qcNotes);
        }
        if (request.getTechnicianId() != null) {
            job.setTechnicianId(request.getTechnicianId());
        }
        if (request.getTechnicianName() != null && !request.getTechnicianName().isBlank()) {
            job.setTechnicianName(request.getTechnicianName());
        }
        job.setStatus(requestedStatus);
        job.setUpdatedAt(now);

        LabJob saved = labJobRepository.save(job);
        recordStatus(saved, current.name(), requestedStatus.name(), request.getTechnicianName(), qcNotes != null ? qcNotes : request.getNotes());
        return getResponse(saved);
    }

    private LabJob findJob(Long id) {
        return labJobRepository.findById(id)
                .orElseThrow(() -> ApiException.of(ErrorCode.LAB_JOB_NOT_FOUND,
                        "Không tìm thấy lệnh mài kính ID: " + id));
    }

    private LabJobResponse getResponse(LabJob job) {
        Prescription prescription = job.getPrescriptionId() == null
                ? null
                : prescriptionRepository.findById(job.getPrescriptionId()).orElse(null);
        return LabJobResponse.from(job, prescription);
    }

    private void recordStatus(
            LabJob job, String fromStatus, String toStatus, String actor, String reason) {
        historyRepository.save(LabJobHistory.builder()
                .labJobId(job.getId())
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .changedBy(actor == null || actor.isBlank() ? "SYSTEM" : actor)
                .note(reason)
                .changedAt(LocalDateTime.now())
                .build());
    }

    private void requireCompletePrescription(Prescription prescription) {
        if (prescription.getOdSph() == null || prescription.getOsSph() == null
                || prescription.getOdCyl() == null || prescription.getOsCyl() == null
                || prescription.getOdAxis() == null || prescription.getOsAxis() == null
                || prescription.getPd() == null) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR,
                    "Toa kính thiếu thông số đo bắt buộc (SPH, CYL, AXIS, PD)");
        }
        validateAxis(prescription.getOdAxis(), "OD");
        validateAxis(prescription.getOsAxis(), "OS");
        checkRange(prescription.getOdSph(), -30, 30, "OD SPH");
        checkRange(prescription.getOsSph(), -30, 30, "OS SPH");
        checkRange(prescription.getOdCyl(), -10, 10, "OD CYL");
        checkRange(prescription.getOsCyl(), -10, 10, "OS CYL");
        checkRange(prescription.getPd(), 40, 90, "PD");
    }

    private void validateAxis(Integer axis, String eye) {
        if (axis != null && (axis < 0 || axis > 180)) {
            throw ApiException.of(ErrorCode.PRESCRIPTION_INCOMPLETE,
                    "Trục loạn " + eye + " phải nằm trong khoảng 0-180 độ");
        }
    }

    private void checkRange(BigDecimal value, double min, double max, String label) {
        if (value != null && (value.doubleValue() < min || value.doubleValue() > max)) {
            throw ApiException.of(ErrorCode.PRESCRIPTION_INCOMPLETE,
                    "Giá trị " + label + " nằm ngoài khoảng hợp lệ (" + min + " .. " + max + ")");
        }
    }
}
