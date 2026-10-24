package com.litemobiletools.dailyexpensetracker.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.litemobiletools.dailyexpensetracker.model.TransactionType;

import org.junit.Test;

public class TransactionFilterTest {

    @Test
    public void emptyFilterHasNoCriteria() {
        TransactionFilter filter = new TransactionFilter();
        assertEquals(0, filter.activeCount());
        assertFalse(filter.hasCriteria());
    }

    @Test
    public void searchTextIsCriteriaButNotAFilter() {
        TransactionFilter filter = new TransactionFilter();
        filter.searchText = "lunch";
        assertEquals(0, filter.activeCount());
        assertTrue(filter.hasCriteria());
        filter.searchText = "   ";
        assertFalse(filter.hasCriteria());
    }

    @Test
    public void dateRangeAndAmountRangeCountOnceEach() {
        TransactionFilter filter = new TransactionFilter();
        filter.startMillis = 1L;
        filter.endExclusiveMillis = 2L;
        filter.minAmountMinor = 100L;
        filter.maxAmountMinor = 900L;
        assertEquals(2, filter.activeCount());
        filter.type = TransactionType.EXPENSE;
        filter.categoryId = 3L;
        filter.paymentMethodId = 4L;
        assertEquals(5, filter.activeCount());
    }

    @Test
    public void onlyOneBoundStillCounts() {
        TransactionFilter filter = new TransactionFilter();
        filter.maxAmountMinor = 500L;
        assertEquals(1, filter.activeCount());
        filter = new TransactionFilter();
        filter.endExclusiveMillis = 5L;
        assertEquals(1, filter.activeCount());
    }

    @Test
    public void copyIsIndependentAndWithoutSearchKeepsFilters() {
        TransactionFilter filter = new TransactionFilter();
        filter.type = TransactionType.INCOME;
        filter.searchText = "salary";
        TransactionFilter copy = filter.copy();
        copy.type = null;
        assertEquals(TransactionType.INCOME, filter.type);
        TransactionFilter cleared = filter.withoutSearch();
        assertEquals("", cleared.searchText);
        assertEquals(TransactionType.INCOME, cleared.type);
        assertEquals("salary", filter.searchText);
    }
}
