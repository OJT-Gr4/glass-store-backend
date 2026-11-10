package com.glassstore.catalog.service;

import com.glassstore.catalog.dto.ProductManagementDtos.*;
import com.glassstore.catalog.dto.ProductResponseDTO;
import com.glassstore.catalog.dto.ProductVariantDTO;
import com.glassstore.catalog.entity.*;
import com.glassstore.catalog.repository.*;
import com.glassstore.catalog.service.impl.ProductServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests thuần túy cho Nghiệp vụ Admin Quản trị Sản phẩm, Biến thể, Giá và Tồn kho.
 * Sử dụng In-Memory Repositories siêu tốc, độc lập môi trường sandbox và hoàn toàn không phụ thuộc JVM attach pipe.
 */
class AdminProductManagementTest {

    private final Map<Long, Product> productDb = new HashMap<>();
    private final Map<Long, ProductVariant> variantDb = new HashMap<>();
    private final Map<Long, Category> categoryDb = new HashMap<>();
    private final Map<Long, Brand> brandDb = new HashMap<>();
    private final Map<Long, InventoryLocation> locationDb = new HashMap<>();
    private final Map<String, InventoryBalance> balanceDb = new HashMap<>();

    private final AtomicLong productIdGen = new AtomicLong(10);
    private final AtomicLong variantIdGen = new AtomicLong(100);

    private ProductRepository productRepository;
    private ProductVariantRepository productVariantRepository;
    private CategoryRepository categoryRepository;
    private BrandRepository brandRepository;
    private InventoryLocationRepository locationRepository;
    private InventoryBalanceRepository balanceRepository;

    private ProductServiceImpl productService;

    private Category testCategory;
    private Brand testBrand;
    private Product testProduct;
    private ProductVariant testVariant;

    @BeforeEach
    void setUp() {
        productDb.clear();
        variantDb.clear();
        categoryDb.clear();
        brandDb.clear();
        locationDb.clear();
        balanceDb.clear();

        // 1. Category Repo Proxy
        categoryRepository = (CategoryRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{CategoryRepository.class},
                (proxy, method, args) -> {
                    if ("findById".equals(method.getName())) {
                        return Optional.ofNullable(categoryDb.get((Long) args[0]));
                    }
                    if ("save".equals(method.getName())) {
                        Category c = (Category) args[0];
                        if (c.getId() == null) c.setId(1L);
                        categoryDb.put(c.getId(), c);
                        return c;
                    }
                    return null;
                });

        // 2. Brand Repo Proxy
        brandRepository = (BrandRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{BrandRepository.class},
                (proxy, method, args) -> {
                    if ("findById".equals(method.getName())) {
                        return Optional.ofNullable(brandDb.get((Long) args[0]));
                    }
                    if ("save".equals(method.getName())) {
                        Brand b = (Brand) args[0];
                        if (b.getId() == null) b.setId(2L);
                        brandDb.put(b.getId(), b);
                        return b;
                    }
                    return null;
                });

        // 3. Location Repo Proxy
        locationRepository = (InventoryLocationRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{InventoryLocationRepository.class},
                (proxy, method, args) -> {
                    if ("findAllByActiveTrueOrderByCodeAsc".equals(method.getName())) {
                        return new ArrayList<>(locationDb.values());
                    }
                    return Collections.emptyList();
                });

        // 4. Balance Repo Proxy
        balanceRepository = (InventoryBalanceRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{InventoryBalanceRepository.class},
                (proxy, method, args) -> {
                    if ("existsByVariant_IdAndLocation_Id".equals(method.getName())) {
                        String key = args[0] + "_" + args[1];
                        return balanceDb.containsKey(key);
                    }
                    if ("save".equals(method.getName())) {
                        InventoryBalance b = (InventoryBalance) args[0];
                        String key = b.getVariant().getId() + "_" + b.getLocation().getId();
                        balanceDb.put(key, b);
                        return b;
                    }
                    return null;
                });

        // 5. Product Repo Proxy
        productRepository = (ProductRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ProductRepository.class},
                (proxy, method, args) -> {
                    String m = method.getName();
                    if ("findById".equals(m)) {
                        return Optional.ofNullable(productDb.get((Long) args[0]));
                    }
                    if ("existsBySlug".equals(m)) {
                        String slug = (String) args[0];
                        return productDb.values().stream().anyMatch(p -> slug.equalsIgnoreCase(p.getSlug()));
                    }
                    if ("existsBySlugAndIdNot".equals(m)) {
                        String slug = (String) args[0];
                        Long id = (Long) args[1];
                        return productDb.values().stream().anyMatch(p -> slug.equalsIgnoreCase(p.getSlug()) && !Objects.equals(p.getId(), id));
                    }
                    if ("save".equals(m)) {
                        Product p = (Product) args[0];
                        if (p.getId() == null) p.setId(productIdGen.incrementAndGet());
                        productDb.put(p.getId(), p);
                        return p;
                    }
                    if ("delete".equals(m)) {
                        Product p = (Product) args[0];
                        productDb.remove(p.getId());
                        return null;
                    }
                    if ("findAll".equals(m)) {
                        return new ArrayList<>(productDb.values());
                    }
                    return null;
                });

