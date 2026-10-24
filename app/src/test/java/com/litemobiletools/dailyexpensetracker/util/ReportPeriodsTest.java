package com.litemobiletools.dailyexpensetracker.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.litemobiletools.dailyexpensetracker.model.ReportPreset;
import com.litemobiletools.dailyexpensetracker.model.ReportRange;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

public class ReportPeriodsTest {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final TimeZone NEW_YORK = TimeZone.getTimeZone("America/New_York");

    private static long at(TimeZone zone, int y, int m, int d, int h, int min) {
        Calendar c = Calendar.getInstance(zone);
        c.clear();
        c.set(y, m - 1, d, h, min, 0);
        return c.getTimeInMillis();
    }

    private static ReportRange resolve(ReportPreset preset, long now, TimeZone zone) {
        return ReportPeriods.resolve(preset, now, zone, Calendar.MONDAY);
    }

    @Test
    public void thisWeekIsTheMondayToSundayWeekContainingToday() {
        ReportRange r = resolve(ReportPreset.THIS_WEEK, at(UTC, 2026, 10, 22, 15, 0), UTC);
        assertEquals(at(UTC, 2026, 10, 19, 0, 0), r.start);
        assertEquals(at(UTC, 2026, 10, 26, 0, 0), r.endExclusive);
    }

    @Test
    public void thisMonthAndPreviousMonth() {
        long now = at(UTC, 2026, 10, 22, 15, 0);
        ReportRange month = resolve(ReportPreset.THIS_MONTH, now, UTC);
        assertEquals(at(UTC, 2026, 10, 1, 0, 0), month.start);
        assertEquals(at(UTC, 2026, 11, 1, 0, 0), month.endExclusive);
        ReportRange previous = resolve(ReportPreset.PREVIOUS_MONTH, now, UTC);
        assertEquals(at(UTC, 2026, 9, 1, 0, 0), previous.start);
        assertEquals(at(UTC, 2026, 10, 1, 0, 0), previous.endExclusive);
    }

    @Test
    public void previousMonthAcrossAYearBoundaryAndLeapFebruary() {
        ReportRange january = resolve(ReportPreset.PREVIOUS_MONTH, at(UTC, 2027, 1, 5, 9, 0), UTC);
        assertEquals(at(UTC, 2026, 12, 1, 0, 0), january.start);
        assertEquals(at(UTC, 2027, 1, 1, 0, 0), january.endExclusive);
        ReportRange leap = resolve(ReportPreset.PREVIOUS_MONTH, at(UTC, 2028, 3, 10, 9, 0), UTC);
        assertEquals(29, PeriodMath.daysBetween(leap.start, leap.endExclusive, UTC));
    }

    @Test
    public void lastThirtyAndNinetyDaysEndWithTodayIncluded() {
        long now = at(UTC, 2026, 10, 22, 15, 0);
        ReportRange r30 = resolve(ReportPreset.LAST_30_DAYS, now, UTC);
        assertEquals(at(UTC, 2026, 10, 23, 0, 0), r30.endExclusive);
        assertEquals(at(UTC, 2026, 9, 23, 0, 0), r30.start);
        assertEquals(30, PeriodMath.daysBetween(r30.start, r30.endExclusive, UTC));
        ReportRange r90 = resolve(ReportPreset.LAST_90_DAYS, now, UTC);
        assertEquals(90, PeriodMath.daysBetween(r90.start, r90.endExclusive, UTC));
        assertEquals(r30.endExclusive, r90.endExclusive);
    }

    @Test
    public void rollingRangesStayOnLocalMidnightsAcrossDaylightSaving() {
        // DST in New York starts 8 March 2026; a 30-day window across it still has 30 local days.
        long now = at(NEW_YORK, 2026, 3, 20, 12, 0);
        ReportRange r = resolve(ReportPreset.LAST_30_DAYS, now, NEW_YORK);
        assertEquals(at(NEW_YORK, 2026, 2, 19, 0, 0), r.start);
        assertEquals(at(NEW_YORK, 2026, 3, 21, 0, 0), r.endExclusive);
        assertEquals(30, PeriodMath.daysBetween(r.start, r.endExclusive, NEW_YORK));
    }

