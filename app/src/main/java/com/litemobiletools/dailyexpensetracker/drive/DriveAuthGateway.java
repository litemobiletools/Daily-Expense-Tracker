package com.litemobiletools.dailyexpensetracker.drive;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The part of Google Identity the connection logic needs. The real implementation is
 * {@link PlayServicesAuthGateway}; tests use a fake. Callbacks arrive on the main thread.
 */
public interface DriveAuthGateway {

    interface Callback<T> {
        void onResult(@NonNull T result);
    }

    /**
     * Asks for the drive.appdata scope. If access was granted before, the outcome is
     * {@link AuthOutcome.Kind#GRANTED} without any UI; otherwise {@link AuthOutcome.Kind#NEEDS_CONSENT}.
     */
    void authorize(@NonNull Callback<AuthOutcome> callback);

    /** Reads the outcome of the consent screen. */
    @NonNull
    AuthOutcome parseConsentResult(@Nullable Intent data);

    /** Asks Google to withdraw the app's access. */
    void revoke(@NonNull Callback<RevokeResult> callback);

    /** Removes a token Google's cache still holds after Drive rejected it. */
    void clearToken(@NonNull String accessToken);
}