        // 6. Variant Repo Proxy
        productVariantRepository = (ProductVariantRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ProductVariantRepository.class},
                (proxy, method, args) -> {
                    String m = method.getName();
                    if ("findById".equals(m)) {
                        return Optional.ofNullable(variantDb.get((Long) args[0]));
                    }
                    if ("existsBySku".equals(m)) {
                        String sku = (String) args[0];
                        return variantDb.values().stream().anyMatch(v -> sku.equalsIgnoreCase(v.getSku()));
                    }
                    if ("existsByBarcode".equals(m)) {
                        String barcode = (String) args[0];
                        return variantDb.values().stream().anyMatch(v -> barcode.equalsIgnoreCase(v.getBarcode()));
                    }
                    if ("existsBySkuAndIdNot".equals(m)) {
                        String sku = (String) args[0];
                        Long id = (Long) args[1];
                        return variantDb.values().stream().anyMatch(v -> sku.equalsIgnoreCase(v.getSku()) && !Objects.equals(v.getId(), id));
                    }
                    if ("existsByBarcodeAndIdNot".equals(m)) {
                        String barcode = (String) args[0];
                        Long id = (Long) args[1];
                        return variantDb.values().stream().anyMatch(v -> barcode.equalsIgnoreCase(v.getBarcode()) && !Objects.equals(v.getId(), id));
                    }
                    if ("save".equals(m)) {
                        ProductVariant v = (ProductVariant) args[0];
                        if (v.getId() == null) v.setId(variantIdGen.incrementAndGet());
                        variantDb.put(v.getId(), v);
                        return v;
                    }
                    return null;
                });

        productService = new ProductServiceImpl(
                productRepository,
                productVariantRepository,
                categoryRepository,
                brandRepository,
                locationRepository,
                balanceRepository
        );

        // Nạp dữ liệu nền
        testCategory = Category.builder().id(1L).name("Gọng Kính").slug("gong-kinh").build();
        categoryDb.put(1L, testCategory);

        testBrand = Brand.builder().id(2L).name("Bolon").country("France").build();
        brandDb.put(2L, testBrand);

        InventoryLocation loc1 = InventoryLocation.builder().id(101L).code("CN01").name("Chi nhánh 01").active(true).build();
        locationDb.put(101L, loc1);

        testProduct = Product.builder()
                .id(10L)
                .name("Gọng Kính Bolon BJ3120")
                .slug("gong-kinh-bolon-bj3120")
                .category(testCategory)
                .brand(testBrand)
                .productType(ProductType.FRAME)
                .status("ACTIVE")
                .variants(new ArrayList<>())
                .build();
        productDb.put(10L, testProduct);

        testVariant = ProductVariant.builder()
                .id(100L)
                .product(testProduct)
                .sku("BL-BJ3120-C10")
                .barcode("8936012340011")
                .name("Bolon BJ3120 Rose Gold")
                .price(new BigDecimal("3280000.00"))
                .costPrice(new BigDecimal("1850000.00"))
                .stockQuantity(15)
                .reservedQuantity(2)
                .status("ACTIVE")
                .build();
        variantDb.put(100L, testVariant);
        testProduct.getVariants().add(testVariant);
    }

    @Test
    @DisplayName("Admin tạo sản phẩm mới thành công, tồn kho khởi tạo bằng 0 (không sửa trực tiếp số dư)")
    void createProduct_Success_ZeroStock() {
        VariantCreateRequest variantReq = VariantCreateRequest.builder()
                .sku("NEW-SKU-01")
                .barcode("893000999")
                .name("Kính Mới Bản Vàng")
                .price(new BigDecimal("2500000.00"))
                .costPrice(new BigDecimal("1200000.00"))
                .color("Gold")
                .imageUrl("https://images.unsplash.com/photo-1591076482161")
                .status("ACTIVE")
                .build();

        ProductCreateRequest request = ProductCreateRequest.builder()
                .name("Gọng Kính Mới")
                .slug("gong-kinh-moi")
                .categoryId(1L)
                .brandId(2L)
                .productType(ProductType.FRAME)
                .description("Mô tả kính mới")
                .status("ACTIVE")
                .variants(List.of(variantReq))
                .build();

        ProductResponseDTO result = productService.createProduct(request);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("Gọng Kính Mới");
        assertThat(result.getVariants()).hasSize(1);

        ProductVariantDTO createdVariant = result.getVariants().get(0);
        assertThat(createdVariant.getSku()).isEqualTo("NEW-SKU-01");
        assertThat(createdVariant.getStockQuantity()).isZero(); // Tồn kho ban đầu luôn là 0
        assertThat(createdVariant.getPrice()).isEqualByComparingTo("2500000.00");
        assertThat(createdVariant.getCostPrice()).isEqualByComparingTo("1200000.00");

        ProductVariant inDb = variantDb.get(createdVariant.getId());
        assertThat(inDb).isNotNull();
        assertThat(inDb.getStockQuantity()).isZero();
    }

    @Test
    @DisplayName("Admin tạo sản phẩm với slug đã tồn tại -> Bị chặn với HTTP 409 Conflict")
    void createProduct_DuplicateSlug_ThrowsConflict() {
        ProductCreateRequest request = ProductCreateRequest.builder()
                .name("Gọng Kính Trùng")
                .slug("gong-kinh-bolon-bj3120") // Đã có của testProduct
                .categoryId(1L)
                .brandId(2L)
                .productType(ProductType.FRAME)
                .build();

        assertThatThrownBy(() -> productService.createProduct(request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(rse.getReason()).contains("đã tồn tại");
                });
    }

    @Test
    @DisplayName("Admin tạo biến thể với SKU trùng lặp -> Bị chặn với HTTP 409 Conflict")
    void createVariant_DuplicateSku_ThrowsConflict() {
        VariantCreateRequest req = VariantCreateRequest.builder()
                .sku("BL-BJ3120-C10") // Đã tồn tại trong DB
                .barcode("893999999")
                .name("Trùng SKU")
                .price(BigDecimal.valueOf(1000000))
                .costPrice(BigDecimal.valueOf(500000))
                .build();

        assertThatThrownBy(() -> productService.createVariant(10L, req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(rse.getReason()).contains("SKU 'BL-BJ3120-C10' đã tồn tại");
                });
    }

    @Test
    @DisplayName("Admin tạo biến thể với Barcode trùng lặp -> Bị chặn với HTTP 409 Conflict")
    void createVariant_DuplicateBarcode_ThrowsConflict() {
        VariantCreateRequest req = VariantCreateRequest.builder()
                .sku("NEW-UNIQUE-SKU")
                .barcode("8936012340011") // Đã tồn tại trong DB
                .name("Trùng Barcode")
                .price(BigDecimal.valueOf(1000000))
                .costPrice(BigDecimal.valueOf(500000))
                .build();

        assertThatThrownBy(() -> productService.createVariant(10L, req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(rse.getReason()).contains("Barcode '8936012340011' đã tồn tại");
                });
    }

    @Test
    @DisplayName("Admin nhập giá bán âm (< 0) -> Bị chặn với HTTP 400 Bad Request")
    void createVariant_NegativePrice_ThrowsBadRequest() {
        VariantCreateRequest req = VariantCreateRequest.builder()
                .sku("VALID-SKU")
                .barcode("VALID-BARCODE")
                .name("Giá âm")
                .price(new BigDecimal("-10000.00")) // Giá âm
                .costPrice(BigDecimal.valueOf(500000))
                .build();

        assertThatThrownBy(() -> productService.createVariant(10L, req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(rse.getReason()).contains("Giá bán không được âm");
                });
    }

    @Test
    @DisplayName("Admin nhập giá vốn âm (< 0) -> Bị chặn với HTTP 400 Bad Request")
    void createVariant_NegativeCostPrice_ThrowsBadRequest() {
        VariantCreateRequest req = VariantCreateRequest.builder()
                .sku("VALID-SKU")
                .barcode("VALID-BARCODE")
                .name("Giá vốn âm")
                .price(new BigDecimal("1000000.00"))
                .costPrice(new BigDecimal("-50000.00")) // Giá vốn âm
                .build();

        assertThatThrownBy(() -> productService.createVariant(10L, req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(rse.getReason()).contains("Giá vốn không được âm");
                });
    }

    @Test
    @DisplayName("Admin nhập URL ảnh chứa payload nguy hiểm (javascript:) -> Bị chặn HTTP 400")
    void createVariant_UnsafeImageUrl_ThrowsBadRequest() {
        VariantCreateRequest req = VariantCreateRequest.builder()
                .sku("SAFE-SKU")
                .barcode("SAFE-BAR")
                .name("XSS Payload")
                .price(BigDecimal.valueOf(1000000))
                .costPrice(BigDecimal.valueOf(500000))
                .imageUrl("javascript:alert(document.cookie)") // URL độc hại
                .build();

        assertThatThrownBy(() -> productService.createVariant(10L, req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(rse.getReason()).contains("URL ảnh chứa giao thức hoặc mã lệnh không an toàn");
                });
    }

    @Test
    @DisplayName("Admin cập nhật biến thể: số dư tồn kho (stockQuantity) TUYỆT ĐỐI không bị thay đổi qua CRUD")
    void updateVariant_StockQuantityRemainsUntouched() {
        // Ban đầu tồn kho là 15, reserved là 2
        assertThat(testVariant.getStockQuantity()).isEqualTo(15);
        assertThat(testVariant.getReservedQuantity()).isEqualTo(2);

        VariantUpdateRequest updateReq = VariantUpdateRequest.builder()
                .sku("BL-BJ3120-C10")
                .barcode("8936012340011")
                .name("Tên mới sau sửa")
                .price(new BigDecimal("3500000.00")) // Thay đổi giá bán
                .costPrice(new BigDecimal("1900000.00"))
                .color("Gold Vàng")
                .status("ACTIVE")
                .build();

        ProductVariantDTO updated = productService.updateVariant(100L, updateReq);

        assertThat(updated.getName()).isEqualTo("Tên mới sau sửa");
        assertThat(updated.getPrice()).isEqualByComparingTo("3500000.00");
        // Kiểm tra tồn kho vẫn nguyên vẹn 15
        assertThat(testVariant.getStockQuantity()).isEqualTo(15);
        assertThat(testVariant.getReservedQuantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("Admin cập nhật trạng thái HIDDEN hoặc DISCONTINUED thành công")
    void updateProductStatus_Success() {
        ProductResponseDTO res = productService.updateProductStatus(10L, "HIDDEN");

        assertThat(res.getStatus()).isEqualTo("HIDDEN");
        assertThat(testProduct.getStatus()).isEqualTo("HIDDEN");
    }

    @Test
    @DisplayName("Admin xóa sản phẩm còn tồn kho (>0) -> Bị chặn với 409 Conflict để tránh mất kiểm soát hàng hóa")
    void deleteProduct_WithStock_ThrowsConflict() {
        // testProduct có biến thể testVariant đang có stockQuantity = 15
        assertThatThrownBy(() -> productService.deleteProduct(10L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(rse.getReason()).contains("Không thể xóa sản phẩm đang còn tồn kho");
                });
    }

    @Test
    @DisplayName("Xóa sản phẩm đã có biến thể lịch sử nhưng hết tồn -> Chuyển sang DISCONTINUED bảo toàn đơn cũ (AC2)")
    void deleteProduct_ZeroStock_SetsDiscontinued() {
        testVariant.setStockQuantity(0);
        testVariant.setReservedQuantity(0);

        productService.deleteProduct(10L);

        // Không xóa cứng bảng mà chuyển sang DISCONTINUED
        assertThat(testProduct.getStatus()).isEqualTo("DISCONTINUED");
        assertThat(testVariant.getStatus()).isEqualTo("DISCONTINUED");
        assertThat(productDb).containsKey(10L);
    }

    @Test
    @DisplayName("Cập nhật nhanh giá qua server: hợp lệ và cập nhật đúng (AC3)")
    void updateVariantPrice_Success() {
        VariantPriceRequest priceReq = VariantPriceRequest.builder()
                .price(new BigDecimal("4200000.00"))
                .costPrice(new BigDecimal("2100000.00"))
                .build();

        ProductVariantDTO updated = productService.updateVariantPrice(100L, priceReq);

        assertThat(updated.getPrice()).isEqualByComparingTo("4200000.00");
        assertThat(updated.getCostPrice()).isEqualByComparingTo("2100000.00");
        assertThat(testVariant.getPrice()).isEqualByComparingTo("4200000.00");
    }

    @Test
    @DisplayName("Bộ lọc quản trị: tìm kiếm theo từ khóa tên hoặc SKU và trạng thái")
    void getAdminProducts_FilterByKeywordAndStatus() {
        List<ProductResponseDTO> list = productService.getAdminProducts(null, null, null, "ACTIVE", "BJ3120");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getName()).contains("Bolon");

        List<ProductResponseDTO> emptyList = productService.getAdminProducts(null, null, null, "ACTIVE", "NonExistingSKU");
        assertThat(emptyList).isEmpty();
    }
}
