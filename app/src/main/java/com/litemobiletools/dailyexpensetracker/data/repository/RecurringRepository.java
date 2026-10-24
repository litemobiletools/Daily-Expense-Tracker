package com.litemobiletools.dailyexpensetracker.data.repository;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.litemobiletools.dailyexpensetracker.data.AppDatabase;
import com.litemobiletools.dailyexpensetracker.data.DatabaseExecutor;
import com.litemobiletools.dailyexpensetracker.data.RepoCallback;
import com.litemobiletools.dailyexpensetracker.data.TransactionInput;
import com.litemobiletools.dailyexpensetracker.data.TransactionValidator;
import com.litemobiletools.dailyexpensetracker.data.ValidationException;
import com.litemobiletools.dailyexpensetracker.data.ValidationException.Reason;
import com.litemobiletools.dailyexpensetracker.data.entity.CategoryEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.RecurringOccurrenceEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.RecurringRuleEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionEntity;
import com.litemobiletools.dailyexpensetracker.model.MissedPolicy;
import com.litemobiletools.dailyexpensetracker.model.RecurrenceFrequency;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;
import com.litemobiletools.dailyexpensetracker.settings.AppPreferences;
import com.litemobiletools.dailyexpensetracker.util.DateRanges;
import com.litemobiletools.dailyexpensetracker.util.Recurrence;

