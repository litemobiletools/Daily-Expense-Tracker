package com.litemobiletools.dailyexpensetracker.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * The curated set of display currencies the Settings screen offers (Phase 9). A concise list of
 * widely used ISO 4217 codes keeps the picker usable on small screens and with large fonts; it is
 * not a claim that other currencies are invalid — any single transaction still carries its own
 * currency code, recorded on creation. The currency the device locale already uses is always
 * appended, so the preselected choice is never missing. Changing the selection never converts or
 * alters any stored amounts.
 */
public final class CurrencyChoices {

    private CurrencyChoices() {
    }

    /** Major ISO 4217 codes, uppercase, no duplicates and no pseudo-currencies. */
    private static final String[] CODES = {
            "USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "NZD", "CNY", "HKD",
            "SGD", "TWD", "KRW", "INR", "IDR", "MYR", "PHP", "THB", "VND", "PKR",
            "BDT", "LKR", "AED", "SAR", "QAR", "KWD", "BHD", "OMR", "ILS", "TRY",
            "RUB", "UAH", "PLN", "CZK", "HUF", "RON", "SEK", "NOK", "DKK", "ISK",
            "MXN", "BRL", "ARS", "CLP", "COP", "PEN", "ZAR", "EGP", "NGN", "KES",
            "GHS", "MAD", "ETB", "TZS"
    };

    /**
     * The display list: the preferred code first when present in the curated set, then every other
     * code in alphabetical order. Unknown-but-valid stored codes (or a locale currency outside the
     * curated set) are appended at the end so the current choice is always selectable.
     */
    @NonNull
    public static List<String> codes(@Nullable String preferredCode) {
        TreeSet<String> sorted = new TreeSet<>(Arrays.asList(CODES));
        List<String> result = new ArrayList<>(sorted);
        if (preferredCode != null) {
            try {
                Currency.getInstance(preferredCode);
                int index = result.indexOf(preferredCode);
                if (index > 0) {
                    result.remove(index);
                    result.add(0, preferredCode);
                } else if (index < 0) {
                    result.add(preferredCode);
                }
            } catch (IllegalArgumentException ignored) {
                // Never add a code that is not a real currency; the fallback default is valid.
            }
        }
        // The device's own currency remains selectable even without a stored preference.
        String localeCode = CurrencyFormatter.currentCode();
        if (!result.contains(localeCode)) {
            result.add(localeCode);
        }
        return result;
    }

    /** One dialog line, e.g. "United States Dollar (USD) — $". Purely cosmetic and JVM-testable. */
    @NonNull
    public static String label(@NonNull String currencyCode, @NonNull Locale locale) {
        Currency currency = Currency.getInstance(currencyCode);
        String display = upWords(currency.getDisplayName(locale), locale);
        String symbol = currency.getSymbol(locale);
        if (symbol != null && !symbol.equals(currencyCode)) {
            return display + " (" + currencyCode + ") — " + symbol;
        }
        return display + " (" + currencyCode + ")";
    }

    /** Uppercases the first letter of each word without depending on the default locale. */
    @NonNull
    private static String upWords(@NonNull String text, @NonNull Locale locale) {
        if (text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        boolean start = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(start && Character.isLetter(c)
                    ? Character.toUpperCase(c) : start ? Character.toLowerCase(c) : c);
            start = Character.isWhitespace(c);
        }
        return out.toString();
    }
}
