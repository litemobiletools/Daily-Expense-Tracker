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
import com.litemobiletools.dailyexpensetracker.util.JsonWriter;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Serializes a {@link BackupData} into the file format:
 * <pre>
 * {"format":"det-backup",…,"integrity":{"algorithm":"SHA-256","dataSha256":"…"}}
 * {… full data JSON, one compact line …}
 * </pre>
 * The checksum covers the exact bytes written after the header separator, so verification compares
 * those bytes directly and never re-serializes them. Output is deterministic (fixed field order;
 * rows are understood to be sorted by id by the collector) and uses long values only, so no
 * floating point ever touches money or timestamps.
 */
public final class BackupWriter {

    private BackupWriter() {
    }

    /** The full file: single-line header, header separator byte, then the data JSON. */
    @NonNull
    public static String write(@NonNull BackupData data) {
        byte[] dataBytes = dataJson(data).getBytes(StandardCharsets.UTF_8);
        return headerLine(data, BackupIntegrity.sha256Hex(dataBytes))
                + (char) BackupFormat.HEADER_SEPARATOR
                + new String(dataBytes, StandardCharsets.UTF_8);
    }

    /** The header as one compact JSON line (checksum is passed in, computed over the data bytes). */
    @NonNull
    public static String headerLine(@NonNull BackupData data, @NonNull String dataSha256Hex) {
        JsonWriter w = new JsonWriter().beginObject();
        w.name("format").value(BackupFormat.FORMAT_ID);
        w.name("formatVersion").value(BackupFormat.FORMAT_VERSION);
        w.name("createdAtMs").value(data.createdAtMs);
        w.name("metadata").beginObject()
                .name("appId").value(data.appInfo.appId)
                .name("appVersion").value(data.appInfo.appVersion)
                .name("appVersionCode").value(data.appInfo.appVersionCode)
                .name("dbSchemaVersion").value(data.appInfo.dbSchemaVersion)
                .endObject();
        w.name("recordCounts").beginObject();
        w.name(BackupFormat.TABLE_CATEGORIES).value(count(data.categories));
        w.name(BackupFormat.TABLE_PAYMENT_METHODS).value(count(data.paymentMethods));
        w.name(BackupFormat.TABLE_TRANSACTIONS).value(count(data.transactions));
        w.name(BackupFormat.TABLE_BUDGETS).value(count(data.budgets));
        w.name(BackupFormat.TABLE_SAVINGS_GOALS).value(count(data.savingsGoals));
        w.name(BackupFormat.TABLE_GOAL_CONTRIBUTIONS).value(count(data.goalContributions));
        w.name(BackupFormat.TABLE_RECURRING_RULES).value(count(data.recurringRules));
        w.name(BackupFormat.TABLE_RECURRING_OCCURRENCES).value(count(data.recurringOccurrences));
        // Preferences are one required data object, always present and never inline-counted above.
        w.name(BackupFormat.TABLE_PREFERENCES).value(1);
        w.endObject();
        w.name("integrity").beginObject()
                .name("algorithm").value(BackupFormat.HASH_ALGORITHM)
                .name("dataSha256").value(dataSha256Hex)
                .endObject().endObject();
        return w.toString();
    }

