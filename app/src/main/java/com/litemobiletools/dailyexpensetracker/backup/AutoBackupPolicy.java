package com.litemobiletools.dailyexpensetracker.backup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.litemobiletools.dailyexpensetracker.drive.AuthOutcome;
import com.litemobiletools.dailyexpensetracker.drive.DriveOpResult;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * The automatic backup decisions that are independent of Android and Drive plumbing, in one piece
 * of pure code: how failures map to worker outcomes and to stored status, and how the preferred
 * backup time anchors the periodic schedule. Everything here is deterministic and testable on the
 * JVM. Execution times are always approximate: Android may delay background work, and WorkManager
 * only guarantees the interval between runs, not a clock time.
 */
public final class AutoBackupPolicy {

    /** How often an automatic backup runs. */
    public enum Frequency {
        DAILY {
            @Override public long periodMs() { return 24L * 60 * 60 * 1000; }
            @Override public long maxInitialDelayMs() { return 24L * 60 * 60 * 1000; }
        },
        WEEKLY {
            @Override public long periodMs() { return 7L * 24 * 60 * 60 * 1000; }
            @Override public long maxInitialDelayMs() { return 7L * 24 * 60 * 60 * 1000; }
        };

        /** WorkManager's minimum periodic interval for this frequency (never below 15 minutes). */
        public abstract long periodMs();

        /** Upper bound for the anchor delay computed for one run of this frequency. */
        public abstract long maxInitialDelayMs();
    }

    /** What a worker should do with one failed attempt. */
    public enum WorkerOutcome {
        /** Temporary failure: try again soon (bounded), a periodic run follows either way. */
        RETRY_TEMPORARY,
        /** Permanent for now (for example authorization/configuration): stop this attempt, a status
         *  tells the user what to do, and the next periodic run picks it up. */
        STOP_UNTIL_NEXT_PERIOD
    }

    /** WorkManager retries are bounded so a permanent problem never loops fast forever. */
    public static final int MAX_ATTEMPTS_PER_RUN = 3;

    /** Silent authorization await timeout: an authorized-but-unreachable Play services call fails politely. */
    public static final long AUTH_WAIT_MS = 30_000L;

    private AutoBackupPolicy() {
    }

    /** Parses a stored frequency name; daily is the default. */
    @NonNull
    public static Frequency frequency(@Nullable String stored) {
        if (Frequency.WEEKLY.name().equals(stored)) {
            return Frequency.WEEKLY;
        }
        return Frequency.DAILY;
    }

    /**
     * Maps one failed Drive operation to what the worker should do. Temporary failures retry a
     * bounded number of times within this run; everything else stops this run and is retried by the
     * next periodic run.
     */
    @NonNull
    public static WorkerOutcome decide(@NonNull DriveOpResult.Kind kind, int attempt) {
        boolean temporary = kind == DriveOpResult.Kind.NETWORK
                || kind == DriveOpResult.Kind.RATE_LIMITED
                || kind == DriveOpResult.Kind.SERVER_ERROR;
        if (temporary && attempt < MAX_ATTEMPTS_PER_RUN) {
            return WorkerOutcome.RETRY_TEMPORARY;
        }
        return WorkerOutcome.STOP_UNTIL_NEXT_PERIOD;
    }

    /**
     * Maps an authorization outcome (Phase 6 {@code authorize()} semantics, silent only) to a
     * status the screen can show; {@code null} means authorization succeeded and normal failure
     * mapping applies to the actual backup attempt.
     */
    @Nullable
    public static ResultForStatus authProblem(@NonNull AuthOutcome.Kind kind, int googleCode) {
        switch (kind) {
            case GRANTED:
                return null;
            case NEEDS_CONSENT:
                return new ResultForStatus(BackupMetadataStore.AutoBackupState.AUTH_REQUIRED,
                        WorkerOutcome.STOP_UNTIL_NEXT_PERIOD, "NEEDS_CONSENT", 0);
            case FAILED:
                if (googleCode == 7 || googleCode == 15) {
                    // Google's network/timeout statuses: possibly temporary.
                    return new ResultForStatus(BackupMetadataStore.AutoBackupState.WAITING_FOR_NETWORK,
                            WorkerOutcome.RETRY_TEMPORARY, "NETWORK", 0);
                }
                if (googleCode == 8 || googleCode == 10) {
                    // Build/Cloud configuration problem: retrying in the background cannot fix it.
                    return new ResultForStatus(BackupMetadataStore.AutoBackupState.AUTH_REQUIRED,
                            WorkerOutcome.STOP_UNTIL_NEXT_PERIOD, "CONFIG", googleCode);
                }
                return new ResultForStatus(BackupMetadataStore.AutoBackupState.FAILED,
                        WorkerOutcome.STOP_UNTIL_NEXT_PERIOD, "AUTH", googleCode);
            default:
                return new ResultForStatus(BackupMetadataStore.AutoBackupState.FAILED,
                        WorkerOutcome.STOP_UNTIL_NEXT_PERIOD, "AUTH", googleCode);
        }
    }

