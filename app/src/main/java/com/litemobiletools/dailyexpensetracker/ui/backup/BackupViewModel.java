package com.litemobiletools.dailyexpensetracker.ui.backup;

import android.app.Application;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.litemobiletools.dailyexpensetracker.backup.BackupData;
import com.litemobiletools.dailyexpensetracker.backup.BackupFileNames;
import com.litemobiletools.dailyexpensetracker.backup.BackupFileReader;
import com.litemobiletools.dailyexpensetracker.backup.BackupFormatException;
import com.litemobiletools.dailyexpensetracker.backup.BackupFormat;
import com.litemobiletools.dailyexpensetracker.backup.BackupManager;
import com.litemobiletools.dailyexpensetracker.backup.BackupMetadataStore;
import com.litemobiletools.dailyexpensetracker.backup.BackupOpsLock;
import com.litemobiletools.dailyexpensetracker.backup.BackupParser;
import com.litemobiletools.dailyexpensetracker.backup.BackupRestorer;
import com.litemobiletools.dailyexpensetracker.backup.BackupValidator;
import com.litemobiletools.dailyexpensetracker.data.AppDatabase;
import com.litemobiletools.dailyexpensetracker.drive.AuthOutcome;
import com.litemobiletools.dailyexpensetracker.drive.DriveApi;
import com.litemobiletools.dailyexpensetracker.drive.DriveBackupApi;
import com.litemobiletools.dailyexpensetracker.drive.DriveBackupFileInfo;
import com.litemobiletools.dailyexpensetracker.drive.DriveConnectionManager;
import com.litemobiletools.dailyexpensetracker.drive.DriveConnectionState;
import com.litemobiletools.dailyexpensetracker.drive.DriveConnectionStore;
import com.litemobiletools.dailyexpensetracker.drive.DriveConstants;
import com.litemobiletools.dailyexpensetracker.drive.DriveEvent;
import com.litemobiletools.dailyexpensetracker.drive.DriveOpResult;
import com.litemobiletools.dailyexpensetracker.drive.PlayServicesAuthGateway;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the Drive connection flow AND the manual backup/restore operations alive across rotation
 * and exposes both to the screen. All Drive and database work happens on one background executor;
 * every LiveData update is posted to the main thread. Only one operation runs at a time, and it
 * must not overlap the connection flow (the screen disables the buttons either way).
 *
 * <p>Access tokens exist only inside one operation's call chain. Failures are reported as typed
 * reasons; nothing about the user's data is logged.
 */
public class BackupViewModel extends AndroidViewModel implements DriveConnectionManager.Listener {

    /** One manual backup/restore step the user can trigger. */
    public enum Op {
        /** Build, upload and verify a new backup ("Back Up Now"). */
        BACKUP,
        /** Load the list of backups from the app data folder ("Restore Backup" / "Refresh list"). */
        LIST,
        /** Download and fully validate the chosen backup in memory. */
        RESTORE_DOWNLOAD,
        /** Replace the local data with the validated backup after the user confirmed. */
        RESTORE_APPLY
    }

    /** Why an operation failed; the screen maps each value to a clear, safe message. */
    public enum FailureKind {
        /** Drive is not connected yet; the screen offers the Connect flow. */
        NOT_CONNECTED,
        /** The user cancelled the Google consent screen, or did not grant the Drive permission. */
        AUTH_DENIED,
        /** Another authorization failure (Google status code accompanies it in {@link OpUi}). */
        AUTH_FAILED,
        /** The build/Cloud project is not set up, or access was revoked at Google. */
        AUTH_CONFIG,
        /** No connection or timeout. */
        NETWORK,
        /** Google refused for quota reasons; try later. */
        RATE_LIMITED,
        /** Google server problem; try later. */
        SERVER_ERROR,
        /** The user's Drive is full. */
        DRIVE_FULL,
        /** The chosen backup no longer exists in Drive. */
        NOT_FOUND,
        /** The backup file is damaged, incomplete, corrupt or was altered. */
        MALFORMED,
        /** The backup was made by a newer app version (or another app). */
        VERSION,
        /** Local database work failed; the local data was left unchanged. */
        RESTORE_DB,
        /** Another manual or automatic backup operation is running; this attempt was not started. */
        BUSY,
        /** Anything unexpected. */
        UNKNOWN
    }

