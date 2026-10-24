package com.litemobiletools.dailyexpensetracker.backup;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.litemobiletools.dailyexpensetracker.backup.AutoBackupPolicy.Frequency;
import com.litemobiletools.dailyexpensetracker.settings.AppPreferences;
import com.litemobiletools.dailyexpensetracker.settings.AppPreferences.AutoBackupSchedule;

import java.util.concurrent.TimeUnit;

/**
 * Owns the single unique periodic WorkManager job for automatic backups (Phase 8). One unique work
 * name means repeated app launches and repeated setting reads can never create duplicate workers:
 * relaunches re-apply with {@link ExistingPeriodicWorkPolicy#KEEP}, and a settings change re-applies
 * with {@link ExistingPeriodicWorkPolicy#UPDATE}, which replaces the schedule in place. WorkManager
 * persists the schedule across reboots itself. The job asks only for a network connection — no
 * battery-optimization exemption and no unrestricted background execution are ever requested.
 * The preferred time anchors only the first run; Android never guarantees a clock time.
 */
public final class AutoBackupScheduler {

    /** The single unique work name; there is always at most one periodic backup job. */
    public static final String WORK_NAME = "auto-backup-periodic";

    private AutoBackupScheduler() {
    }

    /**
     * Applies the stored schedule: schedules the unique periodic work when automatic backup is
     * enabled, cancels it when disabled. Safe to call on every app launch and after every
     * setting change; never creates duplicates.
     */
    public static void apply(@NonNull Context context) {
        apply(context, AppPreferences.getAutoBackupSchedule(context));
    }

    /** Same as {@link #apply(Context)} for an already-read schedule (avoids a double prefs read). */
    public static void apply(@NonNull Context context, @NonNull AutoBackupSchedule schedule) {
        if (!schedule.enabled) {
            cancel(context);
            return;
        }
        Engines.workManager(context).enqueueUniquePeriodicWork(WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE, buildRequest(schedule, System.currentTimeMillis(),
                        java.util.TimeZone.getDefault()));
    }

    /** Cancels the scheduled work; harmless if nothing is scheduled. */
    public static void cancel(@NonNull Context context) {
        Engines.workManager(context).cancelUniqueWork(WORK_NAME);
    }

    /** The periodic request for a schedule: network constraint, anchored preferred time, fixed period. */
    @NonNull
    public static PeriodicWorkRequest buildRequest(@NonNull AutoBackupSchedule schedule, long nowMs,
                                                   @NonNull java.util.TimeZone zone) {
        Frequency frequency = schedule.weekly ? Frequency.WEEKLY : Frequency.DAILY;
        long anchorDelay = AutoBackupPolicy.initialDelayMs(nowMs, schedule.weekly, schedule.hour,
                zone);
        PeriodicWorkRequest.Builder builder = new PeriodicWorkRequest.Builder(
                AutoBackupWorker.class, frequency.periodMs(), TimeUnit.MILLISECONDS)
                .setInitialDelay(anchorDelay, TimeUnit.MILLISECONDS)
                .addTag(WORK_NAME)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build());
        return builder.build();
    }

    /** Indirection so tests can point the scheduler at a test-initialized WorkManager. */
    static class Engines {
        static WorkManager workManager(@NonNull Context context) {
            return WorkManager.getInstance(context);
        }
    }
}
