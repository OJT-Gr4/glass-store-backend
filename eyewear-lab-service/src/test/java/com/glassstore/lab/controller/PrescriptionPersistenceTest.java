package com.glassstore.lab.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glassstore.lab.entity.Prescription;
import com.glassstore.lab.repository.PrescriptionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@org.springframework.security.test.context.support.WithMockUser(username="1", roles="ADMIN")
class PrescriptionPersistenceTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private EntityManager entityManager;
    @Autowired private PrescriptionRepository repository;

    @Test
    void savesLensSelectionAndReadsItBackFromCustomerHistory() throws Exception {
        String body = mvc.perform(post("/api/prescriptions").contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"customerId":90001,"optometristId":1,"odSph":-6.25,"osSph":-5.50,
                     "lensSku":"TEST-U6-174","lensName":"Crystal U6 1.74"}
                    """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(body).path("data").path("id").asLong();
        entityManager.flush();
        entityManager.clear();
        assertThat(repository.findById(id).orElseThrow().getLensSku()).isEqualTo("TEST-U6-174");
        mvc.perform(get("/api/prescriptions/customer/90001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].lensSku").value("TEST-U6-174"))
                .andExpect(jsonPath("$.data[0].lensName").value("Crystal U6 1.74"));
        mvc.perform(get("/api/prescriptions/customer/90002"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void historyIsNewestFirstAndIsolatedByCustomerIncludingTimestampTies() throws Exception {
        LocalDateTime date = LocalDateTime.of(2026, 5, 1, 10, 0);
        Prescription first = repository.saveAndFlush(exam(90001L, date));
        Prescription second = repository.saveAndFlush(exam(90001L, date));
        Prescription latest = repository.saveAndFlush(exam(90001L, date.plusMonths(1)));
        repository.saveAndFlush(exam(90002L, date.plusMonths(2)));
        entityManager.clear();
        mvc.perform(get("/api/prescriptions/customer/90001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].id").value(latest.getId().intValue()))
                .andExpect(jsonPath("$.data[1].id").value(second.getId().intValue()))
                .andExpect(jsonPath("$.data[2].id").value(first.getId().intValue()));
    }

    private Prescription exam(Long customerId, LocalDateTime date) {
        return Prescription.builder().customerId(customerId).optometristId(1L)
                .odSph(new BigDecimal("-2.00")).osSph(new BigDecimal("-1.50"))
                .pd(new BigDecimal("63.0")).createdAt(date).build();
    }
}
