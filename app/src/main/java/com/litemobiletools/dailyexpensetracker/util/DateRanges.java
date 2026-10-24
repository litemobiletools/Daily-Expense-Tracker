package com.litemobiletools.dailyexpensetracker.util;

import androidx.annotation.NonNull;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Day/month boundaries for queries. All boundaries are instants (UTC epoch millis) computed
 * in an explicit time zone, and are meant to be used as half-open ranges
 * {@code [start, endExclusive)} so a transaction at exactly midnight belongs to exactly one period.
 */
public final class DateRanges {

    private DateRanges() {
    }

    public static long startOfDay(long instant, @NonNull TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(instant);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static long startOfNextDay(long instant, @NonNull TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(startOfDay(instant, zone));
        c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    public static long startOfMonth(long instant, @NonNull TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(startOfDay(instant, zone));
        c.set(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    public static long startOfNextMonth(long instant, @NonNull TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(startOfMonth(instant, zone));
        c.add(Calendar.MONTH, 1);
        return c.getTimeInMillis();
    }

    /** The calendar day of {@code instant} in {@code zone}, as midnight UTC (what MaterialDatePicker uses). */
    public static long pickerUtcFromLocal(long instant, @NonNull TimeZone zone) {
        Calendar local = Calendar.getInstance(zone);
        local.setTimeInMillis(instant);
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH));
        return utc.getTimeInMillis();
    }

    /** First moment (00:00) in {@code zone} of the day picked in a date picker (midnight UTC value). */
    public static long localStartOfPickedDay(long pickedUtcMidnight, @NonNull TimeZone zone) {
        return combineDateWithTimeOfDay(pickedUtcMidnight, startOfDay(0, zone), zone);
    }

    /**
     * Combines a date picked in a Material date picker (midnight UTC of the chosen day) with the
     * time of day of {@code nowMillis} in {@code zone}, giving the instant stored on a transaction.
     * Transactions entered on the same day therefore keep their entry order.
     */
    public static long combineDateWithTimeOfDay(long pickedUtcMidnight, long nowMillis,
                                                @NonNull TimeZone zone) {
        Calendar picked = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        picked.setTimeInMillis(pickedUtcMidnight);
        Calendar now = Calendar.getInstance(zone);
        now.setTimeInMillis(nowMillis);
        Calendar result = Calendar.getInstance(zone);
        result.clear();
        result.set(picked.get(Calendar.YEAR), picked.get(Calendar.MONTH), picked.get(Calendar.DAY_OF_MONTH),
                now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND));
        return result.getTimeInMillis();
    }
}
