package com.litemobiletools.dailyexpensetracker.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CurrencyFormatterTest {

    @Test
    public void fractionDigitsFollowIso4217() {
        assertEquals(2, CurrencyFormatter.fractionDigits("USD"));
        assertEquals(0, CurrencyFormatter.fractionDigits("JPY"));
        assertEquals(3, CurrencyFormatter.fractionDigits("KWD"));
    }

    @Test
    public void formatsMinorUnitsWithoutFloatingPointError() {
        String usd = CurrencyFormatter.format(1250, "USD");
        assertTrue(usd, usd.contains("12.50") || usd.contains("12,50"));
        String yen = CurrencyFormatter.format(500, "JPY");
        assertTrue(yen, yen.contains("500") && !yen.contains(".5"));
    }

    @Test
    public void currentCodeIsAValidCurrency() {
        String code = CurrencyFormatter.currentCode();
        assertEquals(3, code.length());
        CurrencyFormatter.fractionDigits(code);
    }
}
