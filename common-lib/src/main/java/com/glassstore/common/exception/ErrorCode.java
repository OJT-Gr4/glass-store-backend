package com.glassstore.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Canonical error catalogue shared by every service.
 *
 * <p>Each constant maps a stable machine-readable {@code code} to the HTTP status the
 * gateway should return. Keeping them in one place guarantees the whole platform answers
 * with the same contract for the same failure (SRS §Exception Handling).</p>
 */
public enum ErrorCode {

    // ---- generic ----------------------------------------------------------------
    VALIDATION_ERROR(40001, "Dữ liệu gửi lên không hợp lệ", HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(40002, "Không đọc được nội dung yêu cầu", HttpStatus.BAD_REQUEST),
    NOT_FOUND(40401, "Không tìm thấy dữ liệu yêu cầu", HttpStatus.NOT_FOUND),
    CONFLICT(40901, "Dữ liệu đã tồn tại hoặc xung đột trạng thái", HttpStatus.CONFLICT),
    INTERNAL_ERROR(50000, "Hệ thống gặp sự cố, vui lòng thử lại sau", HttpStatus.INTERNAL_SERVER_ERROR),
    UPSTREAM_UNAVAILABLE(50301, "Dịch vụ liên quan hiện không sẵn sàng", HttpStatus.SERVICE_UNAVAILABLE),

    // ---- catalog / inventory ----------------------------------------------------
    VARIANT_NOT_FOUND(40410, "Không tìm thấy mã SKU sản phẩm", HttpStatus.NOT_FOUND),
    PRODUCT_NOT_FOUND(40411, "Không tìm thấy sản phẩm", HttpStatus.NOT_FOUND),
    PRODUCT_DISCONTINUED(40910, "Sản phẩm đã ngừng kinh doanh", HttpStatus.CONFLICT),
    VARIANT_INACTIVE(40911, "Mã SKU này hiện không được bán", HttpStatus.CONFLICT),
    INSUFFICIENT_STOCK(40912, "Không đủ tồn kho khả dụng", HttpStatus.CONFLICT),
    RESERVATION_NOT_FOUND(40412, "Không tìm thấy phiếu giữ tồn kho", HttpStatus.NOT_FOUND),
    RESERVATION_NOT_ACTIVE(40913, "Phiếu giữ tồn kho không còn hiệu lực", HttpStatus.CONFLICT),

    // ---- cart -------------------------------------------------------------------
    CART_NOT_FOUND(40420, "Không tìm thấy giỏ hàng", HttpStatus.NOT_FOUND),
    CART_EMPTY(40920, "Giỏ hàng đang trống", HttpStatus.CONFLICT),
    INVALID_QUANTITY(40020, "Số lượng sản phẩm không hợp lệ", HttpStatus.BAD_REQUEST),

    // ---- voucher ----------------------------------------------------------------
    VOUCHER_NOT_FOUND(40430, "Mã giảm giá không tồn tại", HttpStatus.NOT_FOUND),
    VOUCHER_EXPIRED(40930, "Mã giảm giá đã hết hạn", HttpStatus.CONFLICT),
    VOUCHER_INACTIVE(40931, "Mã giảm giá chưa có hiệu lực", HttpStatus.CONFLICT),
    VOUCHER_NOT_APPLICABLE(40932, "Đơn hàng chưa đạt điều kiện áp dụng mã giảm giá", HttpStatus.CONFLICT),
    VOUCHER_EXHAUSTED(40933, "Mã giảm giá đã hết lượt sử dụng", HttpStatus.CONFLICT),

    // ---- order ------------------------------------------------------------------
    ORDER_NOT_FOUND(40440, "Không tìm thấy đơn hàng", HttpStatus.NOT_FOUND),
    ORDER_PRICE_CHANGED(40940, "Giá sản phẩm đã thay đổi, vui lòng xác nhận lại", HttpStatus.CONFLICT),
    ORDER_INVALID_TRANSITION(40941, "Không thể chuyển đơn hàng sang trạng thái này", HttpStatus.CONFLICT),
    ORDER_NOT_CANCELLABLE(40942, "Đơn hàng không còn ở trạng thái có thể hủy", HttpStatus.CONFLICT),
    ORDER_FORBIDDEN(40340, "Bạn không có quyền truy cập đơn hàng này", HttpStatus.FORBIDDEN),
    IDEMPOTENCY_KEY_REQUIRED(40040, "Thiếu Idempotency-Key cho yêu cầu tạo đơn", HttpStatus.BAD_REQUEST),
    IDEMPOTENCY_CONFLICT(40943, "Khóa thanh toán đã dùng cho yêu cầu khác; kiểm tra đơn cũ", HttpStatus.CONFLICT),
    POS_SESSION_NOT_FOUND(40480, "Không tìm thấy phiên bán hàng POS", HttpStatus.NOT_FOUND),
    POS_SESSION_CLOSED(40980, "Phiên bán hàng POS đã đóng", HttpStatus.CONFLICT),

    // ---- payment ----------------------------------------------------------------
    PAYMENT_NOT_FOUND(40450, "Không tìm thấy giao dịch thanh toán", HttpStatus.NOT_FOUND),
    PAYMENT_ALREADY_SETTLED(40950, "Đơn hàng đã được thanh toán thành công", HttpStatus.CONFLICT),
    PAYMENT_AMOUNT_MISMATCH(40951, "Số tiền thanh toán không khớp với giá trị đơn hàng", HttpStatus.CONFLICT),
    PAYMENT_PROVIDER_NOT_CONFIGURED(50350, "Cổng thanh toán chưa được cấu hình trên hệ thống", HttpStatus.SERVICE_UNAVAILABLE),
    PAYMENT_SIGNATURE_INVALID(40050, "Chữ ký xác thực thanh toán không hợp lệ", HttpStatus.BAD_REQUEST),

    // ---- prescription / lab -----------------------------------------------------
    PRESCRIPTION_NOT_FOUND(40460, "Không tìm thấy toa kính", HttpStatus.NOT_FOUND),
    PRESCRIPTION_INCOMPLETE(40960, "Toa kính thiếu thông số khúc xạ bắt buộc", HttpStatus.CONFLICT),
    LAB_JOB_NOT_FOUND(40461, "Không tìm thấy lệnh gia công mài kính", HttpStatus.NOT_FOUND),
    LAB_JOB_INVALID_TRANSITION(40961, "Không thể chuyển lệnh gia công sang trạng thái này", HttpStatus.CONFLICT),
    LAB_JOB_QC_REASON_REQUIRED(40962, "Phải ghi rõ lý do khi đánh giá QC không đạt", HttpStatus.CONFLICT),
    LAB_JOB_QC_REQUIRED(40963, "Lệnh gia công phải đạt QC trước khi bàn giao", HttpStatus.CONFLICT),
    REPAIR_SERVICE_INVALID_TRANSITION(40964, "Không thể chuyển phiếu sửa chữa sang trạng thái này", HttpStatus.CONFLICT),

    // ---- identity ---------------------------------------------------------------
    INVALID_CREDENTIALS(40101, "Tài khoản hoặc mật khẩu không chính xác", HttpStatus.UNAUTHORIZED),
    ACCOUNT_LOCKED(40301, "Tài khoản đang bị tạm khóa", HttpStatus.FORBIDDEN),
    USER_NOT_FOUND(40470, "Không tìm thấy người dùng", HttpStatus.NOT_FOUND),
    DUPLICATE_USER(40970, "Email hoặc tên đăng nhập đã tồn tại", HttpStatus.CONFLICT),
    UNAUTHENTICATED(40100, "Yêu cầu cần đăng nhập", HttpStatus.UNAUTHORIZED);

    private final int code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String defaultMessage, HttpStatus httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
