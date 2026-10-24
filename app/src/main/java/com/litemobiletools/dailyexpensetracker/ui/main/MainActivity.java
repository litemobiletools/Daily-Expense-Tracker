package com.litemobiletools.dailyexpensetracker.ui.main;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.snackbar.Snackbar;
import com.litemobiletools.dailyexpensetracker.BuildConfig;
import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.ads.AdSupport;
import com.litemobiletools.dailyexpensetracker.data.RepoCallback;
import com.litemobiletools.dailyexpensetracker.data.repository.RecurringRepository;
import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.ui.budgets.BudgetsFragment;
import com.litemobiletools.dailyexpensetracker.ui.home.HomeFragment;
import com.litemobiletools.dailyexpensetracker.ui.reports.ReportsFragment;
import com.litemobiletools.dailyexpensetracker.data.TransactionFilter;
import com.litemobiletools.dailyexpensetracker.ui.settings.SettingsFragment;
import com.litemobiletools.dailyexpensetracker.ui.transactions.TransactionsFragment;

/** Hosts the five top-level destinations behind a bottom navigation bar. */
public class MainActivity extends AppCompatActivity {

    private static final String STATE_SELECTED = "selected_destination";

    private BottomNavigationView bottomNav;
    private MaterialToolbar toolbar;

    // ---- Phase 10: single restrained banner (consent- and initialization-gated) ----------------
    // One AdView for every top-level tab: navigating between tabs reuses it and never issues a
    // second request. All secondary activities (add/edit transaction, backup/restore, recurring,
    // insights, goals) show no advertising at all.

    /** Conservative minimum gap between failed refresh attempts; there is no retry loop. */
    private static final long BANNER_RETRY_MIN_INTERVAL_MS = 120_000L;

