package com.litemobiletools.dailyexpensetracker.drive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.litemobiletools.dailyexpensetracker.backup.BackupFileNames;
import com.litemobiletools.dailyexpensetracker.drive.DriveOpResult.Kind;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the Phase 7 backup operations against a local HTTP server (production URLs are HTTPS). */
public class DriveBackupApiTest {

    private ServerSocket server;
    private Thread serverThread;
    private volatile int status;
    /** Bodies served per request, in order; the last one repeats. */
    private volatile String[] bodies = new String[]{"{\"files\":[]}"};
    /** When true, the server claims a huge Content-Length but serves a tiny body. */
    private volatile boolean claimHugeLength;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>("");

    @Before
    public void startServer() throws IOException {
        status = 200;
        bodies = new String[]{"{\"files\":[]}"};
        claimHugeLength = false;
        requestCount.set(0);
        server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        serverThread = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    handle(socket);
                } catch (IOException ignored) {
                    // Closed by the test.
                }
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void handle(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
        String requestLine = readLine(in, lineBuf);
        String[] parts = requestLine.split(" ");
        method.set(parts[0]);
        path.set(parts[1]);
        int q = parts[1].indexOf('?');
        query.set(q < 0 ? "" : parts[1].substring(q + 1));
        int contentLength = 0;
        String line;
        while ((line = readLine(in, lineBuf)) != null && !line.isEmpty()) {
            if (line.regionMatches(true, 0, "Authorization:", 0, 14)) {
                authorization.set(line.substring(14).trim());
            } else if (line.regionMatches(true, 0, "Content-Type:", 0, 13)) {
                contentType.set(line.substring(13).trim());
            } else if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                contentLength = Integer.parseInt(line.substring(15).trim());
            }
        }
        if (contentLength > 0) {
            byte[] bodyBytes = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = in.read(bodyBytes, read, contentLength - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            receivedBody.set(new String(bodyBytes, 0, read, StandardCharsets.UTF_8));
        } else {
            receivedBody.set("");
        }
        int which = Math.min(requestCount.getAndIncrement(), bodies.length - 1);
        byte[] bytes = bodies[which].getBytes(StandardCharsets.UTF_8);
        long declared = claimHugeLength ? 999_999_999 : bytes.length;
        String head = "HTTP/1.1 " + status + " X\r\nContent-Length: " + declared
                + "\r\nConnection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private String readLine(InputStream in, ByteArrayOutputStream buffer) throws IOException {
        buffer.reset();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                String text = buffer.toString("UTF-8");
                return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
            }
            buffer.write(c);
        }
        return null;
    }

    @After
    public void stopServer() throws IOException {
        server.close();
    }

    private String base() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    private DriveBackupApi api() {
        return new DriveBackupApi(base() + "/drive/v3/files", base() + "/upload/drive/v3/files");
    }

    // ---- List -------------------------------------------------------------------------

    private static final String PAGE_ONE = "{\"files\":["
            + "{\"id\":\"f1\",\"name\":\"daily-expense-backup-20261022-165000.json\","
            + "\"size\":\"4096\",\"modifiedTime\":\"2026-10-22T16:50:00.000Z\"},"
            + "{\"id\":\"x1\",\"name\":\"something-else.json\",\"size\":\"10\","
            + "\"modifiedTime\":\"2026-10-21T10:00:00.000Z\"}],"
            + "\"nextPageToken\":\"tok\"}";
    private static final String PAGE_TWO = "{\"files\":["
            + "{\"id\":\"f2\",\"name\":\"daily-expense-backup-20261023-100000.json\","
            + "\"size\":\"5000\",\"modifiedTime\":\"2026-10-23T10:00:00.000Z\"}]}";

    @Test
    public void listFiltersToBackupNamesAcrossPages() {
        requestCount.set(0);
        bodies = new String[]{PAGE_ONE, PAGE_TWO};
        DriveOpResult<List<DriveBackupFileInfo>> result =
                api().listAppDataFiles("tok", BackupFileNames::isBackupFileName);
        assertEquals(Kind.OK, result.kind);
        List<DriveBackupFileInfo> files = result.value;
        assertEquals(2, files.size());
        assertEquals("f1", files.get(0).id);
        assertEquals("daily-expense-backup-20261022-165000.json", files.get(0).name);
        assertEquals(4096, files.get(0).sizeBytes);
        Calendar expected = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        expected.clear();
        expected.set(2026, Calendar.OCTOBER, 22, 16, 50, 0);
        assertEquals(expected.getTimeInMillis(), files.get(0).modifiedTimeMs);
        assertEquals("f2", files.get(1).id);
        assertTrue(query.get().contains("spaces=appDataFolder"));
        assertTrue(query.get().contains("pageToken=tok"));
        assertEquals("Bearer tok", authorization.get());
    }

    @Test
    public void listMapsEveryDriveFailureKind() {
        assertKind(401, "{\"error\":{\"code\":401,\"status\":\"UNAUTHENTICATED\"}}",
                Kind.UNAUTHORIZED);
        assertKind(403, "{\"error\":{\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}",
                Kind.RATE_LIMITED);
        assertKind(403, "{\"error\":{\"errors\":[{\"reason\":\"accessNotConfigured\"}]}}",
                Kind.API_NOT_ENABLED);
        assertKind(403, "", Kind.FORBIDDEN);
        assertKind(500, "", Kind.SERVER_ERROR);
        assertKind(404, "", Kind.NOT_FOUND);

        bodies = new String[]{"not json"};
        DriveOpResult<List<DriveBackupFileInfo>> bad = api().listAppDataFiles("tok", null);
        assertEquals(Kind.MALFORMED_RESPONSE, bad.kind);
        assertNull(bad.value);
        assertFalse(bad.isRetryable());

        assertEquals(Kind.NETWORK, new DriveBackupApi("http://127.0.0.1:1/files", base())
                .listAppDataFiles("tok", null).kind);
    }

    private void assertKind(int httpStatus, String responseBody, Kind expected) {
        status = httpStatus;
        bodies = new String[]{responseBody};
        DriveOpResult<?> result = api().listAppDataFiles("tok", null);
        status = 200;
        assertEquals(expected, result.kind);        assertEquals(DriveOpResult.failure(expected, 0).isRetryable(), result.isRetryable());
    }

    // ---- Upload -----------------------------------------------------------------------

    @Test
    public void uploadSendsOneMultipartRequestAndReportsIt() {
        requestCount.set(0);
        bodies = new String[]{"{\"id\":\"new1\",\"name\":\"daily-expense-backup-20261022-170000.json\",\"size\":\"9\"}"};
        byte[] content = "{\"data\":1}".getBytes(StandardCharsets.UTF_8); // 9 bytes
        List<Long> done = new ArrayList<>();
        List<Long> total = new ArrayList<>();
        DriveOpResult<DriveBackupFileInfo> result = api().upload("tok2",
                "daily-expense-backup-20261022-170000.json", content, (d, t) -> {
                    done.add(d);
                    total.add(t);
                });
        assertEquals(Kind.OK, result.kind);
        assertEquals("new1", result.value.id);
        assertEquals("daily-expense-backup-20261022-170000.json", result.value.name);
        assertEquals(9, result.value.sizeBytes);
        assertEquals("POST", method.get());
        assertEquals("Bearer tok2", authorization.get());
        assertEquals("uploadType=multipart&fields=id%2Cname%2Csize", query.get());
        String body = receivedBody.get();
        assertTrue(contentType.get().startsWith("multipart/related; boundary=\""));
        assertTrue(body.contains("Content-Type: application/json; charset=UTF-8"));
        assertTrue(body.contains("{\"name\":\"daily-expense-backup-20261022-170000.json\","
                + "\"parents\":[\"appDataFolder\"],\"mimeType\":\"application/json\"}"));
        assertTrue(body.contains(new String(content, StandardCharsets.UTF_8)));
        // Progress reached the full transfer (head + content + tail) at least once; both values agree.
        assertFalse(total.isEmpty());
        assertEquals(done.get(done.size() - 1), total.get(total.size() - 1));
        assertTrue(total.get(total.size() - 1) >= content.length);
    }

    @Test
    public void uploadRejectsAreTyped() {
        assertUpload(401, Kind.UNAUTHORIZED);
        assertUpload(503, Kind.SERVER_ERROR);
        assertUpload(429, Kind.RATE_LIMITED);
        status = 200;
        bodies = new String[]{"not json"};
        DriveOpResult<DriveBackupFileInfo> bad = api().upload("tok", "n", new byte[1], null);
        assertEquals(Kind.MALFORMED_RESPONSE, bad.kind);
        assertNull(bad.value);
        DriveOpResult<DriveBackupFileInfo> dead = new DriveBackupApi(base(), "http://127.0.0.1:1/upload")
                .upload("tok", "n", new byte[1], null);
        assertEquals(Kind.NETWORK, dead.kind);
    }

    private void assertUpload(int httpStatus, Kind expected) {
        status = httpStatus;
        bodies = new String[]{""};
        DriveOpResult<?> result = api().upload("tok", "n", new byte[1], null);
        status = 200;
        assertEquals(expected, result.kind);
        assertEquals(DriveOpResult.failure(expected, 0).isRetryable(), result.isRetryable());
    }

    // ---- Verify -----------------------------------------------------------------------

    @Test
    public void verifyReturnsTheStoredMetadata() {
        requestCount.set(0);
        bodies = new String[]{"{\"id\":\"f9\",\"name\":\"daily-expense-backup-20261022-180000.json\",\"size\":\"42\"}"};
        DriveOpResult<DriveBackupFileInfo> result = api().verify("tok", "f9");
        assertEquals(Kind.OK, result.kind);
        assertEquals("f9", result.value.id);
        assertEquals("daily-expense-backup-20261022-180000.json", result.value.name);
        assertEquals(42, result.value.sizeBytes);
        assertEquals("GET", method.get());
        assertTrue(path.get().startsWith("/drive/v3/files/f9"));
        assertTrue(query.get().contains("fields=id%2Cname%2Csize"));
    }

    @Test
    public void verifyMissingFileIsNotFound() {
        status = 404;
        bodies = new String[]{""};
        DriveOpResult<DriveBackupFileInfo> result = api().verify("tok", "gone");
        status = 200;
        assertEquals(Kind.NOT_FOUND, result.kind);
        assertNull(result.value);
    }

    // ---- Download ---------------------------------------------------------------------

    @Test
    public void downloadReturnsTheBytes() {
        requestCount.set(0);
        bodies = new String[]{"{\"data\":12345}"};
        DriveOpResult<byte[]> result = api().download("tok3", "f1", null);
        assertEquals(Kind.OK, result.kind);
        assertEquals("{\"data\":12345}", new String(result.value, StandardCharsets.UTF_8));
        assertEquals("GET", method.get());
        assertTrue(path.get().startsWith("/drive/v3/files/f1"));
        assertTrue(query.get().contains("alt=media"));
        assertEquals("Bearer tok3", authorization.get());
    }

    @Test
    public void downloadErrorsAreTyped() {
        status = 404;
        bodies = new String[]{""};
        assertEquals(Kind.NOT_FOUND, api().download("tok", "gone", null).kind);
        status = 500;
        assertEquals(Kind.SERVER_ERROR, api().download("tok", "gone", null).kind);
        status = 200;
        DriveOpResult<byte[]> dead = new DriveBackupApi("http://127.0.0.1:1/files", base())
                .download("tok", "f1", null);
        assertEquals(Kind.NETWORK, dead.kind);
    }

    @Test
    public void downloadRefusesFilesOverTheCapWithoutReadingThem() {
        requestCount.set(0);
        bodies = new String[]{"{}"};
        claimHugeLength = true;
        try {
            DriveOpResult<byte[]> result = api().download("tok", "big", null);
            assertEquals(Kind.TOO_LARGE, result.kind);
        } finally {
            claimHugeLength = false;
        }
    }

    // ---- Housekeeping -----------------------------------------------------------------

    @Test
    public void constantsStayHttpsAndAppDataOnly() {
        assertTrue(DriveConstants.FILES_URL.startsWith("https://"));
        assertTrue(DriveConstants.UPLOAD_URL.startsWith("https://"));
        assertEquals("https://www.googleapis.com/auth/drive.appdata", DriveConstants.SCOPE_APPDATA);
    }

    // ---- Delete (Phase 8 automatic backup) ---------------------------------------------

    @Test
    public void deleteSendsDeleteRequestWithBearerToken() {
        DriveOpResult<Boolean> result = api().delete("tok", "backup-1");
        assertEquals(Kind.OK, result.kind);
        assertEquals(Boolean.TRUE, result.value);
        assertEquals("DELETE", method.get());
        assertEquals("/drive/v3/files/backup-1", path.get());
        assertEquals("Bearer tok", authorization.get());
        assertEquals(0, receivedBody.get().length());
    }

    @Test
    public void deleteAccepts204And200AndCountsMissingAsDeleted() {
        requestCount.set(0);
        status = 204;
        bodies = new String[]{""};
        assertEquals(Kind.OK, api().delete("tok", "a").kind);
        requestCount.set(0);
        status = 200;
        bodies = new String[]{"{}"};
        assertEquals(Kind.OK, api().delete("tok", "b").kind);
        // The file is already gone: the wanted end state is still "deleted".
        requestCount.set(0);
        status = 404;
        bodies = new String[]{"{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\"}}"};
        DriveOpResult<Boolean> gone = api().delete("tok", "c");
        assertEquals(Kind.OK, gone.kind);
        status = 200;
    }

    @Test
    public void deleteMapsDriveFailures() {
        requestCount.set(0);
        status = 401;
        bodies = new String[]{"{\"error\":{\"code\":401,\"status\":\"UNAUTHENTICATED\"}}"};
        assertEquals(Kind.UNAUTHORIZED, api().delete("tok", "a").kind);
        requestCount.set(0);
        status = 429;
        bodies = new String[]{"{\"error\":{\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}"};
        assertEquals(Kind.RATE_LIMITED, api().delete("tok", "a").kind);
        requestCount.set(0);
        status = 200;
        bodies = new String[]{"{}"};
        assertEquals(Kind.NETWORK, new DriveBackupApi("http://127.0.0.1:1/files", base())
                .delete("tok", "a").kind);
    }

    @Test
    public void opResultToStringCarriesNoPayload() {
        DriveOpResult<DriveBackupFileInfo> ok = DriveOpResult.ok(
                new DriveBackupFileInfo("f1", "daily-expense-backup-20261022-165000.json", 12, 0));
        assertFalse(ok.toString().contains("daily-expense-backup"));
        assertFalse(ok.isRetryable());
        assertTrue(DriveOpResult.failure(Kind.SERVER_ERROR, 503).isRetryable());
        assertTrue(DriveOpResult.failure(Kind.RATE_LIMITED, 429).isRetryable());
        assertTrue(DriveOpResult.failure(Kind.NETWORK, 0).isRetryable());
        assertFalse(DriveOpResult.failure(Kind.DRIVE_FULL, 403).isRetryable());
    }
}
