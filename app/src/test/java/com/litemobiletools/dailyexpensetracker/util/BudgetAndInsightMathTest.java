package com.litemobiletools.dailyexpensetracker.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class BudgetAndInsightMathTest {

    @Test
    public void percentIsRoundedDownAndSafeForZero() {
        assertEquals(0, BudgetMath.percent(0, 10000));
        assertEquals(0, BudgetMath.percent(5000, 0));
        assertEquals(0, BudgetMath.percent(-5, 100));
        assertEquals(49, BudgetMath.percent(4999, 10000));
        assertEquals(50, BudgetMath.percent(5000, 10000));
        assertEquals(100, BudgetMath.percent(10000, 10000));
        assertEquals(150, BudgetMath.percent(15000, 10000));
        assertEquals(999, BudgetMath.percent(Long.MAX_VALUE / 1000, 1));
    }

    @Test
    public void statusThresholds() {
        assertEquals(BudgetMath.Status.NONE, BudgetMath.status(100, 0));
        assertEquals(BudgetMath.Status.OK, BudgetMath.status(0, 10000));
        assertEquals(BudgetMath.Status.OK, BudgetMath.status(7999, 10000));
        assertEquals(BudgetMath.Status.WARNING, BudgetMath.status(8000, 10000));
        assertEquals(BudgetMath.Status.WARNING, BudgetMath.status(9999, 10000));
        assertEquals(BudgetMath.Status.REACHED, BudgetMath.status(10000, 10000));
        assertEquals(BudgetMath.Status.EXCEEDED, BudgetMath.status(10001, 10000));
    }

    @Test
    public void remainingGoesNegativeWhenExceeded() {
        assertEquals(2500, BudgetMath.remaining(7500, 10000));
        assertEquals(0, BudgetMath.remaining(10000, 10000));
        assertEquals(-300, BudgetMath.remaining(10300, 10000));
    }

    @Test
    public void percentChangeHasNoValueWithoutAPreviousPeriod() {
        assertNull(InsightMath.percentChange(500, 0));
        assertNull(InsightMath.percentChange(0, 0));
        assertEquals(Integer.valueOf(50), InsightMath.percentChange(1500, 1000));
        assertEquals(Integer.valueOf(-25), InsightMath.percentChange(750, 1000));
        assertEquals(Integer.valueOf(-100), InsightMath.percentChange(0, 1000));
        assertEquals(Integer.valueOf(0), InsightMath.percentChange(1000, 1000));
    }

    @Test
    public void savingsRateNeedsIncome() {
        assertNull(InsightMath.savingsRatePercent(0, 500));
        assertEquals(Integer.valueOf(75), InsightMath.savingsRatePercent(2000, 500));
        assertEquals(Integer.valueOf(0), InsightMath.savingsRatePercent(2000, 2000));
        assertEquals(Integer.valueOf(-50), InsightMath.savingsRatePercent(2000, 3000));
        assertEquals(Integer.valueOf(100), InsightMath.savingsRatePercent(2000, 0));
    }

    @Test
    public void averageIsSafeForZeroDays() {
        assertEquals(0, InsightMath.averagePerDay(5000, 0));
        assertEquals(0, InsightMath.averagePerDay(5000, -1));
        assertEquals(166, InsightMath.averagePerDay(5000, 30));
        assertEquals(0, InsightMath.averagePerDay(0, 30));
    }
}
