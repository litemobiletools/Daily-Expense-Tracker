package com.litemobiletools.dailyexpensetracker.drive;

import android.app.PendingIntent;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.Executor;

/**
 * Runs the Drive connection flow: authorize (drive.appdata only), verify with a read-only Drive call,
 * and disconnect. Call every method on the main thread; callbacks to the {@link Listener} arrive there too.
 * The Drive request runs on the supplied I/O executor.
 *
 * <p>Truth comes from Google, not from storage: the saved "connection requested" flag only decides
 * whether to re-check when the screen opens. {@link DriveConnectionState#CONNECTED} is reported only after
 * Drive has answered a read-only request in this process. Tokens live only in local variables.
 */
public final class DriveConnectionManager {

    public interface Listener {
        void onStateChanged(@NonNull DriveConnectionState state);

        void onEvent(@NonNull DriveEvent event);

        /** The user must approve on Google's screen; launch the intent for a result. */
        void onConsentRequired(@NonNull PendingIntent consent);
    }

    private final DriveAuthGateway gateway;
    private final DriveApi api;
    private final DriveConnectionStore store;
    private final Executor io;
    private final Executor main;

    private int lastErrorCode;
    @Nullable private Listener listener;
    @NonNull private DriveConnectionState state = DriveConnectionState.NOT_CONNECTED;
    @NonNull private DriveConnectionState stateBeforeConnect = DriveConnectionState.NOT_CONNECTED;

