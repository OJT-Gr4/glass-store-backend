package com.glassstore.catalog.service.impl;

import com.glassstore.catalog.dto.ProductManagementDtos.*;
import com.glassstore.catalog.dto.ProductResponseDTO;
import com.glassstore.catalog.dto.ProductVariantDTO;
import com.glassstore.catalog.entity.*;
import com.glassstore.catalog.repository.*;
import com.glassstore.catalog.service.ProductService;
import com.glassstore.catalog.util.SlugUtils;
import com.glassstore.catalog.util.UrlSafetyValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final InventoryLocationRepository locationRepository;
    private final InventoryBalanceRepository balanceRepository;

    @Override
    public List<ProductResponseDTO> getAllProducts(Long categoryId, ProductType productType, String status) {
        List<Product> products;

        if (categoryId != null) {
            products = productRepository.findByCategoryId(categoryId);
        } else if (productType != null) {
            products = productRepository.findByProductType(productType);
        } else if (status != null && !"ALL".equalsIgnoreCase(status)) {
            products = productRepository.findByStatus(status);
        } else {
            products = productRepository.findAll();
        }

        return products.stream()
                .filter(p -> categoryId == null || (p.getCategory() != null && Objects.equals(p.getCategory().getId(), categoryId)))
                .filter(p -> productType == null || p.getProductType() == productType)
                .filter(p -> status == null || "ALL".equalsIgnoreCase(status) || (p.getStatus() != null && p.getStatus().equalsIgnoreCase(status)))
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Override
    public ProductResponseDTO getProductById(Long id) {
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID sản phẩm không được để trống.");
        }
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy sản phẩm với ID: " + id));
        return mapToDTO(product);
    }

    @Override
    public ProductResponseDTO getProductBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slug sản phẩm không được để trống.");
        }
        Product product = productRepository.findBySlug(slug.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy sản phẩm với slug: " + slug));
        return mapToDTO(product);
    }

    @Override
    public List<ProductResponseDTO> getAdminProducts(Long categoryId, Long brandId, ProductType productType, String status, String keyword) {
        List<Product> products = productRepository.findAll();

        String normalizedKeyword = (keyword != null && !keyword.isBlank())
                ? keyword.trim().toLowerCase(Locale.ROOT)
                : null;

        return products.stream()
                .filter(p -> categoryId == null || (p.getCategory() != null && Objects.equals(p.getCategory().getId(), categoryId)))
                .filter(p -> brandId == null || (p.getBrand() != null && Objects.equals(p.getBrand().getId(), brandId)))
                .filter(p -> productType == null || p.getProductType() == productType)
                .filter(p -> status == null || "ALL".equalsIgnoreCase(status) || (p.getStatus() != null && p.getStatus().equalsIgnoreCase(status)))
                .filter(p -> {
                    if (normalizedKeyword == null) {
                        return true;
                    }
                    boolean matchProduct = (p.getName() != null && p.getName().toLowerCase(Locale.ROOT).contains(normalizedKeyword))
                            || (p.getSlug() != null && p.getSlug().toLowerCase(Locale.ROOT).contains(normalizedKeyword))
                            || (p.getDescription() != null && p.getDescription().toLowerCase(Locale.ROOT).contains(normalizedKeyword))
                            || (p.getBrand() != null && p.getBrand().getName() != null && p.getBrand().getName().toLowerCase(Locale.ROOT).contains(normalizedKeyword))
                            || (p.getCategory() != null && p.getCategory().getName() != null && p.getCategory().getName().toLowerCase(Locale.ROOT).contains(normalizedKeyword));
                    if (matchProduct) {
                        return true;
                    }
                    if (p.getVariants() != null) {
                        return p.getVariants().stream().anyMatch(v ->
                                (v.getSku() != null && v.getSku().toLowerCase(Locale.ROOT).contains(normalizedKeyword))
                                        || (v.getBarcode() != null && v.getBarcode().toLowerCase(Locale.ROOT).contains(normalizedKeyword))
                                        || (v.getName() != null && v.getName().toLowerCase(Locale.ROOT).contains(normalizedKeyword))
                        );
                    }
                    return false;
                })
                .sorted(Comparator.comparing(Product::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public ProductResponseDTO createProduct(ProductCreateRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dữ liệu tạo sản phẩm không được để trống.");
        }
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tên sản phẩm không được để trống.");
        }
        if (request.getCategoryId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID danh mục không được để trống.");
        }
        if (request.getBrandId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID thương hiệu không được để trống.");
        }
        if (request.getProductType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loại sản phẩm không được để trống.");
        }

        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy danh mục với ID: " + request.getCategoryId()));
        Brand brand = brandRepository.findById(request.getBrandId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thương hiệu với ID: " + request.getBrandId()));

        String slug = request.getSlug();
        if (slug != null && !slug.trim().isEmpty()) {
            slug = SlugUtils.toSlug(slug);
            if (productRepository.existsBySlug(slug)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã slug '" + slug + "' đã tồn tại.");
            }
        } else {
            String baseSlug = SlugUtils.toSlug(request.getName());
            slug = baseSlug;
            int counter = 1;
            while (productRepository.existsBySlug(slug)) {
                slug = baseSlug + "-" + counter++;
            }
        }

        String rawStatus = request.getStatus();
        if (rawStatus != null && !rawStatus.isBlank() && !ProductStatus.isValid(rawStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trạng thái sản phẩm không hợp lệ: " + rawStatus);
        }
        String productStatus = ProductStatus.normalize(rawStatus);

        Product product = Product.builder()
                .name(request.getName().trim())
                .slug(slug)
                .category(category)
                .brand(brand)
                .productType(request.getProductType())
                .description(trimToNull(request.getDescription()))
                .status(productStatus)
                .variants(new ArrayList<>())
                .build();

        Product savedProduct = productRepository.save(product);

        if (request.getVariants() != null && !request.getVariants().isEmpty()) {
            // Kiểm tra trùng SKU hoặc Barcode ngay trong danh sách request
            Set<String> skusInReq = new HashSet<>();
            Set<String> barcodesInReq = new HashSet<>();
            for (VariantCreateRequest vReq : request.getVariants()) {
                if (vReq.getSku() != null) {
                    String cleanSku = vReq.getSku().trim().toUpperCase(Locale.ROOT);
                    if (!skusInReq.add(cleanSku)) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã SKU trùng lặp trong yêu cầu: " + vReq.getSku());
                    }
                }
                if (vReq.getBarcode() != null) {
                    String cleanBarcode = vReq.getBarcode().trim();
                    if (!barcodesInReq.add(cleanBarcode)) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã Barcode trùng lặp trong yêu cầu: " + vReq.getBarcode());
                    }
                }
            }

            List<InventoryLocation> activeLocations = locationRepository.findAllByActiveTrueOrderByCodeAsc();
            for (VariantCreateRequest vReq : request.getVariants()) {
                createVariantInternal(savedProduct, vReq, activeLocations);
            }
        }

        return mapToDTO(savedProduct);
    }

    @Override
    @Transactional
    public ProductResponseDTO updateProduct(Long id, ProductUpdateRequest request) {
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID sản phẩm không được để trống.");
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dữ liệu cập nhật sản phẩm không được để trống.");
        }
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tên sản phẩm không được để trống.");
        }
        if (request.getCategoryId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID danh mục không được để trống.");
        }
        if (request.getBrandId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID thương hiệu không được để trống.");
        }
        if (request.getProductType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loại sản phẩm không được để trống.");
        }

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy sản phẩm với ID: " + id));

        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy danh mục với ID: " + request.getCategoryId()));
        Brand brand = brandRepository.findById(request.getBrandId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thương hiệu với ID: " + request.getBrandId()));

        String slug = request.getSlug();
        if (slug != null && !slug.trim().isEmpty()) {
            slug = SlugUtils.toSlug(slug);
            if (productRepository.existsBySlugAndIdNot(slug, id)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã slug '" + slug + "' đã tồn tại ở sản phẩm khác.");
            }
            product.setSlug(slug);
        }

        product.setName(request.getName().trim());
        product.setCategory(category);
        product.setBrand(brand);
        product.setProductType(request.getProductType());
        product.setDescription(trimToNull(request.getDescription()));
        if (request.getStatus() != null && !request.getStatus().trim().isEmpty()) {
            if (!ProductStatus.isValid(request.getStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trạng thái sản phẩm không hợp lệ: " + request.getStatus());
            }
            product.setStatus(ProductStatus.normalize(request.getStatus()));
        }
        product.setUpdatedAt(LocalDateTime.now());

        Product saved = productRepository.save(product);
        return mapToDTO(saved);
    }

    @Override
    @Transactional
    public ProductResponseDTO updateProductStatus(Long id, String status) {
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID sản phẩm không được để trống.");
        }
        if (status == null || !ProductStatus.isValid(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trạng thái sản phẩm không hợp lệ: " + status);
        }
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy sản phẩm với ID: " + id));

        String normalized = ProductStatus.normalize(status);
        product.setStatus(normalized);
        product.setUpdatedAt(LocalDateTime.now());

        Product saved = productRepository.save(product);
        return mapToDTO(saved);
    }

    @Override
    @Transactional
    public void deleteProduct(Long id) {
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID sản phẩm không được để trống.");
        }
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy sản phẩm với ID: " + id));

        boolean hasStock = product.getVariants() != null && product.getVariants().stream().anyMatch(v ->
                (v.getStockQuantity() != null && v.getStockQuantity() > 0)
                        || (v.getReservedQuantity() != null && v.getReservedQuantity() > 0));

        if (hasStock) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Không thể xóa sản phẩm đang còn tồn kho khả dụng; hãy chuyển trạng thái sang Ẩn hoặc Ngừng kinh doanh.");
        }

        if (product.getVariants() != null && !product.getVariants().isEmpty()) {
            // Đã có biến thể (có thể đã có đơn hàng trong quá khứ): chuyển DISCONTINUED để bảo toàn snapshot lịch sử đơn
            product.setStatus(ProductStatus.DISCONTINUED.name());
            product.getVariants().forEach(v -> {
                v.setStatus(ProductStatus.DISCONTINUED.name());
                v.setUpdatedAt(LocalDateTime.now());
            });
            product.setUpdatedAt(LocalDateTime.now());
            productRepository.save(product);
        } else {
            productRepository.delete(product);
        }
    }

    @Override
    @Transactional
    public ProductVariantDTO createVariant(Long productId, VariantCreateRequest request) {
        if (productId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID sản phẩm không được để trống.");
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dữ liệu tạo biến thể không được để trống.");
        }
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy sản phẩm với ID: " + productId));

        List<InventoryLocation> activeLocations = locationRepository.findAllByActiveTrueOrderByCodeAsc();
        ProductVariant variant = createVariantInternal(product, request, activeLocations);
        return mapVariantToDTO(variant);
    }

    @Override
    @Transactional
    public ProductVariantDTO updateVariant(Long variantId, VariantUpdateRequest request) {
        if (variantId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID biến thể không được để trống.");
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dữ liệu cập nhật biến thể không được để trống.");
        }
        ProductVariant variant = productVariantRepository.findById(variantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy biến thể với ID: " + variantId));

        if (request.getSku() == null || request.getSku().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã SKU không được để trống.");
        }
        if (request.getBarcode() == null || request.getBarcode().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã Barcode không được để trống.");
        }
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tên biến thể không được để trống.");
        }

        String sku = request.getSku().trim();
        String barcode = request.getBarcode().trim();

        if (productVariantRepository.existsBySkuAndIdNot(sku, variantId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã SKU '" + sku + "' đã tồn tại ở biến thể khác.");
        }
        if (productVariantRepository.existsByBarcodeAndIdNot(barcode, variantId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã Barcode '" + barcode + "' đã tồn tại ở biến thể khác.");
        }

        if (request.getPrice() != null && request.getPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Giá bán không được âm.");
        }
        if (request.getCostPrice() != null && request.getCostPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Giá vốn không được âm.");
        }

        UrlSafetyValidator.validateImageUrl(request.getImageUrl());

        variant.setSku(sku);
        variant.setBarcode(barcode);
        variant.setName(request.getName().trim());
        variant.setColor(trimToNull(request.getColor()));
        variant.setFrameShape(trimToNull(request.getFrameShape()));
        variant.setMaterial(trimToNull(request.getMaterial()));
        variant.setLensIndex(trimToNull(request.getLensIndex()));
        if (request.getPrice() != null) {
            variant.setPrice(request.getPrice());
        }
        if (request.getCostPrice() != null) {
            variant.setCostPrice(request.getCostPrice());
        }
        variant.setImageUrl(trimToNull(request.getImageUrl()));
        if (request.getStatus() != null && !request.getStatus().trim().isEmpty()) {
            if (!ProductStatus.isValid(request.getStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trạng thái biến thể không hợp lệ: " + request.getStatus());
            }
            variant.setStatus(ProductStatus.normalize(request.getStatus()));
        }
        variant.setUpdatedAt(LocalDateTime.now());

        // CHÚ Ý BẢO TOÀN TỒN KHO: stockQuantity, reservedQuantity, nonSellableQuantity TUYỆT ĐỐI KHÔNG BỊ SỬA QUA CRUD
        ProductVariant saved = productVariantRepository.save(variant);
        return mapVariantToDTO(saved);
    }

    @Override
    @Transactional
    public ProductVariantDTO updateVariantStatus(Long variantId, String status) {
        if (variantId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID biến thể không được để trống.");
        }
        if (status == null || !ProductStatus.isValid(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trạng thái biến thể không hợp lệ: " + status);
        }
        ProductVariant variant = productVariantRepository.findById(variantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy biến thể với ID: " + variantId));

        variant.setStatus(ProductStatus.normalize(status));
        variant.setUpdatedAt(LocalDateTime.now());
        ProductVariant saved = productVariantRepository.save(variant);
        return mapVariantToDTO(saved);
    }

    @Override
    @Transactional
    public ProductVariantDTO updateVariantPrice(Long variantId, VariantPriceRequest request) {
        if (variantId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID biến thể không được để trống.");
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dữ liệu cập nhật giá không được để trống.");
        }
        ProductVariant variant = productVariantRepository.findById(variantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy biến thể với ID: " + variantId));

        if (request.getPrice() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Giá bán không được để trống.");
        }
        if (request.getPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Giá bán không được âm.");
        }
        variant.setPrice(request.getPrice());

        if (request.getCostPrice() != null) {
            if (request.getCostPrice().compareTo(BigDecimal.ZERO) < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Giá vốn không được âm.");
            }
            variant.setCostPrice(request.getCostPrice());
        }

        variant.setUpdatedAt(LocalDateTime.now());
        ProductVariant saved = productVariantRepository.save(variant);
        return mapVariantToDTO(saved);
    }

    @Override
    @Transactional
    public void deleteVariant(Long variantId) {
        if (variantId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID biến thể không được để trống.");
        }
        ProductVariant variant = productVariantRepository.findById(variantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy biến thể với ID: " + variantId));

        if ((variant.getStockQuantity() != null && variant.getStockQuantity() > 0)
                || (variant.getReservedQuantity() != null && variant.getReservedQuantity() > 0)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Không thể xóa biến thể đang có tồn kho; hãy chuyển trạng thái sang Ẩn hoặc Ngừng kinh doanh.");
        }

        // Chuyển sang DISCONTINUED để không làm hỏng lịch sử đơn hàng snapshot
        variant.setStatus(ProductStatus.DISCONTINUED.name());
        variant.setUpdatedAt(LocalDateTime.now());
        productVariantRepository.save(variant);
    }

    private ProductVariant createVariantInternal(Product product, VariantCreateRequest request, List<InventoryLocation> locations) {
        if (request.getSku() == null || request.getSku().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã SKU không được để trống.");
        }
        if (request.getBarcode() == null || request.getBarcode().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã Barcode không được để trống.");
        }
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tên biến thể không được để trống.");
        }

        String sku = request.getSku().trim();
        String barcode = request.getBarcode().trim();

        if (productVariantRepository.existsBySku(sku)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã SKU '" + sku + "' đã tồn tại.");
        }
        if (productVariantRepository.existsByBarcode(barcode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã Barcode '" + barcode + "' đã tồn tại.");
        }

        if (request.getPrice() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Giá bán không được để trống.");
        }
        if (request.getPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Giá bán không được âm.");
        }
        BigDecimal costPrice = request.getCostPrice() != null ? request.getCostPrice() : BigDecimal.ZERO;
        if (costPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Giá vốn không được âm.");
        }

        UrlSafetyValidator.validateImageUrl(request.getImageUrl());

        String rawStatus = request.getStatus();
        if (rawStatus != null && !rawStatus.isBlank() && !ProductStatus.isValid(rawStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trạng thái biến thể không hợp lệ: " + rawStatus);
        }
        String variantStatus = ProductStatus.normalize(rawStatus);

        ProductVariant variant = ProductVariant.builder()
                .product(product)
                .sku(sku)
                .barcode(barcode)
                .name(request.getName().trim())
                .color(trimToNull(request.getColor()))
                .frameShape(trimToNull(request.getFrameShape()))
                .material(trimToNull(request.getMaterial()))
                .lensIndex(trimToNull(request.getLensIndex()))
                .price(request.getPrice())
                .costPrice(costPrice)
                .stockQuantity(0) // Khởi tạo tồn kho ban đầu luôn là 0, chỉ tăng qua chứng từ kho
                .reservedQuantity(0)
                .nonSellableQuantity(0)
                .imageUrl(trimToNull(request.getImageUrl()))
                .status(variantStatus)
                .build();

        ProductVariant saved = productVariantRepository.save(variant);

        // Khởi tạo dòng số dư tồn kho ban đầu với số lượng 0 cho các địa điểm
        if (locations != null && saved.getId() != null) {
            for (InventoryLocation location : locations) {
                if (!balanceRepository.existsByVariant_IdAndLocation_Id(saved.getId(), location.getId())) {
                    balanceRepository.save(InventoryBalance.builder()
                            .variant(saved)
                            .location(location)
                            .quantity(0)
                            .build());
                }
            }
        }

        if (product.getVariants() != null) {
            product.getVariants().add(saved);
        }
        return saved;
    }

    private ProductResponseDTO mapToDTO(Product product) {
        List<ProductVariantDTO> variantDTOs = product.getVariants() == null
                ? Collections.emptyList()
                : product.getVariants().stream()
                .map(this::mapVariantToDTO)
                .collect(Collectors.toList());

        return ProductResponseDTO.builder()
                .id(product.getId())
                .name(product.getName())
                .slug(product.getSlug())
                .categoryId(product.getCategory() != null ? product.getCategory().getId() : null)
                .categoryName(product.getCategory() != null ? product.getCategory().getName() : null)
                .brandId(product.getBrand() != null ? product.getBrand().getId() : null)
                .brandName(product.getBrand() != null ? product.getBrand().getName() : null)
                .productType(product.getProductType())
                .description(product.getDescription())
                .status(product.getStatus())
                .variants(variantDTOs)
                .build();
    }

    private ProductVariantDTO mapVariantToDTO(ProductVariant v) {
        return ProductVariantDTO.builder()
                .id(v.getId())
                .sku(v.getSku())
                .barcode(v.getBarcode())
                .name(v.getName())
                .color(v.getColor())
                .frameShape(v.getFrameShape())
                .material(v.getMaterial())
                .lensIndex(v.getLensIndex())
                .price(v.getPrice())
                .costPrice(v.getCostPrice())
                .stockQuantity(v.getStockQuantity())
                .availableQuantity(v.getAvailableQuantity())
                .imageUrl(v.getImageUrl())
                .status(v.getStatus())
                .build();
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
