# GlassStore Backend - Omnichannel Eyewear Retail Microservices

Dự án Backend xây dựng trên nền tảng **Java 21 (LTS)**, **Spring Boot 3.5.16**, **Spring Cloud 2025.0.3**, **Spring Data JPA**, và **MySQL 8.0**, phục vụ toàn bộ nghiệp vụ chuỗi bán lẻ mắt kính đa kênh (Omnichannel) theo tiêu chuẩn SRS.

---

## 1. Công nghệ sử dụng (Tech Stack)

- **Ngôn ngữ:** Java 21 (LTS)
- **Framework:** Spring Boot 3.5.16
- **Kiến trúc phân tán:** Spring Cloud 2025.0.3
  - **API Gateway:** Spring Cloud Gateway (Port 8080 - Single Entry Point, Netty Reactive)
  - **Inter-service Communication:** Spring Cloud OpenFeign (Declarative REST Client)
- **Cơ sở dữ liệu:** MySQL 8.0 (Logical Database Isolation: 1 MySQL Server chứa 4 Schemas độc lập)

Backend mặc định kết nối MySQL trên cổng `3306`. Nếu dùng MySQL Docker được publish ở `3307`, đặt biến môi trường `DB_PORT=3307` trước khi khởi động cả bốn service. Hai cổng có thể chứa hai bộ dữ liệu khác nhau; kiểm tra đúng server trước khi nhập dữ liệu hoặc chạy migration.
- **ORM / Persistence:** Spring Data JPA & Hibernate
- **Bảo mật & Xác thực:** Spring Security 6, JJWT (io.jsonwebtoken 0.11.5), BCrypt
- **Email Service (Gửi OTP):** Spring Boot Starter Mail (Google SMTP)
- **Quản lý & Build tool:** Apache Maven (Multi-module Monorepo / Multi-service)
- **Containerization:** Docker, Docker Compose

---

## 2. Cấu trúc thư mục mã nguồn

```text
backend/
├── docker/
│   └── mysql/
│       └── init/
│           └── 01-init-databases.sql    # Tự động khởi tạo 4 Database Schema
├── docker-compose.yml                   # Khởi chạy MySQL 8.0 (Port 3306)
├── pom.xml                              # Maven Root POM quản lý phiên bản dùng chung
├── common-lib/                          # Thư viện dùng chung giữa các service
│   ├── pom.xml
│   └── src/main/java/com/glassstore/common/
│       └── dto/
│           └── ApiResponse.java         # Chuẩn format JSON Response hệ thống
├── api-gateway/                         # Spring Cloud Gateway (Port 8080)
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/glassstore/gateway/GatewayApplication.java
│       └── resources/application.yml   # Cấu hình CORS & Routing
├── identity-service/                    # Quản lý User, RBAC & OTP (Port 8081)
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/glassstore/identity/IdentityApplication.java
│       └── resources/application.yml   # Kết nối db_identity & Google Mail SMTP
├── catalog-service/                     # Gọng kính, Tròng kính, Tồn kho (Port 8082)
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/glassstore/catalog/CatalogApplication.java
│       └── resources/application.yml   # Kết nối db_catalog
├── order-pos-service/                   # Đơn hàng, POS, Giỏ hàng, Thanh toán (Port 8083)
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/glassstore/order/OrderPosApplication.java
│       └── resources/application.yml   # Kết nối db_order & OpenFeign Clients
└── eyewear-lab-service/                 # Đo mắt OD/OS & Xưởng gia công kính (Port 8084)
    ├── pom.xml
    └── src/main/
        ├── java/com/glassstore/lab/EyewearLabApplication.java
        └── resources/application.yml   # Kết nối db_lab
```

---

## 3. Yêu cầu môi trường (Prerequisites)

- **Java JDK:** 21 (khuyến nghị OpenJDK 21 LTS hoặc Eclipse Temurin 21)
- **Maven:** 3.9+ (hoặc dùng Maven wrapper tương thích với Maven 3.9+)
- **Docker & Docker Compose:** Đã cài đặt Docker Desktop hoặc Docker Engine để chạy MySQL

---

## 4. Phân bổ Cổng và Cơ sở dữ liệu (Ports & Schemas)

