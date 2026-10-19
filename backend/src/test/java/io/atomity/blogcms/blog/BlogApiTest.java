package io.atomity.blogcms.blog;

import com.fasterxml.jackson.databind.JsonNode;
import io.atomity.blogcms.IntegrationTest;
import io.atomity.blogcms.media.TestImages;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BlogApiTest extends IntegrationTest {

    // ── Fixtures ───────────────────────────────────────────────────────────

    private static Map<String, Object> doc(String... paragraphs) {
        List<Map<String, Object>> content = new ArrayList<>();
        for (String text : paragraphs) {
            content.add(Map.of("type", "paragraph", "content", List.of(Map.of("type", "text", "text", text))));
        }
        return Map.of("type", "doc", "content", content);
    }

    private static Map<String, Object> postBody(String title, Map<String, Object> content) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("content", content);
        return body;
    }

    private JsonNode createPost(String title, String... paragraphs) throws Exception {
        return data(call(post("/api/v1/posts"), postBody(title, doc(paragraphs))).andExpect(status().isCreated()));
    }

    private JsonNode publish(String id) throws Exception {
        return data(call(post("/api/v1/posts/" + id + "/publish")).andExpect(status().isOk()));
    }

    private void archive(String id) throws Exception {
        call(post("/api/v1/posts/" + id + "/archive")).andExpect(status().isOk());
    }

    private void unpublish(String id) throws Exception {
        call(post("/api/v1/posts/" + id + "/unpublish")).andExpect(status().isOk());
    }

    private String createAuthor(String name) throws Exception {
        return data(call(post("/api/v1/authors"), Map.of("name", name)).andExpect(status().isCreated())).path("id").asText();
    }

    private String createTag(String name) throws Exception {
        return data(call(post("/api/v1/tags"), Map.of("name", name)).andExpect(status().isCreated())).path("id").asText();
    }

    private JsonNode uploadImage(String filename) throws Exception {
        var file = new MockMultipartFile("file", filename, "image/png", TestImages.encode("png", 20, 10));
        return data(call(multipart("/api/v1/media").file(file)).andExpect(status().isCreated()));
    }

    // ── List ───────────────────────────────────────────────────────────────

    @Test
    void listIsPublicAndReturnsOnlyPublishedPosts() throws Exception {
        createPost("Draft post", "Draft body must stay hidden.");

        String archived = createPost("Archived post", "Archived body must stay hidden.").path("id").asText();
        archive(archived);

        String unpublished = createPost("Unpublished post", "Was live once.").path("id").asText();
        publish(unpublished);
        unpublish(unpublished);

        String live = createPost("Live post", "Visible body.").path("id").asText();
        publish(live);

        // No token on purpose: the blog list is public.
        mockMvc.perform(get("/api/v1/blogs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(live))
                .andExpect(jsonPath("$.data[0].title").value("Live post"))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(10))
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.meta.totalPages").value(1));
    }

    @Test
    void listDefaultsToNewestFirstAndValidatesPaging() throws Exception {
        for (String title : List.of("Alpha", "Bravo", "Charlie")) {
            String id = createPost(title, title + " body.").path("id").asText();
            publish(id);
        }

        mockMvc.perform(get("/api/v1/blogs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].slug").value("charlie"))
                .andExpect(jsonPath("$.data[1].slug").value("bravo"))
                .andExpect(jsonPath("$.data[2].slug").value("alpha"));

        mockMvc.perform(get("/api/v1/blogs?sort=title&direction=asc&size=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].slug").value("alpha"))
                .andExpect(jsonPath("$.data[1].slug").value("bravo"))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(2))
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.totalPages").value(2));

        mockMvc.perform(get("/api/v1/blogs?sort=title&direction=asc&size=2&page=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].slug").value("charlie"));

        mockMvc.perform(get("/api/v1/blogs?sort=title&direction=asc&size=2&page=9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta.totalElements").value(3));

        mockMvc.perform(get("/api/v1/blogs?size=1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.size").value(100));

        mockMvc.perform(get("/api/v1/blogs?sort=password"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].errorCode").value("BAD_REQUEST"));

        mockMvc.perform(get("/api/v1/blogs?direction=sideways"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].errorCode").value("BAD_REQUEST"));
    }

    // ── Detail by id ───────────────────────────────────────────────────────

    @Test
    void detailByIdReturnsTheFullPublishedPost() throws Exception {
        String author = createAuthor("Lena");
        String tag = createTag("FinOps");
        JsonNode cover = uploadImage("cover.png");

        Map<String, Object> body = postBody("Detailed post", doc("Hello from the body."));
        body.put("authorIds", List.of(author));
        body.put("tagIds", List.of(tag));
        body.put("coverImageId", cover.path("id").asText());
        body.put("coverImageAlt", "A cover");
        body.put("seoTitle", "SEO title");
        body.put("metaDescription", "Meta description");
        body.put("canonicalUrl", "https://example.com/detailed");
        body.put("featured", true);
        String id = data(call(post("/api/v1/posts"), body).andExpect(status().isCreated())).path("id").asText();
        publish(id);

        // No token on purpose: published posts are public.
        mockMvc.perform(get("/api/v1/blogs/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.title").value("Detailed post"))
                .andExpect(jsonPath("$.data.content.type").value("doc"))
                .andExpect(jsonPath("$.data.content.content[0].content[0].text").value("Hello from the body."))
                .andExpect(jsonPath("$.data.authors[0].name").value("Lena"))
                .andExpect(jsonPath("$.data.tags[0].slug").value("finops"))
                .andExpect(jsonPath("$.data.coverImage.id").value(cover.path("id").asText()))
                .andExpect(jsonPath("$.data.coverImage.url").value(cover.path("url").asText()))
                .andExpect(jsonPath("$.data.coverImageAlt").value("A cover"))
                .andExpect(jsonPath("$.data.seoTitle").value("SEO title"))
                .andExpect(jsonPath("$.data.metaDescription").value("Meta description"))
                .andExpect(jsonPath("$.data.canonicalUrl").value("https://example.com/detailed"))
                .andExpect(jsonPath("$.data.featured").value(true));
    }

    @Test
    void detailByIdHidesEverythingThatIsNotPublished() throws Exception {
        String draft = createPost("Draft post", "Draft body.").path("id").asText();

        String archived = createPost("Archived post", "Archived body.").path("id").asText();
        publish(archived);
        archive(archived);

        String unpublished = createPost("Unpublished post", "Unpublished body.").path("id").asText();
        publish(unpublished);
        unpublish(unpublished);

        for (String id : List.of(draft, archived, unpublished, UUID.randomUUID().toString())) {
            mockMvc.perform(get("/api/v1/blogs/" + id))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errors[0].errorCode").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.errors[0].message").value("Blog not found"));
        }

        mockMvc.perform(get("/api/v1/blogs/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].errorCode").value("BAD_REQUEST"));
    }
}
