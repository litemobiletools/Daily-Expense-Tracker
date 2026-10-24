package com.litemobiletools.dailyexpensetracker.util;

import static org.junit.Assert.assertEquals;

import com.litemobiletools.dailyexpensetracker.model.BudgetPeriod;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

public class PeriodMathTest {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final TimeZone DHAKA = TimeZone.getTimeZone("Asia/Dhaka");

    private static long at(TimeZone zone, int y, int m, int d, int h, int min) {
        Calendar c = Calendar.getInstance(zone);
        c.clear();
        c.set(y, m - 1, d, h, min, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void weekStartsOnMondayWhenMondayIsTheFirstDay() {
        // Thursday 22 Oct 2026
        long thursday = at(UTC, 2026, 10, 22, 15, 30);
        assertEquals(at(UTC, 2026, 10, 19, 0, 0), PeriodMath.startOfWeek(thursday, UTC, Calendar.MONDAY));
        assertEquals(at(UTC, 2026, 10, 26, 0, 0), PeriodMath.startOfNextWeek(thursday, UTC, Calendar.MONDAY));
    }

    @Test
    public void weekStartsOnSundayWhenSundayIsTheFirstDay() {
        long thursday = at(UTC, 2026, 10, 22, 15, 30);
        assertEquals(at(UTC, 2026, 10, 18, 0, 0), PeriodMath.startOfWeek(thursday, UTC, Calendar.SUNDAY));
        assertEquals(at(UTC, 2026, 10, 25, 0, 0), PeriodMath.startOfNextWeek(thursday, UTC, Calendar.SUNDAY));
    }

    @Test
    public void firstDayOfTheWeekBelongsToItsOwnWeekAndLastMomentToThePreviousOne() {
        long mondayMidnight = at(UTC, 2026, 10, 19, 0, 0);
        assertEquals(mondayMidnight, PeriodMath.startOfWeek(mondayMidnight, UTC, Calendar.MONDAY));
        assertEquals(at(UTC, 2026, 10, 12, 0, 0), PeriodMath.startOfWeek(mondayMidnight - 1, UTC, Calendar.MONDAY));
        long sundayEnd = mondayMidnight - 1;
        assertEquals(mondayMidnight, PeriodMath.startOfNextWeek(sundayEnd, UTC, Calendar.MONDAY));
    }

    @Test
    public void weekCanSpanAMonthAndYearBoundary() {
        long newYearsDay = at(UTC, 2027, 1, 1, 10, 0); // Friday
        assertEquals(at(UTC, 2026, 12, 28, 0, 0), PeriodMath.startOfWeek(newYearsDay, UTC, Calendar.MONDAY));
        assertEquals(at(UTC, 2027, 1, 4, 0, 0), PeriodMath.startOfNextWeek(newYearsDay, UTC, Calendar.MONDAY));
    }

    @Test
    public void weekBoundariesFollowTheLocalZone() {
        // Sunday 25 Oct 2026 20:00 UTC is already Monday 26 Oct 02:00 in Dhaka (UTC+6).
        long instant = at(UTC, 2026, 10, 25, 20, 0);
        assertEquals(at(DHAKA, 2026, 10, 26, 0, 0), PeriodMath.startOfWeek(instant, DHAKA, Calendar.MONDAY));
        assertEquals(at(UTC, 2026, 10, 19, 0, 0), PeriodMath.startOfWeek(instant, UTC, Calendar.MONDAY));
    }

    @Test
    public void rangeForMonthlyAndWeeklyPeriods() {
        long anchor = at(UTC, 2026, 2, 14, 12, 0);
        long[] month = PeriodMath.range(BudgetPeriod.MONTHLY, anchor, UTC, Calendar.MONDAY);
        assertEquals(at(UTC, 2026, 2, 1, 0, 0), month[0]);
        assertEquals(at(UTC, 2026, 3, 1, 0, 0), month[1]);
        long[] week = PeriodMath.range(BudgetPeriod.WEEKLY, anchor, UTC, Calendar.MONDAY);
        assertEquals(at(UTC, 2026, 2, 9, 0, 0), week[0]);
        assertEquals(at(UTC, 2026, 2, 16, 0, 0), week[1]);
    }

    @Test
    public void shiftMovesWholePeriods() {
        long jan31 = at(UTC, 2026, 1, 31, 12, 0);
        assertEquals(at(UTC, 2026, 2, 1, 0, 0), PeriodMath.shift(BudgetPeriod.MONTHLY, jan31, 1, UTC));
        assertEquals(at(UTC, 2025, 12, 1, 0, 0), PeriodMath.shift(BudgetPeriod.MONTHLY, jan31, -1, UTC));
        assertEquals(at(UTC, 2026, 2, 7, 0, 0), PeriodMath.shift(BudgetPeriod.WEEKLY, jan31, 1, UTC));
        assertEquals(at(UTC, 2026, 1, 24, 0, 0), PeriodMath.shift(BudgetPeriod.WEEKLY, jan31, -1, UTC));
    }

    @Test
    public void daysBetweenCountsLocalDays() {
        assertEquals(28, PeriodMath.daysBetween(at(UTC, 2026, 2, 1, 0, 0), at(UTC, 2026, 3, 1, 0, 0), UTC));
        assertEquals(29, PeriodMath.daysBetween(at(UTC, 2028, 2, 1, 0, 0), at(UTC, 2028, 3, 1, 0, 0), UTC));
        assertEquals(0, PeriodMath.daysBetween(at(UTC, 2026, 2, 1, 0, 0), at(UTC, 2026, 2, 1, 0, 0), UTC));
    }
}
