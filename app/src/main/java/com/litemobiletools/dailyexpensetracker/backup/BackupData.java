package com.litemobiletools.dailyexpensetracker.backup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.litemobiletools.dailyexpensetracker.data.entity.BudgetEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.CategoryEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.GoalContributionEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.PaymentMethodEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.RecurringOccurrenceEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.RecurringRuleEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.SavingsGoalEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionEntity;

import java.util.List;

/**
 * The complete user data of one backup: rows of all persistent Room tables (the existing entity
 * classes, never duplicated) plus the app's supported preferences. Created from a live database
 * by {@link BackupCollector}, or from file bytes by {@link BackupParser}. Holds no tokens,
 * credentials or transient state.
 */
public final class BackupData {

    /** Information about the app and schema the backup was made with (written into the header). */
    public static final class AppInfo {
        @NonNull public final String appId;
        @NonNull public final String appVersion;
        public final int appVersionCode;
        public final int dbSchemaVersion;

        public AppInfo(@NonNull String appId, @NonNull String appVersion, int appVersionCode,
                       int dbSchemaVersion) {
            this.appId = appId;
            this.appVersion = appVersion;
            this.appVersionCode = appVersionCode;
            this.dbSchemaVersion = dbSchemaVersion;
        }
    }

    /** The two app preferences the backup preserves (theme and recurring handling). */
    public static final class Preferences {
        /** One of AppPreferences.THEME_* (0 = follow system, 1 = light, 2 = dark). */
        public final int themeMode;
        /** {@code MissedPolicy.name()} as written in the file. */
        @NonNull public final String missedPolicy;

        public Preferences(int themeMode, @NonNull String missedPolicy) {
            this.themeMode = themeMode;
            this.missedPolicy = missedPolicy;
        }
    }

    @NonNull public final AppInfo appInfo;
    public final long createdAtMs;
    @NonNull public final Preferences preferences;

    @NonNull public final List<CategoryEntity> categories;
    @NonNull public final List<PaymentMethodEntity> paymentMethods;
    @NonNull public final List<TransactionEntity> transactions;
    @NonNull public final List<BudgetEntity> budgets;
    @NonNull public final List<SavingsGoalEntity> savingsGoals;
    @NonNull public final List<GoalContributionEntity> goalContributions;
    @NonNull public final List<RecurringRuleEntity> recurringRules;
    @NonNull public final List<RecurringOccurrenceEntity> recurringOccurrences;

    public BackupData(@NonNull AppInfo appInfo, long createdAtMs, @NonNull Preferences preferences,
                      @NonNull List<CategoryEntity> categories,
                      @NonNull List<PaymentMethodEntity> paymentMethods,
                      @NonNull List<TransactionEntity> transactions,
                      @NonNull List<BudgetEntity> budgets,
                      @NonNull List<SavingsGoalEntity> savingsGoals,
                      @NonNull List<GoalContributionEntity> goalContributions,
                      @NonNull List<RecurringRuleEntity> recurringRules,
                      @NonNull List<RecurringOccurrenceEntity> recurringOccurrences) {
        this.appInfo = appInfo;
        this.createdAtMs = createdAtMs;
        this.preferences = preferences;
        this.categories = categories;
        this.paymentMethods = paymentMethods;
        this.transactions = transactions;
        this.budgets = budgets;
        this.savingsGoals = savingsGoals;
        this.goalContributions = goalContributions;
        this.recurringRules = recurringRules;
        this.recurringOccurrences = recurringOccurrences;
    }

    /** Number of database records in the backup (preferences not counted). */
    public int recordCount() {
        return categories.size() + paymentMethods.size() + transactions.size() + budgets.size()
                + savingsGoals.size() + goalContributions.size() + recurringRules.size()
                + recurringOccurrences.size();
    }

    /** Timestamp of a backup file, preferring the file name (local device time) over creation time. */
    public long displayTimeMs(@Nullable String fileName) {
        long fromName = BackupFileNames.timestampFromName(fileName == null ? "" : fileName);
        return fromName != 0 ? fromName : createdAtMs;
    }
}
