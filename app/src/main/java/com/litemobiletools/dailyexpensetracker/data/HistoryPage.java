package com.litemobiletools.dailyexpensetracker.data;

import androidx.annotation.NonNull;

import com.litemobiletools.dailyexpensetracker.data.entity.TransactionRow;

import java.util.List;

/** One page of history rows; {@link #totalCount} is -1 when the count was not requested. */
public final class HistoryPage {

    public final int totalCount;
    @NonNull public final List<TransactionRow> rows;

    public HistoryPage(int totalCount, @NonNull List<TransactionRow> rows) {
        this.totalCount = totalCount;
        this.rows = rows;
    }
}