    /** Snapshot of one manual operation, for the screen's buttons/progress/messages. */
    public static final class OpUi {
        public enum Mode { WORKING, FAILED, DONE, IDLE }

        @Nullable public final Op op;
        @NonNull public final Mode mode;
        /** 0–100 while progress is known; -1 while indeterminate. */
        public final int percent;
        @Nullable public final FailureKind failure;
        public final int googleCode;
        public final boolean retryable;
        /** Payload after DONE: the verified new backup (BACKUP) or record count (RESTORE_APPLY). */
        @Nullable public final DriveBackupFileInfo doneBackup;
        public final int doneRecords;
        /** For DONE after RESTORE_APPLY: the file the data came from. */
        @Nullable public final DriveBackupFileInfo doneSource;

        OpUi(@Nullable Op op, @NonNull Mode mode, int percent, @Nullable FailureKind failure,
             int googleCode, boolean retryable, @Nullable DriveBackupFileInfo doneBackup,
             int doneRecords, @Nullable DriveBackupFileInfo doneSource) {
            this.op = op;
            this.mode = mode;
            this.percent = percent;
            this.failure = failure;
            this.googleCode = googleCode;
            this.retryable = retryable;
            this.doneBackup = doneBackup;
            this.doneRecords = doneRecords;
            this.doneSource = doneSource;
        }

        @NonNull
        static OpUi working(@NonNull Op op, int percent) {
            return new OpUi(op, Mode.WORKING, percent, null, 0, false, null, 0, null);
        }

        @NonNull
        static OpUi failed(@NonNull Op op, @NonNull FailureKind failure, int googleCode,
                           boolean retryable) {
            return new OpUi(op, Mode.FAILED, -1, failure, googleCode, retryable, null, 0, null);
        }

        @NonNull
        static OpUi doneBackup(@NonNull DriveBackupFileInfo file) {
            return new OpUi(Op.BACKUP, Mode.DONE, 100, null, 0, false, file, 0, null);
        }

        @NonNull
        static OpUi doneRestore(@NonNull DriveBackupFileInfo source, int records) {
            return new OpUi(Op.RESTORE_APPLY, Mode.DONE, 100, null, 0, false, null, records, source);
        }

        @NonNull
        static OpUi idle() {
            return new OpUi(null, Mode.IDLE, -1, null, 0, false, null, 0, null);
        }
    }

    /** A downloaded, fully validated backup waiting for the user's destructive-change confirmation. */
    public static final class PendingRestore {
        @NonNull public final DriveBackupFileInfo source;
        public final long createdAtMs;
        public final int recordCount;
        @NonNull public final BackupData data;

        PendingRestore(@NonNull DriveBackupFileInfo source, long createdAtMs, int recordCount,
                       @NonNull BackupData data) {
            this.source = source;
            this.createdAtMs = createdAtMs;
            this.recordCount = recordCount;
            this.data = data;
        }
    }

    /** The last successful backup this device made (device-local display state). */
    public static final class LastBackup {
        public final long atMs;
        @Nullable public final String name;

        LastBackup(long atMs, @Nullable String name) {
            this.atMs = atMs;
            this.name = name;
        }
    }

    /** Internal carrier of one Drive request's typed failure. */
    private static final class Failure {
        final DriveOpResult.Kind kind;
        final int httpStatus;

        Failure(DriveOpResult.Kind kind, int httpStatus) {
            this.kind = kind;
            this.httpStatus = httpStatus;
        }
    }

    /** Internal carrier of a backup file rejection (typed reason, no record details). */
    private static final class Rejected {
        final BackupFormatException error;

        Rejected(@NonNull BackupFormatException error) {
            this.error = error;
        }
    }

    /** Internal carrier: the shared operation permit was taken (manual or automatic operation). */
    private static final class Busy {
    }

    /** Marker inside the Object result contract for the permission-denied restore apply step. */
    private static final Object BUSY = new Busy();

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DriveConnectionManager manager;
    private final PlayServicesAuthGateway authGateway;
    private final DriveBackupApi backupApi;
    private final BackupManager backupManager;
    private final BackupMetadataStore metadataStore;

