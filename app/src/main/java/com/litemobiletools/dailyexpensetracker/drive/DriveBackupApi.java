package com.litemobiletools.dailyexpensetracker.drive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.litemobiletools.dailyexpensetracker.util.JsonWriter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Drive REST operations for the app's private {@code appDataFolder} backup (Phase 7): list the
 * backup files, upload a new one (multipart, one request), verify its stored metadata, download
 * one. Plain {@link HttpURLConnection} like the Phase 6 check; the scope stays
 * {@code drive.appdata} only, so Drive never exposes anything outside the app's hidden folder.
 *
 * <p>Worker thread only. The token is sent only in the Authorization header over HTTPS and is
 * never logged; error bodies are read only to find Google's reason, then dropped. Whole backups
 * are small (a few MB in practice); transfer and storage are hard-capped at
 * {@link DriveConstants#MAX_BACKUP_BYTES} to bound memory use.
 */
public class DriveBackupApi {

    /** At most once every this many written/read bytes a progress callback fires. */
    static final long PROGRESS_STEP_BYTES = 256 * 1024;
    private static final int MAX_LIST_PAGES = 2;
    private static final int PAGE_SIZE = 200;
    private static final int STREAM_CHUNK = 32 * 1024;

    private final String filesUrl;
    private final String uploadUrl;

    public DriveBackupApi() {
        this(DriveConstants.FILES_URL, DriveConstants.UPLOAD_URL);
    }

    /** For tests, which point these at a local server. Production code uses the HTTPS defaults. */
    DriveBackupApi(@NonNull String filesUrl, @NonNull String uploadUrl) {
        this.filesUrl = filesUrl;
        this.uploadUrl = uploadUrl;
    }

    /** Decides what {@link #listAppDataFiles} returns. Production code filters to backup file names. */
    public interface NameFilter {
        boolean accept(@NonNull String name);
    }

    /** Reports streamed bytes of the current operation, on the worker thread it runs on. */
    public interface Progress {
        void onProgress(long done, long total);
    }

    // ---- List ----------------------------------------------------------------------------

    /**
     * Lists files in the app data folder (newest first, up to {@code 2 x PAGE_SIZE}), filtered by
     * {@code nameFilter}. Only the app's own hidden folder is ever contacted.
     */
    @NonNull
    public DriveOpResult<List<DriveBackupFileInfo>> listAppDataFiles(@NonNull String accessToken,
                                                                    @Nullable NameFilter nameFilter) {
        List<DriveBackupFileInfo> all = new ArrayList<>();
        String pageToken = null;
        for (int page = 0; page < MAX_LIST_PAGES; page++) {
            DriveOpResult<Page> result = listPage(accessToken, pageToken);
            if (result.kind != DriveOpResult.Kind.OK) {
                return DriveOpResult.failure(result.kind, result.httpStatus);
            }
            for (DriveBackupFileInfo info : result.value.files) {
                if (nameFilter == null || nameFilter.accept(info.name)) {
                    all.add(info);
                }
            }
            pageToken = result.value.nextPageToken;
            if (pageToken == null) {
                break;
            }
        }
        return DriveOpResult.ok(Collections.unmodifiableList(all));
    }

    private static final class Page {
        final List<DriveBackupFileInfo> files = new ArrayList<>();
        String nextPageToken;
    }

    private DriveOpResult<Page> listPage(@NonNull String accessToken, @Nullable String pageToken) {
        StringBuilder url = new StringBuilder(filesUrl)
                .append("?spaces=appDataFolder")
                .append("&pageSize=").append(PAGE_SIZE)
                .append("&orderBy=modifiedTime%20desc")
                .append("&fields=nextPageToken%2Cfiles(id%2Cname%2Csize%2CmodifiedTime)");
        if (pageToken != null) {
            url.append("&pageToken=").append(encode(pageToken));
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url.toString()).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(DriveApi.CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(DriveApi.READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setRequestProperty("Accept", "application/json");

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                return DriveOpResult.failure(kind(DriveHttp.classify(status,
                        DriveHttp.readErrorReason(connection))), status);
            }
            JSONObject json = new JSONObject(readString(connection.getInputStream()));
            Page page = new Page();
            JSONArray files = json.optJSONArray("files");
            if (files != null) {
                for (int i = 0; i < files.length(); i++) {
                    JSONObject file = files.getJSONObject(i);
                    page.files.add(new DriveBackupFileInfo(
                            file.getString("id"),
                            file.getString("name"),
                            parseLongOrZero(file.optString("size")),
                            parseDriveTime(file.optString("modifiedTime"))));
                }
            }
            page.nextPageToken = json.optString("nextPageToken", null);
            return DriveOpResult.ok(page);
        } catch (IOException e) {
            return DriveOpResult.failure(DriveOpResult.Kind.NETWORK, 0);
        } catch (JSONException | RuntimeException e) {
            return DriveOpResult.failure(DriveOpResult.Kind.MALFORMED_RESPONSE, 0);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ---- Upload --------------------------------------------------------------------------

    /**
     * Uploads {@code fileBytes} under {@code fileName} as a JSON file into the app data folder
     * in one multipart request (atomic: an interrupted request stores nothing). Returns the
     * created file's metadata. The caller verifies it afterwards with {@link #verify}.
     */
    @NonNull
    public DriveOpResult<DriveBackupFileInfo> upload(@NonNull String accessToken,
                                                     @NonNull String fileName,
                                                     @NonNull byte[] fileBytes,
                                                     @Nullable Progress progress) {
        String boundary = "det-backup-" + Long.toHexString(System.nanoTime());
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                + "{\"name\":" + JsonWriter.escapeString(fileName) + ","
                + "\"parents\":[\"appDataFolder\"],\"mimeType\":\"application/json\"}\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + fileBytes.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(fileBytes, 0, body, head.length, fileBytes.length);
        System.arraycopy(tail, 0, body, head.length + fileBytes.length, tail.length);

        HttpURLConnection connection = null;
        try {
            URL url = new URL(uploadUrl + "?uploadType=multipart&fields=id%2Cname%2Csize");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(DriveApi.CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(DriveApi.READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type",
                    "multipart/related; boundary=\"" + boundary + "\"");

            try (OutputStream out = connection.getOutputStream()) {
                ProgressTracker tracker = new ProgressTracker(progress, body.length);
                int sent = 0;
                while (sent < body.length) {
                    int chunk = Math.min(STREAM_CHUNK, body.length - sent);
                    out.write(body, sent, chunk);
                    sent += chunk;
                    tracker.report(sent);
                }
                out.flush();
            }
            if (progress != null) {
                progress.onProgress(body.length, body.length);
            }

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                return DriveOpResult.failure(kind(DriveHttp.classify(status,
                        DriveHttp.readErrorReason(connection))), status);
            }
            JSONObject json = new JSONObject(readString(connection.getInputStream()));
            return DriveOpResult.ok(new DriveBackupFileInfo(
                    json.getString("id"),
                    json.getString("name"),
                    parseLongOrZero(json.optString("size")),
                    0));
        } catch (IOException e) {
            return DriveOpResult.failure(DriveOpResult.Kind.NETWORK, 0);
        } catch (JSONException | RuntimeException e) {
            return DriveOpResult.failure(DriveOpResult.Kind.MALFORMED_RESPONSE, 0);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ---- Verify --------------------------------------------------------------------------

    /** Reads a file's metadata by id, so the caller can confirm what was stored. */
    @NonNull
    public DriveOpResult<DriveBackupFileInfo> verify(@NonNull String accessToken, @NonNull String id) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(filesUrl + "/" + encode(id) + "?fields=id%2Cname%2Csize");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(DriveApi.CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(DriveApi.READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setRequestProperty("Accept", "application/json");

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                return DriveOpResult.failure(kind(DriveHttp.classify(status,
                        DriveHttp.readErrorReason(connection))), status);
            }
            JSONObject json = new JSONObject(readString(connection.getInputStream()));
            return DriveOpResult.ok(new DriveBackupFileInfo(
                    json.getString("id"),
                    json.getString("name"),
                    parseLongOrZero(json.optString("size")),
                    0));
        } catch (IOException e) {
            return DriveOpResult.failure(DriveOpResult.Kind.NETWORK, 0);
        } catch (JSONException | RuntimeException e) {
            return DriveOpResult.failure(DriveOpResult.Kind.MALFORMED_RESPONSE, 0);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ---- Download ------------------------------------------------------------------------

    /**
     * Downloads a file's content by id, capped at {@link DriveConstants#MAX_BACKUP_BYTES}.
     * Progress reports received bytes against the content length when Drive provides it.
     */
    @NonNull
    public DriveOpResult<byte[]> download(@NonNull String accessToken, @NonNull String id,
                                          @Nullable Progress progress) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(filesUrl + "/" + encode(id) + "?alt=media");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(DriveApi.CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(Math.max(DriveApi.READ_TIMEOUT_MS, 30_000));
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setRequestProperty("Accept", "application/json");

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                return DriveOpResult.failure(kind(DriveHttp.classify(status,
                        DriveHttp.readErrorReason(connection))), status);
            }
            long total = connection.getContentLengthLong();
            if (total > DriveConstants.MAX_BACKUP_BYTES) {
                return DriveOpResult.failure(DriveOpResult.Kind.TOO_LARGE, status);
            }
            ByteArrayOutputStream out = readCapped(connection.getInputStream(), progress, total);
            if (out.size() > DriveConstants.MAX_BACKUP_BYTES) {
                return DriveOpResult.failure(DriveOpResult.Kind.TOO_LARGE, status);
            }
            return DriveOpResult.ok(out.toByteArray());
        } catch (IOException e) {
            return DriveOpResult.failure(DriveOpResult.Kind.NETWORK, 0);
        } catch (RuntimeException e) {
            return DriveOpResult.failure(DriveOpResult.Kind.FAILED, 0);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ---- Delete ---------------------------------------------------------------------------

    /**
     * Permanently deletes one file by id from the app data folder. Callers only ever pass ids of
     * files this app itself uploaded (backup-named files); unrelated files are never addressed by
     * this app. A file Google no longer knows about counts as deleted. Used by the automatic
     * backup to retire superseded backups only after the replacement has been verified.
     */
    @NonNull
    public DriveOpResult<Boolean> delete(@NonNull String accessToken, @NonNull String id) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(filesUrl + "/" + encode(id));
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("DELETE");
            connection.setConnectTimeout(DriveApi.CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(DriveApi.READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setRequestProperty("Accept", "application/json");

            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NO_CONTENT || status == HttpURLConnection.HTTP_OK
                    || status == HttpURLConnection.HTTP_NOT_FOUND) {
                // 204/200: deleted. 404: already gone, which is the wanted end state.
                return DriveOpResult.ok(Boolean.TRUE);
            }
            return DriveOpResult.failure(kind(DriveHttp.classify(status,
                    DriveHttp.readErrorReason(connection))), status);
        } catch (IOException e) {
            return DriveOpResult.failure(DriveOpResult.Kind.NETWORK, 0);
        } catch (RuntimeException e) {
            return DriveOpResult.failure(DriveOpResult.Kind.FAILED, 0);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ---- Low level helpers ---------------------------------------------------------------

    @NonNull
    private static ByteArrayOutputStream readCapped(@NonNull InputStream in,
                                                    @Nullable Progress progress, long total)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        ProgressTracker tracker = new ProgressTracker(progress, total > 0 ? total : -1);
        byte[] buffer = new byte[8192];
        long done = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            done += read;
            if (out.size() > DriveConstants.MAX_BACKUP_BYTES) {
                throw new IOException("backup exceeds the supported size");
            }
            tracker.report(done);
        }
        return out;
    }

    /** Throttles progress callbacks to at most one per {@link #PROGRESS_STEP_BYTES}. */
    private static final class ProgressTracker {
        private final Progress progress;
        private final long total;
        private long nextReport;

        ProgressTracker(@Nullable Progress progress, long total) {
            this.progress = progress;
            this.total = total;
            this.nextReport = total <= 0 ? Long.MAX_VALUE : PROGRESS_STEP_BYTES;
        }

        void report(long done) {
            if (progress == null || total <= 0 || done < nextReport) {
                return;
            }
            progress.onProgress(Math.min(done, total), total);
            nextReport = done + PROGRESS_STEP_BYTES;
        }
    }

    /** Reads a small JSON response; oversized replies abort rather than grow without bound. */
    @NonNull
    static String readString(@NonNull InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (out.size() > DriveConstants.MAX_JSON_RESPONSE_BYTES) {
                throw new IOException("response too large");
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @NonNull
    static DriveOpResult.Kind kind(@NonNull DriveCheckResult check) {
        switch (check) {
            case OK:
                return DriveOpResult.Kind.OK;
            case UNAUTHORIZED:
                return DriveOpResult.Kind.UNAUTHORIZED;
            case API_NOT_ENABLED:
                return DriveOpResult.Kind.API_NOT_ENABLED;
            case FORBIDDEN:
                return DriveOpResult.Kind.FORBIDDEN;
            case RATE_LIMITED:
                return DriveOpResult.Kind.RATE_LIMITED;
            case SERVER_ERROR:
                return DriveOpResult.Kind.SERVER_ERROR;
            case NOT_FOUND:
                return DriveOpResult.Kind.NOT_FOUND;
            case DRIVE_FULL:
                return DriveOpResult.Kind.DRIVE_FULL;
            case NETWORK:
                return DriveOpResult.Kind.NETWORK;
            default:
                return DriveOpResult.Kind.FAILED;
        }
    }

    @NonNull
    private static String encode(@NonNull String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            throw new AssertionError("UTF-8 is always available");
        }
    }

    private static long parseLongOrZero(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Drive's RFC 3339 times are display metadata only; best effort, 0 when unreadable. */
    static long parseDriveTime(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX"};
        for (String pattern : patterns) {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            format.setLenient(false);
            try {
                Date date = format.parse(value);
                return date == null ? 0 : date.getTime();
            } catch (ParseException ignored) {
                // Try the next shape.
            }
        }
        return 0;
    }
}
