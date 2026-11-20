-- Lens-only fabrication legitimately has no frame. Match the nullable LabJob mapping.
ALTER TABLE db_lab.lab_jobs MODIFY COLUMN frame_sku VARCHAR(100) NULL;
