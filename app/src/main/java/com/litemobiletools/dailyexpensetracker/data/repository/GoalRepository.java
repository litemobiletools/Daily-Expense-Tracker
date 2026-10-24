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
import com.litemobiletools.dailyexpensetracker.data.entity.GoalContributionEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.GoalWithSaved;
import com.litemobiletools.dailyexpensetracker.data.entity.SavingsGoalEntity;
import com.litemobiletools.dailyexpensetracker.util.AmountParser;
import com.litemobiletools.dailyexpensetracker.util.CurrencyFormatter;

import java.util.List;

/**
 * Savings goals are tracking records only. Nothing here creates or changes a transaction or the
 * dashboard balance. A goal's saved amount is the sum of its contribution history, which is kept.
 */
public class GoalRepository {

    public static final int MAX_NAME_LENGTH = 40;

    private final AppDatabase db;

    public GoalRepository(@NonNull AppDatabase db) {
        this.db = db;
    }

    public static GoalRepository get(@NonNull Context context) {
        return new GoalRepository(AppDatabase.getInstance(context));
    }

    // ---- Async API -------------------------------------------------------------------

    public void list(@NonNull RepoCallback<List<GoalWithSaved>> callback) {
        DatabaseExecutor.run(this::listSync, callback);
    }

    public void get(long id, @NonNull RepoCallback<GoalWithSaved> callback) {
        DatabaseExecutor.run(() -> getSync(id), callback);
    }

    public void contributions(long goalId, int limit, @NonNull RepoCallback<List<GoalContributionEntity>> callback) {
        DatabaseExecutor.run(() -> contributionsSync(goalId, limit), callback);
    }

    public void create(@NonNull String name, long targetMinor, @NonNull String currency,
                       @Nullable Long targetDate, @NonNull RepoCallback<Long> callback) {
        DatabaseExecutor.run(() -> createSync(name, targetMinor, currency, targetDate), callback);
    }

    public void update(long id, @NonNull String name, long targetMinor, @Nullable Long targetDate,
                       @NonNull RepoCallback<Boolean> callback) {
        DatabaseExecutor.run(() -> {
            updateSync(id, name, targetMinor, targetDate);
            return Boolean.TRUE;
        }, callback);
    }

    public void contribute(long goalId, long deltaMinor, @Nullable String note,
                           @NonNull RepoCallback<Boolean> callback) {
        DatabaseExecutor.run(() -> {
            contributeSync(goalId, deltaMinor, note, System.currentTimeMillis());
            return Boolean.TRUE;
        }, callback);
    }

    public void setCompleted(long id, boolean completed, @NonNull RepoCallback<Boolean> callback) {
        DatabaseExecutor.run(() -> {
            setCompletedSync(id, completed);
            return Boolean.TRUE;
        }, callback);
    }

    public void delete(long id, @NonNull RepoCallback<Boolean> callback) {
        DatabaseExecutor.run(() -> deleteSync(id), callback);
    }

    // ---- Synchronous API (worker thread only) ----------------------------------------

    @NonNull
    @WorkerThread
    public List<GoalWithSaved> listSync() {
        return db.savingsGoalDao().listWithSaved();
    }

    @Nullable
    @WorkerThread
    public GoalWithSaved getSync(long id) {
        return db.savingsGoalDao().getWithSaved(id);
    }

    @NonNull
    @WorkerThread
    public List<GoalContributionEntity> contributionsSync(long goalId, int limit) {
        return db.savingsGoalDao().contributions(goalId, limit);
    }

    @WorkerThread
    public long createSync(@NonNull String name, long targetMinor, @NonNull String currency,
                           @Nullable Long targetDate) throws Exception {
        String clean = cleanName(name);
        checkAmount(targetMinor, currency);
        long now = System.currentTimeMillis();
        SavingsGoalEntity goal = new SavingsGoalEntity();
        goal.name = clean;
        goal.targetMinor = targetMinor;
        goal.currencyCode = currency;
        goal.targetDate = targetDate;
        goal.createdAt = now;
        goal.updatedAt = now;
        return db.savingsGoalDao().insert(goal);
    }

