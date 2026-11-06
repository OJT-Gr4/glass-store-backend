-- Canonical, fresh-only development dataset. Never run on an existing database.
SET NAMES utf8mb4;
USE db_identity;
DELIMITER //
CREATE PROCEDURE glassstore_assert_empty_demo()
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema IN ('db_identity','db_catalog','db_order','db_lab')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'GlassStore seed requires empty schemas. Existing data was not reset; use reviewed migrations.';
  END IF;
END//
DELIMITER ;
CALL glassstore_assert_empty_demo();
DROP PROCEDURE glassstore_assert_empty_demo;
-- ==============================================================================
-- 02-seed-data.sql: Khởi tạo bảng dữ liệu và nạp dữ liệu mẫu (Seed Data)
-- Chuẩn hóa theo SRS Omnichannel Eyewear Retail & Eyecare Management System
-- Hệ thống 4 Logical Database độc lập: db_identity, db_catalog, db_lab, db_order
-- ==============================================================================

-- ==============================================================================
-- 1. SCHEMA: db_identity (Xác thực, Nhân sự theo Chi Nhánh & Phân quyền RBAC)
-- ==============================================================================
USE `db_identity`;

CREATE TABLE IF NOT EXISTS `roles` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `name` VARCHAR(50) NOT NULL UNIQUE,
    `description` VARCHAR(255)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `users` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `username` VARCHAR(50) NOT NULL UNIQUE,
    `email` VARCHAR(100) NOT NULL UNIQUE,
    `password_hash` VARCHAR(255) NOT NULL,
    `full_name` VARCHAR(100) NOT NULL,
    `phone_number` VARCHAR(20),
    `employee_code` VARCHAR(50) NULL,
    `branch_name` VARCHAR(100) DEFAULT 'Flagship Q.1 (TP.HCM)',
    `status` VARCHAR(20) DEFAULT 'ACTIVE',
    `failed_attempts` INT DEFAULT 0,
    `lockout_expiration` TIMESTAMP NULL DEFAULT NULL,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `user_roles` (
    `user_id` BIGINT NOT NULL,
    `role_id` BIGINT NOT NULL,
    PRIMARY KEY (`user_id`, `role_id`),
    CONSTRAINT `fk_user_roles_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_user_roles_role` FOREIGN KEY (`role_id`) REFERENCES `roles` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Nạp Roles
INSERT INTO `roles` (`id`, `name`, `description`) VALUES
(1, 'ROLE_ADMIN', 'Quản trị viên toàn hệ thống và chuỗi chi nhánh'),
(2, 'ROLE_SALES_STAFF', 'Nhân viên tư vấn bán hàng & Thu ngân POS'),
(3, 'ROLE_OPTOMETRIST', 'Bác sĩ / Kỹ thuật viên đo khám khúc xạ có CCHN'),
(4, 'ROLE_LAB_TECH', 'Kỹ thuật viên xưởng gia công mài lắp kính'),
(5, 'ROLE_CUSTOMER', 'Khách hàng mua sắm đa kênh (Web, App, POS)')
ON DUPLICATE KEY UPDATE `id` = `id`;

-- Nạp Users mẫu (Mật khẩu mặc định: 123456 -> mã hóa BCrypt: $2a$10$C6ujiQrjW/xUIVpiW0u7xuxeW2nYMd6.GoB3NacC14UG/6R9MQpom)
INSERT INTO `users` (`id`, `username`, `email`, `password_hash`, `full_name`, `phone_number`, `employee_code`, `branch_name`, `status`) VALUES
-- Ban Quản Trị & Cửa Hàng Trưởng
(1, 'admin', 'adminoptic@gmail.com', '$2a$10$C6ujiQrjW/xUIVpiW0u7xuxeW2nYMd6.GoB3NacC14UG/6R9MQpom', 'Admin', '0901000001', 'ADM-001', 'Trụ Sở Chính (Headquarter)', 'ACTIVE'),
(2, 'manager_nam', 'nam.le@optivision.vn', '$2a$10$C6ujiQrjW/xUIVpiW0u7xuxeW2nYMd6.GoB3NacC14UG/6R9MQpom', 'Lê Hoàng Nam (Store Manager)', '0901000014', 'STF-014', 'Tràng Tiền Plaza (Hà Nội)', 'ACTIVE'),

-- Nhân Viên Bán Hàng & Thu Ngân POS
(3, 'staff', 'posoptic@gmail.com', '$2a$10$C6ujiQrjW/xUIVpiW0u7xuxeW2nYMd6.GoB3NacC14UG/6R9MQpom', 'Nguyễn Văn Nam', '0901000002', 'NV-001', 'Flagship Q.1 (TP.HCM)', 'ACTIVE'),
(4, 'staff_ha', 'ha.le@optivision.vn', '$2a$10$C6ujiQrjW/xUIVpiW0u7xuxeW2nYMd6.GoB3NacC14UG/6R9MQpom', 'Lê Văn Hà (POS Tràng Tiền)', '0912345678', 'STF-012', 'Tràng Tiền Plaza (Hà Nội)', 'ACTIVE'),
(5, 'staff_vy', 'vy.nguyen@optivision.vn', '$2a$10$C6ujiQrjW/xUIVpiW0u7xuxeW2nYMd6.GoB3NacC14UG/6R9MQpom', 'Nguyễn Thảo Vy (Thủ Kho)', '0903112233', 'STF-092', 'Thảo Điền Store (Q.2)', 'ACTIVE'),

-- Bác Sĩ & KTV Đo Khúc Xạ Nhãn Khoa
(6, 'optometrist', 'optometryoptic@gmail.com', '$2a$10$C6ujiQrjW/xUIVpiW0u7xuxeW2nYMd6.GoB3NacC14UG/6R9MQpom', 'Lê Thị Ánh Tuyết', '0901000003', 'KTV-001', 'Flagship Q.1 (TP.HCM)', 'ACTIVE'),
(7, 'optom_phuc', 'phuc.lam@optivision.vn', '$2a$10$C6ujiQrjW/xUIVpiW0u7xuxeW2nYMd6.GoB3NacC14UG/6R9MQpom', 'BS. Lâm Hoàng Phúc', '0908889999', 'STF-102', 'Flagship Q.1 (TP.HCM)', 'ACTIVE'),

-- Kỹ Thuật Viên Xưởng Lab Mài Lắp
(8, 'labtech', 'laboptic@gmail.com', '$2a$10$C6ujiQrjW/xUIVpiW0u7xuxeW2nYMd6.GoB3NacC14UG/6R9MQpom', 'Trần Văn Tuấn', '0901000004', 'LAB-001', 'Lab Flagship Q.1', 'ACTIVE'),
(9, 'lab_dinh', 'dinh.nguyen@optivision.vn', '$2a$10$C6ujiQrjW/xUIVpiW0u7xuxeW2nYMd6.GoB3NacC14UG/6R9MQpom', 'Nguyễn Văn Định (KTV QC)', '0988776655', 'STF-078', 'Lab Tràng Tiền HN', 'ACTIVE'),

-- Khách Hàng Thành Viên Đa Kênh
(10, 'customer', 'customer@gmail.com', '$2a$10$C6ujiQrjW/xUIVpiW0u7xuxeW2nYMd6.GoB3NacC14UG/6R9MQpom', 'Nguyễn Hoàng Duy', '0918732998', NULL, NULL, 'ACTIVE'),
(11, 'cus_kieu', 'thuykieu.le@gmail.com', '$2a$10$C6ujiQrjW/xUIVpiW0u7xuxeW2nYMd6.GoB3NacC14UG/6R9MQpom', 'Lê Thúy Kiều', '0988124551', NULL, NULL, 'ACTIVE'),
(12, 'cus_quang', 'quang.trinh@gmail.com', '$2a$10$C6ujiQrjW/xUIVpiW0u7xuxeW2nYMd6.GoB3NacC14UG/6R9MQpom', 'Trịnh Minh Quang', '0912449002', NULL, NULL, 'ACTIVE'),
(13, 'cus_ngoc', 'bichngoc.pham@gmail.com', '$2a$10$C6ujiQrjW/xUIVpiW0u7xuxeW2nYMd6.GoB3NacC14UG/6R9MQpom', 'Phạm Thị Bích Ngọc', '0977898332', NULL, NULL, 'ACTIVE'),
(14, 'cus_kiet', 'kiet.do@gmail.com', '$2a$10$C6ujiQrjW/xUIVpiW0u7xuxeW2nYMd6.GoB3NacC14UG/6R9MQpom', 'Đỗ Tuấn Kiệt', '0938552191', NULL, NULL, 'ACTIVE')
ON DUPLICATE KEY UPDATE `id` = `id`;

-- Gán quyền Role cho Users
INSERT INTO `user_roles` (`user_id`, `role_id`) VALUES
(1, 1), -- admin -> ROLE_ADMIN
(2, 1), -- manager_nam -> ROLE_ADMIN
(3, 2), -- staff -> ROLE_SALES_STAFF
(4, 2), -- staff_ha -> ROLE_SALES_STAFF
(5, 2), -- staff_vy -> ROLE_SALES_STAFF
(6, 3), -- optometrist -> ROLE_OPTOMETRIST
(7, 3), -- optom_phuc -> ROLE_OPTOMETRIST
(8, 4), -- labtech -> ROLE_LAB_TECH
(9, 4), -- lab_dinh -> ROLE_LAB_TECH
(10, 5), -- cus_duy -> ROLE_CUSTOMER
(11, 5), -- cus_kieu -> ROLE_CUSTOMER
(12, 5), -- cus_quang -> ROLE_CUSTOMER
(13, 5), -- cus_ngoc -> ROLE_CUSTOMER
(14, 5)  -- cus_kiet -> ROLE_CUSTOMER
ON DUPLICATE KEY UPDATE `role_id` = `role_id`;


-- ==============================================================================
-- 2. SCHEMA: db_catalog (Danh mục, Thương hiệu, Sản phẩm quang học & Biến thể SKU)
-- ==============================================================================
USE `db_catalog`;

CREATE TABLE IF NOT EXISTS `categories` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `name` VARCHAR(100) NOT NULL,
    `slug` VARCHAR(100) NOT NULL UNIQUE,
    `description` VARCHAR(255),
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `brands` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `name` VARCHAR(100) NOT NULL UNIQUE,
    `country` VARCHAR(50),
    `description` VARCHAR(255)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `products` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `name` VARCHAR(255) NOT NULL,
    `slug` VARCHAR(255) NOT NULL UNIQUE,
    `category_id` BIGINT NOT NULL,
    `brand_id` BIGINT NOT NULL,
    `product_type` ENUM('FRAME', 'LENS', 'SUNGLASSES', 'ACCESSORY') NOT NULL,
    `description` TEXT,
    `status` VARCHAR(20) DEFAULT 'ACTIVE',
    `failed_attempts` INT DEFAULT 0,
    `lockout_expiration` TIMESTAMP NULL DEFAULT NULL,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `fk_products_category` FOREIGN KEY (`category_id`) REFERENCES `categories` (`id`),
    CONSTRAINT `fk_products_brand` FOREIGN KEY (`brand_id`) REFERENCES `brands` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `product_variants` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `product_id` BIGINT NOT NULL,
    `sku` VARCHAR(100) NOT NULL UNIQUE,
    `barcode` VARCHAR(100) NOT NULL UNIQUE,
    `name` VARCHAR(255) NOT NULL,
    `color` VARCHAR(50),
    `frame_shape` VARCHAR(50),     -- Mắt vuông, Mắt tròn, Aviator, Chữ nhật, Mắt mèo, Đa giác
    `material` VARCHAR(50),        -- Titanium, Nhựa TR90, Acetate, Hợp kim mạ vàng
    `lens_index` VARCHAR(20),      -- Chiết suất tròng: 1.56, 1.60, 1.67, 1.74
    `price` DECIMAL(12, 2) NOT NULL,
    `cost_price` DECIMAL(12, 2) NOT NULL,
    `stock_quantity` INT NOT NULL DEFAULT 50,
    `reserved_quantity` INT NOT NULL DEFAULT 0,
    `non_sellable_quantity` INT NOT NULL DEFAULT 0,
    `version` BIGINT NOT NULL DEFAULT 0,
    `image_url` VARCHAR(500),
    `status` VARCHAR(20) DEFAULT 'ACTIVE',
    `failed_attempts` INT DEFAULT 0,
    `lockout_expiration` TIMESTAMP NULL DEFAULT NULL,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `fk_variants_product` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Nạp Categories (Theo Bảng 5 SRS)
INSERT INTO `categories` (`id`, `name`, `slug`, `description`) VALUES
(1, 'Gọng Kính', 'gong-kinh', 'Gọng kính cận, viễn, loạn thời trang cao cấp Titanium, Acetate và TR90'),
(2, 'Tròng Kính', 'trong-kinh', 'Tròng kính thuốc chống ánh sáng xanh, chống trầy xước, đổi màu Transitions'),
(3, 'Kính Râm', 'kinh-ram', 'Kính mát thời trang chống tia cực tím UV400 và phân cực Polarized'),
(4, 'Phụ Kiện', 'phu-kien', 'Hộp đựng da thủ công, khăn lau microfiber, nước lau nano diệt khuẩn')
ON DUPLICATE KEY UPDATE `id` = `id`;

-- Nạp Brands (Thương hiệu chuẩn thế giới)
INSERT INTO `brands` (`id`, `name`, `country`, `description`) VALUES
(1, 'Bolon', 'Pháp', 'Thương hiệu gọng kính thời trang châu Âu thiết kế thanh lịch'),
(2, 'Ray-Ban', 'Mỹ / Ý', 'Thương hiệu kính râm và gọng kính biểu tượng số 1 toàn cầu'),
(3, 'Gentle Monster', 'Hàn Quốc', 'Thiết kế gọng kính phá cách dẫn đầu xu hướng thời trang châu Á'),
(4, 'Chemi', 'Hàn Quốc', 'Tròng kính quang học Crystal U2/U6 phổ biến với độ bền cao'),
(5, 'Essilor', 'Pháp', 'Tập đoàn tròng kính số 1 thế giới với công nghệ Crizal, Eyezen và Varilux'),
(6, 'Carl Zeiss', 'Đức', 'Tập đoàn quang học huyền thoại 175 năm công nghệ tròng Freeform'),
(7, 'Charmant', 'Nhật Bản', 'Thương hiệu gọng Titanium khoan không viền số 1 Nhật Bản'),
(8, 'Oakley', 'Mỹ', 'Dòng kính mát thể thao chuyên dụng với tròng Prizm chống va đập')
ON DUPLICATE KEY UPDATE `id` = `id`;

-- Nạp Products (14 sản phẩm kính mắt đa dạng)
INSERT INTO `products` (`id`, `name`, `slug`, `category_id`, `brand_id`, `product_type`, `description`, `status`) VALUES
(1, 'Gọng Kính Bolon Titanium Square BJ3120', 'gong-kinh-bolon-titanium-bj3120', 1, 1, 'FRAME', 'Khung B-Titanium siêu nhẹ 8.2g, chống ăn mòn mồ hôi, mắt vuông nam tính', 'ACTIVE'),
(2, 'Gọng Kính Nhựa Dẻo Gentle Monster South Side', 'gong-kinh-nhua-deo-gm-south-side', 1, 3, 'FRAME', 'Chất liệu TR90 siêu bền uốn dẻo, dáng mắt tròn vintage phong cách Hàn Quốc', 'ACTIVE'),
(3, 'Gọng Kính Aviator Ray-Ban Optical RB6489', 'gong-kinh-aviator-ray-ban-rb6489', 1, 2, 'FRAME', 'Dáng kính phi công huyền thoại thanh lịch, khung kim loại cao cấp mạ vàng/bạc', 'ACTIVE'),
(4, 'Gọng Kính Khoan Không Viền Charmant Titanium Z ZT2233', 'gong-kinh-khoan-charmant-zt2233', 1, 7, 'FRAME', 'Gọng bắt ốc không viền cao cấp Nhật Bản, vật liệu Z-Titanium siêu đàn hồi', 'ACTIVE'),
(5, 'Gọng Kính Kim Loại Đa Giác Bolon Retro Chic BJ7150', 'gong-kinh-da-giac-bolon-bj7150', 1, 1, 'FRAME', 'Dáng kính đa giác lục giác thời thượng, viền mạ vàng hồng Rose Gold tôn nét mặt', 'ACTIVE'),
(6, 'Gọng Kính Acetate Gentle Monster Milan', 'gong-kinh-acetate-gm-milan', 1, 3, 'FRAME', 'Chất liệu nhựa Acetate bóng cao cấp đúc nguyên khối, mắt kính oval cá tính', 'ACTIVE'),
(7, 'Tròng Kính Cận Chống Ánh Sáng Xanh Chemi U2 1.60', 'trong-kinh-can-chemi-u2-1-60', 2, 4, 'LENS', 'Chiết suất 1.60 mỏng nhẹ, lớp phủ Crystal U2 chống trầy xước, ngăn ánh sáng xanh', 'ACTIVE'),
(8, 'Tròng Kính Đổi Màu Essilor Transitions Gen 8 1.56', 'trong-kinh-doi-mau-essilor-transitions-gen-8', 2, 5, 'LENS', 'Công nghệ đổi màu siêu tốc khi ra nắng, bảo vệ mắt tối đa trước tia UV và chói sáng', 'ACTIVE'),
(9, 'Tròng Kính Crizal Rock 1.67 Blue UV Siêu Chống Trầy', 'trong-kinh-essilor-crizal-rock-1-67', 2, 5, 'LENS', 'Chiết suất 1.67 mỏng hơn 35%, công nghệ High-Resistance kháng trầy gấp 3 lần', 'ACTIVE'),
(10, 'Tròng Kính Cao Cấp Carl Zeiss ClearView BlueGuard 1.67', 'trong-kinh-zeiss-clearview-1-67', 2, 6, 'LENS', 'Tròng phẳng Freeform công nghệ Đức, tầm nhìn quang học sắc nét gấp 3 lần', 'ACTIVE'),
(11, 'Tròng Kính Đa Tròng Kỹ Thuật Số Essilor Varilux Comfort Max', 'trong-kinh-da-trong-essilor-varilux-comfort-max', 2, 5, 'LENS', 'Giải pháp thị lực toàn diện cho độ tuổi 40+, hỗ trợ chuyển đổi mượt nhìn xa và gần', 'ACTIVE'),
(12, 'Tròng Kính Cận Nặng Siêu Mỏng Chemi Crystal U6 1.74', 'trong-kinh-sieu-mong-chemi-u6-1-74', 2, 4, 'LENS', 'Chiết suất cao nhất 1.74, chuyên biệt cho độ cận cao từ -6.00D đến -15.00D', 'ACTIVE'),
(13, 'Kính Râm Ray-Ban Classic Wayfarer Polarized RB2140', 'kinh-ram-ray-ban-wayfarer-polarized', 3, 2, 'SUNGLASSES', 'Kính râm gọng đen mắt xanh đen phân cực chống lóa kinh điển bảo vệ mắt 100% UV400', 'ACTIVE'),
(14, 'Kính Mát Thể Thao Oakley Holbrook Prizm Sapphire', 'kinh-mat-oakley-holbrook-prizm', 3, 8, 'SUNGLASSES', 'Gọng O-Matter nhẹ bền chống va đập, tròng Prizm Sapphire tăng cường độ tương phản', 'ACTIVE'),
(15, 'Combo Phụ Kiện Hộp Đựng Da Khâu Tay & Khăn Lau Nano', 'hop-dung-kinh-da-khan-lau-nano', 4, 1, 'ACCESSORY', 'Bộ phụ kiện bảo quản kính chuyên dụng cao cấp chống va đập và dung dịch nano 60ml', 'ACTIVE')
ON DUPLICATE KEY UPDATE `id` = `id`;

-- Nạp Biến thể sản phẩm (SKU) có Barcode, giá bán, chiết suất và tồn kho
INSERT INTO `product_variants` (`id`, `product_id`, `sku`, `barcode`, `name`, `color`, `frame_shape`, `material`, `lens_index`, `price`, `cost_price`, `stock_quantity`, `image_url`, `status`) VALUES
-- Bolon Square BJ3120
(1, 1, 'FRM-BOLON-BJ3120-BLK', '893850123401', 'Gọng Bolon Titanium - Đen Titan Nhám', 'Đen nhám', 'Mắt vuông', 'Titanium', NULL, 3250000, 1800000, 38, 'https://images.unsplash.com/photo-1591076482161-42ce6da69f67?w=500', 'ACTIVE'),
(2, 1, 'FRM-BOLON-BJ3120-GLD', '893850123402', 'Gọng Bolon Titanium - Vàng Hồng Rose Gold', 'Vàng hồng', 'Mắt vuông', 'Titanium', NULL, 3450000, 1900000, 24, 'https://images.unsplash.com/photo-1574258495973-f010dfbb5371?w=500', 'ACTIVE'),
(3, 1, 'FRM-BOLON-BJ3120-SIL', '893850123403', 'Gọng Bolon Titanium - Bạc Sang Trọng', 'Bạc xước', 'Mắt vuông', 'Titanium', NULL, 3250000, 1800000, 18, 'https://images.unsplash.com/photo-1574258495973-f010dfbb5371?w=500', 'ACTIVE'),

-- Gentle Monster South Side
(4, 2, 'FRM-GM-SOUTHSIDE-BLK', '893850123404', 'Gọng Gentle Monster TR90 - Đen Bóng', 'Đen bóng', 'Mắt tròn', 'Nhựa TR90', NULL, 1850000, 950000, 45, 'https://images.unsplash.com/photo-1508296695146-257a814070b4?w=500', 'ACTIVE'),
(5, 2, 'FRM-GM-SOUTHSIDE-TOR', '893850123405', 'Gọng Gentle Monster TR90 - Đồi Mồi Nâu', 'Đồi mồi', 'Mắt tròn', 'Nhựa TR90', NULL, 1850000, 950000, 28, 'https://images.unsplash.com/photo-1511499767150-a48a237f0083?w=500', 'ACTIVE'),

-- Ray-Ban Aviator Optical
(6, 3, 'FRM-RB-AVIATOR-GLD', '893850123406', 'Gọng Ray-Ban Aviator - Mạ Vàng Kim 18K', 'Vàng kim', 'Aviator', 'Hợp kim', NULL, 4100000, 2400000, 20, 'https://images.unsplash.com/photo-1577803645773-f96470509666?w=500', 'ACTIVE'),
(7, 3, 'FRM-RB-AVIATOR-SIL', '893850123407', 'Gọng Ray-Ban Aviator - Bạc Chrome', 'Bạc chrome', 'Aviator', 'Hợp kim', NULL, 3950000, 2300000, 15, 'https://images.unsplash.com/photo-1577803645773-f96470509666?w=500', 'ACTIVE'),

-- Charmant Titanium Z ZT2233
(8, 4, 'FRM-CHARMANT-ZT2233', '893850123408', 'Gọng Charmant Titanium Z Khoan Không Viền', 'Xám nòng súng', 'Không viền', 'Titanium Z', NULL, 6850000, 4200000, 12, 'https://images.unsplash.com/photo-1591076482161-42ce6da69f67?w=500', 'ACTIVE'),

-- Bolon Retro Chic BJ7150
(9, 5, 'FRM-BOLON-BJ7150-ROSE', '893850123409', 'Gọng Đa Giác Bolon Retro Chic - Vàng Hồng', 'Vàng hồng', 'Đa giác', 'Titanium', NULL, 3650000, 2100000, 22, 'https://images.unsplash.com/photo-1574258495973-f010dfbb5371?w=500', 'ACTIVE'),

-- Gentle Monster Milan
(10, 6, 'FRM-GM-MILAN-CRY', '893850123410', 'Gọng Acetate Gentle Monster Milan - Trong Suốt', 'Trong suốt', 'Mắt tròn', 'Acetate', NULL, 2450000, 1350000, 30, 'https://images.unsplash.com/photo-1508296695146-257a814070b4?w=500', 'ACTIVE'),

-- Tròng Chemi U2 1.60
(11, 7, 'LNS-CHEMI-U2-160', '893850123411', 'Cặp Tròng Chemi U2 BlueCut 1.60', 'Trong suốt', NULL, 'Chất dẻo quang học', '1.60', 650000, 320000, 120, 'https://images.unsplash.com/photo-1582142839970-2b9da988941f?w=500', 'ACTIVE'),

-- Tròng Essilor Transitions 1.56
(12, 8, 'LNS-ESS-TRANS-156', '893850123412', 'Cặp Tròng Essilor Transitions Gen 8 1.56', 'Đổi màu khói', NULL, 'Chất dẻo quang học', '1.56', 3380000, 1950000, 40, 'https://images.unsplash.com/photo-1582142839970-2b9da988941f?w=500', 'ACTIVE'),

-- Tròng Essilor Crizal Rock 1.67
(13, 9, 'LNS-ESS-CRIZAL-167', '893850123413', 'Cặp Tròng Essilor Crizal Rock 1.67 Blue UV', 'Trong suốt', NULL, 'Chất dẻo quang học', '1.67', 1980000, 1100000, 65, 'https://images.unsplash.com/photo-1582142839970-2b9da988941f?w=500', 'ACTIVE'),

-- Tròng Carl Zeiss ClearView 1.67
(14, 10, 'LNS-ZEISS-167', '893850123414', 'Cặp Tròng Zeiss ClearView 1.67 BlueGuard', 'Trong suốt', NULL, 'Chất dẻo quang học', '1.67', 4250000, 2600000, 25, 'https://images.unsplash.com/photo-1582142839970-2b9da988941f?w=500', 'ACTIVE'),

-- Tròng Đa Tròng Essilor Varilux 1.60
(15, 11, 'LNS-ESS-VARILUX-160', '893850123415', 'Cặp Tròng Đa Tròng Essilor Varilux Comfort Max 1.60', 'Trong suốt', NULL, 'Chất dẻo quang học', '1.60', 5650000, 3400000, 15, 'https://images.unsplash.com/photo-1582142839970-2b9da988941f?w=500', 'ACTIVE'),

-- Tròng Chemi U6 1.74 Siêu Mỏng
(16, 12, 'LNS-CHEMI-U6-174', '893850123416', 'Cặp Tròng Chemi Crystal U6 1.74 Siêu Mỏng', 'Trong suốt', NULL, 'Chất dẻo quang học', '1.74', 2500000, 1400000, 35, 'https://images.unsplash.com/photo-1582142839970-2b9da988941f?w=500', 'ACTIVE'),

-- Kính râm Wayfarer
(17, 13, 'SUN-RB-WAYF-BLK', '893850123417', 'Kính Râm Ray-Ban Classic Wayfarer Polarized', 'Đen bóng', 'Mắt vuông', 'Acetate', NULL, 4950000, 2900000, 18, 'https://images.unsplash.com/photo-1511499767150-a48a237f0083?w=500', 'ACTIVE'),

-- Kính mát Oakley Holbrook
(18, 14, 'SUN-OAK-HOLBROOK-BLU', '893850123418', 'Kính Mát Oakley Holbrook Prizm Sapphire', 'Đen mờ Sapphire', 'Mắt vuông thể thao', 'O-Matter', NULL, 3950000, 2300000, 20, 'https://images.unsplash.com/photo-1511499767150-a48a237f0083?w=500', 'ACTIVE'),

-- Phụ kiện
(19, 15, 'ACC-BOX-LEATHER', '893850123419', 'Hộp Đựng Kính Da Khâu Tay Chống Sốc', 'Nâu bò', NULL, 'Da PU', NULL, 180000, 75000, 150, 'https://images.unsplash.com/photo-1584917865442-de89df76afd3?w=500', 'ACTIVE'),
(20, 15, 'ACC-NANO-SPRAY', '893850123420', 'Chai Xịt Nano Kháng Khuẩn & Chống Bám Bụi (60ml)', 'Trong suốt', NULL, 'Dung dịch nano', NULL, 95000, 35000, 300, 'https://images.unsplash.com/photo-1584917865442-de89df76afd3?w=500', 'ACTIVE'),
(21, 15, 'ACC-MICRO-CLOTH', '893850123421', 'Khăn Lau Kính Microfiber Nhãn Khoa Cao Cấp', 'Xanh navy', NULL, 'Sợi Microfiber', NULL, 35000, 12000, 500, 'https://images.unsplash.com/photo-1584917865442-de89df76afd3?w=500', 'ACTIVE')
ON DUPLICATE KEY UPDATE `id` = `id`;


-- ==============================================================================
-- 3. SCHEMA: db_lab (Đo mắt khúc xạ OD/OS & Lệnh mài lắp gia công xưởng)
-- ==============================================================================
USE `db_lab`;

CREATE TABLE IF NOT EXISTS `prescriptions` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `customer_id` BIGINT NOT NULL,
    `customer_name` VARCHAR(255),
    `optometrist_name` VARCHAR(255),
    `optometrist_id` BIGINT NOT NULL,
    `od_sph` DECIMAL(4, 2) NOT NULL,     -- Mắt phải (OD) - Độ cầu
    `od_cyl` DECIMAL(4, 2) DEFAULT 0.00, -- Độ loạn
    `od_axis` INT DEFAULT 0,             -- Trục loạn (0 - 180 độ)
    `od_add` DECIMAL(4, 2) DEFAULT 0.00, -- Độ cộng thêm nhìn gần (lão thị)
    `os_sph` DECIMAL(4, 2) NOT NULL,     -- Mắt trái (OS) - Độ cầu
    `os_cyl` DECIMAL(4, 2) DEFAULT 0.00, -- Độ loạn
    `os_axis` INT DEFAULT 0,             -- Trục loạn (0 - 180 độ)
    `os_add` DECIMAL(4, 2) DEFAULT 0.00,
    `pd` DECIMAL(4, 1) NOT NULL,         -- Khoảng cách đồng tử (mm)
    `notes` VARCHAR(500),
    `lens_sku` VARCHAR(255),
    `lens_name` VARCHAR(255),
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `lab_jobs` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `idempotency_key` VARCHAR(100) NOT NULL UNIQUE,
    `order_item_id` BIGINT,
    `order_id` BIGINT NOT NULL,
    `order_code` VARCHAR(60),
    `tray_number` VARCHAR(60),
    `customer_name` VARCHAR(255),
    `prescription_id` BIGINT,
    `sku` VARCHAR(100),
    `frame_sku` VARCHAR(255),
    `frame_name` VARCHAR(255),
    `lens_sku` VARCHAR(255),
    `lens_name` VARCHAR(255),
    `technician_id` BIGINT,
    `status` VARCHAR(50) NOT NULL DEFAULT 'CREATED',
    `qc_notes` VARCHAR(500),
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `fk_lab_jobs_prescription` FOREIGN KEY (`prescription_id`) REFERENCES `prescriptions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Nạp Phiếu đo mắt lâm sàng theo Bảng 8 SRS (Độ cầu SPH, độ loạn CYL, trục AXIS, ADD, PD)
INSERT INTO `prescriptions` (`id`, `customer_id`, `optometrist_id`, `od_sph`, `od_cyl`, `od_axis`, `od_add`, `os_sph`, `os_cyl`, `os_axis`, `os_add`, `pd`, `notes`) VALUES
-- 1. Nguyễn Hoàng Duy (Cận thị văn phòng)
(1, 10, 6, -3.25, -0.75, 180, 0.00, -2.75, -0.50, 175, 0.00, 63.5, 'Làm việc màn hình 8h/ngày, khuyên dùng tròng Essilor Crizal Rock 1.67 Blue UV giảm mỏi điều tiết'),
-- 2. Lê Thúy Kiều (Cận loạn vừa)
(2, 11, 7, -2.00, -0.50, 180, 0.00, -1.75, -0.25, 175, 0.00, 61.0, 'Độ khúc xạ ổn định so với năm ngoái, lắp gọng Aviator mạ vàng tôn dáng mặt'),
-- 3. Trịnh Minh Quang (Cận nặng & Loạn cao)
(3, 12, 6, -7.50, -2.50, 180, 0.00, -7.00, -2.00, 170, 0.00, 69.0, 'Cảnh báo: Khoảng cách đồng tử PD rộng 69mm và độ cận cao. Cần mài tâm tròng Chemi U6 1.74 chính xác'),
-- 4. Phạm Thị Bích Ngọc (Lão thị / Kính Đa Tròng)
(4, 13, 6, 1.50, 0.00, 0, 2.00, 1.75, 0.00, 0, 2.00, 64.0, 'Lão thị nhìn gần mờ, tư vấn đa tròng kỹ thuật số Essilor Varilux Comfort Max mượt mà'),
-- 5. Đỗ Tuấn Kiệt (Kính không độ chống chói & UV)
(5, 14, 7, 0.00, 0.00, 0, 0.00, 0.00, 0.00, 0, 0.00, 65.0, 'Mắt chính thị 10/10, mua kính mát thể thao Oakley Holbrook đi phượt và chống bụi'),
-- 6. Đỗ Quỳnh Anh (Cận nhẹ học sinh)
(6, 10, 7, -1.50, 0.00, 0, 0.00, -1.25, 0.00, 0, 0.00, 60.5, 'Cận thị học đường, khuyên dùng tròng đổi màu Transitions bảo vệ võng mạc')
ON DUPLICATE KEY UPDATE `id` = `id`;

-- Nạp Lệnh gia công xưởng Lab mẫu trong các giai đoạn vận hành
INSERT INTO `lab_jobs` (`id`, `idempotency_key`, `order_item_id`, `order_id`, `prescription_id`, `frame_sku`, `lens_sku`, `technician_id`, `status`, `qc_notes`) VALUES
(1, 'LAB_REQ_1001_2', 2, 1001, 1, 'FRM-BOLON-BJ3120-BLK', 'LNS-ESS-CRIZAL-167', 8, 'IN_PROGRESS', 'KTV Trần Văn Bách đang vận hành máy mài Essilor Kappa vát cạnh gọng Bolon'),
(2, 'LAB_REQ_1002_4', 4, 1002, 2, 'FRM-RB-AVIATOR-GLD', 'LNS-CHEMI-U2-160', 8, 'QC_PASSED', 'Kiểm tra tâm quang học đạt chuẩn ANSI Z80.1, hoàn tất bao gói và chuyển shipper AhaMove'),
(3, 'LAB_REQ_1003_6', 6, 1003, 3, 'FRM-CHARMANT-ZT2233', 'LNS-CHEMI-U6-174', 8, 'WAITING_PROCESS', 'Đang chờ BS. Vũ Anh Tuấn ký xác nhận duyệt lệnh mài tròng chiết suất 1.74 do PD = 69mm'),
(4, 'LAB_REQ_1004_8', 8, 1004, 4, 'FRM-BOLON-BJ7150-ROSE', 'LNS-ESS-VARILUX-160', 9, 'QC_CHECK', 'Đo đối chiếu thông số đa tròng Varilux trên máy chấm tâm điện tử Nidek LM-1800'),
(6, 'LAB_REQ_1006_12', 12, 1006, 6, 'FRM-GM-SOUTHSIDE-BLK', 'LNS-ESS-TRANS-156', 8, 'QC_PASSED', 'Mài lắp hoàn hảo, kiểm tra lực xiết ốc bản lề 0.18 N.m đạt chuẩn Bench Alignment')
ON DUPLICATE KEY UPDATE `id` = `id`;


-- ==============================================================================
-- 4. SCHEMA: db_order (Giỏ hàng, Đơn online, Bán tại quầy POS & Thanh toán đa kênh)
-- ==============================================================================
USE `db_order`;

CREATE TABLE IF NOT EXISTS `orders` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `order_code` VARCHAR(50) NOT NULL UNIQUE,
    `customer_id` BIGINT,
    `customer_name` VARCHAR(100) NOT NULL,
    `customer_phone` VARCHAR(20) NOT NULL,
    `shipping_address` VARCHAR(255),
    `branch_code` VARCHAR(32),
    `branch_name` VARCHAR(120),
    `payment_method` VARCHAR(20),
    `prescription_id` BIGINT,
    `tax_amount` DECIMAL(12,2) DEFAULT 0,
    `channel` ENUM('ONLINE', 'POS') DEFAULT 'ONLINE',
    `order_type` ENUM('FRAME_ONLY', 'LENS_ONLY', 'COMPLETE_GLASSES', 'ACCESSORY') DEFAULT 'COMPLETE_GLASSES',
    `total_amount` DECIMAL(12, 2) NOT NULL,
    `discount_amount` DECIMAL(12, 2) DEFAULT 0.00,
    `final_amount` DECIMAL(12, 2) NOT NULL,
    `status` VARCHAR(30) NOT NULL DEFAULT 'PAID',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `order_items` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `order_id` BIGINT NOT NULL,
    `sku_id` VARCHAR(100) NOT NULL,
    `product_name` VARCHAR(255) NOT NULL,
    `prescription_id` BIGINT,
    `quantity` INT NOT NULL DEFAULT 1,
    `unit_price` DECIMAL(12, 2) NOT NULL,
    `subtotal` DECIMAL(12, 2) NOT NULL,
    CONSTRAINT `fk_order_items_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `payments` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `order_id` BIGINT NOT NULL,
    `payment_method` ENUM('CASH', 'VIETQR', 'VNPAY', 'COD') NOT NULL,
    `transaction_code` VARCHAR(100),
    `amount` DECIMAL(12, 2) NOT NULL,
    `status` ENUM('PENDING', 'SUCCESS', 'FAILED') DEFAULT 'SUCCESS',
    `paid_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT `fk_payments_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Nạp 6 Đơn hàng đa kênh thực tế (Theo Bảng 7 SRS: Kênh Online & POS Cửa hàng)
INSERT INTO `orders` (`id`, `order_code`, `customer_id`, `customer_name`, `customer_phone`, `shipping_address`, `channel`, `order_type`, `total_amount`, `discount_amount`, `final_amount`, `status`) VALUES
-- 1. Đơn POS Flagship Q.1 - Khách Nguyễn Hoàng Duy
(1001, 'OPT-8842-SG', 10, 'Nguyễn Hoàng Duy', '0918732998', '120 Hai Bà Trưng, P. Đa Kao, Q.1, TP.HCM', 'POS', 'COMPLETE_GLASSES', 5230000, 0, 5230000, 'PREPARING'),

-- 2. Đơn Online App - Khách Lê Thúy Kiều
(1002, 'OPT-8841-ONL', 11, 'Lê Thúy Kiều', '0988124551', '45 Lê Duẩn, P. Bến Nghé, Q.1, TP.HCM', 'ONLINE', 'COMPLETE_GLASSES', 4750000, 630000, 4120000, 'SHIPPING'),

-- 3. Đơn Web E-Com - Khách Trịnh Minh Quang
(1003, 'OPT-8839-WEB', 12, 'Trịnh Minh Quang', '0912449002', '88 Nguyễn Đình Chiểu, Q.3, TP.HCM', 'ONLINE', 'COMPLETE_GLASSES', 6850000, 0, 6850000, 'PREPARING'),

-- 4. Đơn POS Tràng Tiền Plaza HN - Khách Phạm Thị Bích Ngọc
(1004, 'OPT-8835-HN', 13, 'Phạm Thị Bích Ngọc', '0977898332', '24 Tràng Tiền, Hoàn Kiếm, Hà Nội', 'POS', 'COMPLETE_GLASSES', 12500000, 0, 12500000, 'PREPARING'),

-- 5. Đơn Online App - Khách Đỗ Tuấn Kiệt (Kính mát Oakley)
(1005, 'OPT-8830-ONL', 14, 'Đỗ Tuấn Kiệt', '0938552191', '52 Đường số 7, KDC An Phú, TP. Thủ Đức', 'ONLINE', 'FRAME_ONLY', 3950000, 0, 3950000, 'PENDING_PAYMENT'),

-- 6. Đơn POS Flagship Q.1 - Khách Đỗ Quỳnh Anh (Combo Gọng + Tròng đổi màu)
(1006, 'OPT-8825-POS', 10, 'Đỗ Quỳnh Anh', '0909123456', 'Nhận trực tiếp tại Showroom Q.1', 'POS', 'COMPLETE_GLASSES', 5230000, 0, 5230000, 'COMPLETED')
ON DUPLICATE KEY UPDATE `id` = `id`;

-- Nạp Chi tiết các sản phẩm trong đơn hàng (Order Items)
INSERT INTO `order_items` (`id`, `order_id`, `sku_id`, `product_name`, `prescription_id`, `quantity`, `unit_price`, `subtotal`) VALUES
-- Đơn 1001 (Nguyễn Hoàng Duy)
(1, 1001, 'FRM-BOLON-BJ3120-BLK', 'Gọng Bolon Titanium - Đen Titan Nhám', 1, 1, 3250000, 3250000),
(2, 1001, 'LNS-ESS-CRIZAL-167', 'Cặp Tròng Essilor Crizal Rock 1.67 Blue UV', 1, 1, 1980000, 1980000),

-- Đơn 1002 (Lê Thúy Kiều)
(3, 1002, 'FRM-RB-AVIATOR-GLD', 'Gọng Ray-Ban Aviator - Mạ Vàng Kim 18K', 2, 1, 4100000, 4100000),
(4, 1002, 'LNS-CHEMI-U2-160', 'Cặp Tròng Chemi U2 BlueCut 1.60', 2, 1, 650000, 650000),

-- Đơn 1003 (Trịnh Minh Quang)
(5, 1003, 'FRM-CHARMANT-ZT2233', 'Gọng Charmant Titanium Z Khoan Không Viền', 3, 1, 4350000, 4350000),
(6, 1003, 'LNS-CHEMI-U6-174', 'Cặp Tròng Chemi Crystal U6 1.74 Siêu Mỏng', 3, 1, 2500000, 2500000),

-- Đơn 1004 (Phạm Thị Bích Ngọc)
(7, 1004, 'FRM-BOLON-BJ7150-ROSE', 'Gọng Đa Giác Bolon Retro Chic - Vàng Hồng', 4, 1, 3650000, 3650000),
(8, 1004, 'LNS-ESS-VARILUX-160', 'Cặp Tròng Đa Tròng Essilor Varilux Comfort Max 1.60', 4, 1, 5650000, 5650000),
(9, 1004, 'FRM-CHARMANT-ZT2233', 'Gọng Charmant Titanium Z Dự Phòng', NULL, 1, 3200000, 3200000),

-- Đơn 1005 (Đỗ Tuấn Kiệt)
(10, 1005, 'SUN-OAK-HOLBROOK-BLU', 'Kính Mát Oakley Holbrook Prizm Sapphire', NULL, 1, 3950000, 3950000),

-- Đơn 1006 (Đỗ Quỳnh Anh)
(11, 1006, 'FRM-GM-SOUTHSIDE-BLK', 'Gọng Gentle Monster TR90 - Đen Bóng', 6, 1, 1850000, 1850000),
(12, 1006, 'LNS-ESS-TRANS-156', 'Cặp Tròng Essilor Transitions Gen 8 1.56', 6, 1, 3380000, 3380000)
ON DUPLICATE KEY UPDATE `id` = `id`;

-- Nạp Giao dịch thanh toán (Payments)
INSERT INTO `payments` (`id`, `order_id`, `payment_method`, `transaction_code`, `amount`, `status`) VALUES
(1, 1001, 'VNPAY', 'VNP-20261102-8842', 5230000, 'SUCCESS'),
(2, 1002, 'VIETQR', 'VQR-MBB-988124', 4120000, 'SUCCESS'),
(3, 1003, 'VIETQR', 'VQR-VCB-912449', 6850000, 'SUCCESS'),
(4, 1004, 'VNPAY', 'POS-BIDV-883501', 12500000, 'SUCCESS'),
(5, 1005, 'COD', 'COD-AHA-883012', 3950000, 'PENDING'),
(6, 1006, 'CASH', 'CASH-POS-882501', 5230000, 'SUCCESS')
ON DUPLICATE KEY UPDATE `id` = `id`;

-- Fill display metadata from the same canonical identities and order records.
UPDATE db_order.orders o JOIN db_identity.users u ON u.id=o.customer_id
SET o.customer_name=u.full_name, o.customer_phone=u.phone_number;
UPDATE db_lab.prescriptions p JOIN db_identity.users c ON c.id=p.customer_id
JOIN db_identity.users d ON d.id=p.optometrist_id
SET p.customer_name=c.full_name, p.optometrist_name=d.full_name;

UPDATE db_order.orders o
SET o.branch_code=CASE WHEN o.channel='POS' THEN IF(o.id=1004,'BRANCH_02','BRANCH_01') ELSE NULL END,
    o.branch_name=CASE WHEN o.channel='POS' THEN IF(o.id=1004,'Chi nhánh 02 - Tràng Tiền, Hà Nội','Chi nhánh 01 - Lê Thánh Tôn, Quận 1') ELSE NULL END,
    o.payment_method=(SELECT p.payment_method FROM db_order.payments p WHERE p.order_id=o.id ORDER BY p.id DESC LIMIT 1),
    o.prescription_id=(SELECT MAX(i.prescription_id) FROM db_order.order_items i WHERE i.order_id=o.id);
UPDATE db_lab.lab_jobs j JOIN db_order.orders o ON o.id=j.order_id
LEFT JOIN db_catalog.product_variants f ON f.sku=j.frame_sku
LEFT JOIN db_catalog.product_variants l ON l.sku=j.lens_sku
SET j.order_code=o.order_code, j.customer_name=o.customer_name,
    j.tray_number=CONCAT('KHAY-',o.order_code),j.sku=j.lens_sku,
    j.frame_name=f.name,j.lens_name=l.name;
