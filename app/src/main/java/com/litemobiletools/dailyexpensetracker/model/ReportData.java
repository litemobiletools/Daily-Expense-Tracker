package com.litemobiletools.dailyexpensetracker.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.litemobiletools.dailyexpensetracker.data.entity.CategoryTotal;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionRow;

import java.util.List;

/**
 * Everything the Reports screen and the exports show for one period, in one currency. Figures are
 * recorded facts from saved transactions only (savings-goal contributions and recurring templates are
 * not transactions and are never included).
 */
public final class ReportData {

    /** Income and expenses of one calendar month inside the range. */
    public static final class MonthTotal {
        public final long monthStart;
        public final long incomeMinor;
        public final long expenseMinor;

        public MonthTotal(long monthStart, long incomeMinor, long expenseMinor) {
            this.monthStart = monthStart;
            this.incomeMinor = incomeMinor;
            this.expenseMinor = expenseMinor;
        }
    }

    @NonNull public final ReportRange range;
    @NonNull public final ReportRange previousRange;
    @NonNull public final String currencyCode;
    public final long incomeMinor;
    public final long expenseMinor;
    public final long prevIncomeMinor;
    public final long prevExpenseMinor;
    /** Null when the previous period had nothing to compare with. */
    @Nullable public final Integer incomeChangePercent;
    @Nullable public final Integer expenseChangePercent;
    public final int daysCounted;
    public final long averageDailyExpenseMinor;
    @NonNull public final List<CategoryTotal> expenseByCategory;
    @NonNull public final List<CategoryTotal> incomeByCategory;
    @NonNull public final List<TransactionRow> largestExpenses;
    @NonNull public final List<TransactionRow> largestIncome;
    /** Expense per local day; null when the range is long enough to show months instead. */
    @Nullable public final long[] dailyExpenseMinor;
    /** Day starts matching {@link #dailyExpenseMinor}, plus the range end. */
    @Nullable public final long[] dayBoundaries;
    /** Per-month totals; null for short ranges. */
    @Nullable public final List<MonthTotal> monthly;
    /** Budgets of the matching period type, or null when the range is not a single week or month. */
    @Nullable public final List<BudgetProgress> budgets;
    /** Transactions in the range recorded in other currencies (not part of these totals). */
    public final int otherCurrencyCount;

    public ReportData(@NonNull ReportRange range, @NonNull ReportRange previousRange,
                      @NonNull String currencyCode, long incomeMinor, long expenseMinor,
                      long prevIncomeMinor, long prevExpenseMinor, @Nullable Integer incomeChangePercent,
                      @Nullable Integer expenseChangePercent, int daysCounted, long averageDailyExpenseMinor,
                      @NonNull List<CategoryTotal> expenseByCategory, @NonNull List<CategoryTotal> incomeByCategory,
                      @NonNull List<TransactionRow> largestExpenses, @NonNull List<TransactionRow> largestIncome,
                      @Nullable long[] dailyExpenseMinor, @Nullable long[] dayBoundaries,
                      @Nullable List<MonthTotal> monthly, @Nullable List<BudgetProgress> budgets,
                      int otherCurrencyCount) {
        this.range = range;
        this.previousRange = previousRange;
        this.currencyCode = currencyCode;
        this.incomeMinor = incomeMinor;
        this.expenseMinor = expenseMinor;
        this.prevIncomeMinor = prevIncomeMinor;
        this.prevExpenseMinor = prevExpenseMinor;
        this.incomeChangePercent = incomeChangePercent;
        this.expenseChangePercent = expenseChangePercent;
        this.daysCounted = daysCounted;
        this.averageDailyExpenseMinor = averageDailyExpenseMinor;
        this.expenseByCategory = expenseByCategory;
        this.incomeByCategory = incomeByCategory;
        this.largestExpenses = largestExpenses;
        this.largestIncome = largestIncome;
        this.dailyExpenseMinor = dailyExpenseMinor;
        this.dayBoundaries = dayBoundaries;
        this.monthly = monthly;
        this.budgets = budgets;
        this.otherCurrencyCount = otherCurrencyCount;
    }

    /** Income minus expenses (may be negative). */
    public long netMinor() {
        return incomeMinor - expenseMinor;
    }

    public boolean isEmpty() {
        return incomeMinor == 0 && expenseMinor == 0;
    }
}
