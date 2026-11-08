USE db_lab;

SET @scheduled_date_migration = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'db_lab' AND table_name = 'eye_exam_appointments' AND column_name = 'scheduled_date'),
    'SELECT ''scheduled_date already exists'' AS migration_status',
    'ALTER TABLE eye_exam_appointments ADD COLUMN scheduled_date DATE NULL');
PREPARE scheduled_date_statement FROM @scheduled_date_migration;
EXECUTE scheduled_date_statement;
DEALLOCATE PREPARE scheduled_date_statement;

SET @location_code_migration = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'db_lab' AND table_name = 'eye_exam_appointments' AND column_name = 'location_code'),
    'SELECT ''location_code already exists'' AS migration_status',
    'ALTER TABLE eye_exam_appointments ADD COLUMN location_code VARCHAR(32) NULL');
PREPARE location_code_statement FROM @location_code_migration;
EXECUTE location_code_statement;
DEALLOCATE PREPARE location_code_statement;

SET @location_index_migration = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics
            WHERE table_schema = 'db_lab' AND table_name = 'eye_exam_appointments' AND index_name = 'idx_appointments_location_code'),
    'SELECT ''appointment location index already exists'' AS migration_status',
    'CREATE INDEX idx_appointments_location_code ON eye_exam_appointments (location_code)');
PREPARE location_index_statement FROM @location_index_migration;
EXECUTE location_index_statement;
DEALLOCATE PREPARE location_index_statement;
