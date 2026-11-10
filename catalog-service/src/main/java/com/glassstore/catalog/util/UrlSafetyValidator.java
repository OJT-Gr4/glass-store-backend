package com.glassstore.catalog.util;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Kiểm tra an toàn cho URL ảnh của sản phẩm và biến thể (Subtask 3 / Security).
 *
 * <p>Ngăn chặn các cuộc tấn công XSS, giao thức nguy hiểm (javascript:, file:, vbscript:)
 * và các payload mã độc nhúng trong thuộc tính ảnh.</p>
 */
public final class UrlSafetyValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private UrlSafetyValidator() {
    }

    public static void validateImageUrl(String url) {
        if (url == null || url.isBlank()) {
            return;
        }

        String trimmed = url.trim();
        if (trimmed.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL ảnh không được vượt quá 500 ký tự.");
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("javascript:")
                || lower.startsWith("vbscript:")
                || lower.startsWith("file:")
                || lower.contains("<script")
                || lower.contains("</script")
                || lower.contains("onerror=")
                || lower.contains("onload=")
                || lower.contains("javascript:")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL ảnh chứa giao thức hoặc mã lệnh không an toàn.");
        }

        if (lower.startsWith("/")) {
            // Đường dẫn tương đối nội bộ hợp lệ
            return;
        }

        if (lower.startsWith("data:image/")) {
            // Data URI cho ảnh hợp lệ
            return;
        }

        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chỉ chấp nhận URL ảnh có giao thức HTTP hoặc HTTPS hợp lệ.");
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL ảnh không đúng định dạng: " + e.getMessage());
        }
    }
}