Tất cả các truy vấn từ bên ngoài (Frontend Web/Mobile) **chỉ đi qua API Gateway (Port 8080)**.

| Service | Port | Database Schema | Nghiệp vụ chính theo SRS |
| :--- | :---: | :--- | :--- |
| **`api-gateway`** | **8080** | *Không sử dụng* | Single Entry Point, Định tuyến, Xử lý CORS tập trung |
| **`identity-service`** | **8081** | `db_identity` | Đăng ký, đăng nhập JWT, phân quyền RBAC, gửi OTP Gmail |
| **`catalog-service`** | **8082** | `db_catalog` | Quản lý danh mục, gọng kính, tròng mắt, SKU, tồn kho |
| **`order-pos-service`** | **8083** | `db_order` | Giỏ hàng, đơn hàng online, bán hàng tại quầy (POS), voucher |
| **`eyewear-lab-service`**| **8084** | `db_lab` | Phiếu đo mắt OD/OS, Lệnh gia công mài lắp kính xưởng Lab, QC |

---

## 5. Hướng dẫn khởi chạy (Getting Started)

### Bước 1: Khởi chạy Database bằng Docker Compose
Đứng tại thư mục `backend/`, chạy lệnh:
```bash
docker compose up -d
```
Container MySQL 8.0 sẽ tự động bật trên cổng `3306` và tự động thực thi tuần tự 2 script:
1. `01-init-databases.sql`: Tạo 4 database schema (`db_identity`, `db_catalog`, `db_order`, `db_lab`).
2. `02-seed-data.sql`: Tạo toàn bộ cấu trúc bảng và nạp sẵn dữ liệu mẫu thực tế.

> **Mẹo:** Nếu muốn xóa sạch dữ liệu để nạp lại từ đầu:
> ```bash
> docker compose down -v && docker compose up -d
> ```

### Bước 2: Danh sách tài khoản mẫu & Dữ liệu Seed sẵn

**Tất cả tài khoản đều có mật khẩu mặc định là:** `123456`

| Email đăng nhập | Tên người dùng | Vai trò (Role) | Chức năng kiểm thử |
| :--- | :--- | :--- | :--- |
| `adminoptic@gmail.com` | Admin | `ROLE_ADMIN` | Quản trị toàn hệ thống, cấu hình |
| `posoptic@gmail.com` | Nguyễn Văn Nam | `ROLE_SALES_STAFF` | Bán hàng trực tiếp tại quầy POS |
| `optometryoptic@gmail.com` | Lê Thị Ánh Tuyết | `ROLE_OPTOMETRIST` | Nhập kết quả đo khúc xạ OD/OS |
| `laboptic@gmail.com` | Trần Văn Tuấn | `ROLE_LAB_TECH` | Đổi trạng thái mài kính & kiểm định QC |
| `customer@gmail.com` | Nguyễn Hoàng Duy | `ROLE_CUSTOMER` | Đăng nhập mua kính online trên Web |

**Dữ liệu sản phẩm mẫu có sẵn (50 chiếc tồn kho mỗi loại):**
- Gọng kính Titanium Bolon (`FRM-BOLON-BLK`, `FRM-BOLON-SIL`)
- Gọng nhựa TR90 Gentle Monster (`FRM-GM-BLK`, `FRM-GM-TOR`)
- Gọng phi công Ray-Ban Aviator (`FRM-RB-GLD`)
- Tròng kính Chemi U2 BlueCut 1.60 (`LNS-CHEMI-160`) & Tròng đổi màu Essilor (`LNS-ESS-TRANS`)
- Kính râm Ray-Ban Wayfarer Polarized (`SUN-RB-WAYF-BLK`)
- Phụ kiện Hộp da + Khăn lau Nano (`ACC-BOX-NANO`)

### Bước 3: Build mã nguồn bằng Maven
```bash
mvn clean compile
```

