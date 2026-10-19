package io.atomity.blogcms.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.atomity.blogcms.shared.ApiError;
import io.atomity.blogcms.shared.ApiResponse;
import io.atomity.blogcms.shared.CorrelationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler;
import org.springframework.security.web.firewall.RequestRejectedHandler;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Stateless bearer-token auth. The frontend's route handlers hold the token in an
 * httpOnly cookie and forward it as {@code Authorization: Bearer}; the browser never
 * talks to this service directly, so there is no CORS or CSRF surface to configure.
 */
@Configuration
public class SecurityConfig {

    public static final String ISSUER = "blog-cms";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health/**", "/metrics").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        // Image files are public so published pages can embed them.
                        .requestMatchers(HttpMethod.GET, "/api/v1/media/*/file").permitAll()
                        // Published blog posts are public so a website can read them without signing in.
                        .requestMatchers(HttpMethod.GET, "/api/v1/blogs", "/api/v1/blogs/*", "/api/v1/blogs/slug/*").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> {
                        })
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED,
                                        "UNAUTHORIZED", "Sign in to continue"))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(response, objectMapper, HttpServletResponse.SC_FORBIDDEN,
                                        "FORBIDDEN", "You are not allowed to do that")))
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, e) ->
                        writeError(response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED,
                                "UNAUTHORIZED", "Sign in to continue")));
        return http.build();
    }

    /** Requests the firewall refuses (e.g. control characters in a header) are the client's fault: 400, not 500. */
    @Bean
    public RequestRejectedHandler requestRejectedHandler() {
        return new HttpStatusRequestRejectedHandler();
    }

    @Bean
    public JwtEncoder jwtEncoder(AuthProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
    }

    @Bean
    public JwtDecoder jwtDecoder(AuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        return decoder;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static SecretKey secretKey(AuthProperties properties) {
        return new SecretKeySpec(properties.jwt().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** Security errors use the same envelope as every other error response. */
    private static void writeError(HttpServletResponse response, ObjectMapper objectMapper, int status,
                                   String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        var error = new ApiError(code, message, MDC.get(CorrelationFilter.MDC_CORRELATION_KEY));
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(error));
    }
}
