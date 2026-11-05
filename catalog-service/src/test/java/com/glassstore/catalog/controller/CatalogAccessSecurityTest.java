package com.glassstore.catalog.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
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
}
