package com.litemobiletools.dailyexpensetracker.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.litemobiletools.dailyexpensetracker.data.entity.BudgetEntity;
import com.litemobiletools.dailyexpensetracker.util.BudgetMath;

/** One budget evaluated against recorded expenses for one period. Computed, never stored. */
public final class BudgetProgress {

    @NonNull public final BudgetEntity budget;
    /** Null for the overall budget. */
    @Nullable public final String categoryName;
    public final long spentMinor;
    public final long periodStart;
    public final long periodEndExclusive;

    public BudgetProgress(@NonNull BudgetEntity budget, @Nullable String categoryName, long spentMinor,
                          long periodStart, long periodEndExclusive) {
        this.budget = budget;
        this.categoryName = categoryName;
        this.spentMinor = spentMinor;
        this.periodStart = periodStart;
        this.periodEndExclusive = periodEndExclusive;
    }

    public boolean isOverall() {
        return budget.categoryId == null;
    }

    public long limitMinor() {
        return budget.amountMinor;
    }

    /** Negative once the limit is exceeded. */
    public long remainingMinor() {
        return BudgetMath.remaining(spentMinor, budget.amountMinor);
    }

    public int percent() {
        return BudgetMath.percent(spentMinor, budget.amountMinor);
    }

    @NonNull
    public BudgetMath.Status status() {
        return BudgetMath.status(spentMinor, budget.amountMinor);
    }
}