### Bước 4: Thứ tự khởi chạy các dịch vụ (Services)
Khởi chạy lần lượt từng Application (qua IntelliJ IDEA hoặc lệnh `mvn spring-boot:run`):
1. **`api-gateway`** (`GatewayApplication.java`) - Port 8080
2. **`identity-service`** (`IdentityApplication.java`) - Port 8081
3. **`catalog-service`** (`CatalogApplication.java`) - Port 8082
4. **`eyewear-lab-service`** (`EyewearLabApplication.java`) - Port 8084
5. **`order-pos-service`** (`OrderPosApplication.java`) - Port 8083

---

## 6. Chuẩn JSON Response & Giao tiếp liên dịch vụ

### 6.1. Định dạng phản hồi chuẩn (Standard API Response)
Tất cả các REST Controller trả về theo format `ApiResponse<T>`:
```json
{
  "code": 200,
  "message": "Success",
  "data": { ... },
  "timestamp": "2026-10-30T15:30:00"
}
```

### 6.2. Giao tiếp OpenFeign giữa các Service
- Khi POS thanh toán thành công, `order-pos-service` ghi một sự kiện trừ kho vào outbox cùng giao dịch đơn hàng/thanh toán, sau đó gọi `catalog-service` qua OpenFeign. Catalog trừ có điều kiện theo SKU và chi nhánh, ghi lịch sử trong cùng transaction; unique order/idempotency keys và kiểm tra lịch sử khiến request lặp chỉ được replay, không trừ lại. Lỗi kỹ thuật được retry tối đa 10 lần; lỗi nghiệp vụ ở trạng thái `FAILED`, còn lỗi kỹ thuật quá giới hạn chuyển `RECONCILIATION_REQUIRED` để đối soát hoặc retry thủ công.
- Khi đơn hàng kính thuốc được thanh toán thành công, `order-pos-service` gọi sang `eyewear-lab-service` qua OpenFeign (`POST /api/lab-jobs`) để khởi tạo lệnh gia công mài lắp kính cho xưởng Lab.

---

## 7. Danh sách REST API Endpoints chính

Base URL Gateway: `http://localhost:8080`

| Phương thức | Đường dẫn Gateway | Dịch vụ đích | Mô tả | Mã HTTP |
| :--- | :--- | :--- | :--- | :---: |
| `POST` | `/api/auth/login` | `identity-service` | Đăng nhập hệ thống, trả về JWT Token | 200 OK |
| `POST` | `/api/auth/register` | `identity-service` | Đăng ký tài khoản khách hàng | 201 Created |
| `POST` | `/api/auth/send-otp` | `identity-service` | Gửi mã OTP xác thực qua Google SMTP | 200 OK |
| `GET` | `/api/products` | `catalog-service` | Lấy danh sách kính (bộ lọc giá, kiểu dáng, chất liệu) | 200 OK |
| `GET` | `/api/products/{id}` | `catalog-service` | Xem chi tiết gọng/tròng/kính râm | 200 OK |
| `POST` | `/api/orders` | `order-pos-service` | Tạo đơn hàng đặt kính Online (cần `Idempotency-Key`) | 201 Created |
| `GET` | `/api/cart` | `order-pos-service` | Lấy / tạo giỏ hàng khách | 200 OK |
| `POST` | `/api/cart/items` | `order-pos-service` | Thêm SKU vào giỏ | 201 Created |
| `POST` | `/api/cart/checkout` | `order-pos-service` | Đặt hàng từ giỏ | 201 Created |
| `POST` | `/api/pos/sessions` | `order-pos-service` | Mở phiên bán hàng POS | 201 Created |
| `POST` | `/api/pos/checkout` | `order-pos-service` | Bán hàng tại quầy (CASH settle ngay) | 201 Created |
| `POST` | `/api/payments/callback` | `order-pos-service` | Webhook cổng thanh toán (chưa cấu hình provider) | 200 OK |
| `POST` | `/api/prescriptions` | `eyewear-lab-service`| Lưu thông số đo mắt OD/OS cho khách hàng | 201 Created |
| `GET` | `/api/lab-jobs` | `eyewear-lab-service`| Lấy danh sách kính chờ mài lắp tại xưởng | 200 OK |
| `PATCH`| `/api/lab-jobs/{id}/status`| `eyewear-lab-service`| Cập nhật trạng thái gia công (Mài/QC/Bàn giao)| 200 OK |

---

## 9. Quản lý tồn kho GS-23