    private final MutableLiveData<DriveConnectionState> state =
            new MutableLiveData<>(DriveConnectionState.NOT_CONNECTED);
    private final MutableLiveData<DriveEvent> event = new MutableLiveData<>();
    private final MutableLiveData<PendingIntent> consent = new MutableLiveData<>();
    private final MutableLiveData<OpUi> opState = new MutableLiveData<>(OpUi.idle());
    private final MutableLiveData<List<DriveBackupFileInfo>> backups = new MutableLiveData<>();
    private final MutableLiveData<PendingRestore> pendingRestore = new MutableLiveData<>();
    private final MutableLiveData<LastBackup> lastBackup = new MutableLiveData<>();

    /** Parameters of the most recent attempt, for the screen's Retry button. */
    @Nullable private Op attemptOp;
    @Nullable private String attemptFileId;
    /** The validated backup held between "user picked a backup" and "user confirmed". */
    @Nullable private BackupData heldRestoreData;
    @Nullable private DriveBackupFileInfo heldRestoreSource;
    private boolean busy;
    private boolean disposed;

    public BackupViewModel(@NonNull Application application) {
        super(application);
        this.authGateway = new PlayServicesAuthGateway(application);
        this.backupApi = new DriveBackupApi();
        this.metadataStore = new BackupMetadataStore(application);
        this.backupManager = new BackupManager(AppDatabase.getInstance(application), application);
        this.lastBackup.setValue(currentLastBackup());
        this.manager = new DriveConnectionManager(authGateway, new DriveApi(),
                new DriveConnectionStore.Prefs(application), io, main::post);
        this.manager.setListener(this);
    }

    // ---- Observables -----------------------------------------------------------------

    @NonNull
    public LiveData<DriveConnectionState> state() {
        return state;
    }

    @NonNull
    public LiveData<DriveEvent> event() {
        return event;
    }

    @NonNull
    public LiveData<PendingIntent> consent() {
        return consent;
    }

    @NonNull
    public LiveData<OpUi> opState() {
        return opState;
    }

    @NonNull
    public LiveData<List<DriveBackupFileInfo>> backups() {
        return backups;
    }

    @NonNull
    public LiveData<PendingRestore> pendingRestore() {
        return pendingRestore;
    }

    @NonNull
    public LiveData<LastBackup> lastBackup() {
        return lastBackup;
    }

    // ---- Connection flow (Phase 6 behavior, unchanged) ---------------------------------

    /** Re-checks an earlier connection when the screen opens. Never shows a Google screen. */
    public void refresh() {
        manager.refresh();
    }

    public void connect() {
        manager.connect();
    }

    public void disconnect() {
        manager.disconnect();
    }

    public void consumeEvent() {
        event.setValue(null);
    }

    public void consumeConsent() {
        consent.setValue(null);
    }

    /** Google's status code behind the latest connection failure (0 if none). */
    public int lastErrorCode() {
        return manager.getLastErrorCode();
    }

    /** Delivers what Google's consent screen returned (drives the connection flow). */
    public void onConsentResult(boolean completed, @Nullable Intent data) {
        if (manager.getState() == DriveConnectionState.AUTHORIZING) {
            // Phase 6 connection flow is waiting for this result.
            manager.onConsentResult(completed, data);
            return;
        }
        // A manual backup/restore operation asked for consent; it already reported a retryable
        // failure and its Try again button needs a truthfully CONNECTED state to work.
        if (!completed) {
            return;
        }
        AuthOutcome outcome = authGateway.parseConsentResult(data);
        if (outcome.kind == AuthOutcome.Kind.GRANTED) {
            manager.onDriveAccessVerified();
        } else {
            manager.onOperationAuthFailed(outcome.errorCode);
        }
    }

    // ---- Manual operations (Phase 7) ---------------------------------------------------

    /** True while any operation or the connection flow is running. */
    public boolean isBusy() {
        return busy || manager.getState().isBusy();
    }

    /** "Back Up Now". */
    public void backupNow() {
        start(Op.BACKUP, null);
    }

    /** Loads the list of backups from the app data folder. */
    public void restoreList() {
        start(Op.LIST, null);
    }

