package com.litemobiletools.dailyexpensetracker.drive;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Read-only Drive REST client. Phase 6 only lists the app's hidden {@code appDataFolder}
 * (one file id at most) to prove the authorization works. It never uploads, changes or deletes anything.
 * The backup operations of Phase 7 live in {@link DriveBackupApi}.
 *
 * <p>Must be called off the main thread. The token is sent only in the Authorization header over HTTPS
 * and is never logged; error bodies are read only to find Google's error reason, then dropped.
 */
public class DriveApi {

    static final int CONNECT_TIMEOUT_MS = 10_000;
    static final int READ_TIMEOUT_MS = 15_000;

    private final String filesUrl;

    public DriveApi() {
        this(DriveConstants.FILES_URL);
    }

    /** For tests, which point this at a local server. Production code uses the HTTPS default. */
    DriveApi(@NonNull String filesUrl) {
        this.filesUrl = filesUrl;
    }

    /** Asks Drive for at most one file id in the app data folder. */
    @NonNull
    public DriveCheckResult checkAppDataAccess(@NonNull String accessToken) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(filesUrl + "?spaces=appDataFolder&pageSize=1&fields=files(id)");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setRequestProperty("Accept", "application/json");

            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_OK) {
                return DriveCheckResult.OK;
            }
            return classify(status, DriveHttp.readErrorReason(connection));
        } catch (IOException e) {
            return DriveCheckResult.NETWORK;
        } catch (RuntimeException e) {
            return DriveCheckResult.ERROR;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Maps an HTTP status (and Google's error reason, for 403) to a result. Pure, so it is unit tested. */
    @NonNull
    static DriveCheckResult classify(int status, @NonNull String body) {
        return DriveHttp.classify(status, body);
    }
}
