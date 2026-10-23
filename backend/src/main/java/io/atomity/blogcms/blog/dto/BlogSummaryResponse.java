package io.atomity.blogcms.blog.dto;

import io.atomity.blogcms.author.dto.AuthorRef;
import io.atomity.blogcms.media.dto.MediaRef;
import io.atomity.blogcms.post.dto.PostSummaryResponse;
import io.atomity.blogcms.post.entity.Post;
import io.atomity.blogcms.tag.dto.TagRef;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row in the public blog list — everything a reader needs except the body.
 * Editor-only fields ({@code status}, {@code version}, {@code createdAt}, {@code focusKeyword})
 * are deliberately omitted.
 */
public record BlogSummaryResponse(
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
        Instant updatedAt) {

    public static BlogSummaryResponse of(Post post) {
        return new BlogSummaryResponse(post.getId(), post.getTitle(), post.getSlug(), post.getExcerpt(),
                post.isFeatured(), PostSummaryResponse.authorRefs(post), PostSummaryResponse.tagRefs(post),
                MediaRef.of(post.getCoverImage()), post.getWordCount(), post.getReadingTimeMinutes(),
                post.getPublishedAt(), post.getUpdatedAt());
    }
}
