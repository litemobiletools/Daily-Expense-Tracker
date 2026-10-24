package com.litemobiletools.dailyexpensetracker.data;

import static com.litemobiletools.dailyexpensetracker.data.TestSupport.USD;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.UTC;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.at;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.categoryId;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.day;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.litemobiletools.dailyexpensetracker.data.entity.RecurringOccurrenceEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.RecurringRuleEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionRow;
import com.litemobiletools.dailyexpensetracker.data.repository.RecurringRepository;
import com.litemobiletools.dailyexpensetracker.data.repository.RecurringRepository.ReconcileResult;
import com.litemobiletools.dailyexpensetracker.data.repository.RecurringRepository.RuleInput;
import com.litemobiletools.dailyexpensetracker.data.repository.TransactionRepository;
import com.litemobiletools.dailyexpensetracker.model.MissedPolicy;
import com.litemobiletools.dailyexpensetracker.model.RecurrenceFrequency;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Recurrence engine tests. "Now" is passed in explicitly (UTC), so each test replays days of
 * app launches deterministically: reconcileSync is the same call the app makes on every foreground.
 */
@RunWith(AndroidJUnit4.class)
public class RecurringRepositoryTest {

    private AppDatabase db;
    private RecurringRepository recurring;

    @Before
    public void setUp() {
        db = TestSupport.newDatabase();
        recurring = new RecurringRepository(db, null);
    }

    @After
    public void tearDown() {
        db.close();
    }

    // ---- helpers ---------------------------------------------------------------------

    private RuleInput rule(RecurrenceFrequency frequency, long start, Long end) {
        return new RuleInput(TransactionType.EXPENSE, 120000, USD,
                categoryId(db, TransactionType.EXPENSE, "Bills"), null, "Rent", frequency, start, end);
    }

    private long create(RecurrenceFrequency frequency, long start, Long end, long now) throws Exception {
        return recurring.createSync(rule(frequency, start, end), now, UTC);
    }

    private ReconcileResult reconcile(long now, MissedPolicy policy) {
        return recurring.reconcileSync(now, UTC, policy);
    }

    private ReconcileResult reconcile(long now) {
        return reconcile(now, MissedPolicy.CATCH_UP);
    }

    private List<Long> recordedDays(long ruleId) {
        List<Long> days = new ArrayList<>();
        for (RecurringOccurrenceEntity o : db.recurringDao().occurrences(ruleId, 1000)) {
            days.add(o.occurrenceDate);
        }
        Collections.sort(days);
        return days;
    }

    private int transactions() {
        return db.transactionDao().count();
    }

    private interface Body {
        void run() throws Exception;
    }

    private void expect(ValidationException.Reason reason, Body body) throws Exception {
        try {
            body.run();
            fail("Expected " + reason);
        } catch (ValidationException e) {
            assertEquals(reason, e.getReason());
        }
    }

    // ---- recording and idempotence ---------------------------------------------------

    @Test
    public void aRuleDueTodayIsRecordedOnceNoMatterHowOftenTheAppRefreshes() throws Exception {
        long now = at(2026, 10, 22, 8, 0);
        long id = create(RecurrenceFrequency.DAILY, day(2026, 10, 22), null, now);

        assertEquals(1, reconcile(now).recorded);
        for (int i = 0; i < 5; i++) {
            ReconcileResult again = reconcile(now + i * 1000L);
            assertEquals(0, again.recorded);
        }
        assertEquals(1, transactions());
        assertEquals(1, db.recurringDao().occurrenceCount(id));
    }

