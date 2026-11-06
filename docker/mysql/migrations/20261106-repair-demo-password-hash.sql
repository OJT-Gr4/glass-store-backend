-- Repairs only the known, incorrect historical DEMO password fixture.
-- Does not reset custom passwords or accounts outside the canonical demo list.
USE db_identity;
START TRANSACTION;
UPDATE users
SET password_hash = '$2a$10$C6ujiQrjW/xUIVpiW0u7xuxeW2nYMd6.GoB3NacC14UG/6R9MQpom'
WHERE password_hash = '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi'
  AND (username,email) IN (
    ('admin','adminoptic@gmail.com'),('manager_nam','nam.le@optivision.vn'),
    ('staff','posoptic@gmail.com'),('staff_ha','ha.le@optivision.vn'),('staff_vy','vy.nguyen@optivision.vn'),
    ('optometrist','optometryoptic@gmail.com'),('optom_phuc','phuc.lam@optivision.vn'),
    ('labtech','laboptic@gmail.com'),('lab_dinh','dinh.nguyen@optivision.vn'),
    ('customer','customer@gmail.com'),('cus_kieu','thuykieu.le@gmail.com'),
    ('cus_quang','quang.trinh@gmail.com'),('cus_ngoc','bichngoc.pham@gmail.com'),('cus_kiet','kiet.do@gmail.com')
  );
SELECT ROW_COUNT() AS repaired_demo_accounts;
COMMIT;