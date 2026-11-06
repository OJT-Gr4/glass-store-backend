-- Fresh development database only. Opening balances, not a restock/reset script.
SET NAMES utf8mb4;
USE db_catalog;

CREATE TABLE inventory_locations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_balances (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    variant_id BIGINT NOT NULL,
    location_id BIGINT NOT NULL,
    quantity INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_inventory_variant_location UNIQUE (variant_id, location_id),
    CONSTRAINT fk_opening_balance_variant FOREIGN KEY (variant_id) REFERENCES product_variants(id),
    CONSTRAINT fk_opening_balance_location FOREIGN KEY (location_id) REFERENCES inventory_locations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO inventory_locations(code,name,active) VALUES
('BRANCH_01','Chi nhánh 01',TRUE),
('BRANCH_02','Chi nhánh 02',TRUE),
('LAB','Tổng kho Lab',TRUE);

INSERT INTO inventory_balances(variant_id,location_id,quantity)
SELECT v.id,l.id,
  CASE l.code
    WHEN 'BRANCH_01' THEN LEAST(5,GREATEST(v.stock_quantity,0))
    WHEN 'BRANCH_02' THEN LEAST(5,GREATEST(v.stock_quantity-5,0))
    ELSE GREATEST(v.stock_quantity-10,0)
  END
FROM product_variants v CROSS JOIN inventory_locations l;
