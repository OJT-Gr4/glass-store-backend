# JWT/RBAC và hợp đồng OMS — Sprint 2, Ngày 3

Phạm vi: S2-D3-01 (Phong) và lỗi nghiệm thu S2-D3-04 (Huy Đức). Backend Java 21. Các quyền được kiểm tra lại tại service, ngoài kiểm tra JWT tại gateway.

## Ma trận quyền

| API / hành động | Quyền |
|---|---|
| Đăng nhập, đăng ký khách hàng, refresh | Công khai theo hợp đồng identity |
| Đọc catalog | Công khai sản phẩm/biến thể ACTIVE; staff được đọc phạm vi nội bộ |
| Cấp staff, đổi trạng thái/xóa staff | ADMIN; không cho đăng ký staff ẩn danh |
| Hồ sơ người dùng | ADMIN/SALES_STAFF/OPTOMETRIST hoặc chính chủ |
| Xem đơn và thanh toán | ADMIN/SALES_STAFF; CUSTOMER chỉ dữ liệu của mình |
| Tạo đơn ONLINE | CUSTOMER dùng customerId từ JWT; staff theo hợp đồng hiện có |
| POS, chuyển trạng thái OMS | ADMIN/SALES_STAFF |
| Xem lịch sử OMS | ADMIN/SALES_STAFF |
| Đọc toa | Staff lâm sàng/bán hàng/Lab; CUSTOMER chỉ toa của mình; service theo hợp đồng nội bộ |
| Lưu toa | ADMIN/OPTOMETRIST; optometristId lấy từ JWT |
| Lịch hẹn | Staff tiếp nhận/lâm sàng; CUSTOMER chỉ lịch của mình, tạo mới ở WAITING |
| Lệnh Lab / QC | ADMIN/LAB_TECH sửa; SALES_STAFF được đọc |
| Đọc tồn / danh sách kho | ADMIN/LAB_TECH/SALES_STAFF; giữ hỗ trợ role LAB cũ |
| Chuyển kho / nhận hàng | ADMIN/LAB_TECH; giữ hỗ trợ role LAB cũ |
| Tra cứu bảo hành | ADMIN/SALES_STAFF; CUSTOMER chỉ phiếu của mình |
| Tạo/cập nhật phiếu sửa chữa | ADMIN/SALES_STAFF/LAB_TECH |
| API nội bộ kho, kích hoạt BH, tạo/tra Lab theo order | Token SERVICE có chữ ký; không dùng token customer/staff để giả danh service |

Route không được khai báo trong phạm vi API bị từ chối. Không token hoặc token không hợp lệ/hết hạn/refresh dùng thay access: 401. Sai vai trò hoặc truy cập đối tượng không thuộc mình: 403.

## Danh tính và quyền sở hữu

- `X-Customer-Id`, `customerId` trong JSON và `actor` trong JSON không cấp quyền truy cập.
- Khách tạo đơn/giỏ/lịch hẹn được gắn với ID trong token. Toa được tham chiếu khi đặt hàng cũng phải thuộc khách đó.
- Actor OMS, người đo, kỹ thuật viên cập nhật QC, nhân viên tiếp nhận sửa chữa và người điều chỉnh kho lấy từ security context.
- Token hợp lệ dùng claims đã ký; thu hồi token tức thời/introspection và cơ chế rotation khóa chưa thuộc phần triển khai này.

## Xác thực nội bộ

Các Feign client dùng cấu hình `ServiceClientSecurity`, tạo JWT có `type=SERVICE`, role `ROLE_SERVICE`, subject `service:<application>` và thời hạn 60 giây. Token này chỉ có quyền trên những route nội bộ được khai báo; không dùng để đọc toàn bộ đơn trên API khách/staff. Gateway chặn các hợp đồng nội bộ không dành cho trình duyệt.

Đặt cùng `JWT_SECRET` cho identity, gateway, catalog, order và lab. Cấu hình phát triển giữ khóa mặc định tương thích với dự án cũ; môi trường triển khai cần cấp khóa riêng qua biến môi trường. Không đưa token hoặc khóa thật vào log/Git.

Kích hoạt quyền lợi dùng `POST /api/warranties/activate`; tạo yêu cầu dịch vụ là hợp đồng khác, không dùng endpoint đó để giả lập kích hoạt.

## Chuyển trạng thái OMS

- Xác minh bước chuyển trước mọi thao tác ghi dữ liệu, khóa order trong giao dịch và lưu audit cùng trạng thái.
- OMS không tự đánh dấu payment SUCCESS. Xác nhận tiền đi qua luồng thanh toán; hoàn tất chỉ khi payment đã SUCCESS.
- Kính thuốc muốn SHIPPING / READY_FOR_PICKUP / COMPLETED phải xác minh đúng order tại Lab và có trạng thái QC_PASSED / READY_FOR_DELIVERY / DELIVERED. Thiếu kết quả hoặc không liên lạc được Lab thì chưa bàn giao.
- Các trạng thái hủy/refund phải đi qua API hủy tương ứng để xử lý kho, quyền lợi và tiền; không thay actor để vượt qua luồng POS.
- Giao diện không đổi trạng thái giả khi API lỗi; không đóng bàn giao/mở nhãn từ nút lưu nếu server chưa xác nhận.

## Kiểm chứng

Các suite `GatewayAccessSecurityTest`, `RbacAuthorizationTest`, `CatalogAccessSecurityTest`, `OrderAccessSecurityTest`, `OmsDay3AcceptanceTest` và `LabAccessSecurityTest` kiểm tra ma trận và quyền sở hữu. JWT hết hạn/refresh/sai chữ ký được thử qua decoder thật. Các test ghi dữ liệu chạy H2 riêng; không reset MySQL phát triển.

Chạy toàn bộ bằng profile test với datasource H2 riêng cho những suite không khai báo datasource trong annotation. Điều này kiểm chứng code và hợp đồng API; không thay thế nghiệm thu triển khai toàn cụm với DB/tài khoản thực tế.
