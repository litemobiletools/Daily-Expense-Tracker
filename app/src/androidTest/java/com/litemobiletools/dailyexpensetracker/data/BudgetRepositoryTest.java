package com.litemobiletools.dailyexpensetracker.data;

import static com.litemobiletools.dailyexpensetracker.data.TestSupport.USD;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.UTC;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.at;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.categoryId;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.expense;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.income;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.litemobiletools.dailyexpensetracker.data.repository.BudgetRepository;
import com.litemobiletools.dailyexpensetracker.data.repository.DashboardRepository;
import com.litemobiletools.dailyexpensetracker.data.repository.TransactionRepository;
import com.litemobiletools.dailyexpensetracker.model.BudgetPeriod;
import com.litemobiletools.dailyexpensetracker.model.BudgetProgress;
import com.litemobiletools.dailyexpensetracker.model.DashboardSnapshot;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;
import com.litemobiletools.dailyexpensetracker.util.BudgetMath;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class BudgetRepositoryTest {

    private AppDatabase db;
    private BudgetRepository budgets;

    @Before
    public void setUp() {
        db = TestSupport.newDatabase();
        budgets = new BudgetRepository(db);
    }

    @After
    public void tearDown() {
        db.close();
    }

    private List<BudgetProgress> monthly(long anchor) {
        return budgets.progressSync(BudgetPeriod.MONTHLY, USD, anchor, UTC, Calendar.MONDAY);
    }

    private BudgetProgress overall(List<BudgetProgress> list) {
        for (BudgetProgress p : list) {
            if (p.isOverall()) {
                return p;
            }
        }
        throw new AssertionError("no overall budget");
    }

    private BudgetProgress category(List<BudgetProgress> list, String name) {
        for (BudgetProgress p : list) {
            if (name.equals(p.categoryName)) {
                return p;
            }
        }
        throw new AssertionError("no budget for " + name);
    }

    private void expectRejected(ValidationException.Reason reason, Object unused, Callable body) throws Exception {
        try {
            body.call();
            fail("Expected " + reason);
        } catch (ValidationException e) {
            assertEquals(reason, e.getReason());
        }
    }

    private interface Callable {
        void call() throws Exception;
    }

    @Test
    public void overallBudgetCountsOnlyExpensesInThePeriod() throws Exception {
        budgets.saveSync(null, BudgetPeriod.MONTHLY, null, USD, 100000);
        expense(db, 30000, "Food", at(2026, 10, 3, 12, 0));
        expense(db, 20000, "Bills", at(2026, 10, 20, 12, 0));
        income(db, 500000, "Salary", at(2026, 10, 1, 9, 0));       // income is never spending
        expense(db, 99999, "Food", at(2026, 9, 30, 23, 59));        // previous month
        expense(db, 88888, "Food", at(2026, 11, 1, 0, 0));          // next month

        BudgetProgress p = overall(monthly(at(2026, 10, 15, 0, 0)));
        assertEquals(50000, p.spentMinor);
        assertEquals(100000, p.limitMinor());
        assertEquals(50000, p.remainingMinor());
        assertEquals(50, p.percent());
        assertEquals(BudgetMath.Status.OK, p.status());
    }

    @Test
    public void categoryBudgetCountsOnlyThatCategoryAndIsIndependentOfTheOverallBudget() throws Exception {
        budgets.saveSync(null, BudgetPeriod.MONTHLY, null, USD, 100000);
        budgets.saveSync(null, BudgetPeriod.MONTHLY, categoryId(db, TransactionType.EXPENSE, "Food"), USD, 40000);
        expense(db, 30000, "Food", at(2026, 10, 3, 12, 0));
        expense(db, 25000, "Transport", at(2026, 10, 4, 12, 0));

        List<BudgetProgress> list = monthly(at(2026, 10, 15, 0, 0));
        assertEquals(2, list.size());
        // The same Food expense counts once in the overall budget and once in its own; they are not summed.
        assertEquals(55000, overall(list).spentMinor);
        assertEquals(30000, category(list, "Food").spentMinor);
        assertEquals(75, category(list, "Food").percent());
        assertEquals(BudgetMath.Status.OK, category(list, "Food").status());       // 75% is below the 80% warning line

        expense(db, 2000, "Food", at(2026, 10, 5, 12, 0));                          // 32000 of 40000 = exactly 80%
        assertEquals(BudgetMath.Status.WARNING, category(monthly(at(2026, 10, 15, 0, 0)), "Food").status());
    }

    @Test
    public void reachingAndExceedingTheLimitIsReportedButNeverBlocksRecording() throws Exception {
        budgets.saveSync(null, BudgetPeriod.MONTHLY, null, USD, 10000);
        expense(db, 10000, "Food", at(2026, 10, 3, 12, 0));
        assertEquals(BudgetMath.Status.REACHED, overall(monthly(at(2026, 10, 15, 0, 0))).status());

        // A further expense is still accepted.
        long id = expense(db, 2500, "Food", at(2026, 10, 4, 12, 0));
        assertTrue(id > 0);
        BudgetProgress p = overall(monthly(at(2026, 10, 15, 0, 0)));
        assertEquals(BudgetMath.Status.EXCEEDED, p.status());
        assertEquals(-2500, p.remainingMinor());
        assertEquals(125, p.percent());
    }

    @Test
    public void monthBoundariesAreHalfOpen() throws Exception {
        budgets.saveSync(null, BudgetPeriod.MONTHLY, null, USD, 100000);
        expense(db, 1, "Food", at(2026, 10, 1, 0, 0));              // first instant: in
        expense(db, 10, "Food", at(2026, 10, 31, 23, 59));          // last minute: in
        expense(db, 100, "Food", at(2026, 11, 1, 0, 0));            // next month: out
        expense(db, 1000, "Food", at(2026, 9, 30, 23, 59));         // previous month: out
        assertEquals(11, overall(monthly(at(2026, 10, 15, 0, 0))).spentMinor);
        assertEquals(100, overall(monthly(at(2026, 11, 15, 0, 0))).spentMinor);
        assertEquals(1000, overall(monthly(at(2026, 9, 15, 0, 0))).spentMinor);
    }

    @Test
    public void weeklyBudgetUsesTheLocalWeek() throws Exception {
        budgets.saveSync(null, BudgetPeriod.WEEKLY, null, USD, 50000);
        // Week of Mon 19 Oct to Sun 25 Oct 2026 (Monday first).
        expense(db, 100, "Food", at(2026, 10, 18, 23, 59));         // Sunday before: out
        expense(db, 200, "Food", at(2026, 10, 19, 0, 0));           // Monday 00:00: in
        expense(db, 400, "Food", at(2026, 10, 25, 23, 59));         // Sunday night: in
        expense(db, 800, "Food", at(2026, 10, 26, 0, 0));           // next Monday: out

        List<BudgetProgress> week = budgets.progressSync(BudgetPeriod.WEEKLY, USD,
                at(2026, 10, 22, 15, 0), UTC, Calendar.MONDAY);
        assertEquals(600, week.get(0).spentMinor);
        assertEquals(at(2026, 10, 19, 0, 0), week.get(0).periodStart);
        assertEquals(at(2026, 10, 26, 0, 0), week.get(0).periodEndExclusive);

        // Sunday-first calendars see a different week for the same instants.
        List<BudgetProgress> sundayWeek = budgets.progressSync(BudgetPeriod.WEEKLY, USD,
                at(2026, 10, 22, 15, 0), UTC, Calendar.SUNDAY);
        assertEquals(at(2026, 10, 18, 0, 0), sundayWeek.get(0).periodStart);
        assertEquals(300, sundayWeek.get(0).spentMinor); // Sun 18 Oct to Sat 24 Oct holds the 100 and the 200
    }

    @Test
    public void progressFollowsEditsAndDeletionsOfTransactions() throws Exception {
        budgets.saveSync(null, BudgetPeriod.MONTHLY, null, USD, 100000);
        TransactionRepository tx = new TransactionRepository(db);
        long a = expense(db, 20000, "Food", at(2026, 10, 3, 12, 0));
        long b = expense(db, 30000, "Food", at(2026, 10, 4, 12, 0));
        assertEquals(50000, overall(monthly(at(2026, 10, 15, 0, 0))).spentMinor);

        tx.updateSync(a, new TransactionInput(TransactionType.EXPENSE, 25000, USD,
                categoryId(db, TransactionType.EXPENSE, "Food"), null, at(2026, 10, 3, 12, 0), null));
        assertEquals(55000, overall(monthly(at(2026, 10, 15, 0, 0))).spentMinor);

        // Moving an expense into another month removes it from this month's budget.
        tx.updateSync(b, new TransactionInput(TransactionType.EXPENSE, 30000, USD,
                categoryId(db, TransactionType.EXPENSE, "Food"), null, at(2026, 9, 10, 12, 0), null));
        assertEquals(25000, overall(monthly(at(2026, 10, 15, 0, 0))).spentMinor);

        tx.deleteSync(a);
        assertEquals(0, overall(monthly(at(2026, 10, 15, 0, 0))).spentMinor);
    }

    @Test
    public void changingAnExpenseToIncomeRemovesItFromSpending() throws Exception {
        budgets.saveSync(null, BudgetPeriod.MONTHLY, null, USD, 100000);
        long id = expense(db, 20000, "Food", at(2026, 10, 3, 12, 0));
        new TransactionRepository(db).updateSync(id, new TransactionInput(TransactionType.INCOME, 20000, USD,
                categoryId(db, TransactionType.INCOME, "Gift"), null, at(2026, 10, 3, 12, 0), null));
        assertEquals(0, overall(monthly(at(2026, 10, 15, 0, 0))).spentMinor);
    }

    @Test
    public void currenciesAreNeverCombined() throws Exception {
        budgets.saveSync(null, BudgetPeriod.MONTHLY, null, USD, 100000);
        expense(db, 10000, "Food", at(2026, 10, 3, 12, 0));
        TestSupport.addTransaction(db, TransactionType.EXPENSE, 777777, "Food", "JPY", at(2026, 10, 4, 12, 0));
        assertEquals(10000, overall(monthly(at(2026, 10, 15, 0, 0))).spentMinor);

        // A JPY budget is separate and only sees JPY.
        budgets.saveSync(null, BudgetPeriod.MONTHLY, null, "JPY", 900000);
        List<BudgetProgress> jpy = budgets.progressSync(BudgetPeriod.MONTHLY, "JPY",
                at(2026, 10, 15, 0, 0), UTC, Calendar.MONDAY);
        assertEquals(1, jpy.size());
        assertEquals(777777, jpy.get(0).spentMinor);
    }

    @Test
    public void pastPeriodsCanBeReviewed() throws Exception {
        budgets.saveSync(null, BudgetPeriod.MONTHLY, null, USD, 100000);
        expense(db, 40000, "Food", at(2026, 8, 10, 12, 0));
        expense(db, 60000, "Food", at(2026, 9, 10, 12, 0));
        assertEquals(40000, overall(monthly(at(2026, 8, 20, 0, 0))).spentMinor);
        assertEquals(60000, overall(monthly(at(2026, 9, 20, 0, 0))).spentMinor);
        assertEquals(0, overall(monthly(at(2026, 7, 20, 0, 0))).spentMinor);
    }

    @Test
    public void emptyPeriodsAndNoBudgetsDoNotCrash() throws Exception {
        assertTrue(monthly(at(2026, 10, 15, 0, 0)).isEmpty());
        budgets.saveSync(null, BudgetPeriod.MONTHLY, null, USD, 100000);
        BudgetProgress p = overall(monthly(at(2026, 10, 15, 0, 0)));
        assertEquals(0, p.spentMinor);
        assertEquals(0, p.percent());
        assertEquals(100000, p.remainingMinor());
    }

    @Test
    public void onlyOneBudgetPerPeriodCategoryAndCurrency() throws Exception {
        long food = categoryId(db, TransactionType.EXPENSE, "Food");
        budgets.saveSync(null, BudgetPeriod.MONTHLY, null, USD, 100000);
        budgets.saveSync(null, BudgetPeriod.MONTHLY, food, USD, 30000);
        expectRejected(ValidationException.Reason.BUDGET_DUPLICATE, null,
                () -> budgets.saveSync(null, BudgetPeriod.MONTHLY, null, USD, 5000));
        expectRejected(ValidationException.Reason.BUDGET_DUPLICATE, null,
                () -> budgets.saveSync(null, BudgetPeriod.MONTHLY, food, USD, 5000));
        // Same category in the weekly period, or the same period in another currency, is allowed.
        budgets.saveSync(null, BudgetPeriod.WEEKLY, food, USD, 5000);
        budgets.saveSync(null, BudgetPeriod.MONTHLY, null, "EUR", 5000);
        assertEquals(4, db.budgetDao().count());
    }

    @Test
    public void editingAndDeletingBudgets() throws Exception {
        long id = budgets.saveSync(null, BudgetPeriod.MONTHLY, null, USD, 100000);
        long same = budgets.saveSync(id, BudgetPeriod.MONTHLY, null, USD, 150000);
        assertEquals(id, same);
        assertEquals(150000, db.budgetDao().getById(id).amountMinor);
        assertEquals(1, db.budgetDao().count());

        assertTrue(budgets.deleteSync(id));
        assertNull(db.budgetDao().getById(id));
        assertTrue(!budgets.deleteSync(id));
    }

    @Test
    public void invalidBudgetsAreRejected() throws Exception {
        expectRejected(ValidationException.Reason.AMOUNT_NOT_POSITIVE, null,
                () -> budgets.saveSync(null, BudgetPeriod.MONTHLY, null, USD, 0));
        expectRejected(ValidationException.Reason.AMOUNT_NOT_POSITIVE, null,
                () -> budgets.saveSync(null, BudgetPeriod.MONTHLY, null, USD, -5));
        expectRejected(ValidationException.Reason.AMOUNT_TOO_LARGE, null,
                () -> budgets.saveSync(null, BudgetPeriod.MONTHLY, null, USD, Long.MAX_VALUE));
        // Budgets are for expenses; an income category is not allowed.
        expectRejected(ValidationException.Reason.BUDGET_CATEGORY_INVALID, null,
                () -> budgets.saveSync(null, BudgetPeriod.MONTHLY,
                        categoryId(db, TransactionType.INCOME, "Salary"), USD, 1000));
        expectRejected(ValidationException.Reason.BUDGET_CATEGORY_INVALID, null,
                () -> budgets.saveSync(null, BudgetPeriod.MONTHLY, 99999L, USD, 1000));
        assertEquals(0, db.budgetDao().count());
    }

    @Test
    public void deletingAnUnusedCategoryRemovesItsBudgetButKeepsOthers() throws Exception {
        com.litemobiletools.dailyexpensetracker.data.repository.CategoryRepository categories =
                new com.litemobiletools.dailyexpensetracker.data.repository.CategoryRepository(db);
        long pets = categories.addSync("Pets", TransactionType.EXPENSE, "pets", null);
        budgets.saveSync(null, BudgetPeriod.MONTHLY, pets, USD, 5000);
        budgets.saveSync(null, BudgetPeriod.MONTHLY, null, USD, 100000);
        categories.deleteSync(pets);
        assertEquals(1, db.budgetDao().count());
        assertNotNull(db.budgetDao().find(BudgetPeriod.MONTHLY, null, USD));
    }

    @Test
    public void dashboardShowsTheOverallBudgetAndTheMostUsedCategoryBudgets() throws Exception {
        long now = System.currentTimeMillis();
        budgets.saveSync(null, BudgetPeriod.MONTHLY, null, USD, 100000);
        budgets.saveSync(null, BudgetPeriod.MONTHLY, categoryId(db, TransactionType.EXPENSE, "Food"), USD, 10000);
        budgets.saveSync(null, BudgetPeriod.MONTHLY, categoryId(db, TransactionType.EXPENSE, "Bills"), USD, 10000);
        expense(db, 9000, "Food", now);
        expense(db, 2000, "Bills", now);

        DashboardSnapshot snapshot = new DashboardRepository(db).loadSync(USD, "Other", now, java.util.TimeZone.getDefault());
        assertEquals(100000, snapshot.monthBudgetMinor);
        assertEquals(11000, snapshot.monthExpenseMinor);
        assertEquals(2, snapshot.budgetHighlights.size());
        assertEquals("Food", snapshot.budgetHighlights.get(0).categoryName); // 90% before 20%
    }
}
