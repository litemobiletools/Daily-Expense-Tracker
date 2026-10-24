package com.litemobiletools.dailyexpensetracker.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * A savings goal. This is a tracking record only: contributions never create transactions and
 * never change the dashboard balance. The saved amount is the sum of the goal's contributions.
 */
@Entity(tableName = "savings_goals")
public class SavingsGoalEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public String name = "";

    @ColumnInfo(name = "target_minor")
    public long targetMinor;

    @NonNull
    @ColumnInfo(name = "currency_code")
    public String currencyCode = "";

    /** Optional target date: first moment of that local day. */
    @Nullable
    @ColumnInfo(name = "target_date")
    public Long targetDate;

    @ColumnInfo(name = "is_completed")
    public boolean isCompleted;

    @Nullable
    @ColumnInfo(name = "completed_at")
    public Long completedAt;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;
}
