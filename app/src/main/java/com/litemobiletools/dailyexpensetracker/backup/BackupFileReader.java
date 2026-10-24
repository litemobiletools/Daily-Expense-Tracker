package com.litemobiletools.dailyexpensetracker.backup;

import androidx.annotation.NonNull;

import com.litemobiletools.dailyexpensetracker.drive.DriveConstants;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads the split structure of the backup file: a one-line header, one newline separator byte,
 * then the raw data bytes. Checks the header shape (format id and version, metadata, record
 * counts, integrity block) before anything else, then verifies the SHA-256 over the exact data
 * bytes — no JSON re-serialization involved in the check. Uses only Android's built-in
 * {@code org.json} for parsing.
 */
public final class BackupFileReader {

    /** One read backup file: the verified header and the raw, checksum-verified data bytes. */
    public static final class BackupFile {
        @NonNull public final BackupHeadMeta head;
        @NonNull public final byte[] dataBytes;

        BackupFile(@NonNull BackupHeadMeta head, @NonNull byte[] dataBytes) {
            this.head = head;
            this.dataBytes = dataBytes;
        }
    }

    private BackupFileReader() {
    }

    /**
     * Parses and verifies a complete backup file. Throws {@link BackupFormatException} with a
     * typed reason; local data is never touched when reading fails.
     *
     * @param bytes the raw file bytes (as downloaded)
     * @param currentFormatVersion the format version this app understands (for future checks)
     */
    @NonNull
    public static BackupFile read(@NonNull byte[] bytes, int currentFormatVersion)
            throws BackupFormatException {
        if (bytes.length == 0) {
            throw new BackupFormatException(BackupFormatException.Reason.EMPTY_FILE);
        }
        if (bytes.length > DriveConstants.MAX_BACKUP_BYTES) {
            throw new BackupFormatException(BackupFormatException.Reason.TOO_LARGE);
        }
        int separator = indexOf(bytes, BackupFormat.HEADER_SEPARATOR);
        if (separator < 0) {
            throw new BackupFormatException(BackupFormatException.Reason.MALFORMED_HEADER);
        }
        String headerText = new String(bytes, 0, separator, StandardCharsets.UTF_8);
        JSONObject header;
        try {
            header = new JSONObject(headerText);
        } catch (JSONException e) {
            throw new BackupFormatException(BackupFormatException.Reason.MALFORMED_HEADER, null, e);
        }
        String format = optString(header, "format");
        if (!BackupFormat.FORMAT_ID.equals(format)) {
            throw new BackupFormatException(BackupFormatException.Reason.UNSUPPORTED_FORMAT);
        }
        int formatVersion;
        try {
            formatVersion = header.getInt("formatVersion");
        } catch (JSONException e) {
            throw new BackupFormatException(BackupFormatException.Reason.MALFORMED_HEADER, null, e);
        }
        if (formatVersion > currentFormatVersion) {
            throw new BackupFormatException(BackupFormatException.Reason.NEWER_VERSION,
                    "file format " + formatVersion + ", app supports " + currentFormatVersion);
        }
        if (formatVersion <= 0) {
            throw new BackupFormatException(BackupFormatException.Reason.UNSUPPORTED_VERSION);
        }
        JSONObject metadata = optObject(header, "metadata");
        String appId = optString(metadata, "appId");
        if (appId.isEmpty()) {
            throw new BackupFormatException(BackupFormatException.Reason.MALFORMED_HEADER);
        }
        JSONObject recordCountsJson = optObject(header, "recordCounts");
        Map<String, Integer> recordCounts = new HashMap<>();
        for (String tableKey : BackupFormat.ALL_TABLES) {
            if (!recordCountsJson.has(tableKey)) {
                throw new BackupFormatException(BackupFormatException.Reason.MISSING_TABLE,
                        "header has no record count for " + tableKey);
            }
            try {
                recordCounts.put(tableKey, recordCountsJson.getInt(tableKey));
            } catch (JSONException e) {
                throw new BackupFormatException(BackupFormatException.Reason.MALFORMED_HEADER,
                        "record count for " + tableKey, e);
            }
            if (recordCounts.get(tableKey) == null || recordCounts.get(tableKey) < 0) {
                throw new BackupFormatException(BackupFormatException.Reason.MALFORMED_HEADER,
                        "record count for " + tableKey);
            }
        }
        JSONObject integrity = optObject(header, "integrity");
        String algorithm = optString(integrity, "algorithm");
        if (!BackupFormat.HASH_ALGORITHM.equals(algorithm)) {
            throw new BackupFormatException(BackupFormatException.Reason.UNSUPPORTED_FORMAT,
                    "integrity algorithm");
        }
        String checksumHex = optString(integrity, "dataSha256");
        if (!BackupIntegrity.isSha256Hex(checksumHex)) {
            throw new BackupFormatException(BackupFormatException.Reason.MALFORMED_HEADER,
                    "digest");
        }
        // createdAtMs and schema metadata belong to the data definition; their validity is
        // checked against the data during parsing/validation.
        try {
            if (header.getLong("createdAtMs") <= 0) {
                throw new BackupFormatException(BackupFormatException.Reason.MALFORMED_HEADER);
            }
        } catch (JSONException e) {
            throw new BackupFormatException(BackupFormatException.Reason.MALFORMED_HEADER, null, e);
        }

        byte[] dataBytes = new byte[bytes.length - separator - 1];
        System.arraycopy(bytes, separator + 1, dataBytes, 0, dataBytes.length);
        // Integrity: the recorded digest must match the exact data bytes that follow the header.
        if (!checksumHex.equalsIgnoreCase(BackupIntegrity.sha256Hex(dataBytes))) {
            throw new BackupFormatException(BackupFormatException.Reason.CHECKSUM_MISMATCH);
        }
        BackupHeadMeta head = parseHead(header, formatVersion, appId, recordCounts, checksumHex);
        return new BackupFile(head, dataBytes);
    }

