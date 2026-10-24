package com.litemobiletools.dailyexpensetracker.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AmountParserTest {

    @Test
    public void parsesWholeAndDecimalAmounts() {
        assertEquals(1250, AmountParser.parse("12.50").minorUnits);
        assertEquals(1200, AmountParser.parse("12").minorUnits);
        assertEquals(5, AmountParser.parse("0.05").minorUnits);
        assertEquals(1050, AmountParser.parse(" 10.5 ").minorUnits);
    }

    @Test
    public void acceptsDecimalComma() {
        AmountParser.Result result = AmountParser.parse("7,25");
        assertTrue(result.isValid());
        assertEquals(725, result.minorUnits);
    }

    @Test
    public void rejectsEmptyInput() {
        assertEquals(AmountParser.Error.EMPTY, AmountParser.parse(null).error);
        assertEquals(AmountParser.Error.EMPTY, AmountParser.parse("  ").error);
    }

    @Test
    public void rejectsInvalidText() {
        assertEquals(AmountParser.Error.INVALID, AmountParser.parse("abc").error);
        assertEquals(AmountParser.Error.INVALID, AmountParser.parse("1.2.3").error);
    }

    @Test
    public void rejectsZeroAndNegative() {
        assertEquals(AmountParser.Error.NOT_POSITIVE, AmountParser.parse("0").error);
        assertEquals(AmountParser.Error.NOT_POSITIVE, AmountParser.parse("0.00").error);
        assertEquals(AmountParser.Error.NOT_POSITIVE, AmountParser.parse("-5").error);
    }

    @Test
    public void rejectsMoreThanTwoDecimalsInsteadOfRounding() {
        assertEquals(AmountParser.Error.TOO_MANY_DECIMALS, AmountParser.parse("1.234").error);
        assertEquals(AmountParser.Error.TOO_MANY_DECIMALS, AmountParser.parse("0.001").error);
        assertTrue(AmountParser.parse("1.230").isValid());
    }

    @Test
    public void rejectsOversizedAmounts() {
        assertEquals(AmountParser.Error.TOO_LARGE, AmountParser.parse("1000000000").error);
        assertEquals(99999999999L, AmountParser.parse("999999999.99").minorUnits);
    }

    @Test
    public void zeroDecimalCurrencyRejectsFractions() {
        assertEquals(500, AmountParser.parse("500", 0).minorUnits);
        assertEquals(AmountParser.Error.TOO_MANY_DECIMALS, AmountParser.parse("500.5", 0).error);
        assertEquals(999999999L, AmountParser.parse("999999999", 0).minorUnits);
        assertEquals(AmountParser.Error.TOO_LARGE, AmountParser.parse("1000000000", 0).error);
    }

    @Test
    public void threeDecimalCurrencyKeepsPrecision() {
        assertEquals(12345, AmountParser.parse("12.345", 3).minorUnits);
        assertEquals(AmountParser.Error.TOO_MANY_DECIMALS, AmountParser.parse("12.3456", 3).error);
    }

    @Test
    public void maxMinorUnitsMatchesDigits() {
        assertEquals(999999999L, AmountParser.maxMinorUnits(0));
        assertEquals(99999999999L, AmountParser.maxMinorUnits(2));
        assertEquals(999999999999L, AmountParser.maxMinorUnits(3));
    }
}
