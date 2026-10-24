package com.litemobiletools.dailyexpensetracker.util;

import androidx.annotation.NonNull;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

/**
 * The single currency-formatting utility. Amounts are integer minor units; the number of minor-unit
 * digits comes from ISO 4217 via {@link Currency#getDefaultFractionDigits()} (2 for USD, 0 for JPY,
 * 3 for KWD). Every formatter here is explicit about its currency — no method hides it away — and
 * all of them only change how a value is displayed: no method ever converts, rescales or rewrites
 * an amount. The currency a screen works in is the user's choice (see AppPreferences), whose
 * default is {@link #currentCode()}, the device locale's currency.
 */
public final class CurrencyFormatter {

    private static final String FALLBACK_CODE = "USD";

    private CurrencyFormatter() {
    }

    /**
     * ISO code of the device locale's currency, or USD if the locale has none. Used only as the
     * default of the currency preference, never as a formatting fallback.
     */
    @NonNull
    public static String currentCode() {
        try {
            Currency currency = Currency.getInstance(Locale.getDefault());
            if (currency != null) {
                return currency.getCurrencyCode();
            }
        } catch (IllegalArgumentException ignored) {
            // Locale without a country: fall through.
        }
        return FALLBACK_CODE;
    }

    /** Minor-unit digits for a currency; pseudo-currencies (reported as -1) use 2. */
    public static int fractionDigits(@NonNull String currencyCode) {
        int digits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
        return digits < 0 ? 2 : digits;
    }

    @NonNull
    public static String format(long amountMinor, @NonNull String currencyCode) {
        Currency currency = Currency.getInstance(currencyCode);
        int digits = fractionDigits(currencyCode);
        NumberFormat format = NumberFormat.getCurrencyInstance(Locale.getDefault());
        format.setCurrency(currency);
        format.setMinimumFractionDigits(digits);
        format.setMaximumFractionDigits(digits);
        return format.format(BigDecimal.valueOf(amountMinor, digits));
    }

    @NonNull
    public static String symbol(@NonNull String currencyCode) {
        return Currency.getInstance(currencyCode).getSymbol(Locale.getDefault());
    }

    /** Plain editable text for an amount, e.g. 1250 USD -> "12.50" (no grouping, no symbol). */
    @NonNull
    public static String plainAmount(long amountMinor, @NonNull String currencyCode) {
        return BigDecimal.valueOf(amountMinor, fractionDigits(currencyCode)).toPlainString();
    }
}
