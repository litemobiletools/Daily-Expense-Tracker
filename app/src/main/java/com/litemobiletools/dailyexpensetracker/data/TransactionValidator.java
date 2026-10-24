package com.litemobiletools.dailyexpensetracker.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.litemobiletools.dailyexpensetracker.util.AmountParser;
import com.litemobiletools.dailyexpensetracker.util.CurrencyFormatter;

import java.util.Currency;

/** Database-independent checks. Nothing is rounded or altered: invalid input is rejected. */
public final class TransactionValidator {

    public static final int MAX_NOTE_LENGTH = 100;

    private TransactionValidator() {
    }

    /** @throws ValidationException if any field is unacceptable */
    public static void validate(@NonNull TransactionInput input) throws ValidationException {
        if (input.type == null) {
            throw new ValidationException(ValidationException.Reason.INVALID_TYPE);
        }
        try {
            Currency.getInstance(input.currencyCode);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(ValidationException.Reason.INVALID_CURRENCY);
        }
        if (input.amountMinor <= 0) {
            throw new ValidationException(ValidationException.Reason.AMOUNT_NOT_POSITIVE);
        }
        int digits = CurrencyFormatter.fractionDigits(input.currencyCode);
        if (input.amountMinor > AmountParser.maxMinorUnits(digits)) {
            throw new ValidationException(ValidationException.Reason.AMOUNT_TOO_LARGE);
        }
        if (input.timestampMillis <= 0) {
            throw new ValidationException(ValidationException.Reason.INVALID_DATE);
        }
        if (input.note != null && input.note.trim().length() > MAX_NOTE_LENGTH) {
            throw new ValidationException(ValidationException.Reason.NOTE_TOO_LONG);
        }
    }

    /** Trims the note for storage; a blank note becomes null. */
    @Nullable
    public static String normalizeNote(@Nullable String note) {
        if (note == null) {
            return null;
        }
        String trimmed = note.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
