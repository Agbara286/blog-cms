package io.atomity.blogcms.media.storage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.atomity.blogcms.shared.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NextcloudStorageServiceTest {

    private HttpServer server;
    private ExecutorService serverExecutor;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Set<String> collections = ConcurrentHashMap.newKeySet();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
    private NextcloudStorageService storage;

    private volatile int mkcolStatus = 201;
    private final AtomicInteger mkcolRequests = new AtomicInteger();
    private volatile int propfindStatus = 207;
    private volatile int putStatus = 201;
    private volatile int getFailureStatus;
    private volatile int deleteStatus = 204;
    private volatile int moveStatus = 201;
    private volatile CountDownLatch slowMkcolStarted;
    private volatile CountDownLatch allowSlowMkcolToFinish;
    private final AtomicInteger putRequests = new AtomicInteger();
    private final List<String> moveOverwriteHeaders = new CopyOnWriteArrayList<>();

    private static final String DAV_ROOT = "/remote.php/dav/files/test-user";
    private static final String AUTHORIZATION = "Basic " + Base64.getEncoder().encodeToString(
            "test-user:test-password".getBytes(StandardCharsets.UTF_8));

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);

        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);

        server.createContext(DAV_ROOT, this::handleWebDav);
        server.start();

        StorageProperties properties = new StorageProperties(
                "http://localhost:" + server.getAddress().getPort(),
                "test-user",
                "test-password",
                "/BlogCMS");

        storage = new NextcloudStorageService(properties);
        mkcolRequests.set(0);
        putRequests.set(0);
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.close();
        }
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
        files.clear();
        collections.clear();
        requests.clear();
        authorizationHeaders.clear();
        moveOverwriteHeaders.clear();
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
        collections.add(DAV_ROOT + "/BlogCMS");
        collections.add(DAV_ROOT + "/BlogCMS/images");

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
        assertTrue(
                requests.stream().noneMatch(request -> request.startsWith("DELETE ")),
                "Directory-creation failure must not issue a cleanup DELETE");
    }

    @Test
    void acceptsMkcol409WhenPropfindReturns2xx() {
        mkcolStatus = 409;
        propfindStatus = 200;
        collections.add(DAV_ROOT + "/BlogCMS");
        collections.add(DAV_ROOT + "/BlogCMS/images");

        assertDoesNotThrow(
                () -> storage.store("images/test.png", new byte[] { 1, 2, 3 }));

        assertTrue(requests.stream().anyMatch(request -> request.startsWith("PROPFIND ")));
        assertTrue(files.containsKey(DAV_ROOT + "/BlogCMS/images/test.png"));
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

    @Test
    void removesTemporaryFileWhenPublishingFails() {
        moveStatus = 500;

        ApiException exception = assertThrows(
                ApiException.class,
                () -> storage.store("images/test.png", new byte[] { 1, 2, 3 }));

        assertTrue(exception.getMessage().contains("temporarily unavailable"));
        assertFalse(files.containsKey(DAV_ROOT + "/BlogCMS/images/test.png"));

        assertFalse(files.keySet().stream()
                .anyMatch(path -> path.contains(".uploading")));
        assertTrue(moveOverwriteHeaders.contains("F"));
    }

    @Test
    void removesTemporaryFileWhenUploadFails() {
        putStatus = 500;

        ApiException exception = assertThrows(
                ApiException.class,
                () -> storage.store("images/test.png", new byte[] { 1, 2, 3 }));

        assertTrue(exception.getMessage().contains("temporarily unavailable"));
        assertFalse(files.containsKey(DAV_ROOT + "/BlogCMS/images/test.png"));

        assertFalse(files.keySet().stream()
                .anyMatch(path -> path.contains(".uploading")));

        assertTrue(requests.stream().anyMatch(
                request -> request.startsWith("DELETE ")
                        && request.contains(".uploading")),
                "The failed temporary upload should trigger cleanup");
    }

    @Test
    void reusesDirectoriesAcrossUploads() {
        storage.store("images/2026/first.png", new byte[] { 1, 2, 3 });
        int requestsAfterFirstUpload = mkcolRequests.get();

        storage.store("images/2026/second.png", new byte[] { 4, 5, 6 });

        assertTrue(requestsAfterFirstUpload > 0);
        assertEquals(requestsAfterFirstUpload, mkcolRequests.get());
    }

    @Test
    void createsSharedDirectoriesOnlyOnceForConcurrentUploads() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> firstUpload = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to start first upload");
                }

                storage.store("images/2026/first.png", new byte[] { 1, 2, 3 });
                return null;
            });

            Future<?> secondUpload = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to start second upload");
                }

                storage.store("images/2026/second.png", new byte[] { 4, 5, 6 });
                return null;
            });

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            firstUpload.get(10, TimeUnit.SECONDS);
            secondUpload.get(10, TimeUnit.SECONDS);

            assertTrue(files.containsKey(
                    DAV_ROOT + "/BlogCMS/images/2026/first.png"));
            assertTrue(files.containsKey(
                    DAV_ROOT + "/BlogCMS/images/2026/second.png"));

            // BlogCMS, images, and 2026 should each be created only once.
            assertEquals(3, mkcolRequests.get());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void publishesWithMoveOverwriteDisabled() {
        storage.store("images/test.png", new byte[] { 1, 2, 3 });

        assertEquals(List.of("F"), moveOverwriteHeaders);
        assertTrue(files.containsKey(DAV_ROOT + "/BlogCMS/images/test.png"));
        assertFalse(files.keySet().stream().anyMatch(path -> path.contains(".uploading")));
    }

    @Test
    void recreatesOrRechecksDirectoriesAfterCacheExpires() throws InterruptedException {
        storage.close();
        storage = new NextcloudStorageService(
                new StorageProperties(
                        "http://localhost:" + server.getAddress().getPort(),
                        "test-user",
                        "test-password",
                        "/BlogCMS"),
                Duration.ofMillis(100));

        storage.store("images/first.png", new byte[] { 1 });

        int requestsBeforeExpiry = mkcolRequests.get();
        assertTrue(requestsBeforeExpiry > 0);

        Thread.sleep(200);

        storage.store("images/second.png", new byte[] { 2 });

        assertTrue(
                mkcolRequests.get() > requestsBeforeExpiry,
                "Expired directory-cache entries should trigger a new directory check");
    }

    @Test
    void refusesToReplaceExistingFileWhenOverwriteIsFalse() {
        String destination = DAV_ROOT + "/BlogCMS/images/test.png";
        byte[] existing = { 9, 8, 7 };
        files.put(destination, existing);

        ApiException exception = assertThrows(
                ApiException.class,
                () -> storage.store("images/test.png", new byte[] { 1, 2, 3 }));

        assertTrue(exception.getMessage().contains("temporarily unavailable"));
        assertArrayEquals(existing, files.get(destination));
        assertTrue(moveOverwriteHeaders.contains("F"));
    }

    @Test
    void doesNotRetryWhenMoveConflictsWithExistingDestination() {
        String destination = DAV_ROOT + "/BlogCMS/images/test.png";
        files.put(destination, new byte[] { 1, 1, 1 });

        assertThrows(
                ApiException.class,
                () -> storage.store("images/test.png", new byte[] { 2, 2, 2 }));

        assertEquals(1, putRequests.get(), "An existing destination must not trigger a second upload");
        assertEquals(1, requests.stream().filter(request -> request.startsWith("MOVE ")).count());
        assertArrayEquals(new byte[] { 1, 1, 1 }, files.get(destination));
    }

    @Test
    void recreatesDirectoriesWhenCachedFolderIsMissingDuringUpload() {
        storage.store("images/first.png", new byte[] { 1 });
        int mkcolAfterFirstUpload = mkcolRequests.get();
        assertTrue(mkcolAfterFirstUpload > 0);
        assertTrue(collections.contains(DAV_ROOT + "/BlogCMS/images"));

        removeCollection(DAV_ROOT + "/BlogCMS/images");

        storage.store("images/second.png", new byte[] { 2 });

        assertTrue(
                mkcolRequests.get() > mkcolAfterFirstUpload,
                "A missing remote collection should invalidate the cache and MKCOL again");
        assertTrue(collections.contains(DAV_ROOT + "/BlogCMS/images"));
        assertTrue(files.containsKey(DAV_ROOT + "/BlogCMS/images/second.png"));
        assertArrayEquals(new byte[] { 2 }, files.get(DAV_ROOT + "/BlogCMS/images/second.png"));
    }

    @Test
    void uploadsToDifferentDirectoriesCanProceedConcurrently() throws Exception {
        slowMkcolStarted = new CountDownLatch(1);
        allowSlowMkcolToFinish = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> slowUpload = executor.submit(
                    () -> storage.store("slow-folder/slow.png", new byte[] { 1 }));

            assertTrue(
                    slowMkcolStarted.await(5, TimeUnit.SECONDS),
                    "The slow upload should be blocked on MKCOL for its own directory");

            Future<?> fastUpload = executor.submit(
                    () -> storage.store("fast-folder/fast.png", new byte[] { 2 }));

            fastUpload.get(5, TimeUnit.SECONDS);

            assertTrue(files.containsKey(
                    DAV_ROOT + "/BlogCMS/fast-folder/fast.png"));

            allowSlowMkcolToFinish.countDown();
            slowUpload.get(5, TimeUnit.SECONDS);

            assertTrue(files.containsKey(
                    DAV_ROOT + "/BlogCMS/slow-folder/slow.png"));
        } finally {
            if (allowSlowMkcolToFinish != null) {
                allowSlowMkcolToFinish.countDown();
            }
            executor.shutdownNow();
        }
    }

    @Test
    void loadTimesOutWhenTheResponseBodyStalls() {
        storage.close();
        storage = new NextcloudStorageService(
                new StorageProperties(
                        "http://localhost:" + server.getAddress().getPort(),
                        "test-user",
                        "test-password",
                        "/BlogCMS"),
                Duration.ofSeconds(60),
                Duration.ofSeconds(2),
                Duration.ofMillis(200));

        files.put(DAV_ROOT + "/BlogCMS/stalled.png", new byte[] { 1, 2, 3 });
        getFailureStatus = -1;

        Resource resource = storage.load("stalled.png");
        IOException exception = assertThrows(IOException.class, () -> {
            try (var input = resource.getInputStream()) {
                input.readAllBytes();
            }
        });

        assertTrue(exception.getMessage().contains("Timed out reading from Nextcloud"));
    }

    @Test
    void storeTimesOutWhenTheRemotePutStalls() {
        storage.close();
        storage = new NextcloudStorageService(
                new StorageProperties(
                        "http://localhost:" + server.getAddress().getPort(),
                        "test-user",
                        "test-password",
                        "/BlogCMS"),
                Duration.ofSeconds(60),
                Duration.ofSeconds(2),
                Duration.ofMillis(200));

        putStatus = -1;

        ApiException exception = assertThrows(
                ApiException.class,
                () -> storage.store("images/stalled.png", new byte[] { 1, 2, 3 }));

        assertTrue(exception.getMessage().contains("temporarily unavailable"));
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
            case "MKCOL" -> {
                mkcolRequests.incrementAndGet();
                if (slowMkcolStarted != null && path.endsWith("/slow-folder")) {
                    slowMkcolStarted.countDown();
                    try {
                        if (allowSlowMkcolToFinish == null
                                || !allowSlowMkcolToFinish.await(5, TimeUnit.SECONDS)) {
                            respond(exchange, 500);
                            return;
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        respond(exchange, 500);
                        return;
                    }
                }
                if (mkcolStatus >= 200 && mkcolStatus < 300) {
                    collections.add(path);
                }
                respond(exchange, mkcolStatus);
            }

            case "PROPFIND" -> {
                if (collectionExists(path)) {
                    respond(exchange, propfindStatus);
                } else {
                    respond(exchange, 404);
                }
            }

            case "PUT" -> {
                putRequests.incrementAndGet();
                byte[] content = exchange.getRequestBody().readAllBytes();

                if (putStatus < 0) {
                    try {
                        Thread.sleep(2_000);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    respond(exchange, 500);
                    return;
                }

                if (!collectionExists(parentPath(path))) {
                    respond(exchange, 409);
                    return;
                }

                if (putStatus >= 200 && putStatus < 300) {
                    files.put(path, content);
                }
                respond(exchange, putStatus);
            }

            case "MOVE" -> {
                String overwrite = exchange.getRequestHeaders().getFirst("Overwrite");
                moveOverwriteHeaders.add(overwrite);
                String destinationHeader = exchange.getRequestHeaders().getFirst("Destination");

                if (destinationHeader == null) {
                    respond(exchange, 400);
                    return;
                }

                String destination = URI.create(destinationHeader).getPath();
                String destinationPrefix = DAV_ROOT + "/BlogCMS/";

                if (!destination.startsWith(destinationPrefix)) {
                    respond(exchange, 400);
                    return;
                }

                if (moveStatus < 200 || moveStatus >= 300) {
                    respond(exchange, moveStatus);
                    return;
                }

                if (!files.containsKey(path)) {
                    respond(exchange, 404);
                    return;
                }

                if (!collectionExists(parentPath(destination))) {
                    respond(exchange, 409);
                    return;
                }

                if ("F".equalsIgnoreCase(overwrite) && files.containsKey(destination)) {
                    respond(exchange, 412);
                    return;
                }

                files.put(destination, files.remove(path));
                respond(exchange, moveStatus);
            }

            case "GET" -> {
                if (getFailureStatus < 0) {
                    exchange.sendResponseHeaders(200, 64);
                    try {
                        Thread.sleep(2_000);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                    return;
                }
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

    private void removeCollection(String directory) {
        collections.remove(directory);
        files.keySet().removeIf(path -> path.equals(directory) || path.startsWith(directory + "/"));
    }

    private boolean collectionExists(String directory) {
        return DAV_ROOT.equals(directory) || collections.contains(directory);
    }

    private static String parentPath(String path) {
        int separator = path.lastIndexOf('/');
        return separator < 0 ? "" : path.substring(0, separator);
    }
}
