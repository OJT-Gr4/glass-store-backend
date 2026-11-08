-- S3-05. Review and apply once against the lab schema before deploying the new application.
-- No connection configuration is changed. Run this before 20261108-lab-prescription-snapshots.sql.
-- Existing unknown measurements remain NULL: this migration does not invent clinical data.
USE db_lab;

SET @rx_ddl = IF(EXISTS (
    SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
      AND table_name = 'prescriptions' AND column_name = 'version'),
    'SELECT ''prescriptions.version already exists''',
    'ALTER TABLE prescriptions ADD COLUMN version BIGINT NOT NULL DEFAULT 0');
PREPARE rx_statement FROM @rx_ddl;
EXECUTE rx_statement;
DEALLOCATE PREPARE rx_statement;

SET @rx_ddl = IF(EXISTS (
    SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
      AND table_name = 'prescriptions' AND column_name = 'updated_at'),
    'SELECT ''prescriptions.updated_at already exists''',
    'ALTER TABLE prescriptions ADD COLUMN updated_at DATETIME(6) NULL');
PREPARE rx_statement FROM @rx_ddl;
EXECUTE rx_statement;
DEALLOCATE PREPARE rx_statement;

CREATE TABLE IF NOT EXISTS prescription_revisions (
    revision_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    prescription_id BIGINT NOT NULL,
    version BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    customer_name VARCHAR(255),
    optometrist_id BIGINT,
    optometrist_name VARCHAR(255),
    od_sph DECIMAL(4,2), od_cyl DECIMAL(4,2), od_axis INT, od_add DECIMAL(4,2),
    os_sph DECIMAL(4,2), os_cyl DECIMAL(4,2), os_axis INT, os_add DECIMAL(4,2),
    pd DECIMAL(4,1), notes VARCHAR(500), lens_sku VARCHAR(255), lens_name VARCHAR(255),
    created_at DATETIME(6), actor_id BIGINT, actor_name VARCHAR(255),
    reason VARCHAR(500) NOT NULL, recorded_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_prescription_revision UNIQUE (prescription_id, version)
);

-- Preserve the actual pre-deployment state. Original prescriber is used only for the initial
-- snapshot; all subsequent revision actors come from the authenticated editor's JWT.
INSERT INTO prescription_revisions (
    prescription_id, version, customer_id, customer_name, optometrist_id, optometrist_name,
    od_sph, od_cyl, od_axis, od_add, os_sph, os_cyl, os_axis, os_add, pd,
    notes, lens_sku, lens_name, created_at, actor_id, actor_name, reason, recorded_at
)
SELECT p.id, p.version, p.customer_id, p.customer_name, p.optometrist_id, p.optometrist_name,
    p.od_sph, p.od_cyl, p.od_axis, p.od_add, p.os_sph, p.os_cyl, p.os_axis, p.os_add, p.pd,
    p.notes, p.lens_sku, p.lens_name, p.created_at, p.optometrist_id, p.optometrist_name,
    'Legacy prescription before first controlled update', COALESCE(p.created_at, CURRENT_TIMESTAMP(6))
FROM prescriptions p
WHERE NOT EXISTS (SELECT 1 FROM prescription_revisions r
                  WHERE r.prescription_id = p.id AND r.version = p.version);
