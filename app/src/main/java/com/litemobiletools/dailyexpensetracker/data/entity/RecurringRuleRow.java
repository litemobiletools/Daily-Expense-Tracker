package com.litemobiletools.dailyexpensetracker.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Embedded;

/** A recurring rule plus its category's display data, for list screens. */
public class RecurringRuleRow {

    @NonNull
    @Embedded
    public RecurringRuleEntity rule = new RecurringRuleEntity();

    @NonNull
    @ColumnInfo(name = "category_name")
    public String categoryName = "";

    @Nullable
    @ColumnInfo(name = "category_icon_key")
    public String categoryIconKey;

    @Nullable
    @ColumnInfo(name = "category_color_argb")
    public Integer categoryColorArgb;
}
