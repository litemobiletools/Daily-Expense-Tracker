package com.litemobiletools.dailyexpensetracker.drive;

/** What the Backup settings screen shows. A saved "connected" flag is never treated as proof: see {@link DriveConnectionManager}. */
public enum DriveConnectionState {
    /** Drive has not been connected (or was disconnected). The app works fully offline. */
    NOT_CONNECTED,
    /** Waiting for Google's authorization (including the consent screen). */
    AUTHORIZING,
    /** Authorization is fine; verifying that the app's Drive folder can be read. */
    CHECKING,
    /** Authorized, and the app's hidden Drive folder answered a read-only check. */
    CONNECTED,
    /** Withdrawing the app's Drive access. */
    DISCONNECTING,
    /** Access expired or was withdrawn: the user must authorize again. */
    REAUTH_REQUIRED,
    /** No network or the request timed out. Local features are unaffected. */
    NETWORK_UNAVAILABLE,
    /** Google or Google Play services is temporarily unavailable or rate limiting. */
    SERVICE_UNAVAILABLE,
    /** The user denied or revoked the permission. */
    DENIED,
    /** This build is not set up for Google Drive (OAuth client or Drive API not configured). */
    CONFIG_ERROR;

    /** True while a request is running, so another one must not be started. */
    public boolean isBusy() {
        return this == AUTHORIZING || this == CHECKING || this == DISCONNECTING;
    }
}
