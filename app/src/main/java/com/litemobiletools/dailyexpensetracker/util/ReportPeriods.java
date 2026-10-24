package com.litemobiletools.dailyexpensetracker.util;

import androidx.annotation.NonNull;

import com.litemobiletools.dailyexpensetracker.model.BudgetPeriod;
import com.litemobiletools.dailyexpensetracker.model.ReportPreset;
import com.litemobiletools.dailyexpensetracker.model.ReportRange;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Turns a reporting preset into concrete local-calendar boundaries, and finds the period to compare
 * against. All arithmetic is done in calendar days (never by adding 24-hour blocks), so ranges stay
 * on local midnights across daylight-saving changes, month ends and leap years.
 *
 * <ul>
 *   <li>This week / this month / this year: the whole calendar period containing today (the part
 *       after today simply has no transactions yet).</li>
 *   <li>Previous month: the whole previous calendar month.</li>
 *   <li>Last 30 / 90 days: the N local days ending with today, today included.</li>
 *   <li>Custom: from the first moment of the start day to the end of the end day.</li>
 * </ul>
 *
 * The comparison period is the previous calendar week, month or year for calendar presets, and the
 * same number of days immediately before for rolling and custom ranges.
 */
public final class ReportPeriods {

    private ReportPeriods() {
    }

    @NonNull
    public static ReportRange resolve(@NonNull ReportPreset preset, long now, @NonNull TimeZone zone,
                                      int firstDayOfWeek) {
        switch (preset) {
            case THIS_WEEK: {
                long[] r = PeriodMath.range(BudgetPeriod.WEEKLY, now, zone, firstDayOfWeek);
                return new ReportRange(preset, r[0], r[1]);
            }
            case THIS_MONTH:
                return new ReportRange(preset, DateRanges.startOfMonth(now, zone), DateRanges.startOfNextMonth(now, zone));
            case PREVIOUS_MONTH: {
                long thisMonth = DateRanges.startOfMonth(now, zone);
                return new ReportRange(preset, DateRanges.startOfMonth(thisMonth - 1, zone), thisMonth);
            }
            case LAST_30_DAYS:
                return lastDays(preset, 30, now, zone);
            case LAST_90_DAYS:
                return lastDays(preset, 90, now, zone);
            case THIS_YEAR:
                return new ReportRange(preset, startOfYear(now, zone), addYears(startOfYear(now, zone), 1, zone));
            default:
                throw new IllegalArgumentException("Use custom() for custom ranges");
        }
    }

    /**
     * A custom range covering whole local days.
     *
     * @throws IllegalArgumentException if the start day is after the end day
     */
    @NonNull
    public static ReportRange custom(long startDay, long endDay, @NonNull TimeZone zone) {
        long start = DateRanges.startOfDay(startDay, zone);
        long lastDay = DateRanges.startOfDay(endDay, zone);
        if (start > lastDay) {
            throw new IllegalArgumentException("Start date is after end date");
        }
        return new ReportRange(ReportPreset.CUSTOM, start, DateRanges.startOfNextDay(lastDay, zone));
    }

    /** The period to compare {@code range} with. */
    @NonNull
    public static ReportRange previous(@NonNull ReportRange range, @NonNull TimeZone zone) {
        switch (range.preset) {
            case THIS_WEEK:
                return new ReportRange(range.preset, addDays(range.start, -7, zone), range.start);
            case THIS_MONTH:
            case PREVIOUS_MONTH:
                return new ReportRange(range.preset, DateRanges.startOfMonth(range.start - 1, zone), range.start);
            case THIS_YEAR:
                return new ReportRange(range.preset, addYears(range.start, -1, zone), range.start);
            default: {
                int days = PeriodMath.daysBetween(range.start, range.endExclusive, zone);
                return new ReportRange(range.preset, addDays(range.start, -days, zone), range.start);
            }
        }
    }

    /** True while {@code now} lies inside the range (the period is not over yet). */
    public static boolean inProgress(@NonNull ReportRange range, long now) {
        return now >= range.start && now < range.endExclusive;
    }

    private static ReportRange lastDays(ReportPreset preset, int days, long now, TimeZone zone) {
        long end = DateRanges.startOfNextDay(now, zone);
        return new ReportRange(preset, addDays(end, -days, zone), end);
    }

    static long addDays(long dayStart, int days, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(dayStart);
        c.add(Calendar.DAY_OF_MONTH, days);
        return DateRanges.startOfDay(c.getTimeInMillis(), zone);
    }

    private static long startOfYear(long instant, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(DateRanges.startOfMonth(instant, zone));
        c.set(Calendar.MONTH, Calendar.JANUARY);
        return c.getTimeInMillis();
    }

    private static long addYears(long instant, int years, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(instant);
        c.add(Calendar.YEAR, years);
        return c.getTimeInMillis();
    }
}
