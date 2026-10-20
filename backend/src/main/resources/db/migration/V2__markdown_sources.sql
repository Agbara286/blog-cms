-- Original .md files imported into posts. Kept apart from the media library, which only holds images.

CREATE TABLE markdown_sources (
    id                UUID         PRIMARY KEY,
    storage_key       VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    size_bytes        BIGINT       NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_markdown_sources_storage_key UNIQUE (storage_key)
);

ALTER TABLE posts ADD COLUMN markdown_source_id UUID REFERENCES markdown_sources (id) ON DELETE RESTRICT;

CREATE INDEX idx_posts_markdown_source_id ON posts (markdown_source_id);
