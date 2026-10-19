package io.atomity.blogcms.media.storage;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Configuration for Nextcloud WebDAV media storage. */
@Validated
@ConfigurationProperties(prefix = "blogcms.media.nextcloud")
public record StorageProperties(
        @NotBlank String url,
        @NotBlank String username,
        @NotBlank String password,
        @NotBlank String remoteDirectory) {
}
