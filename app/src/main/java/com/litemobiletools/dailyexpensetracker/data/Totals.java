package com.litemobiletools.dailyexpensetracker.data;

/** Income and expense sums, in minor units of one currency. */
public final class Totals {

    public final long incomeMinor;
    public final long expenseMinor;

    public Totals(long incomeMinor, long expenseMinor) {
        this.incomeMinor = incomeMinor;
        this.expenseMinor = expenseMinor;
    }

    /** Income minus expenses (may be negative). */
    public long netMinor() {
        return incomeMinor - expenseMinor;
    }
}
