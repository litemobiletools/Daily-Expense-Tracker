package com.litemobiletools.dailyexpensetracker.data.repository;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.litemobiletools.dailyexpensetracker.data.AppDatabase;
import com.litemobiletools.dailyexpensetracker.data.DatabaseExecutor;
import com.litemobiletools.dailyexpensetracker.data.RepoCallback;
import com.litemobiletools.dailyexpensetracker.data.ValidationException;
import com.litemobiletools.dailyexpensetracker.data.ValidationException.Reason;
import com.litemobiletools.dailyexpensetracker.data.entity.BudgetEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.CategoryEntity;
import com.litemobiletools.dailyexpensetracker.model.BudgetPeriod;
import com.litemobiletools.dailyexpensetracker.model.BudgetProgress;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;
import com.litemobiletools.dailyexpensetracker.util.AmountParser;
import com.litemobiletools.dailyexpensetracker.util.CurrencyFormatter;
import com.litemobiletools.dailyexpensetracker.util.PeriodMath;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * Budgets and their progress. Only EXPENSE transactions in the budget's own currency count, and
 * each budget is computed independently: an expense counts once toward the overall budget and
 * once toward its category budget, but the two figures are never added together.
 * Budgets never block recording an expense.
 */
public class BudgetRepository {

    private final AppDatabase db;

    public BudgetRepository(@NonNull AppDatabase db) {
        this.db = db;
    }

    public static BudgetRepository get(@NonNull Context context) {
        return new BudgetRepository(AppDatabase.getInstance(context));
    }

    public static int firstDayOfWeek() {
        return Calendar.getInstance().getFirstDayOfWeek();
    }

    // ---- Async API -------------------------------------------------------------------

    public void loadProgress(@NonNull BudgetPeriod period, @NonNull String currency, long anchor,
                             @NonNull RepoCallback<List<BudgetProgress>> callback) {
        DatabaseExecutor.run(() -> progressSync(period, currency, anchor, TimeZone.getDefault(),
                firstDayOfWeek()), callback);
    }

    /** Creates a budget, or updates the amount of the existing one for the same period, category and currency. */
    public void save(@Nullable Long budgetId, @NonNull BudgetPeriod period, @Nullable Long categoryId,
                     @NonNull String currency, long amountMinor, @NonNull RepoCallback<Long> callback) {
        DatabaseExecutor.run(() -> saveSync(budgetId, period, categoryId, currency, amountMinor), callback);
    }

    public void delete(long budgetId, @NonNull RepoCallback<Boolean> callback) {
        DatabaseExecutor.run(() -> deleteSync(budgetId), callback);
    }

    public void get(long budgetId, @NonNull RepoCallback<BudgetEntity> callback) {
        DatabaseExecutor.run(() -> db.budgetDao().getById(budgetId), callback);
    }

    // ---- Synchronous API (worker thread only) ----------------------------------------

    /** Progress of every budget of {@code period} for the period containing {@code anchor}. */
    @NonNull
    @WorkerThread
    public List<BudgetProgress> progressSync(@NonNull BudgetPeriod period, @NonNull String currency,
                                             long anchor, @NonNull TimeZone zone, int firstDayOfWeek) {
        return db.runInTransaction(() -> {
            long[] range = PeriodMath.range(period, anchor, zone, firstDayOfWeek);
            List<BudgetProgress> result = new ArrayList<>();
            for (BudgetEntity budget : db.budgetDao().listByPeriod(period, currency)) {
                result.add(progressOf(budget, range[0], range[1]));
            }
            return result;
        });
    }

    /** Progress of one budget over an explicit range (used by dashboard and insights). */
    @NonNull
    @WorkerThread
    public BudgetProgress progressOf(@NonNull BudgetEntity budget, long start, long endExclusive) {
        long spent;
        String categoryName = null;
        if (budget.categoryId == null) {
            spent = db.transactionDao().sumByType(TransactionType.EXPENSE, budget.currencyCode, start, endExclusive);
        } else {
            spent = db.transactionDao().sumByTypeAndCategory(TransactionType.EXPENSE, budget.categoryId,
                    budget.currencyCode, start, endExclusive);
            CategoryEntity category = db.categoryDao().getById(budget.categoryId);
            categoryName = category == null ? "" : category.name;
        }
        return new BudgetProgress(budget, categoryName, spent, start, endExclusive);
    }

    @WorkerThread
    public long saveSync(@Nullable Long budgetId, @NonNull BudgetPeriod period, @Nullable Long categoryId,
                         @NonNull String currency, long amountMinor) throws Exception {
        if (amountMinor <= 0) {
            throw new ValidationException(Reason.AMOUNT_NOT_POSITIVE);
        }
        if (amountMinor > AmountParser.maxMinorUnits(CurrencyFormatter.fractionDigits(currency))) {
            throw new ValidationException(Reason.AMOUNT_TOO_LARGE);
        }
        return db.runInTransaction(() -> {
            if (categoryId != null) {
                CategoryEntity category = db.categoryDao().getById(categoryId);
                if (category == null || category.type != TransactionType.EXPENSE) {
                    throw new ValidationException(Reason.BUDGET_CATEGORY_INVALID);
                }
            }
            BudgetEntity existingForKey = db.budgetDao().find(period, categoryId, currency);
            long now = System.currentTimeMillis();
            if (budgetId == null) {
                if (existingForKey != null) {
                    throw new ValidationException(Reason.BUDGET_DUPLICATE);
                }
                BudgetEntity budget = new BudgetEntity();
                budget.period = period;
                budget.categoryId = categoryId;
                budget.currencyCode = currency;
                budget.amountMinor = amountMinor;
                budget.createdAt = now;
                budget.updatedAt = now;
                return db.budgetDao().insert(budget);
            }
            BudgetEntity budget = db.budgetDao().getById(budgetId);
            if (budget == null) {
                throw new ValidationException(Reason.BUDGET_NOT_FOUND);
            }
            if (existingForKey != null && existingForKey.id != budgetId) {
                throw new ValidationException(Reason.BUDGET_DUPLICATE);
            }
            budget.period = period;
            budget.categoryId = categoryId;
            budget.currencyCode = currency;
            budget.amountMinor = amountMinor;
            budget.updatedAt = now;
            db.budgetDao().update(budget);
            return budget.id;
        });
    }

    @WorkerThread
    public boolean deleteSync(long budgetId) {
        return db.budgetDao().deleteById(budgetId) > 0;
    }
}