    /** Downloads and fully validates the chosen backup; the result lands in {@link #pendingRestore}. */
    public void downloadBackup(@NonNull DriveBackupFileInfo file) {
        start(Op.RESTORE_DOWNLOAD, file.id);
    }

    /** Applies the confirmed backup to the database (destructive, behind the safety snapshot). */
    public void confirmRestore() {
        if (busy || manager.getState().isBusy()) {
            return;
        }
        PendingRestore pending = pendingRestore.getValue();
        if (pending == null) {
            return;
        }
        pendingRestore.setValue(null);
        heldRestoreData = pending.data;
        heldRestoreSource = pending.source;
        attemptOp = Op.RESTORE_APPLY;
        attemptFileId = null;
        busy = true;
        opState.setValue(OpUi.working(Op.RESTORE_APPLY, -1));
        io.execute(() -> {
            Object result;
            if (!BackupOpsLock.tryAcquire()) {
                // The automatic backup worker is running; a restore never competes with it.
                result = BUSY;
            } else {
                try {
                    result = applyFlow();
                } finally {
                    BackupOpsLock.release();
                }
            }
            final Object finalResult = result;
            main.post(() -> deliver(Op.RESTORE_APPLY, finalResult));
        });
    }

    /** Discards the downloaded backup without touching local data. */
    public void cancelRestore() {
        pendingRestore.setValue(null);
        heldRestoreData = null;
        heldRestoreSource = null;
        if (!busy) {
            opState.setValue(OpUi.idle());
        }
    }

    /** Repeats the failed operation with the same parameters (only for recoverable failures). */
    public void retry() {
        OpUi current = opState.getValue();
        if (current == null || current.mode != OpUi.Mode.FAILED || !current.retryable || busy
                || manager.getState().isBusy()) {
            return;
        }
        Op op = current.op;
        if (op == null) {
            return;
        }
        if (op == Op.RESTORE_APPLY) {
            // Retry applies the still-held validated data; nothing is re-downloaded from Drive.
            attemptOp = Op.RESTORE_APPLY;
            attemptFileId = null;
            busy = true;
            opState.setValue(OpUi.working(Op.RESTORE_APPLY, -1));
            io.execute(() -> {
                Object result;
                if (!BackupOpsLock.tryAcquire()) {
                    result = BUSY;
                } else {
                    try {
                        result = applyFlow();
                    } finally {
                        BackupOpsLock.release();
                    }
                }
                final Object finalResult = result;
                main.post(() -> deliver(Op.RESTORE_APPLY, finalResult));
            });
            return;
        }
        start(op, attemptFileId);
    }

    private void start(@NonNull Op op, @Nullable String fileId) {
        if (busy || manager.getState().isBusy()) {
            return;
        }
        if (manager.getState() != DriveConnectionState.CONNECTED) {
            opState.setValue(OpUi.failed(op, FailureKind.NOT_CONNECTED, 0, false));
            return;
        }
        attemptOp = op;
        attemptFileId = fileId;
        busy = true;
        opState.setValue(OpUi.working(op, -1));
        // The token travels only through this call chain; nothing stores it.
        authGateway.authorize(outcome -> {
            if (disposed) {
                busy = false;
                return;
            }
            switch (outcome.kind) {
                case GRANTED:
                    String token = outcome.accessToken();
                    if (token == null) {
                        busy = false;
                        opState.setValue(OpUi.failed(op, FailureKind.AUTH_CONFIG, 0, false));
                        return;
                    }
                    runOperation(op, token);
                    break;
                case NEEDS_CONSENT:
                    // The operation holds the consent intent; the connection state stays truthful.
                    manager.onOperationNeedsConsent();
                    if (outcome.consent != null) {
                        consent.setValue(outcome.consent);
                    }
                    busy = false;
                    opState.setValue(OpUi.failed(op, FailureKind.AUTH_DENIED, 0, true));
                    break;
                default:
                    manager.onOperationAuthFailed(outcome.errorCode);
                    busy = false;
                    opState.setValue(OpUi.failed(op, authFailureKind(outcome.errorCode),
                            outcome.errorCode, false));
                    break;
            }
        });
    }

