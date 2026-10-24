package com.litemobiletools.dailyexpensetracker.util;

/**
 * Phase 10: pure decision logic for whether this build may request ads at all. No Android
 * classes are involved, which keeps it unit-testable on the JVM.
 *
 * <p>Success criteria: a release build must never accidentally ship with Google's official test
 * ad IDs (they only ever serve test ads and would redirect real users away from real
 * monetization), and a malformed ID must never reach the Mobile Ads SDK (an invalid AdMob App ID
 * makes the SDK fail fast with an exception).</p>
 *
 * <p>The live ID values themselves live only in {@code res/values/ads.xml}; this class only
 * compares against Google's published sample test IDs.</p>
 */
public final class AdReleaseGuard {

    /** Google's official sample App ID, served test ads only (developers.google.com/admob/android/test-ads). */
    static final String GOOGLE_TEST_APP_ID = "ca-app-pub-3940256099942544~3347511713";

    /** Google's official test banner unit, served test ads only. */
    static final String GOOGLE_TEST_BANNER_ID = "ca-app-pub-3940256099942544/6300978111";

    /** Google's official test interstitial unit, served test ads only. */
    static final String GOOGLE_TEST_INTERSTITIAL_ID = "ca-app-pub-3940256099942544/1033173712";

    private static final String ID_PREFIX = "ca-app-pub-";

    private AdReleaseGuard() {
    }

    /**
     * True when ad requests are allowed for this build.
     *
     * <ul>
     *   <li>Debug builds are allowed while both IDs are well-formed: test IDs are Google's
     *       documented way to develop and debug ad code.</li>
     *   <li>Release builds are allowed only when both IDs are well-formed AND different from
     *       Google's official test IDs. A release build still carrying the test IDs disables ads
     *       entirely (fail-safe): the app stays fully functional and free, and real users are
     *       never shown config that was never set up.</li>
     * </ul>
     *
     * @param debugBuild       {@code BuildConfig.DEBUG}
     * @param appId            AdMob App ID resource value ({@code R.string.admob_app_id})
     * @param bannerUnitId     banner ad unit resource value ({@code R.string.admob_banner_unit_id})
     * @param interstitialUnit interstitial ad unit resource value
     *                         ({@code R.string.admob_interstitial_unit_id}); may be {@code null}
     *                         in builds that show no interstitial
     */
    public static boolean allowsAdRequests(boolean debugBuild, String appId, String bannerUnitId,
                                           String interstitialUnit) {
        if (!isValidAppId(appId) || !isValidUnitId(bannerUnitId)) {
            return false;
        }
        if (!debugBuild) {
            return !GOOGLE_TEST_APP_ID.equals(appId)
                    && !GOOGLE_TEST_BANNER_ID.equals(bannerUnitId)
                    && (interstitialUnit == null
                        || (!GOOGLE_TEST_INTERSTITIAL_ID.equals(interstitialUnit)
                            && isValidUnitId(interstitialUnit)));
        }
        // Debug builds tolerate an unusable interstitial unit (ads simply do not preload for it).
        return interstitialUnit == null || isValidUnitId(interstitialUnit);
    }

    /** Shaped like {@code ca-app-pub-XXXXXXXXXXXXXXXX~NNNNNNNNNN} (tidle separator, non-empty suffix). */
    static boolean isValidAppId(String appId) {
        return hasIdShape(appId, '~');
    }

    /** Shaped like {@code ca-app-pub-XXXXXXXXXXXXXXXX/NNNNNNNNNN} (slash separator, non-empty suffix). */
    static boolean isValidUnitId(String bannerUnitId) {
        return hasIdShape(bannerUnitId, '/');
    }

    private static boolean hasIdShape(String id, char separator) {
        if (id == null) {
            return false;
        }
        String trimmed = id.trim();
        int sepAt = trimmed.indexOf(separator);
        return trimmed.startsWith(ID_PREFIX)
                && sepAt > ID_PREFIX.length()
                && sepAt < trimmed.length() - 1;
    }
}
