# GlassStore Authentication & Authorization API Documentation

Tài liệu chi tiết kỹ thuật cho module **Identity & Authentication Service** của hệ thống bán kính mắt đa kênh GlassStore.

---

## 1. Tổng quan Kiến trúc

- **Framework**: Spring Boot 3.4.3 (Java 21), Spring Security 6.
- **Cơ chế xác thực**: Stateless JWT (Access Token 24h, Refresh Token 7 ngày).
- **Mã hóa mật khẩu**: BCrypt (`PasswordEncoder`).
- **Bảo vệ Brute Force**: Tự động khóa tài khoản 15 phút sau 5 lần nhập sai mật khẩu liên tiếp.
- **Cổng phân phối**:
  - Trực tiếp qua Identity Service: Port `8081`
  - Qua API Gateway: Port `8080/api/...`

---

## 2. Danh sách Endpoints Khách hàng & Xác thực chung

### 2.1. Đăng ký tài khoản (Register)
Khởi tạo tài khoản khách hàng ở trạng thái `PENDING` và sinh mã OTP 6 số gửi qua Email.

- **URL**: `POST /api/auth/register`
- **Quyền truy cập**: Public (Không cần token)
- **Request Body (JSON)**:
  ```json
  {
    "email": "customer@example.com",
    "password": "Password123@",
    "fullName": "Nguyễn Văn A",
    "phoneNumber": "0912345678"
  }
  ```
- **Response Success (200 OK)**:
  ```json
  {
    "success": true,
    "message": "Đăng ký thành công! Vui lòng kiểm tra email để lấy mã OTP xác thực tài khoản.",
    "data": {
      "id": 1,
      "email": "customer@example.com",
      "fullName": "Nguyễn Văn A",
      "phoneNumber": "0912345678",
      "status": "PENDING",
      "roles": ["ROLE_CUSTOMER"]
    }
  }
  ```
- **Error Responses**:
  - `400 Bad Request`: Email sai định dạng, mật khẩu ngắn hơn 6 ký tự hoặc email đã được kích hoạt trước đó.

---

### 2.2. Xác thực OTP (Verify OTP)
Xác thực mã OTP 6 số để kích hoạt tài khoản `PENDING` thành `ACTIVE`. Tự động cấp Access Token và Refresh Token ngay sau khi kích hoạt.

- **URL**: `POST /api/auth/verify-otp`
- **Quyền truy cập**: Public
- **Request Body (JSON)**:
  ```json
  {
    "email": "customer@example.com",
    "otp": "123456"
  }
  ```
- **Response Success (200 OK)**:
  ```json
  {
    "success": true,
    "message": "Xác thực OTP thành công! Tài khoản đã được kích hoạt.",
    "data": {
      "accessToken": "eyJhbGciOiJIUzI1NiIsIn...",
      "refreshToken": "4a7b9c1d-...",
      "tokenType": "Bearer",
      "expiresIn": 86400000,
      "user": {
        "id": 1,
        "email": "customer@example.com",
        "fullName": "Nguyễn Văn A",
        "status": "ACTIVE",
        "roles": ["ROLE_CUSTOMER"]
      }
    }
  }
  ```
- **Error Responses**:
  - `400 Bad Request`: Mã OTP sai hoặc đã hết hạn (> 5 phút).

---

### 2.3. Gửi lại OTP (Resend OTP)
Tạo mã OTP mới và vô hiệu hóa OTP cũ.

- **URL**: `POST /api/auth/resend-otp`
- **Quyền truy cập**: Public
- **Request Body (JSON)**:
  ```json
  {
    "email": "customer@example.com"
  }
  ```
- **Response Success (200 OK)**:
  ```json
  {
    "success": true,
    "message": "Mã OTP mới đã được gửi tới email của bạn."
  }
  ```

---

### 2.4. Đăng nhập (Login)
Áp dụng cho cả Khách hàng và Nhân viên nội bộ.

- **URL**: `POST /api/auth/login`
- **Quyền truy cập**: Public
- **Request Body (JSON)**:
  ```json
  {
    "email": "sales@glassstore.vn",
    "password": "Password123@"
  }
  ```
