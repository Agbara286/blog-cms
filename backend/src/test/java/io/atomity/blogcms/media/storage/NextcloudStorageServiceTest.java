package io.atomity.blogcms.media.storage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.atomity.blogcms.shared.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class NextcloudStorageServiceTest {

    private HttpServer server;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
    private NextcloudStorageService storage;

    private volatile int mkcolStatus = 201;
    private volatile int propfindStatus = 207;
    private volatile int putStatus = 201;
    private volatile int getFailureStatus;
    private volatile int deleteStatus = 204;

    private static final String DAV_ROOT = "/remote.php/dav/files/test-user";
    private static final String AUTHORIZATION = "Basic " + Base64.getEncoder().encodeToString(
            "test-user:test-password".getBytes(StandardCharsets.UTF_8));

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(DAV_ROOT, this::handleWebDav);
        server.start();

        StorageProperties properties = new StorageProperties(
                "http://localhost:" + server.getAddress().getPort(),
                "test-user",
                "test-password",
                "/BlogCMS");

        storage = new NextcloudStorageService(properties);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        files.clear();
        requests.clear();
        authorizationHeaders.clear();
    }

    @Test
    void storesAndLoadsOriginalFileBytes() throws IOException {
        byte[] original = { 1, 2, 3, 4, 5 };

        storage.store("images/test.png", original);
        Resource resource = storage.load("images/test.png");

        try (var input = resource.getInputStream()) {
            assertArrayEquals(original, input.readAllBytes());
        }
    }

    @Test
    void createsNestedDirectoriesBeforeUploading() {
        storage.store("images/2026/test.png", new byte[] { 1, 2, 3 });

        List<String> createdDirectories = requests.stream()
                .filter(request -> request.startsWith("MKCOL "))
                .toList();

        assertEquals(List.of(
                "MKCOL " + DAV_ROOT + "/BlogCMS",
                "MKCOL " + DAV_ROOT + "/BlogCMS/images",
                "MKCOL " + DAV_ROOT + "/BlogCMS/images/2026"), createdDirectories);
    }

    @Test
    void acceptsDirectoriesThatAlreadyExistWhenPropfindConfirmsThem() {
        mkcolStatus = 405;
        propfindStatus = 207;

        assertDoesNotThrow(
                () -> storage.store("images/test.png", new byte[] { 1, 2, 3 }));

        assertTrue(requests.stream().anyMatch(request -> request.startsWith("PROPFIND ")));
        assertTrue(files.containsKey(DAV_ROOT + "/BlogCMS/images/test.png"));
    }

    @Test
    void rejectsMkcol405WhenPropfindCannotConfirmDirectory() {
        mkcolStatus = 405;
        propfindStatus = 404;

        ApiException exception = assertThrows(
                ApiException.class,
                () -> storage.store("images/test.png", new byte[] { 1, 2, 3 }));

        assertTrue(exception.getMessage().contains("temporarily unavailable"));
    }

    @Test
    void rejectsUnsafeStorageKeysBeforeMakingRequests() {
        assertThrows(
                IllegalArgumentException.class,
                () -> storage.store("../secret.txt", new byte[] { 1 }));

        assertThrows(
                IllegalArgumentException.class,
                () -> storage.load("images/../../secret.txt"));

        assertTrue(requests.isEmpty());
    }

    @Test
    void mapsUploadServerFailuresToStorageUnavailable() {
        putStatus = 500;

        ApiException exception = assertThrows(
                ApiException.class,
                () -> storage.store("images/test.png", new byte[] { 1, 2, 3 }));

        assertTrue(exception.getMessage().contains("temporarily unavailable"));
    }

    @Test
    void mapsDownloadServerFailuresToStorageUnavailable() {
        getFailureStatus = 500;

        ApiException exception = assertThrows(
                ApiException.class,
                () -> storage.load("images/test.png"));

        assertTrue(exception.getMessage().contains("temporarily unavailable"));
    }

    @Test
    void missingFileReturnsNotFound() {
        assertThrows(
                ApiException.class,
                () -> storage.load("missing.png"));
    }

    @Test
    void deleteRemovesStoredFile() {
        String path = DAV_ROOT + "/BlogCMS/images/test.png";
        files.put(path, "image".getBytes(StandardCharsets.UTF_8));

        storage.delete("images/test.png");

        assertFalse(files.containsKey(path));
    }

    @Test
    void deleteFailureDoesNotThrowOrPretendRemoteFileWasDeleted() {
        String path = DAV_ROOT + "/BlogCMS/images/test.png";
        files.put(path, "image".getBytes(StandardCharsets.UTF_8));
        deleteStatus = 500;

        assertDoesNotThrow(() -> storage.delete("images/test.png"));
        assertTrue(files.containsKey(path));
    }

    @Test
    void sendsBasicAuthentication() {
        storage.store("images/auth-test.png", new byte[] { 1 });

        assertTrue(
                authorizationHeaders.contains(AUTHORIZATION),
                "WebDAV requests must include the configured Basic Auth header");
    }

    private void handleWebDav(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        requests.add(method + " " + path);
        authorizationHeaders.add(
                exchange.getRequestHeaders().getFirst("Authorization"));

        if (!AUTHORIZATION.equals(exchange.getRequestHeaders()
                .getFirst("Authorization"))) {
            respond(exchange, 401);
            return;
        }

        switch (method) {
            case "MKCOL" -> respond(exchange, mkcolStatus);

            case "PROPFIND" -> respond(exchange, propfindStatus);

            case "PUT" -> {
                byte[] content = exchange.getRequestBody().readAllBytes();
                if (putStatus >= 200 && putStatus < 300) {
                    files.put(path, content);
                }
                respond(exchange, putStatus);
            }

            case "GET" -> {
                if (getFailureStatus != 0) {
                    respond(exchange, getFailureStatus);
                    return;
                }

                byte[] content = files.get(path);
                if (content == null) {
                    respond(exchange, 404);
                } else {
                    exchange.sendResponseHeaders(200, content.length);
                    exchange.getResponseBody().write(content);
                    exchange.close();
                }
            }

            case "DELETE" -> {
                if (deleteStatus >= 200 && deleteStatus < 300) {
                    files.remove(path);
                }
                respond(exchange, deleteStatus);
            }

            default -> respond(exchange, 405);
        }
    }

    private void respond(HttpExchange exchange, int status)
            throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}