    @Test
    public void recordedTransactionHasTheRulesValuesAtNoonOfTheScheduledDay() throws Exception {
        long now = at(2026, 10, 22, 8, 0);
        RuleInput input = new RuleInput(TransactionType.INCOME, 250000, USD,
                categoryId(db, TransactionType.INCOME, "Salary"), TestSupport.paymentId(db, "Bank Transfer"),
                "  Pay  ", RecurrenceFrequency.MONTHLY, day(2026, 10, 22), null);
        recurring.createSync(input, now, UTC);
        reconcile(now);

        TransactionRow row = new TransactionRepository(db).getRecentSync(1).get(0);
        assertEquals(TransactionType.INCOME, row.type);
        assertEquals(250000, row.amountMinor);
        assertEquals("Salary", row.categoryName);
        assertEquals("Bank Transfer", row.paymentMethodName);
        assertEquals("Pay", row.note);
        assertEquals(at(2026, 10, 22, 12, 0), row.transactionTimestamp);
    }

    @Test
    public void aRuleStartingInTheFutureRecordsNothingUntilItIsDue() throws Exception {
        long now = at(2026, 10, 22, 8, 0);
        create(RecurrenceFrequency.DAILY, day(2026, 10, 25), null, now);
        assertEquals(0, reconcile(at(2026, 10, 24, 23, 59)).recorded);
        assertEquals(0, transactions());
        assertEquals(1, reconcile(at(2026, 10, 25, 0, 0)).recorded);
        assertEquals(1, transactions());
    }

    @Test
    public void dailyRulesRecordOneEntryPerDayAcrossManyLaunches() throws Exception {
        long id = create(RecurrenceFrequency.DAILY, day(2026, 10, 30), null, at(2026, 10, 30, 8, 0));
        for (int d = 0; d < 5; d++) {
            long launch = at(2026, 10, 30, 8, 0) + d * 24L * 3600_000L;
            reconcile(launch);
            reconcile(launch + 3600_000L);
        }
        assertEquals(5, transactions());
        assertEquals(java.util.Arrays.asList(day(2026, 10, 30), day(2026, 10, 31), day(2026, 11, 1),
                day(2026, 11, 2), day(2026, 11, 3)), recordedDays(id));
    }

    @Test
    public void weeklyMonthlyAndYearlyRulesFollowTheirIntervals() throws Exception {
        long start = at(2026, 1, 31, 8, 0);
        long weekly = create(RecurrenceFrequency.WEEKLY, day(2026, 1, 31), null, start);
        long monthly = create(RecurrenceFrequency.MONTHLY, day(2026, 1, 31), null, start);
        long yearly = create(RecurrenceFrequency.YEARLY, day(2026, 1, 31), null, start);

        reconcile(start);
        reconcile(at(2026, 2, 7, 8, 0));
        reconcile(at(2026, 2, 28, 8, 0));
        reconcile(at(2026, 3, 31, 8, 0));
        reconcile(at(2027, 1, 31, 8, 0));

        // Weekly: every 7 days up to the 2027-01-31 launch, but catch-up caps the last long gap at 12 entries.
        List<Long> weeklyDays = recordedDays(weekly);
        assertEquals(day(2026, 1, 31), (long) weeklyDays.get(0));
        assertEquals(day(2026, 2, 7), (long) weeklyDays.get(1));
        // Monthly on the 31st: Jan 31, Feb 28 (short month), Mar 31 ... then the later launch catches up.
        List<Long> monthlyDays = recordedDays(monthly);
        assertTrue(monthlyDays.contains(day(2026, 1, 31)));
        assertTrue(monthlyDays.contains(day(2026, 2, 28)));
        assertTrue(monthlyDays.contains(day(2026, 3, 31)));
        assertTrue(monthlyDays.contains(day(2027, 1, 31)));
        assertFalse(monthlyDays.contains(day(2026, 2, 27)));
        // Yearly: Jan 31 2026 and Jan 31 2027.
        assertEquals(java.util.Arrays.asList(day(2026, 1, 31), day(2027, 1, 31)), recordedDays(yearly));
    }

