-- ==============================================================================
-- 20261109-sprint3-otp-warranty.sql: Migration Sprint 3
-- Hỗ trợ S3-01 (Phone OTP & Token Version Revocation), S3-09 (Refund Confirmation),
-- S3-10 (Warranty Policy Versioning & Claim History), S3-11 (OMS Cancel Rejection)
-- An toàn, có thể chạy lại nhiều lần (idempotent), không làm mất dữ liệu cũ.
-- ==============================================================================

-- 1. DB_IDENTITY: S3-01 Phone OTP & Token Version
USE `db_identity`;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'db_identity' AND table_name = 'users' AND column_name = 'phone_verified'),
    'SELECT 1', 'ALTER TABLE users ADD COLUMN phone_verified BOOLEAN NOT NULL DEFAULT FALSE');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'db_identity' AND table_name = 'users' AND column_name = 'token_version'),
    'SELECT 1', 'ALTER TABLE users ADD COLUMN token_version INT NOT NULL DEFAULT 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE users SET token_version = 0 WHERE token_version IS NULL;
UPDATE users SET phone_verified = TRUE WHERE phone_number IS NOT NULL AND phone_verified = FALSE;

CREATE TABLE IF NOT EXISTS `otp_challenges` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `challenge_code` VARCHAR(64) NOT NULL UNIQUE,
    `user_id` BIGINT NOT NULL,
    `phone_number` VARCHAR(20) NOT NULL,
    `purpose` VARCHAR(32) NOT NULL,
    `code_hash` VARCHAR(255) NOT NULL,
    `expires_at` DATETIME(6) NOT NULL,
    `attempt_count` INT NOT NULL DEFAULT 0,
    `max_attempts` INT NOT NULL DEFAULT 5,
    `consumed_at` DATETIME(6) NULL,
    `invalidated_at` DATETIME(6) NULL,
    `created_at` DATETIME(6) NOT NULL,
    INDEX `idx_otp_user_purpose` (`user_id`, `purpose`),
    INDEX `idx_otp_challenge_code` (`challenge_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. DB_LAB: S3-10 Warranty Policy Versioning & Claim Histories
USE `db_lab`;

CREATE TABLE IF NOT EXISTS `warranties` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `warranty_code` VARCHAR(255) NOT NULL UNIQUE,
    `original_order_code` VARCHAR(255) NULL,
    `order_id` BIGINT NULL,
    `order_item_id` BIGINT NULL,
    `benefit_type` VARCHAR(40) NULL,
    `activated_at` DATETIME(6) NULL,
    `expires_at` DATETIME(6) NULL,
    `product_barcode` VARCHAR(255) NULL,
    `customer_id` BIGINT NULL,
    `customer_name` VARCHAR(255) NOT NULL,
    `customer_phone` VARCHAR(255) NULL,
    `customer_tier` VARCHAR(255) NULL,
    `product_name` VARCHAR(255) NOT NULL,
    `product_sku` VARCHAR(255) NULL,
    `terms_and_conditions` VARCHAR(1000) NULL,
    `policy` VARCHAR(255) NULL,
    `status` VARCHAR(50) NULL,
    `status_label` VARCHAR(255) NULL,
    `created_at` DATETIME(6) NULL,
    `updated_at` DATETIME(6) NULL,
    CONSTRAINT `uk_warranty_order_item_benefit` UNIQUE (`order_item_id`, `benefit_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `warranty_policies` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `policy_code` VARCHAR(64) NOT NULL,
    `benefit_type` VARCHAR(40) NOT NULL,
    `version` INT NOT NULL DEFAULT 1,
    `duration_days` INT NULL,
    `coverage_terms` VARCHAR(1000) NOT NULL,
    `eligible_sku_prefixes` VARCHAR(120) NULL,
    `service_fee` DECIMAL(12, 2) DEFAULT 0.00,
    `effective_from` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `active` BOOLEAN NOT NULL DEFAULT TRUE,
    `updated_by` VARCHAR(100) NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX `idx_warranty_policy_benefit_active` (`benefit_type`, `active`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `warranty_policies` (`id`, `policy_code`, `benefit_type`, `version`, `duration_days`, `coverage_terms`, `service_fee`, `active`, `updated_by`) VALUES
(1, 'POL_EXCHANGE_30D', 'EXCHANGE_30_DAYS', 1, 30, 'Đổi mới sản phẩm lỗi do nhà sản xuất trong vòng 30 ngày', 0.00, TRUE, 'system'),
(2, 'POL_LENS_COAT_180D', 'LENS_COATING', 1, 180, 'Bảo hành bong tróc lớp váng phủ tròng kính trong 6 tháng', 0.00, TRUE, 'system'),
(3, 'POL_FRAME_ADJ_365D', 'FRAME_ADJUSTMENT', 1, 365, 'Căn chỉnh gọng, thay đệm mũi và ốc vít miễn phí 1 năm', 0.00, TRUE, 'system')
ON DUPLICATE KEY UPDATE `id` = `id`;

CREATE TABLE IF NOT EXISTS `warranty_claim_histories` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `warranty_id` BIGINT NOT NULL,
    `old_status` VARCHAR(50) NULL,
    `new_status` VARCHAR(50) NOT NULL,
    `reason` VARCHAR(500) NULL,
    `changed_by` VARCHAR(100) NULL,
    `changed_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX `idx_claim_history_warranty` (`warranty_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. DB_ORDER: S3-09 & S3-11 Cancel, Refund Fields
USE `db_order`;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'db_order' AND table_name = 'orders' AND column_name = 'refund_confirmed_by'),
    'SELECT 1', 'ALTER TABLE orders ADD COLUMN refund_confirmed_by VARCHAR(255) NULL');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'db_order' AND table_name = 'orders' AND column_name = 'refund_confirmed_at'),
    'SELECT 1', 'ALTER TABLE orders ADD COLUMN refund_confirmed_at DATETIME(6) NULL');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'db_order' AND table_name = 'orders' AND column_name = 'refund_status'),
    'SELECT 1', 'ALTER TABLE orders ADD COLUMN refund_status VARCHAR(255) NULL');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'db_order' AND table_name = 'orders' AND column_name = 'cancel_reason'),
    'SELECT 1', 'ALTER TABLE orders ADD COLUMN cancel_reason VARCHAR(255) NULL');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'db_order' AND table_name = 'orders' AND column_name = 'cancelled_by'),
    'SELECT 1', 'ALTER TABLE orders ADD COLUMN cancelled_by VARCHAR(255) NULL');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'db_order' AND table_name = 'orders' AND column_name = 'cancelled_at'),
    'SELECT 1', 'ALTER TABLE orders ADD COLUMN cancelled_at DATETIME(6) NULL');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
