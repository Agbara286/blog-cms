package io.atomity.blogcms.markdown.entity;

import io.atomity.blogcms.shared.Timestamps;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** An imported .md file. The file lives in storage under {@code storageKey}; this row is its metadata. */
@Entity
@Table(name = "markdown_sources")
public class MarkdownSource {

    @Id
    private UUID id;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MarkdownSource() {
    }

    public static MarkdownSource create(UUID id, String storageKey, String originalFilename, long sizeBytes) {
        var source = new MarkdownSource();
        source.id = id;
        source.storageKey = storageKey;
        source.originalFilename = originalFilename;
        source.sizeBytes = sizeBytes;
        source.createdAt = Timestamps.now();
        return source;
    }

    public UUID getId() { return id; }
    public String getStorageKey() { return storageKey; }
    public String getOriginalFilename() { return originalFilename; }
    public long getSizeBytes() { return sizeBytes; }
    public Instant getCreatedAt() { return createdAt; }
}
