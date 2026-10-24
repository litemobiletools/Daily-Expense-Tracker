package com.litemobiletools.dailyexpensetracker.drive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the Drive check against a local HTTP server (test-only; production URLs are HTTPS). */
public class DriveApiTest {

    private ServerSocket server;
    private Thread serverThread;
    private volatile int status;
    private volatile String body;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @Before
    public void startServer() throws IOException {
        status = 200;
        body = "{\"files\":[]}";
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
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        String requestLine = in.readLine();
        String[] parts = requestLine.split(" ");
        method.set(parts[0]);
        int q = parts[1].indexOf('?');
        query.set(q < 0 ? "" : parts[1].substring(q + 1));
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            if (line.regionMatches(true, 0, "Authorization:", 0, 14)) {
                authorization.set(line.substring(14).trim());
            }
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " X\r\nContent-Length: " + bytes.length
                + "\r\nConnection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    @After
    public void stopServer() throws IOException {
        server.close();
    }

    private DriveApi api() {
        return new DriveApi("http://127.0.0.1:" + server.getLocalPort() + "/drive/v3/files");
    }

    @Test
    public void okIsReadOnlyGetOfTheAppDataFolderWithMinimalFields() {
        assertEquals(DriveCheckResult.OK, api().checkAppDataAccess("tok"));
        assertEquals("GET", method.get());
        assertEquals("spaces=appDataFolder&pageSize=1&fields=files(id)", query.get());
        assertEquals("Bearer tok", authorization.get());
    }

    @Test
    public void unauthorizedWhen401() {
        status = 401;
        body = "{\"error\":{\"code\":401,\"status\":\"UNAUTHENTICATED\"}}";
        assertEquals(DriveCheckResult.UNAUTHORIZED, api().checkAppDataAccess("tok"));
    }

    @Test
    public void apiNotEnabledWhen403AccessNotConfigured() {
        status = 403;
        body = "{\"error\":{\"errors\":[{\"reason\":\"accessNotConfigured\"}]}}";
        assertEquals(DriveCheckResult.API_NOT_ENABLED, api().checkAppDataAccess("tok"));
    }

    @Test
    public void rateLimitedWhen429() {
        status = 429;
        body = "";
        assertEquals(DriveCheckResult.RATE_LIMITED, api().checkAppDataAccess("tok"));
    }

    @Test
    public void serverErrorWhen503() {
        status = 503;
        body = "";
        assertEquals(DriveCheckResult.SERVER_ERROR, api().checkAppDataAccess("tok"));
    }

    @Test
    public void aDeletedFileIsTypedAsNotFound() {
        status = 404;
        body = "";
        assertEquals(DriveCheckResult.NOT_FOUND, api().checkAppDataAccess("tok"));
    }

    @Test
    public void unexpectedStatusIsAGenericError() {
        status = 418;
        body = "";
        assertEquals(DriveCheckResult.ERROR, api().checkAppDataAccess("tok"));
    }

    @Test
    public void unreachableServerIsANetworkResult() {
        DriveApi unreachable = new DriveApi("http://127.0.0.1:1/drive/v3/files");
        assertEquals(DriveCheckResult.NETWORK, unreachable.checkAppDataAccess("tok"));
    }

    @Test
    public void classifyHandlesEveryForbiddenReason() {
        assertEquals(DriveCheckResult.API_NOT_ENABLED, DriveApi.classify(403, "SERVICE_DISABLED"));
        assertEquals(DriveCheckResult.RATE_LIMITED, DriveApi.classify(403, "{\"reason\":\"userRateLimitExceeded\"}"));
        assertEquals(DriveCheckResult.UNAUTHORIZED, DriveApi.classify(403, "{\"reason\":\"insufficientPermissions\"}"));
        assertEquals(DriveCheckResult.FORBIDDEN, DriveApi.classify(403, ""));
        assertEquals(DriveCheckResult.UNAUTHORIZED, DriveApi.classify(401, ""));
        assertEquals(DriveCheckResult.SERVER_ERROR, DriveApi.classify(500, ""));
    }

    @Test
    public void requestedScopeIsOnlyAppData() {
        assertEquals("https://www.googleapis.com/auth/drive.appdata", DriveConstants.SCOPE_APPDATA);
        assertTrue(DriveConstants.FILES_URL.startsWith("https://"));
        assertFalse(DriveConstants.SCOPE_APPDATA.endsWith("/drive"));
    }

    @Test
    public void outcomeToStringNeverRevealsTheToken() {
        AuthOutcome outcome = AuthOutcome.granted("SECRET-TOKEN-123");
        assertFalse(outcome.toString().contains("SECRET"));
        assertNull(AuthOutcome.failed(10).accessToken());
    }
}
