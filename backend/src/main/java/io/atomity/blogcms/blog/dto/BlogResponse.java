package io.atomity.blogcms.blog.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.atomity.blogcms.author.dto.AuthorRef;
import io.atomity.blogcms.media.dto.MediaRef;
import io.atomity.blogcms.post.dto.PostSummaryResponse;
import io.atomity.blogcms.post.entity.Post;
import io.atomity.blogcms.tag.dto.TagRef;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A published blog post as a reader loads it: the summary plus the body and SEO metadata.
 * Editor-only fields ({@code status}, {@code version}, {@code createdAt}, {@code focusKeyword})
 * are deliberately omitted.
 */
public record BlogResponse(
        UUID id,
        String title,
        String slug,
        String excerpt,
        boolean featured,
        List<AuthorRef> authors,
        List<TagRef> tags,
        MediaRef coverImage,
        int wordCount,
        int readingTimeMinutes,
        Instant publishedAt,
        Instant updatedAt,
        JsonNode content,
        String coverImageAlt,
        String seoTitle,
        String metaDescription,
        String canonicalUrl) {

    public static BlogResponse of(Post post) {
        return new BlogResponse(post.getId(), post.getTitle(), post.getSlug(), post.getExcerpt(),
                post.isFeatured(), PostSummaryResponse.authorRefs(post), PostSummaryResponse.tagRefs(post),
                MediaRef.of(post.getCoverImage()), post.getWordCount(), post.getReadingTimeMinutes(),
                post.getPublishedAt(), post.getUpdatedAt(), post.getContent(), post.getCoverImageAlt(),
                post.getSeoTitle(), post.getMetaDescription(), post.getCanonicalUrl());
    }
}
