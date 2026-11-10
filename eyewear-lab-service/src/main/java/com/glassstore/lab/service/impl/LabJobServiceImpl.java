package com.glassstore.lab.service.impl;

import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.lab.dto.CreateLabJobRequest;
import com.glassstore.lab.dto.CreateLabQcRequest;
import com.glassstore.lab.dto.LabJobResponse;
import com.glassstore.lab.dto.LabQcResponse;
import com.glassstore.lab.dto.ReworkJobRequest;
import com.glassstore.lab.dto.UpdateJobStatusRequest;
import com.glassstore.lab.entity.LabJob;
import com.glassstore.lab.entity.LabJobHistory;
import com.glassstore.lab.entity.LabJobStatus;
import com.glassstore.lab.entity.LabQcHistory;
import com.glassstore.lab.entity.Prescription;
import com.glassstore.lab.entity.QcResult;
import com.glassstore.lab.repository.LabJobHistoryRepository;
import com.glassstore.lab.repository.LabJobRepository;
import com.glassstore.lab.repository.LabQcHistoryRepository;
import com.glassstore.lab.repository.PrescriptionRepository;
import com.glassstore.lab.service.LabJobService;
import com.glassstore.lab.service.LabNotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final LabQcHistoryRepository qcHistoryRepository;
    private final com.glassstore.lab.service.PrescriptionService prescriptionService;

    @Autowired(required = false)
    private LabNotificationPublisher labNotificationPublisher;

    public void setLabNotificationPublisher(LabNotificationPublisher labNotificationPublisher) {
        this.labNotificationPublisher = labNotificationPublisher;
    }

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
    public List<LabJobResponse> getLabJobsByOrderId(Long orderId) {
        if (orderId == null) {
            return Collections.emptyList();
        }
        List<LabJob> jobs = labJobRepository.findAllByOrderIdOrderByIdAsc(orderId);
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
            prescription = request.getPrescriptionVersion() != null
                    ? prescriptionService.readRevisionAsPrescription(requestedRxId, request.getPrescriptionVersion())
                    : prescriptionRepository.findById(requestedRxId)
                    .orElseThrow(() -> ApiException.of(ErrorCode.VALIDATION_ERROR,
                            "Không tìm thấy toa kính với ID: " + requestedRxId));
        }

        // Fabrication uses an existing clinical prescription, never invented measurements or staff IDs.
        if (prescription == null) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Thiếu thông tin toa kính cho lệnh gia công");
        }

        // Kiểm tra đúng khách hàng của đơn hàng
        if (request.getCustomerId() != null && !request.getCustomerId().equals(prescription.getCustomerId())) {
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
                .prescriptionSnapshot(com.glassstore.lab.entity.LabPrescriptionSnapshot.from(prescription))
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
        if ((requestedStatus == LabJobStatus.QC_FAILED || requestedStatus == LabJobStatus.REWORK) && (qcNotes == null || qcNotes.isBlank())) {
            throw ApiException.of(ErrorCode.LAB_JOB_QC_REASON_REQUIRED,
                    "Phải ghi rõ lý do khi đánh giá QC không đạt hoặc yêu cầu mài lại");
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

        // Subtask 7.2 & 7.3: Phân biệt cần mài lại với hỏng vật tư, ghi nhận rework count
        if (requestedStatus == LabJobStatus.QC_FAILED) {
            job.setQcResult("FAIL");
            job.setMaterialDamaged(Boolean.TRUE.equals(request.getMaterialDamaged()));
            // LƯU Ý QUAN TRỌNG (Subtask 7.2): Không tự trừ một cặp phôi khi QC_FAILED!
        } else if (requestedStatus == LabJobStatus.REWORK) {
            int rc = (job.getReworkCount() != null ? job.getReworkCount() : 0) + 1;
            job.setReworkCount(rc);
            job.setQcResult("REWORK");
            job.setMaterialDamaged(false);
        } else if (requestedStatus == LabJobStatus.QC_PASSED) {
            job.setQcResult("PASS");
        } else if (current == LabJobStatus.QC_FAILED && requestedStatus == LabJobStatus.IN_PROGRESS) {
            int rc = (job.getReworkCount() != null ? job.getReworkCount() : 0) + 1;
            job.setReworkCount(rc);
            job.setQcResult("REWORK");
            job.setMaterialDamaged(false);
        }

        job.setStatus(requestedStatus);
        job.setUpdatedAt(now);

        LabJob saved = labJobRepository.save(job);
        recordStatus(saved, current.name(), requestedStatus.name(), request.getTechnicianName(), qcNotes != null ? qcNotes : request.getNotes());

        if (requestedStatus == LabJobStatus.QC_FAILED) {
            recordQc(saved, QcResult.FAIL, qcNotes, Boolean.TRUE.equals(request.getMaterialDamaged()),
                    request.getTechnicianId(), request.getTechnicianName(), saved.getReworkCount(), request.getIdempotencyKey());
        } else if (requestedStatus == LabJobStatus.REWORK) {
            recordQc(saved, QcResult.REWORK, qcNotes, false,
                    request.getTechnicianId(), request.getTechnicianName(), saved.getReworkCount(), request.getIdempotencyKey());
        } else if (requestedStatus == LabJobStatus.QC_PASSED) {
            recordQc(saved, QcResult.PASS, qcNotes != null ? qcNotes : request.getNotes(), false,
                    request.getTechnicianId(), request.getTechnicianName(), saved.getReworkCount(), request.getIdempotencyKey());
        } else if (current == LabJobStatus.QC_FAILED && requestedStatus == LabJobStatus.IN_PROGRESS) {
            recordQc(saved, QcResult.REWORK, request.getEffectiveTechnicianNotes() != null ? request.getEffectiveTechnicianNotes() : "Bắt đầu mài lại", false,
                    request.getTechnicianId(), request.getTechnicianName(), saved.getReworkCount(), request.getIdempotencyKey());
        }

        if (requestedStatus == LabJobStatus.QC_PASSED
                || requestedStatus == LabJobStatus.READY_FOR_DELIVERY
                || requestedStatus == LabJobStatus.COMPLETED) {
            publishLabReadySafely(saved);
        } else if (requestedStatus == LabJobStatus.QC_FAILED || requestedStatus == LabJobStatus.REWORK) {
            publishLabReworkSafely(saved);
        }

        return getResponse(saved);
    }

    @Override
    @Transactional
    public LabQcResponse submitQcEvaluation(Long id, CreateLabQcRequest request) {
        LabJob job = findJob(id);
        if (request.getResult() == null) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Kết quả QC không được để trống (PASS, FAIL, REWORK)");
        }

        // Subtask 7.3: Idempotency check chống nhân đôi / retry
        String idempotencyKey = request.getIdempotencyKey();
        if (idempotencyKey != null && !idempotencyKey.isBlank() && qcHistoryRepository != null) {
            Optional<LabQcHistory> existing = qcHistoryRepository.findByIdempotencyKey(idempotencyKey.trim());
            if (existing.isPresent()) {
                log.info("QC evaluation with idempotencyKey {} already exists, returning existing result", idempotencyKey);
                return LabQcResponse.from(existing.get());
            }
        }

        String reason = request.getEffectiveReason();
        if ((request.getResult() == QcResult.FAIL || request.getResult() == QcResult.REWORK)
                && (reason == null || reason.isBlank())) {
            throw ApiException.of(ErrorCode.LAB_JOB_QC_REASON_REQUIRED,
                    "Phải ghi rõ lý do khi đánh giá QC không đạt hoặc yêu cầu mài lại");
        }

        // Subtask 7.2: Phân biệt cần mài lại với thực sự hỏng vật tư; không tự trừ một cặp phôi chỉ vì nhấn QC_FAILED
        boolean materialDamaged = Boolean.TRUE.equals(request.getMaterialDamaged());
        if (request.getResult() == QcResult.REWORK) {
            materialDamaged = false; // Mài lại phôi hiện tại, không phải hỏng vật tư
        }

        LocalDateTime now = LocalDateTime.now();
        String actor = request.getInspectorName() != null && !request.getInspectorName().isBlank()
                ? request.getInspectorName()
                : (job.getTechnicianName() != null ? job.getTechnicianName() : "QC_STAFF");
        Long inspectorId = request.getInspectorId() != null ? request.getInspectorId() : job.getTechnicianId();

        int currentReworkCount = job.getReworkCount() != null ? job.getReworkCount() : 0;
        LabJobStatus previousStatus = job.getStatus();

        if (request.getResult() == QcResult.PASS) {
            job.setStatus(LabJobStatus.QC_PASSED);
            job.setCompletedAt(now);
            job.setQcResult("PASS");
        } else if (request.getResult() == QcResult.REWORK) {
            job.setStatus(LabJobStatus.REWORK);
            currentReworkCount++;
            job.setReworkCount(currentReworkCount);
            job.setQcResult("REWORK");
            job.setMaterialDamaged(false);
        } else { // FAIL
            job.setStatus(LabJobStatus.QC_FAILED);
            job.setQcResult("FAIL");
            job.setMaterialDamaged(materialDamaged);
            // LƯU Ý QUAN TRỌNG (Subtask 7.2): Không tự trừ một cặp phôi chỉ vì nhấn QC_FAILED
        }

        if (reason != null) {
            job.setQcNotes(reason);
        }
        job.setUpdatedAt(now);
        LabJob savedJob = labJobRepository.save(job);

        LabQcHistory qcRecord = LabQcHistory.builder()
                .idempotencyKey(idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey.trim() : null)
                .labJobId(savedJob.getId())
                .orderId(savedJob.getOrderId())
                .prescriptionId(savedJob.getPrescriptionId())
                .qcResult(request.getResult())
                .powerCheckPassed(request.getPowerCheckPassed())
                .opticalCenterCheckPassed(request.getOpticalCenterCheckPassed())
                .surfaceQualityCheckPassed(request.getSurfaceQualityCheckPassed())
                .bevelEdgingCheckPassed(request.getBevelEdgingCheckPassed())
                .frameFittingCheckPassed(request.getFrameFittingCheckPassed())
                .failureReason(reason)
                .defectType(request.getDefectType())
                .materialDamaged(materialDamaged)
                .notes(request.getNotes())
                .inspectorId(inspectorId)
                .inspectorName(actor)
                .reworkCount(currentReworkCount)
                .inspectedAt(now)
                .build();

        LabQcHistory savedQc = qcHistoryRepository != null ? qcHistoryRepository.save(qcRecord) : qcRecord;
        recordStatus(savedJob, previousStatus != null ? previousStatus.name() : null,
                savedJob.getStatus().name(), actor,
                "QC: " + request.getResult().name() + (reason != null ? " - " + reason : ""));

        if (request.getResult() == QcResult.PASS) {
            publishLabReadySafely(savedJob);
        } else {
            publishLabReworkSafely(savedJob);
        }

        return LabQcResponse.from(savedQc);
    }

    @Override
    @Transactional
    public LabJobResponse requestRework(Long id, ReworkJobRequest request) {
        LabJob job = findJob(id);

        if (request.getReason() == null || request.getReason().trim().isEmpty()) {
            throw ApiException.of(ErrorCode.LAB_JOB_QC_REASON_REQUIRED, "Phải ghi rõ lý do mài lại");
        }

        // Subtask 7.3: Idempotency / Retry protection
        String idempotencyKey = request.getIdempotencyKey();
        if (idempotencyKey != null && !idempotencyKey.isBlank() && qcHistoryRepository != null) {
            Optional<LabQcHistory> existing = qcHistoryRepository.findByIdempotencyKey(idempotencyKey.trim());
            if (existing.isPresent()) {
                log.info("Rework request with idempotencyKey {} already executed, returning current job", idempotencyKey);
                return getResponse(job);
            }
        }

        // Tránh nhân đôi khi retry nếu job đã IN_PROGRESS với cùng lý do mài lại
        if (job.getStatus() == LabJobStatus.IN_PROGRESS && request.getReason().trim().equals(job.getTechnicianNotes())) {
            log.info("LabJob {} is already IN_PROGRESS for rework, skipping duplicate rework trigger", id);
            return getResponse(job);
        }

        if (job.getStatus() == LabJobStatus.COMPLETED || job.getStatus() == LabJobStatus.DELIVERED
                || job.getStatus() == LabJobStatus.CANCELLED) {
            throw ApiException.of(ErrorCode.LAB_JOB_INVALID_TRANSITION,
                    "Không thể yêu cầu mài lại cho lệnh đã " + job.getStatus());
        }

        // Subtask 7.3: Giữ liên kết lần mài lại với công việc gốc và toa đã xác nhận
        if (job.getPrescriptionId() == null) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Không tìm thấy toa kính gốc đã xác nhận cho lệnh mài lại");
        }

        LabJobStatus previousStatus = job.getStatus();
        LocalDateTime now = LocalDateTime.now();
        int currentReworkCount = (job.getReworkCount() != null ? job.getReworkCount() : 0) + 1;
        job.setReworkCount(currentReworkCount);
        job.setStatus(LabJobStatus.IN_PROGRESS);
        job.setStartedAt(now);
        job.setTechnicianNotes(request.getReason().trim());
        job.setQcResult("REWORK");
        job.setMaterialDamaged(false); // Mài lại phôi gốc, không làm hỏng vật tư
        job.setUpdatedAt(now);

        String actor = request.getTechnicianName() != null && !request.getTechnicianName().isBlank()
                ? request.getTechnicianName()
                : (job.getTechnicianName() != null ? job.getTechnicianName() : "LAB_TECH");
        if (request.getTechnicianId() != null) {
            job.setTechnicianId(request.getTechnicianId());
        }
        if (request.getTechnicianName() != null && !request.getTechnicianName().isBlank()) {
            job.setTechnicianName(request.getTechnicianName());
        }

        LabJob saved = labJobRepository.save(job);

        if (qcHistoryRepository != null) {
            LabQcHistory qcRecord = LabQcHistory.builder()
                    .idempotencyKey(idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey.trim() : null)
                    .labJobId(saved.getId())
                    .orderId(saved.getOrderId())
                    .prescriptionId(saved.getPrescriptionId())
                    .qcResult(QcResult.REWORK)
                    .failureReason(request.getReason().trim())
                    .materialDamaged(false)
                    .notes(request.getNotes())
                    .inspectorId(request.getTechnicianId() != null ? request.getTechnicianId() : saved.getTechnicianId())
                    .inspectorName(actor)
                    .reworkCount(currentReworkCount)
                    .inspectedAt(now)
                    .build();
            qcHistoryRepository.save(qcRecord);
        }

        recordStatus(saved, previousStatus.name(), LabJobStatus.IN_PROGRESS.name(), actor,
                "Lần mài lại thứ " + currentReworkCount + ": " + request.getReason().trim());

        publishLabReworkSafely(saved);

        return getResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabQcResponse> getQcHistory(Long id) {
        findJob(id);
        if (qcHistoryRepository == null) {
            return Collections.emptyList();
        }
        return qcHistoryRepository.findByLabJobIdOrderByInspectedAtAsc(id).stream()
                .map(LabQcResponse::from)
                .toList();
    }

    private void recordQc(
            LabJob job, QcResult result, String reason, Boolean materialDamaged,
            Long inspectorId, String inspectorName, Integer reworkCount, String idempotencyKey) {
        if (qcHistoryRepository != null) {
            qcHistoryRepository.save(LabQcHistory.builder()
                    .idempotencyKey(idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey.trim() : null)
                    .labJobId(job.getId())
                    .orderId(job.getOrderId())
                    .prescriptionId(job.getPrescriptionId())
                    .qcResult(result)
                    .failureReason(reason)
                    .materialDamaged(materialDamaged != null ? materialDamaged : false)
                    .inspectorId(inspectorId)
                    .inspectorName(inspectorName != null && !inspectorName.isBlank() ? inspectorName : "QC_STAFF")
                    .reworkCount(reworkCount != null ? reworkCount : 0)
                    .inspectedAt(LocalDateTime.now())
                    .build());
        }
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
                || prescription.getOdAdd() == null || prescription.getOsAdd() == null
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
        checkRange(prescription.getOdAdd(), 0, 4, "OD ADD");
        checkRange(prescription.getOsAdd(), 0, 4, "OS ADD");
    }

    private void validateAxis(Integer axis, String eye) {
        if (axis != null && (axis < 0 || axis > 180)) {
            throw ApiException.of(ErrorCode.PRESCRIPTION_INCOMPLETE,
                    "Trục loạn " + eye + " phải nằm trong khoảng 0-180 độ");
        }
    }

    private void publishLabReadySafely(LabJob saved) {
        if (labNotificationPublisher == null || saved == null) {
            return;
        }
        try {
            Long customerId = null;
            if (saved.getPrescriptionId() != null && prescriptionRepository != null) {
                customerId = prescriptionRepository.findById(saved.getPrescriptionId())
                        .map(Prescription::getCustomerId)
                        .orElse(null);
            }
            labNotificationPublisher.publishLabReady(saved, customerId);
        } catch (Exception ex) {
            log.warn("Non-fatal error publishing LAB_READY notification for job {}: {}", saved.getId(), ex.getMessage());
        }
    }

    private void publishLabReworkSafely(LabJob saved) {
        if (labNotificationPublisher == null || saved == null) {
            return;
        }
        try {
            labNotificationPublisher.publishLabRework(saved);
        } catch (Exception ex) {
            log.warn("Non-fatal error publishing LAB_REWORK notification for job {}: {}", saved.getId(), ex.getMessage());
        }
    }

    private void checkRange(BigDecimal value, double min, double max, String label) {
        if (value != null && (value.doubleValue() < min || value.doubleValue() > max)) {
            throw ApiException.of(ErrorCode.PRESCRIPTION_INCOMPLETE,
                    "Giá trị " + label + " nằm ngoài khoảng hợp lệ (" + min + " .. " + max + ")");
        }
    }
}