    @Test
    public void monthlyOnThe31stIsRecordedOnTheLastDayOfShortMonths() throws Exception {
        long id = create(RecurrenceFrequency.MONTHLY, day(2026, 1, 31), null, at(2026, 1, 31, 8, 0));
        reconcile(at(2026, 1, 31, 9, 0));
        reconcile(at(2026, 2, 27, 9, 0));   // not yet due
        assertEquals(1, recordedDays(id).size());
        reconcile(at(2026, 2, 28, 9, 0));   // due on the 28th
        reconcile(at(2026, 3, 30, 9, 0));
        reconcile(at(2026, 3, 31, 9, 0));
        reconcile(at(2026, 4, 30, 9, 0));
        assertEquals(java.util.Arrays.asList(day(2026, 1, 31), day(2026, 2, 28), day(2026, 3, 31),
                day(2026, 4, 30)), recordedDays(id));
    }

    @Test
    public void yearlyOnFebruary29UsesFebruary28InNonLeapYears() throws Exception {
        long id = create(RecurrenceFrequency.YEARLY, day(2024, 2, 29), null, at(2024, 2, 29, 8, 0));
        reconcile(at(2024, 2, 29, 9, 0));
        reconcile(at(2025, 2, 27, 9, 0));
        reconcile(at(2025, 2, 28, 9, 0));
        reconcile(at(2026, 2, 28, 9, 0));
        reconcile(at(2027, 2, 28, 9, 0));
        reconcile(at(2028, 2, 28, 9, 0));    // leap year: due on the 29th, not yet
        reconcile(at(2028, 2, 29, 9, 0));
        assertEquals(java.util.Arrays.asList(day(2024, 2, 29), day(2025, 2, 28), day(2026, 2, 28),
                day(2027, 2, 28), day(2028, 2, 29)), recordedDays(id));
    }

    // ---- missed occurrences ----------------------------------------------------------

    @Test
    public void catchUpAfterALongAbsenceIsBoundedAndTheRestIsSkippedAndCounted() throws Exception {
        long start = at(2026, 1, 1, 8, 0);
        long id = create(RecurrenceFrequency.DAILY, day(2026, 1, 1), null, start);
        reconcile(start);                                   // day 0 recorded
        ReconcileResult result = reconcile(start + 40 * 24L * 3600_000L, MissedPolicy.CATCH_UP);

        assertEquals(RecurringRepository.MAX_CATCH_UP, result.recorded);
        assertEquals(40 - RecurringRepository.MAX_CATCH_UP, result.skipped);
        assertEquals(1 + RecurringRepository.MAX_CATCH_UP, transactions());
        RecurringRuleEntity rule = db.recurringDao().getRule(id);
        assertEquals(result.skipped, rule.skippedCount);
        assertEquals(41, rule.nextIndex);
        // The most recent days were the ones recorded.
        List<Long> days = recordedDays(id);
        assertEquals(day(2026, 2, 10), (long) days.get(days.size() - 1));

        // A further launch records nothing.
        assertEquals(0, reconcile(start + 40 * 24L * 3600_000L + 5000).recorded);
        assertEquals(1 + RecurringRepository.MAX_CATCH_UP, transactions());
    }

    @Test
    public void aShortAbsenceIsFullyCaughtUp() throws Exception {
        long start = at(2026, 1, 1, 8, 0);
        long id = create(RecurrenceFrequency.DAILY, day(2026, 1, 1), null, start);
        reconcile(start);
        ReconcileResult result = reconcile(start + 5 * 24L * 3600_000L);
        assertEquals(5, result.recorded);
        assertEquals(0, result.skipped);
        assertEquals(6, recordedDays(id).size());
    }

    @Test
    public void skipMissedPolicyRecordsOnlyWhatIsDueToday() throws Exception {
        long start = at(2026, 1, 1, 8, 0);
        long daily = create(RecurrenceFrequency.DAILY, day(2026, 1, 1), null, start);
        long weekly = create(RecurrenceFrequency.WEEKLY, day(2026, 1, 1), null, start);
        reconcile(start, MissedPolicy.SKIP_MISSED);
        assertEquals(2, transactions());

        // Ten days later: daily is due today (record 1, skip 9); weekly's last occurrence was 3 days ago (skip 1).
        ReconcileResult result = reconcile(at(2026, 1, 11, 8, 0), MissedPolicy.SKIP_MISSED);
        assertEquals(1, result.recorded);
        assertEquals(9 + 1, result.skipped);
        assertEquals(3, transactions());
        assertEquals(9, db.recurringDao().getRule(daily).skippedCount);
        assertEquals(1, db.recurringDao().getRule(weekly).skippedCount);
        assertEquals(java.util.Arrays.asList(day(2026, 1, 1)), recordedDays(weekly));
    }