    private void runOperation(@NonNull Op op, @NonNull String token) {
        io.execute(() -> {
            Object result;
            if (!BackupOpsLock.tryAcquire()) {
                // The automatic backup worker (or another screen action) is operation right now.
                result = BUSY;
            } else {
                try {
                    switch (op) {
                        case BACKUP:
                            result = backupFlow(token);
                            break;
                        case LIST:
                            result = listFlow(token);
                            break;
                        case RESTORE_DOWNLOAD:
                            result = downloadFlow(token);
                            break;
                        default:
                            result = new Failure(DriveOpResult.Kind.FAILED, 0);
                            break;
                    }
                } catch (BackupFormatException e) {
                    result = new Rejected(e);
                } catch (Exception e) {
                    result = new Failure(DriveOpResult.Kind.FAILED, 0);
                } finally {
                    BackupOpsLock.release();
                }
            }
            final Object finalResult = result;
            main.post(() -> deliver(op, finalResult));
        });
    }

    /** Delivers one finished step on the main thread: success, typed failure, or file rejection. */
    private void deliver(@NonNull Op op, @Nullable Object result) {
        busy = false;
        if (disposed) {
            return;
        }
        if (result == BUSY) {
            opState.setValue(OpUi.failed(op, FailureKind.BUSY, 0, false));
            return;
        }
        if (result instanceof Rejected) {
            opState.setValue(OpUi.failed(op, mapRejection(((Rejected) result).error), 0, false));
            return;
        }
        if (result instanceof Failure) {
            applyTaskFailure(op, (Failure) result);
            return;
        }
        if (op == Op.BACKUP && result instanceof DriveBackupFileInfo) {
            manager.onDriveAccessVerified();
            opState.setValue(OpUi.doneBackup((DriveBackupFileInfo) result));
        } else if (op == Op.RESTORE_APPLY && result instanceof Integer) {
            manager.onDriveAccessVerified();
            DriveBackupFileInfo source = heldRestoreSource;
            heldRestoreData = null;
            heldRestoreSource = null;
            opState.setValue(OpUi.doneRestore(source, (Integer) result));
        } else if (op == Op.LIST && result instanceof List) {
            manager.onDriveAccessVerified();
            backups.setValue(new ArrayList<>((List<DriveBackupFileInfo>) result));
            opState.setValue(OpUi.idle());
        } else if (op == Op.RESTORE_DOWNLOAD && result instanceof PendingRestore) {
            PendingRestore pending = (PendingRestore) result;
            heldRestoreData = pending.data;
            heldRestoreSource = pending.source;
            opState.setValue(OpUi.idle());
            pendingRestore.setValue(pending);
        } else {
            opState.setValue(OpUi.idle());
        }
    }

    private void applyTaskFailure(@NonNull Op op, @NonNull Failure failure) {
        if (failure.kind == DriveOpResult.Kind.UNAUTHORIZED) {
            manager.onOperationNeedsConsent();
        }
        FailureKind failureKind = op == Op.RESTORE_APPLY ? FailureKind.RESTORE_DB
                : mapFailure(failure.kind);
        boolean retryable = op == Op.RESTORE_APPLY
                || failure.kind == DriveOpResult.Kind.NETWORK
                || failure.kind == DriveOpResult.Kind.RATE_LIMITED
                || failure.kind == DriveOpResult.Kind.SERVER_ERROR;
        opState.setValue(OpUi.failed(op, failureKind, failure.httpStatus, retryable));
    }

    // ---- Flows (worker threads) -------------------------------------------------------

