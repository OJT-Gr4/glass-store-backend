# S2-D4-02 — Ghi nhận payment an toàn

## Luồng POS

1. POS kiểm tra giá/ưu đãi với server, chọn phương thức, nhập đủ tiền mặt hoặc xác nhận thủ công đã nhận chuyển khoản/thẻ qua máy quầy.
2. Trước POST `/api/orders`, FE lưu toàn bộ payload và UUID `Idempotency-Key` trong sessionStorage theo nhân viên/chi nhánh. Đây là yêu cầu đang xử lý, khác đơn tạm F4.
3. `CheckoutIdempotencyService` chuẩn hóa payload, băm SHA-256. SQL insert-or-lock trên khóa chính `checkout_requests.request_key` buộc cả các request đồng thời chờ cùng một bản ghi. Nội dung/người tạo khác trả 409; cùng nội dung trả đơn đã có, không kiểm tra lại giá mới hay ghi payment mới.
4. Order, payment, voucher/điểm và các outbox được commit trong cùng transaction. Giá/VAT/giảm giá vẫn do server tính theo D3.
5. Tiền mặt ghi `CASH_RECEIVED`; chuyển khoản phải có `manualPaymentConfirmed=true`, ghi `MANUAL_TRANSFER`; thẻ qua máy ghi `CARD_TERMINAL`. Lưu người xác nhận và thời điểm. Đây là xác nhận của thu ngân, không giả lập callback ngân hàng.
6. Sau commit, outbox kho POS gọi catalog bằng khóa gắn với order/payment cũ. Mất phản hồi hoặc kho lỗi không làm mất payment. Retry kho không tạo order/payment mới.
7. FE chỉ xóa giỏ khi nhận được order ID/code. Lỗi xác định 4xx giữ giỏ để sửa. Mất kết nối/5xx hoặc xung đột khóa giữ nguyên yêu cầu, khóa thao tác tạo đơn mới và cung cấp nút thử lại đúng payload/khóa cũ. Tải lại trang trong cùng tab vẫn khôi phục yêu cầu và giỏ.

## Luồng online và callback

- Checkout luôn tạo `PENDING_PAYMENT` + payment `PENDING`; không tự ghi SUCCESS cho VIETQR/CARD/VNPAY. Adapter gateway chưa cấu hình tiếp tục báo `PAYMENT_PROVIDER_NOT_CONFIGURED`.
- `OrderInventoryService` lưu tác vụ RESERVE, COMMIT, RELEASE. Worker chạy sau commit và quét lại mỗi 5 giây; reservationKey luôn là orderCode.
- Chỉ callback đã xác thực chữ ký và đúng số tiền, hoặc thao tác thu tiền mặt của nhân viên, mới chuyển PAID. Callback khóa đơn ngay lần đọc đầu tiên, sau đó đọc payment để tránh snapshot cũ dưới MySQL REPEATABLE READ.
- Trùng provider/transaction trả payment cũ. Callback đồng thời tuần tự theo khóa đơn. Callback thất bại lặp lại dùng lại cùng attempt. Callback thành công chọn đúng payment ID đã xác minh, không chọn nhầm một attempt PENDING khác.
- Thu tiền mặt lặp lại trả đơn cũ. Khi đã nhận tiền, tác vụ kho/lab chạy qua outbox; lỗi dịch vụ không rollback payment đã xác nhận.
- Hủy đơn chưa thu tiền phát RELEASE. Đơn đã xuất kho hoặc có kết quả xuất/giữ kho không chắc chắn chuyển đối soát; không giả định RELEASE có thể hoàn lại hàng đã COMMIT. Hoàn tiền vẫn dùng luồng riêng của nhóm, không đánh dấu đã hoàn khi chưa có xác nhận.

## Trạng thái và đối soát

| Trạng thái kho | Ý nghĩa / hành động |
| --- | --- |
| PENDING | Đã lưu công việc, tự thử lại với cùng khóa. Không thanh toán lại. |
| SYNCED | Kho đã xác nhận thao tác. |
| FAILED | POS gặp lỗi nghiệp vụ không tự retry; kiểm tra lỗi trước khi retry. |
| RECONCILIATION_REQUIRED | Hết 10 lần thử hoặc kết quả cần bù trừ; quản lý kiểm tra order/payment/reservation/movement. Không tự cộng tồn hoặc tạo payment mới. |

GET `/api/orders/{id}` trả `inventorySyncStatus`, `inventorySyncOperation`, `inventorySyncError`. POST `/api/orders/{id}/inventory/retry` thử lại cùng tác vụ. RELEASE cần đối soát bị từ chối tự retry để tránh bù trừ sai. Không có tự động charge/refund qua gateway chưa tích hợp.

```sql
SELECT id, order_code, status, final_amount FROM db_order.orders WHERE id = ?;
SELECT order_id, status, amount, confirmation_mode, confirmed_by, confirmed_at FROM db_order.payments WHERE order_id = ?;
SELECT * FROM db_order.checkout_requests WHERE order_id = ?;
SELECT * FROM db_order.stock_deduction_outbox WHERE order_id = ?;
SELECT * FROM db_order.order_inventory_tasks WHERE order_id = ?;
```

