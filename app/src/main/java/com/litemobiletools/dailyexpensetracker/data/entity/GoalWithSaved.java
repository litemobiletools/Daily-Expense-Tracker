package com.litemobiletools.dailyexpensetracker.data.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Embedded;

/** A goal plus the sum of its contributions, in minor units. */
public class GoalWithSaved {

    @NonNull
    @Embedded
    public SavingsGoalEntity goal = new SavingsGoalEntity();

    @ColumnInfo(name = "saved_minor")
    public long savedMinor;
}
