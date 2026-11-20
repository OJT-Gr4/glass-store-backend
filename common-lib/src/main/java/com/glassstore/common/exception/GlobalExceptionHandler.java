package com.glassstore.common.exception;

import com.glassstore.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Translates every exception into the platform's {@link ApiResponse} envelope.
 *
 * <p>Registered from {@code common-lib} via auto-configuration, so each service gets
 * identical error shapes without copying this class (SRS §Exception Handling).</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Object>> handleResponseStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).headers(ex.getHeaders())
                .body(ApiResponse.<Object>builder()
                        .code(ex.getStatusCode().value())
                        .message(ex.getReason() == null ? "Request rejected" : ex.getReason())
                        .timestamp(java.time.LocalDateTime.now()).build());
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Object>> handleApiException(ApiException ex) {
        ErrorCode code = ex.getErrorCode();
        // Business failures are expected, so log at warn without a stack trace.
        log.warn("Business rule rejected request [{}]: {}", code.name(), ex.getMessage());

        ApiResponse<Object> body = ApiResponse.<Object>builder()
                .code(code.getCode())
                .message(ex.getMessage())
                .errorCode(code.name())
                .data(ex.getDetails().isEmpty() ? null : ex.getDetails())
                .timestamp(java.time.LocalDateTime.now())
                .build();

        return ResponseEntity.status(code.getHttpStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleBeanValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> fieldErrors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            String friendlyMsg = humanizeValidationMessage(fe.getField(), fe.getDefaultMessage());
            fieldErrors.merge(fe.getField(), friendlyMsg,
                    (a, b) -> a + "; " + b);
        }
        String summary = fieldErrors.entrySet().stream()
                .map(e -> e.getValue().toString())
                .distinct()
                .collect(Collectors.joining(", "));

        return buildError(ErrorCode.VALIDATION_ERROR, summary, fieldErrors);
    }

    private String humanizeValidationMessage(String field, String msg) {
        if (msg == null || msg.isBlank()) {
            return "Trường '" + field + "' không hợp lệ";
        }
        String lower = msg.toLowerCase();
        if (lower.contains("must not be blank") || lower.contains("must not be null") || lower.contains("must not be empty")) {
            return "Trường '" + field + "' là bắt buộc và không được để trống";
        }
        if (lower.contains("must be greater than or equal to 1")) {
            return "Trường '" + field + "' phải có giá trị tối thiểu từ 1 trở lên";
        }
        if (lower.contains("must be greater than or equal to 0")) {
            return "Trường '" + field + "' phải có giá trị từ 0 trở lên";
        }
        if (lower.contains("size must be between")) {
            return "Độ dài của trường '" + field + "' không hợp lệ";
        }
        return msg;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Object>> handleMissingHeader(MissingRequestHeaderException ex) {
        String header = ex.getHeaderName();
        if ("Idempotency-Key".equalsIgnoreCase(header)) {
            return buildError(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, null, null);
        }
        return buildError(ErrorCode.VALIDATION_ERROR, "Thiếu header bắt buộc: " + header, null);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        return buildError(ErrorCode.VALIDATION_ERROR,
                "Thiếu tham số bắt buộc: " + ex.getParameterName(), null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return buildError(ErrorCode.VALIDATION_ERROR,
                "Tham số '" + ex.getName() + "' không đúng định dạng", null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("Unreadable request body: {}", ex.getMostSpecificCause().getMessage());
        return buildError(ErrorCode.MALFORMED_REQUEST, null, null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleDataIntegrity(DataIntegrityViolationException ex) {
        // Uniqueness races (duplicate order code, duplicate callback) land here.
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return buildError(ErrorCode.CONFLICT, null, null);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Object>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("Concurrent modification detected: {}", ex.getMessage());
        return buildError(ErrorCode.CONFLICT,
                "Dữ liệu vừa được cập nhật bởi giao dịch khác, vui lòng thử lại", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return buildError(ErrorCode.INTERNAL_ERROR, null, null);
    }

    private ResponseEntity<ApiResponse<Object>> buildError(ErrorCode code, String message,
                                                           Map<String, Object> details) {
        ApiResponse<Object> body = ApiResponse.<Object>builder()
                .code(code.getCode())
                .message(message != null ? message : code.getDefaultMessage())
                .errorCode(code.name())
                .data(details)
                .timestamp(java.time.LocalDateTime.now())
                .build();
        return ResponseEntity.status(code.getHttpStatus()).body(body);
    }
}
