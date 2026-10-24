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
import com.litemobiletools.dailyexpensetracker.model.BudgetPeriod;
import com.litemobiletools.dailyexpensetracker.model.RecurrenceFrequency;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the checksum-verified data bytes into {@link BackupData} rows using the existing entity
 * classes. This step is structural only: it rejects rows that are not the expected types and
 * checks the header's record counts; value and reference checks live in {@link BackupValidator}.
 * Any nonconformity anywhere rejects the whole file — records are never silently skipped.
 */
public final class BackupParser {

    private BackupParser() {
    }

    @NonNull
    public static BackupData parse(@NonNull BackupFileReader.BackupFile file)
            throws BackupFormatException {
        BackupHeadMeta head = file.head;
        String dataText = new String(file.dataBytes, java.nio.charset.StandardCharsets.UTF_8);
        JSONObject data;
        try {
            data = new JSONObject(dataText);
        } catch (JSONException e) {
            throw new BackupFormatException(BackupFormatException.Reason.MALFORMED_DATA, null, e);
        }

        List<CategoryEntity> categories = parseCategories(data);
        List<PaymentMethodEntity> paymentMethods = parsePaymentMethods(data);
        List<TransactionEntity> transactions = parseTransactions(data);
        List<BudgetEntity> budgets = parseBudgets(data);
        List<SavingsGoalEntity> savingsGoals = parseSavingsGoals(data);
        List<GoalContributionEntity> contributions = parseGoalContributions(data);
        List<RecurringRuleEntity> rules = parseRecurringRules(data);
        List<RecurringOccurrenceEntity> occurrences = parseRecurringOccurrences(data);

        checkCount(BackupFormat.TABLE_CATEGORIES, head.declaredCount(BackupFormat.TABLE_CATEGORIES),
                categories.size());
        checkCount(BackupFormat.TABLE_PAYMENT_METHODS, head.declaredCount(BackupFormat.TABLE_PAYMENT_METHODS),
                paymentMethods.size());
        checkCount(BackupFormat.TABLE_TRANSACTIONS, head.declaredCount(BackupFormat.TABLE_TRANSACTIONS),
                transactions.size());
        checkCount(BackupFormat.TABLE_BUDGETS, head.declaredCount(BackupFormat.TABLE_BUDGETS),
                budgets.size());
        checkCount(BackupFormat.TABLE_SAVINGS_GOALS, head.declaredCount(BackupFormat.TABLE_SAVINGS_GOALS),
                savingsGoals.size());
        checkCount(BackupFormat.TABLE_GOAL_CONTRIBUTIONS, head.declaredCount(BackupFormat.TABLE_GOAL_CONTRIBUTIONS),
                contributions.size());
        checkCount(BackupFormat.TABLE_RECURRING_RULES, head.declaredCount(BackupFormat.TABLE_RECURRING_RULES),
                rules.size());
        checkCount(BackupFormat.TABLE_RECURRING_OCCURRENCES, head.declaredCount(BackupFormat.TABLE_RECURRING_OCCURRENCES),
                occurrences.size());

        JSONObject preferences = optObject(data, BackupFormat.TABLE_PREFERENCES); // presence checked
        BackupData.Preferences prefs;
        try {
            prefs = new BackupData.Preferences(preferences.getInt("themeMode"),
                    preferences.getString("missedPolicy"));
        } catch (JSONException e) {
            throw new BackupFormatException(BackupFormatException.Reason.MALFORMED_RECORD,
                    BackupFormat.TABLE_PREFERENCES, e);
        }

        BackupData.AppInfo appInfo = new BackupData.AppInfo(head.appId, head.appVersion,
                head.appVersionCode, head.dbSchemaVersion);
        return new BackupData(appInfo, head.createdAtMs, prefs, categories, paymentMethods,
                transactions, budgets, savingsGoals, contributions, rules, occurrences);
    }

    private static void checkCount(@NonNull String table, int declared, int actual)
            throws BackupFormatException {
        if (declared != actual) {
            throw new BackupFormatException(BackupFormatException.Reason.COUNT_MISMATCH,
                    table + " expected " + declared + " found " + actual);
        }
    }

    @NonNull
    private static JSONArray array(@NonNull JSONObject data, @NonNull String table)
            throws BackupFormatException {
        if (!data.has(table)) {
            throw new BackupFormatException(BackupFormatException.Reason.MISSING_TABLE, table);
        }
        try {
            return data.getJSONArray(table);
        } catch (JSONException e) {
            throw new BackupFormatException(BackupFormatException.Reason.MALFORMED_DATA, table, e);
        }
    }

