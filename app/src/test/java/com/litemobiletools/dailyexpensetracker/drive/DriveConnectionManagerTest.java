package com.litemobiletools.dailyexpensetracker.drive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.PendingIntent;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class DriveConnectionManagerTest {

    private static final Executor DIRECT = Runnable::run;

    private static class FakeGateway implements DriveAuthGateway {
        AuthOutcome authorizeOutcome = AuthOutcome.granted("tok");
        AuthOutcome consentOutcome = AuthOutcome.granted("tok");
        RevokeResult revokeResult = RevokeResult.REVOKED;
        int authorizeCalls;
        int revokeCalls;
        final List<String> clearedTokens = new ArrayList<>();

        @Override
        public void authorize(@NonNull Callback<AuthOutcome> callback) {
            authorizeCalls++;
            callback.onResult(authorizeOutcome);
        }

        @NonNull
        @Override
        public AuthOutcome parseConsentResult(@Nullable Intent data) {
            return consentOutcome;
        }

        @Override
        public void revoke(@NonNull Callback<RevokeResult> callback) {
            revokeCalls++;
            callback.onResult(revokeResult);
        }

        @Override
        public void clearToken(@NonNull String accessToken) {
            clearedTokens.add(accessToken);
        }
    }

    private static final class FakeApi extends DriveApi {
        DriveCheckResult result = DriveCheckResult.OK;
        int calls;
        String lastToken;

        FakeApi() {
            super("http://unused.invalid/files");
        }

        @NonNull
        @Override
        public DriveCheckResult checkAppDataAccess(@NonNull String accessToken) {
            calls++;
            lastToken = accessToken;
            return result;
        }
    }

    private static final class MemoryStore implements DriveConnectionStore {
        boolean requested;

        @Override
        public boolean isConnectionRequested() {
            return requested;
        }

        @Override
        public void setConnectionRequested(boolean value) {
            requested = value;
        }
    }

    private static final class Recorder implements DriveConnectionManager.Listener {
        final List<DriveConnectionState> states = new ArrayList<>();
        final List<DriveEvent> events = new ArrayList<>();
        int consents;

        @Override
        public void onStateChanged(@NonNull DriveConnectionState state) {
            states.add(state);
        }

        @Override
        public void onEvent(@NonNull DriveEvent event) {
            events.add(event);
        }

        @Override
        public void onConsentRequired(@NonNull PendingIntent consent) {
            consents++;
        }
    }

    private FakeGateway gateway;
    private FakeApi api;
    private MemoryStore store;
    private Recorder recorder;
    private DriveConnectionManager manager;

    @Before
    public void setUp() {
        gateway = new FakeGateway();
        api = new FakeApi();
        store = new MemoryStore();
        recorder = new Recorder();
        manager = new DriveConnectionManager(gateway, api, store, DIRECT, DIRECT);
        manager.setListener(recorder);
    }

    @Test
    public void startsNotConnectedAndNeverContactsGoogleAtLaunch() {
        manager.refresh();
        assertEquals(DriveConnectionState.NOT_CONNECTED, manager.getState());
        assertEquals(0, gateway.authorizeCalls);
        assertEquals(0, api.calls);
    }

    @Test
    public void connectWithExistingGrantVerifiesThenReportsConnected() {
        manager.connect();
        assertEquals(DriveConnectionState.CONNECTED, manager.getState());
        assertEquals("tok", api.lastToken);
        assertTrue(store.requested);
        assertEquals(DriveEvent.CONNECTED, recorder.events.get(0));
        assertEquals(DriveConnectionState.AUTHORIZING, recorder.states.get(0));
    }

    @Test
    public void aSavedFlagAloneIsNeverShownAsConnected() {
        store.requested = true;
        gateway.authorizeOutcome = AuthOutcome.needsConsent(null);
        manager.refresh();
        assertEquals(DriveConnectionState.REAUTH_REQUIRED, manager.getState());
        assertEquals(0, api.calls);
    }

    @Test
    public void refreshWithFlagAndWorkingAccessIsConnectedWithoutAMessage() {
        store.requested = true;
        manager.refresh();
        assertEquals(DriveConnectionState.CONNECTED, manager.getState());
        assertTrue(recorder.events.isEmpty());
    }

    @Test
    public void expiredTokenClearsItAndRequestsReauthorization() {
        store.requested = true;
        api.result = DriveCheckResult.UNAUTHORIZED;
        manager.refresh();
        assertEquals(DriveConnectionState.REAUTH_REQUIRED, manager.getState());
        assertEquals(1, gateway.clearedTokens.size());
        assertTrue("flag stays so the user is asked to approve again", store.requested);
    }

    @Test
    public void connectFailureIsNotRememberedAsConnected() {
        api.result = DriveCheckResult.API_NOT_ENABLED;
        manager.connect();
        assertEquals(DriveConnectionState.CONFIG_ERROR, manager.getState());
        assertFalse(store.requested);
        assertTrue(recorder.events.isEmpty());
    }

    @Test
    public void developerErrorFromGoogleIsAConfigurationError() {
        gateway.authorizeOutcome = AuthOutcome.failed(10);
        manager.connect();
        assertEquals(DriveConnectionState.CONFIG_ERROR, manager.getState());
        assertEquals(0, api.calls);
    }

    @Test
    public void consentWithoutAnIntentIsAServiceErrorNotACrash() {
        gateway.authorizeOutcome = AuthOutcome.needsConsent(null);
        manager.connect();
        assertEquals(DriveConnectionState.SERVICE_UNAVAILABLE, manager.getState());
    }

    @Test
    public void networkFailureMapsToNetworkUnavailable() {
        api.result = DriveCheckResult.NETWORK;
        manager.connect();
        assertEquals(DriveConnectionState.NETWORK_UNAVAILABLE, manager.getState());
        assertFalse(store.requested);
    }

    @Test
    public void rateLimitAndServerErrorsAreServiceUnavailable() {
        api.result = DriveCheckResult.RATE_LIMITED;
        manager.connect();
        assertEquals(DriveConnectionState.SERVICE_UNAVAILABLE, manager.getState());
        api.result = DriveCheckResult.SERVER_ERROR;
        manager.connect();
        assertEquals(DriveConnectionState.SERVICE_UNAVAILABLE, manager.getState());
    }

    @Test
    public void deniedPermissionIsReported() {
        gateway.authorizeOutcome = AuthOutcome.failed(AuthOutcome.DENIED);
        manager.connect();
        assertEquals(DriveConnectionState.DENIED, manager.getState());
    }

    @Test
    public void duplicateTapsWhileBusyAreIgnored() {
        // A gateway that never answers keeps the manager busy.
        List<DriveAuthGateway.Callback<AuthOutcome>> pending = new ArrayList<>();
        DriveAuthGateway slow = new FakeGateway() {
            @Override
            public void authorize(@NonNull Callback<AuthOutcome> callback) {
                pending.add(callback);
            }
        };
        DriveConnectionManager busy = new DriveConnectionManager(slow, api, store, DIRECT, DIRECT);
        busy.connect();
        busy.connect();
        busy.disconnect();
        busy.refresh();
        assertEquals(1, pending.size());
        assertEquals(DriveConnectionState.AUTHORIZING, busy.getState());
    }

    @Test
    public void disconnectRevokesClearsTheFlagAndKeepsNothingElse() {
        store.requested = true;
        manager.disconnect();
        assertEquals(1, gateway.revokeCalls);
        assertEquals(DriveConnectionState.NOT_CONNECTED, manager.getState());
        assertFalse(store.requested);
        assertEquals(DriveEvent.DISCONNECTED_REVOKED, recorder.events.get(0));
        assertEquals(0, api.calls);
    }

    @Test
    public void disconnectWithUnknownAccountIsHonestAboutNotRevoking() {
        store.requested = true;
        gateway.revokeResult = RevokeResult.ACCOUNT_UNKNOWN;
        manager.disconnect();
        assertFalse(store.requested);
        assertEquals(DriveEvent.DISCONNECTED_LOCAL_ONLY, recorder.events.get(0));
    }

    @Test
    public void disconnectWhenRevokeFailsIsLocalOnly() {
        gateway.revokeResult = RevokeResult.FAILED;
        manager.disconnect();
        assertEquals(DriveEvent.DISCONNECTED_LOCAL_ONLY, recorder.events.get(0));
        assertEquals(DriveConnectionState.NOT_CONNECTED, manager.getState());
    }

    @Test
    public void alreadyWithdrawnAccessCountsAsRevoked() {
        gateway.revokeResult = RevokeResult.NOTHING_TO_REVOKE;
        manager.disconnect();
        assertEquals(DriveEvent.DISCONNECTED_REVOKED, recorder.events.get(0));
    }

    @Test
    public void authFailureCodesMapToStates() {
        assertEquals(DriveConnectionState.CONFIG_ERROR, DriveConnectionManager.stateForAuthFailure(10));
        assertEquals(DriveConnectionState.NETWORK_UNAVAILABLE, DriveConnectionManager.stateForAuthFailure(7));
        assertEquals(DriveConnectionState.DENIED, DriveConnectionManager.stateForAuthFailure(12501));
        assertEquals(DriveConnectionState.REAUTH_REQUIRED, DriveConnectionManager.stateForAuthFailure(4));
        assertEquals(DriveConnectionState.CONFIG_ERROR, DriveConnectionManager.stateForAuthFailure(8));
        assertEquals(DriveConnectionState.SERVICE_UNAVAILABLE, DriveConnectionManager.stateForAuthFailure(13));
        assertEquals(DriveConnectionState.SERVICE_UNAVAILABLE, DriveConnectionManager.stateForAuthFailure(AuthOutcome.UNKNOWN));
    }

    @Test
    public void checkResultsMapToStates() {
        assertEquals(DriveConnectionState.CONNECTED, DriveConnectionManager.stateForCheck(DriveCheckResult.OK));
        assertEquals(DriveConnectionState.REAUTH_REQUIRED, DriveConnectionManager.stateForCheck(DriveCheckResult.UNAUTHORIZED));
        assertEquals(DriveConnectionState.CONFIG_ERROR, DriveConnectionManager.stateForCheck(DriveCheckResult.FORBIDDEN));
        assertEquals(DriveConnectionState.NETWORK_UNAVAILABLE, DriveConnectionManager.stateForCheck(DriveCheckResult.NETWORK));
        assertEquals(DriveConnectionState.SERVICE_UNAVAILABLE, DriveConnectionManager.stateForCheck(DriveCheckResult.ERROR));
    }

    @Test
    public void grantedConsentResultIsVerifiedBeforeBeingTrusted() {
        // Reach AUTHORIZING without a PendingIntent by parking the silent authorize.
        List<DriveAuthGateway.Callback<AuthOutcome>> pending = new ArrayList<>();
        FakeGateway parked = new FakeGateway() {
            @Override
            public void authorize(@NonNull Callback<AuthOutcome> callback) {
                pending.add(callback);
            }
        };
        DriveConnectionManager m = new DriveConnectionManager(parked, api, store, DIRECT, DIRECT);
        m.connect();
        assertEquals(DriveConnectionState.AUTHORIZING, m.getState());
        api.result = DriveCheckResult.UNAUTHORIZED;
        m.onConsentResult(true, null);
        assertEquals(DriveConnectionState.REAUTH_REQUIRED, m.getState());
        assertEquals(1, parked.clearedTokens.size());
        assertFalse(store.requested);
    }

    @Test
    public void cancelledConsentRestoresThePreviousStateAndEmitsCancelled() {
        List<DriveAuthGateway.Callback<AuthOutcome>> pending = new ArrayList<>();
        FakeGateway parked = new FakeGateway() {
            @Override
            public void authorize(@NonNull Callback<AuthOutcome> callback) {
                pending.add(callback);
            }
        };
        DriveConnectionManager m = new DriveConnectionManager(parked, api, store, DIRECT, DIRECT);
        Recorder r = new Recorder();
        m.setListener(r);
        m.connect();
        m.onConsentResult(false, null);
        assertEquals(DriveConnectionState.NOT_CONNECTED, m.getState());
        assertEquals(DriveEvent.CANCELLED, r.events.get(0));
    }
}
