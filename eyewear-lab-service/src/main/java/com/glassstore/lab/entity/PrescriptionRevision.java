package com.glassstore.lab.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** Append-only clinical snapshot. No setters or update endpoint are provided. */
@Entity
@Table(name = "prescription_revisions", uniqueConstraints =
        @UniqueConstraint(name = "uk_prescription_revision", columnNames = {"prescription_id", "version"}))
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PrescriptionRevision {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonIgnore
    private Long revisionId;
    @Column(name = "prescription_id", nullable = false, updatable = false)
    private Long prescriptionId;
    @Column(nullable = false, updatable = false)
    private Long version;
    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;
    private String customerName;
    private Long optometristId;
    private String optometristName;
    @Column(precision = 4, scale = 2)
    private BigDecimal odSph;
    @Column(precision = 4, scale = 2)
    private BigDecimal odCyl;
    private Integer odAxis;
    @Column(precision = 4, scale = 2)
    private BigDecimal odAdd;
    @Column(precision = 4, scale = 2)
    private BigDecimal osSph;
    @Column(precision = 4, scale = 2)
    private BigDecimal osCyl;
    private Integer osAxis;
    @Column(precision = 4, scale = 2)
    private BigDecimal osAdd;
    @Column(precision = 4, scale = 1)
    private BigDecimal pd;
    @Column(length = 500)
    private String notes;
    private String lensSku;
    private String lensName;
    private LocalDateTime createdAt;
    private Long actorId;
    private String actorName;
    @Column(nullable = false, length = 500)
    private String reason;
    @Column(nullable = false)
    private LocalDateTime recordedAt;

    /** Prescription identity, so existing snapshot consumers can keep using data.id. */
    public Long getId() {
        return prescriptionId;
    }

    public static PrescriptionRevision capture(Prescription p, Long actorId, String actorName,
                                                String reason, LocalDateTime recordedAt) {
        PrescriptionRevision revision = new PrescriptionRevision();
        revision.prescriptionId = p.getId();
        revision.version = p.getVersion();
        revision.customerId = p.getCustomerId();
        revision.customerName = p.getCustomerName();
        revision.optometristId = p.getOptometristId();
        revision.optometristName = p.getOptometristName();
        revision.odSph = p.getOdSph();
        revision.odCyl = p.getOdCyl();
        revision.odAxis = p.getOdAxis();
        revision.odAdd = p.getOdAdd();
        revision.osSph = p.getOsSph();
        revision.osCyl = p.getOsCyl();
        revision.osAxis = p.getOsAxis();
        revision.osAdd = p.getOsAdd();
        revision.pd = p.getPd();
        revision.notes = p.getNotes();
        revision.lensSku = p.getLensSku();
        revision.lensName = p.getLensName();
        revision.createdAt = p.getCreatedAt();
        revision.actorId = actorId;
        revision.actorName = actorName;
        revision.reason = reason;
        revision.recordedAt = recordedAt;
        return revision;
    }

    /** Detached copy only; callers must never save this as the current prescription. */
    public Prescription toPrescription() {
        return Prescription.builder().id(prescriptionId).version(version).customerId(customerId)
                .customerName(customerName).optometristId(optometristId).optometristName(optometristName)
                .odSph(odSph).odCyl(odCyl).odAxis(odAxis).odAdd(odAdd)
                .osSph(osSph).osCyl(osCyl).osAxis(osAxis).osAdd(osAdd).pd(pd)
                .notes(notes).lensSku(lensSku).lensName(lensName).createdAt(createdAt).build();
    }
}
