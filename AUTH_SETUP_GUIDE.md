# GlassStore Auth & RBAC Setup & Deployment Guide

Hướng dẫn chi tiết từng bước cấu hình môi trường, khởi chạy cơ sở dữ liệu, backend và frontend.

---

## 1. Yêu cầu Hệ thống (Prerequisites)

- **Java JDK**: 21 trở lên (`java -version`).
- **Apache Maven**: 3.9+ (hoặc Maven Wrapper có sẵn trong repo).
- **Node.js**: 18+ & **npm**: 9+ (`node -v`, `npm -v`).
- **MySQL**: 8.0+ (hoặc Docker Desktop).

---

## 2. Bước 1: Khởi động Cơ sở Dữ liệu MySQL

### Cách 1: Sử dụng Docker Compose (Khuyên dùng)
```bash
cd d:\Doanhnghiep\OJTgr4\glass-store-backend
docker compose -f docker-compose.infra.yml up -d mysql-identity
```
Script SQL khởi tạo sẽ tự động chạy file:
- `docker/mysql/init/01-init-databases.sql` (Tạo DB `db_identity`)
- `docker/mysql/init/03-auth-migration.sql` (Cấu trúc bảng users, roles, otp_verifications, refresh_tokens)

### Cách 2: Chạy trực tiếp trên MySQL cục bộ
Nếu bạn cài MySQL cục bộ (Port 3306, user `root`, pass `root`), hãy mở Workbench hoặc DBeaver và chạy:
```sql
SOURCE d:/Doanhnghiep/OJTgr4/glass-store-backend/docker/mysql/init/03-auth-migration.sql;
```

---

## 3. Bước 2: Cấu hình Biến Môi trường (Environment Variables)

Hệ thống Identity Service đã được cấu hình các giá trị mặc định sẵn sàng cho môi trường Dev. Nếu muốn gửi Email OTP thực tế qua Google Gmail SMTP, bạn thiết lập các biến môi trường sau:

```bash
# Windows PowerShell
$env:GMAIL_USERNAME = "your-email@gmail.com"
$env:GMAIL_APP_PASSWORD = "your-google-app-password"
$env:JWT_SECRET = "9a7f8e6d5c4b3a210987654321fedcba0123456789abcdef0123456789abcdef"
```

> **Lưu ý**: Nếu không cấu hình SMTP Gmail, hệ thống vẫn tự động kích hoạt chế độ mô phỏng an toàn (`dev-safe fallback`): mã OTP 6 số sẽ được in ra log console ở mức WARN để kiểm thử nhanh mà không bị gián đoạn.

---

## 4. Bước 3: Khởi chạy Backend Identity Service

```powershell
cd d:\Doanhnghiep\OJTgr4\glass-store-backend\identity-service
mvn spring-boot:run
```
- Identity Service sẽ khởi động tại: `http://localhost:8081`
- Khi service khởi chạy lần đầu, `DataInitializer` sẽ tự động seed các tài khoản thử nghiệm:
  - `admin@glassstore.vn` / mật khẩu `123456` (`ROLE_ADMIN`)
  - `sales@glassstore.vn` / mật khẩu `123456` (`ROLE_SALES_STAFF`)
  - `optometrist@glassstore.vn` / mật khẩu `123456` (`ROLE_OPTOMETRIST`)
  - `lab@glassstore.vn` / mật khẩu `123456` (`ROLE_LAB_TECH`)
  - `customer@glassstore.vn` / mật khẩu `123456` (`ROLE_CUSTOMER`)

---

## 5. Bước 4: Khởi chạy Frontend Customer Web

```powershell
cd d:\Doanhnghiep\OJTgr4\glass-store-customer-web-
npm run dev
```
- Ứng dụng Web Khách hàng chạy tại: `http://localhost:5173`
- Bấm vào icon **Tài Khoản** hoặc nút **Đăng Nhập** trên Header để mở Auth Modal với đầy đủ 3 màn hình: Đăng nhập, Đăng ký và Nhập OTP 6 ô có đếm ngược.

---

## 6. Bước 5: Khởi chạy Frontend Internal Portal (Nhân viên)

```powershell
cd d:\Doanhnghiep\OJTgr4\glass-store-internal-portal
npm run dev
```
- Cổng nhân viên chạy tại: `http://localhost:5174` (hoặc cổng tiếp theo do Vite cấp)
- Màn hình Đăng nhập nhân viên xuất hiện:
  - Đăng nhập tài khoản `sales@glassstore.vn`: Menu chỉ hiện **POS**.
  - Đăng nhập tài khoản `optometrist@glassstore.vn`: Menu chỉ hiện **Phòng Đo Mắt**.
  - Đăng nhập tài khoản `lab@glassstore.vn`: Menu chỉ hiện **Xưởng Lab**.
  - Đăng nhập tài khoản `admin@glassstore.vn`: Menu hiện đầy đủ tất cả các trang.

