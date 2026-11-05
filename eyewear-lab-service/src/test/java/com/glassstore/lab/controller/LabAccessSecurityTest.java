package com.glassstore.lab.controller;

import com.glassstore.lab.entity.*;
import com.glassstore.lab.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.math.BigDecimal;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:lab-access;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
@AutoConfigureMockMvc
class LabAccessSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired PrescriptionRepository prescriptions;
    @Autowired EyeExamAppointmentRepository appointments;
    @Autowired WarrantyRepository warranties;
    @Autowired LabJobRepository jobs;
    @Autowired LabJobHistoryRepository jobHistory;
    @MockBean com.glassstore.lab.config.LabDataInitializer demoSeed;
    Prescription mine,other;
    Warranty otherWarranty;
    EyeExamAppointment otherAppointment;
    RequestPostProcessor role(String role) { return jwt().jwt(j -> j.subject("10").claim("userId",10L).claim("username","qa-doctor"))
        .authorities(new SimpleGrantedAuthority("ROLE_"+role)); }
    @BeforeEach void data() {
        jobHistory.deleteAll();jobs.deleteAll();warranties.deleteAll();appointments.deleteAll();prescriptions.deleteAll();
        mine=prescriptions.saveAndFlush(Prescription.builder().customerId(10L).customerName("QA own").optometristId(1L)
            .odSph(BigDecimal.ZERO).odCyl(BigDecimal.ZERO).odAxis(0)
            .osSph(BigDecimal.ZERO).osCyl(BigDecimal.ZERO).osAxis(0).pd(new BigDecimal("63")).build());
        other=prescriptions.saveAndFlush(Prescription.builder().customerId(20L).customerName("QA other").optometristId(1L).odSph(BigDecimal.ZERO).osSph(BigDecimal.ZERO).pd(new BigDecimal("63")).build());
        otherAppointment=appointments.saveAndFlush(EyeExamAppointment.builder().ticketCode("QA-OTHER").customerId(20L).customerName("QA other").status("WAITING").build());
        otherWarranty=warranties.saveAndFlush(Warranty.builder().warrantyCode("QA-WAR").customerId(20L).customerName("QA other").productName("QA frame").status("ACTIVE").build());
    }
    @Test void unauthenticatedReadsAreRejected() throws Exception {
        for(String path:java.util.List.of("/api/prescriptions","/api/lab-jobs","/api/appointments","/api/warranties","/api/repair-services"))
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }
    @Test void customerCannotReadAnotherClinicalRecordOrWarranty() throws Exception {
        mvc.perform(get("/api/prescriptions/"+mine.getId()).with(role("CUSTOMER"))).andExpect(status().isOk());
        mvc.perform(get("/api/prescriptions/"+other.getId()).with(role("CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/prescriptions/customer/20").with(role("CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/appointments/"+otherAppointment.getId()).with(role("CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/warranties/"+otherWarranty.getId()).with(role("CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/warranties/code/QA-WAR").with(role("CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/warranties").with(role("CUSTOMER"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }
    @Test void clinicalAndLabMutationsAreSeparatedByRole() throws Exception {
        mvc.perform(post("/api/prescriptions").with(role("LAB_TECH")).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(patch("/api/lab-jobs/1/status").with(role("SALES_STAFF")).contentType("application/json").content("{\"status\":\"QC_PASSED\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/warranties").with(role("CUSTOMER")).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/lab-jobs/from-order").with(role("ADMIN")).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
    }
    @Test void doctorIdentityIsTakenFromToken() throws Exception {
        mvc.perform(post("/api/prescriptions").with(role("OPTOMETRIST")).contentType("application/json")
            .content("{\"customerId\":10,\"customerName\":\"QA\",\"optometristId\":999,\"odSph\":0,\"osSph\":0,\"pd\":63}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.optometristId").value(10));
    }
    @Test void bookingCannotUseAnotherCustomerIdOrOverwriteAnAppointment() throws Exception {
        mvc.perform(post("/api/appointments").with(role("CUSTOMER")).contentType("application/json")
            .content("{\"id\":"+otherAppointment.getId()+",\"customerId\":20,\"customerName\":\"QA\",\"status\":\"COMPLETED\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.customerId").value(10)).andExpect(jsonPath("$.data.status").value("WAITING"));
        org.junit.jupiter.api.Assertions.assertEquals(20L,appointments.findById(otherAppointment.getId()).orElseThrow().getCustomerId());
    }
    @Test void authenticatedServiceCanCreateAndLookUpTheExactOrdersJob() throws Exception {
        mvc.perform(post("/api/lab-jobs/from-order").with(role("SERVICE")).contentType("application/json")
            .content("{\"orderId\":501,\"prescriptionId\":"+mine.getId()+",\"frameSku\":\"FRM-QA\",\"lensSku\":\"LNS-QA\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.orderId").value(501));
        mvc.perform(get("/api/lab-jobs/by-order/501").with(role("SERVICE")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.orderId").value(501));
    }
}
