package com.glassstore.catalog.controller;

import com.glassstore.catalog.dto.ProductManagementDtos.*;
import com.glassstore.catalog.dto.ProductResponseDTO;
import com.glassstore.catalog.dto.ProductVariantDTO;
import com.glassstore.catalog.entity.ProductType;
import com.glassstore.catalog.service.ProductService;
import com.glassstore.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller Quản trị Sản phẩm, Biến thể và Giá (FR-PRO-01..09, FR-ADM-05).
 * Tất cả các endpoints bắt đầu bằng /api/catalog/admin/** được bảo vệ nghiêm ngặt bằng ROLE_ADMIN.
 */
@RestController
@RequestMapping("/api/catalog/admin")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class AdminProductController {

    private final ProductService productService;

    @GetMapping("/products")
    public ResponseEntity<ApiResponse<List<ProductResponseDTO>>> getAdminProducts(
            @RequestParam(name = "categoryId", required = false) Long categoryId,
            @RequestParam(name = "brandId", required = false) Long brandId,
            @RequestParam(name = "productType", required = false) ProductType productType,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "keyword", required = false) String keyword) {
        log.info("Admin GET /api/catalog/admin/products categoryId={}, brandId={}, type={}, status={}, keyword={}",
                categoryId, brandId, productType, status, keyword);
        List<ProductResponseDTO> products = productService.getAdminProducts(categoryId, brandId, productType, status, keyword);
        return ResponseEntity.ok(ApiResponse.success("Lấy danh sách sản phẩm quản trị thành công", products));
    }

    @GetMapping("/products/{id}")
    public ResponseEntity<ApiResponse<ProductResponseDTO>> getProductById(@PathVariable("id") Long id) {
        log.info("Admin GET /api/catalog/admin/products/{}", id);
        return ResponseEntity.ok(ApiResponse.success("Lấy chi tiết sản phẩm thành công", productService.getProductById(id)));
    }

    @PostMapping("/products")
    public ResponseEntity<ApiResponse<ProductResponseDTO>> createProduct(@Valid @RequestBody ProductCreateRequest request) {
        log.info("Admin POST /api/catalog/admin/products: name={}, skuCount={}",
                request.getName(), request.getVariants() != null ? request.getVariants().size() : 0);
        ProductResponseDTO created = productService.createProduct(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo sản phẩm thành công", created));
    }

    @PutMapping("/products/{id}")
    public ResponseEntity<ApiResponse<ProductResponseDTO>> updateProduct(
            @PathVariable("id") Long id,
            @Valid @RequestBody ProductUpdateRequest request) {
        log.info("Admin PUT /api/catalog/admin/products/{}: name={}", id, request.getName());
        ProductResponseDTO updated = productService.updateProduct(id, request);
        return ResponseEntity.ok(ApiResponse.success("Cập nhật thông tin sản phẩm thành công", updated));
    }

    @PatchMapping("/products/{id}/status")
    public ResponseEntity<ApiResponse<ProductResponseDTO>> updateProductStatus(
            @PathVariable("id") Long id,
            @Valid @RequestBody ProductStatusRequest request) {
        log.info("Admin PATCH /api/catalog/admin/products/{}/status: status={}", id, request.getStatus());
        ProductResponseDTO updated = productService.updateProductStatus(id, request.getStatus());
        return ResponseEntity.ok(ApiResponse.success("Cập nhật trạng thái sản phẩm thành công", updated));
    }

    @DeleteMapping("/products/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable("id") Long id) {
        log.info("Admin DELETE /api/catalog/admin/products/{}", id);
        productService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/products/{productId}/variants")
    public ResponseEntity<ApiResponse<ProductVariantDTO>> createVariant(
            @PathVariable("productId") Long productId,
            @Valid @RequestBody VariantCreateRequest request) {
        log.info("Admin POST /api/catalog/admin/products/{}/variants: sku={}", productId, request.getSku());
        ProductVariantDTO created = productService.createVariant(productId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo biến thể sản phẩm thành công", created));
    }

    @PutMapping("/variants/{variantId}")
    public ResponseEntity<ApiResponse<ProductVariantDTO>> updateVariant(
            @PathVariable("variantId") Long variantId,
            @Valid @RequestBody VariantUpdateRequest request) {
        log.info("Admin PUT /api/catalog/admin/variants/{}: sku={}", variantId, request.getSku());
        ProductVariantDTO updated = productService.updateVariant(variantId, request);
        return ResponseEntity.ok(ApiResponse.success("Cập nhật biến thể sản phẩm thành công", updated));
    }

    @PatchMapping("/variants/{variantId}/status")
    public ResponseEntity<ApiResponse<ProductVariantDTO>> updateVariantStatus(
            @PathVariable("variantId") Long variantId,
            @Valid @RequestBody VariantStatusRequest request) {
        log.info("Admin PATCH /api/catalog/admin/variants/{}/status: status={}", variantId, request.getStatus());
        ProductVariantDTO updated = productService.updateVariantStatus(variantId, request.getStatus());
        return ResponseEntity.ok(ApiResponse.success("Cập nhật trạng thái biến thể thành công", updated));
    }

    @PatchMapping("/variants/{variantId}/price")
    public ResponseEntity<ApiResponse<ProductVariantDTO>> updateVariantPrice(
            @PathVariable("variantId") Long variantId,
            @Valid @RequestBody VariantPriceRequest request) {
        log.info("Admin PATCH /api/catalog/admin/variants/{}/price: price={}, costPrice={}",
                variantId, request.getPrice(), request.getCostPrice());
        ProductVariantDTO updated = productService.updateVariantPrice(variantId, request);
        return ResponseEntity.ok(ApiResponse.success("Cập nhật giá biến thể thành công", updated));
    }

    @DeleteMapping("/variants/{variantId}")
    public ResponseEntity<Void> deleteVariant(@PathVariable("variantId") Long variantId) {
        log.info("Admin DELETE /api/catalog/admin/variants/{}", variantId);
        productService.deleteVariant(variantId);
        return ResponseEntity.noContent().build();
    }
}

