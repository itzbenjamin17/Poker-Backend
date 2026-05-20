package com.pokergame.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class SecurityHardeningIntegrationTest extends AbstractIntegrationTestSupport {

    @Autowired
    protected JwtService jwtService;

    @Test
    @DisplayName("should handle player names with colons correctly (Identity Spoofing)")
    void givenNameWithColon_whenJoined_thenIdentityIsPreserved() throws Exception {
        String spoofedName = "Alice:anything";
        String roomName = uniqueName("ColonRoom");
        
        JsonNode data = createRoom(roomName, spoofedName, 6);

        assertThat(data.path("playerName").asText()).isEqualTo(spoofedName);
        
        // Verify token extracts correctly
        String token = data.path("token").asText();
        PlayerPrincipal principal = jwtService.extractPrincipal(token);
        assertThat(principal.playerName()).isEqualTo(spoofedName);
        assertThat(principal.roomId()).isEqualTo(data.path("roomId").asText());
    }

    @Test
    @DisplayName("should consistently sanitize names between token and storage")
    void givenNameWithSpaces_whenJoined_thenTokenAndStorageAreConsistent() throws Exception {
        String rawName = "  Bob  ";
        String sanitizedName = "Bob";
        String roomName = uniqueName("SanitizeRoom");
        
        JsonNode data = createRoom(roomName, rawName, 6);

        // Token should be for "Bob", not "  Bob  "
        assertThat(data.path("playerName").asText()).isEqualTo(sanitizedName);
        String token = data.path("token").asText();
        PlayerPrincipal principal = jwtService.extractPrincipal(token);
        assertThat(principal.playerName()).isEqualTo(sanitizedName);
    }

    @Test
    @DisplayName("should prevent joining a room after game has started")
    void givenStartedGame_whenJoinAttempted_thenReturnBadRequest() throws Exception {
        String roomName = uniqueName("StartedRoom");
        JsonNode host = createRoom(roomName, "Host", 6);
        String roomId = host.path("roomId").asText();
        
        joinRoom(roomName, "Player2");
        
        // Start game
        restClient.post()
                .uri("/api/room/" + roomId + "/start-game")
                .header("Authorization", "Bearer " + host.path("token").asText())
                .retrieve()
                .toBodilessEntity();

        // Attempt late join
        HttpClientErrorException exception = assertThrows(HttpClientErrorException.class, () -> 
            restClient.post()
                    .uri("/api/room/join")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new JoinRoomRequest(roomName, "LateComer", null))
                    .retrieve()
                    .toBodilessEntity());

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exception.getResponseBodyAsString()).contains("The game has already started");
    }

    @Test
    @DisplayName("should prevent starting a game twice")
    void givenStartedGame_whenStartAttemptedAgain_thenReturnBadRequest() throws Exception {
        String roomName = uniqueName("DoubleStartRoom");
        JsonNode host = createRoom(roomName, "Host", 6);
        String roomId = host.path("roomId").asText();
        joinRoom(roomName, "Player2");
        
        // Start game 1
        restClient.post()
                .uri("/api/room/" + roomId + "/start-game")
                .header("Authorization", "Bearer " + host.path("token").asText())
                .retrieve()
                .toBodilessEntity();

        // Start game 2
        HttpClientErrorException exception = assertThrows(HttpClientErrorException.class, () -> 
            restClient.post()
                    .uri("/api/room/" + roomId + "/start-game")
                    .header("Authorization", "Bearer " + host.path("token").asText())
                    .retrieve()
                    .toBodilessEntity());

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exception.getResponseBodyAsString()).contains("Game has already started");
    }

    @Test
    @DisplayName("should return 429 Too Many Requests when exceeding REST rate limit")
    void givenHighRequestRate_whenCreateRoomRepeatedly_thenReturn429() {
        // Explicitly enable rate limiting for this test
        ReflectionTestUtils.setField(rateLimitService, "enabled", true);
        
        String invalidRoomName = "A".repeat(51); // Triggers validation error, which prevents any "success" cleanup
        
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
    @DisplayName("should return 413 Payload Too Large when request body exceeds limit")
    void givenLargePayload_whenCreateRoom_thenReturn413() {
        StringBuilder largeContent = new StringBuilder();
        largeContent.append("A".repeat(11000)); // Limit is 10KB
        
        HttpClientErrorException exception = assertThrows(HttpClientErrorException.class, () -> 
            restClient.post()
                    .uri("/api/room/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"roomName\": \"" + largeContent.toString() + "\"}")
                    .retrieve()
                    .toBodilessEntity());

        assertThat(exception.getStatusCode().value()).isEqualTo(413);
    }

    @Test
    @DisplayName("should prevent oversubscribing room during concurrent joins")
    void givenMaxPlayers2_whenMultipleJoinConcurrently_thenOnlyLimitSucceeds() throws Exception {
        String roomName = uniqueName("ConcurrentRoom");
        int maxPlayers = 2;
        createRoom(roomName, "Host", maxPlayers);
        
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<CompletableFuture<HttpStatus>> futures = new ArrayList<>();
        
        for (int i = 0; i < 8; i++) {
            final int id = i;
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    restClient.post()
                            .uri("/api/room/join")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(new JoinRoomRequest(roomName, "Player" + id, null))
                            .retrieve()
                            .toBodilessEntity();
                    return HttpStatus.OK;
                } catch (HttpClientErrorException e) {
                    return (HttpStatus) (HttpStatus) e.getStatusCode();
                } catch (Exception e) {
                    return HttpStatus.INTERNAL_SERVER_ERROR;
                }
            }, executor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);
        
        long successCount = futures.stream()
                .map(CompletableFuture::join)
                .filter(s -> s == HttpStatus.OK)
                .count();

        // 1 host already in. Max 2. So exactly 1 more join should succeed.
        assertThat(successCount).isEqualTo(1);
        executor.shutdown();
    }
}
