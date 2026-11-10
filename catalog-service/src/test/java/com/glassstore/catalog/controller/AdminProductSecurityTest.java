package com.glassstore.catalog.controller;

import com.glassstore.catalog.config.InventorySecurityConfig;
import com.glassstore.catalog.service.ProductService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kiểm thử bảo mật phân quyền Role (Subtask 3 / FR-ADM-05).
 * Kiểm chứng Anonymous -> 401, Sai Role (SALES_STAFF, CUSTOMER) -> 403, ROLE_ADMIN -> 200.
 * Sử dụng TestConfiguration độc lập, không phụ thuộc ByteBuddy dynamic attach.
 */
@WebMvcTest(AdminProductController.class)
@Import(InventorySecurityConfig.class)
class AdminProductSecurityTest {

    @TestConfiguration
    static class Config {
        @Bean
        public ProductService productService() {
            return (ProductService) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{ProductService.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("hashCode".equals(name)) return 1;
                        if ("equals".equals(name)) return proxy == args[0];
                        if ("toString".equals(name)) return "ProductServiceProxy";
                        if ("getAdminProducts".equals(name)) return List.of();
                        return null;
                    });
        }
    }

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("Chưa đăng nhập (Anonymous) gọi /api/catalog/admin/products -> 401 Unauthorized")
    void anonymous_Returns401() throws Exception {
        mvc.perform(get("/api/catalog/admin/products"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Sai role (ROLE_SALES_STAFF) gọi /api/catalog/admin/products -> 403 Forbidden")
    void salesStaff_Returns403() throws Exception {
        mvc.perform(get("/api/catalog/admin/products")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_STAFF"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Sai role (ROLE_CUSTOMER) gọi /api/catalog/admin/products -> 403 Forbidden")
    void customer_Returns403() throws Exception {
        mvc.perform(get("/api/catalog/admin/products")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Đúng quyền (ROLE_ADMIN) gọi /api/catalog/admin/products -> 200 OK")
    void admin_Returns200() throws Exception {
        mvc.perform(get("/api/catalog/admin/products")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }
}