`catalog-service` quản lý tồn vật lý theo SKU tại `BRANCH_01`, `BRANCH_02` và `LAB`. Khi khởi động, schema được Hibernate cập nhật theo entity hiện có; các SKU cũ chưa có số liệu theo địa điểm được chuyển nguyên số lượng `stock_quantity` sang `LAB`, hai chi nhánh bắt đầu từ 0. Các hàng tồn đã được phân bổ trước đó không bị ghi đè.

| Phương thức | Đường dẫn | Mô tả |
| :--- | :--- | :--- |
| `GET` | `/api/inventory` | Tồn từng SKU theo ba địa điểm |
| `GET` | `/api/inventory/locations` | Danh sách địa điểm |
| `GET` | `/api/inventory/transfers` | Danh sách phiếu điều chuyển |
| `GET` | `/api/inventory/movements` | Tra cứu lịch sử biến động (lọc tùy chọn theo SKU, địa điểm, mã tham chiếu; tối đa 100 dòng/trang) |
| `POST` | `/api/inventory/transfers` | Tạo phiếu `PENDING` với kho nguồn, kho đích và các SKU |
| `PATCH` | `/api/inventory/transfers/{id}/receive` | Xác nhận nhận hàng và cập nhật cả hai kho nguyên tử |

Tạo phiếu không giữ hàng và chưa làm thay đổi tồn. Khi nhận phiếu `PENDING`, catalog kiểm tra lại số dư nguồn bằng câu lệnh cập nhật có điều kiện, trừ kho nguồn, cộng kho đích, cập nhật trạng thái và ghi lịch sử xuất/nhập trong cùng transaction. Khóa phiếu ngăn xác nhận đồng thời hoặc lặp lại. JWT và vai trò `ROLE_ADMIN`/`ROLE_LAB` bảo vệ các API inventory, tương ứng với quyền của route `/stock-transfer` trên portal. Hệ thống hiện chưa có phân quyền theo từng chi nhánh, nên các vai trò được phép thấy các địa điểm đang hoạt động.

Các bản ghi `IN_TRANSIT` đã tạo theo luồng GS-23 cũ vẫn nhận được: vì tồn nguồn đã bị trừ khi xuất, bước nhận chỉ cộng kho đích và đồng bộ lại tổng tồn SKU, không trừ nguồn lần nữa. API `dispatch` cũ không còn được dùng cho phiếu mới. Lịch sử địa điểm được lưu trong `inventory_movements` và có thể tra cứu qua endpoint ở trên.

POS gửi `Idempotency-Key` cho `POST /api/orders`. `order-pos-service` gọi endpoint nội bộ `POST /internal/inventory/sales/deduct` trên cổng nội bộ của catalog-service; endpoint này không được định tuyến qua API Gateway. Địa điểm bán được xác định từ nhãn chi nhánh hiện tại; nhãn không khớp địa điểm đã hỗ trợ bị từ chối, không trừ vào kho mặc định.

Lệnh kiểm thử nhanh:

```bash
mvn -pl catalog-service,order-pos-service -am test
```

Internal Portal dùng `/api/inventory` qua API Gateway (Vite dev proxy tới cổng 8080). Khi triển khai production, cấu hình `VITE_API_GATEWAY_URL` theo môi trường.

---

## 10. Quy tắc Git & Phân nhánh (Git Workflow)

1. **Nhánh `main`:** Nhánh chính chứa mã nguồn ổn định nhất phục vụ nộp bài và demo. Không commit/push trực tiếp vào nhánh này.
2. **Nhánh `dev`:** Nhánh làm việc chung hàng ngày của team. Mọi tính năng sau khi kiểm thử nội bộ sẽ được merge vào `dev`.
3. **Quy trình làm việc của thành viên:**
   - Clone repo và chuyển sang `dev`: `git checkout dev && git pull origin dev`
   - Tạo nhánh tính năng mới: `git checkout -b feature/<ten-tinh-nang>`
   - Commit chuẩn Convention: `git commit -m "feat(catalog): add product filter by frame shape"`
   - Đẩy nhánh lên Remote và tạo Pull Request (PR) merge vào nhánh `dev`.
