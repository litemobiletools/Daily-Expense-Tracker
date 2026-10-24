package com.litemobiletools.dailyexpensetracker.backup;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Device-local, non-sensitive facts about the app's backups: when the last one succeeded and which
 * file it produced (manual and automatic), plus the current automatic backup status. Shown by the
 * backup screen; never sent anywhere and contains nothing about the data itself. The recorded
 * digest is a SHA-256 over backup content; it stays in this app's own storage and is never logged.
 * Separate from the connection store (drive_connection.xml), which stays the only place Drive
 * connection state is remembered.
 */
public final class BackupMetadataStore {

    /** Current automatic backup status, mapped by the screen to readable text. */
    public enum AutoBackupState {
        /** The automatic backup feature never ran yet (still disabled, or the first run didn't happen). */
        NOT_RUN_YET,
        /** An automatic backup is running right now (this process). */
        IN_PROGRESS,
        /** The last attempt uploaded and verified a new backup. */
        SUCCESS,
        /** The last run proved the data has not changed since the verified backup in Drive. */
        UP_TO_DATE,
        /** No usable network; the run will be retried. */
        WAITING_FOR_NETWORK,
        /** Drive is not connected or needs renewed authorization; recorded, not retried in background. */
        AUTH_REQUIRED,
        /** The last attempt failed; usually retried by the next scheduled run. */
        FAILED
    }

    private static final String FILE = "backup_metadata";
    private static final String KEY_LAST_AT = "last_backup_at_ms";
    private static final String KEY_LAST_NAME = "last_backup_name";

    private static final String KEY_AUTO_STATE = "auto_state";
    private static final String KEY_AUTO_STATE_AT = "auto_state_at_ms";
    private static final String KEY_AUTO_SUCCESS_AT = "auto_success_at_ms";
    private static final String KEY_AUTO_FILE_ID = "auto_file_id";
    private static final String KEY_AUTO_FILE_NAME = "auto_file_name";
    private static final String KEY_AUTO_DIGEST = "auto_data_sha256";
    /** Last failure detail: the Drive failure kind name (safe, no user data), plus its HTTP status. */
    private static final String KEY_AUTO_KIND = "auto_last_kind";
    private static final String KEY_AUTO_HTTP = "auto_last_http";

    private final SharedPreferences prefs;

    public BackupMetadataStore(@NonNull Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---- Facts shared by manual and automatic backups -----------------------------------

    /** 0 when no backup succeeded on this device yet. */
    public long lastBackupAtMs() {
        return prefs.getLong(KEY_LAST_AT, 0);
    }

    @Nullable
    public String lastBackupName() {
        return prefs.getString(KEY_LAST_NAME, null);
    }

    /** Called only after an upload has been uploaded AND verified. */
    public void recordSuccess(long atMs, @Nullable String fileName) {
        prefs.edit()
                .putLong(KEY_LAST_AT, atMs)
                .putString(KEY_LAST_NAME, fileName)
                .apply();
    }

    // ---- Automatic backup status (Phase 8) ------------------------------------------------

    @NonNull
    public AutoBackupState autoState() {
        String stored = prefs.getString(KEY_AUTO_STATE, null);
        if (stored == null) {
            return AutoBackupState.NOT_RUN_YET;
        }
        try {
            return AutoBackupState.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return AutoBackupState.NOT_RUN_YET;
        }
    }

    /** When the last automatic attempt changed status (useful to judge whether it is really running). */
    public long autoStateAtMs() {
        return prefs.getLong(KEY_AUTO_STATE_AT, 0);
    }

    /** 0 if no automatic backup has succeeded yet. */
    public long autoSuccessAtMs() {
        return prefs.getLong(KEY_AUTO_SUCCESS_AT, 0);
    }

    @Nullable
    public String autoFileId() {
        return prefs.getString(KEY_AUTO_FILE_ID, null);
    }

    /** SHA-256 of the data section of the last verified automatic backup, null until one exists. */
    @Nullable
    public String autoDataDigest() {
        return prefs.getString(KEY_AUTO_DIGEST, null);
    }

    /** HTTP status behind the last failure, 0 if none. */
    public int autoLastHttpStatus() {
        return prefs.getInt(KEY_AUTO_HTTP, 0);
    }

    /** Marks an automatic attempt as started (worker holds the operation lock from here on). */
    public void recordAutoInProgress(long atMs) {
        setState(AutoBackupState.IN_PROGRESS, atMs);
    }

    /** Records a non-success attempt outcome (message-safe kind label only, e.g. NETWORK or code). */
    public void recordAutoOutcome(@NonNull AutoBackupState state, long atMs,
                                  @Nullable String kindLabel, int httpStatus) {
        setState(state, atMs);
        prefs.edit()
                .putString(KEY_AUTO_KIND, kindLabel)
                .putInt(KEY_AUTO_HTTP, httpStatus)
                .apply();
    }

    /**
     * Called only after an automatic upload was uploaded AND verified: stores the success, the file
     * it produced and the digest proving what its data covered. Also updates the facts the manual
     * card shows, since the device did produce this newest verified backup.
     */
    public void recordAutoSuccess(long atMs, @Nullable String fileName, @NonNull String fileId,
                                  @NonNull String dataDigestHex) {
        recordSuccess(atMs, fileName);
        setState(AutoBackupState.SUCCESS, atMs);
        prefs.edit()
                .putLong(KEY_AUTO_SUCCESS_AT, atMs)
                .putString(KEY_AUTO_FILE_ID, fileId)
                .putString(KEY_AUTO_FILE_NAME, fileName)
                .putString(KEY_AUTO_DIGEST, dataDigestHex)
                .putString(KEY_AUTO_KIND, null)
                .putInt(KEY_AUTO_HTTP, 0)
                .apply();
    }

    /**
     * The verified automatic backup is confirmed to still be intact in Drive during this run,
     * so nothing was uploaded ("already up to date").
     */
    public void recordAutoUpToDate(long atMs) {
        setState(AutoBackupState.UP_TO_DATE, atMs);
    }

    private void setState(@NonNull AutoBackupState state, long atMs) {
        prefs.edit()
                .putString(KEY_AUTO_STATE, state.name())
                .putLong(KEY_AUTO_STATE_AT, atMs)
                .apply();
    }
}
