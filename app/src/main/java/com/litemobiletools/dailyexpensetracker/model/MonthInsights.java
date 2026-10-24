package com.litemobiletools.dailyexpensetracker.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.litemobiletools.dailyexpensetracker.data.entity.CategoryTotal;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionRow;

import java.util.List;

/**
 * Figures for one calendar month, computed only from recorded transactions in one currency.
 * Nothing here is a forecast.
 */
public final class MonthInsights {

    /** Spending on one local day. */
    public static final class DayTotal {
        public final long dayStart;
        public final long amountMinor;

        public DayTotal(long dayStart, long amountMinor) {
            this.dayStart = dayStart;
            this.amountMinor = amountMinor;
        }
    }

    public final long monthStart;
    public final long monthEndExclusive;
    public final long incomeMinor;
    public final long expenseMinor;
    public final long prevIncomeMinor;
    public final long prevExpenseMinor;
    /** Number of days the average is based on: elapsed days for the current month, all days for a past month, 0 for a future month. */
    public final int daysCounted;
    public final long averageDailyExpenseMinor;
    /** Expense change versus the previous month in percent, or null if the previous month had no expenses. */
    @Nullable public final Integer expenseChangePercent;
    /** Share of income not spent, or null if there was no income. */
    @Nullable public final Integer savingsRatePercent;
    @NonNull public final List<CategoryTotal> topCategories;
    @NonNull public final List<TransactionRow> largestExpenses;
    /** Expense per day for every day of the month, in order. */
    @NonNull public final long[] dailyExpenseMinor;
    @NonNull public final List<DayTotal> highestDays;
    @NonNull public final List<BudgetProgress> monthlyBudgets;

    public MonthInsights(long monthStart, long monthEndExclusive, long incomeMinor, long expenseMinor,
                         long prevIncomeMinor, long prevExpenseMinor, int daysCounted,
                         long averageDailyExpenseMinor, @Nullable Integer expenseChangePercent,
                         @Nullable Integer savingsRatePercent, @NonNull List<CategoryTotal> topCategories,
                         @NonNull List<TransactionRow> largestExpenses, @NonNull long[] dailyExpenseMinor,
                         @NonNull List<DayTotal> highestDays, @NonNull List<BudgetProgress> monthlyBudgets) {
        this.monthStart = monthStart;
        this.monthEndExclusive = monthEndExclusive;
        this.incomeMinor = incomeMinor;
        this.expenseMinor = expenseMinor;
        this.prevIncomeMinor = prevIncomeMinor;
        this.prevExpenseMinor = prevExpenseMinor;
        this.daysCounted = daysCounted;
        this.averageDailyExpenseMinor = averageDailyExpenseMinor;
        this.expenseChangePercent = expenseChangePercent;
        this.savingsRatePercent = savingsRatePercent;
        this.topCategories = topCategories;
        this.largestExpenses = largestExpenses;
        this.dailyExpenseMinor = dailyExpenseMinor;
        this.highestDays = highestDays;
        this.monthlyBudgets = monthlyBudgets;
    }
}
