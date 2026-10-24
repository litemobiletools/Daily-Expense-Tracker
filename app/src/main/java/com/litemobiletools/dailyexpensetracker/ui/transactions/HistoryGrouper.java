package com.litemobiletools.dailyexpensetracker.ui.transactions;

import androidx.annotation.NonNull;

import com.litemobiletools.dailyexpensetracker.data.entity.TransactionRow;
import com.litemobiletools.dailyexpensetracker.util.DateRanges;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/** Turns rows (already sorted newest first) into a flat list with one heading per calendar day. */
public final class HistoryGrouper {

    public static final int DAY_TODAY = 0;
    public static final int DAY_YESTERDAY = 1;
    public static final int DAY_OTHER = 2;

    private HistoryGrouper() {
    }

    @NonNull
    public static List<HistoryItem> group(@NonNull List<TransactionRow> rows, @NonNull TimeZone zone) {
        List<HistoryItem> items = new ArrayList<>(rows.size() + 16);
        long currentDay = Long.MIN_VALUE;
        for (TransactionRow row : rows) {
            long day = DateRanges.startOfDay(row.transactionTimestamp, zone);
            if (day != currentDay) {
                items.add(HistoryItem.header(day));
                currentDay = day;
            }
            items.add(HistoryItem.row(day, row));
        }
        return items;
    }

    /** Whether a day heading reads "Today", "Yesterday" or a formatted date. */
    public static int dayKind(long dayStart, long nowMillis, @NonNull TimeZone zone) {
        long today = DateRanges.startOfDay(nowMillis, zone);
        if (dayStart == today) {
            return DAY_TODAY;
        }
        long yesterday = DateRanges.startOfDay(today - 1, zone);
        return dayStart == yesterday ? DAY_YESTERDAY : DAY_OTHER;
    }
}