    @NonNull
    private Object backupFlow(@NonNull String token) {
        // 1) List first: it doubles as a reachability check and deduplicates the file name.
        Object listedOrFailure = listFiles(token);
        if (listedOrFailure instanceof Failure) {
            return listedOrFailure;
        }
        @SuppressWarnings("unchecked")
        List<DriveBackupFileInfo> existing = (List<DriveBackupFileInfo>) listedOrFailure;
        Set<String> names = new HashSet<>();
        for (DriveBackupFileInfo info : existing) {
            names.add(info.name);
        }
        String name = BackupFileNames.unique(
                BackupFileNames.fileName(System.currentTimeMillis(), java.util.TimeZone.getDefault()),
                names);

        // 2) Serialize the database (one consistent snapshot) on this same worker thread.
        byte[] bytes = backupManager.buildBackupBytes();
        if (bytes.length > DriveConstants.MAX_BACKUP_BYTES) {
            return new Failure(DriveOpResult.Kind.TOO_LARGE, 0);
        }

        // 3) Upload with progress; 4) verify what Drive actually stored.
        Object uploadedOrFailure = uploadBackup(token, name, bytes);
        if (uploadedOrFailure instanceof Failure) {
            return uploadedOrFailure;
        }
        DriveBackupFileInfo uploaded = (DriveBackupFileInfo) uploadedOrFailure;
        DriveOpResult<DriveBackupFileInfo> verified = backupApi.verify(token, uploaded.id);
        if (verified.kind != DriveOpResult.Kind.OK) {
            return new Failure(verified.kind, verified.httpStatus);
        }
        if (verified.value == null || !uploaded.name.equals(verified.value.name)
                || verified.value.sizeBytes != bytes.length) {
            return new Failure(DriveOpResult.Kind.MALFORMED_RESPONSE, 0);
        }

        metadataStore.recordSuccess(System.currentTimeMillis(), name);
        main.post(this::reloadLastBackup);
        return verified.value;
    }

    @NonNull
    private Object uploadBackup(@NonNull String token, @NonNull String name, @NonNull byte[] bytes) {
        DriveOpResult<DriveBackupFileInfo> uploaded = backupApi.upload(token, name, bytes,
                (done, total) -> main.post(() -> {
                    OpUi current = opState.getValue();
                    if (current != null && current.mode == OpUi.Mode.WORKING
                            && current.op == Op.BACKUP) {
                        opState.setValue(OpUi.working(Op.BACKUP, percent(done, total)));
                    }
                }));
        if (uploaded.kind != DriveOpResult.Kind.OK) {
            return new Failure(uploaded.kind, uploaded.httpStatus);
        }
        return uploaded.value;
    }

    @NonNull
    private Object listFlow(@NonNull String token) {
        return listFiles(token);
    }

    /** Lists the backup files, or a {@link Failure} when Drive refuses. */
    @NonNull
    private Object listFiles(@NonNull String token) {
        DriveOpResult<List<DriveBackupFileInfo>> result =
                backupApi.listAppDataFiles(token, BackupFileNames::isBackupFileName);
        if (result.kind != DriveOpResult.Kind.OK || result.value == null) {
            return new Failure(result.kind, result.httpStatus);
        }
        return result.value;
    }

    /**
     * Downloads the chosen file and validates it completely before anything local happens.
     * Returns a {@link PendingRestore} on success, a {@link Failure} for Drive problems, and
     * throws {@link BackupFormatException} when the file itself is rejected.
     */
    @NonNull
    private Object downloadFlow(@NonNull String token) throws BackupFormatException {
        String fileId = attemptFileId;
        if (fileId == null) {
            throw new BackupFormatException(BackupFormatException.Reason.MALFORMED_HEADER,
                    "no file chosen");
        }
        DriveOpResult<byte[]> downloaded = backupApi.download(token, fileId,
                (done, total) -> main.post(() -> {
                    OpUi current = opState.getValue();
                    if (current != null && current.mode == OpUi.Mode.WORKING
                            && current.op == Op.RESTORE_DOWNLOAD) {
                        opState.setValue(OpUi.working(Op.RESTORE_DOWNLOAD, percent(done, total)));
                    }
                }));
        if (downloaded.kind != DriveOpResult.Kind.OK) {
            return new Failure(downloaded.kind, downloaded.httpStatus);
        }
        BackupFileReader.BackupFile file =
                BackupFileReader.read(downloaded.value, BackupFormat.FORMAT_VERSION);
        BackupData data = BackupParser.parse(file);
        BackupValidator.validate(data, getApplication().getPackageName(),
                backupManager.currentSchemaVersion());
        DriveOpResult<DriveBackupFileInfo> meta = backupApi.verify(token, fileId);
        DriveBackupFileInfo source = (meta.kind == DriveOpResult.Kind.OK && meta.value != null)
                ? meta.value : new DriveBackupFileInfo(fileId, "", 0, 0);
        return new PendingRestore(source, data.createdAtMs, data.recordCount(), data);
    }

