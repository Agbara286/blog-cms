package io.atomity.blogcms.post.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.atomity.blogcms.author.entity.Author;
import io.atomity.blogcms.markdown.entity.MarkdownSource;
import io.atomity.blogcms.media.entity.Media;
import io.atomity.blogcms.tag.entity.Tag;

import java.util.Collection;
import java.util.List;

/**
 * Everything an edit sets on a post, already validated and resolved (relations as
 * entities, content metrics derived) by PostService.
 */
public record PostEdit(
        String title,
        String slug,
        String excerpt,
        JsonNode content,
        String contentText,
        int wordCount,
        int readingTimeMinutes,
        Media coverImage,
        String coverImageAlt,
        MarkdownSource markdownSource,
        String seoTitle,
        String metaDescription,
        String focusKeyword,
        String canonicalUrl,
        boolean featured,
        List<Author> authors,
        Collection<Tag> tags,
        Collection<Media> inlineImages) {
}
