package com.litemobiletools.dailyexpensetracker.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.litemobiletools.dailyexpensetracker.backup.AutoBackupPolicy.Frequency;
import com.litemobiletools.dailyexpensetracker.backup.AutoBackupPolicy.ResultForStatus;
import com.litemobiletools.dailyexpensetracker.backup.AutoBackupPolicy.WorkerOutcome;
import com.litemobiletools.dailyexpensetracker.backup.BackupMetadataStore.AutoBackupState;
import com.litemobiletools.dailyexpensetracker.drive.AuthOutcome;
import com.litemobiletools.dailyexpensetracker.drive.DriveOpResult.Kind;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

/** The pure automatic backup decisions: retry bounds, status mapping and schedule anchoring. */
public class AutoBackupPolicyTest {

    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    private static long at(int y, int m, int d, int h, int min) {
        Calendar c = Calendar.getInstance(GMT);
        c.clear();
        c.set(y, m - 1, d, h, min, 0);
        return c.getTimeInMillis();
    }

    // ---- Frequency parsing ----------------------------------------------------------------

    @Test
    public void frequencyDefaultsToDaily() {
        assertEquals(Frequency.DAILY, AutoBackupPolicy.frequency(null));
        assertEquals(Frequency.DAILY, AutoBackupPolicy.frequency("DAILY"));
        assertEquals(Frequency.WEEKLY, AutoBackupPolicy.frequency("WEEKLY"));
        assertEquals(Frequency.DAILY, AutoBackupPolicy.frequency("nonsense"));
    }

    @Test
    public void periodsAreTwentyFourHoursAndSevenDays() {
        assertEquals(24L * 60 * 60 * 1000, Frequency.DAILY.periodMs());
        assertEquals(7L * 24 * 60 * 60 * 1000, Frequency.WEEKLY.periodMs());
    }

    // ---- Preferred time anchoring -----------------------------------------------------------

    @Test
    public void dailyAnchorIsTheNextOccurrenceOfThePreferredHour() {
        long now = at(2026, 10, 22, 21, 30); // after 21:00 today: anchor is tomorrow 21:00
        long delay = AutoBackupPolicy.initialDelayMs(now, false, 21, GMT);
        assertEquals(23.5 * 60 * 60 * 1000, delay, 1000);
        // Daily period is exactly 24 h, so the anchor delay must fit inside the period.
        assertTrue(delay > 0 && delay < Frequency.DAILY.periodMs());
    }

    @Test
    public void dailyAnchorIsLaterTodayWhenTheHourIsStillAhead() {
        long now = at(2026, 10, 22, 6, 15);
        long delay = AutoBackupPolicy.initialDelayMs(now, false, 21, GMT);
        assertEquals(14.75 * 60 * 60 * 1000, delay, 1000);
    }

    @Test
    public void weeklyAnchorStaysOnTheSameWeekday() {
        long now = at(2026, 10, 22, 10, 0); // Thursday 10:00, preferred 09:00 → next Thursday 09:00
        long delay = AutoBackupPolicy.initialDelayMs(now, true, 9, GMT);
        assertEquals(6.958333333 * 24 * 60 * 60 * 1000, delay, 60_000); // 6 days 23 h − nothing
        assertTrue(delay > Frequency.DAILY.periodMs());
        assertTrue(delay < Frequency.WEEKLY.periodMs());
    }

    @Test
    public void weeklyAnchorWhenTheHourIsStillAheadThisWeek() {
        long now = at(2026, 10, 22, 8, 0); // Thursday 08:00, preferred 09:00 → today 09:00
        long delay = AutoBackupPolicy.initialDelayMs(now, true, 9, GMT);
        assertEquals(1.0 * 60 * 60 * 1000, delay, 1000);
    }

    @Test
    public void hourIsClampedAndDelayNeverReachesThePeriod() {
        long now = at(2026, 10, 22, 9, 0);
        long daily = AutoBackupPolicy.initialDelayMs(now, false, 9, GMT);
        // Exactly at the anchor hour: the next day's 09:00 is used, capped just under the period.
        assertEquals(24.0 * 60 * 60 * 1000, daily, 90_000);
        assertTrue(daily > 0 && daily < Frequency.DAILY.periodMs());
        long clamped = AutoBackupPolicy.initialDelayMs(now, false, 99, GMT);
        // Hour 99 clamps to 23:00 → today 23:00, i.e. 14 hours ahead.
        assertEquals(14.0 * 60 * 60 * 1000, clamped, 1000);
        long weekly = AutoBackupPolicy.initialDelayMs(now, true, -5, GMT);
        assertTrue(weekly > 0 && weekly < Frequency.WEEKLY.periodMs());
    }

    // ---- Retry decisions ----------------------------------------------------------------------

