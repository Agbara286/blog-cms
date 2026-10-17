package io.atomity.blogcms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base for API tests: the full application against a real Postgres (shared by every
 * test class), an empty database before each test, and helpers for authenticated calls.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    protected static final String ADMIN_EMAIL = "admin@test.local";
    protected static final String ADMIN_PASSWORD = "test-password";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String token;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE posts, authors, tags, media, markdown_sources CASCADE");
    }

    /** A valid bearer token, fetched once through the real login endpoint. */
    protected String token() throws Exception {
        if (token == null) {
            String body = mockMvc.perform(post("/api/v1/auth/login")
                            .header("X-Forwarded-For", "token-helper")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            token = objectMapper.readTree(body).at("/data/token").asText();
        }
        return token;
    }

    /** Adds the bearer token and, if given, a JSON body. */
    protected ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json(body));
        }
        return mockMvc.perform(request);
    }

    protected ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return call(request, null);
    }

    /** The `data` field of a response. */
    protected JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    protected String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
