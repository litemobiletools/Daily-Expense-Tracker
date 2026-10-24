package com.litemobiletools.dailyexpensetracker.drive;

/** Outcome of Drive REST requests (the read-only check, and the backup operations of Phase 7). */
public enum DriveCheckResult {
    /** Drive answered 200: the token can read the app's folder. */
    OK,
    /** 401, or 403 for missing permission: the token is expired or lacks the scope. */
    UNAUTHORIZED,
    /** 403 saying the Drive API is not enabled for the Google Cloud project. */
    API_NOT_ENABLED,
    /** Any other 403. */
    FORBIDDEN,
    /** 429, or 403 for a quota limit. */
    RATE_LIMITED,
    /** 5xx from Google. */
    SERVER_ERROR,
    /** The requested file does not exist (deleted, or the id was never valid). */
    NOT_FOUND,
    /** 403 saying the user's Drive is full; the file could not be stored. */
    DRIVE_FULL,
    /** No connection, DNS failure or timeout. */
    NETWORK,
    /** Anything unexpected (other status, unreadable reply). */
    ERROR
}
