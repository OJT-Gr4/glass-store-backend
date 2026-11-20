-- Add columns only when absent (deployment runner checks information_schema first).
ALTER TABLE db_order.loyalty_accounts
 ADD COLUMN protection_started_at DATETIME(6) NULL,
 ADD COLUMN last_activity_at DATETIME(6) NULL,
 ADD COLUMN next_decay_at DATETIME(6) NULL,
 ADD COLUMN baseline_tier_points BIGINT NULL,
 ADD COLUMN tier_points BIGINT NULL,
 ADD COLUMN tier_code VARCHAR(20) NULL,
 ADD COLUMN tier_protected_until DATETIME(6) NULL;
ALTER TABLE db_order.loyalty_entries
 ADD COLUMN event_key VARCHAR(160) NULL,
 ADD COLUMN cycle_at DATETIME(6) NULL,
 ADD COLUMN notice_status VARCHAR(20) NULL,
 ADD COLUMN notification_sent_at DATETIME(6) NULL,
 ADD COLUMN voided BIT NOT NULL DEFAULT 0,
 ADD UNIQUE INDEX uk_loyalty_event_key(event_key);
-- Preserve all balances. Existing customers start a fresh grace period at rollout.
SET @policy_start = CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+07:00');
UPDATE db_order.loyalty_accounts SET
 protection_started_at=@policy_start, next_decay_at=DATE_ADD(@policy_start, INTERVAL 6 MONTH),
 baseline_tier_points=balance, tier_points=balance,
 tier_code=CASE WHEN balance>=10000 THEN 'DIAMOND' WHEN balance>=5000 THEN 'GOLD' WHEN balance>=1000 THEN 'SILVER' ELSE 'MEMBER' END,
 tier_protected_until=DATE_ADD(@policy_start, INTERVAL 12 MONTH)
WHERE protection_started_at IS NULL;
