package com.litemobiletools.dailyexpensetracker.backup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * The names of backup files in the app data folder, always local-device time:
 * {@code daily-expense-backup-20261022-165000.json}. Only files with this exact shape are ever
 * treated as backups, so unrelated Drive files (or other apps' files in their own hidden folders)
 * are ignored even if the scope would return them.
 */
public final class BackupFileNames {

    public static final String PREFIX = "daily-expense-backup-";
    public static final String SUFFIX = ".json";
    private static final String NAME_TIME_PATTERN = "yyyyMMdd-HHmmss";
    /** Name length: prefix + 8 digits + '-' + 6 digits + suffix. */
    private static final int NAME_LENGTH = PREFIX.length() + 8 + 1 + 6 + SUFFIX.length();

    private BackupFileNames() {
    }

    /** {@code daily-expense-backup-20261022-165000.json} for the given local moment. */
    @NonNull
    public static String fileName(long nowMs, @NonNull TimeZone zone) {
        SimpleDateFormat format = new SimpleDateFormat(NAME_TIME_PATTERN, Locale.US);
        format.setTimeZone(zone);
        return PREFIX + format.format(new Date(nowMs)) + SUFFIX;
    }

    /**
     * Only valid backup shapes are treated as one of this app's backups: the exact stamped name, or
     * the same name with the {@code -2}, {@code -3}, … same-second suffix that {@link #unique}
     * generates (those are real backups too and must be listed, restored and cleaned up).
     */
    public static boolean isBackupFileName(@NonNull String name) {
        return stampOf(name) != null;
    }

    /** The 15-character timestamp inside a valid backup name ({@code yyyyMMdd-HHmmss}); null otherwise. */
    @Nullable
    private static String stampOf(@NonNull String name) {
        if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) {
            return null;
        }
        String stem = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
        String stamp = stem;
        if (stem.length() != STAMP_LENGTH) {
            // Possible same-second suffix: "-N". Only meaningful when what remains is a full stamp.
            int dash = stem.lastIndexOf('-');
            String candidate = dash <= 0 ? null : stem.substring(0, dash);
            String suffix = dash <= 0 ? null : stem.substring(dash + 1);
            if (candidate == null || candidate.length() != STAMP_LENGTH
                    || !isStampShape(candidate) || suffix == null
                    || suffix.length() < 1 || suffix.length() > 7 || !isDigits(suffix)
                    || Integer.parseInt(suffix) < 2) {
                return null;
            }
            stamp = candidate;
        }
        if (!isStampShape(stamp) || !isRealTime(stamp)) {
            return null;
        }
        return stamp;
    }

    private static final int STAMP_LENGTH = 8 + 1 + 6;

    private static boolean isStampShape(@NonNull String stamp) {
        if (stamp.length() != STAMP_LENGTH || stamp.charAt(8) != '-') {
            return false;
        }
        for (int i = 0; i < stamp.length(); i++) {
            if (i == 8) {
                continue;
            }
            char c = stamp.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isDigits(@NonNull String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** Rejects impossible-looking timestamps early (they would be unparseable outputs anyway). */
    private static boolean isRealTime(@NonNull String stamp) {
        SimpleDateFormat format = new SimpleDateFormat(NAME_TIME_PATTERN, Locale.US);
        format.setLenient(false);
        try {
            return format.parse(stamp) != null;
        } catch (java.text.ParseException e) {
            return false;
        }
    }

    /**
     * Returns a name not present among {@code existingNames} (same-second collisions get
     * {@code -2}, {@code -3}, … appended before {@link #SUFFIX}).
     */
    @NonNull
    public static String unique(@NonNull String candidate, @NonNull Set<String> existingNames) {
        if (!existingNames.contains(candidate)) {
            return candidate;
        }
        int dot = candidate.length() - SUFFIX.length();
        String base = candidate.substring(0, dot);
        for (int n = 2; ; n++) {
            String next = base + "-" + n + SUFFIX;
            if (!existingNames.contains(next)) {
                return next;
            }
        }
    }

    /**
     * Best-effort read of the local timestamp baked into a valid backup file name (including the
     * numbered same-second names), for display ordering; 0 when the stamp cannot be read.
     */
    public static long timestampFromName(@NonNull String name) {
        String stamp = stampOf(name);
        if (stamp == null) {
            return 0;
        }
        SimpleDateFormat format = new SimpleDateFormat(NAME_TIME_PATTERN, Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        format.setLenient(false);
        try {
            Date date = format.parse(stamp);
            return date == null ? 0 : date.getTime();
        } catch (java.text.ParseException e) {
            return 0;
        }
    }
}
