package com.litemobiletools.dailyexpensetracker.drive;

/** Fixed values for the Google Drive connection. No credentials live here (or anywhere in the app source). */
public final class DriveConstants {

    /**
     * The only OAuth scope the app requests: access to its own hidden application-data folder.
     * It cannot see or change any other file in the user's Drive. Google classes it as non-sensitive.
     */
    public static final String SCOPE_APPDATA = "https://www.googleapis.com/auth/drive.appdata";

    /** Drive REST endpoint for listing files and reading their metadata. HTTPS only. */
    public static final String FILES_URL = "https://www.googleapis.com/drive/v3/files";

    /** Drive REST endpoint for uploading file content. HTTPS only. */
    public static final String UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files";

    /** Page shown to users to review or remove the app's Google account access. */
    public static final String ACCOUNT_PERMISSIONS_URL = "https://myaccount.google.com/permissions";

    /** Whole backup files stay within this size (a JSON of the app's own tables); it bounds memory use. */
    public static final int MAX_BACKUP_BYTES = 32 * 1024 * 1024;

    /** Upper bound for one Drive JSON response body. */
    public static final int MAX_JSON_RESPONSE_BYTES = 16 * 1024 * 1024;

    private DriveConstants() {
    }
}