    public DriveConnectionManager(@NonNull DriveAuthGateway gateway, @NonNull DriveApi api,
                                  @NonNull DriveConnectionStore store,
                                  @NonNull Executor io, @NonNull Executor main) {
        this.gateway = gateway;
        this.api = api;
        this.store = store;
        this.io = io;
        this.main = main;
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    @NonNull
    public DriveConnectionState getState() {
        return state;
    }

    /**
     * Re-checks an earlier connection without showing any Google screen. Does nothing network-related
     * if the user never connected, so Drive is never contacted at launch for people who do not use it.
     */
    public void refresh() {
        if (state.isBusy()) {
            return;
        }
        if (!store.isConnectionRequested()) {
            setState(DriveConnectionState.NOT_CONNECTED);
            return;
        }
        setState(DriveConnectionState.CHECKING);
        gateway.authorize(outcome -> {
            switch (outcome.kind) {
                case GRANTED:
                    verify(outcome, false);
                    break;
                case NEEDS_CONSENT:
                    setState(DriveConnectionState.REAUTH_REQUIRED);
                    break;
                default:
                    lastErrorCode = outcome.errorCode;
                    setState(stateForAuthFailure(outcome.errorCode));
                    break;
            }
        });
    }

    /** The user tapped Connect. Ignored while another request is running (duplicate taps). */
    public void connect() {
        if (state.isBusy()) {
            return;
        }
        stateBeforeConnect = state;
        setState(DriveConnectionState.AUTHORIZING);
        gateway.authorize(outcome -> {
            switch (outcome.kind) {
                case GRANTED:
                    verify(outcome, true);
                    break;
                case NEEDS_CONSENT:
                    if (outcome.consent == null) {
                        setState(DriveConnectionState.SERVICE_UNAVAILABLE);
                    } else if (listener != null) {
                        listener.onConsentRequired(outcome.consent);
                    } else {
                        setState(stateBeforeConnect);
                    }
                    break;
                default:
                    lastErrorCode = outcome.errorCode;
                    setState(stateForAuthFailure(outcome.errorCode));
                    break;
            }
        });
    }

    /** Delivers what Google's consent screen returned. */
    public void onConsentResult(boolean completed, @Nullable Intent data) {
        if (state != DriveConnectionState.AUTHORIZING) {
            return;
        }
        AuthOutcome outcome = data == null && !completed ? AuthOutcome.failed(AuthOutcome.UNKNOWN)
                : gateway.parseConsentResult(data);
        if (!completed && outcome.kind != AuthOutcome.Kind.GRANTED
                && (outcome.kind != AuthOutcome.Kind.FAILED || isPlainCancel(outcome.errorCode))) {
            // Back / close on Google's screen, with no specific Google error attached.
            lastErrorCode = 0;
            setState(stateBeforeConnect);
            emit(DriveEvent.CANCELLED);
            return;
        }
        if (outcome.kind == AuthOutcome.Kind.GRANTED) {
            verify(outcome, true);
        } else if (outcome.kind == AuthOutcome.Kind.NEEDS_CONSENT) {
            setState(DriveConnectionState.REAUTH_REQUIRED);
        } else {
            lastErrorCode = outcome.errorCode;
            setState(stateForAuthFailure(outcome.errorCode));
        }
    }

    private static boolean isPlainCancel(int code) {
        return code == AuthOutcome.UNKNOWN || code == 16 || code == 12501;
    }

    /** Google's status code behind the last authorization failure (0 if none); safe to show to the user. */
    public int getLastErrorCode() {
        return lastErrorCode;
    }

    /**
     * Withdraws the app's Drive access with Google and clears the local flag. The user's local data and
     * any backup already in their Drive are not touched.
     */
    public void disconnect() {
        if (state.isBusy()) {
            return;
        }
        setState(DriveConnectionState.DISCONNECTING);
        gateway.revoke(result -> {
            store.setConnectionRequested(false);
            setState(DriveConnectionState.NOT_CONNECTED);
            boolean revoked = result == RevokeResult.REVOKED || result == RevokeResult.NOTHING_TO_REVOKE;
            emit(revoked ? DriveEvent.DISCONNECTED_REVOKED : DriveEvent.DISCONNECTED_LOCAL_ONLY);
        });
    }

    private void verify(@NonNull AuthOutcome outcome, boolean userInitiated) {
        String token = outcome.accessToken();
        if (token == null) {
            setState(DriveConnectionState.SERVICE_UNAVAILABLE);
            return;
        }
        setState(DriveConnectionState.CHECKING);
        io.execute(() -> {
            DriveCheckResult result = api.checkAppDataAccess(token);
            main.execute(() -> onChecked(result, token, userInitiated));
        });
    }

    private void onChecked(@NonNull DriveCheckResult result, @NonNull String token, boolean userInitiated) {
        if (result == DriveCheckResult.UNAUTHORIZED) {
            // Google's cache may keep handing out the rejected token; drop it so the next try gets a new one.
            gateway.clearToken(token);
        }
        DriveConnectionState next = stateForCheck(result);
        if (next == DriveConnectionState.CONNECTED) {
            store.setConnectionRequested(true);
        }
        setState(next);
        if (next == DriveConnectionState.CONNECTED && userInitiated) {
            emit(DriveEvent.CONNECTED);
        }
    }

    /** Maps the Drive check to a state. */
    @NonNull
    static DriveConnectionState stateForCheck(@NonNull DriveCheckResult result) {
        switch (result) {
            case OK:
                return DriveConnectionState.CONNECTED;
            case UNAUTHORIZED:
                return DriveConnectionState.REAUTH_REQUIRED;
            case API_NOT_ENABLED:
            case FORBIDDEN:
                return DriveConnectionState.CONFIG_ERROR;
            case NETWORK:
                return DriveConnectionState.NETWORK_UNAVAILABLE;
            default:
                return DriveConnectionState.SERVICE_UNAVAILABLE;
        }
    }

    /** Maps a Google authorization failure code to a state. */
    @NonNull
    public static DriveConnectionState stateForAuthFailure(int code) {
        switch (code) {
            case 10:    // DEVELOPER_ERROR: OAuth client / package / SHA-1 not configured for this build
            case 8:     // INTERNAL_ERROR: in practice usually the same setup mismatch (client, SHA-1, test user)
                return DriveConnectionState.CONFIG_ERROR;
            case 7:     // NETWORK_ERROR
            case 15:    // TIMEOUT
                return DriveConnectionState.NETWORK_UNAVAILABLE;
            case 16:    // CANCELED
            case 12501: // SIGN_IN_CANCELLED
            case AuthOutcome.DENIED:
                return DriveConnectionState.DENIED;
            case 4:     // SIGN_IN_REQUIRED
                return DriveConnectionState.REAUTH_REQUIRED;
            default:
                return DriveConnectionState.SERVICE_UNAVAILABLE;
        }
    }

    // ---- Hooks for backup/restore operations (Phase 7) ---------------------------------

    /**
     * A backup operation needs the user's approval first. The operation holds its own consent
     * PendingIntent; this only keeps the connection state truthful while nothing else runs here.
     */
    public void onOperationNeedsConsent() {
        if (state.isBusy()) {
            return;
        }
        setState(DriveConnectionState.REAUTH_REQUIRED);
    }

    /** Google refused an operation's authorization for the given status code. */
    public void onOperationAuthFailed(int googleCode) {
        if (state.isBusy()) {
            return;
        }
        setState(stateForAuthFailure(googleCode));
    }

    /**
     * A Drive request answered positively in this process (backup listed, uploaded and verified,
     * or a backup downloaded). That is proof of access, so the connection state becomes CONNECTED
     * whenever the connection flow itself is idle. Tokens stay in operation local variables.
     */
    public void onDriveAccessVerified() {
        if (state.isBusy()) {
            return;
        }
        store.setConnectionRequested(true);
        setState(DriveConnectionState.CONNECTED);
    }

    private void setState(@NonNull DriveConnectionState next) {
        state = next;
        if (listener != null) {
            listener.onStateChanged(next);
        }
    }

    private void emit(@NonNull DriveEvent event) {
        if (listener != null) {
            listener.onEvent(event);
        }
    }
}
