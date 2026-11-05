package com.glassstore.order.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PosDataServiceTest {
    private MockRestServiceServer server;
    private PosDataService data;

    @BeforeEach void setup() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        data = new PosDataService(builder);
        ReflectionTestUtils.setField(data,"labUrl","http://lab");
        ReflectionTestUtils.setField(data,"catalogUrl","http://catalog");
    }

    @Test void acceptsOnlyThePrescriptionOwnedByTheSelectedCustomer() {
        server.expect(requestTo("http://lab/api/prescriptions/7")).andExpect(header("Authorization","Bearer staff"))
                .andRespond(withSuccess("{\"data\":{\"id\":7,\"customerId\":10}}", MediaType.APPLICATION_JSON));
        assertDoesNotThrow(() -> data.requirePrescription(7L,10L,"Bearer staff"));
        server.verify();
    }

    @Test void rejectsAnotherCustomersPrescription() {
        server.expect(requestTo("http://lab/api/prescriptions/7"))
                .andRespond(withSuccess("{\"data\":{\"id\":7,\"customerId\":99}}", MediaType.APPLICATION_JSON));
        var error = assertThrows(ResponseStatusException.class, () -> data.requirePrescription(7L,10L,"Bearer staff"));
        assertEquals(400,error.getStatusCode().value());
        assertTrue(error.getReason().contains("không thuộc"));
        server.verify();
    }

    @Test void rejectsMissingPrescriptionAndUnavailableLab() {
        server.expect(requestTo("http://lab/api/prescriptions/7")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://lab/api/prescriptions/8")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertEquals(400,assertThrows(ResponseStatusException.class, () -> data.requirePrescription(7L,10L,"Bearer staff")).getStatusCode().value());
        assertEquals(503,assertThrows(ResponseStatusException.class, () -> data.requirePrescription(8L,10L,"Bearer staff")).getStatusCode().value());
        server.verify();
    }

    @Test void rejectsGuestAndInvalidIdentityBeforeCallingLab() {
        assertThrows(ResponseStatusException.class, () -> data.requirePrescription(7L,null,"Bearer staff"));
        assertThrows(ResponseStatusException.class, () -> data.requirePrescription(-1L,10L,"Bearer staff"));
        server.verify();
    }

    @Test void rejectsMalformedPrescriptionResponse() {
        server.expect(requestTo("http://lab/api/prescriptions/7"))
                .andRespond(withSuccess("{\"data\":{\"id\":8,\"customerId\":10}}", MediaType.APPLICATION_JSON));
        assertThrows(ResponseStatusException.class, () -> data.requirePrescription(7L,10L,"Bearer staff"));
        server.verify();
    }

    @Test void catalogSnapshotIncludesTypePriceNameAndSellability() {
        server.expect(requestTo("http://catalog/api/products")).andRespond(withSuccess("""
                {"data":[{"name":"Lens","productType":"LENS","status":"ACTIVE","variants":[
                 {"sku":"LNS-1","name":"1.60","price":500000,"status":"ACTIVE"},
                 {"sku":"LNS-2","price":600000,"status":"INACTIVE"}]}]}
                """,MediaType.APPLICATION_JSON));
        var catalog = data.catalogItems();
        assertEquals("LENS",catalog.get("LNS-1").productType());
        assertEquals("Lens - 1.60",catalog.get("LNS-1").name());
        assertEquals(500000,catalog.get("LNS-1").price().intValueExact());
        assertTrue(catalog.get("LNS-1").sellable());
        assertFalse(catalog.get("LNS-2").sellable());
        server.verify();
    }
}
