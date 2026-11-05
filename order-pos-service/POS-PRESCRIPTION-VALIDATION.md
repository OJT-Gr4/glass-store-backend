# POS: kiểm tra toa, giá và voucher trước thanh toán

Phạm vi: FR-POS-07/16/17, FR-EYE-10. Không thay đổi API hoặc entity của lab-service.

## Hợp đồng tạo đơn tại quầy

`POST /api/orders`, gửi Bearer token thu ngân/Admin và `Idempotency-Key` ổn định khi thử lại:

```json
{
  "channel": "POS",
  "customerId": 10,
  "customerName": "Tên khách",
  "customerPhone": "0901234567",
  "branchCode": "BRANCH_01",
  "paymentMethod": "CASH",
  "cashReceived": 2000000,
  "voucherCode": "POS10",
  "pointsToUse": 0,
  "items": [
    {"skuId": "SKU-GONG", "quantity": 1, "expectedUnitPrice": 1000000},
    {"skuId": "SKU-TRONG", "quantity": 1, "expectedUnitPrice": 500000, "prescriptionId": 7}
  ]
}
```

SKU và giá trong ví dụ phải thay bằng catalog thực tế. `unitPrice` cũ vẫn được đọc như alias của `expectedUnitPrice`. Giá dự kiến bắt buộc khi thanh toán POS; không dùng nó làm giá tính tiền. Không cần gửi tổng tiền, thuế, trạng thái hay số tiền giảm: server tự tính.

## Thứ tự xử lý

1. Xác thực thu ngân, kiểm tra yêu cầu lặp theo idempotency key.
2. Đọc catalog thật: SKU phải tồn tại, sản phẩm/biến thể ACTIVE. Lấy tên, loại hàng và giá từ server.
3. Nếu có toa: gọi `GET /api/prescriptions/{id}` của lab-service, kiểm tra đúng ID và đúng `customerId`. Khách vãng lai không được dùng toa của tài khoản khác. Chỉ dòng LENS được gắn toa. Mỗi đơn một toa; đơn có toa cần có tròng kính. Order lưu ID toa chung, OrderItem lưu ID trên dòng tròng.
4. Khi thanh toán, giá dự kiến thiếu hoặc khác catalog trả 409; không tự chấp nhận thay đổi giá. Quote chỉ báo giá, không trừ quyền lợi.
5. Kiểm tra lại voucher có khóa: hiệu lực, kênh, đơn tối thiểu, lượt dùng, điều kiện thành viên và kết hợp điểm. Dùng chính sách voucher/điểm hiện có; không thêm mã giảm giá cố định trong code.
6. Tính tiền hàng → trừ voucher → trừ điểm → VAT 8% → tổng. Kiểm tra tồn chi nhánh và tiền khách đưa trước khi lưu.
7. Lưu đơn/thanh toán/quyền lợi trong transaction. Lệnh kho và lab dùng outbox của nhóm; retry không tạo thêm đơn hay lệnh lab.

## Khi có lỗi

- Toa sai khách/ID không hợp lệ, SKU sai/inactive, voucher sai: từ chối trước khi lưu đơn/trừ quyền lợi.
- Lab trả 404: báo dữ liệu không tồn tại. Lab timeout/5xx hoặc lỗi kết nối: từ chối an toàn với 503, giữ giỏ để thử lại. Endpoint lab hiện có thể trả 500 khi không tìm thấy toa; trường hợp đó cũng không được thanh toán.
- Giá thay đổi: bấm **Cập nhật giá / tồn kho**, xác nhận cập nhật, kiểm tra tổng mới rồi nhập tiền và thanh toán lại. Giữ nguyên SKU/số lượng/khách/toa.
- API báo lỗi không xóa giỏ. Chỉ xóa sau khi API trả đơn đã lưu có ID và mã đơn.

## Kiểm thử

- 28 test tập trung: ownership qua HTTP mock, định danh sai/mất kết nối, metadata SKU, giá sai/thiếu, voucher bị vô hiệu giữa quote và checkout, tổng tiền giả, lưu/đọc lại toa/khách, idempotency và lab retry.
- Frontend build; browser kiểm tra F4 và giữ giỏ/toa/khách sau phản hồi giả lập 400/409/503, cập nhật giá mà không mất số lượng.
- Toàn bộ test DB dùng datasource H2, không dùng cấu hình MySQL mặc định.
- Test `OnlinePrescriptionCheckoutTest` còn lỗi trên bản dev gốc `1dab722` (toa khách tự nhập và seed test); không thuộc luồng chọn toa POS này. Chưa kết luận toàn bộ backend suite đạt.
