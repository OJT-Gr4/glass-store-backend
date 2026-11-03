# GlassStore RBAC (Role-Based Access Control) Matrix

Tài liệu ma trận phân quyền chi tiết giữa các vai trò người dùng trong hệ thống GlassStore.

---

## 1. Định nghĩa các Vai trò (Roles)

| Mã Vai Trò (Database) | Tên Hiển Thị | Đối tượng | Mô tả nhiệm vụ |
|---|---|---|---|
| `ROLE_CUSTOMER` | Khách Hàng | Người tiêu dùng | Đặt hàng, tra cứu toa khúc xạ cá nhân, bảo hành, theo dõi đơn kính. |
| `ROLE_SALES_STAFF` | Nhân Viên Bán Hàng | Nhân viên Showroom | Thao tác trên POS, quét barcode, tạo đơn bán lẻ, thu tiền hóa đơn. |
| `ROLE_OPTOMETRIST` | Kỹ Thuật Viên Khúc Xạ | Bác sĩ/KTV Khúc xạ | Đo khám mắt chuẩn nhãn khoa, lập toa kính OD/OS, chuyển dữ liệu cho Lab. |
| `ROLE_LAB_TECH` | Kỹ Thuật Viên Mài Lắp Lab | Thợ xưởng kính | Nhận lệnh mài lắp từ toa khúc xạ, xử lý mài kính tự động, kiểm định QC. |
| `ROLE_ADMIN` | Quản Trị Viên Hệ Thống | Giám đốc / Admin | Toàn quyền kiểm soát hệ thống, báo cáo doanh thu, tồn kho và cấu hình. |

---

## 2. Ma trận Phân quyền Chức năng & API (Permission Matrix)

> **Ký hiệu**:
> - ✅ **ALLOW**: Được phép truy cập hoàn toàn.
> - ⛔ **DENY (HTTP 403)**: Bị chặn tại Backend Spring Security và ẩn khỏi giao diện Frontend.
> - ❌ **N/A**: Không thuộc phạm vi áp dụng.

| Nhóm Tài Nguyên | Chức năng / API Endpoint | ROLE_CUSTOMER | ROLE_SALES_STAFF | ROLE_OPTOMETRIST | ROLE_LAB_TECH | ROLE_ADMIN |
|---|---|:---:|:---:|:---:|:---:|:---:|
| **Xác thực chung** | Đăng ký & Gửi OTP | ✅ ALLOW | ❌ N/A | ❌ N/A | ❌ N/A | ✅ ALLOW |
| | Xác thực OTP & Kích hoạt | ✅ ALLOW | ❌ N/A | ❌ N/A | ❌ N/A | ✅ ALLOW |
| | Đăng nhập tài khoản | ✅ ALLOW | ✅ ALLOW | ✅ ALLOW | ✅ ALLOW | ✅ ALLOW |
| | Refresh Token & Đăng xuất | ✅ ALLOW | ✅ ALLOW | ✅ ALLOW | ✅ ALLOW | ✅ ALLOW |
| **Bán Lẻ Tại Quầy (POS)** | Truy cập Giao diện POS | ⛔ DENY | ✅ ALLOW | ⛔ DENY | ⛔ DENY | ✅ ALLOW |
| | `GET /api/pos/status` | ⛔ HTTP 403 | ✅ HTTP 200 | ⛔ HTTP 403 | ⛔ HTTP 403 | ✅ HTTP 200 |
| | Tạo đơn hàng POS | ⛔ HTTP 403 | ✅ HTTP 200 | ⛔ HTTP 403 | ⛔ HTTP 403 | ✅ HTTP 200 |
| **Phòng Đo Khám Khúc Xạ** | Truy cập Giao diện Khám Mắt | ⛔ DENY | ⛔ DENY | ✅ ALLOW | ⛔ DENY | ✅ ALLOW |
| | `GET /api/optometry/exams` | ⛔ HTTP 403 | ⛔ HTTP 403 | ✅ HTTP 200 | ⛔ HTTP 403 | ✅ HTTP 200 |
| | Nhập đơn kính OD/OS (Prescription) | ⛔ HTTP 403 | ⛔ HTTP 403 | ✅ HTTP 200 | ⛔ HTTP 403 | ✅ HTTP 200 |
| **Xưởng Mài Lắp Lab & QC** | Truy cập Giao diện Xưởng Lab | ⛔ DENY | ⛔ DENY | ⛔ DENY | ✅ ALLOW | ✅ ALLOW |
| | `GET /api/lab/queue` | ⛔ HTTP 403 | ⛔ HTTP 403 | ⛔ HTTP 403 | ✅ HTTP 200 | ✅ HTTP 200 |
| | Cập nhật tiến độ & Xác nhận QC | ⛔ HTTP 403 | ⛔ HTTP 403 | ⛔ HTTP 403 | ✅ HTTP 200 | ✅ HTTP 200 |
| **Quản trị & Doanh thu** | Truy cập Báo cáo & Tồn kho | ⛔ DENY | ⛔ DENY | ⛔ DENY | ⛔ DENY | ✅ ALLOW |
| | `GET /api/inventory-revenue/report`| ⛔ HTTP 403 | ⛔ HTTP 403 | ⛔ HTTP 403 | ⛔ HTTP 403 | ✅ HTTP 200 |
| | Quản lý Tài khoản & Phân quyền | ⛔ HTTP 403 | ⛔ HTTP 403 | ⛔ HTTP 403 | ⛔ HTTP 403 | ✅ HTTP 200 |

---

## 3. Nguyên tắc Bảo mật 2 Lớp (Dual-Layer Defense)

1. **Lớp 1 - Frontend Guard**:
   - Menu Sidebar chỉ render các tính năng tương ứng với danh sách `roles` trích xuất từ JWT token.
   - Khi cố tình truy cập tab trái quyền, màn hình sẽ hiển thị thẻ **403 Forbidden Card** màu đỏ cảnh báo.
2. **Lớp 2 - Backend Security (@PreAuthorize)**:
   - Tất cả request gọi tới API nghiệp vụ đều bị chặn ngay tại `SecurityConfig` và Annotation `@PreAuthorize("hasRole('...')")`.
   - Bất kể người dùng can thiệp Client-side hay gửi request bằng Postman/cURL, Backend sẽ luôn từ chối bằng mã **HTTP 403 Forbidden** nếu thiếu quyền.

