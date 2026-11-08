package com.glassstore.catalog.controller;

import com.glassstore.catalog.dto.CatalogManagementDtos.*;
import com.glassstore.catalog.dto.InventoryLocationDTO;
import com.glassstore.catalog.service.CatalogManagementService;
import com.glassstore.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class CatalogManagementController {

    private final CatalogManagementService managementService;

    @GetMapping("/api/locations")
    public ResponseEntity<ApiResponse<List<InventoryLocationDTO>>> getPublicLocations() {
        return ResponseEntity.ok(ApiResponse.success("Lấy danh sách địa điểm đang hoạt động thành công",
                managementService.getActiveLocations()));
    }

    @GetMapping("/api/catalog/admin/categories")
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> getCategories() {
        return ResponseEntity.ok(ApiResponse.success("Lấy danh sách danh mục thành công",
                managementService.getCategories()));
    }

    @GetMapping("/api/catalog/admin/categories/{id}")
    public ResponseEntity<ApiResponse<CategoryResponse>> getCategory(@PathVariable("id") Long id) {
        return ResponseEntity.ok(ApiResponse.success("Lấy danh mục thành công",
                managementService.getCategory(id)));
    }

    @PostMapping("/api/catalog/admin/categories")
    public ResponseEntity<ApiResponse<CategoryResponse>> createCategory(
            @Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Tạo danh mục thành công",
                managementService.createCategory(request)));
    }

    @PutMapping("/api/catalog/admin/categories/{id}")
    public ResponseEntity<ApiResponse<CategoryResponse>> updateCategory(
            @PathVariable("id") Long id, @Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Cập nhật danh mục thành công",
                managementService.updateCategory(id, request)));
    }

    @DeleteMapping("/api/catalog/admin/categories/{id}")
    public ResponseEntity<Void> deleteCategory(@PathVariable("id") Long id) {
        managementService.deleteCategory(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/catalog/admin/brands")
    public ResponseEntity<ApiResponse<List<BrandResponse>>> getBrands() {
        return ResponseEntity.ok(ApiResponse.success("Lấy danh sách thương hiệu thành công",
                managementService.getBrands()));
    }

    @GetMapping("/api/catalog/admin/brands/{id}")
    public ResponseEntity<ApiResponse<BrandResponse>> getBrand(@PathVariable("id") Long id) {
        return ResponseEntity.ok(ApiResponse.success("Lấy thương hiệu thành công",
                managementService.getBrand(id)));
    }

    @PostMapping("/api/catalog/admin/brands")
    public ResponseEntity<ApiResponse<BrandResponse>> createBrand(@Valid @RequestBody BrandRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Tạo thương hiệu thành công",
                managementService.createBrand(request)));
    }

    @PutMapping("/api/catalog/admin/brands/{id}")
    public ResponseEntity<ApiResponse<BrandResponse>> updateBrand(
            @PathVariable("id") Long id, @Valid @RequestBody BrandRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Cập nhật thương hiệu thành công",
                managementService.updateBrand(id, request)));
    }

    @DeleteMapping("/api/catalog/admin/brands/{id}")
    public ResponseEntity<Void> deleteBrand(@PathVariable("id") Long id) {
        managementService.deleteBrand(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/catalog/admin/locations")
    public ResponseEntity<ApiResponse<List<LocationResponse>>> getLocations() {
        return ResponseEntity.ok(ApiResponse.success("Lấy danh sách địa điểm thành công",
                managementService.getLocations()));
    }

    @GetMapping("/api/catalog/admin/locations/{id}")
    public ResponseEntity<ApiResponse<LocationResponse>> getLocation(@PathVariable("id") Long id) {
        return ResponseEntity.ok(ApiResponse.success("Lấy địa điểm thành công",
                managementService.getLocation(id)));
    }

    @PostMapping("/api/catalog/admin/locations")
    public ResponseEntity<ApiResponse<LocationResponse>> createLocation(
            @Valid @RequestBody LocationCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Tạo địa điểm thành công",
                managementService.createLocation(request)));
    }

    @PutMapping("/api/catalog/admin/locations/{id}")
    public ResponseEntity<ApiResponse<LocationResponse>> updateLocation(
            @PathVariable("id") Long id, @Valid @RequestBody LocationUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Cập nhật địa điểm thành công",
                managementService.updateLocation(id, request)));
    }

    @PatchMapping("/api/catalog/admin/locations/{id}/active")
    public ResponseEntity<ApiResponse<LocationResponse>> setLocationActive(
            @PathVariable("id") Long id, @RequestBody LocationActiveRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Cập nhật trạng thái địa điểm thành công",
                managementService.setLocationActive(id, request)));
    }

    @DeleteMapping("/api/catalog/admin/locations/{id}")
    public ResponseEntity<Void> deleteLocation(@PathVariable("id") Long id) {
        managementService.deleteLocation(id);
        return ResponseEntity.noContent().build();
    }
}
