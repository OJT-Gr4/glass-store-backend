-- Apply only alongside the completed entitlement-aware checkout release.
ALTER TABLE vouchers ADD COLUMN required_tier VARCHAR(16) NOT NULL DEFAULT 'NONE';
CREATE TABLE voucher_grants (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    voucher_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    reserved_order_id BIGINT NULL,
    used_order_id BIGINT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    granted_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_voucher_grant_customer UNIQUE (voucher_id, customer_id),
    CONSTRAINT fk_voucher_grant_voucher FOREIGN KEY (voucher_id) REFERENCES vouchers(id),
    INDEX idx_voucher_grant_customer (customer_id),
    INDEX idx_voucher_grant_reserved_order (reserved_order_id)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
