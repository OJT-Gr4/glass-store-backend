package com.glassstore.catalog.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glassstore.catalog.dto.ProductManagementDtos.*;
import com.glassstore.catalog.dto.ProductResponseDTO;
import com.glassstore.catalog.dto.ProductVariantDTO;
import com.glassstore.catalog.entity.ProductType;
import com.glassstore.catalog.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller unit tests cho AdminProductController sử dụng Standalone MockMvc.
 * Đảm bảo các route HTTP, validation và response status chạy độc lập với 0 overhead.
 */
class AdminProductControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProductService productService;

    @BeforeEach
    void setUp() {
        productService = (ProductService) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ProductService.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getAdminProducts".equals(name)) {
                        return List.of(ProductResponseDTO.builder()
                                .id(1L)
                                .name("Gọng Bolon")
                                .slug("gong-bolon")
                                .status("ACTIVE")
                                .productType(ProductType.FRAME)
                                .build());
                    }
                    if ("getProductById".equals(name)) {
                        return ProductResponseDTO.builder()
                                .id((Long) args[0])
                                .name("Gọng Bolon")
                                .slug("gong-bolon")
                                .status("ACTIVE")
                                .productType(ProductType.FRAME)
                                .build();
                    }
                    if ("createProduct".equals(name)) {
                        ProductCreateRequest req = (ProductCreateRequest) args[0];
                        return ProductResponseDTO.builder()
                                .id(10L)
                                .name(req.getName())
                                .slug(req.getSlug())
                                .status("ACTIVE")
                                .productType(req.getProductType())
                                .build();
                    }
                    if ("updateProduct".equals(name)) {
                        ProductUpdateRequest req = (ProductUpdateRequest) args[1];
                        return ProductResponseDTO.builder()
                                .id((Long) args[0])
                                .name(req.getName())
                                .slug(req.getSlug())
                                .status("ACTIVE")
                                .build();
                    }
                    if ("updateProductStatus".equals(name)) {
                        return ProductResponseDTO.builder()
                                .id((Long) args[0])
                                .status((String) args[1])
                                .build();
                    }
                    if ("deleteProduct".equals(name)) {
                        return null;
                    }
                    if ("createVariant".equals(name)) {
                        VariantCreateRequest req = (VariantCreateRequest) args[1];
                        return ProductVariantDTO.builder()
                                .id(100L)
                                .sku(req.getSku())
                                .barcode(req.getBarcode())
                                .name(req.getName())
                                .price(req.getPrice())
                                .costPrice(req.getCostPrice())
                                .stockQuantity(0)
                                .status("ACTIVE")
                                .build();
                    }
                    if ("updateVariant".equals(name)) {
                        VariantUpdateRequest req = (VariantUpdateRequest) args[1];
                        return ProductVariantDTO.builder()
                                .id((Long) args[0])
                                .sku(req.getSku())
                                .barcode(req.getBarcode())
                                .name(req.getName())
                                .price(req.getPrice())
                                .costPrice(req.getCostPrice())
                                .status(req.getStatus())
                                .build();
                    }
                    if ("updateVariantStatus".equals(name)) {
                        return ProductVariantDTO.builder()
                                .id((Long) args[0])
                                .status((String) args[1])
                                .build();
                    }
                    if ("updateVariantPrice".equals(name)) {
                        VariantPriceRequest req = (VariantPriceRequest) args[1];
                        return ProductVariantDTO.builder()
                                .id((Long) args[0])
                                .price(req.getPrice())
                                .costPrice(req.getCostPrice())
                                .build();
                    }
                    if ("deleteVariant".equals(name)) {
                        return null;
                    }
                    return null;
                });

        AdminProductController controller = new AdminProductController(productService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /api/catalog/admin/products -> 200 OK kèm danh sách sản phẩm")
    void getAdminProducts_Returns200() throws Exception {
        mockMvc.perform(get("/api/catalog/admin/products")
                        .param("status", "ACTIVE")
                        .param("keyword", "Bolon"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Gọng Bolon"));
    }

    @Test
    @DisplayName("GET /api/catalog/admin/products/{id} -> 200 OK")
    void getProductById_Returns200() throws Exception {
        mockMvc.perform(get("/api/catalog/admin/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    @DisplayName("POST /api/catalog/admin/products -> 201 Created")
    void createProduct_Returns201() throws Exception {
        ProductCreateRequest req = ProductCreateRequest.builder()
                .name("Kính Mới")
                .slug("kinh-moi")
                .categoryId(1L)
                .brandId(2L)
                .productType(ProductType.FRAME)
                .status("ACTIVE")
                .build();

        mockMvc.perform(post("/api/catalog/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("Kính Mới"));
    }

    @Test
    @DisplayName("POST /api/catalog/admin/products thiếu tên -> 400 Bad Request")
    void createProduct_MissingName_Returns400() throws Exception {
        ProductCreateRequest req = ProductCreateRequest.builder()
                .slug("kinh-moi")
                .categoryId(1L)
                .brandId(2L)
                .productType(ProductType.FRAME)
                .build();

        mockMvc.perform(post("/api/catalog/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/catalog/admin/products/{id} -> 200 OK")
    void updateProduct_Returns200() throws Exception {
        ProductUpdateRequest req = ProductUpdateRequest.builder()
                .name("Kính Sửa")
                .slug("kinh-sua")
                .categoryId(1L)
                .brandId(2L)
                .productType(ProductType.FRAME)
                .status("ACTIVE")
                .build();

        mockMvc.perform(put("/api/catalog/admin/products/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("Kính Sửa"));
    }

    @Test
    @DisplayName("PATCH /api/catalog/admin/products/{id}/status -> 200 OK")
    void updateProductStatus_Returns200() throws Exception {
        ProductStatusRequest req = new ProductStatusRequest();
        req.setStatus("HIDDEN");

        mockMvc.perform(patch("/api/catalog/admin/products/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HIDDEN"));
    }

    @Test
    @DisplayName("DELETE /api/catalog/admin/products/{id} -> 204 No Content")
    void deleteProduct_Returns204() throws Exception {
        mockMvc.perform(delete("/api/catalog/admin/products/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("POST /api/catalog/admin/products/{id}/variants -> 201 Created")
    void createVariant_Returns201() throws Exception {
        VariantCreateRequest req = VariantCreateRequest.builder()
                .sku("SKU-VAR-01")
                .barcode("BAR-01")
                .name("Biến thể 01")
                .price(new BigDecimal("1500000.00"))
                .costPrice(new BigDecimal("800000.00"))
                .status("ACTIVE")
                .build();

        mockMvc.perform(post("/api/catalog/admin/products/1/variants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sku").value("SKU-VAR-01"))
                .andExpect(jsonPath("$.data.stockQuantity").value(0));
    }

    @Test
    @DisplayName("POST /api/catalog/admin/products/{id}/variants thiếu SKU hoặc giá -> 400 Bad Request")
    void createVariant_MissingSku_Returns400() throws Exception {
        VariantCreateRequest req = VariantCreateRequest.builder()
                .name("Thiếu SKU")
                .price(new BigDecimal("1500000.00"))
                .build();

        mockMvc.perform(post("/api/catalog/admin/products/1/variants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/catalog/admin/variants/{id} -> 200 OK")
    void updateVariant_Returns200() throws Exception {
        VariantUpdateRequest req = VariantUpdateRequest.builder()
                .sku("SKU-VAR-01-UPD")
                .barcode("BAR-01-UPD")
                .name("Biến thể sửa")
                .price(new BigDecimal("1800000.00"))
                .costPrice(new BigDecimal("950000.00"))
                .status("ACTIVE")
                .build();

        mockMvc.perform(put("/api/catalog/admin/variants/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sku").value("SKU-VAR-01-UPD"));
    }

    @Test
    @DisplayName("PATCH /api/catalog/admin/variants/{id}/status -> 200 OK")
    void updateVariantStatus_Returns200() throws Exception {
        VariantStatusRequest req = new VariantStatusRequest();
        req.setStatus("DISCONTINUED");

        mockMvc.perform(patch("/api/catalog/admin/variants/100/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISCONTINUED"));
    }

    @Test
    @DisplayName("PATCH /api/catalog/admin/variants/{id}/price -> 200 OK (AC3 thay đổi giá qua server)")
    void updateVariantPrice_Returns200() throws Exception {
        VariantPriceRequest req = VariantPriceRequest.builder()
                .price(new BigDecimal("2200000.00"))
                .costPrice(new BigDecimal("1100000.00"))
                .build();

        mockMvc.perform(patch("/api/catalog/admin/variants/100/price")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value(2200000.00))
                .andExpect(jsonPath("$.data.costPrice").value(1100000.00));
    }

    @Test
    @DisplayName("DELETE /api/catalog/admin/variants/{id} -> 204 No Content")
    void deleteVariant_Returns204() throws Exception {
        mockMvc.perform(delete("/api/catalog/admin/variants/100"))
                .andExpect(status().isNoContent());
    }
}
