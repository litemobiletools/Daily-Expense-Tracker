package com.litemobiletools.dailyexpensetracker.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.room.migration.Migration;

import com.litemobiletools.dailyexpensetracker.data.dao.CategoryDao;
import com.litemobiletools.dailyexpensetracker.data.dao.PaymentMethodDao;
import com.litemobiletools.dailyexpensetracker.data.dao.TransactionDao;
import com.litemobiletools.dailyexpensetracker.data.dao.BudgetDao;
import com.litemobiletools.dailyexpensetracker.data.dao.RecurringDao;
import com.litemobiletools.dailyexpensetracker.data.dao.SavingsGoalDao;
import com.litemobiletools.dailyexpensetracker.data.entity.BudgetEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.CategoryEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.GoalContributionEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.PaymentMethodEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.RecurringOccurrenceEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.RecurringRuleEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.SavingsGoalEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionEntity;

/**
 * The app's only database. Queries are never allowed on the main thread
 * ({@code allowMainThreadQueries} is not used), and destructive migration is never enabled:
 * every schema change must ship an explicit {@link Migration} in {@link #MIGRATIONS}
 * together with the exported schema JSON in {@code app/schemas}.
 */
@Database(
        entities = {
                TransactionEntity.class, CategoryEntity.class, PaymentMethodEntity.class,
                BudgetEntity.class, SavingsGoalEntity.class, GoalContributionEntity.class,
                RecurringRuleEntity.class, RecurringOccurrenceEntity.class},
        version = 2,
        exportSchema = true)
@TypeConverters(Converters.class)
public abstract class AppDatabase extends RoomDatabase {

    public static final String DATABASE_NAME = "daily_expense_tracker.db";

    /** One Migration per schema version bump; see {@link Migrations}. Version 1 is the Phase 1-3 schema. */
    public static final Migration[] MIGRATIONS = new Migration[]{Migrations.MIGRATION_1_2};

    private static volatile AppDatabase instance;

    public abstract TransactionDao transactionDao();

    public abstract CategoryDao categoryDao();

    public abstract PaymentMethodDao paymentMethodDao();

    public abstract BudgetDao budgetDao();

    public abstract SavingsGoalDao savingsGoalDao();

    public abstract RecurringDao recurringDao();

    @NonNull
    public static AppDatabase getInstance(@NonNull Context context) {
        AppDatabase local = instance;
        if (local == null) {
            synchronized (AppDatabase.class) {
                local = instance;
                if (local == null) {
                    local = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, DATABASE_NAME)
                            .addMigrations(MIGRATIONS)
                            .build();
                    instance = local;
                    // Queued first on the single-thread executor, so every later read sees
                    // the default categories. The seeder is idempotent.
                    final AppDatabase db = local;
                    DatabaseExecutor.execute(() -> DefaultDataSeeder.seed(db));
                }
            }
        }
        return local;
    }
}
