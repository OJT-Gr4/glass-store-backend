package com.glassstore.order.service;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ClinicalPrescriptionSnapshotTest {
    private Map<String,Object> rx() {
        Map<String,Object> rx = new LinkedHashMap<>();
        rx.put("id", 7L); rx.put("version", 0L); rx.put("customerId", 10L);
        for (String field : new String[]{"odSph","odCyl","odAxis","odAdd","osSph","osCyl","osAxis","osAdd"}) rx.put(field, 0);
        rx.put("pd", 62.5); return rx;
    }

    @Test void pinnedMeasurementsAndVersionSurviveLaterSourceChanges() {
        var live = rx();
        var snapshot = ClinicalPrescriptionSnapshot.from(live, 7L, 10L);
        live.put("odSph", -3); live.put("version", 1L);
        var sent = ClinicalPrescriptionSnapshot.toMap(snapshot, 7L, 10L);
        assertEquals(0, snapshot.getOdSph().signum());
        assertEquals(0L, sent.get("version"));
        assertEquals("CLINICAL_REVISION", snapshot.getSource());
    }

    @Test void missingMeasurementAndWrongCustomerAreRejectedRatherThanFabricated() {
        var incomplete = rx(); incomplete.remove("odAdd");
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> ClinicalPrescriptionSnapshot.from(incomplete, 7L, 10L));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> ClinicalPrescriptionSnapshot.from(rx(), 7L, 11L));
        var fractionalAxis = rx(); fractionalAxis.put("odAxis", 5.5);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> ClinicalPrescriptionSnapshot.from(fractionalAxis, 7L, 10L));
    }
}
