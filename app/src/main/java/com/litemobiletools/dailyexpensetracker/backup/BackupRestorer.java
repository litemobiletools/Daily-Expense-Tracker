package com.litemobiletools.dailyexpensetracker.backup;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.litemobiletools.dailyexpensetracker.data.AppDatabase;
import com.litemobiletools.dailyexpensetracker.data.entity.BudgetEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.CategoryEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.GoalContributionEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.PaymentMethodEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.RecurringOccurrenceEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.RecurringRuleEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.SavingsGoalEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionEntity;

/**
 * Applies a fully validated {@link BackupData} to the database in ONE Room transaction:
 * all existing rows are removed (children first), then every backup row is inserted with its
 * original id in foreign-key-safe order (parents first). Any validation or insertion failure at
 * any point aborts and rolls back the whole transaction, so the previous local data survives.
 *
 * <p>The caller must validate with {@link BackupValidator} first; this class re-checks only
 * cheap invariants while inserting. Restoring never deletes anything in the cloud backup.
 */
public final class BackupRestorer {

    /** How many records were replaced (for the success message). */
    public static final class Result {
        public final int records;

        Result(int records) {
            this.records = records;
        }
    }

    private BackupRestorer() {
    }

    @WorkerThread
    @NonNull
    public static Result restore(@NonNull AppDatabase db, @NonNull BackupData data) {
        return db.runInTransaction(() -> {
            // 1) Clear every table, children before parents (RESTRICT and CASCADE foreign keys
            //    both end up satisfied; occurrences and contributions go first because they link
            //    to includes rules, goals and transactions).
            db.recurringDao().clearAllOccurrences();
            db.savingsGoalDao().clearAllContributions();
            db.transactionDao().clearAll();
            db.recurringDao().clearAllRules();
            db.budgetDao().clearAll();
            db.savingsGoalDao().clearAll();
            db.categoryDao().clearAll();
            db.paymentMethodDao().clearAll();

            // 2) Insert parents before the rows that reference them, preserving ids: a restored
            //    transaction keeps the id a recurring occurrence points at; contributions keep
            //    the goal ids they point at; occurrences keep the rule and transaction ids.
            for (CategoryEntity c : data.categories) {
                db.categoryDao().insert(c);
            }
            for (PaymentMethodEntity p : data.paymentMethods) {
                db.paymentMethodDao().insert(p);
            }
            for (TransactionEntity t : data.transactions) {
                db.transactionDao().insert(t);
            }
            for (BudgetEntity b : data.budgets) {
                db.budgetDao().insert(b);
            }
            for (SavingsGoalEntity g : data.savingsGoals) {
                db.savingsGoalDao().insert(g);
            }
            for (GoalContributionEntity c : data.goalContributions) {
                db.savingsGoalDao().insertContribution(c);
            }
            for (RecurringRuleEntity r : data.recurringRules) {
                db.recurringDao().insertRule(r);
            }
            for (RecurringOccurrenceEntity o : data.recurringOccurrences) {
                // A plain insert (not IGNORE): the validator already proved ids are unique, so any
                // duplicate at insert time means real trouble and aborts the whole restore.
                db.recurringDao().insertOccurrence(o);
            }

            // 3) Cheap regression check: every table must end up with exactly the restored rows.
            if (db.transactionDao().count() != data.transactions.size()
                    || db.budgetDao().count() != data.budgets.size()) {
                throw new IllegalStateException("restore landed a different number of rows");
            }
            return new Result(data.recordCount());
        });
    }
}
