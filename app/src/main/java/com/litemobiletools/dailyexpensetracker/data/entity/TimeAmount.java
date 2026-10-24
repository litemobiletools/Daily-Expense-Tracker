package com.litemobiletools.dailyexpensetracker.data.entity;

import androidx.room.ColumnInfo;

/** Query result used to total spending per local day. */
public class TimeAmount {

    @ColumnInfo(name = "transaction_timestamp")
    public long timestamp;

    @ColumnInfo(name = "amount_minor")
    public long amountMinor;
}
