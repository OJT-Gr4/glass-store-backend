package com.glassstore.catalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.glassstore.common.security.ServiceTokenIssuer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class OrderLocationReferenceClient {

    private final RestClient client;
    private final String jwtSecret;
    private final String serviceName;

    public OrderLocationReferenceClient(
            @Value("${services.order-pos-service.url:http://127.0.0.1:8083}") String url,
            @Value("${jwt.secret}") String jwtSecret,
            @Value("${spring.application.name}") String serviceName) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(3000);
        requestFactory.setReadTimeout(3000);
        client = RestClient.builder().baseUrl(url).requestFactory(requestFactory).build();
        this.jwtSecret = jwtSecret;
        this.serviceName = serviceName;
    }

    public boolean hasReferences(String locationCode, String locationName) {
        try {
            JsonNode response = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/location-references/{code}")
                            .queryParam("name", locationName)
                            .build(locationCode))
                    .headers(headers -> headers.setBearerAuth(ServiceTokenIssuer.issue(jwtSecret, serviceName)))
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode data = response == null ? null : response.get("data");
            if (data == null || !data.isBoolean()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Không thể xác minh đơn hàng đang tham chiếu địa điểm.");
            }
            return data.asBoolean();
        } catch (RestClientException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Không thể xác minh đơn hàng đang tham chiếu địa điểm.", error);
        }
    }
}
