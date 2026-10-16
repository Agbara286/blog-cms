
package io.atomity.blogcms.media.storage;

import io.atomity.blogcms.shared.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Arrays;

@Profile("!test")
@Service
public class NextcloudStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(NextcloudStorageService.class);

    private final StorageProperties properties;
    private final RestClient client;

    public NextcloudStorageService(StorageProperties properties) {
        this.properties = properties;
        this.client = RestClient.builder()
                .defaultHeaders(headers -> headers.setBasicAuth(
                        properties.username(), properties.password()))
                .build();
    }

    @Override
    public void store(String key, byte[] content) {
        String path = remotePath(key);

        try {
            createDirectories(parentPath(path));

            client.put()
                    .uri(remoteUri(path))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(content)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            log.warn("Nextcloud upload failed with HTTP status {}",
                                    response.getStatusCode().value());
                            throw storageUnavailable();
                        }
                        return null;
                    });
        } catch (RestClientException ex) {
            log.warn("Nextcloud upload failed ({})",
                    ex.getClass().getSimpleName());
            throw storageUnavailable();
        }
    }

    @Override
    public Resource load(String key) {
        String path = remotePath(key);

        try {
            byte[] content = client.get()
                    .uri(remoteUri(path))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().value() == 404) {
                            throw ApiException.notFound("Media file");
                        }

                        if (!response.getStatusCode().is2xxSuccessful()) {
                            log.warn("Nextcloud download failed with HTTP status {}",
                                    response.getStatusCode().value());
                            throw storageUnavailable();
                        }

                        return response.getBody().readAllBytes();
                    });

            return new ByteArrayResource(content);
        } catch (RestClientException ex) {
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

            try {
                client.method(HttpMethod.valueOf("MKCOL"))
                        .uri(remoteUri(current.toString()))
                        .exchange((request, response) -> {
                            int status = response.getStatusCode().value();

                            if (response.getStatusCode().is2xxSuccessful()) {
                                return null;
                            }

                            if (status == 405 && directoryExists(current.toString())) {
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
                    .exchange((request, response) ->
                            response.getStatusCode().value() == 207);
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
        String safeKey = validateRelativePath(key, "Invalid storage key");
        String directory = validateRelativePath(
                properties.remoteDirectory(), "Invalid remote directory");

        return directory.isEmpty() ? safeKey : directory + "/" + safeKey;
    }

    private String validateRelativePath(String value, String errorMessage) {
        if (value == null || value.isBlank() || value.contains("\\")) {
            if (value != null && value.isBlank()
                    && value.equals(properties.remoteDirectory())) {
                return "";
            }
            throw new IllegalArgumentException(errorMessage);
        }

        String normalized = value.replaceAll("^/+|/+$", "");
        if (normalized.isEmpty()) {
            if (value.equals(properties.remoteDirectory())) {
                return "";
            }
            throw new IllegalArgumentException(errorMessage);
        }

        boolean invalid = Arrays.stream(normalized.split("/", -1))
                .anyMatch(segment -> segment.isBlank()
                        || segment.equals(".")
                        || segment.equals(".."));

        if (invalid || value.startsWith("/") && !value.equals(properties.remoteDirectory())) {
            throw new IllegalArgumentException(errorMessage);
        }

        return normalized;
    }

    private String parentPath(String path) {
        int separator = path.lastIndexOf('/');
        return separator < 0 ? "" : path.substring(0, separator);
    }

    private ApiException storageUnavailable() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "MEDIA_STORAGE_UNAVAILABLE",
                "Media storage is temporarily unavailable");
    }
}
