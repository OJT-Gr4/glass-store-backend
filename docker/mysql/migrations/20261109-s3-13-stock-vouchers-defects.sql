-- Additive GLAS-13 inventory schema. Existing quantities and movement history are preserved.

SET @inventory_quarantine_column = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'db_catalog' AND table_name = 'inventory_balances'
              AND column_name = 'quarantined_quantity'),
    'SELECT ''quarantined_quantity already exists'' AS migration_status',
    'ALTER TABLE db_catalog.inventory_balances
       ADD COLUMN quarantined_quantity INT NOT NULL DEFAULT 0,
       ADD CONSTRAINT chk_inventory_balance_quarantine
         CHECK (quarantined_quantity >= 0 AND quarantined_quantity <= quantity)');
PREPARE inventory_quarantine_statement FROM @inventory_quarantine_column;
EXECUTE inventory_quarantine_statement;
DEALLOCATE PREPARE inventory_quarantine_statement;

CREATE TABLE IF NOT EXISTS db_catalog.suppliers (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(160) NOT NULL,
    contact_person VARCHAR(160) NULL,
    phone VARCHAR(40) NULL,
    email VARCHAR(254) NULL,
    address VARCHAR(500) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_suppliers_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS db_catalog.stock_vouchers (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    voucher_code VARCHAR(48) NOT NULL,
    voucher_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    location_id BIGINT NOT NULL,
    supplier_id BIGINT NULL,
    reason VARCHAR(500) NOT NULL,
    reference_text VARCHAR(120) NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    created_by VARCHAR(120) NOT NULL,
    decided_by VARCHAR(120) NULL,
    decision_reason VARCHAR(500) NULL,
    decided_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT uk_stock_vouchers_code UNIQUE (voucher_code),
    CONSTRAINT uk_stock_vouchers_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_stock_vouchers_location FOREIGN KEY (location_id)
        REFERENCES db_catalog.inventory_locations (id),
    CONSTRAINT fk_stock_vouchers_supplier FOREIGN KEY (supplier_id)
        REFERENCES db_catalog.suppliers (id),
    CONSTRAINT chk_stock_voucher_type
        CHECK (voucher_type IN ('RECEIPT', 'ISSUE', 'QUARANTINE')),
    CONSTRAINT chk_stock_voucher_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    INDEX idx_stock_voucher_location_status (location_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS db_catalog.stock_voucher_items (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    voucher_id BIGINT NOT NULL,
    variant_id BIGINT NOT NULL,
    sku VARCHAR(100) NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    quantity INT NOT NULL,
    quarantined_quantity INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_stock_voucher_sku UNIQUE (voucher_id, sku),
    CONSTRAINT fk_stock_voucher_items_voucher FOREIGN KEY (voucher_id)
        REFERENCES db_catalog.stock_vouchers (id),
    CONSTRAINT fk_stock_voucher_items_variant FOREIGN KEY (variant_id)
        REFERENCES db_catalog.product_variants (id),
    CONSTRAINT chk_stock_voucher_item_quantity
        CHECK (quantity > 0 AND quarantined_quantity >= 0 AND quarantined_quantity <= quantity),
    INDEX idx_stock_voucher_items_sku (sku)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS db_catalog.inventory_movements (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    movement_type VARCHAR(32) NOT NULL,
    sku VARCHAR(100) NOT NULL,
    source_location_code VARCHAR(32) NULL,
    destination_location_code VARCHAR(32) NULL,
    reference_type VARCHAR(32) NOT NULL,
    reference_id VARCHAR(100) NOT NULL,
    quantity INT NULL,
    performed_by VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @inventory_movement_reference_index = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics
            WHERE table_schema = 'db_catalog' AND table_name = 'inventory_movements'
              AND index_name = 'idx_inventory_movement_reference'),
    'SELECT ''inventory movement reference index already exists'' AS migration_status',
    'CREATE INDEX idx_inventory_movement_reference
       ON db_catalog.inventory_movements (reference_type, reference_id)');
PREPARE inventory_movement_reference_statement FROM @inventory_movement_reference_index;
EXECUTE inventory_movement_reference_statement;
DEALLOCATE PREPARE inventory_movement_reference_statement;
