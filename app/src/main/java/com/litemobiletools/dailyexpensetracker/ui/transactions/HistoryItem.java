package com.litemobiletools.dailyexpensetracker.ui.transactions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.litemobiletools.dailyexpensetracker.data.entity.TransactionRow;

/** One entry in the history list: a date heading or a transaction row. */
public final class HistoryItem {

    public static final int TYPE_HEADER = 0;
    public static final int TYPE_ROW = 1;

    public final int viewType;
    /** First moment of the day this heading or row belongs to, in the device time zone. */
    public final long dayStart;
    @Nullable public final TransactionRow row;

    private HistoryItem(int viewType, long dayStart, @Nullable TransactionRow row) {
        this.viewType = viewType;
        this.dayStart = dayStart;
        this.row = row;
    }

    @NonNull
    public static HistoryItem header(long dayStart) {
        return new HistoryItem(TYPE_HEADER, dayStart, null);
    }

    @NonNull
    public static HistoryItem row(long dayStart, @NonNull TransactionRow row) {
        return new HistoryItem(TYPE_ROW, dayStart, row);
    }

    /** Stable identity for list diffing: transaction id for rows, day for headings. */
    public long stableKey() {
        return row != null ? row.id : dayStart;
    }
}
