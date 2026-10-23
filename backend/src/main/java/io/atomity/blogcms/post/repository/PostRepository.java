package io.atomity.blogcms.post.repository;

import io.atomity.blogcms.post.entity.Post;
import io.atomity.blogcms.post.entity.PostStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PostRepository extends JpaRepository<Post, UUID>, JpaSpecificationExecutor<Post> {

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, UUID id);

    /** Public blog lookup by id — only published posts are ever exposed. */
    Optional<Post> findByIdAndStatus(UUID id, PostStatus status);

    /** Public blog lookup by slug — only published posts are ever exposed. */
    Optional<Post> findBySlugAndStatus(String slug, PostStatus status);

    @Query("SELECT p.status AS status, COUNT(p) AS count FROM Post p GROUP BY p.status")
    List<StatusCount> countByStatus();

    interface StatusCount {
        io.atomity.blogcms.post.entity.PostStatus getStatus();
        Long getCount();
    }
}
