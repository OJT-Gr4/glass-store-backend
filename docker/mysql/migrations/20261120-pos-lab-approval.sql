-- POS review is explicit; existing rows are not automatically approved.
ALTER TABLE db_order.orders ADD COLUMN lab_confirmed_at DATETIME NULL;
ALTER TABLE db_order.orders ADD COLUMN lab_confirmed_by VARCHAR(120) NULL;
-- A POS-reviewed order snapshot can be used without inventing a clinical examination.
ALTER TABLE db_lab.lab_jobs MODIFY COLUMN prescription_id BIGINT NULL;
