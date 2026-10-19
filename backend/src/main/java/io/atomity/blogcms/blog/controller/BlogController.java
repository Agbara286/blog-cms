package io.atomity.blogcms.blog.controller;

import io.atomity.blogcms.blog.dto.BlogListQuery;
import io.atomity.blogcms.blog.dto.BlogResponse;
import io.atomity.blogcms.blog.dto.BlogSummaryResponse;
import io.atomity.blogcms.blog.service.BlogService;
import io.atomity.blogcms.shared.ApiResponse;
import io.atomity.blogcms.shared.PageMeta;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Public read-only access to published posts. */
@RestController
@RequestMapping("/api/v1/blogs")
public class BlogController {

    private final BlogService blogService;

    public BlogController(BlogService blogService) {
        this.blogService = blogService;
    }

    /** GET /api/v1/blogs?sort&direction&page&size — published posts, newest first; pagination in {@code meta}. Public (see SecurityConfig). */
    @GetMapping
    public ApiResponse<List<BlogSummaryResponse>> list(BlogListQuery query) {
        Page<BlogSummaryResponse> page = blogService.list(query);
        return ApiResponse.ok(page.getContent(), PageMeta.of(page));
    }

    /** GET /api/v1/blogs/{id} — one published post with its full content. Public (see SecurityConfig). */
    @GetMapping("/{id}")
    public ApiResponse<BlogResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(blogService.getById(id));
    }

    /** GET /api/v1/blogs/slug/{slug} — one published post looked up by slug. Public (see SecurityConfig). */
    @GetMapping("/slug/{slug}")
    public ApiResponse<BlogResponse> getBySlug(@PathVariable String slug) {
        return ApiResponse.ok(blogService.getBySlug(slug));
    }
}
