package com.litemobiletools.dailyexpensetracker.model;

import androidx.annotation.NonNull;

import com.litemobiletools.dailyexpensetracker.data.entity.TransactionRow;

import java.util.Collections;
import java.util.List;

/**
 * Everything the dashboard displays, in one immutable object, built by DashboardRepository
 * from the Room database. {@link #empty()} is the zero state shown before data has loaded.
 */
public final class DashboardSnapshot {

    public final long totalBalanceMinor;
    public final long monthIncomeMinor;
    public final long monthExpenseMinor;
    public final long monthBudgetMinor;
    @NonNull public final List<CategorySlice> breakdown;
    @NonNull public final List<TransactionRow> recent;
    /** Expenses of the previous month (for a month-over-month comparison); 0 if none. */
    public final long prevMonthExpenseMinor;
    /** The category budgets (monthly) closest to or over their limit, at most three. */
    @NonNull public final List<BudgetProgress> budgetHighlights;
    /**
     * How many transactions this cover month use a currency other than the working currency.
     * The totals are never converted, so they only include the working currency; screens use
     * this to disclose the excluded entries truthfully. 0 means the totals are complete.
     */
    public final int otherCurrencyCount;

    public DashboardSnapshot(long totalBalanceMinor, long monthIncomeMinor, long monthExpenseMinor,
                             long monthBudgetMinor, @NonNull List<CategorySlice> breakdown,
                             @NonNull List<TransactionRow> recent, long prevMonthExpenseMinor,
                             @NonNull List<BudgetProgress> budgetHighlights, int otherCurrencyCount) {
        this.totalBalanceMinor = totalBalanceMinor;
        this.monthIncomeMinor = monthIncomeMinor;
        this.monthExpenseMinor = monthExpenseMinor;
        this.monthBudgetMinor = monthBudgetMinor;
        this.breakdown = breakdown;
        this.recent = recent;
        this.prevMonthExpenseMinor = prevMonthExpenseMinor;
        this.budgetHighlights = budgetHighlights;
        this.otherCurrencyCount = otherCurrencyCount;
    }

    /** Zero state: nothing recorded (or not loaded yet). */
    @NonNull
    public static DashboardSnapshot empty() {
        return new DashboardSnapshot(0, 0, 0, 0,
                Collections.<CategorySlice>emptyList(), Collections.<TransactionRow>emptyList(),
                0, Collections.<BudgetProgress>emptyList(), 0);
    }
}
