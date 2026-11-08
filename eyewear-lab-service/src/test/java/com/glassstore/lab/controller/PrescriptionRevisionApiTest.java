package com.glassstore.lab.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PrescriptionRevisionApiTest extends PrescriptionRevisionTestSupport {
    @Test
    void editPersistsBeforeAfterAndTrustedEditorAndRejectsStaleWriter() throws Exception {
        long id = create();
        ObjectNode changed = update(0, -3.25).put("actorId", 999).put("actorName", "Forged")
                .put("optometristId", 999).put("optometristName", "Forged");
        mvc.perform(patch("/api/prescriptions/" + id).with(actor(202, "OPTOMETRIST"))
                        .contentType("application/json").content(changed.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.odSph").value(-3.25))
                .andExpect(jsonPath("$.data.optometristId").value(101));
        mvc.perform(get("/api/prescriptions/" + id + "/revisions").with(actor(customerId, "CUSTOMER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].version").value(1))
                .andExpect(jsonPath("$.data[0].actorId").value(202))
                .andExpect(jsonPath("$.data[0].actorName").value("actor-202"))
                .andExpect(jsonPath("$.data[0].reason").value("Reviewed after repeat measurement"))
                .andExpect(jsonPath("$.data[1].version").value(0))
                .andExpect(jsonPath("$.data[1].odSph").value(-2.0))
                .andExpect(jsonPath("$.data[1].actorId").value(101));
        mvc.perform(get("/api/prescriptions/" + id + "/revisions/0").with(actor(customerId, "CUSTOMER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.odSph").value(-2.0));
        mvc.perform(put("/api/v1/prescriptions/" + id).with(actor(303, "ADMIN"))
                        .contentType("application/json").content(update(0, -4).toString()))
                .andExpect(status().isConflict());
        assertThat(prescriptions.findById(id).orElseThrow().getOdSph()).isEqualByComparingTo("-3.25");
        assertThat(revisions.findByPrescriptionIdOrderByVersionDesc(id)).hasSize(2);
    }

    @Test
    void explicitZeroIsSavedAndEveryMissingOrNullMeasurementIsRejected() throws Exception {
        ObjectNode zero = request();
        zero.put("odSph", 0); zero.put("osSph", 0);
        mvc.perform(post("/api/prescriptions").with(actor(101, "OPTOMETRIST"))
                        .contentType("application/json").content(zero.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.odSph").value(0))
                .andExpect(jsonPath("$.data.odAxis").value(0));
        long count = prescriptions.count();
        for (String field : new String[]{"odSph", "odCyl", "odAxis", "odAdd", "osSph", "osCyl", "osAxis", "osAdd", "pd"}) {
            ObjectNode missing = request(); missing.remove(field);
            mvc.perform(post("/api/prescriptions").with(actor(101, "OPTOMETRIST"))
                            .contentType("application/json").content(missing.toString())).andExpect(status().isBadRequest());
            ObjectNode nullValue = request(); nullValue.putNull(field);
            mvc.perform(post("/api/prescriptions").with(actor(101, "OPTOMETRIST"))
                            .contentType("application/json").content(nullValue.toString())).andExpect(status().isBadRequest());
        }
        assertThat(prescriptions.count()).isEqualTo(count);
    }

    @Test
    void rejectsDomainPrecisionAndFractionalAxisWithoutSavingOrTruncating() throws Exception {
        long count = prescriptions.count();
        for (String[] invalid : new String[][]{{"odSph","30.01"},{"osSph","-30.01"},{"odCyl","10.01"},
                {"osCyl","-10.01"},{"odAxis","181"},{"osAxis","-1"},{"pd","39.9"},{"pd","90.1"},
                {"odAdd","4.01"},{"osAdd","-0.01"},{"odSph","1.001"},{"pd","63.01"},{"odAxis","12.5"},
                {"customerId","800000.5"}}) {
            ObjectNode data = request();
            data.set(invalid[0], mapper.readTree(invalid[1]));
            mvc.perform(post("/api/prescriptions").with(actor(101, "OPTOMETRIST"))
                            .contentType("application/json").content(data.toString())).andExpect(status().isBadRequest());
        }
        assertThat(prescriptions.count()).isEqualTo(count);
        verifyNoInteractions(customers);
    }

    @Test
    void invalidUpdateRequiresReasonVersionAllMeasurementsAndUnchangedCustomer() throws Exception {
        long id = create();
        ObjectNode blankReason = update(0, -3).put("reason", " ");
        ObjectNode missingVersion = update(0, -3); missingVersion.remove("expectedVersion");
        ObjectNode missingPd = update(0, -3); missingPd.remove("pd");
        for (ObjectNode data : new ObjectNode[]{blankReason, missingVersion, missingPd,
                update(0, -3).put("customerId", customerId + 1), update(0, -3).put("expectedVersion", 0.5)}) {
            mvc.perform(patch("/api/prescriptions/" + id).with(actor(202, "OPTOMETRIST"))
                            .contentType("application/json").content(data.toString())).andExpect(status().isBadRequest());
        }
        assertThat(prescriptions.findById(id).orElseThrow().getVersion()).isZero();
        assertThat(revisions.findByPrescriptionIdOrderByVersionDesc(id)).hasSize(1);
    }

    @Test
    void unauthorizedActorsAndOtherCustomersCannotWriteOrReadRevisionHistory() throws Exception {
        long id = create();
        mvc.perform(patch("/api/prescriptions/" + id).contentType("application/json").content(update(0, -3).toString()))
                .andExpect(status().isUnauthorized());
        for (String role : new String[]{"SALES_STAFF", "LAB_TECH", "CUSTOMER", "SERVICE"}) {
            mvc.perform(patch("/api/prescriptions/" + id).with(actor(999, role))
                            .contentType("application/json").content(update(0, -3).toString()))
                    .andExpect(status().isForbidden());
        }
        for (String suffix : new String[]{"/revisions", "/revisions/0"}) {
            mvc.perform(get("/api/prescriptions/" + id + suffix).with(actor(customerId + 1, "CUSTOMER")))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/prescriptions/9223372036854775807").with(actor(101, "OPTOMETRIST")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/prescriptions/" + id + "/revisions/99").with(actor(101, "OPTOMETRIST")))
                .andExpect(status().isNotFound());
        assertThat(revisions.findByPrescriptionIdOrderByVersionDesc(id)).hasSize(1);
    }

    @Test
    void absentCustomerAndUnavailableIdentityCannotCreateOrChangeData() throws Exception {
        long id = create();
        long count = prescriptions.count();
        when(customers.requireCustomer(customerId)).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer does not exist"));
        mvc.perform(post("/api/prescriptions").with(actor(101, "OPTOMETRIST"))
                        .contentType("application/json").content(request().toString())).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/prescriptions/" + id).with(actor(101, "OPTOMETRIST"))
                        .contentType("application/json").content(update(0, -3).toString())).andExpect(status().isBadRequest());
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cannot verify customer"))
                .when(customers).requireCustomer(customerId);
        mvc.perform(patch("/api/prescriptions/" + id).with(actor(101, "OPTOMETRIST"))
                        .contentType("application/json").content(update(0, -3).toString())).andExpect(status().isServiceUnavailable());
        assertThat(prescriptions.count()).isEqualTo(count);
        assertThat(prescriptions.findById(id).orElseThrow().getVersion()).isZero();
        assertThat(revisions.findByPrescriptionIdOrderByVersionDesc(id)).hasSize(1);
    }
}
