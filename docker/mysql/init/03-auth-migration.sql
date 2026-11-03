-- ==============================================================================
-- 03-auth-migration.sql: Bổ sung các cột và bảng cho Authentication & RBAC
-- Chạy sau 01-init-databases.sql và 02-seed-data.sql
-- ==============================================================================

USE `db_identity`;

-- Bổ sung cột brute-force protection vào bảng users nếu chưa có
ALTER TABLE `users`
    ADD COLUMN IF NOT EXISTS `failed_login_attempts` INT NOT NULL DEFAULT 0 COMMENT 'Số lần nhập sai mật khẩu liên tiếp',
    ADD COLUMN IF NOT EXISTS `locked_until` DATETIME NULL COMMENT 'Tài khoản bị khóa đến thời điểm này',
    MODIFY COLUMN `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        COMMENT 'PENDING | ACTIVE | LOCKED';

-- Bảng lưu OTP xác thực (OTP 6 số, hết hạn sau 5 phút)
CREATE TABLE IF NOT EXISTS `otp_verifications` (
    `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `email`       VARCHAR(100) NOT NULL COMMENT 'Email người dùng',
    `otp_code`    VARCHAR(10)  NOT NULL COMMENT 'Mã OTP 6 chữ số (không lưu plain text sau xác thực)',
    `expiry_time` DATETIME     NOT NULL COMMENT 'Thời điểm hết hạn OTP',
    `used`        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'Đã sử dụng: 0=Chưa, 1=Đã dùng',
    `created_at`  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_otp_email` (`email`),
    INDEX `idx_otp_expiry` (`expiry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Bảng lưu mã OTP xác thực email';

-- Bảng lưu RefreshToken (hỗ trợ thu hồi & đa thiết bị)
CREATE TABLE IF NOT EXISTS `refresh_tokens` (
    `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `token`       VARCHAR(255) NOT NULL UNIQUE COMMENT 'UUID refresh token',
    `user_id`     BIGINT       NOT NULL,
    `expiry_date` DATETIME     NOT NULL COMMENT 'Thời hạn 7 ngày',
    `revoked`     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'Bị thu hồi: 0=Không, 1=Đã thu hồi',
    `created_at`  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT `fk_refresh_token_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
    INDEX `idx_refresh_token` (`token`),
    INDEX `idx_refresh_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Bảng quản lý Refresh Token JWT (Stateful revocation)';

-- Cập nhật lại seed data users: thêm failed_login_attempts = 0
UPDATE `users` SET `failed_login_attempts` = 0 WHERE `failed_login_attempts` IS NULL;

-- Kiểm tra các roles đã được insert đúng tên (với prefix ROLE_)
INSERT IGNORE INTO `roles` (`id`, `name`, `description`) VALUES
(1, 'ROLE_ADMIN',       'Quản trị viên toàn hệ thống'),
(2, 'ROLE_SALES_STAFF', 'Nhân viên bán hàng tại quầy POS'),
(3, 'ROLE_OPTOMETRIST', 'Kỹ thuật viên đo mắt khúc xạ'),
(4, 'ROLE_LAB_TECH',    'Kỹ thuật viên xưởng gia công mài lắp kính'),
(5, 'ROLE_CUSTOMER',    'Khách hàng mua sắm online');

