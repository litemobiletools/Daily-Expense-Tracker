package com.litemobiletools.dailyexpensetracker.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.work.Configuration;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.WorkManagerTestInitHelper;

import com.litemobiletools.dailyexpensetracker.settings.AppPreferences;
import com.litemobiletools.dailyexpensetracker.settings.AppPreferences.AutoBackupSchedule;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Scheduling guarantees of the automatic backup (Phase 8), on a device/emulator, against a
 * test-initialized WorkManager: automatic backup is off until the user turns it on, enabling
 * schedules exactly one unique periodic job with the network constraint, repeated applies never
 * duplicate it, disabling cancels it, and a frequency change replaces it in place.
 */
@RunWith(AndroidJUnit4.class)
public class AutoBackupSchedulerInstrumentedTest {

    private Context context;
    private WorkManager workManager;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
                new Configuration.Builder().build());
        workManager = WorkManager.getInstance(context);
        // Start every test from the default (automatic backup off).
        AppPreferences.setAutoBackupSchedule(context,
                new AutoBackupSchedule(false, false, AppPreferences.DEFAULT_AUTO_HOUR));
    }

    @After
    public void tearDown() {
        AutoBackupScheduler.cancel(context);
    }

    private List<WorkInfo> scheduledWork() throws Exception {
        return workManager.getWorkInfosForUniqueWork(AutoBackupScheduler.WORK_NAME).get();
    }

    @Test
    public void automaticBackupIsDisabledByDefault() throws Exception {
        AutoBackupSchedule schedule = AppPreferences.getAutoBackupSchedule(context);
        assertEquals(false, schedule.enabled);
        AutoBackupScheduler.apply(context);
        List<WorkInfo> infos = scheduledWork();
        assertTrue(infos == null || infos.isEmpty());
    }

    @Test
    public void enablingSchedulesOneUniquePeriodicJobWithNetworkConstraint() throws Exception {
        AutoBackupSchedule on = new AutoBackupSchedule(true, false, 21);
        AppPreferences.setAutoBackupSchedule(context, on);
        AutoBackupScheduler.apply(context, on);
        List<WorkInfo> infos = scheduledWork();
        assertEquals(1, infos.size());
        assertEquals(WorkInfo.State.ENQUEUED, infos.get(0).getState());
    }

    @Test
    public void repeatedAppLaunchesNeverDuplicateTheWorker() throws Exception {
        AutoBackupSchedule on = new AutoBackupSchedule(true, false, 21);
        AutoBackupScheduler.apply(context, on);
        AutoBackupScheduler.apply(context, on);
        AutoBackupScheduler.apply(context, on);
        assertEquals(1, scheduledWork().size());
    }

    @Test
    public void disablingCancelsTheScheduledWork() throws Exception {
        AutoBackupSchedule on = new AutoBackupSchedule(true, false, 21);
        AutoBackupScheduler.apply(context, on);
        assertEquals(1, scheduledWork().size());
        AutoBackupSchedule off = new AutoBackupSchedule(false, false, 21);
        AppPreferences.setAutoBackupSchedule(context, off);
        AutoBackupScheduler.apply(context, off);
        List<WorkInfo> infos = scheduledWork();
        assertTrue(infos == null || infos.isEmpty());
    }

    @Test
    public void changingFrequencyReplacesTheJobWithoutDuplicates() throws Exception {
        AppPreferences.setAutoBackupSchedule(context, new AutoBackupSchedule(true, false, 9));
        AutoBackupScheduler.apply(context, AppPreferences.getAutoBackupSchedule(context));
        assertEquals(1, scheduledWork().size());

        // Weekly replaces the daily job in place; there is still exactly one periodic worker.
        AutoBackupSchedule weekly = new AutoBackupSchedule(true, true, 9);
        AppPreferences.setAutoBackupSchedule(context, weekly);
        AutoBackupScheduler.apply(context, weekly);
        List<WorkInfo> infos = scheduledWork();
        assertEquals(1, infos.size());
        assertEquals(WorkInfo.State.ENQUEUED, infos.get(0).getState());
    }

    @Test
    public void dailyPeriodIsTwentyFourHoursAndWeeklySevenDays() {
        assertEquals(TimeUnit.HOURS.toMillis(24),
                AutoBackupPolicy.Frequency.DAILY.periodMs());
        assertEquals(TimeUnit.HOURS.toMillis(24 * 7),
                AutoBackupPolicy.Frequency.WEEKLY.periodMs());
    }
}
