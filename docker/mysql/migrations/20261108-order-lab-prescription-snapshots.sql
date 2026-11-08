-- Apply once AFTER 20261108-prescription-revisions.sql, before enabling controlled edits.
-- No connection settings are changed. Run during a maintenance window after backup.
-- If Hibernate has already added a column, omit that ADD COLUMN before applying.
-- Legacy rows can only capture the last stored measurements; earlier unrecorded edits
-- cannot be reconstructed. Review legacy fabrication records before release.
ALTER TABLE db_order.orders ADD COLUMN rx_version BIGINT NULL;

ALTER TABLE db_lab.lab_jobs
    ADD COLUMN rx_version BIGINT NULL,
    ADD COLUMN rx_od_sph DECIMAL(4,2) NULL,
    ADD COLUMN rx_od_cyl DECIMAL(4,2) NULL,
    ADD COLUMN rx_od_axis INT NULL,
    ADD COLUMN rx_od_add DECIMAL(4,2) NULL,
    ADD COLUMN rx_os_sph DECIMAL(4,2) NULL,
    ADD COLUMN rx_os_cyl DECIMAL(4,2) NULL,
    ADD COLUMN rx_os_axis INT NULL,
    ADD COLUMN rx_os_add DECIMAL(4,2) NULL,
    ADD COLUMN rx_pd DECIMAL(4,1) NULL,
    ADD COLUMN rx_notes VARCHAR(500) NULL;

UPDATE db_lab.lab_jobs j JOIN db_lab.prescriptions p ON p.id=j.prescription_id
SET j.rx_version=p.version, j.rx_od_sph=p.od_sph, j.rx_od_cyl=p.od_cyl,
    j.rx_od_axis=p.od_axis, j.rx_od_add=p.od_add, j.rx_os_sph=p.os_sph,
    j.rx_os_cyl=p.os_cyl, j.rx_os_axis=p.os_axis, j.rx_os_add=p.os_add,
    j.rx_pd=p.pd, j.rx_notes=p.notes
WHERE j.rx_version IS NULL;

UPDATE db_order.orders o JOIN db_lab.prescriptions p ON p.id=o.prescription_id AND p.customer_id=o.customer_id
SET o.rx_version=p.version, o.rx_source='CLINICAL_REVISION',
    o.rx_od_sph=p.od_sph, o.rx_od_cyl=p.od_cyl, o.rx_od_axis=p.od_axis, o.rx_od_add=p.od_add,
    o.rx_os_sph=p.os_sph, o.rx_os_cyl=p.os_cyl, o.rx_os_axis=p.os_axis, o.rx_os_add=p.os_add, o.rx_pd=p.pd
WHERE o.rx_version IS NULL AND o.rx_source IS NULL;
