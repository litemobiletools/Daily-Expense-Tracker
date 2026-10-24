package com.litemobiletools.dailyexpensetracker.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.sqlite.SQLiteConstraintException;

import androidx.annotation.NonNull;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.litemobiletools.dailyexpensetracker.data.entity.CategoryEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.CategoryTotal;
import com.litemobiletools.dailyexpensetracker.data.entity.PaymentMethodEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionEntity;
import com.litemobiletools.dailyexpensetracker.data.repository.CategoryRepository;
import com.litemobiletools.dailyexpensetracker.data.repository.PaymentMethodRepository;
import com.litemobiletools.dailyexpensetracker.data.repository.TransactionRepository;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Room tests on a real SQLite engine. They use made-up data only. Test code runs off the main thread. */
@RunWith(AndroidJUnit4.class)
public class DatabaseTest {

    private static final String USD = "USD";
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private Context context;
    private AppDatabase db;
    private TransactionRepository transactions;
    private CategoryRepository categories;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase.class).build();
        DefaultDataSeeder.seed(db);
        transactions = new TransactionRepository(db);
        categories = new CategoryRepository(db);
    }

    @After
    public void tearDown() {
        if (db.isOpen()) {
            db.close();
        }
    }

    // ---- helpers ---------------------------------------------------------------------

    private static long utc(int y, int m, int d, int h, int min, int s, int ms) {
        Calendar c = Calendar.getInstance(UTC);
        c.clear();
        c.set(y, m - 1, d, h, min, s);
        c.set(Calendar.MILLISECOND, ms);
        return c.getTimeInMillis();
    }

    private long categoryId(TransactionType type, String name) {
        for (CategoryEntity c : categories.getActiveByTypeSync(type)) {
            if (c.name.equals(name)) {
                return c.id;
            }
        }
        throw new AssertionError("No category " + name);
    }

    private TransactionInput input(TransactionType type, long minor, String currency, long categoryId, long time) {
        return new TransactionInput(type, minor, currency, categoryId, null, time, null);
    }

    private long add(TransactionType type, long minor, String category, long time) throws Exception {
        return transactions.insertSync(input(type, minor, USD, categoryId(type, category), time));
    }

    private void assertRejected(ValidationException.Reason reason, TransactionInput input) throws Exception {
        try {
            transactions.insertSync(input);
            fail("Expected rejection: " + reason);
        } catch (ValidationException e) {
            assertEquals(reason, e.getReason());
        }
    }

    // ---- default data ----------------------------------------------------------------

    @Test
    public void defaultCategoriesAndPaymentMethodsAreSeeded() {
        assertEquals(8, categories.getActiveByTypeSync(TransactionType.EXPENSE).size());
        assertEquals(6, categories.getActiveByTypeSync(TransactionType.INCOME).size());
        assertEquals(6, new PaymentMethodRepository(db).getActiveSync().size());
    }

    @Test
    public void seedingTwiceDoesNotCreateDuplicates() {
        DefaultDataSeeder.seed(db);
        DefaultDataSeeder.seed(db);
        assertEquals(14, db.categoryDao().getAll().size());
        assertEquals(DefaultDataSeeder.defaultCategoryCount(), db.categoryDao().countDefaults());
        assertEquals(6, new PaymentMethodRepository(db).getActiveSync().size());
    }

    @Test
    public void seedingDoesNotUndoRenameOrDeactivation() throws Exception {
        long food = categoryId(TransactionType.EXPENSE, "Food");
        categories.renameSync(food, "Groceries");
        long gift = categoryId(TransactionType.INCOME, "Gift");
        categories.setActiveSync(gift, false);
        DefaultDataSeeder.seed(db);
        assertEquals("Groceries", db.categoryDao().getById(food).name);
        assertFalse(db.categoryDao().getById(gift).isActive);
        assertEquals(14, db.categoryDao().getAll().size());
    }

    @Test
    public void categoriesAreFilteredByType() {
        for (CategoryEntity c : categories.getActiveByTypeSync(TransactionType.EXPENSE)) {
            assertEquals(TransactionType.EXPENSE, c.type);
        }
        for (CategoryEntity c : categories.getActiveByTypeSync(TransactionType.INCOME)) {
            assertEquals(TransactionType.INCOME, c.type);
        }
        assertEquals("Food", categories.getActiveByTypeSync(TransactionType.EXPENSE).get(0).name);
        assertEquals("Salary", categories.getActiveByTypeSync(TransactionType.INCOME).get(0).name);
    }

    // ---- inserting -------------------------------------------------------------------

    @Test
    public void insertsValidIncomeAndExpense() throws Exception {
        long income = add(TransactionType.INCOME, 250000, "Salary", utc(2026, 10, 5, 9, 0, 0, 0));
        long expense = add(TransactionType.EXPENSE, 4599, "Food", utc(2026, 10, 6, 12, 0, 0, 0));
        assertTrue(income > 0 && expense > 0 && income != expense);

        TransactionEntity saved = transactions.getByIdSync(expense);
        assertNotNull(saved);
        assertEquals(TransactionType.EXPENSE, saved.type);
        assertEquals(4599, saved.amountMinor);
        assertEquals(USD, saved.currencyCode);
        assertNull(saved.paymentMethodId);
        assertTrue(saved.createdAt > 0 && saved.createdAt == saved.updatedAt);
        assertEquals(2, db.transactionDao().count());
    }

    @Test
    public void storesOptionalPaymentMethodAndTrimmedNote() throws Exception {
        PaymentMethodEntity cash = new PaymentMethodRepository(db).getActiveSync().get(0);
        long id = transactions.insertSync(new TransactionInput(TransactionType.EXPENSE, 1000, USD,
                categoryId(TransactionType.EXPENSE, "Transport"), cash.id, utc(2026, 10, 6, 8, 0, 0, 0), "  Bus  "));
        TransactionEntity saved = transactions.getByIdSync(id);
        assertEquals(Long.valueOf(cash.id), saved.paymentMethodId);
        assertEquals("Bus", saved.note);
    }

    @Test
    public void rejectsZeroNegativeAndHugeAmountsWithoutWriting() throws Exception {
        long food = categoryId(TransactionType.EXPENSE, "Food");
        long when = utc(2026, 10, 6, 8, 0, 0, 0);
        assertRejected(ValidationException.Reason.AMOUNT_NOT_POSITIVE,
                input(TransactionType.EXPENSE, 0, USD, food, when));
        assertRejected(ValidationException.Reason.AMOUNT_NOT_POSITIVE,
                input(TransactionType.EXPENSE, -5, USD, food, when));
        assertRejected(ValidationException.Reason.AMOUNT_TOO_LARGE,
                input(TransactionType.EXPENSE, Long.MAX_VALUE, USD, food, when));
        assertEquals(0, db.transactionDao().count());
    }

    @Test
    public void rejectsCategoryOfWrongTypeUnknownOrInactive() throws Exception {
        long salary = categoryId(TransactionType.INCOME, "Salary");
        long when = utc(2026, 10, 6, 8, 0, 0, 0);
        assertRejected(ValidationException.Reason.CATEGORY_TYPE_MISMATCH,
                input(TransactionType.EXPENSE, 100, USD, salary, when));
        assertRejected(ValidationException.Reason.CATEGORY_NOT_FOUND,
                input(TransactionType.EXPENSE, 100, USD, 99999, when));
        long gift = categoryId(TransactionType.INCOME, "Gift");
        categories.setActiveSync(gift, false);
        assertRejected(ValidationException.Reason.CATEGORY_INACTIVE,
                input(TransactionType.INCOME, 100, USD, gift, when));
        assertEquals(0, db.transactionDao().count());
    }

    @Test
    public void rejectsUnknownPaymentMethod() throws Exception {
        assertRejected(ValidationException.Reason.PAYMENT_METHOD_NOT_FOUND,
                new TransactionInput(TransactionType.EXPENSE, 100, USD,
                        categoryId(TransactionType.EXPENSE, "Food"), 99999L, utc(2026, 10, 6, 8, 0, 0, 0), null));
    }

    @Test
    public void foreignKeysAreEnforcedByTheDatabase() {
        TransactionEntity bad = new TransactionEntity();
        bad.type = TransactionType.EXPENSE;
        bad.amountMinor = 100;
        bad.currencyCode = USD;
        bad.categoryId = 424242;
        bad.transactionTimestamp = 1;
        try {
            db.transactionDao().insert(bad);
            fail("Expected a foreign key violation");
        } catch (SQLiteConstraintException expected) {
            assertEquals(0, db.transactionDao().count());
        }
    }

    // ---- totals ----------------------------------------------------------------------

    @Test
    public void totalsAndNetBalanceAreCorrect() throws Exception {
        add(TransactionType.INCOME, 300000, "Salary", utc(2026, 10, 1, 9, 0, 0, 0));
        add(TransactionType.INCOME, 50000, "Freelance", utc(2026, 10, 10, 9, 0, 0, 0));
        add(TransactionType.EXPENSE, 12050, "Food", utc(2026, 10, 2, 9, 0, 0, 0));
        add(TransactionType.EXPENSE, 80000, "Bills", utc(2026, 10, 3, 9, 0, 0, 0));

        Totals totals = transactions.getAllTimeTotalsSync(USD);
        assertEquals(350000, totals.incomeMinor);
        assertEquals(92050, totals.expenseMinor);
        assertEquals(257950, totals.netMinor());
        assertEquals(257950, transactions.getNetBalanceSync(USD, Long.MIN_VALUE, Long.MAX_VALUE));
    }

    @Test
    public void emptyDatabaseGivesZeroTotals() {
        Totals totals = transactions.getAllTimeTotalsSync(USD);
        assertEquals(0, totals.incomeMinor);
        assertEquals(0, totals.expenseMinor);
        assertEquals(0, totals.netMinor());
    }

    @Test
    public void netBalanceCanBeNegative() throws Exception {
        add(TransactionType.INCOME, 1000, "Gift", utc(2026, 10, 1, 9, 0, 0, 0));
        add(TransactionType.EXPENSE, 2500, "Food", utc(2026, 10, 2, 9, 0, 0, 0));
        assertEquals(-1500, transactions.getAllTimeTotalsSync(USD).netMinor());
    }

    @Test
    public void dateRangeIncludesStartAndExcludesEnd() throws Exception {
        long start = utc(2026, 10, 1, 0, 0, 0, 0);
        long end = utc(2026, 11, 1, 0, 0, 0, 0);
        add(TransactionType.EXPENSE, 100, "Food", start - 1);          // Sep 30 23:59:59.999 -> outside
        add(TransactionType.EXPENSE, 200, "Food", start);              // exactly start -> inside
        add(TransactionType.EXPENSE, 400, "Food", end - 1);            // last millisecond -> inside
        add(TransactionType.EXPENSE, 800, "Food", end);                // exactly end -> outside

        assertEquals(600, transactions.getTotalsSync(USD, start, end).expenseMinor);
        assertEquals(100, transactions.getTotalsSync(USD, Long.MIN_VALUE, start).expenseMinor);
        assertEquals(800, transactions.getTotalsSync(USD, end, Long.MAX_VALUE).expenseMinor);
    }

    @Test
    public void totalsDoNotMixCurrencies() throws Exception {
        add(TransactionType.EXPENSE, 1000, "Food", utc(2026, 10, 1, 9, 0, 0, 0));
        transactions.insertSync(input(TransactionType.EXPENSE, 5000, "JPY",
                categoryId(TransactionType.EXPENSE, "Food"), utc(2026, 10, 2, 9, 0, 0, 0)));
        assertEquals(1000, transactions.getAllTimeTotalsSync(USD).expenseMinor);
        assertEquals(5000, transactions.getAllTimeTotalsSync("JPY").expenseMinor);
    }

    @Test
    public void groupsExpensesByCategoryLargestFirst() throws Exception {
        add(TransactionType.EXPENSE, 1000, "Food", utc(2026, 10, 1, 9, 0, 0, 0));
        add(TransactionType.EXPENSE, 2000, "Food", utc(2026, 10, 2, 9, 0, 0, 0));
        add(TransactionType.EXPENSE, 9000, "Bills", utc(2026, 10, 3, 9, 0, 0, 0));
        add(TransactionType.INCOME, 50000, "Salary", utc(2026, 10, 3, 9, 0, 0, 0));

        List<CategoryTotal> grouped = transactions.getTotalsByCategorySync(
                TransactionType.EXPENSE, USD, Long.MIN_VALUE, Long.MAX_VALUE);
        assertEquals(2, grouped.size());
        assertEquals("Bills", grouped.get(0).categoryName);
        assertEquals(9000, grouped.get(0).totalMinor);
        assertEquals("Food", grouped.get(1).categoryName);
        assertEquals(3000, grouped.get(1).totalMinor);
    }

    @Test
    public void recentTransactionsAreNewestFirstWithCategoryNames() throws Exception {
        add(TransactionType.EXPENSE, 100, "Food", utc(2026, 10, 1, 9, 0, 0, 0));
        add(TransactionType.INCOME, 200, "Salary", utc(2026, 10, 3, 9, 0, 0, 0));
        add(TransactionType.EXPENSE, 300, "Bills", utc(2026, 10, 2, 9, 0, 0, 0));
        assertEquals("Salary", transactions.getRecentSync(5).get(0).categoryName);
        assertEquals(2, transactions.getRecentSync(2).size());
    }

    // ---- editing and deleting --------------------------------------------------------

    @Test
    public void updateChangesOnlyTheIntendedTransaction() throws Exception {
        long first = add(TransactionType.EXPENSE, 1000, "Food", utc(2026, 10, 1, 9, 0, 0, 0));
        long second = add(TransactionType.EXPENSE, 2000, "Bills", utc(2026, 10, 2, 9, 0, 0, 0));
        TransactionEntity before = transactions.getByIdSync(first);

        Thread.sleep(5);
        transactions.updateSync(first, new TransactionInput(TransactionType.EXPENSE, 1500, USD,
                categoryId(TransactionType.EXPENSE, "Health"), null, utc(2026, 10, 4, 9, 0, 0, 0), "Checkup"));

        TransactionEntity after = transactions.getByIdSync(first);
        assertEquals(1500, after.amountMinor);
        assertEquals("Checkup", after.note);
        assertEquals(before.createdAt, after.createdAt);
        assertTrue(after.updatedAt > before.updatedAt);
        assertEquals(2000, transactions.getByIdSync(second).amountMinor);
        assertEquals(3500, transactions.getAllTimeTotalsSync(USD).expenseMinor);
    }

    @Test
    public void updateOfMissingTransactionIsRejected() throws Exception {
        try {
            transactions.updateSync(777, input(TransactionType.EXPENSE, 100, USD,
                    categoryId(TransactionType.EXPENSE, "Food"), utc(2026, 10, 1, 9, 0, 0, 0)));
            fail("Expected not found");
        } catch (ValidationException e) {
            assertEquals(ValidationException.Reason.TRANSACTION_NOT_FOUND, e.getReason());
        }
    }

    @Test
    public void invalidUpdateLeavesTheOriginalUntouched() throws Exception {
        long id = add(TransactionType.EXPENSE, 1000, "Food", utc(2026, 10, 1, 9, 0, 0, 0));
        try {
            transactions.updateSync(id, input(TransactionType.EXPENSE, 0, USD,
                    categoryId(TransactionType.EXPENSE, "Food"), utc(2026, 10, 1, 9, 0, 0, 0)));
            fail("Expected rejection");
        } catch (ValidationException expected) {
            assertEquals(1000, transactions.getByIdSync(id).amountMinor);
        }
    }

    @Test
    public void deleteRemovesOnlyThatTransactionAndTotalsStayConsistent() throws Exception {
        long keep = add(TransactionType.INCOME, 10000, "Salary", utc(2026, 10, 1, 9, 0, 0, 0));
        long remove = add(TransactionType.EXPENSE, 2500, "Food", utc(2026, 10, 2, 9, 0, 0, 0));
        add(TransactionType.EXPENSE, 700, "Food", utc(2026, 10, 3, 9, 0, 0, 0));
        assertEquals(6800, transactions.getAllTimeTotalsSync(USD).netMinor());

        assertTrue(transactions.deleteSync(remove));
        assertFalse(transactions.deleteSync(remove));
        assertNull(transactions.getByIdSync(remove));
        assertNotNull(transactions.getByIdSync(keep));
        Totals totals = transactions.getAllTimeTotalsSync(USD);
        assertEquals(10000, totals.incomeMinor);
        assertEquals(700, totals.expenseMinor);
        assertEquals(9300, totals.netMinor());
    }

    // ---- categories ------------------------------------------------------------------

    @Test
    public void categoryInUseCannotBeDeletedButUnusedCanBe() throws Exception {
        add(TransactionType.EXPENSE, 100, "Food", utc(2026, 10, 1, 9, 0, 0, 0));
        long food = categoryId(TransactionType.EXPENSE, "Food");
        try {
            categories.deleteSync(food);
            fail("Expected CATEGORY_IN_USE");
        } catch (ValidationException e) {
            assertEquals(ValidationException.Reason.CATEGORY_IN_USE, e.getReason());
        }
        assertNotNull(categories.getByIdSync(food));

        long custom = categories.addSync("Pets", TransactionType.EXPENSE, "pets", null);
        categories.deleteSync(custom);
        assertNull(categories.getByIdSync(custom));
    }

    @Test
    public void renameAndDeactivateKeepHistory() throws Exception {
        long id = add(TransactionType.EXPENSE, 1234, "Food", utc(2026, 10, 1, 9, 0, 0, 0));
        long food = categoryId(TransactionType.EXPENSE, "Food");
        categories.renameSync(food, "Groceries");
        categories.setActiveSync(food, false);

        assertEquals(food, transactions.getByIdSync(id).categoryId);
        assertEquals("Groceries", transactions.getRecentSync(1).get(0).categoryName);
        assertEquals(1234, transactions.getAllTimeTotalsSync(USD).expenseMinor);
        for (CategoryEntity c : categories.getActiveByTypeSync(TransactionType.EXPENSE)) {
            assertNotEquals(food, c.id);
        }
    }

    @Test
    public void duplicateAndInvalidCategoryNamesAreRejected() throws Exception {
        try {
            categories.addSync("food", TransactionType.EXPENSE, "x", null);
            fail("Expected duplicate");
        } catch (ValidationException e) {
            assertEquals(ValidationException.Reason.CATEGORY_NAME_DUPLICATE, e.getReason());
        }
        // The same name is fine under the other type.
        categories.addSync("Food", TransactionType.INCOME, "x", null);
        try {
            categories.addSync("   ", TransactionType.EXPENSE, "x", null);
            fail("Expected invalid");
        } catch (ValidationException e) {
            assertEquals(ValidationException.Reason.CATEGORY_NAME_INVALID, e.getReason());
        }
    }

    // ---- threading and failure handling ----------------------------------------------

    @Test
    public void databaseAccessOnTheMainThreadIsRefused() throws Exception {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                db.transactionDao().count();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        assertTrue(String.valueOf(failure.get()), failure.get() instanceof IllegalStateException);
    }

    @Test
    public void asyncRepositoryDeliversResultsOnTheMainThread() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Boolean> onMain = new AtomicReference<>();
        final AtomicReference<Long> id = new AtomicReference<>();
        transactions.insert(input(TransactionType.EXPENSE, 100, USD,
                categoryId(TransactionType.EXPENSE, "Food"), utc(2026, 10, 1, 9, 0, 0, 0)),
                new RepoCallback<Long>() {
                    @Override
                    public void onSuccess(Long result) {
                        onMain.set(android.os.Looper.myLooper() == android.os.Looper.getMainLooper());
                        id.set(result);
                        latch.countDown();
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        latch.countDown();
                    }
                });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(Boolean.TRUE, onMain.get());
        assertNotNull(id.get());
    }

    @Test
    public void failedDatabaseOperationReportsAnErrorInsteadOfCrashing() throws Exception {
        long food = categoryId(TransactionType.EXPENSE, "Food");
        db.close();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Exception> error = new AtomicReference<>();
        transactions.insert(input(TransactionType.EXPENSE, 100, USD, food, utc(2026, 10, 1, 9, 0, 0, 0)),
                new RepoCallback<Long>() {
                    @Override
                    public void onSuccess(Long result) {
                        latch.countDown();
                    }

                    @Override
                    public void onError(@NonNull Exception e) {
                        error.set(e);
                        latch.countDown();
                    }
                });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNotNull("Closed database must surface as onError", error.get());
    }

    // ---- persistence and schema ------------------------------------------------------

    @Test
    public void dataSurvivesClosingAndReopeningTheDatabaseFile() throws Exception {
        String name = "reopen_test.db";
        context.deleteDatabase(name);
        AppDatabase first = Room.databaseBuilder(context, AppDatabase.class, name).build();
        DefaultDataSeeder.seed(first);
        TransactionRepository repo = new TransactionRepository(first);
        long foodId = new CategoryRepository(first).getActiveByTypeSync(TransactionType.EXPENSE).get(0).id;
        long id = repo.insertSync(input(TransactionType.EXPENSE, 4321, USD, foodId, utc(2026, 10, 1, 9, 0, 0, 0)));
        first.close();

        AppDatabase second = Room.databaseBuilder(context, AppDatabase.class, name)
                .addMigrations(AppDatabase.MIGRATIONS).build();
        try {
            TransactionEntity reloaded = second.transactionDao().getById(id);
            assertNotNull(reloaded);
            assertEquals(4321, reloaded.amountMinor);
            DefaultDataSeeder.seed(second);
            assertEquals(14, second.categoryDao().getAll().size());
        } finally {
            second.close();
            context.deleteDatabase(name);
        }
    }

    @Test
    public void appRequestsOnlyTheInternetPermissionForOptionalDriveAccess() throws Exception {
        android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(
                context.getPackageName(), android.content.pm.PackageManager.GET_PERMISSIONS);
        // AndroidX adds a private "<package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION". INTERNET is the only
        // system permission allowed (optional Google Drive connection); storage, contacts, location, etc. are not.
        if (info.requestedPermissions != null) {
            for (String permission : info.requestedPermissions) {
                assertTrue("Unexpected permission: " + permission,
                        permission.startsWith(context.getPackageName() + ".")
                                || permission.equals(android.Manifest.permission.INTERNET));
            }
        }
    }
}
