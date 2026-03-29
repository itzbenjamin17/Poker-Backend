package com.pokergame.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.security.JwtService;
import com.pokergame.service.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for JWT authentication and authorization.
 * Tests public endpoints, secured endpoints, and token validation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityIntegrationTest {

        @LocalServerPort
        private int port;

        private final ObjectMapper objectMapper = new ObjectMapper();

        @Autowired
        private JwtService jwtService;

        @Autowired
        private RoomService roomService;

        private RestClient restClient;
        private String validToken;
        private String roomId;

        @BeforeEach
        void setUp() {
                restClient = RestClient.builder()
                                .baseUrl("http://localhost:" + port)
                                .build();

                // Create a room and get a valid token for testing secured endpoints
                CreateRoomRequest createRequest = new CreateRoomRequest(
                                "TestRoom" + UUID.randomUUID().toString().substring(0, 8),
                                "TestHost",
                                6, 10, 20, 1000, null);

                roomId = roomService.createRoom(createRequest);
                validToken = jwtService.generateToken("TestHost");
        }

        @Nested
        @DisplayName("Public Endpoints (No Token Required)")
        class PublicEndpointTests {

                @Test
                @DisplayName("POST /api/room/create - should succeed without token and return JWT")
                void createRoom_shouldSucceed_withoutToken() {
                        CreateRoomRequest request = new CreateRoomRequest(
                                        "NewRoom" + UUID.randomUUID().toString().substring(0, 8),
                                        "NewPlayer",
                                        6, 10, 20, 1000, null);

                        String response = restClient.post()
                                        .uri("/api/room/create")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .body(request)
                                        .retrieve()
                                        .body(String.class);

                        assertNotNull(response);
                        assertTrue(response.contains("\"message\":\"Room created successfully\""));
                        assertTrue(response.contains("\"token\""));
                        assertTrue(response.contains("\"roomId\""));
                }

                @Test
                @DisplayName("POST /api/room/join - should succeed without token and return JWT")
                void joinRoom_shouldSucceed_withoutToken() {
                        // First create a room
                        String roomName = "JoinTestRoom" + UUID.randomUUID().toString().substring(0, 8);
                        CreateRoomRequest createRequest = new CreateRoomRequest(
                                        roomName, "Host", 6, 10, 20, 1000, null);

                        restClient.post()
                                        .uri("/api/room/create")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .body(createRequest)
                                        .retrieve()
                                        .body(String.class);

                        // Now join the room
                        JoinRoomRequest joinRequest = new JoinRoomRequest(roomName, "JoiningPlayer", null);

                        String response = restClient.post()
                                        .uri("/api/room/join")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .body(joinRequest)
                                        .retrieve()
                                        .body(String.class);

                        assertNotNull(response);
                        assertTrue(response.contains("\"message\":\"Successfully joined room\""));
                        assertTrue(response.contains("\"token\""));
                }
        }

        @Nested
        @DisplayName("Secured Endpoints (Token Required)")
        class SecuredEndpointTests {

                @Test
                @DisplayName("POST /api/room/{roomId}/leave - should fail without token (403)")
                void leaveRoom_shouldFail_withoutToken() {
                        HttpClientErrorException exception = assertThrows(HttpClientErrorException.class,
                                        () -> restClient.post()
                                                        .uri("/api/room/" + roomId + "/leave")
                                                        .retrieve()
                                                        .body(String.class));

                        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
                }

                @Test
                @DisplayName("POST /api/room/{roomId}/leave - should succeed with valid token")
                void leaveRoom_shouldSucceed_withValidToken() {
                        String response = restClient.post()
                                        .uri("/api/room/" + roomId + "/leave")
                                        .header("Authorization", "Bearer " + validToken)
                                        .retrieve()
                                        .body(String.class);

                        assertNotNull(response);
                        assertTrue(response.contains("\"message\":\"Successfully left room\""));
                }

                @Test
                @DisplayName("POST /api/room/{roomId}/leave - should fail with invalid token (403)")
                void leaveRoom_shouldFail_withInvalidToken() {
                        HttpClientErrorException exception = assertThrows(HttpClientErrorException.class,
                                        () -> restClient.post()
                                                        .uri("/api/room/" + roomId + "/leave")
                                                        .header("Authorization", "Bearer invalid-token-here")
                                                        .retrieve()
                                                        .body(String.class));

                        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
                }

                @Test
                @DisplayName("POST /api/room/{roomId}/start-game - should fail without token (403)")
                void startGame_shouldFail_withoutToken() {
                        HttpClientErrorException exception = assertThrows(HttpClientErrorException.class,
                                        () -> restClient.post()
                                                        .uri("/api/room/" + roomId + "/start-game")
                                                        .retrieve()
                                                        .body(String.class));

                        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
                }

                @Test
                @DisplayName("POST /api/game/{gameId}/leave - should fail without token (403)")
                void leaveGame_shouldFail_withoutToken() {
                        HttpClientErrorException exception = assertThrows(HttpClientErrorException.class,
                                        () -> restClient.post()
                                                        .uri("/api/game/some-game-id/leave")
                                                        .retrieve()
                                                        .body(String.class));

                        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
                }

                @Test
                @DisplayName("POST /api/game/{gameId}/claim-win - should fail without token (403)")
                void claimWin_shouldFail_withoutToken() {
                        HttpClientErrorException exception = assertThrows(HttpClientErrorException.class,
                                        () -> restClient.post()
                                                        .uri("/api/game/some-game-id/claim-win")
                                                        .retrieve()
                                                        .body(String.class));

                        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
                }
        }

        @Nested
        @DisplayName("Authorization Tests (Host-Only Actions)")
        class AuthorizationTests {

                @Test
                @DisplayName("POST /api/room/{roomId}/start-game - should fail when not host")
                void startGame_shouldFail_whenNotHost() throws Exception {
                        // Create a room as Host
                        String roomName = "AuthTestRoom" + UUID.randomUUID().toString().substring(0, 8);
                        CreateRoomRequest createRequest = new CreateRoomRequest(
                                        roomName, "ActualHost", 6, 10, 20, 1000, null);

                        String createResponse = restClient.post()
                                        .uri("/api/room/create")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .body(createRequest)
                                        .retrieve()
                                        .body(String.class);

                        String createdRoomId = objectMapper.readTree(createResponse)
                                        .path("data").path("roomId").asText();

                        // Join as another player
                        JoinRoomRequest joinRequest = new JoinRoomRequest(roomName, "NonHostPlayer", null);
                        restClient.post()
                                        .uri("/api/room/join")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .body(joinRequest)
                                        .retrieve()
                                        .body(String.class);

                        // Generate token for non-host player
                        String nonHostToken = jwtService.generateToken("NonHostPlayer");

                        // Try to start game as non-host - should fail authorization
                        HttpClientErrorException exception = assertThrows(HttpClientErrorException.class,
                                        () -> restClient.post()
                                                        .uri("/api/room/" + createdRoomId + "/start-game")
                                                        .header("Authorization", "Bearer " + nonHostToken)
                                                        .retrieve()
                                                        .body(String.class));

                        assertTrue(exception.getStatusCode().is4xxClientError());
                }

                @Test
                @DisplayName("POST /api/room/{roomId}/start-game - should succeed when host with enough players")
                void startGame_shouldSucceed_whenHost() throws Exception {
                        // Create a room as Host
                        String roomName = "StartGameRoom" + UUID.randomUUID().toString().substring(0, 8);
                        CreateRoomRequest createRequest = new CreateRoomRequest(
                                        roomName, "GameHost", 6, 10, 20, 1000, null);

                        String responseBody = restClient.post()
                                        .uri("/api/room/create")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .body(createRequest)
                                        .retrieve()
                                        .body(String.class);
                        String createdRoomId = objectMapper.readTree(responseBody).path("data").path("roomId").asText();
                        String hostToken = objectMapper.readTree(responseBody).path("data").path("token").asText();

                        // Join another player so we have 2 players
                        JoinRoomRequest joinRequest = new JoinRoomRequest(roomName, "SecondPlayer", null);
                        restClient.post()
                                        .uri("/api/room/join")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .body(joinRequest)
                                        .retrieve()
                                        .body(String.class);

                        // Start game as host - should succeed
                        String response = restClient.post()
                                        .uri("/api/room/" + createdRoomId + "/start-game")
                                        .header("Authorization", "Bearer " + hostToken)
                                        .retrieve()
                                        .body(String.class);

                        assertNotNull(response);
                        assertTrue(response.contains("\"message\":\"Game started successfully\""));
                }
        }

        @Nested
        @DisplayName("Token Validation Tests")
        class TokenValidationTests {

                @Test
                @DisplayName("Token should contain correct player name")
                void token_shouldContainCorrectPlayerName() {
                        String playerName = "TokenTestPlayer";
                        String token = jwtService.generateToken(playerName);

                        String extractedName = jwtService.extractPlayerName(token);
                        assertEquals(playerName, extractedName);
                }

                @Test
                @DisplayName("Token should be valid after generation")
                void token_shouldBeValidAfterGeneration() {
                        String token = jwtService.generateToken("ValidPlayer");
                        assertTrue(jwtService.isTokenValid(token));
                }

                @Test
                @DisplayName("Invalid token should fail validation")
                void invalidToken_shouldFailValidation() {
                        assertFalse(jwtService.isTokenValid("completely.invalid.token"));
                }

                @Test
                @DisplayName("Tampered token should fail validation")
                void tamperedToken_shouldFailValidation() {
                        String validToken = jwtService.generateToken("TamperTest");
                        String tamperedToken = validToken.substring(0, validToken.length() - 5) + "XXXXX";
                        assertFalse(jwtService.isTokenValid(tamperedToken));
                }
        }
}
