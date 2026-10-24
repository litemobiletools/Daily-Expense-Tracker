package com.litemobiletools.dailyexpensetracker.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

public class DateRangesTest {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final TimeZone DHAKA = TimeZone.getTimeZone("Asia/Dhaka"); // UTC+6, no DST

    private static long at(TimeZone zone, int y, int m, int d, int h, int min) {
        Calendar c = Calendar.getInstance(zone);
        c.clear();
        c.set(y, m - 1, d, h, min, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void monthBoundariesAreHalfOpen() {
        long now = at(UTC, 2026, 10, 22, 13, 30);
        assertEquals(at(UTC, 2026, 10, 1, 0, 0), DateRanges.startOfMonth(now, UTC));
        assertEquals(at(UTC, 2026, 11, 1, 0, 0), DateRanges.startOfNextMonth(now, UTC));
    }

    @Test
    public void lastMomentOfMonthBelongsToThatMonth() {
        long lastMillis = at(UTC, 2026, 10, 31, 23, 59) + 59_999;
        assertEquals(at(UTC, 2026, 10, 1, 0, 0), DateRanges.startOfMonth(lastMillis, UTC));
        assertTrue(lastMillis < DateRanges.startOfNextMonth(lastMillis, UTC));
        assertEquals(DateRanges.startOfNextMonth(lastMillis, UTC), lastMillis + 1);
    }

    @Test
    public void decemberRollsIntoNextYear() {
        long now = at(UTC, 2026, 12, 15, 8, 0);
        assertEquals(at(UTC, 2027, 1, 1, 0, 0), DateRanges.startOfNextMonth(now, UTC));
    }

    @Test
    public void boundariesFollowTheGivenTimeZone() {
        // 2026-10-31 20:00 UTC is already 2026-11-01 02:00 in Dhaka.
        long instant = at(UTC, 2026, 10, 31, 20, 0);
        assertEquals(at(DHAKA, 2026, 11, 1, 0, 0), DateRanges.startOfMonth(instant, DHAKA));
        assertEquals(at(UTC, 2026, 10, 1, 0, 0), DateRanges.startOfMonth(instant, UTC));
    }

    @Test
    public void dayBoundaries() {
        long now = at(UTC, 2026, 2, 28, 23, 0);
        assertEquals(at(UTC, 2026, 2, 28, 0, 0), DateRanges.startOfDay(now, UTC));
        assertEquals(at(UTC, 2026, 3, 1, 0, 0), DateRanges.startOfNextDay(now, UTC));
    }

    @Test
    public void pickerRoundTripUsesTheLocalCalendarDay() {
        // 2026-03-05 23:30 in Dhaka is still March 5th locally, though it is 17:30 UTC.
        long instant = at(DHAKA, 2026, 3, 5, 23, 30);
        long picked = DateRanges.pickerUtcFromLocal(instant, DHAKA);
        assertEquals(at(UTC, 2026, 3, 5, 0, 0), picked);
        assertEquals(at(DHAKA, 2026, 3, 5, 0, 0), DateRanges.localStartOfPickedDay(picked, DHAKA));
    }

    @Test
    public void endDayIsFullyIncludedWhenUsingNextDayAsExclusiveEnd() {
        long picked = at(UTC, 2026, 10, 31, 0, 0);
        long endExclusive = DateRanges.startOfNextDay(DateRanges.localStartOfPickedDay(picked, DHAKA), DHAKA);
        long lastMomentOfDay = at(DHAKA, 2026, 10, 31, 23, 59) + 59_999;
        long firstMomentOfNext = at(DHAKA, 2026, 11, 1, 0, 0);
        assertTrue(lastMomentOfDay < endExclusive);
        assertEquals(firstMomentOfNext, endExclusive);
    }

    @Test
    public void combinesPickedDateWithCurrentTimeOfDay() {
        long pickedUtcMidnight = at(UTC, 2026, 3, 5, 0, 0);
        long now = at(DHAKA, 2026, 10, 22, 14, 45);
        long combined = DateRanges.combineDateWithTimeOfDay(pickedUtcMidnight, now, DHAKA);
        assertEquals(at(DHAKA, 2026, 3, 5, 14, 45), combined);
    }
}
