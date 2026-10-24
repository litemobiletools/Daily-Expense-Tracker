package com.litemobiletools.dailyexpensetracker.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import com.litemobiletools.dailyexpensetracker.model.TransactionType;

import org.junit.Test;

public class TransactionValidatorTest {

    private static TransactionInput input(TransactionType type, long amount, String currency,
                                          long timestamp, String note) {
        return new TransactionInput(type, amount, currency, 1, null, timestamp, note);
    }

    private static void assertRejected(ValidationException.Reason expected, TransactionInput input) {
        try {
            TransactionValidator.validate(input);
            fail("Expected " + expected);
        } catch (ValidationException e) {
            assertEquals(expected, e.getReason());
        }
    }

    @Test
    public void acceptsValidIncomeAndExpense() throws Exception {
        TransactionValidator.validate(input(TransactionType.INCOME, 150000, "USD", 1_700_000_000_000L, "Pay"));
        TransactionValidator.validate(input(TransactionType.EXPENSE, 1, "USD", 1_700_000_000_000L, null));
    }

    @Test
    public void rejectsZeroAndNegativeAmounts() {
        assertRejected(ValidationException.Reason.AMOUNT_NOT_POSITIVE,
                input(TransactionType.EXPENSE, 0, "USD", 1_700_000_000_000L, null));
        assertRejected(ValidationException.Reason.AMOUNT_NOT_POSITIVE,
                input(TransactionType.EXPENSE, -100, "USD", 1_700_000_000_000L, null));
    }

    @Test
    public void rejectsUnreasonablyLargeAmountsPerCurrencyPrecision() {
        assertRejected(ValidationException.Reason.AMOUNT_TOO_LARGE,
                input(TransactionType.EXPENSE, 100_000_000_000L, "USD", 1_700_000_000_000L, null));
        assertRejected(ValidationException.Reason.AMOUNT_TOO_LARGE,
                input(TransactionType.EXPENSE, 1_000_000_000L, "JPY", 1_700_000_000_000L, null));
    }

    @Test
    public void acceptsLargestAmountForZeroDecimalCurrency() throws Exception {
        TransactionValidator.validate(input(TransactionType.INCOME, 999_999_999L, "JPY", 1_700_000_000_000L, null));
    }

    @Test
    public void rejectsMissingTypeInvalidCurrencyBadDateAndLongNote() {
        assertRejected(ValidationException.Reason.INVALID_TYPE,
                input(null, 100, "USD", 1_700_000_000_000L, null));
        assertRejected(ValidationException.Reason.INVALID_CURRENCY,
                input(TransactionType.EXPENSE, 100, "ZZZ", 1_700_000_000_000L, null));
        assertRejected(ValidationException.Reason.INVALID_DATE,
                input(TransactionType.EXPENSE, 100, "USD", 0, null));
        StringBuilder longNote = new StringBuilder();
        for (int i = 0; i < 101; i++) {
            longNote.append('x');
        }
        assertRejected(ValidationException.Reason.NOTE_TOO_LONG,
                input(TransactionType.EXPENSE, 100, "USD", 1_700_000_000_000L, longNote.toString()));
    }

    @Test
    public void blankNoteBecomesNull() {
        assertNull(TransactionValidator.normalizeNote("   "));
        assertNull(TransactionValidator.normalizeNote(null));
        assertEquals("Lunch", TransactionValidator.normalizeNote("  Lunch "));
    }
}
