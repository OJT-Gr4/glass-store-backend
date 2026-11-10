-- =========================================================================
-- S3-04: Customer Profile, Consent & Interaction History
-- Add marketing_consent, gender, address to users and create customer_interactions
-- =========================================================================

USE `db_identity`;

-- Add columns to users table for marketing consent and demographic details
SET @dbname = DATABASE();
SET @tablename = "users";

-- Add marketing_consent
SET @preparedStatement = (SELECT IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
   WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = @tablename AND COLUMN_NAME = "marketing_consent"
  ) > 0,
  "SELECT 1",
  "ALTER TABLE `users` ADD COLUMN `marketing_consent` BOOLEAN NOT NULL DEFAULT FALSE"
));
PREPARE stmt FROM @preparedStatement;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Add marketing_consent_updated_at
SET @preparedStatement = (SELECT IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
   WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = @tablename AND COLUMN_NAME = "marketing_consent_updated_at"
  ) > 0,
  "SELECT 1",
  "ALTER TABLE `users` ADD COLUMN `marketing_consent_updated_at` DATETIME NULL"
));
PREPARE stmt FROM @preparedStatement;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Add gender
SET @preparedStatement = (SELECT IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
   WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = @tablename AND COLUMN_NAME = "gender"
  ) > 0,
  "SELECT 1",
  "ALTER TABLE `users` ADD COLUMN `gender` VARCHAR(20) NULL"
));
PREPARE stmt FROM @preparedStatement;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Add address
SET @preparedStatement = (SELECT IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
   WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = @tablename AND COLUMN_NAME = "address"
  ) > 0,
  "SELECT 1",
  "ALTER TABLE `users` ADD COLUMN `address` VARCHAR(500) NULL"
));
PREPARE stmt FROM @preparedStatement;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Create customer_interactions table for CRM care notes and touchpoints
CREATE TABLE IF NOT EXISTS `customer_interactions` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `customer_id` BIGINT NOT NULL,
  `staff_id` BIGINT NULL,
  `staff_name` VARCHAR(100) NULL,
  `channel` VARCHAR(50) NOT NULL DEFAULT 'DIRECT',
  `content` TEXT NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_ci_customer_id` (`customer_id`),
  INDEX `idx_ci_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