    @NonNull
    private static BackupHeadMeta parseHead(@NonNull JSONObject header, int formatVersion,
                                            @NonNull String appId,
                                            @NonNull Map<String, Integer> recordCounts,
                                            @NonNull String checksumHex) throws BackupFormatException {
        JSONObject metadata = optObject(header, "metadata");
        try {
            String appVersion = optString(metadata, "appVersion");
            int appVersionCode = metadata.optInt("appVersionCode", 0);
            int dbSchemaVersion = metadata.optInt("dbSchemaVersion", -1);
            if (dbSchemaVersion <= 0) {
                throw new BackupFormatException(BackupFormatException.Reason.MALFORMED_HEADER,
                        "schema version");
            }
            return new BackupHeadMeta(header.getLong("createdAtMs"), formatVersion, appId,
                    appVersion, appVersionCode, dbSchemaVersion, recordCounts, checksumHex);
        } catch (JSONException e) {
            throw new BackupFormatException(BackupFormatException.Reason.MALFORMED_HEADER, null, e);
        }
    }

    private static int indexOf(@NonNull byte[] bytes, byte target) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == target) {
                return i;
            }
        }
        return -1;
    }

    @NonNull
    private static String optString(@NonNull JSONObject object, @NonNull String key)
            throws BackupFormatException {
        try {
            return object.getString(key);
        } catch (JSONException e) {
            throw new BackupFormatException(BackupFormatException.Reason.MALFORMED_HEADER, key, e);
        }
    }

    @NonNull
    private static JSONObject optObject(@NonNull JSONObject object, @NonNull String key)
            throws BackupFormatException {
        try {
            return object.getJSONObject(key);
        } catch (JSONException e) {
            throw new BackupFormatException(BackupFormatException.Reason.MALFORMED_HEADER, key, e);
        }
    }
}
