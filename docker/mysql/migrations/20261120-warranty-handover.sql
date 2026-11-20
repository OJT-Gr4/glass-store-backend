ALTER TABLE db_lab.warranties ADD COLUMN delivery_method VARCHAR(20) NULL;
ALTER TABLE db_lab.warranties ADD COLUMN receiving_branch_code VARCHAR(50) NULL;
ALTER TABLE db_lab.warranties ADD COLUMN receiving_branch_name VARCHAR(160) NULL;
ALTER TABLE db_lab.warranties ADD COLUMN receiving_branch_address VARCHAR(500) NULL;
ALTER TABLE db_lab.warranties ADD COLUMN collection_address VARCHAR(500) NULL;
