package com.glassstore.catalog.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Tiện ích chuyển đổi chuỗi thành slug thân thiện URL (FR-PRO-01).
 */
public final class SlugUtils {

    private static final Pattern NON_LATIN = Pattern.compile("[^\\w-]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s+]");
    private static final Pattern MULTI_HYPHEN = Pattern.compile("-+");

    private SlugUtils() {
    }

    public static String toSlug(String input) {
        if (input == null || input.isBlank()) {
            return "san-pham-" + System.currentTimeMillis();
        }

        String nowhitespace = WHITESPACE.matcher(input.trim()).replaceAll("-");
        // Thay ký tự Đ/đ của tiếng Việt
        String normalizedVi = nowhitespace.replace("đ", "d").replace("Đ", "d");
        String normalized = Normalizer.normalize(normalizedVi, Normalizer.Form.NFD);
        String slug = Pattern.compile("\\p{InCombiningDiacriticalMarks}+").matcher(normalized).replaceAll("");
        slug = NON_LATIN.matcher(slug).replaceAll("");
        slug = MULTI_HYPHEN.matcher(slug).replaceAll("-");
        slug = slug.toLowerCase(Locale.ROOT);

        if (slug.startsWith("-")) {
            slug = slug.substring(1);
        }
        if (slug.endsWith("-")) {
            slug = slug.substring(0, slug.length() - 1);
        }

        return slug.isBlank() ? "san-pham-" + System.currentTimeMillis() : slug;
    }
}

