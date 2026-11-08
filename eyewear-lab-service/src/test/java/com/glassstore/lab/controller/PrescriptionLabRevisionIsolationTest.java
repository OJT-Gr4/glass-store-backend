package com.glassstore.lab.controller;

import com.glassstore.lab.entity.LabJob;
import com.glassstore.lab.repository.LabJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PrescriptionLabRevisionIsolationTest extends PrescriptionRevisionTestSupport {
    @Autowired LabJobRepository jobs;

    @Test
    void editingLegacyPrescriptionFreezesExistingJobsAndPersistsOriginalRevision() throws Exception {
        long id = create();
        // Simulate an installation predating revision snapshots.
        revisions.deleteAll(revisions.findByPrescriptionIdOrderByVersionDesc(id));
        LabJob legacyJob = jobs.saveAndFlush(LabJob.builder().orderId(customerId).prescriptionId(id)
                .customerName("Legacy customer").frameSku("FRAME-TEST").lensSku("LENS-TEST").build());
        mvc.perform(patch("/api/prescriptions/" + id).with(actor(202, "OPTOMETRIST"))
                        .contentType("application/json").content(update(0, -3.75).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        var stored = jobs.findById(legacyJob.getId()).orElseThrow().getPrescriptionSnapshot();
        assertThat(stored).isNotNull();
        assertThat(stored.getVersion()).isZero();
        assertThat(stored.getOdSph()).isEqualByComparingTo("-2.00");
        assertThat(revisions.findByPrescriptionIdAndVersion(id, 0L).orElseThrow().getOdSph())
                .isEqualByComparingTo("-2.00");
        mvc.perform(get("/api/lab-jobs/" + legacyJob.getId()).with(actor(404, "LAB_TECH")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.prescriptionVersion").value(0))
                .andExpect(jsonPath("$.data.odSph").value(-2.0));
        assertThat(prescriptions.findById(id).orElseThrow().getOdSph()).isEqualByComparingTo("-3.75");
    }

    @Test
    void dispatchCanPinEarlierRevisionAndLaterClinicalChangesLeaveTheJobUnchanged() throws Exception {
        long id = create();
        mvc.perform(patch("/api/prescriptions/" + id).with(actor(202, "OPTOMETRIST"))
                        .contentType("application/json").content(update(0, -3.25).toString()))
                .andExpect(status().isOk());
        var jobRequest = mapper.createObjectNode().put("orderId", customerId).put("prescriptionId", id)
                .put("prescriptionVersion", 0).put("customerId", customerId)
                .put("frameSku", "FRAME-TEST").put("lensSku", "LENS-TEST");
        String body = mvc.perform(post("/api/lab-jobs/from-order").with(actor(999, "SERVICE"))
                        .contentType("application/json").content(jobRequest.toString()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.prescriptionVersion").value(0))
                .andExpect(jsonPath("$.data.odSph").value(-2.0)).andReturn().getResponse().getContentAsString();
        long jobId = mapper.readTree(body).path("data").path("id").asLong();
        mvc.perform(patch("/api/prescriptions/" + id).with(actor(303, "OPTOMETRIST"))
                        .contentType("application/json").content(update(1, -4.00).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2));
        mvc.perform(get("/api/lab-jobs/" + jobId).with(actor(404, "LAB_TECH")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.prescriptionVersion").value(0))
                .andExpect(jsonPath("$.data.odSph").value(-2.0));
        assertThat(jobs.findById(jobId).orElseThrow().getPrescriptionSnapshot().getOdSph())
                .isEqualByComparingTo("-2.00");
        assertThat(revisions.findByPrescriptionIdOrderByVersionDesc(id)).hasSize(3);
    }
}
