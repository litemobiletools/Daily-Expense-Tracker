package com.litemobiletools.dailyexpensetracker.backup;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.litemobiletools.dailyexpensetracker.backup.AutoBackupPolicy.ResultForStatus;
import com.litemobiletools.dailyexpensetracker.backup.BackupMetadataStore.AutoBackupState;
import com.litemobiletools.dailyexpensetracker.data.AppDatabase;
import com.litemobiletools.dailyexpensetracker.drive.AuthOutcome;
import com.litemobiletools.dailyexpensetracker.drive.DriveBackupApi;
import com.litemobiletools.dailyexpensetracker.drive.DriveBackupFileInfo;
import com.litemobiletools.dailyexpensetracker.drive.DriveConnectionStore;
import com.litemobiletools.dailyexpensetracker.drive.DriveConstants;
import com.litemobiletools.dailyexpensetracker.drive.DriveOpResult;
import com.litemobiletools.dailyexpensetracker.drive.PlayServicesAuthGateway;
import com.litemobiletools.dailyexpensetracker.settings.AppPreferences;
import com.litemobiletools.dailyexpensetracker.settings.AppPreferences.AutoBackupSchedule;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The AndroidX WorkManager worker behind automatic backup (Phase 8). Runs only when the user
 * enabled automatic backup and a network connection is available. It reuses the exact Phase 7
 * machinery: the same credential file format, {@link BackupManager} snapshot, validation rules,
 * scope and Drive REST code — there is no second backup implementation.
 *
 * <p>Behavior on the background path: authorization is silent only (the Phase 6
 * {@code AuthorizationClient} flow without any screen; a needed consent is recorded as a status,
 * never launched from the background). The data snapshot is taken in one database transaction.
 * An unchanged dataset skips the upload after its verified backup is still present in Drive, and
 * superseded backups are deleted only after the replacement has been uploaded and verified. Every
 * attempt records a readable status in {@link BackupMetadataStore}; nothing about the data or
 * tokens is ever logged.
 */
public class AutoBackupWorker extends Worker {

