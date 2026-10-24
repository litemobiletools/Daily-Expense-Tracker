package com.litemobiletools.dailyexpensetracker.util;

import androidx.annotation.Nullable;

/** Arithmetic for spending insights. Every method is safe for zero denominators. */
public final class InsightMath {

    private InsightMath() {
    }

    /**
     * Change from {@code previous} to {@code current} in whole percent (rounded toward zero),
     * or null when there is nothing to compare against (previous is zero).
     */
    @Nullable
    public static Integer percentChange(long current, long previous) {
        if (previous <= 0) {
            return null;
        }
        return (int) ((current - previous) * 100 / previous);
    }

    /** Share of income that was not spent, in whole percent; null when there is no income. Negative if spending exceeded income. */
    @Nullable
    public static Integer savingsRatePercent(long incomeMinor, long expenseMinor) {
        if (incomeMinor <= 0) {
            return null;
        }
        return (int) ((incomeMinor - expenseMinor) * 100 / incomeMinor);
    }

    /** Average per day in minor units (rounded down); 0 when there are no days. */
    public static long averagePerDay(long totalMinor, int days) {
        return days <= 0 ? 0 : totalMinor / days;
    }
}
