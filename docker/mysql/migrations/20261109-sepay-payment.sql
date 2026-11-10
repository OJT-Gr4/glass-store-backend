-- GLAS-2: additive migration; existing payments remain unchanged. Safe to rerun.
SET @sepay_column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = 'db_order' AND table_name = 'payments'
      AND column_name = 'provider_order_id'
);
SET @sepay_ddl = IF(@sepay_column_exists = 0,
    'ALTER TABLE db_order.payments ADD COLUMN provider_order_id VARCHAR(120) NULL',
    'SELECT 1');
PREPARE sepay_migration FROM @sepay_ddl;
EXECUTE sepay_migration;
DEALLOCATE PREPARE sepay_migration;
