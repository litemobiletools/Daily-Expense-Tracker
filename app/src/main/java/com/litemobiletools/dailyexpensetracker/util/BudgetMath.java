package com.litemobiletools.dailyexpensetracker.util;

/**
 * Budget progress arithmetic. The thresholds are display conventions, not financial advice:
 * a heads-up from {@link #WARNING_PERCENT}, "reached" at exactly the limit, "exceeded" above it.
 */
public final class BudgetMath {

    public static final int WARNING_PERCENT = 80;
    private static final int MAX_PERCENT = 999;

    public enum Status {
        /** No limit set (amount is zero or negative). */
        NONE,
        OK,
        WARNING,
        REACHED,
        EXCEEDED
    }

    private BudgetMath() {
    }

    /** Whole percent of the limit used (rounded down), 0 when there is no limit or no spending, capped at 999. */
    public static int percent(long spentMinor, long limitMinor) {
        if (limitMinor <= 0 || spentMinor <= 0) {
            return 0;
        }
        long percent = spentMinor * 100 / limitMinor;
        return (int) Math.min(percent, MAX_PERCENT);
    }

    public static Status status(long spentMinor, long limitMinor) {
        if (limitMinor <= 0) {
            return Status.NONE;
        }
        if (spentMinor > limitMinor) {
            return Status.EXCEEDED;
        }
        if (spentMinor == limitMinor) {
            return Status.REACHED;
        }
        return percent(spentMinor, limitMinor) >= WARNING_PERCENT ? Status.WARNING : Status.OK;
    }

    /** Limit minus spending; negative once the limit is exceeded. */
    public static long remaining(long spentMinor, long limitMinor) {
        return limitMinor - spentMinor;
    }
}
