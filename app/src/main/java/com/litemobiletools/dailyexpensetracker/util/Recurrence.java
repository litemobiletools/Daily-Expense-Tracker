package com.litemobiletools.dailyexpensetracker.util;

import androidx.annotation.NonNull;

import com.litemobiletools.dailyexpensetracker.model.RecurrenceFrequency;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Schedule arithmetic for recurring transactions. Occurrence number {@code n} (0-based) is
 * computed directly from the start date, never by chaining from the previous occurrence, so the
 * schedule never drifts. All dates are the first moment of a local calendar day in the given zone.
 *
 * <ul>
 *   <li>DAILY: start + n days. WEEKLY: start + 7n days.</li>
 *   <li>MONTHLY: same day-of-month as the start, moved forward n months. A month that is too short
 *       uses its last day (start on the 31st: Jan 31, Feb 28/29, Mar 31, Apr 30, ...), and the schedule
 *       returns to the 31st whenever the month allows it.</li>
 *   <li>YEARLY: same month and day, n years later. A Feb 29 start uses Feb 28 in non-leap years
 *       and Feb 29 again in leap years.</li>
 * </ul>
 */
public final class Recurrence {

    private static final long DAY_MS = 24L * 3600_000L;

    private Recurrence() {
    }

    /** Date of occurrence {@code index} (0 = the start date itself). */
    public static long occurrenceDate(long startDate, @NonNull RecurrenceFrequency frequency,
                                      long index, @NonNull TimeZone zone) {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative");
        }
        Calendar start = Calendar.getInstance(zone);
        start.setTimeInMillis(startDate);
        int year = start.get(Calendar.YEAR);
        int month = start.get(Calendar.MONTH);
        int day = start.get(Calendar.DAY_OF_MONTH);

        Calendar result = Calendar.getInstance(zone);
        result.clear();
        switch (frequency) {
            case DAILY:
            case WEEKLY: {
                result.set(year, month, day);
                long days = frequency == RecurrenceFrequency.DAILY ? index : index * 7;
                result.add(Calendar.DAY_OF_MONTH, (int) days);
                break;
            }
            case MONTHLY: {
                long totalMonths = month + index;
                result.set((int) (year + totalMonths / 12), (int) (totalMonths % 12), 1);
                result.set(Calendar.DAY_OF_MONTH, Math.min(day, result.getActualMaximum(Calendar.DAY_OF_MONTH)));
                break;
            }
            default: { // YEARLY
                result.set((int) (year + index), month, 1);
                result.set(Calendar.DAY_OF_MONTH, Math.min(day, result.getActualMaximum(Calendar.DAY_OF_MONTH)));
                break;
            }
        }
        return DateRanges.startOfDay(result.getTimeInMillis(), zone);
    }

    /** Smallest occurrence index whose date is on or after {@code targetDate}. */
    public static long firstIndexOnOrAfter(long startDate, @NonNull RecurrenceFrequency frequency,
                                           long targetDate, @NonNull TimeZone zone) {
        if (targetDate <= startDate) {
            return 0;
        }
        long estimate;
        switch (frequency) {
            case DAILY:
                estimate = (targetDate - startDate) / DAY_MS;
                break;
            case WEEKLY:
                estimate = (targetDate - startDate) / (7 * DAY_MS);
                break;
            case MONTHLY: {
                Calendar s = Calendar.getInstance(zone);
                s.setTimeInMillis(startDate);
                Calendar t = Calendar.getInstance(zone);
                t.setTimeInMillis(targetDate);
                estimate = (t.get(Calendar.YEAR) - s.get(Calendar.YEAR)) * 12L
                        + (t.get(Calendar.MONTH) - s.get(Calendar.MONTH));
                break;
            }
            default: {
                Calendar s = Calendar.getInstance(zone);
                s.setTimeInMillis(startDate);
                Calendar t = Calendar.getInstance(zone);
                t.setTimeInMillis(targetDate);
                estimate = t.get(Calendar.YEAR) - s.get(Calendar.YEAR);
                break;
            }
        }
        // The estimate can be off by one; start safely below the answer and walk up.
        long index = Math.max(0, estimate - 2);
        while (occurrenceDate(startDate, frequency, index, zone) < targetDate) {
            index++;
        }
        return index;
    }

    /** Largest occurrence index whose date is on or before {@code date}, or -1 if the start is after it. */
    public static long lastIndexOnOrBefore(long startDate, @NonNull RecurrenceFrequency frequency,
                                           long date, @NonNull TimeZone zone) {
        if (date < startDate) {
            return -1;
        }
        return firstIndexOnOrAfter(startDate, frequency, DateRanges.startOfNextDay(date, zone), zone) - 1;
    }
}
