package com.glassstore.lab.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;
import java.math.BigDecimal;

/** Persisted measurements at dispatch. No mutable relationship to the live prescription. */
@Embeddable @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LabPrescriptionSnapshot {
    @Column(name="rx_version") private Long version;
    @Column(name="rx_od_sph",precision=4,scale=2) private BigDecimal odSph;
    @Column(name="rx_od_cyl",precision=4,scale=2) private BigDecimal odCyl;
    @Column(name="rx_od_axis") private Integer odAxis;
    @Column(name="rx_od_add",precision=4,scale=2) private BigDecimal odAdd;
    @Column(name="rx_os_sph",precision=4,scale=2) private BigDecimal osSph;
    @Column(name="rx_os_cyl",precision=4,scale=2) private BigDecimal osCyl;
    @Column(name="rx_os_axis") private Integer osAxis;
    @Column(name="rx_os_add",precision=4,scale=2) private BigDecimal osAdd;
    @Column(name="rx_pd",precision=4,scale=1) private BigDecimal pd;
    @Column(name="rx_notes",length=500) private String notes;

    public static LabPrescriptionSnapshot from(Prescription rx) {
        return builder().version(rx.getVersion() == null ? 0L : rx.getVersion())
                .odSph(rx.getOdSph()).odCyl(rx.getOdCyl()).odAxis(rx.getOdAxis()).odAdd(rx.getOdAdd())
                .osSph(rx.getOsSph()).osCyl(rx.getOsCyl()).osAxis(rx.getOsAxis()).osAdd(rx.getOsAdd())
                .pd(rx.getPd()).notes(rx.getNotes()).build();
    }

    public Prescription asPrescription(Long id, String customerName) {
        return Prescription.builder().id(id).version(version).customerName(customerName)
                .odSph(odSph).odCyl(odCyl).odAxis(odAxis).odAdd(odAdd)
                .osSph(osSph).osCyl(osCyl).osAxis(osAxis).osAdd(osAdd).pd(pd).notes(notes).build();
    }
}
