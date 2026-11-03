# GlassStore Authentication & RBAC Test Cases Report

Báo cáo kiểm thử chi tiết bộ Unit Test & Integration Logic cho phân hệ Authentication & RBAC.

Source Code Test: `glass-store-backend/identity-service/src/test/java/com/glassstore/identity/service/AuthServiceTest.java`

---

## 1. Danh sách 14 Unit Test Cases

| STT | Tên Test Case | Mục đích Kiểm thử | Dữ liệu đầu vào | Kết quả mong đợi | Trạng thái |
|:---:|---|---|---|---|:---:|
| 01 | `testRegister_Success` | Đăng ký tài khoản mới thành công | Email mới, password hợp lệ | Tạo user `PENDING`, gửi OTP 6 số qua email | **PASS** |
| 02 | `testRegister_DuplicateEmail_ThrowsException` | Chặn trùng email khi đăng ký | Email đã tồn tại trong DB | Ném ngoại lệ `IllegalArgumentException` | **PASS** |
| 03 | `testVerifyOtp_Success` | Xác thực OTP chính xác kích hoạt tài khoản | Mã OTP đúng, chưa hết hạn | User chuyển thành `ACTIVE`, trả về Access + Refresh Token | **PASS** |
| 04 | `testVerifyOtp_InvalidCode_ThrowsException` | Nhập sai mã OTP | Mã OTP không khớp | Ném ngoại lệ `IllegalArgumentException` | **PASS** |
| 05 | `testVerifyOtp_ExpiredOtp_ThrowsException` | Nhập mã OTP đã quá hạn 5 phút | OTP đã hết hạn (> 5 phút) | Báo lỗi OTP hết hạn, yêu cầu gửi lại | **PASS** |
| 06 | `testLogin_PendingAccount_ThrowsException` | Chặn đăng nhập khi chưa kích hoạt | Tài khoản có status `PENDING` | Ném ngoại lệ `IllegalStateException` (HTTP 403) | **PASS** |
| 07 | `testLogin_Success` | Đăng nhập thành công với thông tin đúng | Email và password chính xác | Reset `failedLoginAttempts`, cấp phát JWT tokens | **PASS** |
| 08 | `testLogin_WrongPassword_IncrementsFailedAttempts`| Nhập sai mật khẩu lần 1 đến 4 | Password sai | Tăng `failedLoginAttempts = 1`, ném `BadCredentialsException` | **PASS** |
| 09 | `testLogin_FifthFailedAttempt_LocksAccount` | Nhập sai mật khẩu lần thứ 5 liên tiếp | 5 lần sai mật khẩu | Status chuyển `LOCKED`, khóa tài khoản `lockedUntil = now + 15 phút` | **PASS** |
| 10 | `testLogin_LockedAccount_DuringLockTime_ThrowsException` | Cố tình đăng nhập trong thời gian bị khóa | Tài khoản đang `LOCKED` (chưa hết 15p) | Ném ngoại lệ `LockedException` từ chối ngay lập tức | **PASS** |
| 11 | `testLogin_LockedAccount_AutoUnlockAfter15Minutes` | Tự động mở khóa sau 15 phút hết hạn phạt | Đã quá 15 phút từ lúc khóa | Tự động chuyển lại `ACTIVE`, cho phép đăng nhập thành công | **PASS** |
| 12 | `testRefreshToken_Success` | Cấp mới Access Token bằng Refresh Token | RefreshToken hợp lệ trong DB | Cấp mới Access Token 24h | **PASS** |
| 13 | `testRefreshToken_RevokedOrExpired_ThrowsException` | Dùng Refresh Token đã bị thu hồi/hết hạn | RefreshToken có `revoked = true` | Từ chối cấp token mới | **PASS** |
| 14 | `testLogout_RevokesToken` | Thu hồi token khi người dùng đăng xuất | Request đăng xuất hợp lệ | Đánh dấu `revoked = true` trong database | **PASS** |

---

## 2. Kiểm thử Frontend RBAC

| Kịch bản Kiểm thử | Thao tác | Hành vi quan sát được | Kết quả |
|---|---|---|:---:|
| **Khách hàng** | Đăng ký -> Nhận OTP -> Xác thực | Popup OTP tự động chuyển tiếp sau khi đăng ký, đếm ngược 60s, lưu token vào `localStorage` | **PASS** |
| **Nhân viên Bán Hàng (`ROLE_SALES_STAFF`)** | Đăng nhập vào Internal Portal | Menu chỉ hiển thị duy nhất **Bán Hàng POS Quầy**. Các tab Khám Mắt, Lab, Kho bị ẩn | **PASS** |
| **Kỹ thuật viên Khúc Xạ (`ROLE_OPTOMETRIST`)** | Đăng nhập vào Internal Portal | Menu chỉ hiển thị duy nhất **Phòng Đo Mắt (OD/OS)** | **PASS** |
| **Kỹ thuật viên Lab (`ROLE_LAB_TECH`)** | Đăng nhập vào Internal Portal | Menu chỉ hiển thị duy nhất **Xưởng Gia Công Lab** | **PASS** |
| **Quản trị viên (`ROLE_ADMIN`)** | Đăng nhập vào Internal Portal | Menu hiển thị đầy đủ 4 module: POS, Đo Mắt, Lab, Quản Trị & Tồn Kho | **PASS** |
| **Bảo vệ quyền truy cập** | Cố tình đổi menu trái quyền | Hiển thị màn hình cảnh báo **403 Forbidden Card** | **PASS** |
| **Đăng xuất an toàn** | Bấm nút Đăng xuất trên thanh Header/Sidebar | Xóa sạch session token, điều hướng trở về màn hình Login nhân viên | **PASS** |

