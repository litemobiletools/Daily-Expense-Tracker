package com.litemobiletools.dailyexpensetracker.backup;

import androidx.annotation.NonNull;

/** A backup file was rejected. The {@link Reason} is safe to show; details never contain user data. */
public class BackupFormatException extends Exception {

    /** Why a backup was rejected, in words a screen can map to a clear message. */
    public enum Reason {
        /** Nothing there (empty download). */
        EMPTY_FILE,
        /** The file exceeds the supported size. */
        TOO_LARGE,
        /** Not the one-line header the format defines (truncated, edited, or not JSON). */
        MALFORMED_HEADER,
        /** Not a backup of this app (unknown format marker). */
        UNSUPPORTED_FORMAT,
        /** A format version never shipped (older or unknown). */
        UNSUPPORTED_VERSION,
        /** Made by a newer app version; restore is refused without touching local data. */
        NEWER_VERSION,
        /** The backup belongs to another application id. */
        WRONG_APP_ID,
        /** The data bytes no longer match the recorded SHA-256: corrupted or altered. */
        CHECKSUM_MISMATCH,
        /** A required data section is missing (incomplete file). */
        MISSING_TABLE,
        /** The data part is not parsable JSON. */
        MALFORMED_DATA,
        /** A row has a wrong type or decisively broken value. */
        MALFORMED_RECORD,
        /** A row fails validation (bad enum, non-positive amount, duplicate id, ...). */
        INVALID_RECORD,
        /** A row references a record the backup does not contain. */
        BROKEN_REFERENCE,
        /** Header record counts disagree with the data records. */
        COUNT_MISMATCH
    }

    public final Reason reason;

    public BackupFormatException(@NonNull Reason reason) {
        this(reason, null, null);
    }

    /** @param detail context like "transactions[14]" — never record values, never user data. */
    public BackupFormatException(@NonNull Reason reason, String detail) {
        this(reason, detail, null);
    }

    public BackupFormatException(@NonNull Reason reason, String detail, Throwable cause) {
        super(detail == null ? reason.name() : reason.name() + ": " + detail, cause);
        this.reason = reason;
    }
}