    // ---- duplicate protection --------------------------------------------------------

    @Test
    public void theUniqueConstraintRefusesASecondRowForTheSameOccurrence() throws Exception {
        long id = create(RecurrenceFrequency.DAILY, day(2026, 10, 22), null, at(2026, 10, 22, 8, 0));
        RecurringOccurrenceEntity occurrence = new RecurringOccurrenceEntity();
        occurrence.ruleId = id;
        occurrence.occurrenceDate = day(2026, 10, 22);
        occurrence.createdAt = 1;
        assertTrue(db.recurringDao().insertOccurrenceIgnore(occurrence) > 0);
        RecurringOccurrenceEntity duplicate = new RecurringOccurrenceEntity();
        duplicate.ruleId = id;
        duplicate.occurrenceDate = day(2026, 10, 22);
        duplicate.createdAt = 2;
        assertEquals(-1L, db.recurringDao().insertOccurrenceIgnore(duplicate));
        assertEquals(1, db.recurringDao().occurrenceExists(id, day(2026, 10, 22)));
    }

    @Test
    public void anOccurrenceAlreadyRecordedIsNeverRecordedAgainEvenIfTheCursorIsReset() throws Exception {
        long now = at(2026, 10, 22, 8, 0);
        long id = create(RecurrenceFrequency.DAILY, day(2026, 10, 22), null, now);
        reconcile(now);
        assertEquals(1, transactions());

        RecurringRuleEntity rule = db.recurringDao().getRule(id);
        rule.nextIndex = 0;                      // simulate a corrupted cursor
        db.recurringDao().updateRule(rule);
        ReconcileResult result = reconcile(now);
        assertEquals(0, result.recorded);        // the unique (rule, date) row stops a second transaction
        assertEquals(1, transactions());
    }

    @Test
    public void deletingARecordedTransactionDoesNotCauseItToBeRecordedAgain() throws Exception {
        long now = at(2026, 10, 22, 8, 0);
        long id = create(RecurrenceFrequency.DAILY, day(2026, 10, 22), null, now);
        reconcile(now);
        TransactionRow row = new TransactionRepository(db).getRecentSync(1).get(0);
        assertTrue(new TransactionRepository(db).deleteSync(row.id));
        assertEquals(0, transactions());

        assertEquals(0, reconcile(now).recorded);
        assertEquals(0, transactions());
        // The occurrence is still known, now without a transaction link.
        RecurringOccurrenceEntity occurrence = db.recurringDao().occurrences(id, 1).get(0);
        assertNull(occurrence.transactionId);
    }

