package com.litemobiletools.dailyexpensetracker.data.repository;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.data.AppDatabase;
import com.litemobiletools.dailyexpensetracker.data.DatabaseExecutor;
import com.litemobiletools.dailyexpensetracker.data.RepoCallback;
import com.litemobiletools.dailyexpensetracker.data.Totals;
import com.litemobiletools.dailyexpensetracker.data.entity.CategoryTotal;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionRow;
import com.litemobiletools.dailyexpensetracker.data.entity.BudgetEntity;
import com.litemobiletools.dailyexpensetracker.model.BudgetPeriod;
import com.litemobiletools.dailyexpensetracker.model.BudgetProgress;
import com.litemobiletools.dailyexpensetracker.model.CategorySlice;
import com.litemobiletools.dailyexpensetracker.model.DashboardSnapshot;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;
import com.litemobiletools.dailyexpensetracker.util.DateRanges;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * Builds the dashboard from real data, in a single database transaction so every number comes
 * from the same consistent snapshot. Only transactions in {@code currency} are combined.
 */
public class DashboardRepository {

    private static final int RECENT_LIMIT = 5;
    /** Slices shown in the chart; any further categories are folded into "Other". */
    private static final int MAX_SLICES = 5;
    private static final int[] SLICE_COLORS = {
            R.color.chart_1, R.color.chart_2, R.color.chart_3, R.color.chart_4, R.color.chart_5
    };

    private final AppDatabase db;
    private final TransactionRepository transactions;
    private final BudgetRepository budgets;

    public DashboardRepository(@NonNull AppDatabase db) {
        this.db = db;
        this.transactions = new TransactionRepository(db);
        this.budgets = new BudgetRepository(db);
    }

    public static DashboardRepository get(@NonNull Context context) {
        return new DashboardRepository(AppDatabase.getInstance(context));
    }

    public void load(@NonNull String currency, @NonNull String otherLabel,
                     @NonNull RepoCallback<DashboardSnapshot> callback) {
        DatabaseExecutor.run(() -> loadSync(currency, otherLabel,
                System.currentTimeMillis(), TimeZone.getDefault()), callback);
    }

    @NonNull
    @WorkerThread
    public DashboardSnapshot loadSync(@NonNull String currency, @NonNull String otherLabel,
                                      long nowMillis, @NonNull TimeZone zone) {
        return db.runInTransaction(() -> {
            long monthStart = DateRanges.startOfMonth(nowMillis, zone);
            long monthEnd = DateRanges.startOfNextMonth(nowMillis, zone);

            Totals allTime = transactions.getAllTimeTotalsSync(currency);
            Totals month = transactions.getTotalsSync(currency, monthStart, monthEnd);
            List<CategoryTotal> byCategory = transactions.getTotalsByCategorySync(
                    TransactionType.EXPENSE, currency, monthStart, monthEnd);
            // Totals are never converted: disclose the entries in other currencies that the
            // numbers above do not include. Same consistent snapshot, so the note always matches.
            int otherCurrencyCount = db.transactionDao().countOtherCurrencies(currency, monthStart, monthEnd);

            List<TransactionRow> recent = transactions.getRecentSync(RECENT_LIMIT);

            // Budgets: the overall monthly limit feeds the existing progress card; the category budgets
            // closest to their limit are shown beneath it. Both read the same period and currency.
            long overallBudget = 0;
            List<BudgetProgress> categoryBudgets = new ArrayList<>();
            for (BudgetEntity budget : db.budgetDao().listByPeriod(BudgetPeriod.MONTHLY, currency)) {
                if (budget.categoryId == null) {
                    overallBudget = budget.amountMinor;
                } else {
                    categoryBudgets.add(budgets.progressOf(budget, monthStart, monthEnd));
                }
            }
            java.util.Collections.sort(categoryBudgets, (a, b) -> Integer.compare(b.percent(), a.percent()));
            List<BudgetProgress> highlights = new ArrayList<>(
                    categoryBudgets.subList(0, Math.min(3, categoryBudgets.size())));

            long prevMonthStart = DateRanges.startOfMonth(monthStart - 1, zone);
            long prevExpense = transactions.getTotalsSync(currency, prevMonthStart, monthStart).expenseMinor;

            return new DashboardSnapshot(allTime.netMinor(), month.incomeMinor, month.expenseMinor,
                    overallBudget, toSlices(byCategory, otherLabel), recent, prevExpense, highlights,
                    otherCurrencyCount);
        });
    }

    private static List<CategorySlice> toSlices(List<CategoryTotal> totals, String otherLabel) {
        List<CategorySlice> slices = new ArrayList<>();
        long rest = 0;
        for (int i = 0; i < totals.size(); i++) {
            CategoryTotal total = totals.get(i);
            if (i < MAX_SLICES - 1 || totals.size() == MAX_SLICES) {
                slices.add(new CategorySlice(total.categoryName, total.totalMinor, SLICE_COLORS[i]));
            } else {
                rest += total.totalMinor;
            }
        }
        if (rest > 0) {
            slices.add(new CategorySlice(otherLabel, rest, SLICE_COLORS[MAX_SLICES - 1]));
        }
        return slices;
    }
}
