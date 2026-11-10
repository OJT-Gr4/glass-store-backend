package com.glassstore.catalog.entity;

/**
 * Trạng thái vòng đời sản phẩm và biến thể (FR-PRO-01, FR-PRO-06, FR-ADM-05).
 *
 * <p>ACTIVE: Đang bán, hiển thị trên Web và POS.
 * HIDDEN: Tạm ẩn khỏi catalog khách, vẫn giữ trong Admin/POS và bảo toàn lịch sử đơn.
 * DISCONTINUED: Ngừng kinh doanh, không còn bán mới nhưng tuyệt đối không mất lịch sử đơn hàng cũ.</p>
 */
public enum ProductStatus {
    ACTIVE("Đang kinh doanh"),
    HIDDEN("Đã ẩn"),
    DISCONTINUED("Ngừng kinh doanh");

    private final String description;

    ProductStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public static boolean isValid(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String clean = raw.trim().toUpperCase();
        for (ProductStatus status : values()) {
            if (status.name().equals(clean)) {
                return true;
            }
        }
        return false;
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return ACTIVE.name();
        }
        String clean = raw.trim().toUpperCase();
        for (ProductStatus status : values()) {
            if (status.name().equals(clean)) {
                return status.name();
            }
        }
        throw new IllegalArgumentException("Trạng thái sản phẩm không hợp lệ: " + raw);
    }
}

