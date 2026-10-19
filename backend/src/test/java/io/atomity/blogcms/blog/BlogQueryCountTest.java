package io.atomity.blogcms.blog;

import com.fasterxml.jackson.databind.JsonNode;
import io.atomity.blogcms.IntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the blog list's fetch strategy: authors and tags are loaded in batched IN queries,
 * not one query per post. The budget grows with configuration, not with the number of posts.
 */
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class BlogQueryCountTest extends IntegrationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    // ── Fixtures ───────────────────────────────────────────────────────────

    private static Map<String, Object> doc(String text) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "paragraph", "content", List.of(Map.of("type", "text", "text", text))));
        return Map.of("type", "doc", "content", content);
    }

    private static Map<String, Object> postBody(String title, Map<String, Object> content) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("content", content);
        return body;
    }

    private String createAuthor(String name) throws Exception {
        return data(call(post("/api/v1/authors"), Map.of("name", name)).andExpect(status().isCreated())).path("id").asText();
    }

    private String createTag(String name) throws Exception {
        return data(call(post("/api/v1/tags"), Map.of("name", name)).andExpect(status().isCreated())).path("id").asText();
    }

    // ── Query budget ───────────────────────────────────────────────────────

    @Test
    void listingTenPostsWithAuthorsAndTagsUsesAFixedNumberOfQueries() throws Exception {
        String author = createAuthor("Lena");
        String tag = createTag("FinOps");
        for (int i = 0; i < 10; i++) {
            Map<String, Object> body = postBody("Post " + i, doc("Body " + i));
            body.put("authorIds", List.of(author));
            body.put("tagIds", List.of(tag));
            JsonNode created = data(call(post("/api/v1/posts"), body).andExpect(status().isCreated()));
            call(post("/api/v1/posts/" + created.path("id").asText() + "/publish")).andExpect(status().isOk());
        }

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        mockMvc.perform(get("/api/v1/blogs?size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(10));

        // 1 count + 1 page of posts + 1 batched authors + 1 batched tags.
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(6);
    }
}
