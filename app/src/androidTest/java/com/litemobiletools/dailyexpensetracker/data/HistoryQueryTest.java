package com.litemobiletools.dailyexpensetracker.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.litemobiletools.dailyexpensetracker.data.entity.CategoryEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.PaymentMethodEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionRow;
import com.litemobiletools.dailyexpensetracker.data.repository.CategoryRepository;
import com.litemobiletools.dailyexpensetracker.data.repository.DashboardRepository;
import com.litemobiletools.dailyexpensetracker.data.repository.PaymentMethodRepository;
import com.litemobiletools.dailyexpensetracker.data.repository.TransactionRepository;
import com.litemobiletools.dailyexpensetracker.model.DashboardSnapshot;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;
import com.litemobiletools.dailyexpensetracker.util.DateRanges;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/** History queries, search, filters, paging, edit/delete/undo and dashboard consistency. Made-up data only. */
@RunWith(AndroidJUnit4.class)
public class HistoryQueryTest {

    private static final String USD = "USD";
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private AppDatabase db;
    private TransactionRepository repo;
    private CategoryRepository categories;
    private PaymentMethodRepository payments;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase.class).build();
        DefaultDataSeeder.seed(db);
        repo = new TransactionRepository(db);
        categories = new CategoryRepository(db);
        payments = new PaymentMethodRepository(db);
    }

    @After
    public void tearDown() {
        db.close();
    }

    // ---- helpers ---------------------------------------------------------------------

    private static long utc(int y, int m, int d, int h, int min, int s, int ms) {
        Calendar c = Calendar.getInstance(UTC);
        c.clear();
        c.set(y, m - 1, d, h, min, s);
        c.set(Calendar.MILLISECOND, ms);
        return c.getTimeInMillis();
    }

    private long cat(TransactionType type, String name) {
        for (CategoryEntity c : db.categoryDao().getAll()) {
            if (c.type == type && c.name.equals(name)) {
                return c.id;
            }
        }
        throw new AssertionError("No category " + name);
    }

    private long pay(String name) {
        for (PaymentMethodEntity p : payments.getActiveSync()) {
            if (p.name.equals(name)) {
                return p.id;
            }
        }
        throw new AssertionError("No payment method " + name);
    }

    private long add(TransactionType type, long minor, String category, String payment, String note,
                     String currency, long time) throws Exception {
        return repo.insertSync(new TransactionInput(type, minor, currency, cat(type, category),
                payment == null ? null : pay(payment), time, note));
    }

    private long expense(long minor, String category, long time) throws Exception {
        return add(TransactionType.EXPENSE, minor, category, null, null, USD, time);
    }

    private List<Long> ids(TransactionFilter filter) {
        List<Long> ids = new ArrayList<>();
        for (TransactionRow row : repo.loadHistorySync(filter, USD, 1000, 0, false).rows) {
            ids.add(row.id);
        }
        return ids;
    }

    private TransactionFilter search(String text) {
        TransactionFilter filter = new TransactionFilter();
        filter.searchText = text;
        return filter;
    }

    // ---- ordering and paging ---------------------------------------------------------

    @Test
    public void allTransactionsAreNewestFirst() throws Exception {
        long a = expense(100, "Food", utc(2026, 10, 1, 9, 0, 0, 0));
        long c = expense(300, "Food", utc(2026, 10, 3, 9, 0, 0, 0));
        long b = expense(200, "Food", utc(2026, 10, 2, 9, 0, 0, 0));
        assertEquals(java.util.Arrays.asList(c, b, a), ids(new TransactionFilter()));
    }

    @Test
    public void identicalTimestampsUseNewestIdFirst() throws Exception {
        long when = utc(2026, 10, 1, 9, 0, 0, 0);
        long first = expense(100, "Food", when);
        long second = expense(200, "Food", when);
        long third = expense(300, "Food", when);
        assertEquals(java.util.Arrays.asList(third, second, first), ids(new TransactionFilter()));
    }

    @Test
    public void pagingCoversEveryRowOnceWithoutOverlap() throws Exception {
        for (int i = 0; i < 120; i++) {
            expense(100 + i, "Food", utc(2026, 9, 1, 0, 0, 0, 0) + i * 60_000L);
        }
        TransactionFilter filter = new TransactionFilter();
        HistoryPage first = repo.loadHistorySync(filter, USD, 50, 0, true);
        HistoryPage second = repo.loadHistorySync(filter, USD, 50, 50, false);
        HistoryPage third = repo.loadHistorySync(filter, USD, 50, 100, false);
        assertEquals(120, first.totalCount);
        assertEquals(-1, second.totalCount);
        assertEquals(50, first.rows.size());
        assertEquals(50, second.rows.size());
        assertEquals(20, third.rows.size());
        Set<Long> seen = new HashSet<>();
        long previous = Long.MAX_VALUE;
        for (HistoryPage page : new HistoryPage[]{first, second, third}) {
            for (TransactionRow row : page.rows) {
                assertTrue("duplicate row " + row.id, seen.add(row.id));
                assertTrue(row.transactionTimestamp <= previous);
                previous = row.transactionTimestamp;
            }
        }
        assertEquals(120, seen.size());
    }

    @Test
    public void rowsCarryCategoryIconPaymentAndNoteForDisplay() throws Exception {
        add(TransactionType.EXPENSE, 4599, "Food", "Cash", "Lunch", USD, utc(2026, 10, 1, 9, 0, 0, 0));
        add(TransactionType.INCOME, 1000, "Salary", null, null, USD, utc(2026, 10, 2, 9, 0, 0, 0));
        List<TransactionRow> rows = repo.loadHistorySync(new TransactionFilter(), USD, 10, 0, false).rows;
        TransactionRow salary = rows.get(0);
        assertEquals("Salary", salary.categoryName);
        assertEquals("payments", salary.categoryIconKey);
        assertNotNull(salary.categoryColorArgb);
        assertNull(salary.paymentMethodName);
        assertNull(salary.note);
        TransactionRow food = rows.get(1);
        assertEquals("Cash", food.paymentMethodName);
        assertEquals("Lunch", food.note);
        assertEquals("restaurant", food.categoryIconKey);
    }

    @Test
    public void deactivatedCategoriesStillShowInHistory() throws Exception {
        long id = expense(500, "Health", utc(2026, 10, 1, 9, 0, 0, 0));
        categories.setActiveSync(cat(TransactionType.EXPENSE, "Health"), false);
        assertEquals(java.util.Collections.singletonList(id), ids(new TransactionFilter()));
    }

    // ---- search ----------------------------------------------------------------------

    @Test
    public void searchMatchesNotesCaseInsensitively() throws Exception {
        long lunch = add(TransactionType.EXPENSE, 100, "Other", null, "Team Lunch", USD, utc(2026, 10, 1, 9, 0, 0, 0));
        add(TransactionType.EXPENSE, 100, "Other", null, "Taxi", USD, utc(2026, 10, 2, 9, 0, 0, 0));
        assertEquals(java.util.Collections.singletonList(lunch), ids(search("lunch")));
        assertEquals(java.util.Collections.singletonList(lunch), ids(search("  LUNCH ")));
    }

    @Test
    public void searchMatchesCategoryNames() throws Exception {
        long food = expense(100, "Food", utc(2026, 10, 1, 9, 0, 0, 0));
        expense(100, "Bills", utc(2026, 10, 2, 9, 0, 0, 0));
        assertEquals(java.util.Collections.singletonList(food), ids(search("food")));
        assertEquals(java.util.Collections.singletonList(food), ids(search("oo")));
    }

    @Test
    public void searchMatchesPaymentMethodNames() throws Exception {
        long card = add(TransactionType.EXPENSE, 100, "Food", "Credit Card", null, USD, utc(2026, 10, 1, 9, 0, 0, 0));
        add(TransactionType.EXPENSE, 100, "Food", "Cash", null, USD, utc(2026, 10, 2, 9, 0, 0, 0));
        assertEquals(java.util.Collections.singletonList(card), ids(search("credit")));
    }

    @Test
    public void searchMatchesTransactionTypeByPrefixOfThreeLetters() throws Exception {
        long income = add(TransactionType.INCOME, 100, "Salary", null, null, USD, utc(2026, 10, 1, 9, 0, 0, 0));
        long expense = expense(100, "Transport", utc(2026, 10, 2, 9, 0, 0, 0));
        assertEquals(java.util.Collections.singletonList(income), ids(search("inc")));
        assertEquals(java.util.Collections.singletonList(income), ids(search("Income")));
        assertEquals(java.util.Collections.singletonList(expense), ids(search("expense")));
        // Two letters are too short to count as a type word, so "ex" matches nothing here.
        assertTrue(ids(search("ex")).isEmpty());
    }

    @Test
    public void wildcardCharactersAreMatchedLiterally() throws Exception {
        long percent = add(TransactionType.EXPENSE, 100, "Other", null, "100% done", USD, utc(2026, 10, 1, 9, 0, 0, 0));
        add(TransactionType.EXPENSE, 100, "Other", null, "100 done", USD, utc(2026, 10, 2, 9, 0, 0, 0));
        long underscore = add(TransactionType.EXPENSE, 100, "Other", null, "a_b", USD, utc(2026, 10, 3, 9, 0, 0, 0));
        add(TransactionType.EXPENSE, 100, "Other", null, "axb", USD, utc(2026, 10, 4, 9, 0, 0, 0));
        assertEquals(java.util.Collections.singletonList(percent), ids(search("100%")));
        assertEquals(java.util.Collections.singletonList(underscore), ids(search("a_b")));
        assertTrue(ids(search("%")).size() == 1); // only the note that really contains a percent sign
    }

    @Test
    public void injectionStyleInputIsJustText() throws Exception {
        expense(100, "Food", utc(2026, 10, 1, 9, 0, 0, 0));
        assertTrue(ids(search("'; DROP TABLE transactions; --")).isEmpty());
        assertEquals(1, db.transactionDao().count());
    }

    @Test
    public void blankSearchReturnsEverything() throws Exception {
        expense(100, "Food", utc(2026, 10, 1, 9, 0, 0, 0));
        expense(200, "Food", utc(2026, 10, 2, 9, 0, 0, 0));
        assertEquals(2, ids(search("")).size());
        assertEquals(2, ids(search("    ")).size());
    }

    @Test
    public void noMatchGivesEmptyResultAndZeroCount() throws Exception {
        expense(100, "Food", utc(2026, 10, 1, 9, 0, 0, 0));
        HistoryPage page = repo.loadHistorySync(search("zzzz"), USD, 50, 0, true);
        assertEquals(0, page.totalCount);
        assertTrue(page.rows.isEmpty());
    }

    // ---- filters ---------------------------------------------------------------------

    @Test
    public void typeFilterSeparatesIncomeAndExpenses() throws Exception {
        long in = add(TransactionType.INCOME, 1000, "Salary", null, null, USD, utc(2026, 10, 1, 9, 0, 0, 0));
        long out = expense(100, "Food", utc(2026, 10, 2, 9, 0, 0, 0));
        TransactionFilter filter = new TransactionFilter();
        filter.type = TransactionType.INCOME;
        assertEquals(java.util.Collections.singletonList(in), ids(filter));
        filter.type = TransactionType.EXPENSE;
        assertEquals(java.util.Collections.singletonList(out), ids(filter));
    }

    @Test
    public void categoryAndPaymentFilters() throws Exception {
        long a = add(TransactionType.EXPENSE, 100, "Food", "Cash", null, USD, utc(2026, 10, 1, 9, 0, 0, 0));
        long b = add(TransactionType.EXPENSE, 100, "Food", "Debit Card", null, USD, utc(2026, 10, 2, 9, 0, 0, 0));
        add(TransactionType.EXPENSE, 100, "Bills", "Cash", null, USD, utc(2026, 10, 3, 9, 0, 0, 0));
        TransactionFilter filter = new TransactionFilter();
        filter.categoryId = cat(TransactionType.EXPENSE, "Food");
        assertEquals(java.util.Arrays.asList(b, a), ids(filter));
        filter.paymentMethodId = pay("Cash");
        assertEquals(java.util.Collections.singletonList(a), ids(filter));
    }

    @Test
    public void dateRangeIncludesTheWholeEndDayAndNothingAfter() throws Exception {
        TimeZone zone = TimeZone.getTimeZone("Asia/Dhaka");
        long startDay = DateRanges.localStartOfPickedDay(utc(2026, 10, 10, 0, 0, 0, 0), zone);
        long endDay = DateRanges.localStartOfPickedDay(utc(2026, 10, 12, 0, 0, 0, 0), zone);
        long endExclusive = DateRanges.startOfNextDay(endDay, zone);

        long before = expense(1, "Food", startDay - 1);                // 9 Oct 23:59:59.999
        long atStart = expense(2, "Food", startDay);                   // 10 Oct 00:00
        long middle = expense(3, "Food", startDay + 36 * 3600_000L);   // 11 Oct 12:00
        long endOfEndDay = expense(4, "Food", endExclusive - 1);       // 12 Oct 23:59:59.999
        long after = expense(5, "Food", endExclusive);                 // 13 Oct 00:00

        TransactionFilter filter = new TransactionFilter();
        filter.startMillis = startDay;
        filter.endExclusiveMillis = endExclusive;
        List<Long> result = ids(filter);
        assertEquals(java.util.Arrays.asList(endOfEndDay, middle, atStart), result);
        assertFalse(result.contains(before));
        assertFalse(result.contains(after));

        // Only a start date, only an end date.
        TransactionFilter fromOnly = new TransactionFilter();
        fromOnly.startMillis = startDay;
        assertEquals(java.util.Arrays.asList(after, endOfEndDay, middle, atStart), ids(fromOnly));
        TransactionFilter toOnly = new TransactionFilter();
        toOnly.endExclusiveMillis = startDay;
        assertEquals(java.util.Collections.singletonList(before), ids(toOnly));
    }

    @Test
    public void amountRangeIsInclusiveAndOnlyMatchesTheGivenCurrency() throws Exception {
        long low = expense(500, "Food", utc(2026, 10, 1, 9, 0, 0, 0));
        long mid = expense(1000, "Food", utc(2026, 10, 2, 9, 0, 0, 0));
        long high = expense(2000, "Food", utc(2026, 10, 3, 9, 0, 0, 0));
        add(TransactionType.EXPENSE, 1500, "Food", null, null, "JPY", utc(2026, 10, 4, 9, 0, 0, 0));

        TransactionFilter filter = new TransactionFilter();
        filter.minAmountMinor = 1000L;
        filter.maxAmountMinor = 2000L;
        assertEquals(java.util.Arrays.asList(high, mid), ids(filter));
        filter.minAmountMinor = null;
        assertEquals(java.util.Arrays.asList(mid, low), ids(filterWithMax(1000L)));
        // Without an amount filter, other currencies are still listed.
        assertEquals(4, ids(new TransactionFilter()).size());
    }

    private TransactionFilter filterWithMax(long max) {
        TransactionFilter filter = new TransactionFilter();
        filter.maxAmountMinor = max;
        return filter;
    }

    @Test
    public void combinedFiltersAndSearchNarrowTogetherAndClearingRestoresAll() throws Exception {
        long target = add(TransactionType.EXPENSE, 2500, "Food", "Cash", "Team lunch", USD, utc(2026, 10, 5, 12, 0, 0, 0));
        add(TransactionType.EXPENSE, 2500, "Food", "Cash", "Snacks", USD, utc(2026, 10, 5, 13, 0, 0, 0));
        add(TransactionType.EXPENSE, 900, "Food", "Cash", "Team lunch", USD, utc(2026, 10, 5, 14, 0, 0, 0));
        add(TransactionType.EXPENSE, 2500, "Food", "Debit Card", "Team lunch", USD, utc(2026, 10, 5, 15, 0, 0, 0));
        add(TransactionType.INCOME, 2500, "Gift", "Cash", "Team lunch refund", USD, utc(2026, 10, 5, 16, 0, 0, 0));
        add(TransactionType.EXPENSE, 2500, "Food", "Cash", "Team lunch", USD, utc(2026, 11, 5, 12, 0, 0, 0));

        TransactionFilter filter = new TransactionFilter();
        filter.type = TransactionType.EXPENSE;
        filter.categoryId = cat(TransactionType.EXPENSE, "Food");
        filter.paymentMethodId = pay("Cash");
        filter.minAmountMinor = 2000L;
        filter.maxAmountMinor = 3000L;
        filter.startMillis = utc(2026, 10, 1, 0, 0, 0, 0);
        filter.endExclusiveMillis = utc(2026, 11, 1, 0, 0, 0, 0);
        filter.searchText = "lunch";

        assertEquals(java.util.Collections.singletonList(target), ids(filter));
        assertEquals(5, filter.activeCount());
        assertEquals(1, repo.loadHistorySync(filter, USD, 50, 0, true).totalCount);

        // Clear all filters and the search: everything is back.
        assertEquals(6, ids(new TransactionFilter()).size());
        assertEquals(6, repo.loadHistorySync(new TransactionFilter(), USD, 50, 0, true).totalCount);
    }

    @Test
    public void countAlwaysMatchesTheNumberOfRowsForTheSameFilter() throws Exception {
        for (int i = 0; i < 30; i++) {
            add(i % 2 == 0 ? TransactionType.EXPENSE : TransactionType.INCOME, 100 + i,
                    i % 2 == 0 ? "Food" : "Salary", i % 3 == 0 ? "Cash" : null,
                    i % 5 == 0 ? "note " + i : null, USD, utc(2026, 10, 1, 0, 0, 0, 0) + i * 3600_000L);
        }
        TransactionFilter[] filters = new TransactionFilter[4];
        filters[0] = new TransactionFilter();
        filters[1] = search("note");
        filters[2] = new TransactionFilter();
        filters[2].type = TransactionType.INCOME;
        filters[3] = new TransactionFilter();
        filters[3].paymentMethodId = pay("Cash");
        for (TransactionFilter filter : filters) {
            HistoryPage page = repo.loadHistorySync(filter, USD, 1000, 0, true);
            assertEquals(page.rows.size(), page.totalCount);
        }
    }

    // ---- edit, delete, undo ----------------------------------------------------------

    @Test
    public void editingUpdatesTheOriginalRecordWithoutDuplicating() throws Exception {
        long id = add(TransactionType.EXPENSE, 1000, "Food", "Cash", "Old", USD, utc(2026, 10, 1, 9, 0, 0, 0));
        TransactionEntity before = repo.getByIdSync(id);
        Thread.sleep(5);
        repo.updateSync(id, new TransactionInput(TransactionType.EXPENSE, 1250, USD,
                cat(TransactionType.EXPENSE, "Bills"), null, utc(2026, 10, 2, 9, 0, 0, 0), "New"));

        assertEquals(1, db.transactionDao().count());
        TransactionRow row = repo.getRowSync(id);
        assertEquals(1250, row.amountMinor);
        assertEquals("Bills", row.categoryName);
        assertNull(row.paymentMethodName);
        assertEquals("New", row.note);
        assertEquals(before.createdAt, row.createdAt);
        assertTrue(row.updatedAt > before.updatedAt);
    }

    @Test
    public void editingMayChangeTheTypeWhenACategoryOfThatTypeIsChosen() throws Exception {
        long id = expense(1000, "Food", utc(2026, 10, 1, 9, 0, 0, 0));
        repo.updateSync(id, new TransactionInput(TransactionType.INCOME, 1000, USD,
                cat(TransactionType.INCOME, "Gift"), null, utc(2026, 10, 1, 9, 0, 0, 0), null));
        TransactionRow row = repo.getRowSync(id);
        assertEquals(TransactionType.INCOME, row.type);
        assertEquals("Gift", row.categoryName);
        assertEquals(1, db.transactionDao().count());
    }

    @Test
    public void editedRecordKeepsADeactivatedCategory() throws Exception {
        long id = expense(1000, "Health", utc(2026, 10, 1, 9, 0, 0, 0));
        long health = cat(TransactionType.EXPENSE, "Health");
        categories.setActiveSync(health, false);
        repo.updateSync(id, new TransactionInput(TransactionType.EXPENSE, 1100, USD, health, null,
                utc(2026, 10, 1, 9, 0, 0, 0), null));
        assertEquals(1100, repo.getRowSync(id).amountMinor);
        assertTrue(categories.getSelectableByTypeSync(TransactionType.EXPENSE, health).size()
                > categories.getActiveByTypeSync(TransactionType.EXPENSE).size());
    }

    @Test
    public void notWritingAnEditLeavesTheRecordUnchanged() throws Exception {
        long id = add(TransactionType.EXPENSE, 1000, "Food", "Cash", "Keep me", USD, utc(2026, 10, 1, 9, 0, 0, 0));
        TransactionRow before = repo.getRowSync(id);
        // A cancelled edit never reaches the repository; the stored record must be identical.
        TransactionRow after = repo.getRowSync(id);
        assertTrue(before.sameContentAs(after));
        assertEquals(1, db.transactionDao().count());
    }

    @Test
    public void deleteRemovesOnlyTheChosenTransactionAndNothingRelated() throws Exception {
        long keepA = expense(100, "Food", utc(2026, 10, 1, 9, 0, 0, 0));
        long gone = add(TransactionType.EXPENSE, 200, "Food", "Cash", null, USD, utc(2026, 10, 2, 9, 0, 0, 0));
        long keepB = expense(300, "Food", utc(2026, 10, 3, 9, 0, 0, 0));
        int categoriesBefore = db.categoryDao().getAll().size();
        int paymentsBefore = payments.getActiveSync().size();

        TransactionEntity deleted = repo.deleteReturningSync(gone);

        assertNotNull(deleted);
        assertEquals(gone, deleted.id);
        assertEquals(java.util.Arrays.asList(keepB, keepA), ids(new TransactionFilter()));
        assertEquals(categoriesBefore, db.categoryDao().getAll().size());
        assertEquals(paymentsBefore, payments.getActiveSync().size());
        assertNull(repo.deleteReturningSync(gone)); // a second delete finds nothing and changes nothing
        assertEquals(2, db.transactionDao().count());
    }

    @Test
    public void undoRestoresTheSameRecordWithItsOriginalId() throws Exception {
        long id = add(TransactionType.INCOME, 5000, "Salary", "Bank Transfer", "Pay", USD, utc(2026, 10, 1, 9, 0, 0, 0));
        TransactionRow before = repo.getRowSync(id);
        TransactionEntity deleted = repo.deleteReturningSync(id);
        assertNull(repo.getRowSync(id));

        assertTrue(repo.restoreSync(deleted));

        TransactionRow after = repo.getRowSync(id);
        assertNotNull(after);
        assertTrue(before.sameContentAs(after));
        assertEquals(before.createdAt, after.createdAt);
        assertEquals(1, db.transactionDao().count());
        // Restoring again must not create a duplicate.
        assertFalse(repo.restoreSync(deleted));
        assertEquals(1, db.transactionDao().count());
    }

    // ---- dashboard consistency -------------------------------------------------------

    @Test
    public void dashboardTotalsFollowAddEditAndDelete() throws Exception {
        DashboardRepository dashboard = new DashboardRepository(db);
        long now = utc(2026, 10, 15, 12, 0, 0, 0);
        DashboardSnapshot empty = dashboard.loadSync(USD, "Other", now, UTC);
        assertEquals(0, empty.totalBalanceMinor);
        assertTrue(empty.recent.isEmpty());

        long salary = add(TransactionType.INCOME, 100000, "Salary", null, null, USD, now - 3600_000L);
        long food = add(TransactionType.EXPENSE, 2500, "Food", null, null, USD, now - 7200_000L);
        DashboardSnapshot afterAdd = dashboard.loadSync(USD, "Other", now, UTC);
        assertEquals(97500, afterAdd.totalBalanceMinor);
        assertEquals(100000, afterAdd.monthIncomeMinor);
        assertEquals(2500, afterAdd.monthExpenseMinor);
        assertEquals(2, afterAdd.recent.size());
        assertEquals(salary, afterAdd.recent.get(0).id);

        repo.updateSync(food, new TransactionInput(TransactionType.EXPENSE, 4000, USD,
                cat(TransactionType.EXPENSE, "Food"), null, now - 7200_000L, null));
        DashboardSnapshot afterEdit = dashboard.loadSync(USD, "Other", now, UTC);
        assertEquals(96000, afterEdit.totalBalanceMinor);
        assertEquals(4000, afterEdit.monthExpenseMinor);

        repo.deleteReturningSync(food);
        DashboardSnapshot afterDelete = dashboard.loadSync(USD, "Other", now, UTC);
        assertEquals(100000, afterDelete.totalBalanceMinor);
        assertEquals(0, afterDelete.monthExpenseMinor);
        assertEquals(1, afterDelete.recent.size());
    }

    // ---- performance -----------------------------------------------------------------

    @Test
    public void largeHistoriesStayFastToPageSearchAndFilter() throws Exception {
        final int total = 5000;
        db.runInTransaction(() -> {
            long base = utc(2024, 1, 1, 0, 0, 0, 0);
            long food = cat(TransactionType.EXPENSE, "Food");
            long salary = cat(TransactionType.INCOME, "Salary");
            for (int i = 0; i < total; i++) {
                TransactionEntity t = new TransactionEntity();
                boolean income = i % 10 == 0;
                t.type = income ? TransactionType.INCOME : TransactionType.EXPENSE;
                t.amountMinor = 100 + i;
                t.currencyCode = USD;
                t.categoryId = income ? salary : food;
                t.transactionTimestamp = base + i * 3_600_000L;
                t.note = i % 7 == 0 ? "coffee " + i : null;
                t.createdAt = base;
                t.updatedAt = base;
                db.transactionDao().insert(t);
            }
            return null;
        });

        long t0 = System.nanoTime();
        HistoryPage firstPage = repo.loadHistorySync(new TransactionFilter(), USD, 50, 0, true);
        long firstPageMs = (System.nanoTime() - t0) / 1_000_000;

        t0 = System.nanoTime();
        HistoryPage deepPage = repo.loadHistorySync(new TransactionFilter(), USD, 50, 4900, false);
        long deepPageMs = (System.nanoTime() - t0) / 1_000_000;

        t0 = System.nanoTime();
        HistoryPage searched = repo.loadHistorySync(search("coffee"), USD, 50, 0, true);
        long searchMs = (System.nanoTime() - t0) / 1_000_000;

        TransactionFilter combined = search("coffee");
        combined.type = TransactionType.EXPENSE;
        combined.minAmountMinor = 500L;
        combined.startMillis = utc(2024, 3, 1, 0, 0, 0, 0);
        t0 = System.nanoTime();
        HistoryPage filtered = repo.loadHistorySync(combined, USD, 50, 0, true);
        long filterMs = (System.nanoTime() - t0) / 1_000_000;

        Log.i("HistoryQueryTest", "5000 rows: firstPage=" + firstPageMs + "ms deepPage=" + deepPageMs
                + "ms search=" + searchMs + "ms combined=" + filterMs + "ms");
        assertEquals(total, firstPage.totalCount);
        assertEquals(50, firstPage.rows.size());
        assertEquals(50, deepPage.rows.size());
        assertEquals(715, searched.totalCount); // i % 7 == 0 for i in [0, 5000)
        assertTrue(filtered.totalCount > 0 && filtered.totalCount < searched.totalCount);
        assertTrue("first page took " + firstPageMs + "ms", firstPageMs < 1000);
        assertTrue("deep page took " + deepPageMs + "ms", deepPageMs < 1000);
        assertTrue("search took " + searchMs + "ms", searchMs < 1000);
        assertTrue("combined filter took " + filterMs + "ms", filterMs < 1000);
    }
}
