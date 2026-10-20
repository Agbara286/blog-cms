package io.atomity.blogcms.post.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.atomity.blogcms.author.entity.Author;
import io.atomity.blogcms.markdown.entity.MarkdownSource;
import io.atomity.blogcms.media.entity.Media;
import io.atomity.blogcms.shared.Timestamps;
import io.atomity.blogcms.tag.entity.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A blog post. Content and metadata change through {@link #edit}; the lifecycle
 * (draft → published → archived) only through the intent methods, so the
 * publishing rules live in one place.
 */
@Entity
@Table(name = "posts")
public class Post {

    @Id
    private UUID id;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "slug", nullable = false, unique = true)
    private String slug;

    @Column(name = "excerpt", nullable = false)
    private String excerpt;

    /** Tiptap/ProseMirror document, stored as jsonb. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", nullable = false, columnDefinition = "jsonb")
    private JsonNode content;

    /** Plain text of {@link #content}, kept for search. */
    @Column(name = "content_text", nullable = false)
    private String contentText;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PostStatus status;

    @Column(name = "featured", nullable = false)
    private boolean featured;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cover_image_id")
    private Media coverImage;

    @Column(name = "cover_image_alt", nullable = false)
    private String coverImageAlt;

    /** The .md file the content was imported from, if any. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "markdown_source_id")
    private MarkdownSource markdownSource;

    @Column(name = "seo_title", nullable = false)
    private String seoTitle;

    @Column(name = "meta_description", nullable = false)
    private String metaDescription;

    @Column(name = "focus_keyword", nullable = false)
    private String focusKeyword;

    @Column(name = "canonical_url", nullable = false)
    private String canonicalUrl;

    @Column(name = "word_count", nullable = false)
    private int wordCount;

    @Column(name = "reading_time_minutes", nullable = false)
    private int readingTimeMinutes;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @ManyToMany
    @JoinTable(name = "post_authors",
            joinColumns = @JoinColumn(name = "post_id"),
            inverseJoinColumns = @JoinColumn(name = "author_id"))
    @OrderColumn(name = "position")
    private List<Author> authors = new ArrayList<>();

    @ManyToMany
    @JoinTable(name = "post_tags",
            joinColumns = @JoinColumn(name = "post_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<Tag> tags = new HashSet<>();

    /** Library images embedded in the body — derived from {@link #content} on every edit. */
    @ManyToMany
    @JoinTable(name = "post_images",
            joinColumns = @JoinColumn(name = "post_id"),
            inverseJoinColumns = @JoinColumn(name = "media_id"))
    private Set<Media> inlineImages = new HashSet<>();

    protected Post() {
    }

    /** A new post always starts as a draft. */
    public static Post create(PostEdit edit) {
        var post = new Post();
        post.id = UUID.randomUUID();
        post.status = PostStatus.DRAFT;
        post.createdAt = Timestamps.now();
        post.edit(edit);
        return post;
    }

    public void edit(PostEdit edit) {
        this.title = edit.title();
        this.slug = edit.slug();
        this.excerpt = edit.excerpt();
        this.content = edit.content();
        this.contentText = edit.contentText();
        this.wordCount = edit.wordCount();
        this.readingTimeMinutes = edit.readingTimeMinutes();
        this.coverImage = edit.coverImage();
        this.coverImageAlt = edit.coverImageAlt();
        this.markdownSource = edit.markdownSource();
        this.seoTitle = edit.seoTitle();
        this.metaDescription = edit.metaDescription();
        this.focusKeyword = edit.focusKeyword();
        this.canonicalUrl = edit.canonicalUrl();
        this.featured = edit.featured();
        replace(this.authors, edit.authors());
        replace(this.tags, edit.tags());
        replace(this.inlineImages, edit.inlineImages());
        touch();
    }

    /**
     * Makes the post live. The first publication date is kept across
     * unpublish/republish, so re-publishing doesn't move a post to the top of feeds.
     */
    public void publish() {
        status = PostStatus.PUBLISHED;
        if (publishedAt == null) {
            publishedAt = Timestamps.now();
        }
        touch();
    }

    /** Back to draft — from published, or restoring an archived post. */
    public void unpublish() {
        status = PostStatus.DRAFT;
        touch();
    }

    public void archive() {
        status = PostStatus.ARCHIVED;
        touch();
    }

    /** True when there's nothing to read yet: no words and no images. */
    public boolean hasNoContent() {
        return wordCount == 0 && inlineImages.isEmpty();
    }

    /** A new draft with the same content and metadata; not featured, never published. */
    public Post duplicate(String newTitle, String newSlug) {
        var copy = new Post();
        copy.id = UUID.randomUUID();
        copy.status = PostStatus.DRAFT;
        copy.createdAt = Timestamps.now();
        copy.edit(new PostEdit(newTitle, newSlug, excerpt, content.deepCopy(), contentText, wordCount, readingTimeMinutes,
                coverImage, coverImageAlt, markdownSource, seoTitle, metaDescription, focusKeyword, canonicalUrl, false,
                authors, tags, inlineImages));
        return copy;
    }

    private void touch() {
        updatedAt = Timestamps.now();
    }

    /** Edits the managed collection in place so Hibernate tracks the change. */
    private static <T> void replace(Collection<T> target, Collection<T> source) {
        target.clear();
        target.addAll(source);
    }

    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public String getSlug() { return slug; }
    public String getExcerpt() { return excerpt; }
    public JsonNode getContent() { return content; }
    public PostStatus getStatus() { return status; }
    public boolean isFeatured() { return featured; }
    public Media getCoverImage() { return coverImage; }
    public String getCoverImageAlt() { return coverImageAlt; }
    public MarkdownSource getMarkdownSource() { return markdownSource; }
    public String getSeoTitle() { return seoTitle; }
    public String getMetaDescription() { return metaDescription; }
    public String getFocusKeyword() { return focusKeyword; }
    public String getCanonicalUrl() { return canonicalUrl; }
    public int getWordCount() { return wordCount; }
    public int getReadingTimeMinutes() { return readingTimeMinutes; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    public List<Author> getAuthors() { return authors; }
    public Set<Tag> getTags() { return tags; }
}
