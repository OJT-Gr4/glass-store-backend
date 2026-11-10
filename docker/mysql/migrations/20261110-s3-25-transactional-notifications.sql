-- Migration S3-25 (GLAS-25): Transactional Notifications with Ownership, DedupKey & Channel Config
USE `db_identity`;

CREATE TABLE IF NOT EXISTS `transaction_notifications` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `dedup_key` VARCHAR(180) NOT NULL,
  `recipient_user_id` BIGINT NOT NULL,
  `recipient_role` VARCHAR(50) NULL,
  `category` VARCHAR(30) NOT NULL,
  `event_type` VARCHAR(60) NOT NULL,
  `reference_type` VARCHAR(40) NULL,
  `reference_id` BIGINT NULL,
  `reference_code` VARCHAR(80) NULL,
  `title` VARCHAR(200) NOT NULL,
  `message` VARCHAR(1000) NOT NULL,
  `action_tab` VARCHAR(80) NULL,
  `action_route` VARCHAR(160) NULL,
  `is_read` TINYINT(1) NOT NULL DEFAULT 0,
  `read_at` DATETIME NULL,
  `in_app_status` VARCHAR(40) NOT NULL DEFAULT 'DELIVERED',
  `sms_status` VARCHAR(40) NOT NULL DEFAULT 'SKIPPED',
  `sms_detail` VARCHAR(300) NULL,
  `email_status` VARCHAR(40) NOT NULL DEFAULT 'SKIPPED',
  `email_detail` VARCHAR(300) NULL,
  `source_service` VARCHAR(60) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_transaction_notifications_dedup` (`dedup_key`),
  INDEX `idx_tx_notif_recipient_created` (`recipient_user_id`, `created_at`),
  INDEX `idx_tx_notif_recipient_read` (`recipient_user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `notification_channel_configs` (
  `id` BIGINT NOT NULL,
  `in_app_enabled` TINYINT(1) NOT NULL DEFAULT 1,
  `sms_enabled` TINYINT(1) NOT NULL DEFAULT 1,
  `email_enabled` TINYINT(1) NOT NULL DEFAULT 0,
  `poll_interval_seconds` INT NOT NULL DEFAULT 15,
  `updated_by` VARCHAR(120) NULL,
  `updated_at` DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO `notification_channel_configs`
  (`id`, `in_app_enabled`, `sms_enabled`, `email_enabled`, `poll_interval_seconds`, `updated_by`)
VALUES
  (1, 1, 1, 0, 15, 'SYSTEM');
