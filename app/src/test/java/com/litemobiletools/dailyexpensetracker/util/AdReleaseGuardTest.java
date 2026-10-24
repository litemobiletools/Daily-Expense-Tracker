package com.litemobiletools.dailyexpensetracker.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** JVM tests for the Phase 10 ad release-safety gate. */
public class AdReleaseGuardTest {

    private static final String REAL_APP_ID = "ca-app-pub-1234567890123456~9876543210";
    private static final String REAL_BANNER_ID = "ca-app-pub-1234567890123456/1234567890";
    private static final String REAL_INTERSTITIAL_ID = "ca-app-pub-1234567890123456/9182736450";

    private static final String TEST_APP_ID = "ca-app-pub-3940256099942544~3347511713";
    private static final String TEST_BANNER_ID = "ca-app-pub-3940256099942544/6300978111";
    private static final String TEST_INTERSTITIAL_ID = "ca-app-pub-3940256099942544/1033173712";

    @Test
    public void debugBuildsWithTestIdsMayRequestAds() {
        assertTrue(AdReleaseGuard.allowsAdRequests(true, TEST_APP_ID, TEST_BANNER_ID,
                TEST_INTERSTITIAL_ID));
    }

    @Test
    public void releaseBuildsWithTestIdsAreDisabled() {
        assertFalse(AdReleaseGuard.allowsAdRequests(false, TEST_APP_ID, TEST_BANNER_ID,
                TEST_INTERSTITIAL_ID));
        // A half-updated config (only one ID replaced) is still not production.
        assertFalse(AdReleaseGuard.allowsAdRequests(false, TEST_APP_ID, REAL_BANNER_ID,
                REAL_INTERSTITIAL_ID));
        assertFalse(AdReleaseGuard.allowsAdRequests(false, REAL_APP_ID, TEST_BANNER_ID,
                REAL_INTERSTITIAL_ID));
        // A leftover test interstitial unit also blocks release monetization even when the other
        // IDs are real.
        assertFalse(AdReleaseGuard.allowsAdRequests(false, REAL_APP_ID, REAL_BANNER_ID,
                TEST_INTERSTITIAL_ID));
    }

    @Test
    public void releaseBuildsWithRealIdsMayRequestAds() {
        assertTrue(AdReleaseGuard.allowsAdRequests(false, REAL_APP_ID, REAL_BANNER_ID,
                REAL_INTERSTITIAL_ID));
        assertTrue(AdReleaseGuard.allowsAdRequests(false, REAL_APP_ID, REAL_BANNER_ID, null));
    }

    @Test
    public void malformedOrMissingIdsNeverReachTheSdk() {
        assertFalse(AdReleaseGuard.allowsAdRequests(true, null, REAL_BANNER_ID, REAL_INTERSTITIAL_ID));
        assertFalse(AdReleaseGuard.allowsAdRequests(true, REAL_APP_ID, null, REAL_INTERSTITIAL_ID));
        assertFalse(AdReleaseGuard.allowsAdRequests(true, "REPLACE_ME", REAL_BANNER_ID,
                REAL_INTERSTITIAL_ID));
        // App IDs are separated by '~', units by '/'; the wrong separator is invalid for each.
        assertFalse(AdReleaseGuard.allowsAdRequests(true, REAL_BANNER_ID, REAL_BANNER_ID,
                REAL_INTERSTITIAL_ID));
        assertFalse(AdReleaseGuard.allowsAdRequests(true, REAL_APP_ID, REAL_APP_ID,
                REAL_INTERSTITIAL_ID));
        assertFalse(AdReleaseGuard.allowsAdRequests(true, REAL_APP_ID, REAL_BANNER_ID, REAL_APP_ID));
        // The separator must have something after it.
        assertFalse(AdReleaseGuard.allowsAdRequests(true, "ca-app-pub-~9876543210", REAL_BANNER_ID,
                REAL_INTERSTITIAL_ID));
        assertFalse(AdReleaseGuard.allowsAdRequests(true, REAL_APP_ID, "ca-app-pub-/1234567890",
                REAL_INTERSTITIAL_ID));
        assertTrue(AdReleaseGuard.allowsAdRequests(true, " ca-app-pub-1234567890123456~1234567890 ",
                " ca-app-pub-1234567890123456/1234567890 ", "ca-app-pub-1234567890123456/5678901234"));
    }
}
