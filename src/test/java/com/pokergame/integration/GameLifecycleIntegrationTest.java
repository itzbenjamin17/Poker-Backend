package com.pokergame.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.PlayerAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GameLifecycleIntegrationTest {

    @LocalServerPort
    private int port;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestClient restClient;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    @DisplayName("Host can start game when room has at least two players")
    void startGame_hostWithEnoughPlayers_shouldSucceed() throws Exception {
        String roomName = uniqueName("StartGameRoom");

        JsonNode hostData = createRoom(roomName, "HostStart", 6);
        String roomId = hostData.path("roomId").asText();
        String hostToken = hostData.path("token").asText();

        joinRoom(roomName, "SecondStartPlayer");

        String startBody = restClient.post()
                .uri("/api/room/" + roomId + "/start-game")
                .header("Authorization", "Bearer " + hostToken)
                .retrieve()
                .body(String.class);

        assertNotNull(startBody);
        JsonNode response = objectMapper.readTree(startBody);
        assertEquals("Game started successfully", response.path("message").asText());
        assertEquals(roomId, response.path("data").asText());
    }

    @Test
    @DisplayName("Non-host cannot start game")
    void startGame_nonHost_shouldReturnForbidden() throws Exception {
        String roomName = uniqueName("NonHostStartRoom");

        JsonNode hostData = createRoom(roomName, "HostOnly", 6);
        String roomId = hostData.path("roomId").asText();

        JsonNode joinData = joinRoom(roomName, "NonHostUser");
        String nonHostToken = joinData.path("token").asText();

        HttpClientErrorException ex = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                .uri("/api/room/" + roomId + "/start-game")
                .header("Authorization", "Bearer " + nonHostToken)
                .retrieve()
                .body(String.class));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getResponseBodyAsString().contains("Only the room host can start the game"));
    }

    @Test
    @DisplayName("Cannot start game with less than two players")
    void startGame_withSinglePlayer_shouldReturnForbidden() throws Exception {
        String roomName = uniqueName("SinglePlayerStartRoom");

        JsonNode hostData = createRoom(roomName, "SoloHost", 6);
        String roomId = hostData.path("roomId").asText();
        String hostToken = hostData.path("token").asText();

        HttpClientErrorException ex = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                .uri("/api/room/" + roomId + "/start-game")
                .header("Authorization", "Bearer " + hostToken)
                .retrieve()
                .body(String.class));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getResponseBodyAsString().contains("Need at least 2 players to start game"));
    }

    @Test
    @DisplayName("Game leave without token is forbidden")
    void leaveGame_withoutToken_shouldReturnForbidden() {
        HttpClientErrorException ex = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                .uri("/api/game/some-game-id/leave")
                .retrieve()
                .body(String.class));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    @DisplayName("Game leave with unknown game id returns 404")
    void leaveGame_unknownGame_shouldReturnNotFound() throws Exception {
        String roomName = uniqueName("UnknownGameRoom");
        JsonNode hostData = createRoom(roomName, "HostUnknownGame", 6);
        String hostToken = hostData.path("token").asText();

        HttpClientErrorException ex = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                .uri("/api/game/" + UUID.randomUUID() + "/leave")
                .header("Authorization", "Bearer " + hostToken)
                .retrieve()
                .body(String.class));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertTrue(ex.getResponseBodyAsString().contains("Game not found"));
    }

    @Test
    @DisplayName("Player can leave active game through lifecycle endpoint")
    void leaveGame_validPlayerInStartedGame_shouldSucceed() throws Exception {
        String roomName = uniqueName("LeaveGameRoom");

        JsonNode hostData = createRoom(roomName, "HostLeaveGame", 6);
        String roomId = hostData.path("roomId").asText();
        String hostToken = hostData.path("token").asText();

        JsonNode joinData = joinRoom(roomName, "SecondLeaveGame");
        String secondPlayerToken = joinData.path("token").asText();

        restClient.post()
                .uri("/api/room/" + roomId + "/start-game")
                .header("Authorization", "Bearer " + hostToken)
                .retrieve()
                .body(String.class);

        String leaveBody = restClient.post()
                .uri("/api/game/" + roomId + "/leave")
                .header("Authorization", "Bearer " + secondPlayerToken)
                .retrieve()
                .body(String.class);

        assertNotNull(leaveBody);
        JsonNode response = objectMapper.readTree(leaveBody);
        assertEquals("Successfully left game", response.path("message").asText());
    }

    @Test
    @DisplayName("Two players can complete a full hand round through public game action API")
    void fullRound_twoPlayers_viaPublicApiActions_shouldCompleteAndStartNextHand() throws Exception {
        String roomName = uniqueName("FullRoundRoom");

        JsonNode hostData = createRoom(roomName, "RoundHost", 6);
        String gameId = hostData.path("roomId").asText();
        String hostToken = hostData.path("token").asText();

        JsonNode joinData = joinRoom(roomName, "RoundGuest");
        String guestToken = joinData.path("token").asText();

        restClient.post()
                .uri("/api/room/" + gameId + "/start-game")
                .header("Authorization", "Bearer " + hostToken)
                .retrieve()
                .body(String.class);

        // Play one complete hand via API actions: pre-flop call, then check/check
        // across flop-turn-river.
        performActionByCurrentPlayer(gameId, new PlayerActionRequest(PlayerAction.CALL, null), hostToken, guestToken);
        performActionByCurrentPlayer(gameId, new PlayerActionRequest(PlayerAction.CHECK, null), hostToken, guestToken);
        performActionByCurrentPlayer(gameId, new PlayerActionRequest(PlayerAction.CHECK, null), hostToken, guestToken);
        performActionByCurrentPlayer(gameId, new PlayerActionRequest(PlayerAction.CHECK, null), hostToken, guestToken);
        performActionByCurrentPlayer(gameId, new PlayerActionRequest(PlayerAction.CHECK, null), hostToken, guestToken);
        performActionByCurrentPlayer(gameId, new PlayerActionRequest(PlayerAction.CHECK, null), hostToken, guestToken);
        performActionByCurrentPlayer(gameId, new PlayerActionRequest(PlayerAction.CHECK, null), hostToken, guestToken);

        // Showdown schedules new hand start after delay; verify another legal pre-flop
        // action succeeds.
        TimeUnit.MILLISECONDS.sleep(5500);
        String actingToken = performActionByCurrentPlayer(
                gameId,
                new PlayerActionRequest(PlayerAction.CALL, null),
                hostToken,
                guestToken);

        assertTrue(actingToken.equals(hostToken) || actingToken.equals(guestToken));
    }

    @Test
    @DisplayName("Game end cleanup removes room when one player remains")
    void endGame_whenOnePlayerRemains_shouldEventuallyDestroyRoom() throws Exception {
        String roomName = uniqueName("EndGameCleanupRoom");

        JsonNode hostData = createRoom(roomName, "EndHost", 6);
        String gameId = hostData.path("roomId").asText();
        String hostToken = hostData.path("token").asText();

        JsonNode joinData = joinRoom(roomName, "EndGuest");
        String guestToken = joinData.path("token").asText();

        restClient.post()
                .uri("/api/room/" + gameId + "/start-game")
                .header("Authorization", "Bearer " + hostToken)
                .retrieve()
                .body(String.class);

        String leaveBody = restClient.post()
                .uri("/api/game/" + gameId + "/leave")
                .header("Authorization", "Bearer " + guestToken)
                .retrieve()
                .body(String.class);

        assertNotNull(leaveBody);
        assertTrue(leaveBody.contains("Successfully left game"));

        boolean roomDestroyed = awaitRoomDestruction(gameId, hostToken, 8000);
        assertTrue(roomDestroyed, "Room should be destroyed after game end cleanup when one player remains");
    }

    private String performActionByCurrentPlayer(
            String gameId,
            PlayerActionRequest request,
            String tokenA,
            String tokenB) {
        if (tryPerformAction(gameId, request, tokenA)) {
            return tokenA;
        }
        if (tryPerformAction(gameId, request, tokenB)) {
            return tokenB;
        }

        fail("Neither player could perform action " + request.action() + " for game " + gameId);
        return "";
    }

    private boolean tryPerformAction(String gameId, PlayerActionRequest request, String token) {
        try {
            restClient.post()
                    .uri("/api/game/" + gameId + "/action")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.FORBIDDEN &&
                    ex.getResponseBodyAsString().contains("It's not your turn")) {
                return false;
            }
            throw ex;
        }
    }

    private boolean awaitRoomDestruction(String roomId, String token, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;

        while (System.currentTimeMillis() < deadline) {
            try {
                restClient.get()
                        .uri("/api/room/" + roomId)
                        .header("Authorization", "Bearer " + token)
                        .retrieve()
                        .body(String.class);
            } catch (HttpClientErrorException ex) {
                if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                    return true;
                }
                throw ex;
            }

            TimeUnit.MILLISECONDS.sleep(250);
        }

        return false;
    }

    private JsonNode createRoom(String roomName, String hostName, int maxPlayers) throws Exception {
        CreateRoomRequest request = new CreateRoomRequest(roomName, hostName, maxPlayers, 10, 20, 1000, null);

        String body = restClient.post()
                .uri("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(String.class);

        assertNotNull(body);
        JsonNode json = objectMapper.readTree(body);
        assertEquals("Room created successfully", json.path("message").asText());

        JsonNode data = json.path("data");
        assertFalse(data.path("roomId").asText().isBlank());
        assertFalse(data.path("token").asText().isBlank());
        assertEquals(hostName, data.path("playerName").asText());
        return data;
    }

    private JsonNode joinRoom(String roomName, String playerName) throws Exception {
        JoinRoomRequest request = new JoinRoomRequest(roomName, playerName, null);

        String body = restClient.post()
                .uri("/api/room/join")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(String.class);

        assertNotNull(body);
        JsonNode json = objectMapper.readTree(body);
        assertEquals("Successfully joined room", json.path("message").asText());

        JsonNode data = json.path("data");
        assertFalse(data.path("roomId").asText().isBlank());
        assertFalse(data.path("token").asText().isBlank());
        assertEquals(playerName, data.path("playerName").asText());
        return data;
    }

    private String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
