package com.litemobiletools.dailyexpensetracker.backup;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.litemobiletools.dailyexpensetracker.data.AppDatabase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * Reads the complete user data from the database in one consistent snapshot (all reads inside a
 * single Room transaction) for two purposes: the manual "Back Up Now" file, and the temporary
 * local safety copy taken before a restore replaces the data. Worker thread only.
 */
public final class BackupCollector {

    private BackupCollector() {
    }

    /**
     * Collects every persistent table, sorted by id, plus the preferences passed in (they are not
     * kept in the database).
     */
    @WorkerThread
    @NonNull
    public static BackupData snapshot(@NonNull AppDatabase db,
                                      @NonNull BackupData.AppInfo appInfo,
                                      @NonNull BackupData.Preferences preferences) {
        return db.runInTransaction(() ->
                build(db, System.currentTimeMillis(), appInfo, preferences));
    }

    private static BackupData build(AppDatabase db, long nowMs, BackupData.AppInfo appInfo,
                                    BackupData.Preferences preferences) {
        BackupData data = new BackupData(appInfo, nowMs, preferences,
                new ArrayList<>(db.categoryDao().getAll()),
                new ArrayList<>(db.paymentMethodDao().getAll()),
                new ArrayList<>(db.transactionDao().getAllEntities()),
                new ArrayList<>(db.budgetDao().getAll()),
                new ArrayList<>(db.savingsGoalDao().getAll()),
                new ArrayList<>(db.savingsGoalDao().getAllContributions()),
                new ArrayList<>(db.recurringDao().listRules()),
                new ArrayList<>(db.recurringDao().listAllOccurrences()));        // Sorting by id keeps byte-identical snapshots for the same data (stable checksums).
        sortById(data.categories, c -> c.id);
        sortById(data.paymentMethods, p -> p.id);
        sortById(data.transactions, t -> t.id);
        sortById(data.budgets, b -> b.id);
        sortById(data.savingsGoals, g -> g.id);
        sortById(data.goalContributions, c -> c.id);
        sortById(data.recurringRules, r -> r.id);
        sortById(data.recurringOccurrences, o -> o.id);
        return data;
    }

    private static <T> void sortById(@NonNull List<T> rows, @NonNull ToLongFunction<T> id) {
        rows.sort(Comparator.comparingLong(id));
    }
}