    public AutoBackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        AutoBackupSchedule schedule = AppPreferences.getAutoBackupSchedule(context);
        if (!schedule.enabled) {
            // The user disabled it between scheduling and running: nothing to do this attempt.
            return Result.success();
        }
        return runAttempt(context);
    }

    @NonNull
    private Result runAttempt(@NonNull Context context) {
        // One backup/restore operation at a time, shared with the manual screen. Non-blocking:
        // if a manual operation is running, this attempt silently reschedules itself.
        if (!BackupOpsLock.tryAcquire()) {
            return transientRetry(null);
        }
        BackupMetadataStore store = new BackupMetadataStore(context);
        try {
            store.recordAutoInProgress(System.currentTimeMillis());
            return attempt(context, store);
        } catch (RuntimeException e) {
            // Anything unexpected: record a failure rather than crash the background work.
            store.recordAutoOutcome(AutoBackupState.FAILED, System.currentTimeMillis(), "UNEXPECTED", 0);
            return Result.failure();
        } finally {
            BackupOpsLock.release();
        }
    }

    @NonNull
    private Result attempt(@NonNull Context context, @NonNull BackupMetadataStore store) {
        // 1) Drive authorization, silent only. No Google screen is ever launched from here.
        AuthOutcome auth = silentAuthorize(new PlayServicesAuthGateway(context));
        if (auth == null) {
            store.recordAutoOutcome(AutoBackupState.FAILED, System.currentTimeMillis(),
                    "AUTH_TIMEOUT", 0);
            return transientRetry(null);
        }
        ResultForStatus authProblem = AutoBackupPolicy.authProblem(auth.kind, auth.errorCode);
        if (authProblem != null) {
            store.recordAutoOutcome(authProblem.state, System.currentTimeMillis(),
                    authProblem.kindLabel, authProblem.httpStatus);
            return outcome(authProblem.outcome);
        }
        String token = auth.accessToken();
        if (token == null) {
            store.recordAutoOutcome(AutoBackupState.AUTH_REQUIRED, System.currentTimeMillis(),
                    "AUTH", 0);
            return Result.success();
        }

        // 2) One consistent, validated snapshot of the local data.
        BackupData data;
        try {
            data = new BackupManager(AppDatabase.getInstance(context), context).snapshotData();
        } catch (RuntimeException e) {
            store.recordAutoOutcome(AutoBackupState.FAILED, System.currentTimeMillis(), "DB", 0);
            return transientRetry(store);
        }
        String dataDigest = BackupIntegrity.sha256Hex(
                BackupWriter.dataJson(data).getBytes(StandardCharsets.UTF_8));

        // 3) Drive: list once — the existence check for the skip decision and the unique name source.
        DriveBackupApi api = new DriveBackupApi();
        DriveOpResult<List<DriveBackupFileInfo>> listed = api.listAppDataFiles(token,
                BackupFileNames::isBackupFileName);
        if (listed.kind != DriveOpResult.Kind.OK || listed.value == null) {
            return classify(listed, store);
        }
        Set<String> names = new HashSet<>();
        String knownFileId = store.autoFileId();
        boolean knownFilePresent = false;
        for (DriveBackupFileInfo info : listed.value) {
            names.add(info.name);
            if (knownFileId != null && knownFileId.equals(info.id)) {
                knownFilePresent = true;
            }
        }

        // 4) Skip when the data provably has not changed and the verified backup is still intact.
        if (knownFilePresent && dataDigest.equals(store.autoDataDigest())
                && store.autoSuccessAtMs() > 0) {
            store.recordAutoUpToDate(System.currentTimeMillis());
            return Result.success();
        }

        // 5) Build the file (the Phase 7 format, including header checksum) and upload it.
        long nowMs = System.currentTimeMillis();
        byte[] bytes = BackupWriter.write(data).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > DriveConstants.MAX_BACKUP_BYTES) {
            store.recordAutoOutcome(AutoBackupState.FAILED, System.currentTimeMillis(), "TOO_LARGE", 0);
            return Result.success();
        }
        String name = BackupFileNames.unique(
                BackupFileNames.fileName(nowMs, java.util.TimeZone.getDefault()), names);
        DriveOpResult<DriveBackupFileInfo> uploaded = api.upload(token, name, bytes, null);
        if (uploaded.kind != DriveOpResult.Kind.OK || uploaded.value == null) {
            return classify(uploaded, store);
        }

        // 6) Verify what Drive actually stored before declaring success (no download required).
        DriveOpResult<DriveBackupFileInfo> verified = api.verify(token, uploaded.value.id);
        if (verified.kind != DriveOpResult.Kind.OK) {
            return classify(verified, store);
        }
        if (verified.value == null || !uploaded.value.name.equals(verified.value.name)
                || verified.value.sizeBytes != bytes.length) {
            store.recordAutoOutcome(AutoBackupState.FAILED, System.currentTimeMillis(),
                    "MALFORMED_RESPONSE", 0);
            return Result.success();
        }

        // 7) The replacement is verified: retire superseded backups (backup-named files only).
        for (DriveBackupFileInfo info : listed.value) {
            if (info.id.equals(uploaded.value.id)) {
                continue;
            }
            // Individual cleanup failures are ignored: the newest verified backup exists and a
            // later run or a later attempt can finish deleting safely.
            api.delete(token, info.id);
        }

        // 8) Record success only now (upload verified), for the automatic card and the manual card.
        store.recordAutoSuccess(nowMs, name, uploaded.value.id, dataDigest);
        new DriveConnectionStore.Prefs(context).setConnectionRequested(true);
        return Result.success();
    }

    // ---- Helpers -----------------------------------------------------------------------

    /**
     * Runs the Phase 6 authorization callback model on a background thread: waits briefly for the
     * outcome without ever launching a screen. Returns null when Google Play services did not
     * answer within the wait window.
     */
    @Nullable
    private AuthOutcome silentAuthorize(@NonNull PlayServicesAuthGateway gateway) {
        CountDownLatch latch = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<AuthOutcome> outcome =
                new java.util.concurrent.atomic.AtomicReference<>();
        final RuntimeException[] thrown = new RuntimeException[1];
        try {
            gateway.authorize(result -> {
                outcome.set(result);
                latch.countDown();
            });
        } catch (RuntimeException e) {
            thrown[0] = e;
        }
        boolean answered = false;
        if (thrown[0] == null) {
            try {
                answered = latch.await(AutoBackupPolicy.AUTH_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // The worker is being stopped; no status write was promised here.
                return null;
            }
        }
        if (!answered) {
            return null;
        }
        return outcome.get();
    }

    private Result transientRetry(@Nullable BackupMetadataStore store) {
        if (getRunAttemptCount() < AutoBackupPolicy.MAX_ATTEMPTS_PER_RUN) {
            return Result.retry();
        }
        if (store != null) {
            store.recordAutoOutcome(AutoBackupState.FAILED, System.currentTimeMillis(),
                    "RETRY_LIMIT", 0);
        }
        return Result.failure();
    }

    @NonNull
    private Result outcome(@NonNull AutoBackupPolicy.WorkerOutcome outcome) {
        return outcome == AutoBackupPolicy.WorkerOutcome.RETRY_TEMPORARY
                ? transientRetry(null) : Result.success();
    }

    /** One failed Drive call: records the status and produces the worker decision for it. */
    @NonNull
    private Result classify(@NonNull DriveOpResult<?> result,
                            @NonNull BackupMetadataStore store) {
        ResultForStatus mapped = AutoBackupPolicy.statusFor(result.kind, result.httpStatus,
                getRunAttemptCount());
        store.recordAutoOutcome(mapped.state, System.currentTimeMillis(), mapped.kindLabel,
                mapped.httpStatus);
        return outcome(mapped.outcome);
    }
}