import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * Recurring transactions. Rules live apart from real transactions and only ever create records in
 * this app; no money moves.
 *
 * <p><b>No duplicates, by construction.</b> Each rule keeps {@code next_index}, the first occurrence
 * not yet handled. Reconciliation records occurrences strictly between that cursor and today and then
 * advances it inside the same database transaction, so running it again (refresh, app restart,
 * two screens) finds nothing new. As a second, independent guard, every recorded occurrence also inserts
 * a row into {@code recurring_occurrences} with a unique (rule_id, occurrence_date) index; if that insert
 * finds an existing row, no transaction is created. Deleting a generated transaction keeps the occurrence
 * row, so it is never recorded again.
 *
 * <p><b>No surprise backlog.</b> After a long absence {@link MissedPolicy#CATCH_UP} records at most
 * {@link #MAX_CATCH_UP} of the most recent missed occurrences per rule (older ones are skipped and
 * counted); {@link MissedPolicy#SKIP_MISSED} records only an occurrence due today. A rule created with a
 * start date in the past starts at its next occurrence from today, and resuming a paused rule skips what came
 * due during the pause.
 */
public class RecurringRepository {

    public static final int MAX_CATCH_UP = 12;
    private static final int NOON_HOUR = 12;

    /** Outcome of one reconciliation pass. */
    public static final class ReconcileResult {
        public final int recorded;
        public final int skipped;

        public ReconcileResult(int recorded, int skipped) {
            this.recorded = recorded;
            this.skipped = skipped;
        }
    }

    /** User-entered rule values. Dates are the first moment of a local day. */
    public static final class RuleInput {
        @NonNull public final TransactionType type;
        public final long amountMinor;
        @NonNull public final String currencyCode;
        public final long categoryId;
        @Nullable public final Long paymentMethodId;
        @Nullable public final String note;
        @NonNull public final RecurrenceFrequency frequency;
        public final long startDate;
        @Nullable public final Long endDate;

        public RuleInput(@NonNull TransactionType type, long amountMinor, @NonNull String currencyCode,
                         long categoryId, @Nullable Long paymentMethodId, @Nullable String note,
                         @NonNull RecurrenceFrequency frequency, long startDate, @Nullable Long endDate) {
            this.type = type;
            this.amountMinor = amountMinor;
            this.currencyCode = currencyCode;
            this.categoryId = categoryId;
            this.paymentMethodId = paymentMethodId;
            this.note = note;
            this.frequency = frequency;
            this.startDate = startDate;
            this.endDate = endDate;
        }
    }

    private final AppDatabase db;
    private final Context appContext;

    public RecurringRepository(@NonNull AppDatabase db, @Nullable Context context) {
        this.db = db;
        this.appContext = context == null ? null : context.getApplicationContext();
    }

    public static RecurringRepository get(@NonNull Context context) {
        return new RecurringRepository(AppDatabase.getInstance(context), context);
    }

    // ---- Async API -------------------------------------------------------------------

    /** Brings every active rule up to date. Safe to call as often as you like. */
    public void reconcile(@NonNull RepoCallback<ReconcileResult> callback) {
        final MissedPolicy policy = appContext == null ? MissedPolicy.CATCH_UP
                : AppPreferences.getMissedPolicy(appContext);
        DatabaseExecutor.run(() -> reconcileSync(System.currentTimeMillis(), TimeZone.getDefault(), policy), callback);
    }

    public void list(@NonNull RepoCallback<List<RecurringRuleEntity>> callback) {
        DatabaseExecutor.run(() -> db.recurringDao().listRules(), callback);
    }

    /** Rules with their category's display data, active ones first. */
    public void listRows(@NonNull RepoCallback<List<com.litemobiletools.dailyexpensetracker.data.entity.RecurringRuleRow>> callback) {
        DatabaseExecutor.run(() -> db.recurringDao().listRuleRows(), callback);
    }

    public void get(long id, @NonNull RepoCallback<RecurringRuleEntity> callback) {
        DatabaseExecutor.run(() -> db.recurringDao().getRule(id), callback);
    }

    public void occurrences(long ruleId, int limit, @NonNull RepoCallback<List<RecurringOccurrenceEntity>> callback) {
        DatabaseExecutor.run(() -> db.recurringDao().occurrences(ruleId, limit), callback);
    }

    /** Creates a rule and immediately records an occurrence that is due today. */
    public void create(@NonNull RuleInput input, @NonNull RepoCallback<Long> callback) {
        final MissedPolicy policy = appContext == null ? MissedPolicy.CATCH_UP
                : AppPreferences.getMissedPolicy(appContext);
        DatabaseExecutor.run(() -> {
            long now = System.currentTimeMillis();
            TimeZone zone = TimeZone.getDefault();
            long id = createSync(input, now, zone);
            reconcileSync(now, zone, policy);
            return id;
        }, callback);
    }

    public void update(long id, @NonNull RuleInput input, @NonNull RepoCallback<Boolean> callback) {
        final MissedPolicy policy = appContext == null ? MissedPolicy.CATCH_UP
                : AppPreferences.getMissedPolicy(appContext);
        DatabaseExecutor.run(() -> {
            long now = System.currentTimeMillis();
            TimeZone zone = TimeZone.getDefault();
            updateSync(id, input, now, zone);
            reconcileSync(now, zone, policy);
            return Boolean.TRUE;
        }, callback);
    }

    public void setActive(long id, boolean active, @NonNull RepoCallback<Boolean> callback) {
        final MissedPolicy policy = appContext == null ? MissedPolicy.CATCH_UP
                : AppPreferences.getMissedPolicy(appContext);
        DatabaseExecutor.run(() -> {
            long now = System.currentTimeMillis();
            TimeZone zone = TimeZone.getDefault();
            setActiveSync(id, active, now, zone);
            reconcileSync(now, zone, policy);
            return Boolean.TRUE;
        }, callback);
    }

    /** Deletes the rule. Transactions it already created stay in the history. */
    public void delete(long id, @NonNull RepoCallback<Boolean> callback) {
        DatabaseExecutor.run(() -> db.recurringDao().deleteRule(id) > 0, callback);
    }

    // ---- Synchronous API (worker thread only) ----------------------------------------

    @WorkerThread
    public long createSync(@NonNull RuleInput input, long nowMillis, @NonNull TimeZone zone) throws Exception {
        validate(input);
        return db.runInTransaction(() -> {
            checkReferences(input);
            long today = DateRanges.startOfDay(nowMillis, zone);
            RecurringRuleEntity rule = new RecurringRuleEntity();
            apply(rule, input);
            rule.nextIndex = firstFutureIndex(input.startDate, input.frequency, today, zone);
            rule.isActive = true;
            rule.createdAt = nowMillis;
            rule.updatedAt = nowMillis;
            return db.recurringDao().insertRule(rule);
        });
    }

    /**
     * Edits a rule. Changing the start date or interval re-anchors the schedule: it continues from the
     * next occurrence from today and never backfills past dates.
     */
    @WorkerThread
    public void updateSync(long id, @NonNull RuleInput input, long nowMillis, @NonNull TimeZone zone) throws Exception {
        validate(input);
        db.runInTransaction(() -> {
            RecurringRuleEntity rule = requireRule(id);
            checkReferences(input);
            boolean restructured = rule.startDate != input.startDate || rule.frequency != input.frequency;
            apply(rule, input);
            if (restructured) {
                long today = DateRanges.startOfDay(nowMillis, zone);
                rule.nextIndex = firstFutureIndex(input.startDate, input.frequency, today, zone);
            }
            rule.updatedAt = nowMillis;
            db.recurringDao().updateRule(rule);
            return null;
        });
    }

    /**
     * Pauses or resumes. Resuming does not record what came due during the pause: the schedule
     * continues at the next occurrence from today, and the passed ones are counted as skipped.
     */
    @WorkerThread
    public void setActiveSync(long id, boolean active, long nowMillis, @NonNull TimeZone zone) throws Exception {
        db.runInTransaction(() -> {
            RecurringRuleEntity rule = requireRule(id);
            if (active && !rule.isActive) {
                long today = DateRanges.startOfDay(nowMillis, zone);
                long resumeAt = Math.max(rule.nextIndex,
                        firstFutureIndex(rule.startDate, rule.frequency, today, zone));
                rule.skippedCount += (int) Math.min(Integer.MAX_VALUE - rule.skippedCount, resumeAt - rule.nextIndex);
                rule.nextIndex = resumeAt;
            }
            rule.isActive = active;
            rule.updatedAt = nowMillis;
            db.recurringDao().updateRule(rule);
            return null;
        });
    }

    @NonNull
    @WorkerThread
    public ReconcileResult reconcileSync(long nowMillis, @NonNull TimeZone zone, @NonNull MissedPolicy policy) {
        int recorded = 0;
        int skipped = 0;
        long today = DateRanges.startOfDay(nowMillis, zone);
        for (RecurringRuleEntity listed : db.recurringDao().listActiveRules()) {
            final long ruleId = listed.id;
            int[] counts = db.runInTransaction(() -> reconcileRule(ruleId, today, nowMillis, zone, policy));
            recorded += counts[0];
            skipped += counts[1];
        }
        return new ReconcileResult(recorded, skipped);
    }

    /** Date of the next occurrence, or null if the rule has ended. */
    @Nullable
    public static Long nextDue(@NonNull RecurringRuleEntity rule, @NonNull TimeZone zone) {
        long date = Recurrence.occurrenceDate(rule.startDate, rule.frequency, rule.nextIndex, zone);
        if (rule.endDate != null && date > rule.endDate) {
            return null;
        }
        return date;
    }

    // ---- Internals -------------------------------------------------------------------

    private int[] reconcileRule(long ruleId, long today, long nowMillis, TimeZone zone, MissedPolicy policy) {
        RecurringRuleEntity rule = db.recurringDao().getRule(ruleId);
        if (rule == null || !rule.isActive) {
            return new int[]{0, 0};
        }
        long cap = rule.endDate != null ? Math.min(today, rule.endDate) : today;
        long last = Recurrence.lastIndexOnOrBefore(rule.startDate, rule.frequency, cap, zone);
        if (last < rule.nextIndex) {
            return new int[]{0, 0};
        }
        long firstToRecord;
        if (policy == MissedPolicy.SKIP_MISSED) {
            boolean dueToday = Recurrence.occurrenceDate(rule.startDate, rule.frequency, last, zone) == today;
            firstToRecord = dueToday ? last : last + 1;
        } else {
            firstToRecord = Math.max(rule.nextIndex, last - MAX_CATCH_UP + 1);
        }
        int recorded = 0;
        for (long index = firstToRecord; index <= last; index++) {
            long date = Recurrence.occurrenceDate(rule.startDate, rule.frequency, index, zone);
            if (recordOccurrence(rule, date, nowMillis, zone)) {
                recorded++;
            }
        }
        int skipped = (int) (firstToRecord - rule.nextIndex);
        rule.skippedCount += skipped;
        rule.nextIndex = last + 1;
        rule.updatedAt = nowMillis;
        db.recurringDao().updateRule(rule);
        return new int[]{recorded, skipped};
    }

    /** @return true if a transaction was created, false if this occurrence had already been recorded */
    private boolean recordOccurrence(RecurringRuleEntity rule, long occurrenceDate, long nowMillis, TimeZone zone) {
        RecurringOccurrenceEntity occurrence = new RecurringOccurrenceEntity();
        occurrence.ruleId = rule.id;
        occurrence.occurrenceDate = occurrenceDate;
        occurrence.createdAt = nowMillis;
        long occurrenceId = db.recurringDao().insertOccurrenceIgnore(occurrence);
        if (occurrenceId == -1) {
            return false; // unique (rule, date) already present: never record twice
        }
        CategoryEntity category = db.categoryDao().getById(rule.categoryId);
        if (category == null || category.type != rule.type) {
            return false; // cannot happen with the foreign keys; never write a mismatched record
        }
        TransactionEntity transaction = new TransactionEntity();
        transaction.type = rule.type;
        transaction.amountMinor = rule.amountMinor;
        transaction.currencyCode = rule.currencyCode;
        transaction.categoryId = rule.categoryId;
        transaction.paymentMethodId = rule.paymentMethodId;
        transaction.transactionTimestamp = noonOf(occurrenceDate, zone);
        transaction.note = rule.note;
        transaction.createdAt = nowMillis;
        transaction.updatedAt = nowMillis;
        long transactionId = db.transactionDao().insert(transaction);
        db.recurringDao().linkTransaction(occurrenceId, transactionId);
        return true;
    }

    /** Recorded entries sit at 12:00 local time of their scheduled day, clear of any midnight/DST edge. */
    private static long noonOf(long dayStart, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(dayStart);
        c.set(Calendar.HOUR_OF_DAY, NOON_HOUR);
        return c.getTimeInMillis();
    }

    /** Index 0 if the schedule starts today or later, otherwise the first occurrence from today on. */
    private static long firstFutureIndex(long startDate, RecurrenceFrequency frequency, long today, TimeZone zone) {
        return Recurrence.firstIndexOnOrAfter(startDate, frequency, today, zone);
    }

    private static void apply(RecurringRuleEntity rule, RuleInput input) {
        rule.type = input.type;
        rule.amountMinor = input.amountMinor;
        rule.currencyCode = input.currencyCode;
        rule.categoryId = input.categoryId;
        rule.paymentMethodId = input.paymentMethodId;
        rule.note = TransactionValidator.normalizeNote(input.note);
        rule.frequency = input.frequency;
        rule.startDate = input.startDate;
        rule.endDate = input.endDate;
    }

    private static void validate(RuleInput input) throws ValidationException {
        // Reuse the transaction rules for type, amount, currency, date and note.
        TransactionValidator.validate(new TransactionInput(input.type, input.amountMinor, input.currencyCode,
                input.categoryId, input.paymentMethodId, input.startDate > 0 ? input.startDate : 1, input.note));
        if (input.startDate <= 0) {
            throw new ValidationException(Reason.INVALID_DATE);
        }
        if (input.endDate != null && input.endDate < input.startDate) {
            throw new ValidationException(Reason.END_BEFORE_START);
        }
    }

    private void checkReferences(RuleInput input) throws ValidationException {
        CategoryEntity category = db.categoryDao().getById(input.categoryId);
        if (category == null) {
            throw new ValidationException(Reason.CATEGORY_NOT_FOUND);
        }
        if (category.type != input.type) {
            throw new ValidationException(Reason.CATEGORY_TYPE_MISMATCH);
        }
        if (input.paymentMethodId != null && db.paymentMethodDao().getById(input.paymentMethodId) == null) {
            throw new ValidationException(Reason.PAYMENT_METHOD_NOT_FOUND);
        }
    }

    private RecurringRuleEntity requireRule(long id) throws ValidationException {
        RecurringRuleEntity rule = db.recurringDao().getRule(id);
        if (rule == null) {
            throw new ValidationException(Reason.RULE_NOT_FOUND);
        }
        return rule;
    }
}