- **Response Success (200 OK)**:
  ```json
  {
    "success": true,
    "message": "Đăng nhập thành công!",
    "data": {
      "accessToken": "eyJhbGciOiJIUzI1NiIsIn...",
      "refreshToken": "8f3b1e2a-...",
      "tokenType": "Bearer",
      "expiresIn": 86400000,
      "user": {
        "id": 2,
        "email": "sales@glassstore.vn",
        "fullName": "Nguyễn Bán Hàng",
        "status": "ACTIVE",
        "roles": ["ROLE_SALES_STAFF"]
      }
    }
  }
  ```
- **Error Responses**:
  - `401 Unauthorized`: Sai mật khẩu (kèm bộ đếm lỗi).
  - `403 Forbidden`: 
    - Tài khoản `PENDING` (chưa nhập OTP).
    - Tài khoản `LOCKED` (nhập sai 5 lần, bị khóa trong vòng 15 phút).

---

### 2.5. Làm mới Token (Refresh Token)
Cấp mới Access Token khi token cũ hết hạn bằng Refresh Token hợp lệ trong database.

- **URL**: `POST /api/auth/refresh-token`
- **Quyền truy cập**: Public
- **Request Body (JSON)**:
  ```json
  {
    "refreshToken": "8f3b1e2a-..."
  }
  ```
- **Response Success (200 OK)**:
  ```json
  {
    "success": true,
    "message": "Cấp mới Access Token thành công!",
    "data": {
      "accessToken": "eyJhbGciOiJIUzI1NiIsIn...",
      "refreshToken": "8f3b1e2a-...",
      "tokenType": "Bearer",
      "expiresIn": 86400000
    }
  }
  ```
- **Error Responses**:
  - `400 Bad Request` / `403 Forbidden`: Refresh token bị thu hồi hoặc đã hết hạn.

---

### 2.6. Đăng xuất (Logout)
Thu hồi Refresh Token và xóa phiên đăng nhập.

- **URL**: `POST /api/auth/logout`
- **Quyền truy cập**: Bearer Token
- **Request Body (JSON)**:
  ```json
  {
    "refreshToken": "8f3b1e2a-..."
  }
  ```
- **Response Success (200 OK)**:
  ```json
  {
    "success": true,
    "message": "Đăng xuất thành công, token đã được thu hồi."
  }
  ```

---

### 2.7. Thông tin tài khoản hiện tại (Get Current User)
- **URL**: `GET /api/auth/me`
- **Quyền truy cập**: Bearer Token (`ROLE_CUSTOMER`, `ROLE_ADMIN`, `ROLE_SALES_STAFF`, `ROLE_OPTOMETRIST`, `ROLE_LAB_TECH`)
- **Headers**: `Authorization: Bearer <accessToken>`
- **Response Success (200 OK)**:
  ```json
  {
    "success": true,
    "data": {
      "id": 2,
      "email": "sales@glassstore.vn",
      "fullName": "Nguyễn Bán Hàng",
      "status": "ACTIVE",
      "roles": ["ROLE_SALES_STAFF"]
    }
  }
  ```

---

## 3. Danh sách Endpoints RBAC Phân quyền Nội bộ

Các API được kiểm tra quyền hạn nghiêm ngặt ở tầng Backend thông qua Spring Security `@PreAuthorize`:

| Phương thức | Endpoint | Vai trò cho phép | Hành vi khi vi phạm quyền |
|---|---|---|---|
| `GET` | `/api/pos/status` | `ROLE_ADMIN`, `ROLE_SALES_STAFF` | **HTTP 403 Forbidden** |
| `GET` | `/api/optometry/exams` | `ROLE_ADMIN`, `ROLE_OPTOMETRIST` | **HTTP 403 Forbidden** |
| `GET` | `/api/lab/queue` | `ROLE_ADMIN`, `ROLE_LAB_TECH` | **HTTP 403 Forbidden** |
| `GET` | `/api/inventory-revenue/report` | `ROLE_ADMIN` | **HTTP 403 Forbidden** |

### Định dạng lỗi 403 Forbidden:
```json
{
  "timestamp": "2026-11-02T11:20:00.000+00:00",
  "status": 403,
  "error": "Forbidden",
  "message": "Bạn không có quyền truy cập tài nguyên này."
}
```

