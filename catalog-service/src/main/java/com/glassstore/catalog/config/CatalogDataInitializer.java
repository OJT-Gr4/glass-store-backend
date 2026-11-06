package com.glassstore.catalog.config;

import com.glassstore.catalog.entity.*;
import com.glassstore.catalog.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "glassstore.demo.seed.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class CatalogDataInitializer implements CommandLineRunner {

    private final BrandRepository brandRepository;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;

    @Override
    public void run(String... args) {
        if (productRepository.count() > 0) {
            log.info("Catalog database đã có dữ liệu sản phẩm, bỏ qua nạp seed.");
            return;
        }

        log.info("Bắt đầu khởi tạo đúng 15 sản phẩm tinh gọn chuẩn ngành kính mắt...");

        // 1. Brands (5 thương hiệu nổi tiếng)
        Brand bolon = brandRepository.save(Brand.builder().name("Bolon").country("France").description("Thương hiệu gọng thời trang cao cấp từ Pháp").build());
        Brand rayban = brandRepository.save(Brand.builder().name("Ray-Ban").country("Italy").description("Biểu tượng kính râm và gọng kính phi công").build());
        Brand charmant = brandRepository.save(Brand.builder().name("Charmant").country("Japan").description("Chuyên gia gọng kính Titanium Nhật Bản").build());
        Brand gentleMonster = brandRepository.save(Brand.builder().name("Gentle Monster").country("Korea").description("Thương hiệu kính mắt phá cách thời thượng Hàn Quốc").build());
        Brand oakley = brandRepository.save(Brand.builder().name("Oakley").country("USA").description("Thương hiệu kính thể thao đỉnh cao của Mỹ").build());
        Brand essilor = brandRepository.save(Brand.builder().name("Essilor").country("France").description("Tập đoàn tròng kính số 1 thế giới").build());
        Brand zeiss = brandRepository.save(Brand.builder().name("Carl Zeiss").country("Germany").description("Quang học đỉnh cao tiêu chuẩn Đức").build());
        Brand chemi = brandRepository.save(Brand.builder().name("Chemi Lens").country("Korea").description("Tròng kính chất lượng cao Hàn Quốc").build());

        // 2. Categories (4 danh mục chính)
        Category catFrames = categoryRepository.save(Category.builder().name("Gọng Kính").slug("gong-kinh").description("Gọng kính cận, viễn, loạn thị").build());
        Category catLenses = categoryRepository.save(Category.builder().name("Tròng Kính Khúc Xạ").slug("trong-kinh").description("Tròng đơn tròng, đa tròng kỹ thuật số").build());
        Category catSun = categoryRepository.save(Category.builder().name("Kính Mát").slug("kinh-mat").description("Kính râm chống tia UV & phân cực").build());
        Category catAcc = categoryRepository.save(Category.builder().name("Phụ Kiện").slug("phu-kien").description("Khăn lau, nước rửa, ve mũi, hộp đựng").build());

        // 3. Đúng 15 Sản phẩm & Biến thể
        // --- 5 GỌNG KÍNH (1 -> 5) ---
        // 1. Bolon Graceful BJ3120
        Product p1 = productRepository.save(Product.builder().name("Gọng Kính Bolon Graceful BJ3120").slug("gong-kinh-bolon-graceful-bj3120").category(catFrames).brand(bolon).productType(ProductType.FRAME).description("Chất liệu B-Titanium siêu nhẹ 7.8g, thanh lịch hình đa giác.").status("ACTIVE").build());
        productVariantRepository.save(ProductVariant.builder().product(p1).sku("BL-BJ3120-C10").barcode("8936012340011").name("Bolon BJ3120 - Rose Gold").color("Rose Gold C10").frameShape("Geometrical").material("B-Titanium").price(new BigDecimal("3280000")).costPrice(new BigDecimal("1850000")).stockQuantity(25).imageUrl("https://images.unsplash.com/photo-1591076482161-42ce6da69f67?auto=format&fit=crop&w=600&q=80").build());

        // 2. Ray-Ban Clubmaster RX5154
        Product p2 = productRepository.save(Product.builder().name("Gọng Kính Ray-Ban Clubmaster RX5154").slug("gong-kinh-ray-ban-clubmaster-rx5154").category(catFrames).brand(rayban).productType(ProductType.FRAME).description("Thiết kế browline cổ điển sang trọng, viền kim loại mạ vàng kết hợp acetate đen bóng.").status("ACTIVE").build());
        productVariantRepository.save(ProductVariant.builder().product(p2).sku("RB-5154-2000").barcode("8936012340028").name("Ray-Ban Clubmaster Classic").color("Đen Vàng (Black Gold)").frameShape("Browline").material("Acetate & Metal").price(new BigDecimal("3850000")).costPrice(new BigDecimal("2200000")).stockQuantity(18).imageUrl("https://images.unsplash.com/photo-1511499767150-a48a237f0083?auto=format&fit=crop&w=600&q=80").build());

        // 3. Charmant LineArt XL1467
        Product p3 = productRepository.save(Product.builder().name("Gọng Kính Charmant LineArt XL1467").slug("gong-kinh-charmant-lineart-xl1467").category(catFrames).brand(charmant).productType(ProductType.FRAME).description("Đỉnh cao chế tác gọng kính Nhật Bản từ Titanium Excellence siêu đàn hồi không biến dạng.").status("ACTIVE").build());
        productVariantRepository.save(ProductVariant.builder().product(p3).sku("CM-XL1467-BK").barcode("8936012340035").name("Charmant LineArt XL1467").color("Đen Xám Bạc").frameShape("Oval").material("Titanium Excellence").price(new BigDecimal("5600000")).costPrice(new BigDecimal("3300000")).stockQuantity(10).imageUrl("https://images.unsplash.com/photo-1574258495973-f010dfbb5371?auto=format&fit=crop&w=600&q=80").build());

        // 4. Gentle Monster South Side
        Product p4 = productRepository.save(Product.builder().name("Gọng Kính Gentle Monster South Side").slug("gong-kinh-gentle-monster-south-side").category(catFrames).brand(gentleMonster).productType(ProductType.FRAME).description("Kiểu dáng vuông bo tròn cá tính, chất liệu Acetate đen tuyền bóng bẩy thời thượng.").status("ACTIVE").build());
        productVariantRepository.save(ProductVariant.builder().product(p4).sku("GM-SOUTHSIDE-01").barcode("8936012340042").name("Gentle Monster South Side 01").color("Đen Tuyền (Black)").frameShape("Square").material("Premium Acetate").price(new BigDecimal("4200000")).costPrice(new BigDecimal("2500000")).stockQuantity(15).imageUrl("https://images.unsplash.com/photo-1572635196237-14b3f281503f?auto=format&fit=crop&w=600&q=80").build());

        // 5. Oakley Pitchman R Carbon
        Product p5 = productRepository.save(Product.builder().name("Gọng Kính Oakley Pitchman R Carbon").slug("gong-kinh-oakley-pitchman-r-carbon").category(catFrames).brand(oakley).productType(ProductType.FRAME).description("Càng kính bằng sợi carbon nguyên khối siêu bền, khớp nối không ốc Hollowpoint độc quyền.").status("ACTIVE").build());
        productVariantRepository.save(ProductVariant.builder().product(p5).sku("OK-OX8105-01").barcode("8936012340059").name("Oakley Pitchman R Carbon").color("Xám Khói Mờ").frameShape("Round").material("O Matter & Carbon").price(new BigDecimal("3450000")).costPrice(new BigDecimal("2050000")).stockQuantity(14).imageUrl("https://images.unsplash.com/photo-1508296695146-257a814070b4?auto=format&fit=crop&w=600&q=80").build());

        // --- 4 KÍNH MÁT THỜI TRANG (6 -> 9) ---
        // 6. Ray-Ban Aviator RB3025
        Product p6 = productRepository.save(Product.builder().name("Kính Mát Ray-Ban Aviator Classic RB3025").slug("kinh-mat-ray-ban-aviator-classic-rb3025").category(catSun).brand(rayban).productType(ProductType.SUNGLASSES).description("Huyền thoại kính phi công, tròng thủy tinh G-15 xanh rêu chống chói 100% tia UV.").status("ACTIVE").build());
        productVariantRepository.save(ProductVariant.builder().product(p6).sku("RB-3025-L0205").barcode("8936012340066").name("Ray-Ban Aviator Gọng Vàng Tròng G-15").color("Gold / Green G-15").frameShape("Aviator Pilot").material("Metal Alloy").price(new BigDecimal("4850000")).costPrice(new BigDecimal("2900000")).stockQuantity(20).imageUrl("https://images.unsplash.com/photo-1577803645773-f96470509666?auto=format&fit=crop&w=600&q=80").build());

        // 7. Bolon Sunglasses BL7110 Polarized
        Product p7 = productRepository.save(Product.builder().name("Kính Mát Bolon BL7110 Phân Cực").slug("kinh-mat-bolon-bl7110-phan-cuc").category(catSun).brand(bolon).productType(ProductType.SUNGLASSES).description("Form mắt mèo nữ tính kiêu kỳ, tròng phân cực Polarized chống lóa đường phố và mặt nước.").status("ACTIVE").build());
        productVariantRepository.save(ProductVariant.builder().product(p7).sku("BL-7110-C20").barcode("8936012340073").name("Bolon BL7110 Mắt Mèo").color("Đồi Mồi Trà (Tortoise Brown)").frameShape("Cat Eye").material("Acetate & Metal").price(new BigDecimal("3680000")).costPrice(new BigDecimal("2100000")).stockQuantity(16).imageUrl("https://images.unsplash.com/photo-1509695507497-903c140c43b0?auto=format&fit=crop&w=600&q=80").build());

        // 8. Oakley Holbrook OO9102 Polarized
        Product p8 = productRepository.save(Product.builder().name("Kính Mát Oakley Holbrook Polarized OO9102").slug("kinh-mat-oakley-holbrook-polarized-oo9102").category(catSun).brand(oakley).productType(ProductType.SUNGLASSES).description("Thiết kế thể thao năng động ngoài trời, tròng Prizm Sapphire Polarized tăng cường độ tương phản.").status("ACTIVE").build());
        productVariantRepository.save(ProductVariant.builder().product(p8).sku("OK-9102-F555").barcode("8936012340080").name("Oakley Holbrook Prizm Polarized").color("Đen Mờ Tròng Xanh Biển").frameShape("Square Sport").material("O Matter").price(new BigDecimal("4150000")).costPrice(new BigDecimal("2400000")).stockQuantity(12).imageUrl("https://images.unsplash.com/photo-1511499767150-a48a237f0083?auto=format&fit=crop&w=600&q=80").build());

        // 9. Gentle Monster Her 01
        Product p9 = productRepository.save(Product.builder().name("Kính Mát Gentle Monster Her 01").slug("kinh-mat-gentle-monster-her-01").category(catSun).brand(gentleMonster).productType(ProductType.SUNGLASSES).description("Kính râm dáng vuông bản to thời thượng, bảo vệ mắt tối đa với tròng phẳng chống tia cực tím.").status("ACTIVE").build());
        productVariantRepository.save(ProductVariant.builder().product(p9).sku("GM-HER-01").barcode("8936012340097").name("Gentle Monster Her 01 Oversize").color("Đen Bóng").frameShape("Oversized Square").material("Acetate").price(new BigDecimal("5100000")).costPrice(new BigDecimal("3100000")).stockQuantity(8).imageUrl("https://images.unsplash.com/photo-1508296695146-257a814070b4?auto=format&fit=crop&w=600&q=80").build());

        // --- 4 TRÒNG KÍNH THUỐC CAO CẤP (10 -> 13) ---
        // 10. Essilor Crizal Sapphire HR 1.67
        Product p10 = productRepository.save(Product.builder().name("Tròng Kính Essilor Crizal Sapphire HR 1.67").slug("trong-kinh-essilor-crizal-sapphire-hr-167").category(catLenses).brand(essilor).productType(ProductType.LENS).description("Lớp váng phủ trong suốt 360 độ, chiết suất mỏng 1.67, chống trầy xước và bám vân tay đỉnh cao.").status("ACTIVE").build());
        productVariantRepository.save(ProductVariant.builder().product(p10).sku("LZ-CRIZAL-HR167").barcode("8936012340103").name("Crizal Sapphire HR 1.67").lensIndex("1.67").material("High-Index MR-7").price(new BigDecimal("2950000")).costPrice(new BigDecimal("1650000")).stockQuantity(40).imageUrl("https://images.unsplash.com/photo-1584308666744-24d5c474f2ae?auto=format&fit=crop&w=600&q=80").build());

        // 11. Essilor Transitions Gen 8 Đổi Màu
        Product p11 = productRepository.save(Product.builder().name("Tròng Kính Essilor Transitions Gen 8 Đổi Màu").slug("trong-kinh-essilor-transitions-gen-8-doi-mau").category(catLenses).brand(essilor).productType(ProductType.LENS).description("Đổi màu thế hệ mới nhất: đổi màu nhanh khi ra nắng gắt, nhả màu cực nhanh khi vào phòng, ngăn ánh sáng xanh.").status("ACTIVE").build());
        productVariantRepository.save(ProductVariant.builder().product(p11).sku("LZ-TRANS-GEN8-160").barcode("8936012340110").name("Transitions Gen 8 Đổi Màu Khói 1.60").lensIndex("1.60").material("MR-8 Photochromic").price(new BigDecimal("3380000")).costPrice(new BigDecimal("1950000")).stockQuantity(35).imageUrl("https://images.unsplash.com/photo-1591076482161-42ce6da69f67?auto=format&fit=crop&w=600&q=80").build());

        // 12. Carl Zeiss Single Vision Superb 1.60
        Product p12 = productRepository.save(Product.builder().name("Tròng Kính Carl Zeiss Single Vision Superb 1.60").slug("trong-kinh-carl-zeiss-single-vision-superb-160").category(catLenses).brand(zeiss).productType(ProductType.LENS).description("Thiết kế phi cầu Freeform độ nét quang học tối ưu từ nước Đức, phủ DuraVision Platinum siêu bền.").status("ACTIVE").build());
        productVariantRepository.save(ProductVariant.builder().product(p12).sku("LZ-ZEISS-SV160").barcode("8936012340127").name("Zeiss Single Vision Superb 1.60").lensIndex("1.60").material("Clarity MR-8").price(new BigDecimal("3450000")).costPrice(new BigDecimal("2100000")).stockQuantity(30).imageUrl("https://images.unsplash.com/photo-1572635196237-14b3f281503f?auto=format&fit=crop&w=600&q=80").build());

        // 13. Chemi Crystal U2 1.60
        Product p13 = productRepository.save(Product.builder().name("Tròng Kính Chemi Crystal U2 1.60").slug("trong-kinh-chemi-crystal-u2-160").category(catLenses).brand(chemi).productType(ProductType.LENS).description("Tròng kính phổ thông chất lượng cao Hàn Quốc, lớp phủ U2 cắt tia UV400, chống bám bụi và chống nước mưa.").status("ACTIVE").build());
        productVariantRepository.save(ProductVariant.builder().product(p13).sku("LZ-CHEMI-U2-160").barcode("8936012340134").name("Chemi U2 Chiết Suất 1.60").lensIndex("1.60").material("Monomer 1.60").price(new BigDecimal("850000")).costPrice(new BigDecimal("420000")).stockQuantity(60).imageUrl("https://images.unsplash.com/photo-1584308666744-24d5c474f2ae?auto=format&fit=crop&w=600&q=80").build());

        // --- 2 PHỤ KIỆN KÍNH MẮT (14 -> 15) ---
        // 14. Bộ Vệ Sinh Kính Nano Cleaner
        Product p14 = productRepository.save(Product.builder().name("Bộ Vệ Sinh Kính Chuyên Dụng Nano Cleaner 60ml").slug("bo-ve-sinh-kinh-chuyen-dung-nano-cleaner-60ml").category(catAcc).brand(essilor).productType(ProductType.ACCESSORY).description("Chai xịt làm sạch váng dầu chuyên biệt kèm khăn Microfiber sợi siêu mịn không làm xước tròng kính.").status("ACTIVE").build());
        productVariantRepository.save(ProductVariant.builder().product(p14).sku("ACC-CLEANER-60ML").barcode("8936012340141").name("Chai Xịt Nano Cleaner 60ml").material("Microfiber & Dung dịch Nano").price(new BigDecimal("120000")).costPrice(new BigDecimal("45000")).stockQuantity(150).imageUrl("https://images.unsplash.com/photo-1508296695146-257a814070b4?auto=format&fit=crop&w=600&q=80").build());

        // 15. Hộp Kính Da Cao Cấp OptiVision Handmade
        Product p15 = productRepository.save(Product.builder().name("Hộp Kính Da Cao Cấp OptiVision Handmade").slug("hop-kinh-da-cao-cap-optivision-handmade").category(catAcc).brand(bolon).productType(ProductType.ACCESSORY).description("Bao da gập nam châm bảo vệ gọng và tròng kính khỏi va đập, lót nhung chống trầy bên trong.").status("ACTIVE").build());
        productVariantRepository.save(ProductVariant.builder().product(p15).sku("ACC-CASE-LEATHER").barcode("8936012340158").name("Hộp Da OptiVision Khóa Nam Châm").material("Da PU Cao Cấp").price(new BigDecimal("250000")).costPrice(new BigDecimal("90000")).stockQuantity(80).imageUrl("https://images.unsplash.com/photo-1591076482161-42ce6da69f67?auto=format&fit=crop&w=600&q=80").build());

        log.info("Khởi tạo hoàn tất chính xác 15 sản phẩm kính mắt cao cấp vào db_catalog!");
    }
}
