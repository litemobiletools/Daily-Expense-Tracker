package com.litemobiletools.dailyexpensetracker.util;

import static org.junit.Assert.assertEquals;

import com.litemobiletools.dailyexpensetracker.model.RecurrenceFrequency;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

public class RecurrenceTest {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final TimeZone NEW_YORK = TimeZone.getTimeZone("America/New_York"); // has DST

    private static long day(TimeZone zone, int y, int m, int d) {
        Calendar c = Calendar.getInstance(zone);
        c.clear();
        c.set(y, m - 1, d);
        return c.getTimeInMillis();
    }

    private static long occ(RecurrenceFrequency f, long start, long index, TimeZone zone) {
        return Recurrence.occurrenceDate(start, f, index, zone);
    }

    @Test
    public void dailyAdvancesOneDayEachTime() {
        long start = day(UTC, 2026, 10, 30);
        assertEquals(start, occ(RecurrenceFrequency.DAILY, start, 0, UTC));
        assertEquals(day(UTC, 2026, 10, 31), occ(RecurrenceFrequency.DAILY, start, 1, UTC));
        assertEquals(day(UTC, 2026, 11, 1), occ(RecurrenceFrequency.DAILY, start, 2, UTC));
        assertEquals(day(UTC, 2027, 1, 1), occ(RecurrenceFrequency.DAILY, start, 63, UTC));
    }

    @Test
    public void weeklyAdvancesSevenDays() {
        long start = day(UTC, 2026, 10, 22);
        assertEquals(day(UTC, 2026, 10, 29), occ(RecurrenceFrequency.WEEKLY, start, 1, UTC));
        assertEquals(day(UTC, 2026, 11, 5), occ(RecurrenceFrequency.WEEKLY, start, 2, UTC));
        assertEquals(day(UTC, 2027, 10, 21), occ(RecurrenceFrequency.WEEKLY, start, 52, UTC));
    }

    @Test
    public void monthlyKeepsTheDayOfMonth() {
        long start = day(UTC, 2026, 1, 15);
        assertEquals(day(UTC, 2026, 2, 15), occ(RecurrenceFrequency.MONTHLY, start, 1, UTC));
        assertEquals(day(UTC, 2026, 12, 15), occ(RecurrenceFrequency.MONTHLY, start, 11, UTC));
        assertEquals(day(UTC, 2027, 1, 15), occ(RecurrenceFrequency.MONTHLY, start, 12, UTC));
    }

    @Test
    public void monthlyOnThe31stUsesTheLastDayOfShortMonthsAndReturnsTo31() {
        long start = day(UTC, 2026, 1, 31);
        assertEquals(day(UTC, 2026, 1, 31), occ(RecurrenceFrequency.MONTHLY, start, 0, UTC));
        assertEquals(day(UTC, 2026, 2, 28), occ(RecurrenceFrequency.MONTHLY, start, 1, UTC));
        assertEquals(day(UTC, 2026, 3, 31), occ(RecurrenceFrequency.MONTHLY, start, 2, UTC));
        assertEquals(day(UTC, 2026, 4, 30), occ(RecurrenceFrequency.MONTHLY, start, 3, UTC));
        assertEquals(day(UTC, 2026, 5, 31), occ(RecurrenceFrequency.MONTHLY, start, 4, UTC));
        assertEquals(day(UTC, 2026, 6, 30), occ(RecurrenceFrequency.MONTHLY, start, 5, UTC));
    }

    @Test
    public void monthlyOnThe29thThirtiethHandlesFebruaryInLeapAndNonLeapYears() {
        long start29 = day(UTC, 2027, 1, 29);
        assertEquals(day(UTC, 2027, 2, 28), occ(RecurrenceFrequency.MONTHLY, start29, 1, UTC)); // 2027 not leap
        long start30 = day(UTC, 2028, 1, 30);
        assertEquals(day(UTC, 2028, 2, 29), occ(RecurrenceFrequency.MONTHLY, start30, 1, UTC)); // 2028 leap
        assertEquals(day(UTC, 2028, 3, 30), occ(RecurrenceFrequency.MONTHLY, start30, 2, UTC));
    }

    @Test
    public void yearlyKeepsMonthAndDay() {
        long start = day(UTC, 2026, 10, 22);
        assertEquals(day(UTC, 2027, 10, 22), occ(RecurrenceFrequency.YEARLY, start, 1, UTC));
        assertEquals(day(UTC, 2036, 10, 22), occ(RecurrenceFrequency.YEARLY, start, 10, UTC));
    }