    @Test
    public void concurrentReconciliationsNeverDuplicate() throws Exception {
        long start = at(2026, 10, 1, 8, 0);
        long id = create(RecurrenceFrequency.DAILY, day(2026, 10, 1), null, start);
        long later = at(2026, 10, 10, 8, 0);
        final int threads = 6;
        final CountDownLatch go = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger failures = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    go.await();
                    reconcile(later);
                } catch (Throwable t) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertEquals(0, failures.get());
        assertEquals(10, transactions());                       // 1 Oct .. 10 Oct, once each
        assertEquals(10, db.recurringDao().occurrenceCount(id));
    }

    // ---- pause, resume, end date, edit, delete ---------------------------------------

    @Test
    public void pausedRulesRecordNothingAndResumingDoesNotBackfillThePause() throws Exception {
        long start = at(2026, 10, 1, 8, 0);
        long id = create(RecurrenceFrequency.DAILY, day(2026, 10, 1), null, start);
        reconcile(start);
        assertEquals(1, transactions());

        recurring.setActiveSync(id, false, at(2026, 10, 2, 8, 0), UTC);
        assertEquals(0, reconcile(at(2026, 10, 9, 8, 0)).recorded);
        assertEquals(1, transactions());

        // Resume on Oct 10: occurrences of Oct 2-9 are skipped, Oct 10 is recorded.
        long resume = at(2026, 10, 10, 8, 0);
        recurring.setActiveSync(id, true, resume, UTC);
        assertEquals(1, reconcile(resume).recorded);
        assertEquals(2, transactions());
        RecurringRuleEntity rule = db.recurringDao().getRule(id);
        assertEquals(8, rule.skippedCount);
        assertTrue(rule.isActive);
        assertEquals(java.util.Arrays.asList(day(2026, 10, 1), day(2026, 10, 10)), recordedDays(id));
    }

    @Test
    public void aRuleStopsAtItsEndDateInclusive() throws Exception {
        long start = at(2026, 10, 1, 8, 0);
        long id = create(RecurrenceFrequency.DAILY, day(2026, 10, 1), day(2026, 10, 3), start);
        reconcile(at(2026, 10, 20, 8, 0));
        assertEquals(java.util.Arrays.asList(day(2026, 10, 1), day(2026, 10, 2), day(2026, 10, 3)), recordedDays(id));
        assertNull(RecurringRepository.nextDue(db.recurringDao().getRule(id), UTC));
        assertEquals(0, reconcile(at(2026, 11, 20, 8, 0)).recorded);
        assertEquals(3, transactions());
    }

    @Test
    public void nextDueIsTheNextScheduledDay() throws Exception {
        long id = create(RecurrenceFrequency.WEEKLY, day(2026, 10, 22), null, at(2026, 10, 22, 8, 0));
        assertEquals(Long.valueOf(day(2026, 10, 22)), RecurringRepository.nextDue(db.recurringDao().getRule(id), UTC));
        reconcile(at(2026, 10, 22, 9, 0));
        assertEquals(Long.valueOf(day(2026, 10, 29)), RecurringRepository.nextDue(db.recurringDao().getRule(id), UTC));
    }

    @Test
    public void startingInThePastBeginsAtTheNextOccurrenceFromTodayWithoutBackfill() throws Exception {
        long now = at(2026, 10, 22, 8, 0);
        long id = create(RecurrenceFrequency.MONTHLY, day(2026, 1, 15), null, now);
        assertEquals(0, reconcile(now).recorded);                 // the 15th of earlier months are not filled in
        assertEquals(0, transactions());
        assertEquals(Long.valueOf(day(2026, 11, 15)), RecurringRepository.nextDue(db.recurringDao().getRule(id), UTC));
        assertEquals(1, reconcile(at(2026, 11, 15, 8, 0)).recorded);
    }

    @Test
    public void editingAmountKeepsTheScheduleAndChangingTheIntervalReanchorsWithoutBackfill() throws Exception {
        long now = at(2026, 10, 22, 8, 0);
        long id = create(RecurrenceFrequency.DAILY, day(2026, 10, 22), null, now);
        reconcile(now);
        long cursorBefore = db.recurringDao().getRule(id).nextIndex;

        RuleInput changedAmount = new RuleInput(TransactionType.EXPENSE, 99900, USD,
                categoryId(db, TransactionType.EXPENSE, "Bills"), null, "Rent", RecurrenceFrequency.DAILY,
                day(2026, 10, 22), null);
        recurring.updateSync(id, changedAmount, now, UTC);
        assertEquals(cursorBefore, db.recurringDao().getRule(id).nextIndex);
        assertEquals(99900, db.recurringDao().getRule(id).amountMinor);

        reconcile(at(2026, 10, 23, 8, 0));
        assertEquals(99900, new TransactionRepository(db).getRecentSync(1).get(0).amountMinor);

        // Switch to weekly starting 1 Oct: nothing from before today is created.
        long later = at(2026, 10, 25, 8, 0);
        RuleInput weekly = new RuleInput(TransactionType.EXPENSE, 99900, USD,
                categoryId(db, TransactionType.EXPENSE, "Bills"), null, "Rent", RecurrenceFrequency.WEEKLY,
                day(2026, 10, 1), null);
        int before = transactions();
        recurring.updateSync(id, weekly, later, UTC);
        assertEquals(0, reconcile(later).recorded);
        assertEquals(before, transactions());
        assertEquals(Long.valueOf(day(2026, 10, 29)), RecurringRepository.nextDue(db.recurringDao().getRule(id), UTC));
    }

    @Test
    public void deletingARuleKeepsTheTransactionsItRecorded() throws Exception {
        long now = at(2026, 10, 22, 8, 0);
        long id = create(RecurrenceFrequency.DAILY, day(2026, 10, 22), null, now);
        reconcile(now);
        reconcile(at(2026, 10, 23, 8, 0));
        assertEquals(2, transactions());

        assertTrue(db.recurringDao().deleteRule(id) > 0);
        assertEquals(2, transactions());
        assertEquals(0, db.recurringDao().occurrenceCount(id));
        assertEquals(0, reconcile(at(2026, 10, 30, 8, 0)).recorded);
    }

    // ---- validation ------------------------------------------------------------------

    @Test
    public void invalidRulesAreRejectedAndNothingIsWritten() throws Exception {
        long now = at(2026, 10, 22, 8, 0);
        long bills = categoryId(db, TransactionType.EXPENSE, "Bills");
        expect(ValidationException.Reason.AMOUNT_NOT_POSITIVE, () -> recurring.createSync(new RuleInput(
                TransactionType.EXPENSE, 0, USD, bills, null, null, RecurrenceFrequency.DAILY,
                day(2026, 10, 22), null), now, UTC));
        expect(ValidationException.Reason.END_BEFORE_START, () -> recurring.createSync(new RuleInput(
                TransactionType.EXPENSE, 100, USD, bills, null, null, RecurrenceFrequency.DAILY,
                day(2026, 10, 22), day(2026, 10, 21)), now, UTC));
        expect(ValidationException.Reason.CATEGORY_TYPE_MISMATCH, () -> recurring.createSync(new RuleInput(
                TransactionType.INCOME, 100, USD, bills, null, null, RecurrenceFrequency.DAILY,
                day(2026, 10, 22), null), now, UTC));
        expect(ValidationException.Reason.CATEGORY_NOT_FOUND, () -> recurring.createSync(new RuleInput(
                TransactionType.EXPENSE, 100, USD, 99999L, null, null, RecurrenceFrequency.DAILY,
                day(2026, 10, 22), null), now, UTC));
        expect(ValidationException.Reason.PAYMENT_METHOD_NOT_FOUND, () -> recurring.createSync(new RuleInput(
                TransactionType.EXPENSE, 100, USD, bills, 99999L, null, RecurrenceFrequency.DAILY,
                day(2026, 10, 22), null), now, UTC));
        expect(ValidationException.Reason.RULE_NOT_FOUND, () -> recurring.setActiveSync(12345, false, now, UTC));
        assertTrue(db.recurringDao().listRules().isEmpty());
    }

    @Test
    public void aCategoryUsedByARuleCannotBeDeleted() throws Exception {
        create(RecurrenceFrequency.DAILY, day(2026, 10, 22), null, at(2026, 10, 22, 8, 0));
        final long bills = categoryId(db, TransactionType.EXPENSE, "Bills");
        expect(ValidationException.Reason.CATEGORY_IN_USE,
                () -> new com.litemobiletools.dailyexpensetracker.data.repository.CategoryRepository(db)
                        .deleteSync(bills));
        assertNotNull(db.categoryDao().getById(bills));
    }
}
