package com.litemobiletools.dailyexpensetracker.data;

import static com.litemobiletools.dailyexpensetracker.data.TestSupport.USD;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.UTC;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.at;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.categoryId;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.day;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.expense;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.income;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.paymentId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.litemobiletools.dailyexpensetracker.data.entity.CategoryTotal;
import com.litemobiletools.dailyexpensetracker.data.repository.BudgetRepository;
import com.litemobiletools.dailyexpensetracker.data.repository.CategoryRepository;
import com.litemobiletools.dailyexpensetracker.data.repository.GoalRepository;
import com.litemobiletools.dailyexpensetracker.data.repository.RecurringRepository;
import com.litemobiletools.dailyexpensetracker.data.repository.ReportRepository;
import com.litemobiletools.dailyexpensetracker.data.repository.TransactionRepository;
import com.litemobiletools.dailyexpensetracker.model.BudgetPeriod;
import com.litemobiletools.dailyexpensetracker.model.RecurrenceFrequency;
import com.litemobiletools.dailyexpensetracker.model.ReportData;
import com.litemobiletools.dailyexpensetracker.model.ReportPreset;
import com.litemobiletools.dailyexpensetracker.model.ReportRange;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;
import com.litemobiletools.dailyexpensetracker.util.ReportPeriods;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;

@RunWith(AndroidJUnit4.class)
public class ReportRepositoryTest {

    private AppDatabase db;
    private ReportRepository reports;

    @Before
    public void setUp() {
        db = TestSupport.newDatabase();
        reports = new ReportRepository(db);
    }

    @After
    public void tearDown() {
        db.close();
    }

    private ReportData load(ReportPreset preset, long now) {
        return reports.loadSync(USD, ReportPeriods.resolve(preset, now, UTC, Calendar.MONDAY), now, UTC, Calendar.MONDAY);
    }

    private ReportData load(ReportRange range, long now) {
        return reports.loadSync(USD, range, now, UTC, Calendar.MONDAY);
    }

    private long withPayment(TransactionType type, long minor, String category, String payment, long when) throws Exception {
        return new TransactionRepository(db).insertSync(new TransactionInput(type, minor, USD,
                categoryId(db, type, category), payment == null ? null : paymentId(db, payment), when, null));
    }

    @Test
    public void thisMonthTotalsNetAndPreviousMonthComparison() throws Exception {
        income(db, 300000, "Salary", at(2026, 10, 1, 9, 0));
        expense(db, 50000, "Food", at(2026, 10, 3, 12, 0));
        expense(db, 25000, "Bills", at(2026, 10, 31, 23, 59));
        expense(db, 100000, "Food", at(2026, 9, 15, 12, 0));     // previous month
        income(db, 200000, "Salary", at(2026, 9, 1, 9, 0));
        expense(db, 99, "Food", at(2026, 11, 1, 0, 0));           // next month: excluded

        ReportData d = load(ReportPreset.THIS_MONTH, at(2026, 10, 22, 12, 0));
        assertEquals(300000, d.incomeMinor);
        assertEquals(75000, d.expenseMinor);
        assertEquals(225000, d.netMinor());
        assertEquals(200000, d.prevIncomeMinor);
        assertEquals(100000, d.prevExpenseMinor);
        assertEquals(Integer.valueOf(50), d.incomeChangePercent);
        assertEquals(Integer.valueOf(-25), d.expenseChangePercent);
        assertEquals(22, d.daysCounted);
        assertEquals(75000 / 22, d.averageDailyExpenseMinor);
    }

    @Test
    public void thisWeekUsesTheLocalWeekBoundaries() throws Exception {
        expense(db, 100, "Food", at(2026, 10, 18, 23, 59));   // Sunday before: out
        expense(db, 200, "Food", at(2026, 10, 19, 0, 0));     // Monday 00:00: in
        expense(db, 400, "Food", at(2026, 10, 25, 23, 59));   // Sunday: in
        expense(db, 800, "Food", at(2026, 10, 26, 0, 0));     // next Monday: out
        ReportData d = load(ReportPreset.THIS_WEEK, at(2026, 10, 22, 12, 0));
        assertEquals(600, d.expenseMinor);
        assertEquals(100, d.prevExpenseMinor);
        assertEquals(7, d.dailyExpenseMinor.length);
    }

