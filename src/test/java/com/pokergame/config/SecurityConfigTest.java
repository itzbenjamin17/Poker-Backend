package com.pokergame.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for Spring Security configuration and authentication entry point.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("SecurityConfig")
class SecurityConfigTest extends AbstractIntegrationTestSupport {

    @Test
    @DisplayName("missing token on protected endpoint returns 401 with ErrorResponse JSON")
    void givenMissingToken_whenAccessingProtectedEndpoint_thenReturn401ErrorResponse() throws Exception {
        HttpClientErrorException exception = assertThrows(HttpClientErrorException.class, () ->
                restClient.get()
                        .uri("/api/room/any-room-id")
                        .retrieve()
                        .body(String.class));

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        JsonNode body = objectMapper.readTree(exception.getResponseBodyAsString());
        assertThat(body.path("status").asInt()).isEqualTo(401);
        assertThat(body.path("error").asText()).isEqualTo("Unauthorized");
        assertThat(body.path("message").asText()).isEqualTo("Session expired or invalid token");
    }

    @Test
    @DisplayName("invalid token on protected endpoint returns 401 with ErrorResponse JSON")
    void givenInvalidToken_whenAccessingProtectedEndpoint_thenReturn401ErrorResponse() throws Exception {
        HttpClientErrorException exception = assertThrows(HttpClientErrorException.class, () ->
                restClient.get()
                        .uri("/api/room/any-room-id")
                        .header("Authorization", "Bearer invalid-or-expired-token")
                        .retrieve()
                        .body(String.class));

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        JsonNode body = objectMapper.readTree(exception.getResponseBodyAsString());
        assertThat(body.path("status").asInt()).isEqualTo(401);
        assertThat(body.path("error").asText()).isEqualTo("Unauthorized");
        assertThat(body.path("message").asText()).isEqualTo("Session expired or invalid token");
    }
}