    /**
     * The confirmed destructive step: a local safety snapshot of the CURRENT data first, then the
     * single-transaction replace. Any failure leaves the database untouched; a successful restore
     * removes the safety snapshot again. Nothing in Drive is ever read, changed or deleted here.
     */
    @NonNull
    private Object applyFlow() {
        BackupData data = heldRestoreData;
        if (data == null) {
            return new Failure(DriveOpResult.Kind.FAILED, 0);
        }
        try {
            backupManager.writeSafetySnapshot();
        } catch (Exception e) {
            return new Failure(DriveOpResult.Kind.FAILED, 0);
        }
        try {
            BackupRestorer.Result restored = backupManager.applyRestore(data);
            backupManager.deleteSafetySnapshot();
            return restored.records;
        } catch (Exception e) {
            // The transaction rolled back; the local data is unchanged. The safety snapshot stays
            // on the device: it is the copy of the data as it was before the failed attempt.
            return new Failure(DriveOpResult.Kind.FAILED, 0);
        }
    }

    // ---- Helpers ------------------------------------------------------------------------

    private void reloadLastBackup() {
        lastBackup.setValue(currentLastBackup());
    }

    @NonNull
    private LastBackup currentLastBackup() {
        return new LastBackup(metadataStore.lastBackupAtMs(), metadataStore.lastBackupName());
    }

    private static int percent(long done, long total) {
        if (total <= 0) {
            return -1;
        }
        return (int) Math.min(100, Math.max(0, Math.round(100.0 * done / total)));
    }

    /** Maps a Google status code behind an operation's authorization failure to a kind for the UI. */
    private static FailureKind authFailureKind(int googleCode) {
        switch (googleCode) {
            case 10:    // DEVELOPER_ERROR (build not configured)
            case 8:     // INTERNAL_ERROR (in practice the same setup mismatch)
                return FailureKind.AUTH_CONFIG;
            case 7:     // NETWORK_ERROR
            case 15:    // TIMEOUT
                return FailureKind.NETWORK;
            case 16:    // CANCELED
            case 12501: // SIGN_IN_CANCELLED
            case AuthOutcome.DENIED:
                return FailureKind.AUTH_DENIED;
            default:
                return FailureKind.AUTH_FAILED;
        }
    }

    /** Maps a Drive HTTP failure kind to a kind for the UI. */
    private static FailureKind mapFailure(DriveOpResult.Kind kind) {
        switch (kind) {
            case UNAUTHORIZED:
                return FailureKind.AUTH_FAILED;
            case FORBIDDEN:
            case API_NOT_ENABLED:
                return FailureKind.AUTH_CONFIG;
            case RATE_LIMITED:
                return FailureKind.RATE_LIMITED;
            case SERVER_ERROR:
                return FailureKind.SERVER_ERROR;
            case NOT_FOUND:
                return FailureKind.NOT_FOUND;
            case DRIVE_FULL:
                return FailureKind.DRIVE_FULL;
            case NETWORK:
                return FailureKind.NETWORK;
            default:
                return FailureKind.UNKNOWN;
        }
    }

    /** Maps a backup file rejection to a kind for the UI (never record details, only the type). */
    private static FailureKind mapRejection(@NonNull BackupFormatException e) {
        switch (e.reason) {
            case NEWER_VERSION:
            case UNSUPPORTED_VERSION:
            case WRONG_APP_ID:
                return FailureKind.VERSION;
            default:
                return FailureKind.MALFORMED;
        }
    }

    // ---- Listeners ---------------------------------------------------------------------

    @Override
    public void onStateChanged(@NonNull DriveConnectionState next) {
        state.setValue(next);
    }

    @Override
    public void onEvent(@NonNull DriveEvent next) {
        event.setValue(next);
    }

    @Override
    public void onConsentRequired(@NonNull PendingIntent pending) {
        consent.setValue(pending);
    }

    @Override
    protected void onCleared() {
        manager.setListener(null);
        disposed = true;
        io.shutdownNow();
    }
}
