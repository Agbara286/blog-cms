package io.atomity.blogcms.markdown;

import com.fasterxml.jackson.databind.JsonNode;
import io.atomity.blogcms.IntegrationTest;
import io.atomity.blogcms.markdown.repository.MarkdownSourceRepository;
import io.atomity.blogcms.media.storage.StorageService;
import io.atomity.blogcms.shared.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MarkdownSourceApiTest extends IntegrationTest {

    private static final byte[] MARKDOWN = "# Hello\n\nSome **bold** text.\n".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MarkdownSourceRepository repository;

    @Autowired
    private StorageService storage;

    private static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "text/markdown", bytes);
    }

    private JsonNode upload(String name, byte[] bytes) throws Exception {
        return data(call(multipart("/api/v1/markdown-sources").file(file(name, bytes))).andExpect(status().isCreated()));
    }

    private static Map<String, Object> postBody(String title, Object markdownSourceId) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("content", Map.of("type", "doc", "content", List.of(
                Map.of("type", "paragraph", "content", List.of(Map.of("type", "text", "text", "Hello"))))));
        body.put("markdownSourceId", markdownSourceId);
        return body;
    }

    @Test
    void uploadStoresTheFileAndServesItBackToSignedInUsers() throws Exception {
        JsonNode source = upload("../drafts/post.md", MARKDOWN);

        assertThat(source.path("fileName").asText()).isEqualTo("post.md");
        assertThat(source.path("sizeBytes").asLong()).isEqualTo(MARKDOWN.length);

        call(get(source.path("url").asText()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/markdown;charset=UTF-8"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(MARKDOWN));

        // No token: unlike images, the original file is not public.
        mockMvc.perform(get(source.path("url").asText())).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyNonEmptyMarkdownTextFilesAreAccepted() throws Exception {
        call(multipart("/api/v1/markdown-sources").file(file("notes.txt", MARKDOWN)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errors[0].errorCode").value("UNSUPPORTED_TYPE"));

        call(multipart("/api/v1/markdown-sources").file(file("empty.md", new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].errorCode").value("EMPTY_FILE"));

        call(multipart("/api/v1/markdown-sources").file(file("binary.md", new byte[]{'a', 0, (byte) 0xff})))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errors[0].errorCode").value("UNSUPPORTED_TYPE"));

        call(multipart("/api/v1/markdown-sources").file(file("big.md", new byte[1024 * 1024 + 1])))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errors[0].errorCode").value("FILE_TOO_LARGE"));
    }

    @Test
    void postKeepsItsSourceFileUntilItIsRemoved() throws Exception {
        String sourceId = upload("post.md", MARKDOWN).path("id").asText();

        JsonNode post = data(call(post("/api/v1/posts"), postBody("Imported", sourceId)).andExpect(status().isCreated()));
        String postId = post.path("id").asText();
        assertThat(post.path("markdownSource").path("fileName").asText()).isEqualTo("post.md");

        // Reloading the post returns the file, so the editor can show it again.
        call(get("/api/v1/posts/" + postId)).andExpect(jsonPath("$.data.markdownSource.id").value(sourceId));

        // Saving without it removes the link; the post content stays.
        Map<String, Object> update = postBody("Imported", null);
        update.put("version", post.path("version").asLong());
        call(put("/api/v1/posts/" + postId), update)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markdownSource").isEmpty())
                .andExpect(jsonPath("$.data.wordCount").value(1));
    }

    @Test
    void replacedRemovedAndDeletedSourcesAreCleanedUp() throws Exception {
        String first = upload("first.md", MARKDOWN).path("id").asText();
        String firstKey = repository.findById(UUID.fromString(first)).orElseThrow().getStorageKey();
        JsonNode post = data(call(post("/api/v1/posts"), postBody("Imported", first)).andExpect(status().isCreated()));
        String postId = post.path("id").asText();

        // Replace: the old row and file go.
        String second = upload("second.md", MARKDOWN).path("id").asText();
        Map<String, Object> replace = postBody("Imported", second);
        replace.put("version", post.path("version").asLong());
        post = data(call(put("/api/v1/posts/" + postId), replace).andExpect(status().isOk()));
        call(get("/api/v1/markdown-sources/" + first + "/file")).andExpect(status().isNotFound());
        assertThatThrownBy(() -> storage.load(firstKey)).isInstanceOf(ApiException.class);

        // Remove: same for the second one.
        Map<String, Object> remove = postBody("Imported", null);
        remove.put("version", post.path("version").asLong());
        call(put("/api/v1/posts/" + postId), remove).andExpect(status().isOk());
        call(get("/api/v1/markdown-sources/" + second + "/file")).andExpect(status().isNotFound());

        // Delete the post: its source goes with it.
        String third = upload("third.md", MARKDOWN).path("id").asText();
        String otherId = data(call(post("/api/v1/posts"), postBody("Other", third)).andExpect(status().isCreated()))
                .path("id").asText();
        call(delete("/api/v1/posts/" + otherId)).andExpect(status().isNoContent());
        call(get("/api/v1/markdown-sources/" + third + "/file")).andExpect(status().isNotFound());
    }

    @Test
    void sourceSharedWithADuplicateIsKeptUntilTheLastPostLetsGo() throws Exception {
        String sourceId = upload("shared.md", MARKDOWN).path("id").asText();
        JsonNode original = data(call(post("/api/v1/posts"), postBody("Original", sourceId)).andExpect(status().isCreated()));
        String copyId = data(call(post("/api/v1/posts/" + original.path("id").asText() + "/duplicate"))
                .andExpect(status().isCreated())).path("id").asText();

        call(delete("/api/v1/posts/" + original.path("id").asText())).andExpect(status().isNoContent());
        call(get("/api/v1/markdown-sources/" + sourceId + "/file")).andExpect(status().isOk());

        call(delete("/api/v1/posts/" + copyId)).andExpect(status().isNoContent());
        call(get("/api/v1/markdown-sources/" + sourceId + "/file")).andExpect(status().isNotFound());
    }

    @Test
    void unknownSourceIsRejected() throws Exception {
        call(post("/api/v1/posts"), postBody("Imported", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].errorCode").value("UNKNOWN_REFERENCE"));
    }
}