    @Test
    public void previousMonthAndCustomRangesIncludeTheirWholeLastDay() throws Exception {
        expense(db, 1, "Food", at(2026, 9, 30, 23, 59));
        expense(db, 10, "Food", at(2026, 10, 1, 0, 0));
        ReportData prev = load(ReportPreset.PREVIOUS_MONTH, at(2026, 10, 22, 12, 0));
        assertEquals(1, prev.expenseMinor);

        expense(db, 100, "Food", at(2026, 10, 7, 23, 59));
        expense(db, 1000, "Food", at(2026, 10, 8, 0, 0));
        ReportRange custom = ReportPeriods.custom(day(2026, 10, 1), day(2026, 10, 7), UTC);
        ReportData d = load(custom, at(2026, 10, 22, 12, 0));
        assertEquals(110, d.expenseMinor);
        // Comparison period: the 7 days before.
        assertEquals(1, d.prevExpenseMinor);
    }

    @Test
    public void zeroPreviousPeriodGivesNoPercentage() throws Exception {
        expense(db, 5000, "Food", at(2026, 10, 3, 12, 0));
        ReportData d = load(ReportPreset.THIS_MONTH, at(2026, 10, 22, 12, 0));
        assertNull(d.expenseChangePercent);
        assertNull(d.incomeChangePercent);
    }

    @Test
    public void categoryTotalsCountEachTransactionOnceEvenWithPaymentMethods() throws Exception {
        withPayment(TransactionType.EXPENSE, 1000, "Food", "Cash", at(2026, 10, 2, 9, 0));
        withPayment(TransactionType.EXPENSE, 2000, "Food", "Debit Card", at(2026, 10, 3, 9, 0));
        withPayment(TransactionType.EXPENSE, 3000, "Food", null, at(2026, 10, 4, 9, 0));
        withPayment(TransactionType.EXPENSE, 9000, "Bills", "Bank Transfer", at(2026, 10, 5, 9, 0));
        withPayment(TransactionType.INCOME, 50000, "Salary", "Bank Transfer", at(2026, 10, 1, 9, 0));

        ReportData d = load(ReportPreset.THIS_MONTH, at(2026, 10, 22, 12, 0));
        assertTrue(ReportRepository.hasUniqueCategories(d.expenseByCategory));
        assertEquals(2, d.expenseByCategory.size());
        CategoryTotal bills = d.expenseByCategory.get(0);
        CategoryTotal food = d.expenseByCategory.get(1);
        assertEquals("Bills", bills.categoryName);
        assertEquals(9000, bills.totalMinor);
        assertEquals(1, bills.transactionCount);
        assertEquals(6000, food.totalMinor);
        assertEquals(3, food.transactionCount);
        long sum = 0;
        for (CategoryTotal t : d.expenseByCategory) {
            sum += t.totalMinor;
        }
        assertEquals(d.expenseMinor, sum);
        assertEquals(1, d.incomeByCategory.size());
        assertEquals(50000, d.incomeByCategory.get(0).totalMinor);
    }

    @Test
    public void otherCurrenciesAreExcludedAndCounted() throws Exception {
        expense(db, 1000, "Food", at(2026, 10, 2, 9, 0));
        TestSupport.addTransaction(db, TransactionType.EXPENSE, 777777, "Food", "JPY", at(2026, 10, 3, 9, 0));
        TestSupport.addTransaction(db, TransactionType.INCOME, 5000, "Gift", "EUR", at(2026, 10, 4, 9, 0));
        ReportData d = load(ReportPreset.THIS_MONTH, at(2026, 10, 22, 12, 0));
        assertEquals(1000, d.expenseMinor);
        assertEquals(0, d.incomeMinor);
        assertEquals(2, d.otherCurrencyCount);
        assertEquals(1, d.largestExpenses.size());
    }

    @Test
    public void emptyPeriodIsSafe() {
        ReportData d = load(ReportPreset.LAST_90_DAYS, at(2026, 10, 22, 12, 0));
        assertTrue(d.isEmpty());
        assertTrue(d.expenseByCategory.isEmpty());
        assertTrue(d.largestExpenses.isEmpty());
        assertNull(d.expenseChangePercent);
        assertNotNull(d.monthly);
        for (ReportData.MonthTotal m : d.monthly) {
            assertEquals(0, m.expenseMinor);
        }
        assertEquals(0, d.averageDailyExpenseMinor);
    }

    @Test
    public void deactivatedCategoriesStillCountInReports() throws Exception {
        expense(db, 4000, "Health", at(2026, 10, 2, 9, 0));
        new CategoryRepository(db).setActiveSync(categoryId(db, TransactionType.EXPENSE, "Health"), false);
        ReportData d = load(ReportPreset.THIS_MONTH, at(2026, 10, 22, 12, 0));
        assertEquals(4000, d.expenseMinor);
        assertEquals("Health", d.expenseByCategory.get(0).categoryName);
    }

