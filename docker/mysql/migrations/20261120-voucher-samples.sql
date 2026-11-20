-- Sample campaigns are not granted automatically. Staff selects recipients in the portal.
INSERT INTO vouchers
    (code, name, active, valid_from, valid_to, min_order_amount, discount_type, discount_value,
     max_discount_amount, channel, members_only, stack_points, per_customer_limit, used_count, required_tier)
SELECT 'WELCOME2027', 'Chào thành viên · Giảm 50.000đ', TRUE, NOW(), '2027-12-31 23:59:59',
    500000, 'FIXED', 50000, 50000, 'ALL', TRUE, TRUE, 1, 0, 'NONE'
WHERE NOT EXISTS (SELECT 1 FROM vouchers WHERE code = 'WELCOME2027');
INSERT INTO vouchers
    (code, name, active, valid_from, valid_to, min_order_amount, discount_type, discount_value,
     max_discount_amount, channel, members_only, stack_points, per_customer_limit, used_count, required_tier)
SELECT 'SILVER2027', 'Đặc quyền hạng Bạc · Giảm 100.000đ', TRUE, NOW(), '2027-12-31 23:59:59',
    1000000, 'FIXED', 100000, 100000, 'ALL', TRUE, TRUE, 1, 0, 'SILVER'
WHERE NOT EXISTS (SELECT 1 FROM vouchers WHERE code = 'SILVER2027');
INSERT INTO vouchers
    (code, name, active, valid_from, valid_to, min_order_amount, discount_type, discount_value,
     max_discount_amount, channel, members_only, stack_points, per_customer_limit, used_count, required_tier)
SELECT 'GOLD2027', 'Đặc quyền hạng Vàng · Giảm 10%', TRUE, NOW(), '2027-12-31 23:59:59',
    1000000, 'PERCENT', 10, 200000, 'ALL', TRUE, TRUE, 1, 0, 'GOLD'
WHERE NOT EXISTS (SELECT 1 FROM vouchers WHERE code = 'GOLD2027');
INSERT INTO vouchers
    (code, name, active, min_order_amount, discount_type, discount_value,
     max_discount_amount, channel, members_only, stack_points, per_customer_limit, used_count, required_tier)
SELECT 'BIGSELL2027', 'Đặc quyền hạng Kim Cương · Giảm 10%', TRUE,
    1000000, 'PERCENT', 10, 500000, 'ALL', TRUE, TRUE, 1, 0, 'DIAMOND'
WHERE NOT EXISTS (SELECT 1 FROM vouchers WHERE code = 'BIGSELL2027');
UPDATE vouchers SET required_tier = 'DIAMOND', per_customer_limit = 1 WHERE code = 'BIGSELL2027';