    @NonNull
    private static List<CategoryEntity> parseCategories(@NonNull JSONObject data)
            throws BackupFormatException {
        JSONArray rows = array(data, BackupFormat.TABLE_CATEGORIES);
        List<CategoryEntity> out = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            try {
                JSONObject row = rows.getJSONObject(i);
                CategoryEntity c = new CategoryEntity();
                c.id = row.getLong("id");
                c.name = row.getString("name");
                c.type = TransactionType.valueOf(row.getString("type"));
                c.iconKey = row.getString("iconKey");
                c.colorArgb = row.isNull("colorArgb") ? null : (int) row.getLong("colorArgb");
                c.isActive = row.getBoolean("isActive");
                c.defaultKey = row.isNull("defaultKey") ? null : row.getString("defaultKey");
                c.sortOrder = row.getInt("sortOrder");
                c.createdAt = row.getLong("createdAt");
                c.updatedAt = row.getLong("updatedAt");
                out.add(c);
            } catch (JSONException e) {
                throw malformed(BackupFormat.TABLE_CATEGORIES, i, e);
            } catch (IllegalArgumentException e) {
                throw invalid(BackupFormat.TABLE_CATEGORIES, i, e);
            }
        }
        return out;
    }

    @NonNull
    private static List<PaymentMethodEntity> parsePaymentMethods(@NonNull JSONObject data)
            throws BackupFormatException {
        JSONArray rows = array(data, BackupFormat.TABLE_PAYMENT_METHODS);
        List<PaymentMethodEntity> out = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            try {
                JSONObject row = rows.getJSONObject(i);
                PaymentMethodEntity p = new PaymentMethodEntity();
                p.id = row.getLong("id");
                p.name = row.getString("name");
                p.iconKey = row.getString("iconKey");
                p.isActive = row.getBoolean("isActive");
                p.defaultKey = row.isNull("defaultKey") ? null : row.getString("defaultKey");
                p.sortOrder = row.getInt("sortOrder");
                out.add(p);
            } catch (JSONException e) {
                throw malformed(BackupFormat.TABLE_PAYMENT_METHODS, i, e);
            }
        }
        return out;
    }

    @NonNull
    private static List<TransactionEntity> parseTransactions(@NonNull JSONObject data)
            throws BackupFormatException {
        JSONArray rows = array(data, BackupFormat.TABLE_TRANSACTIONS);
        List<TransactionEntity> out = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            try {
                JSONObject row = rows.getJSONObject(i);
                TransactionEntity t = new TransactionEntity();
                t.id = row.getLong("id");
                t.type = TransactionType.valueOf(row.getString("type"));
                t.amountMinor = row.getLong("amountMinor");
                t.currencyCode = row.getString("currencyCode");
                t.categoryId = row.getLong("categoryId");
                t.paymentMethodId = lngOrNull(row, "paymentMethodId");
                t.transactionTimestamp = row.getLong("transactionTimestamp");
                t.note = strOrNull(row, "note");
                t.createdAt = row.getLong("createdAt");
                t.updatedAt = row.getLong("updatedAt");
                out.add(t);
            } catch (JSONException e) {
                throw malformed(BackupFormat.TABLE_TRANSACTIONS, i, e);
            } catch (IllegalArgumentException e) {
                throw invalid(BackupFormat.TABLE_TRANSACTIONS, i, e);
            }
        }
        return out;
    }

    @NonNull
    private static List<BudgetEntity> parseBudgets(@NonNull JSONObject data)
            throws BackupFormatException {
        JSONArray rows = array(data, BackupFormat.TABLE_BUDGETS);
        List<BudgetEntity> out = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            try {
                JSONObject row = rows.getJSONObject(i);
                BudgetEntity b = new BudgetEntity();
                b.id = row.getLong("id");
                b.period = BudgetPeriod.valueOf(row.getString("period"));
                b.categoryId = lngOrNull(row, "categoryId");
                b.currencyCode = row.getString("currencyCode");
                b.amountMinor = row.getLong("amountMinor");
                b.createdAt = row.getLong("createdAt");
                b.updatedAt = row.getLong("updatedAt");
                out.add(b);
            } catch (JSONException e) {
                throw malformed(BackupFormat.TABLE_BUDGETS, i, e);
            } catch (IllegalArgumentException e) {
                throw invalid(BackupFormat.TABLE_BUDGETS, i, e);
            }
        }
        return out;
    }

    @NonNull
    private static List<SavingsGoalEntity> parseSavingsGoals(@NonNull JSONObject data)
            throws BackupFormatException {
        JSONArray rows = array(data, BackupFormat.TABLE_SAVINGS_GOALS);
        List<SavingsGoalEntity> out = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            try {
                JSONObject row = rows.getJSONObject(i);
                SavingsGoalEntity g = new SavingsGoalEntity();
                g.id = row.getLong("id");
                g.name = row.getString("name");
                g.targetMinor = row.getLong("targetMinor");
                g.currencyCode = row.getString("currencyCode");
                g.targetDate = lngOrNull(row, "targetDate");
                g.isCompleted = row.getBoolean("isCompleted");
                g.completedAt = lngOrNull(row, "completedAt");
                g.createdAt = row.getLong("createdAt");
                g.updatedAt = row.getLong("updatedAt");
                out.add(g);
            } catch (JSONException e) {
                throw malformed(BackupFormat.TABLE_SAVINGS_GOALS, i, e);
            }
        }
        return out;
    }

    @NonNull
    private static List<GoalContributionEntity> parseGoalContributions(@NonNull JSONObject data)
            throws BackupFormatException {
        JSONArray rows = array(data, BackupFormat.TABLE_GOAL_CONTRIBUTIONS);
        List<GoalContributionEntity> out = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            try {
                JSONObject row = rows.getJSONObject(i);
                GoalContributionEntity c = new GoalContributionEntity();
                c.id = row.getLong("id");
                c.goalId = row.getLong("goalId");
                c.deltaMinor = row.getLong("deltaMinor");
                c.note = strOrNull(row, "note");
                c.contributedAt = row.getLong("contributedAt");
                out.add(c);
            } catch (JSONException e) {
                throw malformed(BackupFormat.TABLE_GOAL_CONTRIBUTIONS, i, e);
            }
        }
        return out;
    }

    @NonNull
    private static List<RecurringRuleEntity> parseRecurringRules(@NonNull JSONObject data)
            throws BackupFormatException {
        JSONArray rows = array(data, BackupFormat.TABLE_RECURRING_RULES);
        List<RecurringRuleEntity> out = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            try {
                JSONObject row = rows.getJSONObject(i);
                RecurringRuleEntity r = new RecurringRuleEntity();
                r.id = row.getLong("id");
                r.type = TransactionType.valueOf(row.getString("type"));
                r.amountMinor = row.getLong("amountMinor");
                r.currencyCode = row.getString("currencyCode");
                r.categoryId = row.getLong("categoryId");
                r.paymentMethodId = lngOrNull(row, "paymentMethodId");
                r.note = strOrNull(row, "note");
                r.frequency = RecurrenceFrequency.valueOf(row.getString("frequency"));
                r.startDate = row.getLong("startDate");
                r.endDate = lngOrNull(row, "endDate");
                r.nextIndex = row.getLong("nextIndex");
                r.isActive = row.getBoolean("isActive");
                r.skippedCount = row.getInt("skippedCount");
                r.createdAt = row.getLong("createdAt");
                r.updatedAt = row.getLong("updatedAt");
                out.add(r);
            } catch (JSONException e) {
                throw malformed(BackupFormat.TABLE_RECURRING_RULES, i, e);
            } catch (IllegalArgumentException e) {
                throw invalid(BackupFormat.TABLE_RECURRING_RULES, i, e);
            }
        }
        return out;
    }

    @NonNull
    private static List<RecurringOccurrenceEntity> parseRecurringOccurrences(@NonNull JSONObject data)
            throws BackupFormatException {
        JSONArray rows = array(data, BackupFormat.TABLE_RECURRING_OCCURRENCES);
        List<RecurringOccurrenceEntity> out = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            try {
                JSONObject row = rows.getJSONObject(i);
                RecurringOccurrenceEntity o = new RecurringOccurrenceEntity();
                o.id = row.getLong("id");
                o.ruleId = row.getLong("ruleId");
                o.occurrenceDate = row.getLong("occurrenceDate");
                o.transactionId = lngOrNull(row, "transactionId");
                o.createdAt = row.getLong("createdAt");
                out.add(o);
            } catch (JSONException e) {
                throw malformed(BackupFormat.TABLE_RECURRING_OCCURRENCES, i, e);
            }
        }
        return out;
    }

    @NonNull
    private static BackupFormatException malformed(@NonNull String table, int index,
                                                   @NonNull Throwable cause) {
        return new BackupFormatException(BackupFormatException.Reason.MALFORMED_RECORD,
                table + "[" + index + "]", cause);
    }

    @NonNull
    private static BackupFormatException invalid(@NonNull String table, int index,
                                                 @NonNull Throwable cause) {
        return new BackupFormatException(BackupFormatException.Reason.INVALID_RECORD,
                table + "[" + index + "]", cause);
    }

    @Nullable
    private static Long lngOrNull(@NonNull JSONObject row, @NonNull String key) throws JSONException {
        return row.isNull(key) ? null : row.getLong(key);
    }

    @Nullable
    private static String strOrNull(@NonNull JSONObject row, @NonNull String key) throws JSONException {
        return row.isNull(key) ? null : row.getString(key);
    }

    @NonNull
    private static JSONObject optObject(@NonNull JSONObject data, @NonNull String table)
            throws BackupFormatException {
        try {
            return data.getJSONObject(table);
        } catch (JSONException e) {
            throw new BackupFormatException(BackupFormatException.Reason.MALFORMED_DATA, table, e);
        }
    }
}
