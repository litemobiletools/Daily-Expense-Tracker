package com.litemobiletools.dailyexpensetracker.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;

import com.litemobiletools.dailyexpensetracker.model.MissedPolicy;
import com.litemobiletools.dailyexpensetracker.util.CurrencyFormatter;

import java.util.Currency;

/** Local, non-sensitive app preferences. Currently: the appearance (theme) choice. */
public final class AppPreferences {

    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    private static final String FILE = "app_preferences";
    private static final String KEY_THEME = "theme_mode";
    private static final String KEY_MISSED_POLICY = "recurring_missed_policy";
    private static final String KEY_AUTO_ENABLED = "auto_backup_enabled";
    private static final String KEY_AUTO_WEEKLY = "auto_backup_weekly";
    private static final String KEY_AUTO_HOUR = "auto_backup_hour";
    private static final String KEY_CURRENCY = "display_currency";

    private AppPreferences() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static int getTheme(@NonNull Context context) {
        int stored = prefs(context).getInt(KEY_THEME, THEME_SYSTEM);
        return stored == THEME_LIGHT || stored == THEME_DARK ? stored : THEME_SYSTEM;
    }

    /** Persists the choice and applies it immediately. */
    public static void setTheme(@NonNull Context context, int theme) {
        prefs(context).edit().putInt(KEY_THEME, theme).apply();
        applyTheme(theme);
    }

    /** How recurring occurrences missed while the app was closed are handled. Default: catch up (bounded). */
    @NonNull
    public static MissedPolicy getMissedPolicy(@NonNull Context context) {
        String stored = prefs(context).getString(KEY_MISSED_POLICY, MissedPolicy.CATCH_UP.name());
        try {
            return MissedPolicy.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return MissedPolicy.CATCH_UP;
        }
    }

    public static void setMissedPolicy(@NonNull Context context, @NonNull MissedPolicy policy) {
        prefs(context).edit().putString(KEY_MISSED_POLICY, policy.name()).apply();
    }

    // ---- Currency (Phase 9) ------------------------------------------------------------------

    /**
     * The ISO 4217 code the app uses for new records and dashboards ("display currency"). The
     * default is the device locale's currency; a preference change never converts or rewrites any
     * stored amount — each record keeps the currency it was saved with.
     */
    @NonNull
    public static String getCurrencyCode(@NonNull Context context) {
        String stored = prefs(context).getString(KEY_CURRENCY, null);
        if (stored != null) {
            try {
                java.util.Currency.getInstance(stored);
                return stored;
            } catch (IllegalArgumentException ignored) {
                // Corrupt or obsolete code: fall back to the default below.
            }
        }
        return CurrencyFormatter.currentCode();
    }

    /** Stores the chosen currency; the code is validated so storage only ever holds a valid code. */
    public static void setCurrencyCode(@NonNull Context context, @NonNull String currencyCode) {
        Currency.getInstance(currencyCode);
        prefs(context).edit().putString(KEY_CURRENCY, currencyCode).apply();
    }

    // ---- Automatic backup (Phase 8) ------------------------------------------------------

    /** A complete automatic backup schedule as the app stores and uses it. */
    public static final class AutoBackupSchedule {
        public final boolean enabled;
        /** True for weekly, false for daily. */
        public final boolean weekly;
        /** Preferred local hour of day, 0..23; exact execution is never promised. */
        public final int hour;

        public AutoBackupSchedule(boolean enabled, boolean weekly, int hour) {
            this.enabled = enabled;
            this.weekly = weekly;
            this.hour = hour;
        }
    }

    public static final int DEFAULT_AUTO_HOUR = 21;

    /** Reads the stored automatic backup schedule. Automatic backup is off until the user turns it on. */
    @NonNull
    public static AutoBackupSchedule getAutoBackupSchedule(@NonNull Context context) {
        SharedPreferences p = prefs(context);
        boolean enabled = p.getBoolean(KEY_AUTO_ENABLED, false);
        int hour = p.getInt(KEY_AUTO_HOUR, DEFAULT_AUTO_HOUR);
        if (hour < 0 || hour > 23) {
            hour = DEFAULT_AUTO_HOUR;
        }
        return new AutoBackupSchedule(enabled, p.getBoolean(KEY_AUTO_WEEKLY, false), hour);
    }

    /** Persists the whole schedule and keeps it consistent (turning it off keeps the other choices). */
    public static void setAutoBackupSchedule(@NonNull Context context,
                                             @NonNull AutoBackupSchedule schedule) {
        prefs(context).edit()
                .putBoolean(KEY_AUTO_ENABLED, schedule.enabled)
                .putBoolean(KEY_AUTO_WEEKLY, schedule.weekly)
                .putInt(KEY_AUTO_HOUR, schedule.hour)
                .apply();
    }

    public static void applyStoredTheme(@NonNull Context context) {
        applyTheme(getTheme(context));
    }

    private static void applyTheme(int theme) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // A backup restore stores the theme from its worker thread; AppCompat recreates the
            // visible activities for a night-mode change, which is only allowed on the main thread.
            new Handler(Looper.getMainLooper()).post(() -> applyTheme(theme));
            return;
        }
        switch (theme) {
            case THEME_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case THEME_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }
}
