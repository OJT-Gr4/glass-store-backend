-- S3-06 adds duplicate-name protection without changing or deleting existing records.
-- Run these checks first; if either query returns rows, resolve the duplicate names
-- with the data owner before applying the corresponding unique index.
SELECT name, COUNT(*) AS duplicate_count
FROM db_catalog.categories
GROUP BY name
HAVING COUNT(*) > 1;

SELECT name, COUNT(*) AS duplicate_count
FROM db_catalog.inventory_locations
GROUP BY name
HAVING COUNT(*) > 1;

ALTER TABLE db_catalog.categories
    ADD CONSTRAINT uk_categories_name UNIQUE (name);

ALTER TABLE db_catalog.inventory_locations
    ADD CONSTRAINT uk_inventory_locations_name UNIQUE (name);

SET @location_area_migration = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'db_catalog' AND table_name = 'inventory_locations' AND column_name = 'area'),
    'SELECT ''area already exists'' AS migration_status',
    'ALTER TABLE db_catalog.inventory_locations ADD COLUMN area VARCHAR(100) NULL');
PREPARE location_area_statement FROM @location_area_migration;
EXECUTE location_area_statement;
DEALLOCATE PREPARE location_area_statement;

SET @location_type_migration = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'db_catalog' AND table_name = 'inventory_locations' AND column_name = 'location_type'),
    'SELECT ''location_type already exists'' AS migration_status',
    'ALTER TABLE db_catalog.inventory_locations ADD COLUMN location_type VARCHAR(16) NULL');
PREPARE location_type_statement FROM @location_type_migration;
EXECUTE location_type_statement;
DEALLOCATE PREPARE location_type_statement;
