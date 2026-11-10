package com.glassstore.catalog.service;

import com.glassstore.catalog.dto.ProductManagementDtos.*;
import com.glassstore.catalog.dto.ProductResponseDTO;
import com.glassstore.catalog.dto.ProductVariantDTO;
import com.glassstore.catalog.entity.ProductType;

import java.util.List;

public interface ProductService {
    List<ProductResponseDTO> getAllProducts(Long categoryId, ProductType productType, String status);
    ProductResponseDTO getProductById(Long id);
    ProductResponseDTO getProductBySlug(String slug);

    // Admin CRUD operations (FR-PRO-01..09, FR-ADM-05)
    List<ProductResponseDTO> getAdminProducts(Long categoryId, Long brandId, ProductType productType, String status, String keyword);
    ProductResponseDTO createProduct(ProductCreateRequest request);
    ProductResponseDTO updateProduct(Long id, ProductUpdateRequest request);
    ProductResponseDTO updateProductStatus(Long id, String status);
    void deleteProduct(Long id);

    ProductVariantDTO createVariant(Long productId, VariantCreateRequest request);
    ProductVariantDTO updateVariant(Long variantId, VariantUpdateRequest request);
    ProductVariantDTO updateVariantStatus(Long variantId, String status);
    ProductVariantDTO updateVariantPrice(Long variantId, VariantPriceRequest request);
    void deleteVariant(Long variantId);
}
