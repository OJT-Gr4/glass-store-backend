package com.glassstore.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.math.BigDecimal;

@Service
public class PosDataService {
    private final RestClient client;
    public PosDataService(RestClient.Builder builder) { this.client = builder.build(); }
    @Value("${services.catalog-service.url:http://localhost:8082}") private String catalogUrl;
    @Value("${services.identity-service.url:http://localhost:8081}") private String identityUrl;
    @Value("${services.eyewear-lab-service.url:http://localhost:8084}") private String labUrl;

    private JsonNode get(String url, String token) {
        try { return client.get().uri(url).header("Authorization", token == null ? "" : token).retrieve().body(JsonNode.class); }
        catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 401 || error.getStatusCode().value() == 403) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập không hợp lệ.");
            if (error.getStatusCode().value() == 404) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không tìm thấy dữ liệu khách hàng / toa kính yêu cầu.");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa kết nối được dữ liệu sản phẩm / thành viên / toa kính.");
        } catch (org.springframework.web.client.RestClientException error) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Dịch vụ dữ liệu chưa sẵn sàng."); }
    }

    public JsonNode actor(String token, boolean adminOnly) {
        if (token == null || !token.startsWith("Bearer ")) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        JsonNode user = get(identityUrl + "/api/auth/me", token).path("data");
        Set<String> roles = new HashSet<>(); user.path("roles").forEach(role -> roles.add(role.asText()));
        if (!roles.contains("ROLE_ADMIN") && (adminOnly || !roles.contains("ROLE_SALES_STAFF"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ thu ngân / quản lý được thực hiện thao tác này.");
        return user;
    }

    public JsonNode customer(String token, Long id) {
        JsonNode customer = get(identityUrl + "/api/users/" + id, token).path("data");
        boolean member = false;
        for (JsonNode role : customer.path("roles")) if ("ROLE_CUSTOMER".equals(role.asText())) member = true;
        if (!member || !"ACTIVE".equals(customer.path("status").asText())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Khách hàng chưa có hồ sơ thành viên đang hoạt động.");
        return customer;
    }

    public JsonNode requirePrescription(Long id, Long customerId, String token) {
        if (id == null || id <= 0 || customerId == null || customerId <= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chọn đúng khách hàng và toa kính trước khi thanh toán.");
        JsonNode response = get(labUrl + "/api/prescriptions/" + id, token);
        JsonNode prescription = response == null ? null : response.path("data");
        if (prescription == null || !prescription.path("id").isIntegralNumber()
                || prescription.path("id").longValue() != id)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Toa kính không tồn tại hoặc thiếu định danh hợp lệ.");
        if (!prescription.path("customerId").isIntegralNumber() || prescription.path("customerId").longValue() != customerId)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Toa kính không thuộc khách hàng đang thanh toán.");
        return prescription;
    }

    public record CatalogItem(BigDecimal price, String name, String productType, boolean sellable) {}

    public Map<String, CatalogItem> catalogItems() {
        Map<String, CatalogItem> items = new HashMap<>();
        for (JsonNode product : get(catalogUrl + "/api/products", null).path("data")) {
            for (JsonNode variant : product.path("variants")) {
                if (!variant.path("price").isNumber()) continue;
                items.put(variant.path("sku").asText(), new CatalogItem(variant.path("price").decimalValue(),
                        product.path("name").asText() + " - " + variant.path("name").asText(""),
                        product.path("productType").asText(),
                        "ACTIVE".equalsIgnoreCase(product.path("status").asText())
                                && "ACTIVE".equalsIgnoreCase(variant.path("status").asText())));
            }
        }
        return items;
    }
}
