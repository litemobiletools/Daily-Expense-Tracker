package com.litemobiletools.dailyexpensetracker.drive;

/** One-off outcomes the UI reports once (a message), as opposed to the lasting {@link DriveConnectionState}. */
public enum DriveEvent {
    /** The user authorized and the Drive folder check passed. */
    CONNECTED,
    /** The user closed Google's consent screen without choosing. */
    CANCELLED,
    /** Disconnected, and Google confirmed the app's access was revoked (or there was none left). */
    DISCONNECTED_REVOKED,
    /** Disconnected on this device, but Google's access could not be revoked from here. */
    DISCONNECTED_LOCAL_ONLY
}
