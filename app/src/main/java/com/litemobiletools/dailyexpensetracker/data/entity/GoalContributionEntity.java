package com.litemobiletools.dailyexpensetracker.data.entity;

import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** One change to a goal's saved amount: positive = money added, negative = money taken out. Never zero. */
@Entity(tableName = "goal_contributions",
        foreignKeys = @ForeignKey(entity = SavingsGoalEntity.class,
                parentColumns = "id", childColumns = "goal_id",
                onDelete = ForeignKey.CASCADE),
        indices = {@Index(value = {"goal_id", "contributed_at"})})
public class GoalContributionEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "goal_id")
    public long goalId;

    @ColumnInfo(name = "delta_minor")
    public long deltaMinor;

    @Nullable
    public String note;

    @ColumnInfo(name = "contributed_at")
    public long contributedAt;
}
