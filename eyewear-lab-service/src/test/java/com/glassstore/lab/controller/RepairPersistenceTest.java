package com.glassstore.lab.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glassstore.lab.repository.RepairServiceRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
@org.springframework.security.test.context.support.WithMockUser(username="1", roles="ADMIN")
class RepairPersistenceTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired EntityManager entities;
    @Autowired RepairServiceRepository repairs;
    @org.springframework.boot.test.mock.mockito.MockBean com.glassstore.lab.client.WarrantyOrderClient orderClient;
    private Map<String,Object> request() {
        org.mockito.Mockito.when(orderClient.getPurchaseItem(10L, 20L)).thenReturn(
                new com.glassstore.lab.dto.WarrantyOrderItemDTO(
                        10L, 20L, "ORD-001", 1L, "Khách QA", "0901234567",
                        "SKU-001", "Gọng QA", LocalDateTime.now().minusDays(2), "PAID", null, null, 0, null));
        var map = new HashMap<String, Object>(Map.of("customerName","Khách QA","customerPhone","0901234567","productName","Gọng QA",
            "frameCondition","Lỏng ốc","lensCondition","Tròng nguyên vẹn","reportedProblem","Kính lỏng",
            "physicalCondition","Không trầy khác","requestedServices",List.of("FRAME_ADJUSTMENT","SCREW_TIGHTENING"),
            "receptionEmployee","QA Staff","expectedPickupAt",LocalDateTime.now().plusHours(2).toString()));
        map.put("orderId", 10L);
        map.put("orderItemId", 20L);
        map.put("customerId", 1L);
        return map;
    }
    @Test void receptionPersistsAndStatusHistorySurvivesReload() throws Exception {
        String body=mvc.perform(post("/api/repair-services").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request())))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("RECEIVED"))
            .andReturn().getResponse().getContentAsString();
        long id=json.readTree(body).path("id").asLong(); String code=json.readTree(body).path("serviceCode").asText();
        repairs.flush(); entities.clear(); assertFalse(code.isBlank()); assertNotNull(repairs.findById(id).orElseThrow().getExpectedPickupAt());
        mvc.perform(get("/api/repair-services").param("phone","0901234567"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].serviceCode").value(code));
        for(String status:List.of("IN_PROGRESS","READY_FOR_PICKUP")) {
            mvc.perform(patch("/api/repair-services/"+id+"/status").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("status",status,"changedBy","QA Technician"))))
                .andExpect(status().isOk());
        }
        repairs.flush(); entities.clear();
        mvc.perform(get("/api/repair-services/"+id)).andExpect(jsonPath("$.status").value("READY_FOR_PICKUP"))
            .andExpect(jsonPath("$.statusHistory.length()").value(3));
    }
    @Test void refusesAPastPickupTimeWithoutSaving() throws Exception {
        long before=repairs.count(); var payload=request(); payload.put("expectedPickupAt",LocalDateTime.now().minusHours(1).toString());
        mvc.perform(post("/api/repair-services").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(payload)))
            .andExpect(status().isBadRequest());
        assertEquals(before,repairs.count());
    }
}
