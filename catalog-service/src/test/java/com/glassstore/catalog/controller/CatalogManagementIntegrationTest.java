package com.glassstore.catalog.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glassstore.catalog.entity.Product;
import com.glassstore.catalog.entity.ProductType;
import com.glassstore.catalog.entity.ProductVariant;
import com.glassstore.catalog.repository.BrandRepository;
import com.glassstore.catalog.repository.CategoryRepository;
import com.glassstore.catalog.repository.InventoryBalanceRepository;
import com.glassstore.catalog.repository.ProductRepository;
import com.glassstore.catalog.repository.ProductVariantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalogManagementIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CategoryRepository categoryRepository;
    @Autowired BrandRepository brandRepository;
    @Autowired ProductRepository productRepository;
    @Autowired ProductVariantRepository variantRepository;
    @Autowired InventoryBalanceRepository balanceRepository;

    @Test
    void adminCreatesAndReadsCategoryBrandAndLocationAndPublicApiHidesInactiveLocations() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

        JsonNode category = createAndRead(
                "/api/catalog/admin/categories",
                "{\"name\":\"Test category " + suffix + "\",\"slug\":\"test-category-" + suffix + "\",\"description\":\"catalog test\"}",
                "/api/catalog/admin/categories/");
        assertEquals("test-category-" + suffix, category.path("slug").asText());
        long categoryId = category.path("id").asLong();
        mvc.perform(put("/api/catalog/admin/categories/" + categoryId)
                .with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Updated category " + suffix + "\",\"slug\":\"updated-category-" + suffix + "\",\"description\":\"updated\"}"))
                .andExpect(status().isOk());
        assertEquals("Updated category " + suffix,
                readItem("/api/catalog/admin/categories/" + categoryId).path("name").asText());

        JsonNode brand = createAndRead(
                "/api/catalog/admin/brands",
                "{\"name\":\"Test brand " + suffix + "\",\"country\":\"Test\",\"description\":\"catalog test\"}",
                "/api/catalog/admin/brands/");
        assertEquals("Test brand " + suffix, brand.path("name").asText());
        long brandId = brand.path("id").asLong();
        mvc.perform(put("/api/catalog/admin/brands/" + brandId)
                .with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Updated brand " + suffix + "\",\"country\":\"Updated\",\"description\":\"updated\"}"))
                .andExpect(status().isOk());
        assertEquals("Updated brand " + suffix,
                readItem("/api/catalog/admin/brands/" + brandId).path("name").asText());

        Product product = productRepository.save(Product.builder()
                .name("Inventory test " + suffix)
                .slug("inventory-test-" + suffix)
                .category(categoryRepository.findById(categoryId).orElseThrow())
                .brand(brandRepository.findById(brandId).orElseThrow())
                .productType(ProductType.FRAME)
                .build());
        String sku = "TEST-" + suffix.toUpperCase();
        variantRepository.save(ProductVariant.builder()
                .product(product)
                .sku(sku)
                .barcode("BAR-" + suffix.toUpperCase())
                .name("Test variant")
                .price(BigDecimal.ONE)
                .costPrice(BigDecimal.ONE)
                .stockQuantity(7)
                .build());

        String code = "TEST_" + suffix.toUpperCase();
        JsonNode location = createAndRead(
                "/api/catalog/admin/locations",
                "{\"code\":\"" + code + "\",\"name\":\"Test location " + suffix + "\",\"area\":\"Central\",\"locationType\":\"BRANCH\"}",
                "/api/catalog/admin/locations/");
        long locationId = location.path("id").asLong();
        assertEquals(code, location.path("code").asText());
        assertEquals("Test location " + suffix, location.path("name").asText());
        assertEquals("Central", location.path("area").asText());
        assertEquals("BRANCH", location.path("locationType").asText());
        mvc.perform(put("/api/catalog/admin/locations/" + locationId)
                .with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Updated location " + suffix + "\",\"area\":\"North\",\"locationType\":\"WAREHOUSE\"}"))
                .andExpect(status().isOk());
        JsonNode updatedLocation = readItem("/api/catalog/admin/locations/" + locationId);
        assertEquals(code, updatedLocation.path("code").asText());
        assertEquals("Updated location " + suffix, updatedLocation.path("name").asText());
        assertEquals("North", updatedLocation.path("area").asText());
        assertEquals("WAREHOUSE", updatedLocation.path("locationType").asText());
        assertEquals(0, balanceRepository.findBySkuAndLocationCode(sku, code).orElseThrow().getQuantity());
        assertEquals(7, variantRepository.findBySku(sku).orElseThrow().getStockQuantity());

        mvc.perform(post("/api/catalog/admin/locations")
                .with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\",\"name\":\"Duplicate location " + suffix + "\",\"locationType\":\"BRANCH\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/catalog/admin/brands")
                .with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Updated brand " + suffix + "\"}"))
                .andExpect(status().isConflict());

        JsonNode publicLocations = objectMapper.readTree(mvc.perform(get("/api/locations"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertEquals(code, findLocation(publicLocations, code).path("code").asText());

        mvc.perform(patch("/api/catalog/admin/locations/" + locationId + "/active")
                .with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
                .andExpect(status().isOk());

        JsonNode afterDeactivate = objectMapper.readTree(mvc.perform(get("/api/locations"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        for (JsonNode publicLocation : afterDeactivate) {
            if (code.equals(publicLocation.path("code").asText())) {
                throw new AssertionError("Inactive location appeared in the public list");
            }
        }
    }

    private JsonNode createAndRead(String collection, String body, String itemCollection) throws Exception {
        String created = mvc.perform(post(collection)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode createdData = objectMapper.readTree(created).path("data");
        long id = createdData.path("id").asLong();

        String read = mvc.perform(get(itemCollection + id)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(read).path("data");
    }

    private JsonNode readItem(String path) throws Exception {
        String response = mvc.perform(get(path)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private JsonNode findLocation(JsonNode locations, String code) {
        for (JsonNode location : locations) {
            if (code.equals(location.path("code").asText())) return location;
        }
        throw new AssertionError("Created location was not returned by the public endpoint");
    }
}
