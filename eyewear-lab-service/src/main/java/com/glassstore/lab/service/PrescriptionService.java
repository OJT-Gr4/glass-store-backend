package com.glassstore.lab.service;

import com.glassstore.common.security.CurrentActor;
import com.glassstore.lab.client.PrescriptionCustomerClient;
import com.glassstore.lab.dto.CreatePrescriptionRequest;
import com.glassstore.lab.dto.UpdatePrescriptionRequest;
import com.glassstore.lab.entity.Prescription;
import com.glassstore.lab.entity.PrescriptionRevision;
import com.glassstore.lab.repository.PrescriptionRepository;
import com.glassstore.lab.repository.PrescriptionRevisionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PrescriptionService {
    private final PrescriptionRepository prescriptions;
    private final PrescriptionRevisionRepository revisions;
    private final PrescriptionCustomerClient customers;
    private final LabPrescriptionSnapshotService labSnapshots;

    public Prescription get(Long id) {
        Prescription prescription = prescriptions.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Prescription not found"));
        CurrentActor.requireOwner(prescription.getCustomerId());
        return prescription;
    }

    public List<PrescriptionRevision> history(Long id) {
        Prescription current = get(id);
        List<PrescriptionRevision> history = revisions.findByPrescriptionIdOrderByVersionDesc(id);
        return history.isEmpty() ? List.of(legacyRevision(current)) : history;
    }

    public PrescriptionRevision revision(Long id, Long version) {
        Prescription current = get(id);
        if (version == null || version < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Prescription version is required");
        }
        return revisions.findByPrescriptionIdAndVersion(id, version).orElseGet(() -> {
            // Existing records have no historical row until their first controlled edit.
            if (Objects.equals(current.getVersion(), version)) return legacyRevision(current);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Prescription revision not found");
        });
    }

    public Prescription readRevisionAsPrescription(Long id, Long version) {
        return revision(id, version).toPrescription();
    }

    @Transactional
    public Prescription create(CreatePrescriptionRequest request) {
        requireWriter();
        String customerName = customers.requireCustomer(request.getCustomerId());
        Prescription prescription = new Prescription();
        prescription.setCustomerId(request.getCustomerId());
        prescription.setCustomerName(customerName);
        prescription.setOptometristId(CurrentActor.id());
        prescription.setOptometristName(CurrentActor.name());
        applyMeasurements(prescription, request);
        Prescription saved = prescriptions.saveAndFlush(prescription);
        revisions.saveAndFlush(PrescriptionRevision.capture(saved, CurrentActor.id(), CurrentActor.name(),
                "Initial prescription", LocalDateTime.now()));
        return saved;
    }

    @Transactional
    public Prescription update(Long id, UpdatePrescriptionRequest request) {
        requireWriter();
        Prescription prescription = get(id);
        if (!Objects.equals(prescription.getVersion(), request.getExpectedVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Prescription was changed by another user; reload before editing");
        }
        if (!Objects.equals(prescription.getCustomerId(), request.getCustomerId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Prescription customer cannot be changed");
        }
        customers.requireCustomer(prescription.getCustomerId());
        PrescriptionRevision before = revisions.existsByPrescriptionIdAndVersion(id, prescription.getVersion())
                ? null : legacyRevision(prescription);
        // Legacy jobs that still depend on the current row must retain the measurements they started with.
        labSnapshots.freezeLegacyJobs(prescription);
        applyMeasurements(prescription, request);
        prescription.setUpdatedAt(LocalDateTime.now());
        // Flush the @Version update before appending snapshots: losing writers roll back the entire transaction.
        Prescription saved = prescriptions.saveAndFlush(prescription);
        if (before != null) revisions.save(before);
        revisions.saveAndFlush(PrescriptionRevision.capture(saved, CurrentActor.id(), CurrentActor.name(),
                request.getReason().trim(), LocalDateTime.now()));
        return saved;
    }

    private static void requireWriter() {
        if (!CurrentActor.hasRole("ADMIN") && !CurrentActor.hasRole("OPTOMETRIST")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an optometrist may change a prescription");
        }
        if (CurrentActor.id() <= 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User identity is missing");
        }
    }

    private static PrescriptionRevision legacyRevision(Prescription prescription) {
        return PrescriptionRevision.capture(prescription, prescription.getOptometristId(),
                prescription.getOptometristName(), "Legacy prescription before first controlled update",
                prescription.getCreatedAt() == null ? LocalDateTime.now() : prescription.getCreatedAt());
    }

    private static void applyMeasurements(Prescription prescription, CreatePrescriptionRequest request) {
        prescription.setOdSph(request.getOdSph());
        prescription.setOdCyl(request.getOdCyl());
        prescription.setOdAxis(request.getOdAxis());
        prescription.setOdAdd(request.getOdAdd());
        prescription.setOsSph(request.getOsSph());
        prescription.setOsCyl(request.getOsCyl());
        prescription.setOsAxis(request.getOsAxis());
        prescription.setOsAdd(request.getOsAdd());
        prescription.setPd(request.getPd());
        prescription.setNotes(request.getNotes());
        prescription.setLensSku(request.getLensSku());
        prescription.setLensName(request.getLensName());
    }
}
