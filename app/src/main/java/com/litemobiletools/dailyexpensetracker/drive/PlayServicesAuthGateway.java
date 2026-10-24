package com.litemobiletools.dailyexpensetracker.drive;

import android.accounts.Account;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.auth.api.identity.AuthorizationClient;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.ClearTokenRequest;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.auth.api.identity.RevokeAccessRequest;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;

import java.util.Collections;
import java.util.List;

/**
 * Google Identity {@link AuthorizationClient} implementation. Requests only the drive.appdata scope; no
 * profile or email scope, so the app never learns who the account is. Tokens are held only in memory.
 */
public final class PlayServicesAuthGateway implements DriveAuthGateway {

    private final AuthorizationClient client;

    public PlayServicesAuthGateway(@NonNull Context context) {
        this.client = Identity.getAuthorizationClient(context.getApplicationContext());
    }

    @NonNull
    private static AuthorizationRequest request() {
        List<Scope> scopes = Collections.singletonList(new Scope(DriveConstants.SCOPE_APPDATA));
        return AuthorizationRequest.builder().setRequestedScopes(scopes).build();
    }

    @Override
    public void authorize(@NonNull Callback<AuthOutcome> callback) {
        try {
            client.authorize(request())
                    .addOnSuccessListener(result -> callback.onResult(toOutcome(result)))
                    .addOnFailureListener(e -> callback.onResult(AuthOutcome.failed(codeOf(e))));
        } catch (RuntimeException e) {
            callback.onResult(AuthOutcome.failed(AuthOutcome.UNKNOWN));
        }
    }

    @NonNull
    @Override
    public AuthOutcome parseConsentResult(@Nullable Intent data) {
        if (data == null) {
            return AuthOutcome.failed(AuthOutcome.UNKNOWN);
        }
        try {
            return toOutcome(client.getAuthorizationResultFromIntent(data));
        } catch (ApiException e) {
            return AuthOutcome.failed(e.getStatusCode());
        } catch (RuntimeException e) {
            return AuthOutcome.failed(AuthOutcome.UNKNOWN);
        }
    }

    @Override
    public void revoke(@NonNull Callback<RevokeResult> callback) {
        try {
            client.authorize(request()).addOnSuccessListener(result -> {
                if (result.hasResolution() || result.getAccessToken() == null) {
                    // The user has not granted (or already withdrew) access: nothing to revoke.
                    callback.onResult(RevokeResult.NOTHING_TO_REVOKE);
                    return;
                }
                GoogleSignInAccount signIn = result.toGoogleSignInAccount();
                Account account = signIn == null ? null : signIn.getAccount();
                String token = result.getAccessToken();
                if (account == null) {
                    // Google needs the account to revoke; without it we can only drop the cached token.
                    clearToken(token);
                    callback.onResult(RevokeResult.ACCOUNT_UNKNOWN);
                    return;
                }
                RevokeAccessRequest revoke = RevokeAccessRequest.builder()
                        .setAccount(account)
                        .setScopes(Collections.singletonList(new Scope(DriveConstants.SCOPE_APPDATA)))
                        .build();
                client.revokeAccess(revoke)
                        .addOnSuccessListener(unused -> {
                            clearToken(token);
                            callback.onResult(RevokeResult.REVOKED);
                        })
                        .addOnFailureListener(e -> callback.onResult(RevokeResult.FAILED));
            }).addOnFailureListener(e -> callback.onResult(RevokeResult.FAILED));
        } catch (RuntimeException e) {
            callback.onResult(RevokeResult.FAILED);
        }
    }

    @Override
    public void clearToken(@NonNull String accessToken) {
        try {
            client.clearToken(ClearTokenRequest.builder().setToken(accessToken).build());
        } catch (RuntimeException ignored) {
            // Best effort: the token is short-lived and is not stored anywhere by the app.
        }
    }

    @NonNull
    private static AuthOutcome toOutcome(@NonNull AuthorizationResult result) {
        if (result.hasResolution()) {
            return AuthOutcome.needsConsent(result.getPendingIntent());
        }
        String token = result.getAccessToken();
        if (token == null || token.isEmpty()) {
            return AuthOutcome.failed(AuthOutcome.UNKNOWN);
        }
        if (!result.getGrantedScopes().contains(DriveConstants.SCOPE_APPDATA)) {
            // The user left the Drive permission unticked on the consent screen.
            return AuthOutcome.failed(AuthOutcome.DENIED);
        }
        return AuthOutcome.granted(token);
    }

    private static int codeOf(@NonNull Exception e) {
        return e instanceof ApiException ? ((ApiException) e).getStatusCode() : AuthOutcome.UNKNOWN;
    }
}