    /** One carrying type: recorded status plus worker decision plus safe detail words. */
    public static final class ResultForStatus {
        @NonNull public final BackupMetadataStore.AutoBackupState state;
        @NonNull public final WorkerOutcome outcome;
        @Nullable public final String kindLabel;
        public final int httpStatus;

        public ResultForStatus(@NonNull BackupMetadataStore.AutoBackupState state,
                               @NonNull WorkerOutcome outcome,
                               @Nullable String kindLabel, int httpStatus) {
            this.state = state;
            this.outcome = outcome;
            this.kindLabel = kindLabel;
            this.httpStatus = httpStatus;
        }
    }

    /** Maps a failed Drive operation kind to the stored status and the worker outcome. */
    @NonNull
    public static ResultForStatus statusFor(@NonNull DriveOpResult.Kind kind, int httpStatus,
                                            int attempt) {
        switch (kind) {
            case NETWORK:
            case RATE_LIMITED:
            case SERVER_ERROR:
                return new ResultForStatus(BackupMetadataStore.AutoBackupState.WAITING_FOR_NETWORK,
                        decide(kind, attempt), kind.name(), httpStatus);
            case UNAUTHORIZED:
                // 401/403-with-auth: the user must approve drive access again in the app.
                return new ResultForStatus(BackupMetadataStore.AutoBackupState.AUTH_REQUIRED,
                        WorkerOutcome.STOP_UNTIL_NEXT_PERIOD, kind.name(), httpStatus);
            case FORBIDDEN:
            case API_NOT_ENABLED:
                return new ResultForStatus(BackupMetadataStore.AutoBackupState.AUTH_REQUIRED,
                        WorkerOutcome.STOP_UNTIL_NEXT_PERIOD, kind.name(), httpStatus);
            default:
                // DRIVE_FULL, NOT_FOUND, MALFORMED_RESPONSE, FAILED and anything unexpected.
                return new ResultForStatus(BackupMetadataStore.AutoBackupState.FAILED,
                        WorkerOutcome.STOP_UNTIL_NEXT_PERIOD, kind.name(), httpStatus);
        }
    }

    /**
     * Delay until the first anchored run in the user's local time: the next occurrence of the
     * preferred hour (for daily), or of the preferred hour on the current weekday (for weekly).
     * Always strictly positive and below the period. The pattern for weekly anchors the run to the
     * same weekday the schedule was created, at the preferred time of day.
     */
    public static long initialDelayMs(long nowMs, boolean weekly, int hour, @NonNull TimeZone zone) {
        Calendar anchor = Calendar.getInstance(zone);
        anchor.setTimeInMillis(nowMs);
        anchor.set(Calendar.MILLISECOND, 0);
        anchor.set(Calendar.SECOND, 0);
        anchor.set(Calendar.MINUTE, 0);
        anchor.set(Calendar.HOUR_OF_DAY, clampHour(hour));
        if (weekly) {
            // Same weekday: push forward full weeks until the anchor is in the future.
            while (anchor.getTimeInMillis() <= nowMs) {
                anchor.add(Calendar.DAY_OF_YEAR, 7);
            }
        } else {
            // Daily: the next occurrence of the hour (today or tomorrow).
            if (anchor.getTimeInMillis() <= nowMs) {
                anchor.add(Calendar.DAY_OF_YEAR, 1);
            }
        }
        long delay = anchor.getTimeInMillis() - nowMs;
        Frequency limit = weekly ? Frequency.WEEKLY : Frequency.DAILY;
        return Math.min(Math.max(delay, 1), limit.maxInitialDelayMs() - 60_000);
    }

    private static int clampHour(int hour) {
        if (hour < 0) {
            return 0;
        }
        return Math.min(hour, 23);
    }
}
