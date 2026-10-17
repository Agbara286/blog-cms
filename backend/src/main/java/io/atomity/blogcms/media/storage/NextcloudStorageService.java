package io.atomity.blogcms.media.storage;

import io.atomity.blogcms.shared.ApiException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Profile("!test")
@Service
public class NextcloudStorageService implements StorageService, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NextcloudStorageService.class);

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final StorageProperties properties;
    private final RestClient client;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    private final ConcurrentHashMap<String, Long> createdDirectories = new ConcurrentHashMap<>();
    private final Map<String, DirectoryLock> directoryLocks = new HashMap<>();
    private final long directoryCacheTtlNanos;
    private final ScheduledExecutorService readWatchdog = Executors.newSingleThreadScheduledExecutor(thread -> {
        Thread watchdog = new Thread(thread, "nextcloud-read-watchdog");
        watchdog.setDaemon(true);
        return watchdog;
    });

    private static final class DirectoryLock {
        private final Object monitor = new Object();
        private int users;
    }

    private enum PublishResult {
        PUBLISHED,
        MISSING_COLLECTION,
        FAILED
    }

    public NextcloudStorageService(StorageProperties properties) {
        this(properties, Duration.ofSeconds(60), CONNECT_TIMEOUT, REQUEST_TIMEOUT);
    }

    NextcloudStorageService(StorageProperties properties, Duration directoryCacheTtl) {
        this(properties, directoryCacheTtl, CONNECT_TIMEOUT, REQUEST_TIMEOUT);
    }

    NextcloudStorageService(
            StorageProperties properties,
            Duration directoryCacheTtl,
            Duration connectTimeout,
            Duration requestTimeout) {
        this.properties = properties;
        this.directoryCacheTtlNanos = directoryCacheTtl.toNanos();
        this.requestTimeout = requestTimeout;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(this.httpClient);
        requestFactory.setReadTimeout(requestTimeout);

        this.client = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, authorizationHeader())
                .build();
    }

    @Override
    public void store(String key, byte[] content) {
        PublishResult result = publish(key, content);
        if (result == PublishResult.MISSING_COLLECTION) {
            result = publish(key, content);
        }
        if (result != PublishResult.PUBLISHED) {
            throw storageUnavailable();
        }
    }

    /**
     * Uploads to a unique temporary name, then MOVE-publishes to the final key.
     * Nextcloud/SabreDAV treats a same-parent MOVE as a rename, so the public
     * path is not a PUT target. {@code Overwrite: F} refuses to replace an
     * unexpected occupant of that key. This is a safer publication sequence,
     * not a proof of cluster-wide atomicity.
     */
    private PublishResult publish(String key, byte[] content) {
        String path = remotePath(key);
        String parent = parentPath(path);
        String temporaryKey = temporaryKey(key);
        String temporaryPath = remotePath(temporaryKey);
        boolean published = false;
        boolean uploadAttempted = false;

        try {
            createDirectories(parent);

            uploadAttempted = true;

            int putStatus = client.put()
                    .uri(remoteUri(temporaryPath))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(content)
                    .exchange((request, response) -> response.getStatusCode().value());

            if (isMissingParentCollection(putStatus)) {
                log.warn("Nextcloud temporary upload failed with HTTP status {}", putStatus);
                invalidateCachedDirectories(parent);
                return PublishResult.MISSING_COLLECTION;
            }

            if (!isSuccessful(putStatus)) {
                log.warn("Nextcloud temporary upload failed with HTTP status {}", putStatus);
                return PublishResult.FAILED;
            }

            int moveStatus = client.method(HttpMethod.valueOf("MOVE"))
                    .uri(remoteUri(temporaryPath))
                    .header("Destination", remoteUri(path).toString())
                    .header("Overwrite", "F")
                    .exchange((request, response) -> response.getStatusCode().value());

            if (isMissingDestinationCollection(moveStatus)) {
                log.warn("Nextcloud file move failed with HTTP status {}", moveStatus);
                invalidateCachedDirectories(parent);
                return PublishResult.MISSING_COLLECTION;
            }

            if (!isSuccessful(moveStatus)) {
                log.warn("Nextcloud file move failed with HTTP status {}", moveStatus);
                return PublishResult.FAILED;
            }

            published = true;
            return PublishResult.PUBLISHED;
        } catch (RestClientException ex) {
            log.warn("Nextcloud upload failed ({})",
                    ex.getClass().getSimpleName());
            return PublishResult.FAILED;
        } finally {
            if (uploadAttempted && !published) {
                delete(temporaryKey);
            }
        }
    }

    @Override
    public Resource load(String key) {
        String path = remotePath(key);

        HttpRequest request = HttpRequest.newBuilder(remoteUri(path))
                .timeout(requestTimeout)
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader())
                .GET()
                .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

            int status = response.statusCode();

            if (status == 404) {
                response.body().close();
                throw ApiException.notFound("Media file");
            }

            if (status < 200 || status >= 300) {
                response.body().close();
                log.warn("Nextcloud download failed with HTTP status {}", status);
                throw storageUnavailable();
            }

            // Streaming GET: request timeout covers headers; idle reads are
            // bounded by the same REQUEST_TIMEOUT via the watchdog wrapper.
            return new InputStreamResource(
                    new IdleTimeoutInputStream(response.body(), readWatchdog, requestTimeout));

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Nextcloud download was interrupted");
            throw storageUnavailable();
        } catch (IOException ex) {
            log.warn("Nextcloud download failed ({})",
                    ex.getClass().getSimpleName());
            throw storageUnavailable();
        }
    }

    @Override
    public void delete(String key) {
        String path = remotePath(key);

        try {
            client.method(HttpMethod.DELETE)
                    .uri(remoteUri(path))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();

                        if (status == 404 || response.getStatusCode().is2xxSuccessful()) {
                            return null;
                        }

                        log.warn("Nextcloud deletion failed with HTTP status {}", status);
                        return null;
                    });
        } catch (RestClientException ex) {
            // Match the existing storage contract: deletion failures do not
            // undo a database deletion. The remote file may need later cleanup.
            log.warn("Nextcloud deletion failed ({})",
                    ex.getClass().getSimpleName());
        }
    }

    @PreDestroy
    @Override
    public void close() {
        readWatchdog.shutdownNow();
    }

    private void createDirectories(String directory) {
        if (directory.isBlank()) {
            return;
        }

        StringBuilder current = new StringBuilder();

        for (String segment : directory.split("/")) {
            if (segment.isBlank()) {
                continue;
            }

            current.append(segment);
            String directoryPath = current.toString();

            DirectoryLock lock;
            synchronized (directoryLocks) {
                lock = directoryLocks.computeIfAbsent(
                        directoryPath, ignored -> new DirectoryLock());
                lock.users++;
            }

            try {
                synchronized (lock.monitor) {
                    long now = System.nanoTime();
                    Long expiresAt = createdDirectories.get(directoryPath);

                    if (expiresAt != null && now < expiresAt) {
                        current.append("/");
                        continue;
                    }

                    createdDirectories.remove(directoryPath);

                    try {
                        client.method(HttpMethod.valueOf("MKCOL"))
                                .uri(remoteUri(directoryPath))
                                .exchange((request, response) -> {
                                    if (response.getStatusCode().is2xxSuccessful()
                                            || directoryExists(directoryPath)) {
                                        createdDirectories.put(
                                                directoryPath,
                                                System.nanoTime()
                                                        + directoryCacheTtlNanos);
                                        return null;
                                    }

                                    log.warn(
                                            "Nextcloud directory creation failed with HTTP status {}",
                                            response.getStatusCode().value());
                                    throw storageUnavailable();
                                });
                    } catch (RestClientException ex) {
                        log.warn(
                                "Nextcloud directory creation failed ({})",
                                ex.getClass().getSimpleName());
                        throw storageUnavailable();
                    }
                }
            } finally {
                synchronized (directoryLocks) {
                    lock.users--;
                    if (lock.users == 0) {
                        directoryLocks.remove(directoryPath, lock);
                    }
                }
            }

            current.append("/");
        }
    }

    private boolean directoryExists(String path) {
        try {
            return client.method(HttpMethod.valueOf("PROPFIND"))
                    .uri(remoteUri(path))
                    .header("Depth", "0")
                    .exchange((request, response) ->
                            response.getStatusCode().is2xxSuccessful());
        } catch (RestClientException ex) {
            log.warn("Nextcloud directory verification failed ({})",
                    ex.getClass().getSimpleName());
            return false;
        }
    }

    private void invalidateCachedDirectories(String directory) {
        String current = directory;
        while (!current.isBlank()) {
            createdDirectories.remove(current);
            current = parentPath(current);
        }
    }

    private static boolean isMissingParentCollection(int status) {
        return status == 404 || status == 409;
    }

    private static boolean isMissingDestinationCollection(int status) {
        return status == 404;
    }

    private static boolean isSuccessful(int status) {
        return HttpStatusCode.valueOf(status).is2xxSuccessful();
    }

    private URI remoteUri(String relativePath) {
        var builder = UriComponentsBuilder
                .fromUriString(properties.url().replaceAll("/+$", ""))
                .pathSegment("remote.php", "dav", "files", properties.username());

        Arrays.stream(relativePath.split("/"))
                .forEach(builder::pathSegment);

        return builder.build().encode().toUri();
    }

    private String remotePath(String key) {
        String safeKey = validateStorageKey(key);
        String directory = normalizeRemoteDirectory(properties.remoteDirectory());

        return directory.isEmpty() ? safeKey : directory + "/" + safeKey;
    }

    private String validateStorageKey(String key) {
        if (key == null || key.isBlank() || key.contains("\\")) {
            throw new IllegalArgumentException("Invalid storage key");
        }

        String normalized = key.replaceAll("^/+|/+$", "");

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Invalid storage key");
        }

        boolean invalid = Arrays.stream(normalized.split("/", -1))
                .anyMatch(segment -> segment.isBlank()
                        || segment.equals(".")
                        || segment.equals(".."));

        if (invalid) {
            throw new IllegalArgumentException("Invalid storage key");
        }

        return normalized;
    }

    private String normalizeRemoteDirectory(String directory) {
        if (directory == null || directory.isBlank()) {
            return "";
        }

        String normalized = directory.replaceAll("^/+|/+$", "");

        boolean invalid = Arrays.stream(normalized.split("/", -1))
                .anyMatch(segment -> segment.isBlank()
                        || segment.equals(".")
                        || segment.equals("..")
                        || segment.contains("\\"));

        if (invalid) {
            throw new IllegalArgumentException("Invalid remote directory");
        }

        return normalized;
    }

    private String parentPath(String path) {
        int separator = path.lastIndexOf('/');
        return separator < 0 ? "" : path.substring(0, separator);
    }

    private String temporaryKey(String key) {
        int separator = key.lastIndexOf('/');
        String fileName = "." + UUID.randomUUID() + ".uploading";

        return separator < 0 ? fileName
                : key.substring(0, separator + 1) + fileName;
    }

    private ApiException storageUnavailable() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "MEDIA_STORAGE_UNAVAILABLE",
                "Media storage is temporarily unavailable");
    }

    private String authorizationHeader() {
        String credentials = properties.username() + ":" + properties.password();
        return "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Closes the underlying stream if a single read blocks longer than the
     * shared request timeout. JDK {@code HttpRequest.timeout()} does not bound
     * later reads from {@code BodyHandlers.ofInputStream()}.
     */
    static final class IdleTimeoutInputStream extends FilterInputStream {

        private final ScheduledExecutorService watchdog;
        private final Duration idleTimeout;
        private final AtomicBoolean timedOut = new AtomicBoolean();

        IdleTimeoutInputStream(InputStream in, ScheduledExecutorService watchdog, Duration idleTimeout) {
            super(in);
            this.watchdog = watchdog;
            this.idleTimeout = idleTimeout;
        }

        @Override
        public int read() throws IOException {
            return withIdleTimeout(in::read);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return withIdleTimeout(() -> in.read(b, off, len));
        }

        private int withIdleTimeout(Read read) throws IOException {
            Future<?> closer = watchdog.schedule(() -> {
                timedOut.set(true);
                try {
                    in.close();
                } catch (IOException ignored) {
                    // The blocked read surfaces the close as an IOException.
                }
            }, idleTimeout.toNanos(), TimeUnit.NANOSECONDS);

            try {
                return read.perform();
            } catch (IOException ex) {
                if (timedOut.get()) {
                    throw new IOException("Timed out reading from Nextcloud", ex);
                }
                throw ex;
            } finally {
                closer.cancel(false);
            }
        }

        @FunctionalInterface
        private interface Read {
            int perform() throws IOException;
        }
    }
}
