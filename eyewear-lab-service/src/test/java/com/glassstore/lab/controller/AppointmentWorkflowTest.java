package com.glassstore.lab.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glassstore.lab.client.PrescriptionCustomerClient;
import com.glassstore.lab.entity.Prescription;
import com.glassstore.lab.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:appointment-workflow;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
@AutoConfigureMockMvc
@Transactional
class AppointmentWorkflowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EyeExamAppointmentRepository appointments;
    @Autowired PrescriptionRepository prescriptions;
    @MockBean PrescriptionCustomerClient customers;

    RequestPostProcessor actor(long id, String role) {
        return jwt().jwt(j -> j.subject(Long.toString(id)).claim("userId", id).claim("username", "actor-" + id))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    long book() throws Exception {
        when(customers.requireCustomer(10L)).thenReturn("Verified Customer");
        String result = mvc.perform(post("/api/appointments").with(actor(10, "CUSTOMER"))
                        .contentType("application/json").content("""
                        {"customerId":999,"customerName":"Spoof","optometristId":999,"status":"COMPLETED",
                         "roomName":"Spoof room","scheduledTime":"2099-11-09T09:00","notes":"First visit"}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.customerId").value(10))
                .andExpect(jsonPath("$.data.customerName").value("Verified Customer"))
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(jsonPath("$.data.optometristId").isEmpty()).andExpect(jsonPath("$.data.roomName").isEmpty())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(result).path("data").path("id").asLong();
    }

    @Test void bookingStartAndCompletionArePersistedAndBoundToExaminerAndPrescription() throws Exception {
        long id = book();
        mvc.perform(patch("/api/appointments/" + id + "/status").with(actor(21, "OPTOMETRIST"))
                        .contentType("application/json").content("{\"status\":\"IN_EXAM\",\"expectedVersion\":0,\"roomName\":\"Room 1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.optometristId").value(21))
                .andExpect(jsonPath("$.data.version").value(1));
        Prescription prescription = prescriptions.saveAndFlush(Prescription.builder().customerId(10L).optometristId(21L)
                .odSph(BigDecimal.ZERO).osSph(BigDecimal.ZERO).pd(new BigDecimal("62.5")).build());
        mvc.perform(patch("/api/appointments/" + id + "/status").with(actor(21, "OPTOMETRIST"))
                        .contentType("application/json").content("{\"status\":\"COMPLETED\",\"expectedVersion\":1,\"prescriptionId\":" + prescription.getId() + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED"));
        mvc.perform(get("/api/appointments/" + id).with(actor(10, "CUSTOMER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.prescriptionId").value(prescription.getId().intValue()));
        assertThat(appointments.findById(id).orElseThrow().getOptometristId()).isEqualTo(21);
    }

    @Test void rejectsStaleClaimsOtherExaminerAndUnlinkedCompletion() throws Exception {
        long id = book();
        mvc.perform(patch("/api/appointments/" + id + "/status").with(actor(21, "OPTOMETRIST"))
                .contentType("application/json").content("{\"status\":\"IN_EXAM\",\"expectedVersion\":0,\"roomName\":\"Room 1\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/appointments/" + id + "/status").with(actor(22, "OPTOMETRIST"))
                .contentType("application/json").content("{\"status\":\"IN_EXAM\",\"expectedVersion\":0,\"roomName\":\"Room 2\"}"))
                .andExpect(status().isConflict());
        mvc.perform(patch("/api/appointments/" + id + "/status").with(actor(22, "OPTOMETRIST"))
                .contentType("application/json").content("{\"status\":\"COMPLETED\",\"expectedVersion\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/appointments/" + id + "/status").with(actor(21, "OPTOMETRIST"))
                .contentType("application/json").content("{\"status\":\"COMPLETED\",\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest());
        assertThat(appointments.findById(id).orElseThrow().getStatus()).isEqualTo("IN_EXAM");
    }

    @Test void unauthenticatedAndSalesClinicalMutationsAreDeniedAndCustomerDataIsIsolated() throws Exception {
        mvc.perform(post("/api/appointments").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        long id = book();
        mvc.perform(get("/api/appointments/" + id).with(actor(11, "CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/appointments").with(actor(11, "CUSTOMER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(patch("/api/appointments/" + id + "/status").with(actor(30, "SALES_STAFF"))
                .contentType("application/json").content("{\"status\":\"IN_EXAM\",\"expectedVersion\":0,\"roomName\":\"Room 1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test void invalidTimeMissingFieldsAndNonexistentCustomerCannotSave() throws Exception {
        long count = appointments.count();
        for (String body : new String[]{"{}", "{\"scheduledTime\":\"bad\"}", "{\"scheduledTime\":\"2020-01-01T09:00\"}"})
            mvc.perform(post("/api/appointments").with(actor(10, "CUSTOMER")).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        when(customers.requireCustomer(99L)).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer does not exist"));
        mvc.perform(post("/api/appointments").with(actor(21, "OPTOMETRIST")).contentType("application/json")
                .content("{\"customerId\":99,\"scheduledTime\":\"2099-11-09T09:00\"}"))
                .andExpect(status().isBadRequest());
        assertThat(appointments.count()).isEqualTo(count);
    }
}
