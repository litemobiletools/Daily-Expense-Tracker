package com.litemobiletools.dailyexpensetracker.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Currency;
import java.util.List;
import java.util.Locale;

/** The supported currency list: validity, ordering preference, no duplicates, readable labels. */
public class CurrencyChoicesTest {

    @Test
    public void baseListIsCuratedAndValid() {
        List<String> codes = CurrencyChoices.codes(null);
        // Every offered code is a real ISO 4217 currency.
        for (String code : codes) {
            Currency.getInstance(code); // throws on invalid
        }
        // No duplicates: codes() starts from a set.
        assertEquals(codes.size(), new java.util.HashSet<>(codes).size());
        assertTrue(codes.size() >= 54);
    }

    @Test
    public void majorCurrenciesAreOffered() {
        List<String> codes = CurrencyChoices.codes(null);
        assertTrue(codes.contains("USD"));
        assertTrue(codes.contains("EUR"));
        assertTrue(codes.contains("JPY"));
        assertTrue(codes.contains("GBP"));
        assertTrue(codes.contains("INR"));
    }

    @Test
    public void theDeviceLocaleCurrencyIsAlwaysSelectable() {
        List<String> codes = CurrencyChoices.codes(null);
        String localeCode = CurrencyFormatter.currentCode();
        assertTrue(codes.contains(localeCode));
    }

    @Test
    public void preferredCodeMovesToFront() {
        List<String> codes = CurrencyChoices.codes("JPY");
        assertEquals("JPY", codes.get(0));
        // Still only once in the list.
        assertEquals(1, codes.stream().filter("JPY"::equals).count());
    }

    @Test
    public void unknownCodeOutsideTheCuratedSetIsAppendedNotRejected() {
        // A valid-but-rare code the user is allowed to keep using.
        List<String> codes = CurrencyChoices.codes("FJD");
        assertTrue(codes.contains("FJD"));
        assertEquals("FJD", codes.get(codes.size() - 1));
    }

    @Test
    public void invalidCodeNeverBreaksTheList() {
        List<String> codes = CurrencyChoices.codes("NOT_A_CODE");
        assertFalse(codes.contains("NOT_A_CODE"));
        assertTrue(codes.size() >= 54);
    }

    @Test
    public void nonsenseCodeIsRejectedByTheUnderlyingCurrencyApi() {
        try {
            Currency.getInstance("ZZZ");
            assertTrue(false);
        } catch (IllegalArgumentException expected) {
            // Normal: a three-letter nonsense code is rejected.
        }
    }

    @Test
    public void labelsContainCodeAndAreHumanReadable() {
        String usd = CurrencyChoices.label("USD", Locale.US);
        assertTrue(usd.contains("USD"));
        assertTrue(usd.contains("$"));
        String jpy = CurrencyChoices.label("JPY", Locale.US);
        assertTrue(jpy.contains("JPY"));
        // The first letter of the display name is capitalized.
        assertEquals(usd.charAt(0), Character.toUpperCase(usd.charAt(0)));
    }
}
