package com.pokergame.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.exception.BadRequestException;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.security.JwtService;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.security.RateLimitService;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.util.RoomCoordinator;
import com.pokergame.service.RoomService;
import com.pokergame.util.LockLease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.pokergame.testutil.TestRoomLocks.withRoomLock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests covering HTTP security, JWT authentication, input sanitization,
 * filter chain ordering, rate limiting, and concurrent admission boundaries.
 */
@Tag("integration")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "poker.persistence.enabled=false",
                "poker.rate-limiting.enabled=true"
        })
@ActiveProfiles("test")
@DisplayName("HTTP security, rate-limit, and sanitization integration")
class HttpSecurityIT {

    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(5);
    private static final AtomicLong UNIQUE_COUNTER = new AtomicLong();

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private GameLifecycleService gameLifecycleService;

    @Autowired
    private RoomCoordinator roomCoordinator;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<String> trackedRoomIds = ConcurrentHashMap.newKeySet();
    private RestClient restClient;

    @BeforeEach
    void setUp() {
        rateLimitService.reset();
        restClient = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @AfterEach
    void tearDown() {
        for (String roomId : List.copyOf(trackedRoomIds)) {
            withRoomLock(roomCoordinator, roomId, () -> {
                gameLifecycleService.evictGame(roomId);
                roomService.destroyRoom(roomId);
                return null;
            });
        }
        trackedRoomIds.clear();
        rateLimitService.reset();
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UNIQUE_COUNTER.incrementAndGet();
    }

    private JsonNode createRoom(String roomName, String hostName, int maxPlayers) throws Exception {
        String body = restClient.post()
                .uri("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateRoomRequest(roomName, hostName, maxPlayers, 10, 20, 1000, null))
                .retrieve()
                .body(String.class);
        JsonNode data = objectMapper.readTree(body).path("data");
        String roomId = data.path("roomId").asText();
        if (!roomId.isBlank()) {
            trackedRoomIds.add(roomId);
        }
        return data;
    }

    private JsonNode joinRoom(String roomName, String playerName) throws Exception {
        String body = restClient.post()
                .uri("/api/room/join")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new JoinRoomRequest(roomName, playerName, null))
                .retrieve()
                .body(String.class);
        return objectMapper.readTree(body).path("data");
    }

    private JsonNode startGame(String roomId, String hostToken) throws Exception {
        String body = restClient.post()
                .uri("/api/room/" + roomId + "/start-game")
                .header("Authorization", "Bearer " + hostToken)
                .retrieve()
                .body(String.class);
        return objectMapper.readTree(body);
    }

    private JsonNode getRoomData(String roomId, String token) throws Exception {
        String body = restClient.get()
                .uri("/api/room/" + roomId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(String.class);
        return objectMapper.readTree(body);
    }

    private JsonNode readGameState(String gameId, String token) throws Exception {
        String body = restClient.get()
                .uri("/api/game/" + gameId + "/state")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(String.class);
        return objectMapper.readTree(body);
    }

    @Nested
    @DisplayName("Public and secured endpoints")
    class PublicAndProtectedEndpoints {

        @Test
        @DisplayName("anonymous create and join return room payloads and JWTs")
        void anonymousCreateAndJoinIssueJwt() throws Exception {
            String roomName = uniqueName("PublicHttpRoom");

            JsonNode createEnvelope = objectMapper.readTree(restClient.post()
                    .uri("/api/room/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CreateRoomRequest(roomName, "NewPlayer", 6, 10, 20, 1000, null))
                    .retrieve()
                    .body(String.class));
            String roomId = createEnvelope.path("data").path("roomId").asText();
            trackedRoomIds.add(roomId);

            assertThat(createEnvelope.path("message").asText()).isEqualTo("Room created successfully");
            assertThat(createEnvelope.path("data").path("token").asText()).isNotBlank();
            assertThat(roomId).isNotBlank();

            JsonNode joinEnvelope = objectMapper.readTree(restClient.post()
                    .uri("/api/room/join")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new JoinRoomRequest(roomName, "JoiningPlayer", null))
                    .retrieve()
                    .body(String.class));

            assertThat(joinEnvelope.path("message").asText()).isEqualTo("Successfully joined room");
            assertThat(joinEnvelope.path("data").path("token").asText()).isNotBlank();
        }

        @Test
        @DisplayName("missing or invalid bearer token returns 401 ErrorResponse JSON")
        void missingAndInvalidTokensReturn401ErrorJson() throws Exception {
            HttpClientErrorException missing = assertThrows(HttpClientErrorException.class, () ->
                    restClient.get()
                            .uri("/api/room/any-room-id")
                            .retrieve()
                            .body(String.class));
            assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            JsonNode missingBody = objectMapper.readTree(missing.getResponseBodyAsString());
            assertThat(missingBody.path("status").asInt()).isEqualTo(401);
            assertThat(missingBody.path("error").asText()).isEqualTo("Unauthorized");
            assertThat(missingBody.path("message").asText()).isEqualTo("Session expired or invalid token");

            HttpClientErrorException invalid = assertThrows(HttpClientErrorException.class, () ->
                    restClient.get()
                            .uri("/api/room/any-room-id")
                            .header("Authorization", "Bearer invalid-or-expired-token")
                            .retrieve()
                            .body(String.class));
            assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            JsonNode invalidBody = objectMapper.readTree(invalid.getResponseBodyAsString());
            assertThat(invalidBody.path("status").asInt()).isEqualTo(401);
            assertThat(invalidBody.path("error").asText()).isEqualTo("Unauthorized");
            assertThat(invalidBody.path("message").asText()).isEqualTo("Session expired or invalid token");
        }

        @Test
        @DisplayName("protected room and game endpoints reject missing bearer tokens with 401")
        void missingTokensOnProtectedEndpointsReturn401() {
            String roomId = UUID.randomUUID().toString();
            roomService.createRoom(roomId, new CreateRoomRequest(
                    uniqueName("ProtectedEndpoints"), "TestHost", 6, 10, 20, 1000, null));
            trackedRoomIds.add(roomId);

            assertThat(assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/room/" + roomId + "/leave")
                    .retrieve()
                    .body(String.class)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            assertThat(assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/room/" + roomId + "/start-game")
                    .retrieve()
                    .body(String.class)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            assertThat(assertThrows(HttpClientErrorException.class, () -> restClient.get()
                    .uri("/api/game/some-game-id/state")
                    .retrieve()
                    .body(String.class)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            assertThat(assertThrows(HttpClientErrorException.class, () -> restClient.get()
                    .uri("/api/game/some-game-id/private-state")
                    .retrieve()
                    .body(String.class)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            assertThat(assertThrows(HttpClientErrorException.class, () -> restClient.get()
                    .uri("/api/game/some-game-id/review")
                    .retrieve()
                    .body(String.class)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            assertThat(assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/game/some-game-id/claim-win")
                    .retrieve()
                    .body(String.class)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("leave room accepts valid membership JWT and rejects invalid JWT")
        void validAndInvalidTokensOnLeaveRoom() {
            String roomId = UUID.randomUUID().toString();
            roomService.createRoom(roomId, new CreateRoomRequest(
                    uniqueName("SecureLeave"), "TestHost", 6, 10, 20, 1000, null));
            trackedRoomIds.add(roomId);
            String membershipId = withRoomLock(roomCoordinator, roomId, () -> roomService.getRoom(roomId).getMembershipId("TestHost"));
            String validToken = jwtService.generateToken("TestHost", roomId, membershipId);

            HttpClientErrorException invalid = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/room/" + roomId + "/leave")
                    .header("Authorization", "Bearer invalid-token-here")
                    .retrieve()
                    .body(String.class));
            assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            String response = restClient.post()
                    .uri("/api/room/" + roomId + "/leave")
                    .header("Authorization", "Bearer " + validToken)
                    .retrieve()
                    .body(String.class);
            assertThat(response).contains("\"message\":\"Successfully left room\"");
            trackedRoomIds.remove(roomId);
        }

        @Test
        @DisplayName("start game rejects non-host token and succeeds for host with two players")
        void startGameAuthorization() throws Exception {
            String roomName = uniqueName("AuthStartRoom");
            JsonNode createData = createRoom(roomName, "ActualHost", 6);
            String roomId = createData.path("roomId").asText();
            String hostToken = createData.path("token").asText();
            String nonHostToken = joinRoom(roomName, "NonHostPlayer").path("token").asText();

            HttpClientErrorException nonHostRejected = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/room/" + roomId + "/start-game")
                    .header("Authorization", "Bearer " + nonHostToken)
                    .retrieve()
                    .body(String.class));
            assertThat(nonHostRejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            JsonNode started = startGame(roomId, hostToken);
            assertThat(started.path("message").asText()).isEqualTo("Game started successfully");
        }
    }

    @Nested
    @DisplayName("Input sanitization and lifecycle boundaries")
    class InputSanitizationAndValidation {

        @Test
        @DisplayName("HTML tags in room or player names return 400 Bad Request")
        void htmlTagsReturn400() throws Exception {
            HttpClientErrorException htmlRoom = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/room/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CreateRoomRequest("<script>alert('xss')</script>Room", "Player1", 6, 10, 20, 1000, null))
                    .retrieve()
                    .toBodilessEntity());
            assertThat(htmlRoom.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(htmlRoom.getResponseBodyAsString()).contains("Input contains illegal HTML tags.");

            HttpClientErrorException htmlCreator = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/room/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CreateRoomRequest("ValidRoom", "<b>Player</b>", 6, 10, 20, 1000, null))
                    .retrieve()
                    .toBodilessEntity());
            assertThat(htmlCreator.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

            String roomName = uniqueName("JoinSanitize");
            createRoom(roomName, "Host", 6);

            HttpClientErrorException htmlJoiner = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/room/join")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new JoinRoomRequest(roomName, "<i>Attacker</i>", null))
                    .retrieve()
                    .toBodilessEntity());
            assertThat(htmlJoiner.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("colon and whitespace in player names preserve identity safely between storage and JWT")
        void colonAndWhitespaceNamesSanitizedConsistently() throws Exception {
            String spoofedName = "Alice:anything";
            JsonNode colonData = createRoom(uniqueName("ColonRoom"), spoofedName, 6);
            assertThat(colonData.path("playerName").asText()).isEqualTo(spoofedName);
            PlayerPrincipal colonPrincipal = jwtService.extractPrincipal(colonData.path("token").asText());
            assertThat(colonPrincipal.playerName()).isEqualTo(spoofedName);
            assertThat(colonPrincipal.roomId()).isEqualTo(colonData.path("roomId").asText());

            JsonNode spaceData = createRoom(uniqueName("SpaceRoom"), "  Bob  ", 6);
            assertThat(spaceData.path("playerName").asText()).isEqualTo("Bob");
            PlayerPrincipal spacePrincipal = jwtService.extractPrincipal(spaceData.path("token").asText());
            assertThat(spacePrincipal.playerName()).isEqualTo("Bob");
        }

        @Test
        @DisplayName("late joins and duplicate start-game calls return 400 Bad Request")
        void joinAndDoubleStartAfterStartReturn400() throws Exception {
            String roomName = uniqueName("StartedBoundary");
            JsonNode host = createRoom(roomName, "Host", 6);
            String roomId = host.path("roomId").asText();
            String hostToken = host.path("token").asText();
            joinRoom(roomName, "Player2");

            startGame(roomId, hostToken);

            HttpClientErrorException lateJoin = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/room/join")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new JoinRoomRequest(roomName, "LateComer", null))
                    .retrieve()
                    .toBodilessEntity());
            assertThat(lateJoin.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(lateJoin.getResponseBodyAsString()).contains("The game has already started");

            HttpClientErrorException secondStart = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/room/" + roomId + "/start-game")
                    .header("Authorization", "Bearer " + hostToken)
                    .retrieve()
                    .toBodilessEntity());
            assertThat(secondStart.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(secondStart.getResponseBodyAsString()).contains("Game has already started");
        }
    }

    @Nested
    @DisplayName("Filter chain ordering and rate limiting")
    class FilterChainAndRateLimiting {

        @Test
        @DisplayName("oversized payload returns 413 before rate limiting or JWT validation runs")
        void oversizedPayloadRejectedWith413BeforeRateLimitAndJwt() throws Exception {
            String largeBody = "{\"roomName\":\"" + "A".repeat(11_000) + "\"}";

            // Send 5 oversized requests (equal to REST bucket capacity) with malformed JWT:
            // if payload filter runs first, all 5 return 413 (neither 401 nor consuming the rate limit bucket).
            for (int i = 0; i < 5; i++) {
                HttpClientErrorException tooLarge = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                        .uri("/api/room/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer malformed.invalid.token")
                        .body(largeBody)
                        .retrieve()
                        .toBodilessEntity());
                assertThat(tooLarge.getStatusCode().value()).isEqualTo(413);
            }

            // Bucket for /api/room/create must still have full capacity afterward.
            JsonNode created = createRoom(uniqueName("Post413Room"), "Host", 6);
            assertThat(created.path("roomId").asText()).isNotBlank();
        }

        @Test
        @DisplayName("rate limit rejection returns 429 before JWT validation runs")
        void rateLimitExhaustionReturns429BeforeJwtValidation() {
            String invalidRoomName = "A".repeat(51);

            for (int i = 0; i < 5; i++) {
                final int idx = i;
                HttpClientErrorException validationError = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                        .uri("/api/room/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new CreateRoomRequest(invalidRoomName, "Player" + idx, 6, 10, 20, 1000, null))
                        .retrieve()
                        .toBodilessEntity());
                assertThat(validationError.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            }

            // 6th request with malformed JWT must fail with 429 from EndpointRateLimitFilter before JwtAuthenticationFilter.
            HttpClientErrorException rateLimited = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/room/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer malformed.invalid.token")
                    .body(new CreateRoomRequest(invalidRoomName, "Player6", 6, 10, 20, 1000, null))
                    .retrieve()
                    .toBodilessEntity());
            assertThat(rateLimited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(rateLimited.getResponseBodyAsString()).contains("Too many requests");
        }
    }

    @Nested
    @DisplayName("Concurrent admissions and start-vs-join ordering")
    class ConcurrentAdmissionsAndOrdering {

        @Test
        @DisplayName("concurrent HTTP joins respect maxPlayers capacity without oversubscription")
        void concurrentJoinsRespectMaxPlayersCap() throws Exception {
            String roomName = uniqueName("ConcurrentCap");
            JsonNode host = createRoom(roomName, "Host", 2);
            int attempts = 5; // Within REST /api/room/join limit of 5 req/15min
            CyclicBarrier ready = new CyclicBarrier(attempts);
            List<Future<String>> outcomes = new ArrayList<>();

            try (ExecutorService executor = Executors.newFixedThreadPool(attempts)) {
                for (int i = 0; i < attempts; i++) {
                    String player = "Player" + i;
                    outcomes.add(executor.submit(() -> {
                        ready.await(5, TimeUnit.SECONDS);
                        try {
                            joinRoom(roomName, player);
                            return player;
                        } catch (HttpClientErrorException rejection) {
                            assertThat(rejection.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                            assertThat(rejection.getResponseBodyAsString()).contains("Room is full");
                            return null;
                        }
                    }));
                }

                List<String> admitted = new ArrayList<>();
                for (Future<String> outcome : outcomes) {
                    String player = outcome.get(10, TimeUnit.SECONDS);
                    if (player != null) {
                        admitted.add(player);
                    }
                }
                assertThat(admitted).hasSize(1);
                JsonNode room = getRoomData(host.path("roomId").asText(), host.path("token").asText());
                assertThat(room.path("currentPlayers").asInt()).isEqualTo(2);
                assertThat(room.path("players")).extracting(p -> p.path("name").asText())
                        .containsExactlyInAnyOrder("Host", admitted.getFirst());
            }
        }

        @Test
        @DisplayName("start ordered before queued join rejects the late join and keeps rosters coherent")
        void startOrderedBeforeQueuedJoinRejectsLateJoin() throws Exception {
            String roomName = uniqueName("StartFirstRace");
            String roomId = UUID.randomUUID().toString();
            roomService.createRoom(roomId, new CreateRoomRequest(roomName, "Host", 6, 10, 20, 1000, null));
            trackedRoomIds.add(roomId);
            roomService.joinRoom(roomId, new JoinRoomRequest(roomName, "Player2", null));

            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<Throwable> lateJoin;
                try (LockLease _ = roomCoordinator.acquire(roomId)) {
                    lateJoin = executor.submit(() -> {
                        try {
                            roomService.joinRoom(roomId, new JoinRoomRequest(roomName, "Racer", null));
                            return null;
                        } catch (Throwable failure) {
                            return failure;
                        }
                    });
                    await().atMost(ASYNC_TIMEOUT)
                            .until(() -> roomCoordinator.lockReservationCount(roomId) >= 2);
                    gameLifecycleService.createGameFromRoom(roomId);
                }

                assertThat(lateJoin.get(5, TimeUnit.SECONDS)).isInstanceOf(BadRequestException.class);
                withRoomLock(roomCoordinator, roomId, () -> {
                    Room room = roomService.getRoom(roomId);
                    Game game = gameLifecycleService.getGame(roomId);
                    assertThat(room.isGameStarted()).isTrue();
                    assertThat(game).isNotNull();
                    assertThat(room.getPlayers()).containsExactlyInAnyOrder("Host", "Player2");
                    assertThat(game.getPlayers().stream().map(Player::getName).toList())
                            .containsExactlyInAnyOrder("Host", "Player2");
                    assertThat(room.hasPlayer("Racer")).isFalse();
                    return null;
                });
            }
        }

        @Test
        @DisplayName("join ordered before queued start admits the player with a stable membership in both rosters")
        void joinOrderedBeforeQueuedStartAdmitsWithStableMembership() throws Exception {
            String roomName = uniqueName("JoinFirstRace");
            String roomId = UUID.randomUUID().toString();
            roomService.createRoom(roomId, new CreateRoomRequest(roomName, "Host", 6, 10, 20, 1000, null));
            trackedRoomIds.add(roomId);
            String hostMembership = withRoomLock(roomCoordinator, roomId, () -> roomService.getRoom(roomId).getMembershipId("Host"));

            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<Throwable> start;
                String racerMembership;
                try (LockLease _ = roomCoordinator.acquire(roomId)) {
                    start = executor.submit(() -> {
                        try {
                            gameLifecycleService.createGameFromRoom(roomId);
                            return null;
                        } catch (Throwable failure) {
                            return failure;
                        }
                    });
                    await().atMost(ASYNC_TIMEOUT)
                            .until(() -> roomCoordinator.lockReservationCount(roomId) >= 2);

                    roomService.joinRoom(roomId, new JoinRoomRequest(roomName, "Racer", null));
                    racerMembership = roomService.getRoom(roomId).getMembershipId("Racer");
                    assertThat(racerMembership).isNotBlank();
                }

                assertThat(start.get(5, TimeUnit.SECONDS)).isNull();
                withRoomLock(roomCoordinator, roomId, () -> {
                    Room room = roomService.getRoom(roomId);
                    Game game = gameLifecycleService.getGame(roomId);
                    assertThat(room.isGameStarted()).isTrue();
                    assertThat(game).isNotNull();
                    assertThat(room.getPlayers()).containsExactlyInAnyOrder("Host", "Racer");
                    assertThat(game.getPlayers().stream().map(Player::getName).toList())
                            .containsExactlyInAnyOrder("Host", "Racer");
                    assertThat(room.getMembershipId("Racer")).isEqualTo(racerMembership);
                    assertThat(room.getMembershipId("Host")).isEqualTo(hostMembership);
                    return null;
                });

                String racerToken = jwtService.generateToken("Racer", roomId, racerMembership);
                assertThat(readGameState(roomId, racerToken).path("players"))
                        .anyMatch(player -> "Racer".equals(player.path("name").asText()));
            }
        }
    }
}
