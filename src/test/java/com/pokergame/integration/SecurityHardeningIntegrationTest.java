package com.pokergame.integration;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import com.pokergame.security.RateLimitService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Security hardening integration")
class SecurityHardeningIntegrationTest extends AbstractIntegrationTestSupport {

    @Autowired
    private RateLimitService rateLimitService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // Explicitly enable rate limiting for these tests and reset buckets
        org.springframework.test.util.ReflectionTestUtils.setField(rateLimitService, "enabled", true);
        rateLimitService.reset();
    }

    @Test
    @DisplayName("should return 429 Too Many Requests when exceeding REST rate limit")
    void givenHighRequestRate_whenCreateRoomRepeatedly_thenReturn429() {
        String invalidRoomName = "A".repeat(51); // Triggers validation error, which prevents cleanup
        
        // Limit is 5 per 15 mins. Send 5 failing requests.
        for (int i = 0; i < 5; i++) {
            try {
                restClient.post()
                        .uri("/api/room/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new CreateRoomRequest(invalidRoomName, "Player" + i, 6, 10, 20, 1000, null))
                        .retrieve()
                        .toBodilessEntity();
            } catch (HttpClientErrorException e) {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            }
        }

        // 6th request should fail with 429
        HttpClientErrorException exception = assertThrows(HttpClientErrorException.class, () -> 
            restClient.post()
                    .uri("/api/room/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CreateRoomRequest(invalidRoomName, "Player6", 6, 10, 20, 1000, null))
                    .retrieve()
                    .toBodilessEntity());

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("should return 400 Bad Request for malformed JSON")
    void givenMalformedJson_whenPostRequest_thenReturn400() {
        HttpClientErrorException exception = assertThrows(HttpClientErrorException.class, () -> 
            restClient.post()
                    .uri("/api/room/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"roomName\": \"Malformed\", \"maxPlayers\": \"not-a-number\"}")
                    .retrieve()
                    .toBodilessEntity());

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exception.getResponseBodyAsString()).contains("Malformed JSON request payload");
    }

    @Test
    @DisplayName("should return 413 Payload Too Large for oversized payloads")
    void givenOversizedPayload_whenPostRequest_thenReturn413() {
        // Generate a large string (> 10KB as set in PayloadSizeFilter)
        StringBuilder largeContent = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            largeContent.append("extremely-long-string-to-bloat-the-payload-");
        }
        
        HttpClientErrorException exception = assertThrows(HttpClientErrorException.class, () -> 
            restClient.post()
                    .uri("/api/room/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"roomName\": \"" + largeContent.toString() + "\"}")
                    .retrieve()
                    .toBodilessEntity());

        assertThat(exception.getStatusCode().value()).isEqualTo(413);
    }
}