    @Test
    public void temporaryFailuresRetryWithinABoundedNumberOfAttempts() {
        assertEquals(WorkerOutcome.RETRY_TEMPORARY, AutoBackupPolicy.decide(Kind.NETWORK, 0));
        assertEquals(WorkerOutcome.RETRY_TEMPORARY, AutoBackupPolicy.decide(Kind.RATE_LIMITED, 2));
        assertEquals(WorkerOutcome.STOP_UNTIL_NEXT_PERIOD,
                AutoBackupPolicy.decide(Kind.NETWORK, AutoBackupPolicy.MAX_ATTEMPTS_PER_RUN));
        assertEquals(WorkerOutcome.STOP_UNTIL_NEXT_PERIOD,
                AutoBackupPolicy.decide(Kind.SERVER_ERROR, 9));
    }

    @Test
    public void permanentFailuresNeverRetry() {
        for (Kind kind : Kind.values()) {
            if (kind == Kind.OK || kind == Kind.NETWORK || kind == Kind.RATE_LIMITED
                    || kind == Kind.SERVER_ERROR) {
                continue;
            }
            assertEquals(WorkerOutcome.STOP_UNTIL_NEXT_PERIOD,
                    AutoBackupPolicy.decide(kind, 0));
        }
    }

    // ---- Status mapping -----------------------------------------------------------------------

    @Test
    public void driveFailureKindsMapToTheResponseStatuses() {
        assertEquals(AutoBackupState.WAITING_FOR_NETWORK,
                AutoBackupPolicy.statusFor(Kind.NETWORK, 0, 0).state);
        assertEquals(AutoBackupState.WAITING_FOR_NETWORK,
                AutoBackupPolicy.statusFor(Kind.RATE_LIMITED, 429, 0).state);
        assertEquals(AutoBackupState.AUTH_REQUIRED,
                AutoBackupPolicy.statusFor(Kind.UNAUTHORIZED, 401, 0).state);
        assertEquals(AutoBackupState.AUTH_REQUIRED,
                AutoBackupPolicy.statusFor(Kind.FORBIDDEN, 403, 0).state);
        assertEquals(AutoBackupState.AUTH_REQUIRED,
                AutoBackupPolicy.statusFor(Kind.API_NOT_ENABLED, 403, 0).state);
        assertEquals(AutoBackupState.FAILED,
                AutoBackupPolicy.statusFor(Kind.DRIVE_FULL, 403, 0).state);
        assertEquals(AutoBackupState.FAILED,
                AutoBackupPolicy.statusFor(Kind.MALFORMED_RESPONSE, 0, 0).state);
        // The HTTP status and safe kind words travel with the recorded status.
        assertEquals(429, AutoBackupPolicy.statusFor(Kind.RATE_LIMITED, 429, 0).httpStatus);
        assertEquals("NETWORK", AutoBackupPolicy.statusFor(Kind.NETWORK, 0, 0).kindLabel);
    }

    // ---- Authorization outcome mapping ----------------------------------------------------------

    @Test
    public void grantedAuthorizationIsNotAProblem() {
        assertNull(AutoBackupPolicy.authProblem(AuthOutcome.Kind.GRANTED, 0));
    }

    @Test
    public void neededConsentIsRecordedAsAuthorizationRequiredAndNotRetried() {
        ResultForStatus result = AutoBackupPolicy.authProblem(AuthOutcome.Kind.NEEDS_CONSENT, 0);
        assertNotNull(result);
        assertEquals(AutoBackupState.AUTH_REQUIRED, result.state);
        assertEquals(WorkerOutcome.STOP_UNTIL_NEXT_PERIOD, result.outcome);
    }

    @Test
    public void googleNetworkCodeWaitsForNetworkAndRetries() {
        ResultForStatus result = AutoBackupPolicy.authProblem(AuthOutcome.Kind.FAILED, 7);
        assertNotNull(result);
        assertEquals(AutoBackupState.WAITING_FOR_NETWORK, result.state);
        assertEquals(WorkerOutcome.RETRY_TEMPORARY, result.outcome);
        // Timeout behaves the same.
        assertEquals(WorkerOutcome.RETRY_TEMPORARY,
                AutoBackupPolicy.authProblem(AuthOutcome.Kind.FAILED, 15).outcome);
    }

    @Test
    public void developerConfigurationCodesAreRecordedAsAuthorizationRequired() {
        // 8/10 without a configured Cloud project: retrying in the background cannot fix it.
        ResultForStatus eight = AutoBackupPolicy.authProblem(AuthOutcome.Kind.FAILED, 8);
        assertNotNull(eight);
        assertEquals(AutoBackupState.AUTH_REQUIRED, eight.state);
        assertEquals(WorkerOutcome.STOP_UNTIL_NEXT_PERIOD, eight.outcome);
        assertEquals(8, eight.httpStatus);
        assertEquals(WorkerOutcome.STOP_UNTIL_NEXT_PERIOD,
                AutoBackupPolicy.authProblem(AuthOutcome.Kind.FAILED, 10).outcome);
    }

    @Test
    public void otherAuthFailuresArePlainFailures() {
        ResultForStatus result = AutoBackupPolicy.authProblem(AuthOutcome.Kind.FAILED, 20);
        assertNotNull(result);
        assertEquals(AutoBackupState.FAILED, result.state);
        assertEquals(WorkerOutcome.STOP_UNTIL_NEXT_PERIOD, result.outcome);
    }
}
