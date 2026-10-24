package com.litemobiletools.dailyexpensetracker.drive;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.security.NetworkSecurityPolicy;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.matcher.ViewMatchers;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.ui.backup.BackupActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class DriveConnectionInstrumentedTest {

    private Context context;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences(DriveConnectionStore.Prefs.FILE, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test
    public void backupScreenStartsNotConnectedWithConnectAndNoDisconnect() {
        try (ActivityScenario<BackupActivity> scenario = ActivityScenario.launch(BackupActivity.class)) {
            onView(withId(R.id.drive_status_title)).check(matches(withText(R.string.drive_state_not_connected)));
            onView(withId(R.id.drive_status_detail)).check(matches(withText(R.string.drive_state_not_connected_detail)));
            onView(withId(R.id.drive_connect)).check(matches(isDisplayed()));
            onView(withId(R.id.drive_disconnect))
                    .check(matches(withEffectiveVisibility(ViewMatchers.Visibility.GONE)));
            onView(withId(R.id.drive_progress))
                    .check(matches(withEffectiveVisibility(ViewMatchers.Visibility.GONE)));
        }
    }

    @Test
    public void openingTheScreenDoesNotCreateAConnectionRecord() {
        try (ActivityScenario<BackupActivity> scenario = ActivityScenario.launch(BackupActivity.class)) {
            scenario.onActivity(activity -> { });
        }
        assertFalse(new DriveConnectionStore.Prefs(context).isConnectionRequested());
    }

    @Test
    public void onlyOneNonSensitiveBooleanIsEverStored() {
        DriveConnectionStore.Prefs store = new DriveConnectionStore.Prefs(context);
        store.setConnectionRequested(true);
        SharedPreferences prefs = context.getSharedPreferences(DriveConnectionStore.Prefs.FILE, Context.MODE_PRIVATE);
        assertEquals(1, prefs.getAll().size());
        assertTrue(prefs.getAll().containsKey("connection_requested"));
        assertTrue(prefs.getAll().get("connection_requested") instanceof Boolean);
        store.setConnectionRequested(false);
        assertFalse(store.isConnectionRequested());
    }

    @Test
    public void cleartextTrafficIsRefused() {
        assertFalse(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted());
    }

    @Test
    public void consentIsRequestedFromTheUiAndCancellationIsHandled() {
        PendingIntent consent = PendingIntent.getActivity(context, 0, new Intent(),
                PendingIntent.FLAG_IMMUTABLE);
        DriveAuthGateway gateway = new DriveAuthGateway() {
            @Override
            public void authorize(@NonNull Callback<AuthOutcome> callback) {
                callback.onResult(AuthOutcome.needsConsent(consent));
            }

            @NonNull
            @Override
            public AuthOutcome parseConsentResult(@Nullable Intent data) {
                return AuthOutcome.failed(AuthOutcome.UNKNOWN);
            }

            @Override
            public void revoke(@NonNull Callback<RevokeResult> callback) {
                callback.onResult(RevokeResult.REVOKED);
            }

            @Override
            public void clearToken(@NonNull String accessToken) {
            }
        };
        DriveConnectionManager manager = new DriveConnectionManager(gateway, new DriveApi(),
                new DriveConnectionStore.Prefs(context), Runnable::run, Runnable::run);
        List<String> log = new ArrayList<>();
        manager.setListener(new DriveConnectionManager.Listener() {
            @Override
            public void onStateChanged(@NonNull DriveConnectionState state) {
                log.add("state:" + state);
            }

            @Override
            public void onEvent(@NonNull DriveEvent event) {
                log.add("event:" + event);
            }

            @Override
            public void onConsentRequired(@NonNull PendingIntent pending) {
                log.add("consent");
            }
        });

        manager.connect();
        manager.connect(); // duplicate tap while waiting for Google
        assertEquals(DriveConnectionState.AUTHORIZING, manager.getState());
        manager.onConsentResult(false, null);

        assertEquals(DriveConnectionState.NOT_CONNECTED, manager.getState());
        assertEquals(List.of("state:AUTHORIZING", "consent", "state:NOT_CONNECTED",
                "event:CANCELLED"), log);
        assertFalse(new DriveConnectionStore.Prefs(context).isConnectionRequested());
    }
}
