package com.litemobiletools.dailyexpensetracker;

import android.app.Application;

import com.litemobiletools.dailyexpensetracker.backup.AutoBackupScheduler;
import com.litemobiletools.dailyexpensetracker.drive.DriveConnectionStore;
import com.litemobiletools.dailyexpensetracker.settings.AppPreferences;

public class DailyExpenseApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AppPreferences.applyStoredTheme(this);
        // Phase 8: keep the automatic backup work in sync with the stored setting. Unique work with
        // the UPDATE policy makes this idempotent: repeated launches update the same job instead of
        // creating duplicates, and WorkManager persists the schedule across reboots.
        // Phase 9: only when Drive access is still wanted — an explicit disconnect cancels the job
        // and clearing that flag keeps it stopped across launches until the user reconnects.
        if (AppPreferences.getAutoBackupSchedule(this).enabled
                && new DriveConnectionStore.Prefs(this).isConnectionRequested()) {
            AutoBackupScheduler.apply(this);
        }
    }
}