    @Test
    public void thisYearCoversJanuaryFirstToNextJanuaryFirst() {
        ReportRange r = resolve(ReportPreset.THIS_YEAR, at(UTC, 2028, 7, 4, 10, 0), UTC);
        assertEquals(at(UTC, 2028, 1, 1, 0, 0), r.start);
        assertEquals(at(UTC, 2029, 1, 1, 0, 0), r.endExclusive);
        assertEquals(366, PeriodMath.daysBetween(r.start, r.endExclusive, UTC)); // leap year
    }

    @Test
    public void customRangeIncludesTheWholeEndDay() {
        ReportRange r = ReportPeriods.custom(at(UTC, 2026, 10, 5, 14, 0), at(UTC, 2026, 10, 7, 9, 0), UTC);
        assertEquals(at(UTC, 2026, 10, 5, 0, 0), r.start);
        assertEquals(at(UTC, 2026, 10, 8, 0, 0), r.endExclusive);
        assertTrue(at(UTC, 2026, 10, 7, 23, 59) + 59_999 < r.endExclusive);
        // A single day is a valid range.
        ReportRange single = ReportPeriods.custom(at(UTC, 2026, 10, 5, 0, 0), at(UTC, 2026, 10, 5, 0, 0), UTC);
        assertEquals(1, PeriodMath.daysBetween(single.start, single.endExclusive, UTC));
    }

    @Test
    public void customRangeRejectsAStartAfterTheEnd() {
        try {
            ReportPeriods.custom(at(UTC, 2026, 10, 8, 0, 0), at(UTC, 2026, 10, 7, 0, 0), UTC);
            fail("Expected rejection");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void previousPeriodsMatchThePresetKind() {
        TimeZone z = UTC;
        ReportRange week = resolve(ReportPreset.THIS_WEEK, at(z, 2026, 10, 22, 0, 0), z);
        ReportRange prevWeek = ReportPeriods.previous(week, z);
        assertEquals(at(z, 2026, 10, 12, 0, 0), prevWeek.start);
        assertEquals(week.start, prevWeek.endExclusive);

        ReportRange march = resolve(ReportPreset.THIS_MONTH, at(z, 2026, 3, 15, 0, 0), z);
        ReportRange february = ReportPeriods.previous(march, z);
        assertEquals(at(z, 2026, 2, 1, 0, 0), february.start);
        assertEquals(march.start, february.endExclusive);

        ReportRange year = resolve(ReportPreset.THIS_YEAR, at(z, 2026, 5, 1, 0, 0), z);
        ReportRange lastYear = ReportPeriods.previous(year, z);
        assertEquals(at(z, 2025, 1, 1, 0, 0), lastYear.start);

        ReportRange custom = ReportPeriods.custom(at(z, 2026, 10, 1, 0, 0), at(z, 2026, 10, 10, 0, 0), z);
        ReportRange before = ReportPeriods.previous(custom, z);
        assertEquals(at(z, 2026, 9, 21, 0, 0), before.start);
        assertEquals(custom.start, before.endExclusive);
        assertEquals(10, PeriodMath.daysBetween(before.start, before.endExclusive, z));
    }

    @Test
    public void inProgressOnlyWhileNowIsInside() {
        ReportRange r = ReportPeriods.custom(at(UTC, 2026, 10, 1, 0, 0), at(UTC, 2026, 10, 10, 0, 0), UTC);
        assertTrue(ReportPeriods.inProgress(r, at(UTC, 2026, 10, 10, 23, 0)));
        assertFalse(ReportPeriods.inProgress(r, at(UTC, 2026, 10, 11, 0, 0)));
        assertFalse(ReportPeriods.inProgress(r, at(UTC, 2026, 9, 30, 23, 0)));
    }

    @Test
    public void daysElapsedCountsTodayAndIsZeroBeforeTheStart() {
        long start = at(UTC, 2026, 10, 1, 0, 0);
        long end = at(UTC, 2026, 11, 1, 0, 0);
        assertEquals(22, PeriodMath.daysElapsed(start, end, at(UTC, 2026, 10, 22, 8, 0), UTC));
        assertEquals(31, PeriodMath.daysElapsed(start, end, at(UTC, 2026, 12, 1, 0, 0), UTC));
        assertEquals(0, PeriodMath.daysElapsed(start, end, at(UTC, 2026, 9, 30, 0, 0), UTC));
    }
}
