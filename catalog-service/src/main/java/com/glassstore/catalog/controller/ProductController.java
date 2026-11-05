package com.glassstore.catalog.controller;

import com.glassstore.catalog.dto.ProductResponseDTO;
import com.glassstore.catalog.entity.ProductType;
import com.glassstore.catalog.service.ProductService;
import com.glassstore.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ProductController {

    private final ProductService productService;

    private boolean publicReader() {
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getAuthorities().stream().noneMatch(a -> java.util.Set.of(
            "ROLE_ADMIN","ROLE_SALES_STAFF","ROLE_OPTOMETRIST","ROLE_LAB_TECH","ROLE_LAB","ROLE_SERVICE").contains(a.getAuthority()));
    }
    private ProductResponseDTO publicProduct(ProductResponseDTO product) {
        if (publicReader()) {
            if (!"ACTIVE".equals(product.getStatus()))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
            if (product.getVariants() != null) product.setVariants(product.getVariants().stream().filter(v -> "ACTIVE".equals(v.getStatus())).toList());
        }
        return product;
    }

    @GetMapping("/lens-recommendations")
    public ResponseEntity<ApiResponse<LensRecommendation>> recommendLenses(
            @RequestParam("odSph") java.math.BigDecimal odSph,
            @RequestParam("osSph") java.math.BigDecimal osSph) {
        java.math.BigDecimal power = odSph.abs().max(osSph.abs());
        List<String> indexes = power.compareTo(new java.math.BigDecimal("2.5")) <= 0
                ? List.of("1.56", "1.60")
                : power.compareTo(new java.math.BigDecimal("5.0")) <= 0 ? List.of("1.67") : List.of("1.74");
        boolean highPower = power.compareTo(new java.math.BigDecimal("5.0")) > 0;
        String family = indexes.contains("1.67") ? "rock" : highPower ? "crystal u6" : "";
        List<com.glassstore.catalog.dto.ProductVariantDTO> variants = productService
                .getAllProducts(null, ProductType.LENS, "ACTIVE").stream()
                .filter(p -> p.getVariants() != null)
                .flatMap(p -> p.getVariants().stream().filter(v -> {
                    String name = (p.getName() + " " + v.getName()).toLowerCase(java.util.Locale.ROOT);
                    return "ACTIVE".equals(v.getStatus()) && v.getSku() != null
                            && indexes.contains(v.getLensIndex()) && name.contains(family);
                })).toList();
        return ResponseEntity.ok(ApiResponse.success("Gợi ý tròng kính theo toa độ",
                new LensRecommendation(indexes, highPower,
                        highPower ? "Độ khúc xạ cao, nguy cơ tròng dày: chỉ định chiết suất 1.74 Crystal U6."
                                : family.isEmpty() ? "Gợi ý chiết suất 1.56 hoặc 1.60." : "Gợi ý tròng 1.67 chống trầy Rock.",
                        variants)));
    }

    public record LensRecommendation(List<String> indexes, boolean highPower, String message,
            List<com.glassstore.catalog.dto.ProductVariantDTO> variants) {}

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProductResponseDTO>>> getAllProducts(
            @RequestParam(name = "categoryId", required = false) Long categoryId,
            @RequestParam(name = "productType", required = false) ProductType productType,
            @RequestParam(name = "status", required = false) String status) {
        
        List<ProductResponseDTO> products = productService.getAllProducts(categoryId, productType, publicReader() ? "ACTIVE" : status);
        if (publicReader()) products=products.stream().map(this::publicProduct).toList();
        return ResponseEntity.ok(ApiResponse.success("Lấy danh sách sản phẩm thành công", products));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductResponseDTO>> getProductById(@PathVariable("id") Long id) {
        ProductResponseDTO product = productService.getProductById(id);
        return ResponseEntity.ok(ApiResponse.success("Lấy chi tiết sản phẩm thành công", publicProduct(product)));
    }

    @GetMapping("/slug/{slug}")
    public ResponseEntity<ApiResponse<ProductResponseDTO>> getProductBySlug(@PathVariable("slug") String slug) {
        ProductResponseDTO product = productService.getProductBySlug(slug);
        return ResponseEntity.ok(ApiResponse.success("Lấy chi tiết sản phẩm thành công", publicProduct(product)));
    }
}
