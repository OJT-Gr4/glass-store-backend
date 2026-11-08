package com.glassstore.lab.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.glassstore.lab.client.PrescriptionCustomerClient;
import com.glassstore.lab.repository.PrescriptionRepository;
import com.glassstore.lab.repository.PrescriptionRevisionRepository;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:prescription-revision-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "glassstore.demo.seed.enabled=false"})
@AutoConfigureMockMvc
abstract class PrescriptionRevisionTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired PrescriptionRepository prescriptions;
    @Autowired PrescriptionRevisionRepository revisions;
    @MockBean PrescriptionCustomerClient customers;
    static final AtomicLong CUSTOMER_SEQUENCE = new AtomicLong(800000);
    long customerId;

    @BeforeEach
    void customer() {
        customerId = CUSTOMER_SEQUENCE.incrementAndGet();
        when(customers.requireCustomer(anyLong())).thenReturn("Verified Customer");
    }

    RequestPostProcessor actor(long id, String role) {
        return jwt().jwt(j -> j.subject(Long.toString(id)).claim("userId", id)
                        .claim("username", "actor-" + id))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    ObjectNode request() {
        ObjectNode data = mapper.createObjectNode();
        data.put("customerId", customerId);
        data.put("odSph", -2.00); data.put("odCyl", 0); data.put("odAxis", 0); data.put("odAdd", 0);
        data.put("osSph", -1.50); data.put("osCyl", 0); data.put("osAxis", 0); data.put("osAdd", 0);
        data.put("pd", 63.0); data.put("notes", "Original note");
        return data;
    }

    long create() throws Exception {
        String body = mvc.perform(post("/api/prescriptions").with(actor(101, "OPTOMETRIST"))
                        .contentType("application/json").content(request().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data").path("id").asLong();
    }

    ObjectNode update(long expectedVersion, double odSph) {
        return request().put("expectedVersion", expectedVersion).put("reason", "Reviewed after repeat measurement")
                .put("odSph", odSph);
    }
}
