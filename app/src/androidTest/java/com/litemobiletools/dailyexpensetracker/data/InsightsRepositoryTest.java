package com.litemobiletools.dailyexpensetracker.data;

import static com.litemobiletools.dailyexpensetracker.data.TestSupport.USD;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.UTC;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.at;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.categoryId;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.expense;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.income;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.litemobiletools.dailyexpensetracker.data.repository.BudgetRepository;
import com.litemobiletools.dailyexpensetracker.data.repository.InsightsRepository;
import com.litemobiletools.dailyexpensetracker.model.BudgetPeriod;
import com.litemobiletools.dailyexpensetracker.model.MonthInsights;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class InsightsRepositoryTest {

    private AppDatabase db;
    private InsightsRepository insights;

    @Before
    public void setUp() {
        db = TestSupport.newDatabase();
        insights = new InsightsRepository(db);
    }

    @After
    public void tearDown() {
        db.close();
    }

    private MonthInsights october(long now) {
        return insights.loadSync(USD, at(2026, 10, 15, 0, 0), now, UTC);
    }

    @Test
    public void totalsComparisonAndSavingsRateMatchTheRecordedTransactions() throws Exception {
        income(db, 300000, "Salary", at(2026, 10, 1, 9, 0));
        expense(db, 50000, "Food", at(2026, 10, 2, 12, 0));
        expense(db, 100000, "Bills", at(2026, 10, 5, 12, 0));
        expense(db, 100000, "Food", at(2026, 9, 5, 12, 0));      // last month
        income(db, 999, "Gift", at(2026, 9, 6, 12, 0));

        MonthInsights m = october(at(2026, 11, 3, 0, 0));        // October is over
        assertEquals(300000, m.incomeMinor);
        assertEquals(150000, m.expenseMinor);
        assertEquals(100000, m.prevExpenseMinor);
        assertEquals(Integer.valueOf(50), m.expenseChangePercent);
        assertEquals(Integer.valueOf(50), m.savingsRatePercent);  // (300000-150000)/300000
        assertEquals(31, m.daysCounted);
        assertEquals(150000 / 31, m.averageDailyExpenseMinor);
    }

    @Test
    public void averageUsesElapsedDaysForTheCurrentMonthAndAllDaysForPastMonths() throws Exception {
        expense(db, 9000, "Food", at(2026, 10, 2, 12, 0));
        MonthInsights midMonth = october(at(2026, 10, 10, 15, 0));
        assertEquals(10, midMonth.daysCounted);
        assertEquals(900, midMonth.averageDailyExpenseMinor);

        assertEquals(31, october(at(2026, 12, 1, 0, 0)).daysCounted);
        assertEquals(0, october(at(2026, 9, 1, 0, 0)).daysCounted);    // a month that has not started
        assertEquals(0, october(at(2026, 9, 1, 0, 0)).averageDailyExpenseMinor);
    }

    @Test
    public void topCategoriesLargestExpensesAndHighestDaysAreRankedCorrectly() throws Exception {
        expense(db, 1000, "Food", at(2026, 10, 2, 8, 0));
        expense(db, 2500, "Food", at(2026, 10, 2, 20, 0));           // 2 Oct total 3500
        expense(db, 9000, "Bills", at(2026, 10, 7, 12, 0));          // 7 Oct total 9000
        expense(db, 3000, "Shopping", at(2026, 10, 9, 12, 0));       // 9 Oct total 3000
        expense(db, 100, "Other", at(2026, 10, 11, 12, 0));
        income(db, 77777, "Salary", at(2026, 10, 3, 12, 0));          // income is not spending

        MonthInsights m = october(at(2026, 11, 3, 0, 0));
        assertEquals("Bills", m.topCategories.get(0).categoryName);
        assertEquals(9000, m.topCategories.get(0).totalMinor);
        assertEquals("Food", m.topCategories.get(1).categoryName);
        assertEquals(3500, m.topCategories.get(1).totalMinor);

        assertEquals(9000, m.largestExpenses.get(0).amountMinor);
        assertEquals(3000, m.largestExpenses.get(1).amountMinor);
        assertEquals(2500, m.largestExpenses.get(2).amountMinor);

        assertEquals(3, m.highestDays.size());
        assertEquals(at(2026, 10, 7, 0, 0), m.highestDays.get(0).dayStart);
        assertEquals(9000, m.highestDays.get(0).amountMinor);
        assertEquals(at(2026, 10, 2, 0, 0), m.highestDays.get(1).dayStart);
        assertEquals(3500, m.highestDays.get(1).amountMinor);
        assertEquals(at(2026, 10, 9, 0, 0), m.highestDays.get(2).dayStart);
    }

    @Test
    public void dailyTotalsCoverEveryDayOfTheMonthAndSumToTheExpenseTotal() throws Exception {
        expense(db, 100, "Food", at(2026, 10, 1, 0, 0));
        expense(db, 200, "Food", at(2026, 10, 1, 23, 59));
        expense(db, 400, "Food", at(2026, 10, 31, 23, 59));
        MonthInsights m = october(at(2026, 11, 3, 0, 0));
        assertEquals(31, m.dailyExpenseMinor.length);
        assertEquals(300, m.dailyExpenseMinor[0]);
        assertEquals(400, m.dailyExpenseMinor[30]);
        long sum = 0;
        for (long v : m.dailyExpenseMinor) {
            sum += v;
        }
        assertEquals(m.expenseMinor, sum);
    }

    @Test
    public void emptyMonthsAreSafe() {
        MonthInsights m = october(at(2026, 10, 20, 0, 0));
        assertEquals(0, m.incomeMinor);
        assertEquals(0, m.expenseMinor);
        assertNull(m.expenseChangePercent);
        assertNull(m.savingsRatePercent);
        assertTrue(m.topCategories.isEmpty());
        assertTrue(m.largestExpenses.isEmpty());
        assertTrue(m.highestDays.isEmpty());
        assertTrue(m.monthlyBudgets.isEmpty());
        assertEquals(0, m.averageDailyExpenseMinor);
        assertEquals(31, m.dailyExpenseMinor.length);
    }

    @Test
    public void savingsRateIsNegativeWhenSpendingExceedsIncomeAndMissingWithoutIncome() throws Exception {
        expense(db, 1000, "Food", at(2026, 10, 2, 12, 0));
        assertNull(october(at(2026, 11, 3, 0, 0)).savingsRatePercent);
        income(db, 500, "Gift", at(2026, 10, 3, 12, 0));
        assertEquals(Integer.valueOf(-100), october(at(2026, 11, 3, 0, 0)).savingsRatePercent);
    }

    @Test
    public void otherCurrenciesAreExcluded() throws Exception {
        expense(db, 1000, "Food", at(2026, 10, 2, 12, 0));
        TestSupport.addTransaction(db, TransactionType.EXPENSE, 555555, "Food", "JPY", at(2026, 10, 2, 12, 0));
        MonthInsights m = october(at(2026, 11, 3, 0, 0));
        assertEquals(1000, m.expenseMinor);
        assertEquals(1, m.largestExpenses.size());
    }

    @Test
    public void monthlyBudgetsAreEvaluatedForTheSelectedMonth() throws Exception {
        BudgetRepository budgets = new BudgetRepository(db);
        budgets.saveSync(null, BudgetPeriod.MONTHLY, categoryId(db, TransactionType.EXPENSE, "Food"), USD, 10000);
        expense(db, 9500, "Food", at(2026, 10, 2, 12, 0));
        expense(db, 5000, "Food", at(2026, 9, 2, 12, 0));
        MonthInsights m = october(at(2026, 11, 3, 0, 0));
        assertEquals(1, m.monthlyBudgets.size());
        assertEquals(9500, m.monthlyBudgets.get(0).spentMinor);
        assertEquals(95, m.monthlyBudgets.get(0).percent());
    }
}
