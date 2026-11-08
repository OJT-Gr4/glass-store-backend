package com.glassstore.catalog.service;

import com.glassstore.catalog.dto.CatalogManagementDtos.*;
import com.glassstore.catalog.dto.InventoryLocationDTO;
import com.glassstore.catalog.entity.Brand;
import com.glassstore.catalog.entity.Category;
import com.glassstore.catalog.entity.InventoryBalance;
import com.glassstore.catalog.entity.InventoryLocation;
import com.glassstore.catalog.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CatalogManagementService {

    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final InventoryLocationRepository locationRepository;
    private final ProductRepository productRepository;
    private final InventoryBalanceRepository balanceRepository;
    private final StockTransferRepository transferRepository;
    private final ProductVariantRepository variantRepository;
    private final LabAppointmentReferenceClient appointmentReferenceClient;
    private final OrderLocationReferenceClient orderReferenceClient;

    public List<CategoryResponse> getCategories() {
        return categoryRepository.findAll().stream().map(this::toResponse).toList();
    }

    public CategoryResponse getCategory(Long id) {
        return toResponse(findCategory(id));
    }

    @Transactional
    public CategoryResponse createCategory(CategoryRequest request) {
        String name = request.name().trim();
        String slug = request.slug().trim();
        ensureCategoryNameAvailable(name, null);
        ensureCategorySlugAvailable(slug, null);
        return toResponse(categoryRepository.save(Category.builder()
                .name(name)
                .slug(slug)
                .description(trimToNull(request.description()))
                .build()));
    }

    @Transactional
    public CategoryResponse updateCategory(Long id, CategoryRequest request) {
        Category category = findCategory(id);
        String name = request.name().trim();
        String slug = request.slug().trim();
        ensureCategoryNameAvailable(name, id);
        ensureCategorySlugAvailable(slug, id);
        category.setName(name);
        category.setSlug(slug);
        category.setDescription(trimToNull(request.description()));
        return toResponse(categoryRepository.save(category));
    }

    @Transactional
    public void deleteCategory(Long id) {
        Category category = findCategory(id);
        if (productRepository.existsByCategory_Id(id)) {
            throw conflict("Không thể xóa danh mục đang được sản phẩm sử dụng.");
        }
        categoryRepository.delete(category);
    }

    public List<BrandResponse> getBrands() {
        return brandRepository.findAll().stream().map(this::toResponse).toList();
    }

    public BrandResponse getBrand(Long id) {
        return toResponse(findBrand(id));
    }

    @Transactional
    public BrandResponse createBrand(BrandRequest request) {
        String name = request.name().trim();
        ensureBrandNameAvailable(name, null);
        return toResponse(brandRepository.save(Brand.builder()
                .name(name)
                .country(trimToNull(request.country()))
                .description(trimToNull(request.description()))
                .build()));
    }

    @Transactional
    public BrandResponse updateBrand(Long id, BrandRequest request) {
        Brand brand = findBrand(id);
        String name = request.name().trim();
        ensureBrandNameAvailable(name, id);
        brand.setName(name);
        brand.setCountry(trimToNull(request.country()));
        brand.setDescription(trimToNull(request.description()));
        return toResponse(brandRepository.save(brand));
    }

    @Transactional
    public void deleteBrand(Long id) {
        Brand brand = findBrand(id);
        if (productRepository.existsByBrand_Id(id)) {
            throw conflict("Không thể xóa thương hiệu đang được sản phẩm sử dụng.");
        }
        brandRepository.delete(brand);
    }

    public List<LocationResponse> getLocations() {
        return locationRepository.findAllByOrderByCodeAsc().stream().map(this::toResponse).toList();
    }

    public List<InventoryLocationDTO> getActiveLocations() {
        return locationRepository.findAllByActiveTrueOrderByCodeAsc().stream()
                .map(location -> new InventoryLocationDTO(
                        location.getCode(), location.getName(), location.getArea(), location.getLocationType()))
                .toList();
    }

    public LocationResponse getLocation(Long id) {
        return toResponse(findLocation(id));
    }

    @Transactional
    public LocationResponse createLocation(LocationCreateRequest request) {
        String code = request.code().trim();
        String name = request.name().trim();
        String locationType = normalizeLocationType(request.locationType());
        ensureLocationCodeAvailable(code, null);
        ensureLocationNameAvailable(name, null);
        InventoryLocation location = locationRepository.save(InventoryLocation.builder()
                .code(code)
                .name(name)
                .area(trimToNull(request.area()))
                .locationType(locationType)
                .active(true)
                .build());
        List<InventoryBalance> openingBalances = variantRepository.findAll().stream()
                .map(variant -> InventoryBalance.builder()
                        .variant(variant)
                        .location(location)
                        .quantity(0)
                        .build())
                .toList();
        balanceRepository.saveAll(openingBalances);
        return toResponse(location);
    }

    @Transactional
    public LocationResponse updateLocation(Long id, LocationUpdateRequest request) {
        InventoryLocation location = findLocation(id);
        String name = request.name().trim();
        ensureLocationNameAvailable(name, id);
        location.setName(name);
        location.setArea(trimToNull(request.area()));
        if (request.locationType() != null) {
            location.setLocationType(normalizeLocationType(request.locationType()));
        }
        return toResponse(locationRepository.save(location));
    }

    @Transactional
    public LocationResponse setLocationActive(Long id, LocationActiveRequest request) {
        if (request.active() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trạng thái active là bắt buộc.");
        }
        InventoryLocation location = findLocation(id);
        location.setActive(request.active());
        return toResponse(locationRepository.save(location));
    }

    @Transactional
    public void deleteLocation(Long id) {
        InventoryLocation location = findLocation(id);
        if (balanceRepository.existsByLocation_Id(id)
                || transferRepository.existsBySourceLocation_IdOrDestinationLocation_Id(id, id)
                || appointmentReferenceClient.hasAppointmentsForLocation(location.getCode())
                || orderReferenceClient.hasReferences(location.getCode(), location.getName())) {
            throw conflict("Không thể xóa địa điểm đang được tồn kho, phiếu điều chuyển hoặc lịch hẹn tham chiếu; hãy vô hiệu hóa địa điểm.");
        }
        locationRepository.delete(location);
    }

    private Category findCategory(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> notFound("Không tìm thấy danh mục."));
    }

    private Brand findBrand(Long id) {
        return brandRepository.findById(id)
                .orElseThrow(() -> notFound("Không tìm thấy thương hiệu."));
    }

    private InventoryLocation findLocation(Long id) {
        return locationRepository.findById(id)
                .orElseThrow(() -> notFound("Không tìm thấy địa điểm."));
    }

    private void ensureCategoryNameAvailable(String name, Long currentId) {
        boolean exists = currentId == null
                ? categoryRepository.existsByNameIgnoreCase(name)
                : categoryRepository.existsByNameIgnoreCaseAndIdNot(name, currentId);
        if (exists) throw conflict("Tên danh mục đã tồn tại.");
    }

    private void ensureCategorySlugAvailable(String slug, Long currentId) {
        boolean exists = currentId == null
                ? categoryRepository.existsBySlugIgnoreCase(slug)
                : categoryRepository.existsBySlugIgnoreCaseAndIdNot(slug, currentId);
        if (exists) throw conflict("Slug danh mục đã tồn tại.");
    }

    private void ensureBrandNameAvailable(String name, Long currentId) {
        boolean exists = currentId == null
                ? brandRepository.existsByNameIgnoreCase(name)
                : brandRepository.existsByNameIgnoreCaseAndIdNot(name, currentId);
        if (exists) throw conflict("Tên thương hiệu đã tồn tại.");
    }

    private void ensureLocationCodeAvailable(String code, Long currentId) {
        boolean exists = currentId == null
                ? locationRepository.existsByCodeIgnoreCase(code)
                : locationRepository.existsByCodeIgnoreCaseAndIdNot(code, currentId);
        if (exists) throw conflict("Mã địa điểm đã tồn tại.");
    }

    private void ensureLocationNameAvailable(String name, Long currentId) {
        boolean exists = currentId == null
                ? locationRepository.existsByNameIgnoreCase(name)
                : locationRepository.existsByNameIgnoreCaseAndIdNot(name, currentId);
        if (exists) throw conflict("Tên địa điểm đã tồn tại.");
    }

    private String normalizeLocationType(String locationType) {
        String normalized = locationType == null ? "" : locationType.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("BRANCH") && !normalized.equals("WAREHOUSE")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loại địa điểm không hợp lệ.");
        }
        return normalized;
    }

    private CategoryResponse toResponse(Category category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getSlug(), category.getDescription());
    }

    private BrandResponse toResponse(Brand brand) {
        return new BrandResponse(brand.getId(), brand.getName(), brand.getCountry(), brand.getDescription());
    }

    private LocationResponse toResponse(InventoryLocation location) {
        return new LocationResponse(location.getId(), location.getCode(), location.getName(),
                location.getArea(), location.getLocationType(), location.isActive());
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
