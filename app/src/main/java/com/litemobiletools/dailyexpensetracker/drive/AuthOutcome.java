package com.litemobiletools.dailyexpensetracker.drive;

import android.app.PendingIntent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Result of asking Google for Drive access. The access token is only carried to the one Drive call
 * that needs it; it is never saved, and {@link #toString()} hides it so it cannot reach a log.
 */
public final class AuthOutcome {

    public enum Kind {
        /** Access is granted and a token is available. */
        GRANTED,
        /** The user has to approve first (consent screen). */
        NEEDS_CONSENT,
        /** Authorization failed; see {@link #errorCode}. */
        FAILED
    }

    /** Failure code for anything without a Google status code. */
    public static final int UNKNOWN = -1;
    /** Failure code for "authorized, but without the Drive permission". */
    public static final int DENIED = -2;

    @NonNull public final Kind kind;
    @Nullable private final String accessToken;
    @Nullable public final PendingIntent consent;
    /** Google API status code for {@link Kind#FAILED}, otherwise 0. */
    public final int errorCode;

    private AuthOutcome(@NonNull Kind kind, @Nullable String accessToken, @Nullable PendingIntent consent, int errorCode) {
        this.kind = kind;
        this.accessToken = accessToken;
        this.consent = consent;
        this.errorCode = errorCode;
    }

    @NonNull
    public static AuthOutcome granted(@NonNull String accessToken) {
        return new AuthOutcome(Kind.GRANTED, accessToken, null, 0);
    }

    @NonNull
    public static AuthOutcome needsConsent(@Nullable PendingIntent consent) {
        return new AuthOutcome(Kind.NEEDS_CONSENT, null, consent, 0);
    }

    @NonNull
    public static AuthOutcome failed(int errorCode) {
        return new AuthOutcome(Kind.FAILED, null, null, errorCode);
    }

    /** The bearer token. Use it for the request in hand and drop it. */
    @Nullable
    public String accessToken() {
        return accessToken;
    }

    @NonNull
    @Override
    public String toString() {
        return "AuthOutcome{" + kind + (kind == Kind.FAILED ? ", code=" + errorCode : "") + "}";
    }
}