    @Test
    public void yearlyOnFebruary29UsesFebruary28InNonLeapYearsAndTheLeapDayAgain() {
        long start = day(UTC, 2024, 2, 29);
        assertEquals(day(UTC, 2024, 2, 29), occ(RecurrenceFrequency.YEARLY, start, 0, UTC));
        assertEquals(day(UTC, 2025, 2, 28), occ(RecurrenceFrequency.YEARLY, start, 1, UTC));
        assertEquals(day(UTC, 2026, 2, 28), occ(RecurrenceFrequency.YEARLY, start, 2, UTC));
        assertEquals(day(UTC, 2027, 2, 28), occ(RecurrenceFrequency.YEARLY, start, 3, UTC));
        assertEquals(day(UTC, 2028, 2, 29), occ(RecurrenceFrequency.YEARLY, start, 4, UTC));
    }

    @Test
    public void schedulesNeverDriftBecauseEachDateIsComputedFromTheStart() {
        long start = day(UTC, 2026, 1, 31);
        // After a short month the schedule is back on the 31st, not stuck on the 28th.
        assertEquals(day(UTC, 2026, 3, 31), occ(RecurrenceFrequency.MONTHLY, start, 2, UTC));
        assertEquals(day(UTC, 2026, 12, 31), occ(RecurrenceFrequency.MONTHLY, start, 11, UTC));
    }

    @Test
    public void datesStayOnLocalMidnightAcrossDaylightSavingChanges() {
        long start = day(NEW_YORK, 2026, 3, 7); // DST starts 8 March 2026
        assertEquals(day(NEW_YORK, 2026, 3, 8), occ(RecurrenceFrequency.DAILY, start, 1, NEW_YORK));
        assertEquals(day(NEW_YORK, 2026, 3, 9), occ(RecurrenceFrequency.DAILY, start, 2, NEW_YORK));
        assertEquals(day(NEW_YORK, 2026, 3, 14), occ(RecurrenceFrequency.WEEKLY, start, 1, NEW_YORK));
        assertEquals(day(NEW_YORK, 2026, 11, 7), occ(RecurrenceFrequency.MONTHLY, start, 8, NEW_YORK));
    }

    @Test
    public void firstIndexOnOrAfterFindsTheNextOccurrence() {
        long start = day(UTC, 2026, 1, 31);
        // Monthly on the 31st: 15 Feb is before the 28 Feb occurrence (index 1).
        assertEquals(1, Recurrence.firstIndexOnOrAfter(start, RecurrenceFrequency.MONTHLY, day(UTC, 2026, 2, 15), UTC));
        assertEquals(1, Recurrence.firstIndexOnOrAfter(start, RecurrenceFrequency.MONTHLY, day(UTC, 2026, 2, 28), UTC));
        assertEquals(2, Recurrence.firstIndexOnOrAfter(start, RecurrenceFrequency.MONTHLY, day(UTC, 2026, 3, 1), UTC));
        assertEquals(0, Recurrence.firstIndexOnOrAfter(start, RecurrenceFrequency.MONTHLY, day(UTC, 2025, 12, 1), UTC));
    }

    @Test
    public void firstIndexAgreesWithOccurrenceDateForEveryFrequency() {
        long start = day(UTC, 2024, 1, 31);
        for (RecurrenceFrequency f : RecurrenceFrequency.values()) {
            for (int offsetDays = 0; offsetDays < 900; offsetDays += 7) {
                long target = day(UTC, 2024, 1, 31) + offsetDays * 24L * 3600_000L;
                long index = Recurrence.firstIndexOnOrAfter(start, f, target, UTC);
                assertEquals(f + " " + offsetDays, true, occ(f, start, index, UTC) >= target);
                if (index > 0) {
                    assertEquals(f + " " + offsetDays, true, occ(f, start, index - 1, UTC) < target);
                }
            }
        }
    }

    @Test
    public void lastIndexOnOrBeforeIncludesTheDayItself() {
        long start = day(UTC, 2026, 10, 22);
        assertEquals(-1, Recurrence.lastIndexOnOrBefore(start, RecurrenceFrequency.DAILY, day(UTC, 2026, 10, 21), UTC));
        assertEquals(0, Recurrence.lastIndexOnOrBefore(start, RecurrenceFrequency.DAILY, day(UTC, 2026, 10, 22), UTC));
        assertEquals(10, Recurrence.lastIndexOnOrBefore(start, RecurrenceFrequency.DAILY, day(UTC, 2026, 11, 1), UTC));
        assertEquals(1, Recurrence.lastIndexOnOrBefore(start, RecurrenceFrequency.WEEKLY, day(UTC, 2026, 11, 4), UTC));
        assertEquals(2, Recurrence.lastIndexOnOrBefore(start, RecurrenceFrequency.WEEKLY, day(UTC, 2026, 11, 5), UTC));
    }
}
