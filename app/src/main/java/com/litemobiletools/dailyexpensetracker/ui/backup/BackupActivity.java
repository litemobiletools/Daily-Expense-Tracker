package com.litemobiletools.dailyexpensetracker.ui.backup;

import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.result.IntentSenderRequest;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.common.util.concurrent.ListenableFuture;
import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.backup.AutoBackupScheduler;
import com.litemobiletools.dailyexpensetracker.backup.BackupFileNames;
import com.litemobiletools.dailyexpensetracker.backup.BackupMetadataStore;
import com.litemobiletools.dailyexpensetracker.drive.DriveBackupFileInfo;
import com.litemobiletools.dailyexpensetracker.drive.DriveConnectionState;
import com.litemobiletools.dailyexpensetracker.drive.DriveConstants;
import com.litemobiletools.dailyexpensetracker.drive.DriveEvent;
import com.litemobiletools.dailyexpensetracker.ui.backup.BackupViewModel.FailureKind;
import com.litemobiletools.dailyexpensetracker.ui.backup.BackupViewModel.LastBackup;
import com.litemobiletools.dailyexpensetracker.ui.backup.BackupViewModel.Op;
import com.litemobiletools.dailyexpensetracker.ui.backup.BackupViewModel.OpUi;
import com.litemobiletools.dailyexpensetracker.ui.backup.BackupViewModel.PendingRestore;
import com.litemobiletools.dailyexpensetracker.ui.common.SecondaryActivity;
import com.litemobiletools.dailyexpensetracker.settings.AppPreferences;
import com.litemobiletools.dailyexpensetracker.settings.AppPreferences.AutoBackupSchedule;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Backup and restore settings: the Google Drive connection (Connect/Disconnect) plus the manual
 * "Back up now" and "Restore backup" operations. Drive is contacted only after the user taps
 * Connect or triggers one of the two manual actions (or to re-check an earlier connection when
 * this screen opens). Restore replaces all local data only behind an explicit confirmation.
 */
public class BackupActivity extends SecondaryActivity {

    private BackupViewModel viewModel;
    private TextView statusTitle;
    private TextView statusDetail;
    private TextView lastBackupText;
    private TextView opStatus;
    private LinearProgressIndicator progress;
    private LinearProgressIndicator opProgress;
    private MaterialButton connect;
    private MaterialButton disconnect;
    private MaterialButton backupNow;
    private MaterialButton backupRestore;
    private MaterialButton backupRetry;
    private TextView backupListEmpty;
    private RecyclerView backupList;
    private BackupFilesAdapter backupAdapter;
    private DriveConnectionState shown = DriveConnectionState.NOT_CONNECTED;
    @Nullable private PendingRestore shownRestore;

    // Automatic backup (Phase 8)
    private MaterialSwitch autoSwitch;
    private RadioGroup autoFrequencyGroup;
    private View autoOptions;
    private TextView autoExplanation;
    private TextView autoTimeRow;
    private TextView autoStatusText;
    private TextView autoNextText;
    private TextView autoStatusHeading;
    private boolean autoSyncing;