    @Test
    public void shortRangesHaveDailyTrendsThatSumToTheTotal() throws Exception {
        expense(db, 100, "Food", at(2026, 10, 1, 0, 0));
        expense(db, 200, "Food", at(2026, 10, 1, 23, 59));
        expense(db, 400, "Food", at(2026, 10, 31, 23, 59));
        ReportData d = load(ReportPreset.THIS_MONTH, at(2026, 10, 22, 12, 0));
        assertNull(d.monthly);
        assertEquals(31, d.dailyExpenseMinor.length);
        assertEquals(300, d.dailyExpenseMinor[0]);
        assertEquals(400, d.dailyExpenseMinor[30]);
        long sum = 0;
        for (long v : d.dailyExpenseMinor) {
            sum += v;
        }
        assertEquals(d.expenseMinor, sum);
    }

    @Test
    public void longRangesHaveMonthlyTotalsThatSumToTheTotals() throws Exception {
        income(db, 5000, "Salary", at(2026, 1, 15, 9, 0));
        expense(db, 1000, "Food", at(2026, 1, 31, 23, 59));
        expense(db, 2000, "Food", at(2026, 2, 1, 0, 0));
        expense(db, 3000, "Food", at(2026, 12, 31, 23, 59));
        ReportData d = load(ReportPreset.THIS_YEAR, at(2026, 6, 1, 12, 0));
        assertNull(d.dailyExpenseMinor);
        assertEquals(12, d.monthly.size());
        assertEquals(1000, d.monthly.get(0).expenseMinor);
        assertEquals(5000, d.monthly.get(0).incomeMinor);
        assertEquals(2000, d.monthly.get(1).expenseMinor);
        assertEquals(3000, d.monthly.get(11).expenseMinor);
        long expenses = 0;
        for (ReportData.MonthTotal m : d.monthly) {
            expenses += m.expenseMinor;
        }
        assertEquals(d.expenseMinor, expenses);
    }

    @Test
    public void budgetsAppearOnlyForMatchingPeriods() throws Exception {
        BudgetRepository budgets = new BudgetRepository(db);
        budgets.saveSync(null, BudgetPeriod.MONTHLY, null, USD, 10000);
        budgets.saveSync(null, BudgetPeriod.WEEKLY, null, USD, 3000);
        expense(db, 2500, "Food", at(2026, 10, 20, 9, 0));
        long now = at(2026, 10, 22, 12, 0);

        ReportData month = load(ReportPreset.THIS_MONTH, now);
        assertEquals(1, month.budgets.size());
        assertEquals(BudgetPeriod.MONTHLY, month.budgets.get(0).budget.period);
        assertEquals(2500, month.budgets.get(0).spentMinor);

        ReportData week = load(ReportPreset.THIS_WEEK, now);
        assertEquals(BudgetPeriod.WEEKLY, week.budgets.get(0).budget.period);

        assertNull(load(ReportPreset.LAST_30_DAYS, now).budgets);
        assertNull(load(ReportPreset.THIS_YEAR, now).budgets);
    }

    @Test
    public void goalContributionsAndRecurringTemplatesAreNotTransactions() throws Exception {
        long now = at(2026, 10, 22, 12, 0);
        GoalRepository goals = new GoalRepository(db);
        long goal = goals.createSync("Trip", 100000, USD, null);
        goals.contributeSync(goal, 50000, null, now);
        // A recurring rule that starts in the future has not recorded anything.
        new RecurringRepository(db, null).createSync(new RecurringRepository.RuleInput(TransactionType.EXPENSE,
                120000, USD, categoryId(db, TransactionType.EXPENSE, "Bills"), null, "Rent",
                RecurrenceFrequency.MONTHLY, day(2026, 10, 30), null), now, UTC);
        expense(db, 1000, "Food", at(2026, 10, 2, 9, 0));

        ReportData d = load(ReportPreset.THIS_MONTH, now);
        assertEquals(1000, d.expenseMinor);
        assertEquals(0, d.incomeMinor);
    }

    @Test
    public void largestTransactionsAreRankedByAmount() throws Exception {
        expense(db, 300, "Food", at(2026, 10, 2, 9, 0));
        expense(db, 900, "Food", at(2026, 10, 3, 9, 0));
        expense(db, 600, "Bills", at(2026, 10, 4, 9, 0));
        income(db, 5000, "Salary", at(2026, 10, 1, 9, 0));
        ReportData d = load(ReportPreset.THIS_MONTH, at(2026, 10, 22, 12, 0));
        assertEquals(900, d.largestExpenses.get(0).amountMinor);
        assertEquals(600, d.largestExpenses.get(1).amountMinor);
        assertEquals(1, d.largestIncome.size());
    }
}
