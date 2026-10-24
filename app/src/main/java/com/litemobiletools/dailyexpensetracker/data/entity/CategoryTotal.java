package com.litemobiletools.dailyexpensetracker.data.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;

/** Query result: total of one category over some period, in minor units. */
public class CategoryTotal {

    @ColumnInfo(name = "category_id")
    public long categoryId;

    @NonNull
    @ColumnInfo(name = "category_name")
    public String categoryName = "";

    @ColumnInfo(name = "total_minor")
    public long totalMinor;

    /** Number of transactions behind the total. */
    @ColumnInfo(name = "transaction_count")
    public int transactionCount;
}
