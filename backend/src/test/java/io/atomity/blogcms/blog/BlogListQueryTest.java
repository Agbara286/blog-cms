package io.atomity.blogcms.blog;

import io.atomity.blogcms.blog.dto.BlogListQuery;
import io.atomity.blogcms.shared.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlogListQueryTest {

    @Test
    void defaultsToNewestPublishedFirst() {
        Pageable pageable = new BlogListQuery(null, null, null, null).toPageable();

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(10);
        Sort.Order publishedAt = pageable.getSort().getOrderFor("publishedAt");
        assertThat(publishedAt).isNotNull();
        assertThat(publishedAt.getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(pageable.getSort().getOrderFor("id")).isNotNull();
    }

    @Test
    void clampsPageSizeToOneToHundred() {
        assertThat(new BlogListQuery(null, null, null, 1000).toPageable().getPageSize()).isEqualTo(100);
        assertThat(new BlogListQuery(null, null, null, 0).toPageable().getPageSize()).isEqualTo(1);
        assertThat(new BlogListQuery(null, null, null, -5).toPageable().getPageSize()).isEqualTo(1);
    }

    @Test
    void negativePageBecomesFirstPage() {
        assertThat(new BlogListQuery(null, null, -3, null).toPageable().getPageNumber()).isZero();
    }

    @Test
    void acceptsWhitelistedSortAndDirection() {
        Pageable pageable = new BlogListQuery("title", "asc", 1, 5).toPageable();

        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(5);
        assertThat(pageable.getSort().getOrderFor("title").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void rejectsUnknownSortAndDirection() {
        assertThatThrownBy(() -> new BlogListQuery("password", null, null, null).toPageable())
                .isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo("BAD_REQUEST");
        assertThatThrownBy(() -> new BlogListQuery(null, "sideways", null, null).toPageable())
                .isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo("BAD_REQUEST");
    }
}
