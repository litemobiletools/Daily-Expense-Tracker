package com.litemobiletools.dailyexpensetracker.drive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Outcome of one Drive backup operation (list, upload, verify, download). Carries the typed
 * failure reason when it is not {@link Kind#OK}; details are safe to show (no tokens, no data).
 *
 * @param <T> success payload type
 */
public final class DriveOpResult<T> {

    public enum Kind {
        /** Succeeded; {@link #value} is set. */
        OK,
        /** 401 or missing scope: authorize again through the existing connection flow. */
        UNAUTHORIZED,
        /** 403 the app cannot resolve to a user action (Google project / policy configuration). */
        FORBIDDEN,
        /** 403 saying the Drive API is not enabled for the Google Cloud project. */
        API_NOT_ENABLED,
        /** 429 or a 403 quota reason: temporary, retry later. */
        RATE_LIMITED,
        /** 5xx from Google: temporary, retry later. */
        SERVER_ERROR,
        /** The requested file is gone (deleted or the id was wrong). */
        NOT_FOUND,
        /** The user's Drive is full; the file could not be stored. */
        DRIVE_FULL,
        /** No connection, DNS failure or timeout. */
        NETWORK,
        /** The file is bigger than the app accepts. */
        TOO_LARGE,
        /** Google's reply could not be parsed, or a post-upload verification failed. */
        MALFORMED_RESPONSE,
        /** Anything else unexpected. */
        FAILED;
    }

    @NonNull public final Kind kind;
    @Nullable public final T value;
    /** HTTP status behind the failure (0 if none), safe to show. */
    public final int httpStatus;

    private DriveOpResult(@NonNull Kind kind, @Nullable T value, int httpStatus) {
        this.kind = kind;
        this.value = value;
        this.httpStatus = httpStatus;
    }

    @NonNull
    public static <T> DriveOpResult<T> ok(@NonNull T value) {
        return new DriveOpResult<>(Kind.OK, value, 200);
    }

    @NonNull
    public static <T> DriveOpResult<T> failure(@NonNull Kind kind, int httpStatus) {
        return new DriveOpResult<>(kind, null, httpStatus);
    }

    /** True for temporary failures where trying again can succeed. */
    public boolean isRetryable() {
        return kind == Kind.NETWORK || kind == Kind.RATE_LIMITED || kind == Kind.SERVER_ERROR;
    }

    @NonNull
    @Override
    public String toString() {
        // value type may hold reportable data; only the kind and status are exposed.
        return "DriveOpResult{" + kind + ", http=" + httpStatus + "}";
    }
}
