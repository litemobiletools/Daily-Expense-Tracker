package com.litemobiletools.dailyexpensetracker.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class LikeEscaperTest {

    @Test
    public void wildcardsAreEscapedSoTheyMatchLiterally() {
        assertEquals("\\%", LikeEscaper.escape("%"));
        assertEquals("a\\_b", LikeEscaper.escape("a_b"));
        assertEquals("\\\\", LikeEscaper.escape("\\"));
        assertEquals("50\\% off\\_sale", LikeEscaper.escape("50% off_sale"));
    }

    @Test
    public void containsWrapsAndTrims() {
        assertEquals("%lunch%", LikeEscaper.contains("  lunch "));
        assertEquals("%100\\%%", LikeEscaper.contains("100%"));
    }

    @Test
    public void blankSearchMeansNoSearch() {
        assertNull(LikeEscaper.contains(null));
        assertNull(LikeEscaper.contains(""));
        assertNull(LikeEscaper.contains("   "));
    }

    @Test
    public void prefixNeedsMinimumLength() {
        assertNull(LikeEscaper.prefix("ex", 3));
        assertNull(LikeEscaper.prefix(" e ", 3));
        assertEquals("exp%", LikeEscaper.prefix("exp", 3));
        assertEquals("inc\\_%", LikeEscaper.prefix("inc_", 3));
    }
}
