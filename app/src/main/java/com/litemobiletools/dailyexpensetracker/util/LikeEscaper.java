package com.litemobiletools.dailyexpensetracker.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Builds SQL LIKE patterns that match user text literally. The DAO uses {@code ESCAPE '\'}, so
 * a typed "50%" or "a_b" searches for those characters instead of acting as wildcards.
 */
public final class LikeEscaper {

    private LikeEscaper() {
    }

    @NonNull
    public static String escape(@NonNull String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Pattern matching text anywhere ("%text%"), or null for blank input (meaning "no search"). */
    @Nullable
    public static String contains(@Nullable String raw) {
        String text = raw == null ? "" : raw.trim();
        return text.isEmpty() ? null : "%" + escape(text) + "%";
    }

    /** Pattern matching text at the start ("text%"), or null if the trimmed text is shorter than {@code minLength}. */
    @Nullable
    public static String prefix(@Nullable String raw, int minLength) {
        String text = raw == null ? "" : raw.trim();
        return text.length() < minLength ? null : escape(text) + "%";
    }
}
