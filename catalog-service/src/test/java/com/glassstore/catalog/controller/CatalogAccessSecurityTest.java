package com.glassstore.catalog.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import com.glassstore.catalog.service.CatalogManagementService;
import static org.mockito.Mockito.when;
import java.util.List;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:catalog-access;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
@AutoConfigureMockMvc
class CatalogAccessSecurityTest {
    @Autowired MockMvc mvc;
    @org.springframework.boot.test.mock.mockito.MockBean com.glassstore.catalog.service.ProductService products;
    @org.springframework.boot.test.mock.mockito.MockBean CatalogManagementService management;
    @Test void publicCatalogDoesNotExposeInventoryApis() throws Exception {
        mvc.perform(get("/api/products")).andExpect(status().isOk());
        org.mockito.Mockito.verify(products).getAllProducts(null,null,"ACTIVE");
        mvc.perform(get("/api/inventory")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/variants/reserve").contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
    }
    @Test void inactiveProductCannotBeReadPublicly() throws Exception {
        var product=new com.glassstore.catalog.dto.ProductResponseDTO();product.setStatus("INACTIVE");
        org.mockito.Mockito.when(products.getProductById(1L)).thenReturn(product);
        mvc.perform(get("/api/products/1")).andExpect(status().isNotFound());
        mvc.perform(get("/api/products/1").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk());
    }
    @Test void customerAndCashierCannotReceiveStockTransfers() throws Exception {
        for(String role:java.util.List.of("CUSTOMER","SALES_STAFF"))
            mvc.perform(patch("/api/inventory/transfers/1/receive").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_"+role))))
                .andExpect(status().isForbidden());
    }
    @Test void staffTokenCannotImpersonateAnInternalService() throws Exception {
        mvc.perform(post("/internal/inventory/sales/check").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
            .contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
    }
    @Test void catalogAdministrationRequiresAdminAndPublicLocationsRemainReadable() throws Exception {
        when(management.getCategories()).thenReturn(List.of());
        when(management.getActiveLocations()).thenReturn(List.of());

        mvc.perform(get("/api/catalog/admin/categories")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/catalog/admin/categories")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_STAFF"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/catalog/admin/categories")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/locations")).andExpect(status().isOk());
    }

    @Test void stockVoucherApprovalAndSupplierChangesRequireAdmin() throws Exception {
        mvc.perform(get("/api/inventory/vouchers")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/inventory/vouchers")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_STAFF"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/inventory/vouchers")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LAB"))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/inventory/vouchers/1/approve")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LAB"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/inventory/suppliers")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LAB")))
                .contentType("application/json")
                .content("{\"code\":\"SUP-1\",\"name\":\"Supplier\"}"))
                .andExpect(status().isForbidden());
    }
}
