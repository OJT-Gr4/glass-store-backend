package com.glassstore.lab.client;
import com.fasterxml.jackson.databind.JsonNode;
import com.glassstore.common.security.CurrentActor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
@Component
public class PrescriptionCustomerClient {
    private final RestClient client;
    public PrescriptionCustomerClient(@Value("${services.identity-service.url:http://localhost:8081}") String url) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000); factory.setReadTimeout(3000);
        client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
    public String requireCustomer(Long id) {
        if (!(CurrentActor.authentication() instanceof JwtAuthenticationToken auth))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bearer token required");
        try {
            JsonNode response = client.get().uri("/api/v1/users/{id}", id)
                .headers(h -> h.setBearerAuth(auth.getToken().getTokenValue()))
                .retrieve().onStatus(s -> s.value() == 404, (req, res) -> {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer does not exist");
                }).body(JsonNode.class);
            JsonNode user = response == null ? null : response.get("data");
            boolean customer = false;
            if (user != null && user.path("roles").isArray())
                for (JsonNode role : user.path("roles"))
                    customer |= "CUSTOMER".equals(role.asText()) || "ROLE_CUSTOMER".equals(role.asText());
            if (user == null || user.path("id").asLong() != id || !customer)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer does not exist");
            return user.path("fullName").asText(null);
        } catch (RestClientException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cannot verify customer", error);
        }
    }
}
