package com.litemobiletools.dailyexpensetracker.drive;

/** What happened when the app asked Google to withdraw its Drive access. */
public enum RevokeResult {
    /** Google revoked the app's access. */
    REVOKED,
    /** There was no active permission to revoke (already withdrawn). */
    NOTHING_TO_REVOKE,
    /** The authorized account could not be identified, so nothing was revoked from here. */
    ACCOUNT_UNKNOWN,
    /** Google reported a failure; the permission may still exist. */
    FAILED
}
