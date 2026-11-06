-- Apply to db_order before deploying S2-D4-02. Safe to run again.
USE db_order;

CREATE TABLE IF NOT EXISTS checkout_requests (
    request_key VARCHAR(100) NOT NULL PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    owner VARCHAR(160) NOT NULL,
    order_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_checkout_order (order_id)
);

CREATE TABLE IF NOT EXISTS order_inventory_tasks (
    order_id BIGINT NOT NULL PRIMARY KEY,
    operation VARCHAR(20) NOT NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    reserve_started BIT NOT NULL DEFAULT 0,
    reserve_confirmed BIT NOT NULL DEFAULT 0,
    commit_started BIT NOT NULL DEFAULT 0,
    commit_confirmed BIT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) NULL,
    updated_at DATETIME(6) NULL,
    INDEX idx_inventory_task_pending (status, updated_at)
);

-- MySQL does not support ADD COLUMN IF NOT EXISTS on every supported version.
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='payments' AND column_name='confirmation_mode'), 'SELECT 1', 'ALTER TABLE payments ADD COLUMN confirmation_mode VARCHAR(40) NULL');
PREPARE migration FROM @ddl; EXECUTE migration; DEALLOCATE PREPARE migration;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='payments' AND column_name='confirmed_by'), 'SELECT 1', 'ALTER TABLE payments ADD COLUMN confirmed_by VARCHAR(120) NULL');
PREPARE migration FROM @ddl; EXECUTE migration; DEALLOCATE PREPARE migration;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='payments' AND column_name='confirmed_at'), 'SELECT 1', 'ALTER TABLE payments ADD COLUMN confirmed_at DATETIME(6) NULL');
PREPARE migration FROM @ddl; EXECUTE migration; DEALLOCATE PREPARE migration;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='payments' AND column_name='payment_reference'), 'SELECT 1', 'ALTER TABLE payments ADD COLUMN payment_reference VARCHAR(120) NULL');
PREPARE migration FROM @ddl; EXECUTE migration; DEALLOCATE PREPARE migration;
