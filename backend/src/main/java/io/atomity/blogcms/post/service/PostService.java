package io.atomity.blogcms.post.service;

import io.atomity.blogcms.author.entity.Author;
import io.atomity.blogcms.author.repository.AuthorRepository;
import io.atomity.blogcms.markdown.entity.MarkdownSource;
import io.atomity.blogcms.markdown.repository.MarkdownSourceRepository;
import io.atomity.blogcms.media.entity.Media;
import io.atomity.blogcms.media.repository.MediaRepository;
import io.atomity.blogcms.post.dto.PostListQuery;
import io.atomity.blogcms.post.dto.PostRequest;
import io.atomity.blogcms.post.dto.PostResponse;
import io.atomity.blogcms.post.dto.PostSummaryResponse;
import io.atomity.blogcms.post.entity.Post;
import io.atomity.blogcms.post.entity.PostEdit;
import io.atomity.blogcms.post.entity.PostStatus;
import io.atomity.blogcms.post.repository.PostRepository;
import io.atomity.blogcms.post.repository.PostSpecifications;
import io.atomity.blogcms.shared.ApiException;
import io.atomity.blogcms.shared.Slugs;
import io.atomity.blogcms.tag.entity.Tag;
import io.atomity.blogcms.tag.repository.TagRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PostService {

    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_SLUG_LENGTH = 160;

    private final PostRepository postRepository;
    private final AuthorRepository authorRepository;
    private final TagRepository tagRepository;
    private final MediaRepository mediaRepository;
    private final MarkdownSourceRepository markdownSourceRepository;

    public PostService(PostRepository postRepository, AuthorRepository authorRepository,
                       TagRepository tagRepository, MediaRepository mediaRepository,
                       MarkdownSourceRepository markdownSourceRepository) {
        this.postRepository = postRepository;
        this.authorRepository = authorRepository;
        this.tagRepository = tagRepository;
        this.mediaRepository = mediaRepository;
        this.markdownSourceRepository = markdownSourceRepository;
    }

    @Transactional(readOnly = true)
    public Page<PostSummaryResponse> list(PostListQuery query) {
        Specification<Post> spec = Specification.where(PostSpecifications.hasStatus(query.status()))
                .and(PostSpecifications.hasTag(query.tag()))
                .and(PostSpecifications.hasAuthor(query.author()))
                .and(PostSpecifications.matches(query.q()));
        return postRepository.findAll(spec, query.toPageable()).map(PostSummaryResponse::of);
    }

    /** Number of posts per status plus ALL, for the list's tabs. Statuses with no posts report 0. */
    @Transactional(readOnly = true)
    public Map<String, Long> countByStatus() {
        Map<PostStatus, Long> byStatus = new EnumMap<>(PostStatus.class);
        for (PostStatus status : PostStatus.values()) {
            byStatus.put(status, 0L);
        }
        postRepository.countByStatus().forEach(row -> byStatus.put(row.getStatus(), row.getCount()));

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("ALL", byStatus.values().stream().mapToLong(Long::longValue).sum());
        byStatus.forEach((status, count) -> counts.put(status.name(), count));
        return counts;
    }

    @Transactional(readOnly = true)
    public PostResponse get(UUID id) {
        return PostResponse.of(find(id));
    }

    @Transactional
    public PostResponse create(PostRequest request) {
        Post post = Post.create(resolveEdit(request, null));
        return PostResponse.of(postRepository.saveAndFlush(post));
    }

    /**
     * Saves an edit. The request carries the version the editor loaded; if the post has
     * moved on since, the save is refused instead of silently overwriting the newer one.
     */
    @Transactional
    public PostResponse update(UUID id, PostRequest request) {
        if (request.version() == null) {
            throw ApiException.badRequest("VALIDATION_ERROR", "version is required when updating a post");
        }
        Post post = find(id);
        if (post.getVersion() != request.version()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "This post was changed somewhere else since you opened it. Reload to get the latest version.");
        }
        post.edit(resolveEdit(request, id));
        // Flush so the response carries the incremented version.
        return PostResponse.of(postRepository.saveAndFlush(post));
    }

    /** Publishing needs a title and something to read. */
    @Transactional
    public PostResponse publish(UUID id) {
        Post post = find(id);
        List<String> missing = new ArrayList<>();
        if (post.getTitle().isBlank()) {
            missing.add("a title");
        }
        if (post.hasNoContent()) {
            missing.add("some content");
        }
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_PUBLISHABLE",
                    "Add " + String.join(" and ", missing) + " before publishing.");
        }
        post.publish();
        return PostResponse.of(postRepository.saveAndFlush(post));
    }

    @Transactional
    public PostResponse unpublish(UUID id) {
        Post post = find(id);
        post.unpublish();
        return PostResponse.of(postRepository.saveAndFlush(post));
    }

    @Transactional
    public PostResponse archive(UUID id) {
        Post post = find(id);
        post.archive();
        return PostResponse.of(postRepository.saveAndFlush(post));
    }

    @Transactional
    public PostResponse duplicate(UUID id) {
        Post source = find(id);
        String title = truncate(source.getTitle().isBlank() ? "Untitled (copy)" : source.getTitle() + " (copy)", MAX_TITLE_LENGTH);
        String base = truncate(source.getSlug(), MAX_SLUG_LENGTH - 10) + "-copy";
        String slug = base;
        for (int n = 2; postRepository.existsBySlug(slug); n++) {
            slug = base + "-" + n;
        }
        return PostResponse.of(postRepository.saveAndFlush(source.duplicate(title, slug)));
    }

    @Transactional
    public void delete(UUID id) {
        postRepository.delete(find(id));
    }

    private Post find(UUID id) {
        return postRepository.findById(id).orElseThrow(() -> ApiException.notFound("Post"));
    }

    /** Validates the request against the database and turns it into a resolved edit. */
    private PostEdit resolveEdit(PostRequest request, UUID selfId) {
        String title = clean(request.title());
        ContentAnalyzer.Analysis content = ContentAnalyzer.analyze(request.content());

        List<Author> authors = resolveOrdered(request.authorIds(), authorRepository::findAllById, Author::getId, "author");
        List<Tag> tags = resolveOrdered(request.tagIds(), tagRepository::findAllById, Tag::getId, "tag");
        List<Media> inlineImages = resolveOrdered(content.imageIds(), mediaRepository::findAllById, Media::getId, "image");
        Media cover = request.coverImageId() == null ? null : mediaRepository.findById(request.coverImageId())
                .orElseThrow(() -> ApiException.badRequest("UNKNOWN_REFERENCE", "The cover image does not exist"));
        MarkdownSource markdownSource = request.markdownSourceId() == null ? null
                : markdownSourceRepository.findById(request.markdownSourceId())
                .orElseThrow(() -> ApiException.badRequest("UNKNOWN_REFERENCE", "The Markdown file does not exist"));

        return new PostEdit(title, resolveSlug(request.slug(), title, selfId), clean(request.excerpt()),
                request.content(), content.text(), content.wordCount(), content.readingTimeMinutes(),
                cover, clean(request.coverImageAlt()), markdownSource, clean(request.seoTitle()), clean(request.metaDescription()),
                clean(request.focusKeyword()), clean(request.canonicalUrl()), Boolean.TRUE.equals(request.featured()),
                authors, tags, inlineImages);
    }

    private String resolveSlug(String requested, String title, UUID selfId) {
        String slug = requested == null || requested.isBlank() ? Slugs.slugify(title) : requested;
        if (slug.isEmpty()) {
            // An untitled draft still needs a unique URL; it can be renamed later.
            slug = "draft-" + Long.toString(ThreadLocalRandom.current().nextLong(Long.MAX_VALUE), 36);
        }
        slug = truncate(slug, MAX_SLUG_LENGTH);
        boolean taken = selfId == null ? postRepository.existsBySlug(slug) : postRepository.existsBySlugAndIdNot(slug, selfId);
        if (taken) {
            throw ApiException.conflict("SLUG_TAKEN", "Another post already uses the slug \"" + slug + "\"");
        }
        return slug;
    }

    /**
     * Loads entities by id, in the order given, rejecting duplicates and unknown ids —
     * a typo'd id must fail the save rather than silently drop an author.
     */
    private static <T> List<T> resolveOrdered(Collection<UUID> ids, Function<Collection<UUID>, List<T>> loader,
                                              Function<T, UUID> idOf, String noun) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        var unique = new LinkedHashSet<>(ids);
        if (unique.size() != ids.size()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "The same " + noun + " is listed more than once");
        }
        Map<UUID, T> found = loader.apply(unique).stream().collect(Collectors.toMap(idOf, Function.identity()));
        if (found.size() != unique.size()) {
            throw ApiException.badRequest("UNKNOWN_REFERENCE", "One or more " + noun + "s do not exist");
        }
        return unique.stream().map(found::get).toList();
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max).replaceAll("-+$", "");
    }
}
