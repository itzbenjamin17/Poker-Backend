package com.pokergame.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import com.pokergame.security.JwtService;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.service.RoomService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

/** Tests security integration behavior. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Security integration")
class SecurityIntegrationTest extends AbstractIntegrationTestSupport {

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RoomService roomService;

    /** Groups test scenarios for public endpoints. */
    @Nested
    @DisplayName("public endpoints")
    class PublicEndpoints {

        /**
         * Protects the contract that the system should allow creating a room without a token and return a JWT.
         */
        @Test
        @DisplayName("should allow creating a room without a token and return a JWT")
        void givenAnonymousRequest_whenCreateRoom_thenReturnRoomDataAndToken() throws Exception {
            String roomName = uniqueName("PublicCreateRoom");

            JsonNode response = objectMapper.readTree(restClient.post()
                    .uri("/api/room/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CreateRoomRequest(roomName, "NewPlayer", 6, 10, 20, 1000, null))
                    .retrieve()
                    .body(String.class));

            assertThat(response.path("message").asText()).isEqualTo("Room created successfully");
            assertThat(response.path("data").path("token").asText()).isNotBlank();
            assertThat(response.path("data").path("roomId").asText()).isNotBlank();
        }

        /**
         * Protects the contract that the system should allow joining a room without a token and return a JWT.
         */
        @Test
        @DisplayName("should allow joining a room without a token and return a JWT")
        void givenAnonymousRequest_whenJoinRoom_thenReturnJoinPayloadAndToken() throws Exception {
            String roomName = uniqueName("PublicJoinRoom");
            createRoom(roomName, "Host", 6);

            JsonNode response = objectMapper.readTree(restClient.post()
                    .uri("/api/room/join")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new JoinRoomRequest(roomName, "JoiningPlayer", null))
                    .retrieve()
                    .body(String.class));

            assertThat(response.path("message").asText()).isEqualTo("Successfully joined room");
            assertThat(response.path("data").path("token").asText()).isNotBlank();
        }
    }

    /** Groups test scenarios for secured endpoints. */
    @Nested
    @DisplayName("secured endpoints")
    class SecuredEndpoints {

        /**
         * Protects the contract that the system should reject leave room requests without a token.
         */
        @Test
        @DisplayName("should reject leave room requests without a token")
        void givenMissingToken_whenLeaveRoom_thenReturnForbidden() {
            String roomId = roomService.createRoom(new CreateRoomRequest(
                    uniqueName("SecureLeaveRoom"),
                    "TestHost",
                    6,
                    10,
                    20,
                    1000,
                    null));

            HttpClientErrorException exception = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/room/" + roomId + "/leave")
                    .retrieve()
                    .body(String.class));

            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        /**
         * Protects the contract that the system should allow leaving a room with a valid token.
         */
        @Test
        @DisplayName("should allow leaving a room with a valid token")
        void givenValidToken_whenLeaveRoom_thenReturnSuccessResponse() {
            String roomName = uniqueName("SecureLeaveRoomValid");
            String roomId = roomService.createRoom(new CreateRoomRequest(roomName, "TestHost", 6, 10, 20, 1000, null));
            String validToken = jwtService.generateToken("TestHost", roomId);

            String response = restClient.post()
                    .uri("/api/room/" + roomId + "/leave")
                    .header("Authorization", "Bearer " + validToken)
                    .retrieve()
                    .body(String.class);

            assertThat(response).contains("\"message\":\"Successfully left room\"");
        }

        /**
         * Protects the contract that the system should reject invalid JWTs on secured endpoints.
         */
        @Test
        @DisplayName("should reject invalid JWTs on secured endpoints")
        void givenInvalidToken_whenLeaveRoom_thenReturnForbidden() {
            String roomId = roomService.createRoom(new CreateRoomRequest(
                    uniqueName("SecureLeaveRoomInvalid"),
                    "TestHost",
                    6,
                    10,
                    20,
                    1000,
                    null));

            HttpClientErrorException exception = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/room/" + roomId + "/leave")
                    .header("Authorization", "Bearer invalid-token-here")
                    .retrieve()
                    .body(String.class));

            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        /**
         * Protects the contract that the system should reject missing tokens for start game, leave game, and claim win.
         */
        @Test
        @DisplayName("should reject missing tokens for start game, leave game, and claim win")
        void givenMissingTokens_whenCallingProtectedEndpoints_thenReturnForbidden() {
            String roomId = roomService.createRoom(new CreateRoomRequest(
                    uniqueName("ProtectedEndpoints"),
                    "TestHost",
                    6,
                    10,
                    20,
                    1000,
                    null));

            assertThat(assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/room/" + roomId + "/start-game")
                    .retrieve()
                    .body(String.class)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            assertThat(assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/game/some-game-id/leave")
                    .retrieve()
                    .body(String.class)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            assertThat(assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/game/some-game-id/claim-win")
                    .retrieve()
                    .body(String.class)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    /** Groups test scenarios for authorization. */
    @Nested
    @DisplayName("authorization")
    class Authorization {

        /**
         * Protects the contract that the system should reject start game requests from non-host players.
         */
        @Test
        @DisplayName("should reject start game requests from non-host players")
        void givenNonHostToken_whenStartGame_thenReturnClientError() throws Exception {
            String roomName = uniqueName("AuthRoom");
            JsonNode createData = createRoom(roomName, "ActualHost", 6);
            String roomId = createData.path("roomId").asText();
            joinRoom(roomName, "NonHostPlayer");

            String nonHostToken = jwtService.generateToken("NonHostPlayer", roomId);

            HttpClientErrorException exception = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/room/" + roomId + "/start-game")
                    .header("Authorization", "Bearer " + nonHostToken)
                    .retrieve()
                    .body(String.class));

            assertThat(exception.getStatusCode().is4xxClientError()).isTrue();
        }

        /**
         * Protects the contract that the system should allow hosts to start a game once enough players have joined.
         */
        @Test
        @DisplayName("should allow hosts to start a game once enough players have joined")
        void givenHostTokenAndEnoughPlayers_whenStartGame_thenReturnSuccessResponse() throws Exception {
            String roomName = uniqueName("HostStartRoom");
            JsonNode createData = createRoom(roomName, "GameHost", 6);
            String roomId = createData.path("roomId").asText();
            String hostToken = createData.path("token").asText();
            joinRoom(roomName, "SecondPlayer");

            JsonNode response = startGame(roomId, hostToken);

            assertThat(response.path("message").asText()).isEqualTo("Game started successfully");
        }
    }

    /** Groups test scenarios for token validation. */
    @Nested
    @DisplayName("token validation")
    class TokenValidation {

        /**
         * Protects the contract that the system should round-trip the player name through the JWT subject.
         */
        @Test
        @DisplayName("should round-trip the player name through the JWT subject")
        void givenPlayerName_whenGenerateToken_thenExtractSamePlayerName() {
            String playerName = "TokenTestPlayer";
            String roomId = "test-room";
            String token = jwtService.generateToken(playerName, roomId);

            assertThat(jwtService.extractPlayerName(token)).isEqualTo(playerName);

            PlayerPrincipal principal = jwtService.extractPrincipal(token);
            assertThat(principal.playerName()).isEqualTo(playerName);
            assertThat(principal.roomId()).isEqualTo(roomId);
        }

        /**
         * Protects the contract that the system should validate fresh tokens and reject invalid or tampered ones.
         */
        @Test
        @DisplayName("should validate fresh tokens and reject invalid or tampered ones")
        void givenDifferentTokenStates_whenValidate_thenReturnExpectedResult() {
            String token = jwtService.generateToken("ValidPlayer", "test-room");
            String tamperedToken = token.substring(0, token.length() - 5) + "XXXXX";

            assertThat(jwtService.isTokenValid(token)).isTrue();
            assertThat(jwtService.isTokenValid("completely.invalid.token")).isFalse();
            assertThat(jwtService.isTokenValid(tamperedToken)).isFalse();
        }
    }
}
