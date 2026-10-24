package com.litemobiletools.dailyexpensetracker.data.repository;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.litemobiletools.dailyexpensetracker.data.AppDatabase;
import com.litemobiletools.dailyexpensetracker.data.DatabaseExecutor;
import com.litemobiletools.dailyexpensetracker.data.RepoCallback;
import com.litemobiletools.dailyexpensetracker.data.entity.BudgetEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.CategoryTotal;
import com.litemobiletools.dailyexpensetracker.model.BudgetPeriod;
import com.litemobiletools.dailyexpensetracker.model.BudgetProgress;
import com.litemobiletools.dailyexpensetracker.model.MonthInsights;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;
import com.litemobiletools.dailyexpensetracker.util.DailyTotals;
import com.litemobiletools.dailyexpensetracker.util.DateRanges;
import com.litemobiletools.dailyexpensetracker.util.InsightMath;
import com.litemobiletools.dailyexpensetracker.util.PeriodMath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TimeZone;

/** Spending insights for one month, built from the same transaction queries as the rest of the app. */
public class InsightsRepository {

    private static final int TOP_CATEGORIES = 5;
    private static final int LARGEST = 5;
    private static final int HIGHEST_DAYS = 3;

    private final AppDatabase db;
    private final BudgetRepository budgets;

    public InsightsRepository(@NonNull AppDatabase db) {
        this.db = db;
        this.budgets = new BudgetRepository(db);
    }

    public static InsightsRepository get(@NonNull Context context) {
        return new InsightsRepository(AppDatabase.getInstance(context));
    }

    public void load(@NonNull String currency, long monthAnchor, @NonNull RepoCallback<MonthInsights> callback) {
        DatabaseExecutor.run(() -> loadSync(currency, monthAnchor, System.currentTimeMillis(),
                TimeZone.getDefault()), callback);
    }

    @NonNull
    @WorkerThread
    public MonthInsights loadSync(@NonNull String currency, long monthAnchor, long nowMillis,
                                  @NonNull TimeZone zone) {
        return db.runInTransaction(() -> {
            long start = DateRanges.startOfMonth(monthAnchor, zone);
            long end = DateRanges.startOfNextMonth(monthAnchor, zone);
            long prevStart = DateRanges.startOfMonth(start - 1, zone);

            long income = db.transactionDao().sumByType(TransactionType.INCOME, currency, start, end);
            long expense = db.transactionDao().sumByType(TransactionType.EXPENSE, currency, start, end);
            long prevIncome = db.transactionDao().sumByType(TransactionType.INCOME, currency, prevStart, start);
            long prevExpense = db.transactionDao().sumByType(TransactionType.EXPENSE, currency, prevStart, start);

            // Daily totals in the local calendar (done in Java so DST days are exact).
            long[] bounds = DailyTotals.dayBoundaries(start, end, zone);
            long[] daily = DailyTotals.aggregate(
                    db.transactionDao().timesAndAmounts(TransactionType.EXPENSE, currency, start, end), bounds);
            List<MonthInsights.DayTotal> days = new ArrayList<>();
            for (int i = 0; i < daily.length; i++) {
                if (daily[i] > 0) {
                    days.add(new MonthInsights.DayTotal(bounds[i], daily[i]));
                }
            }
            Collections.sort(days, new Comparator<MonthInsights.DayTotal>() {
                @Override
                public int compare(MonthInsights.DayTotal a, MonthInsights.DayTotal b) {
                    int byAmount = Long.compare(b.amountMinor, a.amountMinor);
                    return byAmount != 0 ? byAmount : Long.compare(b.dayStart, a.dayStart);
                }
            });
            List<MonthInsights.DayTotal> highest = new ArrayList<>(
                    days.subList(0, Math.min(HIGHEST_DAYS, days.size())));

            int daysCounted = PeriodMath.daysElapsed(start, end, nowMillis, zone);

            List<CategoryTotal> categories = db.transactionDao().totalsByCategory(
                    TransactionType.EXPENSE, currency, start, end);
            categories = new ArrayList<>(categories.subList(0, Math.min(TOP_CATEGORIES, categories.size())));

            List<BudgetProgress> monthly = new ArrayList<>();
            for (BudgetEntity budget : db.budgetDao().listByPeriod(BudgetPeriod.MONTHLY, currency)) {
                monthly.add(budgets.progressOf(budget, start, end));
            }

            return new MonthInsights(start, end, income, expense, prevIncome, prevExpense, daysCounted,
                    InsightMath.averagePerDay(expense, daysCounted),
                    InsightMath.percentChange(expense, prevExpense),
                    InsightMath.savingsRatePercent(income, expense), categories,
                    db.transactionDao().largest(TransactionType.EXPENSE, currency, start, end, LARGEST),
                    daily, highest, monthly);
        });
    }
}