Migration: `docker/mysql/migrations/20261106-payment-safety.sql`. Không backfill hash cho đơn cũ vì không thể khôi phục chắc chắn payload gốc: khóa cũ không có hash bị từ chối và yêu cầu tra cứu đơn.

## Phạm vi bảo đảm

- Cùng khóa là điều kiện bắt buộc để retry checkout; hai khóa khác nhau vẫn là hai ý định mua khác nhau.
- SessionStorage giữ yêu cầu trong tab qua reload; đóng tab/xóa storage phải tra cứu đơn trước khi thu lại tiền.
- Kho POS giữ hợp đồng deduct/cancel đã có; kho online dùng reserve/commit/release đã có. Khi không thể chứng minh đã bù trừ, trạng thái đối soát được giữ cho người phụ trách, không báo thành công giả.
- Test tích hợp dùng database tách biệt và mock biên dịch vụ. Gateway thật không được tích hợp trong task này.
- Catalog khóa reservation ACTIVE khi commit/release để hai lệnh retry chồng nhau không cùng xuất hoặc hoàn một hold hai lần. Chỉ bổ sung khóa vào hợp đồng giữ tồn, không thay đổi điều chuyển kho hay nghiệp vụ Lab.

## Code theo subtask

| Subtask | Điểm xử lý chính |
| --- | --- |
| 2.1 | `CheckoutIdempotencyService.begin/fingerprint`, `CheckoutRequestRepository.claim/lockByKey`, `OrderService.create`, `OrderCheckoutService.checkout`. Claim, order và payment thuộc cùng transaction. |
| 2.2 | `OrderService.create` kiểm tra xác nhận thủ công và lưu audit; `OrderCheckoutService.markPaid/settleCash` khóa đơn; `PaymentService.handleCallback` khóa theo mã đơn, xác minh giao dịch và chọn đúng payment ID. |
| 2.3 | POS dùng `StockOutboxWorker` hiện có, kiểm tra acknowledgement đúng order ID; online dùng `OrderInventoryTask` + `OrderInventoryWorker/Dispatcher`. Hủy đơn và tác vụ RELEASE cùng transaction. Lab dùng outbox sau commit. |
| FE | `usePosPayment` quản lý payload/UUID, khóa request, lưu sessionStorage và retry; `PosPage` xử lý kiểm tra giỏ và kết quả hiển thị; `InventorySyncPanel` hiển thị lỗi và trạng thái đối soát. |

## Clean code D4

- `OrderInventoryWorker` tách bước chuẩn bị hủy, release, reserve, commit, kiểm tra acknowledgement và ghi lỗi thành các hàm riêng.
- `InventoryOperation` và `InventorySyncStatus` gom các giá trị dùng chung, giữ nguyên dữ liệu lưu trong DB/API.
- `CheckoutIdempotencyService` tách chuẩn hóa object/array và trả mã lỗi có cấu trúc `IDEMPOTENCY_CONFLICT`. API client chuyển mã này tới hook; FE không phân tích chuỗi thông báo lỗi.
- `OrderInventoryService` tách tạo task; `StockDeductionService` tách cập nhật trạng thái và retry reservation. Controller bỏ nhánh POS cũ không thể chạy vì đã return phía trên.
- `usePosPayment` giữ toàn bộ vòng đời yêu cầu thanh toán; `PosPage` không còn trực tiếp quản lý khóa idempotency hoặc storage.

## Kết quả kiểm chứng ngày 06/11/2026

- Java 21: toàn bộ **106 test order-pos-service** pass, **35 test catalog-service** pass, không skip. Các database kiểm thử đều là H2 tách biệt (MySQL mode), không chạy create-drop trên MySQL dữ liệu thật.
- Test D4 gồm: 6 checkout đồng thời chỉ một order/payment/outbox; cùng khóa khác payload trả 409; fingerprint chuẩn hóa số; xác nhận thủ công; online chưa xác nhận vẫn PENDING; callback trùng/đồng thời; stock timeout sau khi đã xử lý; retry và hết lượt chuyển đối soát; hủy đơn; Lab lỗi sau payment vẫn giữ tiền đã ghi nhận.
- Test catalog chạy 6 commit hoặc 6 release đồng thời trên cùng hold: một movement, tồn và reserved chính xác.
- Frontend `npm run build` pass. Chrome với API giả lập xác minh mất phản hồi, giữ giỏ, reload rồi replay đúng khóa/payload; lỗi 400 vẫn giữ giỏ; chuyển khoản phải tick xác nhận và gửi `manualPaymentConfirmed=true`.
- Sau đó đã chạy 5 backend với MySQL Docker 8.0 trên cổng 3307 và áp dụng migration D4 local. Kiểm tra thật qua gateway: đăng nhập admin, đọc 15 sản phẩm, 21 SKU tồn kho, 30 đơn và 6 toa; Chrome thêm sản phẩm vào giỏ và lấy báo giá thành công, không tạo đơn mới. Các test đồng thời/lỗi dịch vụ phía trên vẫn dùng H2 và mock biên dịch vụ; chưa thực hiện checkout UAT trên MySQL. Gateway thật chưa cấu hình; kiểm thử callback sử dụng adapter giả lập có kết quả xác minh kiểm soát được.
- Nhánh làm việc: `feature/dat-tien-d4-payment-safety` trong backend và internal portal.
