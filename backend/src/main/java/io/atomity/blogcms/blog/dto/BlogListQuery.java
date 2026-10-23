package io.atomity.blogcms.blog.dto;

import io.atomity.blogcms.post.dto.PostListQuery;
import io.atomity.blogcms.post.entity.PostStatus;
import org.springframework.data.domain.Pageable;

/**
 * Query parameters of GET /api/v1/blogs. Only published posts are ever listed, and the
 * list defaults to newest first; sort, direction, size clamp and the id tiebreaker come
 * from the admin list so both APIs behave the same.
 */
public record BlogListQuery(
        String sort,
        String direction,
        Integer page,
        Integer size) {

    private static final String DEFAULT_SORT = "publishedAt";

    public Pageable toPageable() {
        return new PostListQuery(PostStatus.PUBLISHED, null, null, null,
                sort == null ? DEFAULT_SORT : sort, direction, page, size).toPageable();
    }
}
