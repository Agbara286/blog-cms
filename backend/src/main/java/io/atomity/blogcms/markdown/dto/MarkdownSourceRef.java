package io.atomity.blogcms.markdown.dto;

import io.atomity.blogcms.markdown.entity.MarkdownSource;

import java.util.UUID;

/** An imported .md file, as returned on upload and inside a post. */
public record MarkdownSourceRef(UUID id, String fileName, long sizeBytes, String url) {

    public static MarkdownSourceRef of(MarkdownSource source) {
        return source == null ? null : new MarkdownSourceRef(source.getId(), source.getOriginalFilename(),
                source.getSizeBytes(), "/api/v1/markdown-sources/" + source.getId() + "/file");
    }
}
