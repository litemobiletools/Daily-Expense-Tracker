package com.litemobiletools.dailyexpensetracker.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.math.BigDecimal;

/**
 * Parses and validates user-entered money amounts into minor units. Input is never rounded:
 * more decimals than the currency allows is an error.
 */
public final class AmountParser {

    public enum Error {
        NONE,
        EMPTY,
        INVALID,
        NOT_POSITIVE,
        TOO_MANY_DECIMALS,
        TOO_LARGE
    }

    public static final class Result {
        @NonNull public final Error error;
        public final long minorUnits;

        private Result(@NonNull Error error, long minorUnits) {
            this.error = error;
            this.minorUnits = minorUnits;
        }

        public boolean isValid() {
            return error == Error.NONE;
        }
    }

    /** Largest accepted amount in whole currency units. */
    private static final long MAX_WHOLE_UNITS = 999_999_999L;

    private AmountParser() {
    }

    /** Largest accepted amount in minor units for a currency with {@code fractionDigits} digits. */
    public static long maxMinorUnits(int fractionDigits) {
        long max = MAX_WHOLE_UNITS;
        for (int i = 0; i < fractionDigits; i++) {
            max *= 10;
        }
        return max + (fractionDigits > 0 ? maxFraction(fractionDigits) : 0);
    }

    private static long maxFraction(int fractionDigits) {
        long fraction = 1;
        for (int i = 0; i < fractionDigits; i++) {
            fraction *= 10;
        }
        return fraction - 1;
    }

    /** Parses for a two-digit currency. */
    @NonNull
    public static Result parse(@Nullable String raw) {
        return parse(raw, 2);
    }

    @NonNull
    public static Result parse(@Nullable String raw, int fractionDigits) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return new Result(Error.EMPTY, 0);
        }
        // Some keyboards produce a decimal comma.
        if (text.indexOf('.') < 0) {
            text = text.replace(',', '.');
        }
        BigDecimal value;
        try {
            value = new BigDecimal(text);
        } catch (NumberFormatException e) {
            return new Result(Error.INVALID, 0);
        }
        if (value.signum() <= 0) {
            return new Result(Error.NOT_POSITIVE, 0);
        }
        if (value.stripTrailingZeros().scale() > fractionDigits) {
            return new Result(Error.TOO_MANY_DECIMALS, 0);
        }
        BigDecimal minor = value.movePointRight(fractionDigits);
        if (minor.compareTo(BigDecimal.valueOf(maxMinorUnits(fractionDigits))) > 0) {
            return new Result(Error.TOO_LARGE, 0);
        }
        return new Result(Error.NONE, minor.longValueExact());
    }
}
