package com.litemobiletools.dailyexpensetracker.backup;

import androidx.annotation.NonNull;

import com.litemobiletools.dailyexpensetracker.data.entity.BudgetEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.CategoryEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.GoalContributionEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.PaymentMethodEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.RecurringOccurrenceEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.RecurringRuleEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.SavingsGoalEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionEntity;
import com.litemobiletools.dailyexpensetracker.data.TransactionInput;
import com.litemobiletools.dailyexpensetracker.data.TransactionValidator;
import com.litemobiletools.dailyexpensetracker.model.MissedPolicy;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;
import com.litemobiletools.dailyexpensetracker.util.AmountParser;
import com.litemobiletools.dailyexpensetracker.util.CurrencyFormatter;

import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Full semantic validation of a parsed backup BEFORE any local data is touched. Recreates the
 * invariants the app's repositories and schema guarantee: positive amounts in known currencies,
 * valid enum values, existing cross-references, unique ids and unique index values. The first
 * failing record rejects the whole restore with a typed reason — nothing is skipped or merged.
 */
public final class BackupValidator {

    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_SHORT_KEY_LENGTH = 64;

    private BackupValidator() {
    }

    /**
     * Validates a parsed backup against itself and against what this app supports.
     *
     * @param expectedAppId the running application id; another app's backup is refused
     * @param currentDbSchemaVersion the running Room schema version; backups made with a newer
     *                               schema are refused (older ones are supported by the format)
     */
    public static void validate(@NonNull BackupData data, @NonNull String expectedAppId,
                                int currentDbSchemaVersion) throws BackupFormatException {
        if (!expectedAppId.equals(data.appInfo.appId)) {
            throw new BackupFormatException(BackupFormatException.Reason.WRONG_APP_ID);
        }
        if (data.appInfo.dbSchemaVersion > currentDbSchemaVersion) {
            throw new BackupFormatException(BackupFormatException.Reason.NEWER_VERSION,
                    "schema " + data.appInfo.dbSchemaVersion + " > " + currentDbSchemaVersion);
        }
        if (data.appInfo.dbSchemaVersion <= 0) {
            throw new BackupFormatException(BackupFormatException.Reason.MALFORMED_RECORD,
                    BackupFormat.TABLE_PREFERENCES);
        }

        Set<Long> categoryIds = validateCategories(data.categories);
        Set<Long> paymentIds = validatePaymentMethods(data.paymentMethods);
        Set<Long> transactionIds = validateTransactions(data.transactions, categoryIds, paymentIds);
        validateBudgets(data.budgets, categoryIds);
        Set<Long> goalIds = validateSavingsGoals(data.savingsGoals);
        validateContributions(data.goalContributions, goalIds);
        validateRules(data.recurringRules, categoryIds, paymentIds);
        validateOccurrences(data.recurringOccurrences,
                idSet(data.recurringRules), transactionIds);

        validatePreferences(data.preferences);
        validateFormatStamp(data);
    }

    private static void validateFormatStamp(@NonNull BackupData data) throws BackupFormatException {
        if (data.createdAtMs <= 0) {
            throw new BackupFormatException(BackupFormatException.Reason.MALFORMED_HEADER);
        }
    }

