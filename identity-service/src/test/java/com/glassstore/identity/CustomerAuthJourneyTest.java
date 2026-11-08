package com.glassstore.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glassstore.identity.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:auth-journey;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
@AutoConfigureMockMvc
@Transactional
class CustomerAuthJourneyTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;

    @Test void registrationLoginMeAndRefreshUseThePersistedCustomerIdentity() throws Exception {
        String email = "journey-" + UUID.randomUUID() + "@example.test";
        String registration = "{\"fullName\":\"Journey Customer\",\"email\":\"" + email + "\",\"password\":\"Journey123!\",\"roles\":[\"ROLE_ADMIN\"]}";
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(registration))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.roles[0]").value("ROLE_CUSTOMER"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        var user = users.findByEmail(email).orElseThrow();
        assertThat(user.getPasswordHash()).startsWith("$2").doesNotContain("Journey123!");
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(registration))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        String login = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"email\":\"" + email.toUpperCase(java.util.Locale.ROOT) + "\",\"password\":\"Journey123!\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var session = mapper.readTree(login).path("data");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + session.path("token").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(user.getId().intValue()));
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + session.path("refreshToken").asText()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").contentType("application/json")
                .content("{\"refreshToken\":\"" + session.path("refreshToken").asText() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(user.getId().intValue()));
    }

    @Test void rejectsOversizeAndMalformedRegistrationBeforePersistence() throws Exception {
        long count = users.count();
        for (String body : new String[]{"{", "{}", "{\"fullName\":\"User\",\"email\":\"size@example.test\",\"password\":\"" + "ắ".repeat(30) + "\"}"})
            mvc.perform(post("/api/auth/register").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        assertThat(users.count()).isEqualTo(count);
    }
}
