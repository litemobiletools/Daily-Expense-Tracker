package com.litemobiletools.dailyexpensetracker.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.litemobiletools.dailyexpensetracker.model.BudgetPeriod;

/**
 * A spending limit. {@link #categoryId} null means the overall limit for all expenses; otherwise
 * it limits one expense category. Each budget is evaluated independently (an expense counts once
 * toward the overall budget and once toward its own category budget; the two are never added).
 *
 * <p>The amount is in minor units of {@link #currencyCode}; only expenses in that currency count.
 * At most one budget exists per (period, category, currency): the unique index covers category
 * budgets, and {@code BudgetRepository} enforces it for the overall budget (SQLite treats NULLs as distinct).
 */
@Entity(tableName = "budgets",
        foreignKeys = @ForeignKey(entity = CategoryEntity.class,
                parentColumns = "id", childColumns = "category_id",
                onDelete = ForeignKey.CASCADE),
        indices = {
                @Index(value = {"period", "category_id", "currency_code"}, unique = true),
                @Index("category_id")
        })
public class BudgetEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public BudgetPeriod period = BudgetPeriod.MONTHLY;

    @Nullable
    @ColumnInfo(name = "category_id")
    public Long categoryId;

    @NonNull
    @ColumnInfo(name = "currency_code")
    public String currencyCode = "";

    @ColumnInfo(name = "amount_minor")
    public long amountMinor;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;
}