    @NonNull
    private static Set<Long> validateCategories(@NonNull List<CategoryEntity> rows)
            throws BackupFormatException {
        Set<Long> ids = new HashSet<>();
        Set<String> defaultKeys = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            CategoryEntity c = rows.get(i);
            String where = BackupFormat.TABLE_CATEGORIES + "[" + i + "]";
            require(c.id >= 1, where, "id");
            if (!ids.add(c.id)) {
                throw new BackupFormatException(BackupFormatException.Reason.INVALID_RECORD,
                        where);
            }
            validText(c.name, where);
            validText(c.iconKey, where);
            if (c.defaultKey != null) {
                validShortText(c.defaultKey, where);
                if (!defaultKeys.add(c.defaultKey)) {
                    throw new BackupFormatException(BackupFormatException.Reason.INVALID_RECORD,
                            where);
                }
            }
            // A color is stored as a signed 32-bit ARGB int. The file carries the unsigned
            // 0..0xFFFFFFFF form; backups made by builds before the unsigned form also carry
            // the sign-extended negative value. Both decode losslessly to the same int.
            if (c.colorArgb != null && (c.colorArgb.longValue() < -0x80000000L
                    || c.colorArgb.longValue() > 0xFFFFFFFFL)) {
                throw new BackupFormatException(BackupFormatException.Reason.INVALID_RECORD, where);
            }
            require(c.sortOrder >= 0, where, "sortOrder");
            requireTime(c.createdAt, where);
            requireTime(c.updatedAt, where);
        }
        return ids;
    }

    @NonNull
    private static Set<Long> validatePaymentMethods(@NonNull List<PaymentMethodEntity> rows)
            throws BackupFormatException {
        Set<Long> ids = new HashSet<>();
        Set<String> defaultKeys = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            PaymentMethodEntity p = rows.get(i);
            String where = BackupFormat.TABLE_PAYMENT_METHODS + "[" + i + "]";
            require(p.id >= 1, where, "id");
            if (!ids.add(p.id)) {
                throw new BackupFormatException(BackupFormatException.Reason.INVALID_RECORD, where);
            }
            validText(p.name, where);
            validText(p.iconKey, where);
            if (p.defaultKey != null) {
                validShortText(p.defaultKey, where);
                if (!defaultKeys.add(p.defaultKey)) {
                    throw new BackupFormatException(BackupFormatException.Reason.INVALID_RECORD,
                            where);
                }
            }
            require(p.sortOrder >= 0, where, "sortOrder");
        }
        return ids;
    }

    @NonNull
    private static Set<Long> validateTransactions(@NonNull List<TransactionEntity> rows,
                                                  @NonNull Set<Long> categoryIds,
                                                  @NonNull Set<Long> paymentIds)
            throws BackupFormatException {
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            TransactionEntity t = rows.get(i);
            String where = BackupFormat.TABLE_TRANSACTIONS + "[" + i + "]";
            require(t.id >= 1, where, "id");
            if (!ids.add(t.id)) {
                throw new BackupFormatException(BackupFormatException.Reason.INVALID_RECORD, where);
            }
            rethrowAsInvalid(where, () -> TransactionValidator.validate(new TransactionInput(
                    t.type, t.amountMinor, t.currencyCode, t.categoryId, t.paymentMethodId,
                    t.transactionTimestamp, t.note)));
            if (!categoryIds.contains(t.categoryId)) {
                throw new BackupFormatException(BackupFormatException.Reason.BROKEN_REFERENCE, where);
            }
            if (t.paymentMethodId != null && !paymentIds.contains(t.paymentMethodId)) {
                throw new BackupFormatException(BackupFormatException.Reason.BROKEN_REFERENCE, where);
            }
            requireTime(t.createdAt, where);
            requireTime(t.updatedAt, where);
        }
        return ids;
    }

    private static void validateBudgets(@NonNull List<BudgetEntity> rows,
                                        @NonNull Set<Long> categoryIds) throws BackupFormatException {
        Set<String> uniqueKeys = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            BudgetEntity b = rows.get(i);
            String where = BackupFormat.TABLE_BUDGETS + "[" + i + "]";
            require(b.id >= 1, where, "id");
            // The schema's unique index is (period, category, currency); the id is never part of it.
            if (!uniqueKeys.add(b.period + "|" + b.currencyCode + "|" + b.categoryId)) {
                throw new BackupFormatException(BackupFormatException.Reason.INVALID_RECORD, where);
            }
            if (!uniqueCurrency(b.currencyCode)) {
                throw new BackupFormatException(BackupFormatException.Reason.INVALID_RECORD, where);
            }
            require(b.amountMinor > 0, where, "amountMinor");
            maxAmount(b.currencyCode, b.amountMinor, where);
            if (b.categoryId != null && !categoryIds.contains(b.categoryId)) {
                throw new BackupFormatException(BackupFormatException.Reason.BROKEN_REFERENCE, where);
            }
            requireTime(b.createdAt, where);
            requireTime(b.updatedAt, where);
        }
    }

    @NonNull
    private static Set<Long> validateSavingsGoals(@NonNull List<SavingsGoalEntity> rows)
            throws BackupFormatException {
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            SavingsGoalEntity g = rows.get(i);
            String where = BackupFormat.TABLE_SAVINGS_GOALS + "[" + i + "]";
            require(g.id >= 1, where, "id");
            if (!ids.add(g.id)) {
                throw new BackupFormatException(BackupFormatException.Reason.INVALID_RECORD, where);
            }
            validText(g.name, where);
            if (!uniqueCurrency(g.currencyCode)) {
                throw new BackupFormatException(BackupFormatException.Reason.INVALID_RECORD, where);
            }
            require(g.targetMinor > 0, where, "targetMinor");
            maxAmount(g.currencyCode, g.targetMinor, where);
            if (g.targetDate != null) {
                require(g.targetDate > 0, where, "targetDate");
            }
            if (g.isCompleted) {
                if (g.completedAt == null || g.completedAt <= 0) {
                    throw new BackupFormatException(BackupFormatException.Reason.INVALID_RECORD, where);
                }
            } else if (g.completedAt != null) {
                throw new BackupFormatException(BackupFormatException.Reason.INVALID_RECORD, where);
            }
            requireTime(g.createdAt, where);
            requireTime(g.updatedAt, where);
        }
        return ids;
    }

    private static void validateContributions(@NonNull List<GoalContributionEntity> rows,
                                              @NonNull Set<Long> goalIds)
            throws BackupFormatException {
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            GoalContributionEntity c = rows.get(i);
            String where = BackupFormat.TABLE_GOAL_CONTRIBUTIONS + "[" + i + "]";
            require(c.id >= 1, where, "id");
            if (!ids.add(c.id)) {
                throw new BackupFormatException(BackupFormatException.Reason.INVALID_RECORD, where);
            }
            require(c.deltaMinor != 0, where, "deltaMinor");
            if (c.note != null) {
                TransactionInput probe = new TransactionInput(TransactionType.EXPENSE, 1, "USD",
                        1L, null, 1, c.note);
                rethrowAsInvalid(where, () -> TransactionValidator.validate(probe));
            }
            requireTime(c.contributedAt, where);
            if (!goalIds.contains(c.goalId)) {
                throw new BackupFormatException(BackupFormatException.Reason.BROKEN_REFERENCE, where);
            }
        }
    }

    private static void validateRules(@NonNull List<RecurringRuleEntity> rows,
                                      @NonNull Set<Long> categoryIds,
                                      @NonNull Set<Long> paymentIds) throws BackupFormatException {
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            RecurringRuleEntity r = rows.get(i);
            String where = BackupFormat.TABLE_RECURRING_RULES + "[" + i + "]";
            require(r.id >= 1, where, "id");
            if (!ids.add(r.id)) {
                throw new BackupFormatException(BackupFormatException.Reason.INVALID_RECORD, where);
            }
            require(r.startDate > 0, where, "startDate");
            if (r.endDate != null && r.endDate < r.startDate) {
                throw new BackupFormatException(BackupFormatException.Reason.INVALID_RECORD, where);
            }
            rethrowAsInvalid(where, () -> TransactionValidator.validate(new TransactionInput(
                    r.type, r.amountMinor, r.currencyCode, r.categoryId, r.paymentMethodId,
                    r.startDate, r.note)));
            if (!categoryIds.contains(r.categoryId)) {
                throw new BackupFormatException(BackupFormatException.Reason.BROKEN_REFERENCE, where);
            }
            if (r.paymentMethodId != null && !paymentIds.contains(r.paymentMethodId)) {
                throw new BackupFormatException(BackupFormatException.Reason.BROKEN_REFERENCE, where);
            }
            require(r.nextIndex >= 0, where, "nextIndex");
            require(r.skippedCount >= 0, where, "skippedCount");
            requireTime(r.createdAt, where);
            requireTime(r.updatedAt, where);
        }
    }

    private static void validateOccurrences(@NonNull List<RecurringOccurrenceEntity> rows,
                                            @NonNull Set<Long> ruleIds,
                                            @NonNull Set<Long> transactionIds)
            throws BackupFormatException {
        Set<Long> ids = new HashSet<>();
        Set<String> uniqueKeys = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            RecurringOccurrenceEntity o = rows.get(i);
            String where = BackupFormat.TABLE_RECURRING_OCCURRENCES + "[" + i + "]";
            require(o.id >= 1, where, "id");
            if (!ids.add(o.id)) {
                throw new BackupFormatException(BackupFormatException.Reason.INVALID_RECORD, where);
            }
            requireTime(o.occurrenceDate, where);
            requireTime(o.createdAt, where);
            if (!uniqueKeys.add(o.ruleId + "|" + o.occurrenceDate)) {
                throw new BackupFormatException(BackupFormatException.Reason.INVALID_RECORD, where);
            }
            if (!ruleIds.contains(o.ruleId)) {
                throw new BackupFormatException(BackupFormatException.Reason.BROKEN_REFERENCE, where);
            }
            if (o.transactionId != null && !transactionIds.contains(o.transactionId)) {
                throw new BackupFormatException(BackupFormatException.Reason.BROKEN_REFERENCE, where);
            }
        }
    }

    private static void validatePreferences(@NonNull BackupData.Preferences preferences)
            throws BackupFormatException {
        String where = BackupFormat.TABLE_PREFERENCES;
        if (preferences.themeMode < 0 || preferences.themeMode > 2) {
            throw new BackupFormatException(BackupFormatException.Reason.INVALID_RECORD, where);
        }
        try {
            MissedPolicy.valueOf(preferences.missedPolicy);
        } catch (IllegalArgumentException e) {
            throw new BackupFormatException(BackupFormatException.Reason.INVALID_RECORD, where);
        }
    }

    // ---- Helpers ------------------------------------------------------------------------

    @NonNull
    private static Set<Long> idSet(@NonNull List<?> rows) {
        Set<Long> ids = new HashSet<>();
        for (Object row : rows) {
            if (row instanceof RecurringRuleEntity) {
                ids.add(((RecurringRuleEntity) row).id);
            } else if (row instanceof TransactionEntity) {
                ids.add(((TransactionEntity) row).id);
            }
        }
        return ids;
    }

    private static void validText(@NonNull String text, @NonNull String where)
            throws BackupFormatException {
        if (text.isEmpty() || text.length() > MAX_NAME_LENGTH) {
            throw new BackupFormatException(BackupFormatException.Reason.INVALID_RECORD, where);
        }
    }

    private static void validShortText(@NonNull String text, @NonNull String where)
            throws BackupFormatException {
        if (text.isEmpty() || text.length() > MAX_SHORT_KEY_LENGTH) {
            throw new BackupFormatException(BackupFormatException.Reason.INVALID_RECORD, where);
        }
    }

    private static boolean uniqueCurrency(@NonNull String code) {
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void maxAmount(@NonNull String currencyCode, long amountMinor,
                                  @NonNull String where) throws BackupFormatException {
        int digits = CurrencyFormatter.fractionDigits(currencyCode);
        if (amountMinor > AmountParser.maxMinorUnits(digits)) {
            throw new BackupFormatException(BackupFormatException.Reason.INVALID_RECORD, where);
        }
    }

    private static void requireTime(long millis, @NonNull String where) throws BackupFormatException {
        require(millis > 0, where, "timestamp");
    }

    private static void require(boolean condition, @NonNull String where, @NonNull String field)
            throws BackupFormatException {
        if (!condition) {
            throw new BackupFormatException(BackupFormatException.Reason.INVALID_RECORD,
                    where + " " + field);
        }
    }

    private interface Checked {
        void run() throws Exception;
    }

    private static void rethrowAsInvalid(@NonNull String where, @NonNull Checked action)
            throws BackupFormatException {
        try {
            action.run();
        } catch (Exception e) {
            throw new BackupFormatException(BackupFormatException.Reason.INVALID_RECORD, where, e);
        }
    }
}
