package com.litemobiletools.dailyexpensetracker.data.repository;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.litemobiletools.dailyexpensetracker.data.AppDatabase;
import com.litemobiletools.dailyexpensetracker.data.DatabaseExecutor;
import com.litemobiletools.dailyexpensetracker.data.RepoCallback;
import com.litemobiletools.dailyexpensetracker.data.entity.BudgetEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.CategoryTotal;
import com.litemobiletools.dailyexpensetracker.model.BudgetPeriod;
import com.litemobiletools.dailyexpensetracker.model.BudgetProgress;
import com.litemobiletools.dailyexpensetracker.model.ReportData;
import com.litemobiletools.dailyexpensetracker.model.ReportPreset;
import com.litemobiletools.dailyexpensetracker.model.ReportRange;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;
import com.litemobiletools.dailyexpensetracker.util.DailyTotals;
import com.litemobiletools.dailyexpensetracker.util.DateRanges;
import com.litemobiletools.dailyexpensetracker.util.InsightMath;
import com.litemobiletools.dailyexpensetracker.util.PeriodMath;
import com.litemobiletools.dailyexpensetracker.util.ReportPeriods;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * Builds report figures from the same DAO aggregations the rest of the app uses (sum by type,
 * totals by category, largest transactions, budget progress). Everything is read in one database
 * transaction so all numbers come from the same snapshot, and only rows in {@code currency} are summed.
 */
public class ReportRepository {

    /** Ranges up to this many days show a daily trend; longer ones show months. */
    public static final int MAX_DAILY_DAYS = 62;
    private static final int LARGEST = 5;

    private final AppDatabase db;
    private final BudgetRepository budgets;

    public ReportRepository(@NonNull AppDatabase db) {
        this.db = db;
        this.budgets = new BudgetRepository(db);
    }

    public static ReportRepository get(@NonNull Context context) {
        return new ReportRepository(AppDatabase.getInstance(context));
    }

    public void load(@NonNull String currency, @NonNull ReportRange range, @NonNull RepoCallback<ReportData> callback) {
        DatabaseExecutor.run(() -> loadSync(currency, range, System.currentTimeMillis(),
                TimeZone.getDefault(), BudgetRepository.firstDayOfWeek()), callback);
    }

    @NonNull
    @WorkerThread
    public ReportData loadSync(@NonNull String currency, @NonNull ReportRange range, long nowMillis,
                               @NonNull TimeZone zone, int firstDayOfWeek) {
        return db.runInTransaction(() -> {
            long start = range.start;
            long end = range.endExclusive;
            ReportRange previous = ReportPeriods.previous(range, zone);

            long income = db.transactionDao().sumByType(TransactionType.INCOME, currency, start, end);
            long expense = db.transactionDao().sumByType(TransactionType.EXPENSE, currency, start, end);
            long prevIncome = db.transactionDao().sumByType(TransactionType.INCOME, currency, previous.start, previous.endExclusive);
            long prevExpense = db.transactionDao().sumByType(TransactionType.EXPENSE, currency, previous.start, previous.endExclusive);

            int days = PeriodMath.daysBetween(start, end, zone);
            long[] bounds = null;
            long[] daily = null;
            List<ReportData.MonthTotal> monthly = null;
            if (days <= MAX_DAILY_DAYS) {
                bounds = DailyTotals.dayBoundaries(start, end, zone);
                daily = DailyTotals.aggregate(
                        db.transactionDao().timesAndAmounts(TransactionType.EXPENSE, currency, start, end), bounds);
            } else {
                monthly = new ArrayList<>();
                long monthStart = DateRanges.startOfMonth(start, zone);
                while (monthStart < end) {
                    long monthEnd = DateRanges.startOfNextMonth(monthStart, zone);
                    long from = Math.max(monthStart, start);
                    long to = Math.min(monthEnd, end);
                    monthly.add(new ReportData.MonthTotal(monthStart,
                            db.transactionDao().sumByType(TransactionType.INCOME, currency, from, to),
                            db.transactionDao().sumByType(TransactionType.EXPENSE, currency, from, to)));
                    monthStart = monthEnd;
                }
            }

            int daysCounted = PeriodMath.daysElapsed(start, end, nowMillis, zone);
            return new ReportData(range, previous, currency, income, expense, prevIncome, prevExpense,
                    InsightMath.percentChange(income, prevIncome), InsightMath.percentChange(expense, prevExpense),
                    daysCounted, InsightMath.averagePerDay(expense, daysCounted),
                    db.transactionDao().totalsByCategory(TransactionType.EXPENSE, currency, start, end),
                    db.transactionDao().totalsByCategory(TransactionType.INCOME, currency, start, end),
                    db.transactionDao().largest(TransactionType.EXPENSE, currency, start, end, LARGEST),
                    db.transactionDao().largest(TransactionType.INCOME, currency, start, end, LARGEST),
                    daily, bounds, monthly, budgetsFor(range, currency),
                    db.transactionDao().countOtherCurrencies(currency, start, end));
        });
    }

    /**
     * Budget-versus-actual only makes sense when the range is exactly one budget period: weekly budgets
     * for "this week", monthly budgets for "this month" and "previous month".
     */
    @Nullable
    private List<BudgetProgress> budgetsFor(ReportRange range, String currency) {
        BudgetPeriod period;
        if (range.preset == ReportPreset.THIS_WEEK) {
            period = BudgetPeriod.WEEKLY;
        } else if (range.preset == ReportPreset.THIS_MONTH || range.preset == ReportPreset.PREVIOUS_MONTH) {
            period = BudgetPeriod.MONTHLY;
        } else {
            return null;
        }
        List<BudgetProgress> result = new ArrayList<>();
        for (BudgetEntity budget : db.budgetDao().listByPeriod(period, currency)) {
            result.add(budgets.progressOf(budget, range.start, range.endExclusive));
        }
        return result;
    }

    /** Total of one category in the range, in {@code currency}. */
    @WorkerThread
    public long categoryTotalSync(@NonNull TransactionType type, long categoryId, @NonNull String currency,
                                  @NonNull ReportRange range) {
        return db.transactionDao().sumByTypeAndCategory(type, categoryId, currency, range.start, range.endExclusive);
    }

    /** True if {@code totals} contains each category at most once (used by tests and assertions). */
    public static boolean hasUniqueCategories(@NonNull List<CategoryTotal> totals) {
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        for (CategoryTotal t : totals) {
            if (!seen.add(t.categoryId)) {
                return false;
            }
        }
        return true;
    }
}
