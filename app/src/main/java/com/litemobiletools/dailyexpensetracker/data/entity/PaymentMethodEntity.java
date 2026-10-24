package com.litemobiletools.dailyexpensetracker.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** A built-in way of paying. Purely a label: no bank or card data is ever stored. */
@Entity(tableName = "payment_methods",
        indices = {@Index(value = "default_key", unique = true)})
public class PaymentMethodEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public String name = "";

    @NonNull
    @ColumnInfo(name = "icon_key")
    public String iconKey = "";

    @ColumnInfo(name = "is_active")
    public boolean isActive = true;

    @Nullable
    @ColumnInfo(name = "default_key")
    public String defaultKey;

    @ColumnInfo(name = "sort_order")
    public int sortOrder;
}