    private final AdSupport.ReadyListener adsReadyListener = this::showBannerIfPermitted;
    @androidx.annotation.Nullable
    private AdView adView;
    private boolean bannerShowing;
    private boolean bannerRequestInFlight;
    private long lastBannerAttemptMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // The app bar and bottom bar handle the top/bottom insets; the root keeps content
        // clear of side system bars and display cutouts in landscape.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets side = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(side.left, 0, side.right, 0);
            return insets;
        });

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        bottomNav = findViewById(R.id.bottom_nav);

        int selected = savedInstanceState != null
                ? savedInstanceState.getInt(STATE_SELECTED, R.id.nav_home) : R.id.nav_home;
        bottomNav.setSelectedItemId(selected);
        showDestination(selected, savedInstanceState == null);
        bottomNav.setOnItemSelectedListener(item -> {
            showDestination(item.getItemId(), true);
            return true;
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (bottomNav.getSelectedItemId() != R.id.nav_home) {
                    bottomNav.setSelectedItemId(R.id.nav_home);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        // Phase 10: consent (UMP) + Mobile Ads SDK initialization, then the banner. Both steps
        // happen in the background; nothing here delays any user action, and if they fail the
        // app is simply ad-free.
        AdSupport.start(this);
        AdSupport.addReadyListener(adsReadyListener);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Bring recurring transactions up to date every time the app comes to the foreground. This is
        // idempotent: occurrences already recorded are never recorded again.
        RecurringRepository.get(this).reconcile(new RepoCallback<RecurringRepository.ReconcileResult>() {
            @Override
            public void onSuccess(RecurringRepository.ReconcileResult result) {
                if (result.recorded > 0 && !isDestroyed()) {
                    Snackbar.make(findViewById(R.id.main),
                            getResources().getQuantityString(R.plurals.recurring_recorded_toast,
                                    result.recorded, result.recorded),
                            Snackbar.LENGTH_LONG).setAnchorView(snackbarAnchor()).show();
                }
            }

            @Override
            public void onError(@NonNull Exception error) {
                // Nothing to show: the next foreground will try again.
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A banner attempt that failed earlier (offline start, temporary no-fill) may be retried,
        // at most once per minimum interval and only after consent and initialization allowed
        // requests. No tight retry loop is ever run.
        if (adView != null && !bannerShowing) {
            loadBannerRequest();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AdSupport.removeReadyListener(adsReadyListener);
        if (adView != null) {
            adView.setAdListener(null);
            adView.destroy();
            if (adView.getParent() instanceof ViewGroup) {
                ((ViewGroup) adView.getParent()).removeView(adView);
            }
            adView = null;
        }
    }

    // ---- Phase 10: banner -------------------------------------------------------------------

    /** Creates the banner and issues one request. Uses Google's canonical quick-start pairing:
     * a fixed {@link AdSize#BANNER} with the test banner unit in res/values/ads.xml - the
     * combination Google's documentation pairs with that unit id, which makes test ads show
     * reliably. (Anchored adaptive sizing remains a one-line switch documented in
     * docs/ADMOB_SETUP.md once a real ad unit exists.) */
    private void showBannerIfPermitted() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        FrameLayout container = findViewById(R.id.banner_container);
        if (container == null || adView != null) {
            return;
        }
        createBanner(container);
        loadBannerRequest();
    }

    private void createBanner(@NonNull ViewGroup container) {
        adView = new AdView(this);
        adView.setAdUnitId(getString(R.string.admob_banner_unit_id));
        adView.setAdSize(AdSize.BANNER);
        adView.setAdListener(new AdListener() {
            @Override
            public void onAdLoaded() {
                bannerRequestInFlight = false;
                bannerShowing = true;
                FrameLayout holder = findViewById(R.id.banner_container);
                if (holder != null) {
                    holder.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError error) {
                // No-fill, network failure, or any server error: remove the space and keep the
                // timer so the next attempt (on resume) respects the minimum retry interval.
                bannerRequestInFlight = false;
                bannerShowing = false;
                lastBannerAttemptMs = SystemClock.elapsedRealtime();
                FrameLayout holder = findViewById(R.id.banner_container);
                if (holder != null) {
                    holder.setVisibility(View.GONE);
                }
                if (BuildConfig.DEBUG) {
                    // Debug-only diagnostic: the test integration should always fill, so any
                    // failure is a setup problem the developer must see (network, Play services,
                    // unit-id mismatch, ...). Never shown in release.
                    Toast.makeText(MainActivity.this,
                            "Test banner failed: " + error.getCode() + " " + error.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL);
        container.addView(adView, params);
    }

    /** Issues one ad request if consent and initialization permit it; guarded against duplicates. */
    private void loadBannerRequest() {
        if (adView == null || bannerRequestInFlight || bannerShowing) {
            return;
        }
        if (!AdSupport.isAdsReady()) {
            return; // Consent not collected yet; the ready listener triggers the first attempt.
        }
        long now = SystemClock.elapsedRealtime();
        if (lastBannerAttemptMs != 0 && now - lastBannerAttemptMs < BANNER_RETRY_MIN_INTERVAL_MS) {
            return;
        }
        lastBannerAttemptMs = now;
        bannerRequestInFlight = true;
        adView.loadAd(new AdRequest.Builder().build());
    }

    /** Snackbars anchor above the banner when one is showing, so they never cover the ad. */
    private View snackbarAnchor() {
        View banner = findViewById(R.id.banner_container);
        if (banner != null && banner.getVisibility() == View.VISIBLE) {
            return banner;
        }
        return bottomNav;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_SELECTED, bottomNav.getSelectedItemId());
    }

    private void showDestination(int itemId, boolean replaceFragment) {
        toolbar.setTitle(titleFor(itemId));
        if (!replaceFragment) {
            return; // FragmentManager restored the existing fragment after recreation.
        }
        getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                .replace(R.id.fragment_container, createFragment(itemId, takePendingFilter()))
                .commit();
    }

    /** Filter to apply when the Transactions tab is opened from another screen (consumed once). */
    @androidx.annotation.Nullable private TransactionFilter pendingFilter;

    @androidx.annotation.Nullable
    private TransactionFilter takePendingFilter() {
        TransactionFilter filter = pendingFilter;
        pendingFilter = null;
        return filter;
    }

    /** Switches to the Reports tab. */
    public void openReports() {
        bottomNav.setSelectedItemId(R.id.nav_reports);
    }

    /** Switches to the Transactions tab showing only transactions that match {@code filter}. */
    public void openTransactions(@NonNull TransactionFilter filter) {
        pendingFilter = filter.copy();
        if (bottomNav.getSelectedItemId() == R.id.nav_transactions) {
            showDestination(R.id.nav_transactions, true);
        } else {
            bottomNav.setSelectedItemId(R.id.nav_transactions);
        }
    }

    @StringRes
    private static int titleFor(int itemId) {
        if (itemId == R.id.nav_transactions) {
            return R.string.nav_transactions;
        } else if (itemId == R.id.nav_budgets) {
            return R.string.nav_budgets;
        } else if (itemId == R.id.nav_reports) {
            return R.string.nav_reports;
        } else if (itemId == R.id.nav_settings) {
            return R.string.nav_settings;
        }
        return R.string.toolbar_home;
    }

    @NonNull
    private static Fragment createFragment(int itemId, @androidx.annotation.Nullable TransactionFilter filter) {
        if (itemId == R.id.nav_transactions) {
            return TransactionsFragment.newInstance(filter);
        } else if (itemId == R.id.nav_budgets) {
            return new BudgetsFragment();
        } else if (itemId == R.id.nav_reports) {
            return new ReportsFragment();
        } else if (itemId == R.id.nav_settings) {
            return new SettingsFragment();
        }
        return new HomeFragment();
    }
}
