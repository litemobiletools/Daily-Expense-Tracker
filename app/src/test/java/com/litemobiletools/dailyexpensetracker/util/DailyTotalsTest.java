package com.litemobiletools.dailyexpensetracker.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.litemobiletools.dailyexpensetracker.data.entity.TimeAmount;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

public class DailyTotalsTest {

    private static final TimeZone NEW_YORK = TimeZone.getTimeZone("America/New_York");

    private static long at(int y, int m, int d, int h, int min) {
        Calendar c = Calendar.getInstance(NEW_YORK);
        c.clear();
        c.set(y, m - 1, d, h, min, 0);
        return c.getTimeInMillis();
    }

    private static TimeAmount row(long when, long amount) {
        TimeAmount r = new TimeAmount();
        r.timestamp = when;
        r.amountMinor = amount;
        return r;
    }

    @Test
    public void boundariesFollowLocalMidnightsIncludingTheShortDstDay() {
        long[] b = DailyTotals.dayBoundaries(at(2026, 3, 7, 0, 0), at(2026, 3, 10, 0, 0), NEW_YORK);
        assertEquals(4, b.length);
        assertEquals(at(2026, 3, 8, 0, 0), b[1]);
        assertEquals(at(2026, 3, 9, 0, 0), b[2]);
        assertEquals(23 * 3600_000L, b[2] - b[1]); // the DST day has 23 hours
    }

    @Test
    public void amountsLandOnTheirLocalDayAndOutsideRowsAreIgnored() {
        long[] b = DailyTotals.dayBoundaries(at(2026, 3, 7, 0, 0), at(2026, 3, 10, 0, 0), NEW_YORK);
        List<TimeAmount> rows = new ArrayList<>();
        rows.add(row(at(2026, 3, 7, 0, 0), 1));          // first instant
        rows.add(row(at(2026, 3, 8, 23, 59), 10));       // end of the DST day
        rows.add(row(at(2026, 3, 9, 0, 0), 100));        // next midnight
        rows.add(row(at(2026, 3, 9, 23, 59), 1000));
        rows.add(row(at(2026, 3, 10, 0, 0), 99999));     // range end: excluded
        rows.add(row(at(2026, 3, 6, 23, 59), 99999));    // before: excluded
        assertArrayEquals(new long[]{1, 10, 1100}, DailyTotals.aggregate(rows, b));
    }

    @Test
    public void dayIndexHandlesEdges() {
        long[] b = DailyTotals.dayBoundaries(at(2026, 10, 1, 0, 0), at(2026, 10, 3, 0, 0), NEW_YORK);
        assertEquals(0, DailyTotals.dayIndex(b, at(2026, 10, 1, 0, 0)));
        assertEquals(0, DailyTotals.dayIndex(b, at(2026, 10, 1, 23, 59)));
        assertEquals(1, DailyTotals.dayIndex(b, at(2026, 10, 2, 0, 0)));
        assertEquals(-1, DailyTotals.dayIndex(b, at(2026, 10, 3, 0, 0)));
        assertEquals(-1, DailyTotals.dayIndex(b, at(2026, 9, 30, 23, 59)));
    }
}
