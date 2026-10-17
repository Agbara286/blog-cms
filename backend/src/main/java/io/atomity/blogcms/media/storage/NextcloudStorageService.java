
package io.atomity.blogcms.media.storage;

import io.atomity.blogcms.shared.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Profile("!test")
@Service
public class NextcloudStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(NextcloudStorageService.class);

    private final StorageProperties properties;
    private final RestClient client;
    private final HttpClient httpClient;

    private final Set<String> createdDirectories = ConcurrentHashMap.newKeySet();

    public NextcloudStorageService(StorageProperties properties) {
        this.properties = properties;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(this.httpClient);

        requestFactory.setReadTimeout(Duration.ofSeconds(30));

        this.client = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> headers.setBasicAuth(
                        properties.username(), properties.password()))
                .build();
    }

    @Override
    public void store(String key, byte[] content) {
        String path = remotePath(key);
        String temporaryKey = temporaryKey(key);
        String temporaryPath = remotePath(temporaryKey);
        boolean published = false;

        try {
            createDirectories(parentPath(path));

            // Upload to a unique temporary path, not the final file.
            client.put()
                    .uri(remoteUri(temporaryPath))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(content)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            log.warn("Nextcloud temporary upload failed with HTTP status {}",
                                    response.getStatusCode().value());
                            throw storageUnavailable();
                        }
                        return null;
                    });

            // Publish the file only after the upload succeeds.
            client.method(HttpMethod.valueOf("MOVE"))
                    .uri(remoteUri(temporaryPath))
                    .header("Destination", remoteUri(path).toString())
                    .header("Overwrite", "T")
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            log.warn("Nextcloud file move failed with HTTP status {}",
                                    response.getStatusCode().value());
                            throw storageUnavailable();
                        }
                        return null;
                    });

            published = true;

        } catch (RestClientException ex) {
            log.warn("Nextcloud upload failed ({})",
                    ex.getClass().getSimpleName());
            throw storageUnavailable();
        } finally {
            // Best-effort cleanup if upload or publication fails.
            if (!published) {
                delete(temporaryKey);
            }
        }
    }

    @Override
    public Resource load(String key) {
        String path = remotePath(key);

        String credentials = properties.username() + ":" + properties.password();
        String authorization = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(remoteUri(path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", authorization)
                .GET()
                .build();

        try {
            HttpResponse<java.io.InputStream> response = httpClient.send(
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

            // The stream remains open until the caller consumes and closes it.
            return new InputStreamResource(response.body());

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

    private synchronized void createDirectories(String directory) {
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

            if (createdDirectories.contains(directoryPath)) {
                current.append("/");
                continue;
            }

            try {
                client.method(HttpMethod.valueOf("MKCOL"))
                        .uri(remoteUri(directoryPath))
                        .exchange((request, response) -> {
                            int status = response.getStatusCode().value();

                            if (response.getStatusCode().is2xxSuccessful()) {
                                createdDirectories.add(directoryPath);
                                return null;
                            }

                            // Another request may have created this directory concurrently.
                            // Verify it exists before caching it.
                            if (directoryExists(directoryPath)) {
                                createdDirectories.add(directoryPath);
                                return null;
                            }
                            log.warn("Nextcloud directory creation failed with HTTP status {}",
                                    status);
                            throw storageUnavailable();
                        });
            } catch (RestClientException ex) {
                log.warn("Nextcloud directory creation failed ({})",
                        ex.getClass().getSimpleName());
                throw storageUnavailable();
            }

            current.append("/");
        }
    }

    private boolean directoryExists(String path) {
        try {
            return client.method(HttpMethod.valueOf("PROPFIND"))
                    .uri(remoteUri(path))
                    .header("Depth", "0")
                    .exchange((request, response) -> response.getStatusCode().value() == 207);
        } catch (RestClientException ex) {
            log.warn("Nextcloud directory verification failed ({})",
                    ex.getClass().getSimpleName());
            return false;
        }
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
}
