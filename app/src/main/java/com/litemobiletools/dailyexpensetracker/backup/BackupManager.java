package com.litemobiletools.dailyexpensetracker.backup;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.litemobiletools.dailyexpensetracker.data.AppDatabase;
import com.litemobiletools.dailyexpensetracker.model.MissedPolicy;
import com.litemobiletools.dailyexpensetracker.settings.AppPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Worker-thread orchestration for the manual backup and restore features (Phase 7). Gathers the
 * DB {@link BackupData} snapshot, builds the backup file content, writes a temporary local safety
 * copy before a restore, applies a validated backup to the database, and restores the supported
 * preferences. Talked to from the ViewModel over its own I/O executor; the Drive calls themselves
 * live in {@link com.litemobiletools.dailyexpensetracker.drive.DriveBackupApi}.
 */
public final class BackupManager {

    /** Local file holding the temporary safety copy of the current data before a restore. */
    public static final String SAFETY_FILE_NAME = "pre-restore-safety.json";

    private final AppDatabase db;
    private final Context appContext;

    public BackupManager(@NonNull AppDatabase db, @NonNull Context appContext) {
        this.db = db;
        this.appContext = appContext.getApplicationContext();
    }

    // ---- Backup ---------------------------------------------------------------------------

    /** The running app's identity as it belongs into a backup header. */
    @NonNull
    @WorkerThread
    public static BackupData.AppInfo appInfoOf(@NonNull Context appContext, int dbSchemaVersion) {
        String appId = appContext.getPackageName();
        String version = "?";
        int code = 0;
        try {
            PackageInfo info = appContext.getPackageManager().getPackageInfo(appId, 0);
            if (info.versionName != null) {
                version = info.versionName;
            }
            code = android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? (int) info.getLongVersionCode()
                    : info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            // Never happens for our own package; the backup stays self-describing regardless.
        }
        return new BackupData.AppInfo(appId, version, code, dbSchemaVersion);
    }

    /** The current device's supported preferences for the preferences section. */
    @NonNull
    public static BackupData.Preferences currentPreferences(@NonNull Context appContext) {
        return new BackupData.Preferences(AppPreferences.getTheme(appContext),
                AppPreferences.getMissedPolicy(appContext).name());
    }

    /**
     * Builds the whole backup file content from the live database. Worker thread; every table is
     * read inside one database transaction (one consistent snapshot).
     */
    @NonNull
    @WorkerThread
    public byte[] buildBackupBytes() {
        BackupData.AppInfo info = appInfoOf(appContext, schemaVersion());
        BackupData data = BackupCollector.snapshot(db, info, currentPreferences(appContext));
        return BackupWriter.write(data).getBytes(StandardCharsets.UTF_8);
    }

    /** The Room schema version the database of this device reports. */
    @WorkerThread
    private int schemaVersion() {
        return db.getOpenHelper().getReadableDatabase().getVersion();
    }

    /** Public read for callers on their own worker thread (backup validation before a restore). */
    @WorkerThread
    public int currentSchemaVersion() {
        return schemaVersion();
    }

    @NonNull
    @WorkerThread
    public BackupData snapshotData() {
        BackupData.AppInfo info = appInfoOf(appContext, schemaVersion());
        return BackupCollector.snapshot(db, info, currentPreferences(appContext));
    }

    // ---- Restore --------------------------------------------------------------------------

    /**
     * Writes a temporary local safety copy of the CURRENT user data (the pre-restore snapshot),
     * atomically (a temp file is renamed over the target). The caller deletes it when done —
     * the restore itself is transactional, so this copy is the belt-and-braces local safety net.
     */
    @NonNull
    @WorkerThread
    public File writeSafetySnapshot() throws IOException {
        BackupData data = snapshotData();
        byte[] bytes = BackupWriter.write(data).getBytes(StandardCharsets.UTF_8);
        File dir = appContext.getFilesDir();
        File target = new File(dir, SAFETY_FILE_NAME);
        File temp = new File(dir, SAFETY_FILE_NAME + ".tmp");
        delete(temp);
        try {
            moveTo(temp, target, bytes);
            return target;
        } catch (IOException e) {
            throw e;
        }
    }

    private static void moveTo(@NonNull File temp, @NonNull File target, @NonNull byte[] bytes)
            throws IOException {
        try (OutputStream out = new FileOutputStream(temp)) {
            out.write(bytes);
            out.flush();
        }
        if (!temp.renameTo(target)) {
            // Extreme device quirk (some filesystems): write in place instead, still complete copy.
            try (OutputStream out = new FileOutputStream(target)) {
                out.write(bytes);
            }
            delete(temp);
        }
    }

    @WorkerThread
    public void deleteSafetySnapshot() {
        delete(new File(appContext.getFilesDir(), SAFETY_FILE_NAME));
    }

    private static void delete(@NonNull File file) {
        try {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        } catch (SecurityException ignored) {
            // Nothing to clean up then.
        }
    }

    /**
     * Applies a validated backup inside one Room transaction and then (and only then, after the
     * database has committed) restores the supported preferences from the backup. Worker thread;
     * throws on any failure, leaving the database untouched.
     */
    @WorkerThread
    @NonNull
    public BackupRestorer.Result applyRestore(@NonNull BackupData data) throws Exception {
        BackupRestorer.Result result = BackupRestorer.restore(db, data);
        applyPreferences(data.preferences);
        return result;
    }

    /** Preferences live outside Room; they are applied after the database commit succeeded. */
    @WorkerThread
    private void applyPreferences(@NonNull BackupData.Preferences preferences) {
        AppPreferences.setTheme(appContext, preferences.themeMode);
        try {
            AppPreferences.setMissedPolicy(appContext, MissedPolicy.valueOf(preferences.missedPolicy));
        } catch (IllegalArgumentException e) {
            // Validators reject unknown values long before this point.
        }
    }
}
