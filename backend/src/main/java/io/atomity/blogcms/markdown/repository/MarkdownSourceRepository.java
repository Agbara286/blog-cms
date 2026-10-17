package io.atomity.blogcms.markdown.repository;

import io.atomity.blogcms.markdown.entity.MarkdownSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface MarkdownSourceRepository extends JpaRepository<MarkdownSource, UUID> {
}