    private final ActivityResultLauncher<IntentSenderRequest> consentLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), this::onConsentResult);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initScreen(savedInstanceState, R.layout.activity_backup, R.string.drive_title);

        statusTitle = findViewById(R.id.drive_status_title);
        statusDetail = findViewById(R.id.drive_status_detail);
        progress = findViewById(R.id.drive_progress);
        connect = findViewById(R.id.drive_connect);
        disconnect = findViewById(R.id.drive_disconnect);
        lastBackupText = findViewById(R.id.backup_last_backup);
        opStatus = findViewById(R.id.backup_op_status);
        opProgress = findViewById(R.id.backup_op_progress);
        backupNow = findViewById(R.id.backup_now);
        backupRestore = findViewById(R.id.backup_restore);
        backupRetry = findViewById(R.id.backup_retry);
        backupListEmpty = findViewById(R.id.backup_list_empty);

        backupAdapter = new BackupFilesAdapter(this::confirmPickBackup);
        backupList = findViewById(R.id.backup_list);
        backupList.setLayoutManager(new LinearLayoutManager(this));
        backupList.setAdapter(backupAdapter);

        // Automatic backup (Phase 8)
        autoSwitch = findViewById(R.id.auto_switch);
        autoFrequencyGroup = findViewById(R.id.auto_frequency_group);
        autoOptions = findViewById(R.id.auto_options);
        autoExplanation = findViewById(R.id.auto_explanation);
        autoTimeRow = findViewById(R.id.auto_time_row);
        autoStatusHeading = findViewById(R.id.auto_status_heading);
        autoStatusText = findViewById(R.id.auto_status_text);
        autoNextText = findViewById(R.id.auto_next_text);

        viewModel = new ViewModelProvider(this).get(BackupViewModel.class);
        viewModel.state().observe(this, this::render);
        viewModel.event().observe(this, this::showEvent);
        viewModel.consent().observe(this, this::launchConsent);
        viewModel.lastBackup().observe(this, this::renderLastBackup);
        viewModel.backups().observe(this, this::renderBackupList);
        viewModel.opState().observe(this, this::renderOp);
        viewModel.pendingRestore().observe(this, this::confirmPendingRestore);

        backupNow.setOnClickListener(v -> askBackupNow());
        backupRestore.setOnClickListener(v -> viewModel.restoreList());
        backupRetry.setOnClickListener(v -> viewModel.retry());
        connect.setOnClickListener(v -> confirmConnect());
        disconnect.setOnClickListener(v -> confirmDisconnect());
        findViewById(R.id.drive_permissions).setOnClickListener(v -> openPermissionsPage());

        // Automatic backup controls (Phase 8). The listeners only run while the controls are not
        // being synced from storage (the flag suppresses programmatic events).
        autoSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (autoSyncing) {
                return;
            }
            confirmAutoToggle(isChecked);
        });
        autoFrequencyGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (autoSyncing) {
                return;
            }
            applyAutoFrequency(checkedId == R.id.auto_frequency_weekly);
        });
        autoTimeRow.setOnClickListener(v -> showAutoTimeDialog());

        if (savedInstanceState == null) {
            viewModel.refresh();
        }
    }

    // ---- Connection status (Phase 6) ----------------------------------------------------

    private void render(@NonNull DriveConnectionState state) {
        shown = state;
        int title;
        int detail;
        switch (state) {
            case AUTHORIZING:
                title = R.string.drive_state_authorizing;
                detail = R.string.drive_state_authorizing_detail;
                break;
            case CHECKING:
                title = R.string.drive_state_checking;
                detail = R.string.drive_state_checking_detail;
                break;
            case CONNECTED:
                title = R.string.drive_state_connected;
                detail = R.string.drive_state_connected_detail;
                break;
            case DISCONNECTING:
                title = R.string.drive_state_disconnecting;
                detail = R.string.drive_state_disconnecting_detail;
                break;
            case REAUTH_REQUIRED:
                title = R.string.drive_state_reauth;
                detail = R.string.drive_state_reauth_detail;
                break;
            case NETWORK_UNAVAILABLE:
                title = R.string.drive_state_network;
                detail = R.string.drive_state_network_detail;
                break;
            case SERVICE_UNAVAILABLE:
                title = R.string.drive_state_service;
                detail = R.string.drive_state_service_detail;
                break;
            case DENIED:
                title = R.string.drive_state_denied;
                detail = R.string.drive_state_denied_detail;
                break;
            case CONFIG_ERROR:
                title = R.string.drive_state_config;
                detail = R.string.drive_state_config_detail;
                break;
            default:
                title = R.string.drive_state_not_connected;
                detail = R.string.drive_state_not_connected_detail;
                break;
        }
        statusTitle.setText(title);
        int code = viewModel.lastErrorCode();
        boolean failure = state == DriveConnectionState.CONFIG_ERROR || state == DriveConnectionState.DENIED
                || state == DriveConnectionState.SERVICE_UNAVAILABLE;
        statusDetail.setText(failure && code != 0
                ? getString(R.string.drive_detail_with_code, getString(detail), code)
                : getString(detail));

        boolean busy = state.isBusy();
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);

        boolean connected = state == DriveConnectionState.CONNECTED;
        connect.setVisibility(connected || state == DriveConnectionState.DISCONNECTING ? View.GONE : View.VISIBLE);
        connect.setEnabled(!busy);
        connect.setText(connectLabel(state));
        // Disconnect is offered whenever an earlier connection may still exist (not only when verified).
        boolean canDisconnect = connected || state == DriveConnectionState.REAUTH_REQUIRED
                || state == DriveConnectionState.NETWORK_UNAVAILABLE
                || state == DriveConnectionState.SERVICE_UNAVAILABLE;
        disconnect.setVisibility(canDisconnect ? View.VISIBLE : View.GONE);
        disconnect.setEnabled(!busy);
        syncButtons();
    }

    @StringRes
    private static int connectLabel(@NonNull DriveConnectionState state) {
        switch (state) {
            case REAUTH_REQUIRED:
                return R.string.drive_reconnect;
            case NETWORK_UNAVAILABLE:
            case SERVICE_UNAVAILABLE:
            case DENIED:
            case CONFIG_ERROR:
                return R.string.drive_try_again;
            default:
                return R.string.drive_connect;
        }
    }

    private void confirmConnect() {
        if (shown.isBusy()) {
            return;
        }
        if (shown == DriveConnectionState.REAUTH_REQUIRED || shown == DriveConnectionState.NETWORK_UNAVAILABLE
                || shown == DriveConnectionState.SERVICE_UNAVAILABLE) {
            viewModel.connect();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.drive_connect_dialog_title)
                .setMessage(R.string.drive_connect_dialog_message)
                .setPositiveButton(R.string.drive_connect_continue, (d, w) -> viewModel.connect())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmDisconnect() {
        if (shown.isBusy()) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.drive_disconnect_dialog_title)
                .setMessage(R.string.drive_disconnect_dialog_message)
                .setPositiveButton(R.string.drive_disconnect, (d, w) -> viewModel.disconnect())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void launchConsent(PendingIntent pending) {
        if (pending == null) {
            return;
        }
        viewModel.consumeConsent();
        try {
            consentLauncher.launch(new IntentSenderRequest.Builder(pending.getIntentSender()).build());
        } catch (RuntimeException e) {
            // Could not open Google's screen; report as a cancelled attempt rather than crash.
            viewModel.onConsentResult(false, null);
        }
    }

    private void onConsentResult(@NonNull ActivityResult result) {
        viewModel.onConsentResult(result.getResultCode() == RESULT_OK, result.getData());
    }

    private void showEvent(DriveEvent event) {
        if (event == null) {
            return;
        }
        viewModel.consumeEvent();
        int message;
        switch (event) {
            case CONNECTED:
                // A successful reconnect restores automatic backup (when the user wants it on)
                // without duplicating workers: the scheduler's unique UPDATE policy handles it.
                if (AppPreferences.getAutoBackupSchedule(this).enabled) {
                    AutoBackupScheduler.apply(this);
                }
                message = R.string.drive_event_connected;
                break;
            case CANCELLED:
                message = R.string.drive_event_cancelled;
                break;
            case DISCONNECTED_REVOKED:
                message = R.string.drive_event_disconnected_revoked;
                break;
            case DISCONNECTED_LOCAL_ONLY:
                // Disconnecting stops future automatic backups; reconnecting restores them.
                AutoBackupScheduler.cancel(this);
                message = R.string.drive_event_disconnected_local;
                break;
            default:
                message = R.string.drive_event_disconnected_local;
                break;
        }
        Snackbar.make(findViewById(R.id.screen_root), message, Snackbar.LENGTH_LONG).show();
    }

    private void openPermissionsPage() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(DriveConstants.ACCOUNT_PERMISSIONS_URL)));
        } catch (ActivityNotFoundException e) {
            Snackbar.make(findViewById(R.id.screen_root), R.string.drive_open_failed, Snackbar.LENGTH_SHORT).show();
        }
    }

    // ---- Manual backup / restore (Phase 7) ----------------------------------------------

    private void renderLastBackup(@Nullable LastBackup lastBackup) {
        if (lastBackup == null) {
            return;
        }
        if (lastBackup.atMs > 0) {
            lastBackupText.setText(getString(R.string.backup_last_ok, formatTime(lastBackup.atMs)));
        } else {
            lastBackupText.setText(getString(R.string.backup_last_never));
        }
    }

    private void renderBackupList(@Nullable List<DriveBackupFileInfo> files) {
        backupAdapter.submitList(files);
        boolean empty = files == null || files.isEmpty();
        backupListEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        backupList.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void renderOp(@NonNull OpUi ui) {
        switch (ui.mode) {
            case IDLE:
                opStatus.setVisibility(View.GONE);
                opProgress.setVisibility(View.GONE);
                backupRetry.setVisibility(View.GONE);
                break;
            case WORKING:
                opStatus.setVisibility(View.VISIBLE);
                opStatus.setText(ui.op == Op.RESTORE_APPLY
                        ? getString(R.string.restore_running_status)
                        : getString(R.string.backup_working));
                setOpProgress(ui.percent);
                backupRetry.setVisibility(View.GONE);
                break;
            case FAILED:
                opStatus.setVisibility(View.VISIBLE);
                opStatus.setText(failureMessage(ui));
                opProgress.setVisibility(View.GONE);
                backupRetry.setVisibility(ui.retryable ? View.VISIBLE : View.GONE);
                if (ui.failure == FailureKind.NOT_CONNECTED) {
                    offerConnect();
                }
                break;
            case DONE:
                opStatus.setVisibility(View.GONE);
                opProgress.setVisibility(View.GONE);
                backupRetry.setVisibility(View.GONE);
                if (ui.op == Op.BACKUP && ui.doneBackup != null) {
                    Snackbar.make(findViewById(R.id.screen_root),
                            getString(R.string.backup_done, ui.doneBackup.name), Snackbar.LENGTH_LONG).show();
                } else if (ui.op == Op.RESTORE_APPLY) {
                    Snackbar.make(findViewById(R.id.screen_root),
                            getResources().getQuantityString(R.plurals.backup_restore_done,
                                    ui.doneRecords, ui.doneRecords), Snackbar.LENGTH_LONG).show();
                }
                break;
        }
        syncButtons();
    }

    private void setOpProgress(int percent) {
        boolean indeterminate = percent < 0;
        opProgress.setVisibility(View.VISIBLE);
        if (indeterminate != opProgress.isIndeterminate()) {
            // LinearProgressIndicator requires a visible-state change to flip its mode.
            opProgress.setVisibility(View.INVISIBLE);
            opProgress.setIndeterminate(indeterminate);
            opProgress.setVisibility(View.VISIBLE);
        }
        if (!indeterminate) {
            opProgress.setProgressCompat(percent, true);
        }
    }

    private String failureMessage(@NonNull OpUi ui) {
        FailureKind kind = ui.failure == null ? FailureKind.UNKNOWN : ui.failure;
        int res;
        switch (kind) {
            case NOT_CONNECTED:
                res = R.string.backup_fail_not_connected;
                break;
            case BUSY:
                res = R.string.backup_fail_busy;
                break;
            case AUTH_CONFIG:
                res = R.string.backup_fail_auth_config;
                break;
            case AUTH_DENIED:
                res = R.string.backup_fail_denied;
                break;
            case AUTH_FAILED:
                res = R.string.backup_fail_auth;
                break;
            case NETWORK:
                res = R.string.backup_fail_network;
                break;
            case RATE_LIMITED:
                res = R.string.backup_fail_rate_limited;
                break;
            case SERVER_ERROR:
                res = R.string.backup_fail_server;
                break;
            case DRIVE_FULL:
                res = R.string.backup_fail_drive_full;
                break;
            case NOT_FOUND:
                res = R.string.backup_fail_not_found;
                break;
            case MALFORMED:
                res = R.string.backup_fail_malformed;
                break;
            case VERSION:
                res = R.string.backup_fail_version;
                break;
            case RESTORE_DB:
                res = R.string.backup_fail_restore_db;
                break;
            default:
                res = R.string.backup_fail_unknown;
                break;
        }
        String text = getString(res);
        return ui.googleCode != 0
                ? getString(R.string.drive_detail_with_code, text, ui.googleCode) : text;
    }

    private void offerConnect() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.backup_fail_connect_prompt_title)
                .setMessage(R.string.backup_fail_not_connected)
                .setPositiveButton(R.string.drive_connect, (d, w) -> viewModel.connect())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void askBackupNow() {
        if (viewModel.isBusy()) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.backup_confirm_title)
                .setMessage(R.string.backup_confirm_message)
                .setPositiveButton(R.string.backup_now_action, (d, w) -> viewModel.backupNow())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmPickBackup(@NonNull DriveBackupFileInfo file) {
        if (viewModel.isBusy()) {
            return;
        }
        long when = BackupFileNames.timestampFromName(file.name);
        if (when == 0) {
            when = file.modifiedTimeMs;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.restore_pick_title)
                .setMessage(getString(R.string.restore_pick_message,
                        when != 0 ? formatTime(when) : getString(R.string.backup_unknown_time)))
                .setPositiveButton(R.string.backup_restore_action,
                        (d, w) -> viewModel.downloadBackup(file))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmPendingRestore(@Nullable PendingRestore pending) {
        if (pending == null || pending == shownRestore) {
            return;
        }
        shownRestore = pending; // rotation re-delivers the same object: no duplicate dialog
        long when = pending.createdAtMs;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.restore_confirm_title)
                .setMessage(getString(R.string.restore_confirm_message,
                        when > 0 ? formatTime(when) : getString(R.string.backup_unknown_time),
                        pending.recordCount))
                .setPositiveButton(R.string.restore_confirm_positive,
                        (d, w) -> viewModel.confirmRestore())
                .setNegativeButton(R.string.action_cancel, (d, w) -> viewModel.cancelRestore())
                .setOnCancelListener(d -> viewModel.cancelRestore())
                .show();
    }

    private String formatTime(long ms) {
        return DateUtils.formatDateTime(this, ms,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME);
    }

    /** The manual actions need a verified connection and no running work. */
    private void syncButtons() {
        boolean ready = shown == DriveConnectionState.CONNECTED && !viewModel.isBusy();
        backupNow.setEnabled(ready);
        backupRestore.setEnabled(ready);
    }

    // ---- Automatic backup (Phase 8) --------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        renderAuto();
    }

    /** Syncs every control from stored settings, then refreshes the status and next-run info. */
    private void renderAuto() {
        AutoBackupSchedule schedule = AppPreferences.getAutoBackupSchedule(this);
        autoSyncing = true;
        autoSwitch.setChecked(schedule.enabled);
        ((android.widget.RadioButton) autoFrequencyGroup.findViewById(R.id.auto_frequency_daily))
                .setChecked(!schedule.weekly);
        ((android.widget.RadioButton) autoFrequencyGroup.findViewById(R.id.auto_frequency_weekly))
                .setChecked(schedule.weekly);
        autoTimeRow.setText(getString(R.string.auto_time_summary, hourLabel(schedule.hour)));
        autoSyncing = false;

        autoOptions.setVisibility(schedule.enabled ? View.VISIBLE : View.GONE);
        autoExplanation.setText(schedule.enabled
                ? getString(R.string.auto_card_explanation,
                        schedule.weekly ? getString(R.string.auto_frequency_weekly)
                                : getString(R.string.auto_frequency_daily),
                        hourLabel(schedule.hour))
                : getString(R.string.auto_card_off));
        autoStatusHeading.setText(R.string.auto_status_heading);
        autoStatusText.setText(autoStatusMessage(schedule));
        renderAutoNext();
    }

    /** Reads the recorded automatic backup state and maps it to readable, data-free text. */
    private String autoStatusMessage(@NonNull AutoBackupSchedule schedule) {
        if (!schedule.enabled) {
            return getString(R.string.auto_card_off);
        }
        BackupMetadataStore meta = new BackupMetadataStore(this);
        BackupMetadataStore.AutoBackupState state = meta.autoState();
        long atMs = meta.autoStateAtMs();
        // An attempt recorded "in progress" long ago really means the process died mid-run.
        if (state == BackupMetadataStore.AutoBackupState.IN_PROGRESS
                && System.currentTimeMillis() - atMs > 30 * 60_000L) {
            state = BackupMetadataStore.AutoBackupState.FAILED;
        }
        switch (state) {
            case NOT_RUN_YET:
                return getString(R.string.auto_state_not_run_yet);
            case IN_PROGRESS:
                return getString(R.string.auto_state_in_progress);
            case SUCCESS:
                return getString(R.string.auto_state_success, successTime(meta));
            case UP_TO_DATE:
                return getString(R.string.auto_state_up_to_date, successTime(meta));
            case WAITING_FOR_NETWORK:
                return getString(R.string.auto_state_waiting_network);
            case AUTH_REQUIRED:
                return getString(R.string.auto_state_auth_required);
            default:
                int httpStatus = meta.autoLastHttpStatus();
                return httpStatus > 0
                        ? getString(R.string.auto_state_failed_with_code, httpStatus)
                        : getString(R.string.auto_state_failed);
        }
    }

    private String successTime(@NonNull BackupMetadataStore meta) {
        return meta.autoSuccessAtMs() > 0 ? formatTime(meta.autoSuccessAtMs())
                : getString(R.string.backup_unknown_time);
    }

    /**
     * The next scheduled run, when WorkManager can report it. Approximate by design: Android may
     * delay background work, and the on/off state is re-applied on every app start, so the
     * existence of the unique work truthfully reflects the stored setting.
     */
    private void renderAutoNext() {
        AutoBackupSchedule schedule = AppPreferences.getAutoBackupSchedule(this);
        if (!schedule.enabled) {
            autoNextText.setText(R.string.auto_not_scheduled);
            return;
        }
        // WorkManager answers from its own database: read it off the main thread and show the result
        // when it arrives (never block the UI over a scheduling question).
        autoNextText.setText(R.string.auto_next_run_unknown);
        final ListenableFuture<List<WorkInfo>> future = WorkManager.getInstance(this)
                .getWorkInfosForUniqueWork(AutoBackupScheduler.WORK_NAME);
        future.addListener(() -> {
            if (isDestroyed() || !AppPreferences.getAutoBackupSchedule(this).enabled) {
                return;
            }
            try {
                long nextMs = 0;
                for (WorkInfo info : future.get()) {
                    if (info.getState() == WorkInfo.State.ENQUEUED
                            && info.getNextScheduleTimeMillis() > nextMs) {
                        nextMs = info.getNextScheduleTimeMillis();
                    }
                }
                if (nextMs > 0) {
                    autoNextText.setText(getString(R.string.auto_next_run, formatTime(nextMs)));
                }
            } catch (Exception e) {
                // Keep the "unknown" text already shown.
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void confirmAutoToggle(boolean turnOn) {
        if (turnOn) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.auto_enable_confirm_title)
                    .setMessage(R.string.auto_enable_confirm_message)
                    .setPositiveButton(R.string.auto_on, (d, w) -> applyAutoSchedule(true))
                    .setNegativeButton(R.string.action_cancel, (d, w) -> renderAuto())
                    .setOnCancelListener(d -> renderAuto())
                    .show();
        } else {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.auto_disable_confirm_title)
                    .setMessage(R.string.auto_disable_confirm_message)
                    .setPositiveButton(R.string.auto_off, (d, w) -> applyAutoSchedule(false))
                    .setNegativeButton(R.string.action_cancel, (d, w) -> renderAuto())
                    .setOnCancelListener(d -> renderAuto())
                    .show();
        }
    }

    private void applyAutoSchedule(boolean enabled) {
        AutoBackupSchedule stored = AppPreferences.getAutoBackupSchedule(this);
        AutoBackupSchedule updated = new AutoBackupSchedule(enabled, stored.weekly, stored.hour);
        AppPreferences.setAutoBackupSchedule(this, updated);
        // Schedules the unique work when enabling, cancels it when disabling. Never duplicates.
        AutoBackupScheduler.apply(this, updated);
        renderAuto();
    }

    private void applyAutoFrequency(boolean weekly) {
        AutoBackupSchedule stored = AppPreferences.getAutoBackupSchedule(this);
        AutoBackupSchedule updated = new AutoBackupSchedule(stored.enabled, weekly, stored.hour);
        AppPreferences.setAutoBackupSchedule(this, updated);
        // UPDATE replaces the unique schedule in place: the new period applies, no duplicates.
        AutoBackupScheduler.apply(this, updated);
        renderAuto();
    }

    private void showAutoTimeDialog() {
        AutoBackupSchedule schedule = AppPreferences.getAutoBackupSchedule(this);
        CharSequence[] labels = new CharSequence[24];
        for (int h = 0; h < 24; h++) {
            labels[h] = hourLabel(h);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.auto_time_dialog_title)
                .setSingleChoiceItems(labels, schedule.hour, (dialog, which) -> {
                    dialog.dismiss();
                    AutoBackupSchedule updated = new AutoBackupSchedule(schedule.enabled,
                            schedule.weekly, which);
                    AppPreferences.setAutoBackupSchedule(this, updated);
                    if (updated.enabled) {
                        // A new anchor time only shapes the following scheduled run.
                        AutoBackupScheduler.apply(this, updated);
                    }
                    renderAuto();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 0..23 as a 12-hour label with am/pm in the device locale. */
    private String hourLabel(int hour) {
        Calendar at = Calendar.getInstance();
        at.set(Calendar.HOUR_OF_DAY, hour);
        at.set(Calendar.MINUTE, 0);
        return java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT,
                Locale.getDefault()).format(at.getTime());
    }
}
