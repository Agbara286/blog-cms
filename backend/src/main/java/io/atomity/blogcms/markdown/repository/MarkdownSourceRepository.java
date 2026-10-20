package io.atomity.blogcms.markdown.repository;

import io.atomity.blogcms.markdown.entity.MarkdownSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface MarkdownSourceRepository extends JpaRepository<MarkdownSource, UUID> {

    /** How many posts still point at the file; a duplicated post shares its source. */
    @Query(value = "SELECT COUNT(*) FROM posts WHERE markdown_source_id = :id", nativeQuery = true)
    long countUsages(@Param("id") UUID id);
}
