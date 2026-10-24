package com.litemobiletools.dailyexpensetracker.ads;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;
import com.litemobiletools.dailyexpensetracker.BuildConfig;
import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.util.AdReleaseGuard;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 10: gathers Google UMP consent, initializes the Mobile Ads SDK, and tells the UI when ads
 * may be requested. Everything is optional by design: if consent cannot be gathered, the SDK
 * cannot initialize, or the release guard disables ads (release build still on test IDs, or
 * malformed configuration), the app simply runs without ads - every expense-tracking feature is
 * unaffected and nothing is blocked behind advertising.
 *
 * <p>The flow follows Google's official consolidated "set up consent + initialize" pattern: the
 * consent info update is requested each time a top-level screen starts, the privacy form is shown
 * only when UMP requires it, and {@link ConsentInformation#canRequestAds()} gates SDK
 * initialization before any ad request. Consent failures never block the app.</p>
 *
 * <p>All state is touched on the main thread only (a helper relays the SDK callbacks there), so no
 * volatile fields are needed. Only the application context is kept, never an Activity.</p>
 */
public final class AdSupport {

    /** Called on the main thread when ads may be requested for the remainder of the process. */
    public interface ReadyListener {
        void onAdsReady();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<ReadyListener> PENDING_LISTENERS = new ArrayList<>();

    /** One interstitial at most every ten minutes, tracked per process (resets when the app is
     * fully closed). This is deliberately conservative for a financial-records app. */
    private static final long INTERSTITIAL_MIN_INTERVAL_MS = 10 * 60_000L;

    @Nullable
    private static Context appContext;
    /** Process-wide UMP consent handle; null until {@link #start} first runs. */
    @Nullable
    private static ConsentInformation consentInformation;
    private static boolean initStarted;
    private static boolean mobileAdsInitialized;
    private static boolean listenersNotified;

    @Nullable
    private static InterstitialAd interstitialAd;
    private static boolean interstitialLoading;
    private static long lastInterstitialShownMs;

    /**
     * Set when a UMP consent-info update failed this session. Debug builds may then still serve
     * Google's TEST ads (the documented development path, which needs no consent records);
     * release builds strictly require a successful consent update or existing stored consent.
     */
    private static boolean consentUpdateFailedThisSession;

    private AdSupport() {
    }

    /**
     * Starts the consent + initialization chain. Call from a top-level activity's {@code onCreate}
     * on every recreation: asking UMP for consent info each time matches Google's official sample,
     * while SDK initialization and listener notification happen at most once per process.
     */
    public static void start(@NonNull Activity activity) {
        runOnMain(() -> {
            if (!adsAllowedForThisBuild(activity)) {
                return; // A release build on test IDs (or malformed IDs) runs without ads.
            }
            if (appContext == null) {
                appContext = activity.getApplicationContext();
            }
            ConsentInformation consent = UserMessagingPlatform.getConsentInformation(activity);
            consentInformation = consent;
            requestConsentInfo(activity, consent);
            // Repeat launches: consent was already gathered, so init can begin immediately.
            maybeInitializeMobileAds();
        });
    }

    /**
     * Registers a callback for the moment ads are permitted. If that moment already passed, the
     * callback runs immediately (posted to the main looper). Listeners must be removed on destroy,
     * which {@link #removeReadyListener} supports.
     */
    public static void addReadyListener(@NonNull ReadyListener listener) {
        runOnMain(() -> {
            if (isAdsReady()) {
                MAIN.post(listener::onAdsReady);
            } else if (!PENDING_LISTENERS.contains(listener)) {
                PENDING_LISTENERS.add(listener);
            }
        });
    }

    public static void removeReadyListener(@NonNull ReadyListener listener) {
        runOnMain(() -> PENDING_LISTENERS.remove(listener));
    }

    /**
     * True once consent was collected (or was not required) AND the SDK finished initializing.
     * Main thread only, like all state access in this class.
     */
    @MainThread
    public static boolean isAdsReady() {
        return mobileAdsInitialized && consentAllowsRequests();
    }

    /**
     * Whether UMP requires a permanent privacy-options entry point for this user (consented users
     * in most regions, US state privacy laws). Settings shows the entry row only when true.
     */
    public static boolean privacyOptionsRequired(@Nullable Context context) {
        if (consentInformation == null) {
            return false; // The consent flow never ran (for example, a guarded build).
        }
        return consentInformation.getPrivacyOptionsRequirementStatus()
                == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED;
    }

    /**
     * Shows UMP's privacy options form (the required "how ads are personalized" entry point).
     * {@code onDismissed} runs afterwards, also when the form could not be shown.
     */
    public static void showPrivacyOptionsForm(@NonNull Activity activity,
                                              @Nullable Runnable onDismissed) {
        runOnMain(() -> {
            if (consentInformation == null || adsDisabledForThisBuild()) {
                if (onDismissed != null) {
                    onDismissed.run();
                }
                return;
            }
            UserMessagingPlatform.showPrivacyOptionsForm(activity, error -> {
                if (onDismissed != null) {
                    onDismissed.run();
                }
            });
        });
    }

    // ----------------------------------------------------------------------------------- internals

    private static boolean adsAllowedForThisBuild(@NonNull Context context) {
        return AdReleaseGuard.allowsAdRequests(
                BuildConfig.DEBUG,
                context.getString(R.string.admob_app_id),
                context.getString(R.string.admob_banner_unit_id),
                context.getString(R.string.admob_interstitial_unit_id));
    }

    private static boolean adsDisabledForThisBuild() {
        return appContext == null || !adsAllowedForThisBuild(appContext);
    }

    private static void requestConsentInfo(@NonNull Activity activity,
                                           @NonNull ConsentInformation consent) {
        ConsentRequestParameters parameters = buildConsentParameters(activity);
        // Failures are deliberately non-fatal: the app continues, asking canRequestAds() - ads
        // simply are not requested until it returns true. In debug builds with Google's test IDs
        // a failed update additionally enables the test-ad fallback (see consentAllowsRequests),
        // so a network hiccup while developing never means "no ads at all". The next launch
        // retries the consent update either way.
        consent.requestConsentInfoUpdate(activity, parameters,
                () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity,
                        formError -> maybeInitializeMobileAds()),
                requestError -> runOnMain(() -> {
                    consentUpdateFailedThisSession = BuildConfig.DEBUG;
                    maybeInitializeMobileAds();
                }));
    }

    /**
     * The consent gate that qualifies ALL ad requests. Debug builds with a failed UMP update
     * fall back to test ads (which Google explicitly supports without consent handling during
     * development); release builds never bypass UMP.
     */
    private static boolean consentAllowsRequests() {
        if (consentInformation != null && consentInformation.canRequestAds()) {
            return true;
        }
        return BuildConfig.DEBUG && consentUpdateFailedThisSession;
    }

    private static ConsentRequestParameters buildConsentParameters(@NonNull Context context) {
        // Debug-only consent testing hook (forced geography, test device hash) is exercised from
        // the developer machine as described in docs/ADMOB_SETUP.md ("Testing the consent flow");
        // no debug plumbing ships in code so nothing experimental can leak into release.
        return new ConsentRequestParameters.Builder()
                .setTagForUnderAgeOfConsent(false)
                .build();
    }

    /** Initializes the SDK at most once per process, gated by {@link #consentAllowsRequests}. */
    private static void maybeInitializeMobileAds() {
        runOnMain(() -> {
            if (appContext == null || !consentAllowsRequests()) {
                return;
            }
            if (mobileAdsInitialized) {
                fireListeners();
                return;
            }
            if (initStarted) {
                return; // The initialization callback will fire the listeners when it completes.
            }
            initStarted = true;
            MobileAds.initialize(appContext, initializationStatus ->
                    runOnMain(() -> {
                        mobileAdsInitialized = true;
                        fireListeners();
                    }));
        });
    }

    private static void fireListeners() {
        if (listenersNotified || PENDING_LISTENERS.isEmpty()) {
            return;
        }
        listenersNotified = true;
        for (ReadyListener listener : new ArrayList<>(PENDING_LISTENERS)) {
            MAIN.post(listener::onAdsReady);
        }
        PENDING_LISTENERS.clear();
        // Practical preloading: the interstitial is fetched once ads are permitted, so a later
        // natural transition does not wait for a network round-trip.
        maybeLoadInterstitial();
    }

    // ---- Interstitial (capped, single call site) --------------------------------------------

    /**
     * Loads the interstitial at most once at a time, with no retry loop: a failed load is retried
     * only at the next {@link #showInterstitialIfPermitted} attempt (which itself is capped to one
     * per {@link #INTERSTITIAL_MIN_INTERVAL_MS}).
     */
    private static void maybeLoadInterstitial() {
        runOnMain(() -> {
            if (!isAdsReady() || interstitialAd != null || interstitialLoading) {
                return;
            }
            interstitialLoading = true;
            InterstitialAd.load(appContext,
                    appContext.getString(R.string.admob_interstitial_unit_id),
                    new AdRequest.Builder().build(),
                    new InterstitialAdLoadCallback() {
                        @Override
                        public void onAdLoaded(@NonNull InterstitialAd ad) {
                            runOnMain(() -> {
                                interstitialAd = ad;
                                interstitialLoading = false;
                            });
                        }

                        @Override
                        public void onAdFailedToLoad(@NonNull LoadAdError error) {
                            runOnMain(() -> interstitialLoading = false);
                        }
                    });
        });
    }

    /**
     * Shows the preloaded interstitial only during the frequency cap window, then ALWAYS runs
     * {@code afterClosed} (immediately, when no ad is available, was not yet eligible, or the show
     * failed). Callers must guarantee this is a natural, non-financial break - the only call site
     * is closing the read-only Insights screen, and only when no transaction action took place
     * there. Never called on launch, on transaction edits/deletes, or during backup/restore/export.
     */
    public static void showInterstitialIfPermitted(@NonNull Activity activity,
                                                   @Nullable Runnable afterClosed) {
        runOnMain(() -> {
            Runnable close = afterClosed == null ? () -> { } : afterClosed;
            long now = SystemClock.elapsedRealtime();
            InterstitialAd ad = interstitialAd;
            boolean capElapsed = lastInterstitialShownMs == 0
                    || now - lastInterstitialShownMs >= INTERSTITIAL_MIN_INTERVAL_MS;
            if (ad == null || !capElapsed || adsDisabledForThisBuild()) {
                maybeLoadInterstitial(); // Quiet single attempt so a later transition can be ready.
                close.run();
                return;
            }
            interstitialAd = null; // The loaded ad is consumed: it can never be shown twice.
            lastInterstitialShownMs = now;
            ad.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdDismissedFullScreenContent() {
                    runOnMain(() -> {
                        close.run();
                        maybeLoadInterstitial(); // Reload only after a successful show.
                    });
                }

                @Override
                public void onAdFailedToShowFullScreenContent(@NonNull AdError error) {
                    runOnMain(close::run);
                }
            });
            ad.show(activity);
        });
    }

    private static void runOnMain(@NonNull Runnable block) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block.run();
        } else {
            MAIN.post(block);
        }
    }
}
