package com.litemobiletools.dailyexpensetracker.ui.settings;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import com.google.android.material.divider.MaterialDivider;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.ads.AdSupport;
import com.litemobiletools.dailyexpensetracker.backup.BackupMetadataStore;
import com.litemobiletools.dailyexpensetracker.settings.AppPreferences;
import com.litemobiletools.dailyexpensetracker.ui.backup.BackupActivity;
import com.litemobiletools.dailyexpensetracker.ui.main.MainActivity;
import com.litemobiletools.dailyexpensetracker.util.CurrencyChoices;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class SettingsFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        buildRows();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Live summaries (for example the automatic backup status) read storage again.
        if (getView() != null) {
            buildRows();
        }
    }

    private void buildRows() {
        View view = getView();
        if (view == null) {
            return;
        }
        LinearLayout root = view.findViewById(R.id.settings_container);
        root.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());

        LinearLayout prefs = addSection(inflater, root, R.string.settings_section_preferences);
        addRow(inflater, prefs, R.drawable.ic_theme, R.string.settings_appearance,
                currentThemeSummary(), v -> showThemeDialog());
        addRow(inflater, prefs, R.drawable.ic_currency, R.string.settings_currency,
                currencySummary(), v -> showCurrencyDialog());

        LinearLayout data = addSection(inflater, root, R.string.settings_section_data);
        addRow(inflater, data, R.drawable.ic_backup, R.string.settings_backup,
                backupSummary(),
                v -> startActivity(new Intent(requireContext(), BackupActivity.class)));
        addRow(inflater, data, R.drawable.ic_schedule, R.string.settings_auto_backup,
                autoBackupSummary(),
                v -> startActivity(new Intent(requireContext(), BackupActivity.class)));
        addRow(inflater, data, R.drawable.ic_export, R.string.settings_reports_export,
                getString(R.string.settings_reports_export_summary), v -> {
                    if (requireActivity() instanceof MainActivity) {
                        ((MainActivity) requireActivity()).openReports();
                    }
                });

        LinearLayout security = addSection(inflater, root, R.string.settings_section_security);
        addRow(inflater, security, R.drawable.ic_privacy, R.string.settings_privacy,
                getString(R.string.settings_privacy_summary), v -> showPrivacy());
        // Phase 10: UMP requires a permanent privacy-options entry point in regions with consent
        // requirements (EEA/UK consented users, US state privacy laws). The row appears only when
        // Google actually requires it for this user.
        if (AdSupport.privacyOptionsRequired(requireActivity())) {
            addRow(inflater, security, R.drawable.ic_privacy, R.string.settings_ad_privacy,
                    getString(R.string.settings_ad_privacy_summary),
                    v -> AdSupport.showPrivacyOptionsForm(requireActivity(), null));
        }

        LinearLayout about = addSection(inflater, root, R.string.settings_section_about);
        addRow(inflater, about, R.drawable.ic_info, R.string.settings_about,
                getString(R.string.settings_about_summary), v -> showAbout());
    }

    /** One line under "Automatic backup": what is scheduled right now (or simply off). */
    private String autoBackupSummary() {
        AppPreferences.AutoBackupSchedule schedule =
                AppPreferences.getAutoBackupSchedule(requireContext());
        if (!schedule.enabled) {
            return getString(R.string.settings_auto_backup_summary_off);
        }
        Calendar at = Calendar.getInstance();
        at.set(Calendar.HOUR_OF_DAY, schedule.hour);
        at.set(Calendar.MINUTE, 0);
        String time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT,
                Locale.getDefault()).format(at.getTime());
        return getString(R.string.settings_auto_backup_summary_on,
                getString(schedule.weekly ? R.string.auto_frequency_weekly
                        : R.string.auto_frequency_daily), time);
    }

    private LinearLayout addSection(LayoutInflater inflater, LinearLayout root, @StringRes int title) {
        View section = inflater.inflate(R.layout.item_settings_section, root, false);
        ((TextView) section.findViewById(R.id.section_title)).setText(title);
        root.addView(section);
        return section.findViewById(R.id.section_rows);
    }

    private void addRow(LayoutInflater inflater, LinearLayout parent, @DrawableRes int icon,
                            @StringRes int title, String summary,
                            View.OnClickListener listener) {
        if (parent.getChildCount() > 0) {
            MaterialDivider divider = new MaterialDivider(requireContext());
            divider.setDividerInsetStart(getResources().getDimensionPixelSize(R.dimen.space_lg)
                    + getResources().getDimensionPixelSize(R.dimen.icon_size)
                    + getResources().getDimensionPixelSize(R.dimen.space_lg));
            parent.addView(divider);
        }
        View row = inflater.inflate(R.layout.item_settings_row, parent, false);
        ((ImageView) row.findViewById(R.id.row_icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.row_title)).setText(title);
        ((TextView) row.findViewById(R.id.row_summary)).setText(summary);
        row.setContentDescription(getString(R.string.settings_row_description,
                getString(title), summary));
        row.setOnClickListener(listener);
        parent.addView(row);
    }

    private String currentThemeSummary() {
        return getString(R.string.settings_appearance_summary,
                getString(themeLabel(AppPreferences.getTheme(requireContext()))));
    }

    @StringRes
    private static int themeLabel(int theme) {
        switch (theme) {
            case AppPreferences.THEME_LIGHT:
                return R.string.theme_light;
            case AppPreferences.THEME_DARK:
                return R.string.theme_dark;
            default:
                return R.string.theme_system;
        }
    }

    private void showThemeDialog() {
        CharSequence[] labels = {
                getString(R.string.theme_system),
                getString(R.string.theme_light),
                getString(R.string.theme_dark)
        };
        int[] values = {AppPreferences.THEME_SYSTEM, AppPreferences.THEME_LIGHT, AppPreferences.THEME_DARK};
        int checked = AppPreferences.getTheme(requireContext());
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.theme_dialog_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (values[which] != checked) {
                        // Applying night mode recreates the activity, which rebuilds this screen.
                        AppPreferences.setTheme(requireContext(), values[which]);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // ---- Currency (Phase 9) -----------------------------------------------------------------

    /** The row summary: which currency new entries and dashboards use right now. */
    private String currencySummary() {
        String code = AppPreferences.getCurrencyCode(requireContext());
        try {
            return CurrencyChoices.label(code, Locale.getDefault());
        } catch (IllegalArgumentException e) {
            return code;
        }
    }

    /**
     * Currency choice dialog. The explanation and the choices are ONE custom view: a Material
     * dialog does not reliably show a message together with a single-choice list (the list can be
     * dropped entirely, as observed on a real device), so both parts are laid out explicitly and
     * stay scrollable together on small screens and with large font sizes. Selecting a currency
     * persists immediately and closes the dialog; the row above refreshes and every other screen
     * reads the new value the next time it is built.
     */
    private void showCurrencyDialog() {
        String stored = AppPreferences.getCurrencyCode(requireContext());
        List<String> codes = CurrencyChoices.codes(stored);
        View content = getLayoutInflater().inflate(R.layout.dialog_currency_choice, null, false);
        RadioGroup options = content.findViewById(R.id.currency_options);
        final androidx.appcompat.app.AlertDialog[] dialogHolder = new androidx.appcompat.app.AlertDialog[1];
        for (String code : codes) {
            MaterialRadioButton button = (MaterialRadioButton) LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_currency_choice, options, false);
            button.setText(CurrencyChoices.label(code, Locale.getDefault()));
            button.setChecked(code.equals(stored));
            button.setOnCheckedChangeListener((group, checked) -> {
                if (!checked) {
                    return; // Unchecked by the RadioGroup when another currency is chosen.
                }
                AppPreferences.setCurrencyCode(requireContext(), code);
                buildRows();
                if (dialogHolder[0] != null) {
                    dialogHolder[0].dismiss();
                }
            });
            options.addView(button);
        }
        dialogHolder[0] = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_currency)
                .setView(content)
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** The Row summary under "Backup and restore": the last successful backup on this device. */
    private String backupSummary() {
        BackupMetadataStore store = new BackupMetadataStore(requireContext());
        long at = store.lastBackupAtMs();
        if (at == 0) {
            return getString(R.string.settings_backup_summary_never);
        }
        return getString(R.string.settings_backup_summary_last, formatTime(at));
    }

    private String formatTime(long ms) {
        return java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT, java.text.DateFormat.SHORT,
                Locale.getDefault()).format(new java.util.Date(ms));
    }

    private void showPrivacy() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.privacy_dialog_title)
                .setMessage(R.string.privacy_dialog_message)
                .setPositiveButton(R.string.privacy_open_policy, (d, w) -> openPrivacyPolicy())
                .setNegativeButton(R.string.action_ok, null)
                .show();
    }

    private void openPrivacyPolicy() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse(getString(R.string.privacy_policy_url))));
        } catch (android.content.ActivityNotFoundException e) {
            android.widget.Toast.makeText(requireContext(), R.string.privacy_open_failed,
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void showAbout() {
        String version = "";
        try {
            version = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // Version simply stays blank.
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_message, getString(R.string.app_name), version))
                .setPositiveButton(R.string.about_licenses_action, (d, w) -> showLicenses())
                .setNegativeButton(R.string.action_ok, null)
                .show();
    }

    /** Acknowledgments for the official libraries the app is built on; no invented project links. */
    private void showLicenses() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.about_licenses_title)
                .setMessage(R.string.about_licenses_message)
                .setPositiveButton(R.string.action_ok, null)
                .show();
    }
}