    /** The complete data section: every persistent table (fixed order) plus the preferences. */
    @NonNull
    public static String dataJson(@NonNull BackupData data) {
        JsonWriter w = new JsonWriter().beginObject();
        w.name(BackupFormat.TABLE_CATEGORIES).beginArray();
        for (CategoryEntity c : data.categories) {
            w.beginObject()
                    .name("id").value(c.id)
                    .name("name").value(c.name)
                    .name("type").value(c.type.name())
                    .name("iconKey").value(c.iconKey)
                    .name("colorArgb").valueNullable(c.colorArgb == null
                            ? null : (long) c.colorArgb & 0xFFFFFFFFL)
                    .name("isActive").value(c.isActive)
                    .name("defaultKey").valueNullable(c.defaultKey)
                    .name("sortOrder").value(c.sortOrder)
                    .name("createdAt").value(c.createdAt)
                    .name("updatedAt").value(c.updatedAt)
                    .endObject();
        }
        w.endArray();
        w.name(BackupFormat.TABLE_PAYMENT_METHODS).beginArray();
        for (PaymentMethodEntity p : data.paymentMethods) {
            w.beginObject()
                    .name("id").value(p.id)
                    .name("name").value(p.name)
                    .name("iconKey").value(p.iconKey)
                    .name("isActive").value(p.isActive)
                    .name("defaultKey").valueNullable(p.defaultKey)
                    .name("sortOrder").value(p.sortOrder)
                    .endObject();
        }
        w.endArray();
        w.name(BackupFormat.TABLE_TRANSACTIONS).beginArray();
        for (TransactionEntity t : data.transactions) {
            w.beginObject()
                    .name("id").value(t.id)
                    .name("type").value(t.type.name())
                    .name("amountMinor").value(t.amountMinor)
                    .name("currencyCode").value(t.currencyCode)
                    .name("categoryId").value(t.categoryId)
                    .name("paymentMethodId").valueNullable(t.paymentMethodId)
                    .name("transactionTimestamp").value(t.transactionTimestamp)
                    .name("note").valueNullable(t.note)
                    .name("createdAt").value(t.createdAt)
                    .name("updatedAt").value(t.updatedAt)
                    .endObject();
        }
        w.endArray();
        w.name(BackupFormat.TABLE_BUDGETS).beginArray();
        for (BudgetEntity b : data.budgets) {
            w.beginObject()
                    .name("id").value(b.id)
                    .name("period").value(b.period.name())
                    .name("categoryId").valueNullable(b.categoryId)
                    .name("currencyCode").value(b.currencyCode)
                    .name("amountMinor").value(b.amountMinor)
                    .name("createdAt").value(b.createdAt)
                    .name("updatedAt").value(b.updatedAt)
                    .endObject();
        }
        w.endArray();
        w.name(BackupFormat.TABLE_SAVINGS_GOALS).beginArray();
        for (SavingsGoalEntity g : data.savingsGoals) {
            w.beginObject()
                    .name("id").value(g.id)
                    .name("name").value(g.name)
                    .name("targetMinor").value(g.targetMinor)
                    .name("currencyCode").value(g.currencyCode)
                    .name("targetDate").valueNullable(g.targetDate)
                    .name("isCompleted").value(g.isCompleted)
                    .name("completedAt").valueNullable(g.completedAt)
                    .name("createdAt").value(g.createdAt)
                    .name("updatedAt").value(g.updatedAt)
                    .endObject();
        }
        w.endArray();
        w.name(BackupFormat.TABLE_GOAL_CONTRIBUTIONS).beginArray();
        for (GoalContributionEntity c : data.goalContributions) {
            w.beginObject()
                    .name("id").value(c.id)
                    .name("goalId").value(c.goalId)
                    .name("deltaMinor").value(c.deltaMinor)
                    .name("note").valueNullable(c.note)
                    .name("contributedAt").value(c.contributedAt)
                    .endObject();
        }
        w.endArray();
        w.name(BackupFormat.TABLE_RECURRING_RULES).beginArray();
        for (RecurringRuleEntity r : data.recurringRules) {
            w.beginObject()
                    .name("id").value(r.id)
                    .name("type").value(r.type.name())
                    .name("amountMinor").value(r.amountMinor)
                    .name("currencyCode").value(r.currencyCode)
                    .name("categoryId").value(r.categoryId)
                    .name("paymentMethodId").valueNullable(r.paymentMethodId)
                    .name("note").valueNullable(r.note)
                    .name("frequency").value(r.frequency.name())
                    .name("startDate").value(r.startDate)
                    .name("endDate").valueNullable(r.endDate)
                    .name("nextIndex").value(r.nextIndex)
                    .name("isActive").value(r.isActive)
                    .name("skippedCount").value(r.skippedCount)
                    .name("createdAt").value(r.createdAt)
                    .name("updatedAt").value(r.updatedAt)
                    .endObject();
        }
        w.endArray();
        w.name(BackupFormat.TABLE_RECURRING_OCCURRENCES).beginArray();
        for (RecurringOccurrenceEntity o : data.recurringOccurrences) {
            w.beginObject()
                    .name("id").value(o.id)
                    .name("ruleId").value(o.ruleId)
                    .name("occurrenceDate").value(o.occurrenceDate)
                    .name("transactionId").valueNullable(o.transactionId)
                    .name("createdAt").value(o.createdAt)
                    .endObject();
        }
        w.endArray();
        w.name(BackupFormat.TABLE_PREFERENCES).beginObject()
                .name("themeMode").value(data.preferences.themeMode)
                .name("missedPolicy").value(data.preferences.missedPolicy)
                .endObject();
        w.endObject();
        return w.toString();
    }

    private static int count(@NonNull List<?> rows) {
        return rows.size();
    }
}
