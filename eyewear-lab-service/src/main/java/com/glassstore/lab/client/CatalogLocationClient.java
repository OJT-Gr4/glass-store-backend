package com.glassstore.lab.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CatalogLocationClient {

    private final RestClient client;

    public CatalogLocationClient(@Value("${services.catalog-service.url:http://127.0.0.1:8082}") String url) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(3000);
        requestFactory.setReadTimeout(3000);
        client = RestClient.builder().baseUrl(url).requestFactory(requestFactory).build();
    }

    public String requireActiveLocation(String locationCode) {
        try {
            JsonNode response = client.get().uri("/api/locations").retrieve().body(JsonNode.class);
            JsonNode locations = response == null ? null : response.get("data");
            if (locations == null || !locations.isArray()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Không thể xác minh địa điểm đặt lịch.");
            }
            for (JsonNode location : locations) {
                if (locationCode.equalsIgnoreCase(location.path("code").asText())) {
                    return location.path("code").asText();
                }
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Địa điểm không tồn tại hoặc đã bị ẩn.");
        } catch (RestClientException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Không thể xác minh địa điểm đặt lịch.", error);
        }
    }
}
