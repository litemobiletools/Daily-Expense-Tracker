package com.litemobiletools.dailyexpensetracker.util;

import androidx.annotation.NonNull;

import com.litemobiletools.dailyexpensetracker.model.BudgetPeriod;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Budget periods on the user's local calendar. A month runs from its first day 00:00 to the
 * first day of the next month; a week runs from the locale's first day of the week 00:00 for seven
 * days. Ranges are half-open {@code [start, endExclusive)}, so an instant belongs to exactly one period.
 */
public final class PeriodMath {

    private PeriodMath() {
    }

    public static long startOfWeek(long instant, @NonNull TimeZone zone, int firstDayOfWeek) {
        Calendar c = Calendar.getInstance(zone);
        c.setFirstDayOfWeek(firstDayOfWeek);
        c.setTimeInMillis(DateRanges.startOfDay(instant, zone));
        // Step back to the first day of the week that contains this day.
        while (c.get(Calendar.DAY_OF_WEEK) != firstDayOfWeek) {
            c.add(Calendar.DAY_OF_MONTH, -1);
        }
        return DateRanges.startOfDay(c.getTimeInMillis(), zone);
    }

    public static long startOfNextWeek(long instant, @NonNull TimeZone zone, int firstDayOfWeek) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(startOfWeek(instant, zone, firstDayOfWeek));
        c.add(Calendar.DAY_OF_MONTH, 7);
        return DateRanges.startOfDay(c.getTimeInMillis(), zone);
    }

    /** {start, endExclusive} of the period containing {@code anchor}. */
    @NonNull
    public static long[] range(@NonNull BudgetPeriod period, long anchor, @NonNull TimeZone zone,
                               int firstDayOfWeek) {
        if (period == BudgetPeriod.WEEKLY) {
            return new long[]{startOfWeek(anchor, zone, firstDayOfWeek),
                    startOfNextWeek(anchor, zone, firstDayOfWeek)};
        }
        return new long[]{DateRanges.startOfMonth(anchor, zone), DateRanges.startOfNextMonth(anchor, zone)};
    }

    /** Moves {@code anchor} by {@code count} whole periods (negative = earlier). */
    public static long shift(@NonNull BudgetPeriod period, long anchor, int count, @NonNull TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(DateRanges.startOfDay(anchor, zone));
        if (period == BudgetPeriod.WEEKLY) {
            c.add(Calendar.DAY_OF_MONTH, 7 * count);
        } else {
            c.set(Calendar.DAY_OF_MONTH, 1); // avoid day-of-month overflow when changing months
            c.add(Calendar.MONTH, count);
        }
        return c.getTimeInMillis();
    }

    /**
     * Days an average "per day" should cover: all days of a period that is over, the days up to and
     * including today for a period in progress, and none for a period that has not started.
     */
    public static int daysElapsed(long start, long endExclusive, long nowMillis, @NonNull TimeZone zone) {
        if (nowMillis < start) {
            return 0;
        }
        if (nowMillis >= endExclusive) {
            return daysBetween(start, endExclusive, zone);
        }
        return daysBetween(start, DateRanges.startOfNextDay(nowMillis, zone), zone);
    }

    /** Number of local days in {@code [start, endExclusive)}. */
    public static int daysBetween(long start, long endExclusive, @NonNull TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(start);
        int days = 0;
        while (c.getTimeInMillis() < endExclusive) {
            c.add(Calendar.DAY_OF_MONTH, 1);
            days++;
        }
        return days;
    }
}