    /**
     * Edits name, target and target date. Changing the target re-evaluates completion: a completed
     * goal whose new target is above the saved amount reopens; an open goal whose saved amount now
     * reaches the new target completes.
     */
    @WorkerThread
    public void updateSync(long id, @NonNull String name, long targetMinor,
                           @Nullable Long targetDate) throws Exception {
        String clean = cleanName(name);
        db.runInTransaction(() -> {
            SavingsGoalEntity goal = requireGoal(id);
            checkAmount(targetMinor, goal.currencyCode);
            long now = System.currentTimeMillis();
            boolean targetChanged = goal.targetMinor != targetMinor;
            goal.name = clean;
            goal.targetMinor = targetMinor;
            goal.targetDate = targetDate;
            goal.updatedAt = now;
            if (targetChanged) {
                applyAutoCompletion(goal, db.savingsGoalDao().savedMinor(id), now);
            }
            db.savingsGoalDao().update(goal);
            return null;
        });
    }

    /**
     * Adds ({@code deltaMinor} &gt; 0) or removes (&lt; 0) saved money and keeps the history entry.
     * The saved amount can never drop below zero or exceed the largest supported amount.
     */
    @WorkerThread
    public void contributeSync(long goalId, long deltaMinor, @Nullable String note, long nowMillis) throws Exception {
        if (deltaMinor == 0) {
            throw new ValidationException(Reason.AMOUNT_NOT_POSITIVE);
        }
        db.runInTransaction(() -> {
            SavingsGoalEntity goal = requireGoal(goalId);
            long max = AmountParser.maxMinorUnits(CurrencyFormatter.fractionDigits(goal.currencyCode));
            if (Math.abs(deltaMinor) > max) {
                throw new ValidationException(Reason.AMOUNT_TOO_LARGE);
            }
            long saved = db.savingsGoalDao().savedMinor(goalId);
            long updated = saved + deltaMinor;
            if (updated < 0) {
                throw new ValidationException(Reason.WITHDRAWAL_EXCEEDS_SAVED);
            }
            if (updated > max) {
                throw new ValidationException(Reason.AMOUNT_TOO_LARGE);
            }
            GoalContributionEntity contribution = new GoalContributionEntity();
            contribution.goalId = goalId;
            contribution.deltaMinor = deltaMinor;
            String trimmed = note == null ? "" : note.trim();
            contribution.note = trimmed.isEmpty() ? null : trimmed;
            contribution.contributedAt = nowMillis;
            db.savingsGoalDao().insertContribution(contribution);

            goal.updatedAt = nowMillis;
            applyAutoCompletion(goal, updated, nowMillis);
            db.savingsGoalDao().update(goal);
            return null;
        });
    }

    /** Manually completes or reopens a goal (for example to revise a completed goal). */
    @WorkerThread
    public void setCompletedSync(long id, boolean completed) throws Exception {
        db.runInTransaction(() -> {
            SavingsGoalEntity goal = requireGoal(id);
            long now = System.currentTimeMillis();
            goal.isCompleted = completed;
            goal.completedAt = completed ? now : null;
            goal.updatedAt = now;
            db.savingsGoalDao().update(goal);
            return null;
        });
    }

    /** Deletes the goal and its contribution history. */
    @WorkerThread
    public boolean deleteSync(long id) {
        return db.savingsGoalDao().deleteById(id) > 0;
    }

    // ---- Helpers ---------------------------------------------------------------------

    private static void applyAutoCompletion(SavingsGoalEntity goal, long savedMinor, long now) {
        if (!goal.isCompleted && savedMinor >= goal.targetMinor) {
            goal.isCompleted = true;
            goal.completedAt = now;
        } else if (goal.isCompleted && savedMinor < goal.targetMinor) {
            goal.isCompleted = false;
            goal.completedAt = null;
        }
    }

    private SavingsGoalEntity requireGoal(long id) throws ValidationException {
        SavingsGoalEntity goal = db.savingsGoalDao().getById(id);
        if (goal == null) {
            throw new ValidationException(Reason.GOAL_NOT_FOUND);
        }
        return goal;
    }

    private static String cleanName(String name) throws ValidationException {
        String clean = name.trim();
        if (clean.isEmpty() || clean.length() > MAX_NAME_LENGTH) {
            throw new ValidationException(Reason.GOAL_NAME_INVALID);
        }
        return clean;
    }

    private static void checkAmount(long targetMinor, String currency) throws ValidationException {
        if (targetMinor <= 0) {
            throw new ValidationException(Reason.AMOUNT_NOT_POSITIVE);
        }
        if (targetMinor > AmountParser.maxMinorUnits(CurrencyFormatter.fractionDigits(currency))) {
            throw new ValidationException(Reason.AMOUNT_TOO_LARGE);
        }
    }
}
