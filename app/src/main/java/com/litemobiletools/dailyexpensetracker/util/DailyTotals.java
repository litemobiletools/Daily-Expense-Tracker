package com.litemobiletools.dailyexpensetracker.util;

import androidx.annotation.NonNull;

import com.litemobiletools.dailyexpensetracker.data.entity.TimeAmount;

import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * Totals amounts per local calendar day. Day boundaries are computed with the calendar (so a 23- or
 * 25-hour DST day is still one day), and each amount is placed with a binary search over them.
 */
public final class DailyTotals {

    private DailyTotals() {
    }

    /** First moment of every local day in {@code [start, endExclusive)}, plus {@code endExclusive} itself. */
    @NonNull
    public static long[] dayBoundaries(long start, long endExclusive, @NonNull TimeZone zone) {
        int days = PeriodMath.daysBetween(start, endExclusive, zone);
        long[] bounds = new long[days + 1];
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(start);
        for (int i = 0; i < days; i++) {
            bounds[i] = c.getTimeInMillis();
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        bounds[days] = endExclusive;
        return bounds;
    }

    /** One total per local day of the range; rows outside the range are ignored. */
    @NonNull
    public static long[] aggregate(@NonNull List<TimeAmount> rows, @NonNull long[] boundaries) {
        int days = boundaries.length - 1;
        long[] totals = new long[Math.max(days, 0)];
        if (days <= 0) {
            return totals;
        }
        for (TimeAmount row : rows) {
            int index = dayIndex(boundaries, row.timestamp);
            if (index >= 0) {
                totals[index] += row.amountMinor;
            }
        }
        return totals;
    }

    /** Index of the day containing {@code instant}, or -1 if it is outside the range. */
    public static int dayIndex(@NonNull long[] boundaries, long instant) {
        int days = boundaries.length - 1;
        if (days <= 0 || instant < boundaries[0] || instant >= boundaries[days]) {
            return -1;
        }
        int found = Arrays.binarySearch(boundaries, 0, days, instant);
        return found >= 0 ? found : -found - 2;
    }
}
