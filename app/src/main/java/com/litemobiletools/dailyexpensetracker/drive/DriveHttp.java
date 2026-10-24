package com.litemobiletools.dailyexpensetracker.drive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Shared HTTP plumbing for the Drive calls: maps Google's replies to typed failure reasons and
 * reads an error body only to find Google's reason, then drops it (and never logs it).
 * Pure and static so it is unit tested against a local server.
 */
final class DriveHttp {

    /** Replies are tiny; anything bigger is not read. */
    private static final int MAX_BODY_BYTES = 16 * 1024;

    private DriveHttp() {
    }

    /** Maps an HTTP status (and Google's error reason, for 403) to a result. */
    static DriveCheckResult classify(int status, String body) {
        if (status == 401) {
            return DriveCheckResult.UNAUTHORIZED;
        }
        if (status == 404) {
            return DriveCheckResult.NOT_FOUND;
        }
        if (status == 429) {
            return DriveCheckResult.RATE_LIMITED;
        }
        if (status >= 500 && status <= 599) {
            return DriveCheckResult.SERVER_ERROR;
        }
        if (status == 403) {
            String text = body == null ? "" : body.toLowerCase(Locale.ROOT);
            if (text.contains("accessnotconfigured") || text.contains("service_disabled")) {
                return DriveCheckResult.API_NOT_ENABLED;
            }
            if (text.contains("ratelimitexceeded") || text.contains("quotaexceeded")
                    || text.contains("dailylimitexceeded") || text.contains("rate_limit_exceeded")) {
                return DriveCheckResult.RATE_LIMITED;
            }
            if (text.contains("storagequotaexceeded") || text.contains("storage_quota_exceeded")
                    || text.contains("teamdrivestoragequotaexceeded")) {
                return DriveCheckResult.DRIVE_FULL;
            }
            if (text.contains("insufficientpermissions") || text.contains("access_token_scope_insufficient")
                    || text.contains("autherror")) {
                return DriveCheckResult.UNAUTHORIZED;
            }
            return DriveCheckResult.FORBIDDEN;
        }
        return DriveCheckResult.ERROR;
    }

    /** Reads Google's error response, capped at {@link #MAX_BODY_BYTES}. The text is never logged. */
    static String readErrorReason(HttpURLConnection connection) {
        InputStream stream = connection.getErrorStream();
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int read;
            while ((read = in.read(buffer)) != -1 && out.size() < MAX_BODY_BYTES) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
