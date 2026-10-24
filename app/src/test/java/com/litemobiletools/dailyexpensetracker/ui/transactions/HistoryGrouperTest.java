package com.litemobiletools.dailyexpensetracker.ui.transactions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.litemobiletools.dailyexpensetracker.data.entity.TransactionRow;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

public class HistoryGrouperTest {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static long at(int y, int m, int d, int h, int min) {
        Calendar c = Calendar.getInstance(UTC);
        c.clear();
        c.set(y, m - 1, d, h, min, 0);
        return c.getTimeInMillis();
    }

    private static TransactionRow row(long id, long timestamp) {
        TransactionRow row = new TransactionRow();
        row.id = id;
        row.transactionTimestamp = timestamp;
        return row;
    }

    @Test
    public void insertsOneHeadingPerDayInOrder() {
        List<TransactionRow> rows = new ArrayList<>();
        rows.add(row(5, at(2026, 10, 22, 18, 0)));
        rows.add(row(4, at(2026, 10, 22, 9, 0)));
        rows.add(row(3, at(2026, 10, 21, 23, 59)));
        rows.add(row(2, at(2026, 10, 20, 0, 0)));

        List<HistoryItem> items = HistoryGrouper.group(rows, UTC);

        assertEquals(7, items.size()); // 3 headings + 4 rows
        assertEquals(HistoryItem.TYPE_HEADER, items.get(0).viewType);
        assertEquals(at(2026, 10, 22, 0, 0), items.get(0).dayStart);
        assertEquals(5, items.get(1).row.id);
        assertEquals(4, items.get(2).row.id);
        assertEquals(HistoryItem.TYPE_HEADER, items.get(3).viewType);
        assertEquals(at(2026, 10, 21, 0, 0), items.get(3).dayStart);
        assertEquals(3, items.get(4).row.id);
        assertEquals(HistoryItem.TYPE_HEADER, items.get(5).viewType);
        assertEquals(2, items.get(6).row.id);
    }

    @Test
    public void midnightBelongsToTheNewDay() {
        List<TransactionRow> rows = new ArrayList<>();
        rows.add(row(2, at(2026, 10, 22, 0, 0)));
        rows.add(row(1, at(2026, 10, 22, 0, 0) - 1));
        List<HistoryItem> items = HistoryGrouper.group(rows, UTC);
        assertEquals(4, items.size());
        assertEquals(at(2026, 10, 22, 0, 0), items.get(0).dayStart);
        assertEquals(at(2026, 10, 21, 0, 0), items.get(2).dayStart);
    }

    @Test
    public void emptyListGivesNoItems() {
        assertTrue(HistoryGrouper.group(new ArrayList<TransactionRow>(), UTC).isEmpty());
    }

    @Test
    public void dayKindIsTodayYesterdayOrOther() {
        long now = at(2026, 10, 22, 15, 30);
        assertEquals(HistoryGrouper.DAY_TODAY, HistoryGrouper.dayKind(at(2026, 10, 22, 0, 0), now, UTC));
        assertEquals(HistoryGrouper.DAY_YESTERDAY, HistoryGrouper.dayKind(at(2026, 10, 21, 0, 0), now, UTC));
        assertEquals(HistoryGrouper.DAY_OTHER, HistoryGrouper.dayKind(at(2026, 10, 20, 0, 0), now, UTC));
        assertEquals(HistoryGrouper.DAY_OTHER, HistoryGrouper.dayKind(at(2026, 10, 23, 0, 0), now, UTC));
    }

    @Test
    public void yesterdayAcrossAMonthBoundary() {
        long now = at(2026, 11, 1, 8, 0);
        assertEquals(HistoryGrouper.DAY_YESTERDAY, HistoryGrouper.dayKind(at(2026, 10, 31, 0, 0), now, UTC));
    }
}
