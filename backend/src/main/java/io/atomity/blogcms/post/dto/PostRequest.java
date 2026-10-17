package io.atomity.blogcms.post.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.atomity.blogcms.shared.Slugs;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Create/update body. Text fields may be omitted (treated as empty); an empty slug is
 * derived from the title. {@code version} is required on update: it is the version the
 * editor loaded, and a mismatch means someone else saved in between.
 */
public record PostRequest(
        @Size(max = 200, message = "must be at most 200 characters")
        String title,

        @Size(max = 160, message = "must be at most 160 characters")
        @Pattern(regexp = "^$|" + Slugs.PATTERN, message = Slugs.MESSAGE)
        String slug,

        @Size(max = 300, message = "must be at most 300 characters")
        String excerpt,

        @NotNull(message = "is required")
        JsonNode content,

        UUID coverImageId,

        @Size(max = 300, message = "must be at most 300 characters")
        String coverImageAlt,

        UUID markdownSourceId,

        @Size(max = 70, message = "must be at most 70 characters")
        String seoTitle,

        @Size(max = 160, message = "must be at most 160 characters")
        String metaDescription,

        @Size(max = 100, message = "must be at most 100 characters")
        String focusKeyword,

        @Size(max = 500, message = "must be at most 500 characters")
        @Pattern(regexp = "^$|^https?://\\S+$", message = "must start with http:// or https://")
        String canonicalUrl,

        Boolean featured,

        @Size(max = 20, message = "may list at most 20 authors")
        List<@NotNull UUID> authorIds,

        @Size(max = 30, message = "may list at most 30 tags")
        List<@NotNull UUID> tagIds,

        Long version) {
}
