-- Additive schema change only; connection configuration is unchanged.
-- Review and apply once to db_lab before deploying the new appointment workflow.
USE db_lab;
ALTER TABLE eye_exam_appointments ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE eye_exam_appointments ADD COLUMN prescription_id BIGINT NULL;
