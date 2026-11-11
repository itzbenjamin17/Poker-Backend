package com.pokergame.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.exception.BadRequestException;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import com.pokergame.model.Game;
import com.pokergame.model.Room;
import com.pokergame.persistence.transaction.DurableMutationAspect;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.security.JwtService;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import com.pokergame.util.LockLease;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.pokergame.util.ConcurrentTestSupport.stopExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests security hardening integration behavior. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class SecurityHardeningIntegrationTest extends AbstractIntegrationTestSupport {

    @Autowired
    protected JwtService jwtService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private GameLifecycleService gameLifecycleService;

    @Autowired
    private DurableMutationAspect durableMutationAspect;

    /**
     * Protects the contract that the system should handle player names with colons correctly (Identity Spoofing).
     */
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

    /**
     * Protects the contract that the system should consistently sanitize names between token and storage.
     */
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

    /**
     * Protects the contract that the system should prevent joining a room after game has started.
     */
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

    /**
     * Protects the contract that the system should prevent starting a game twice.
     */
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

    /**
     * Protects the contract that the system should return 429 Too Many Requests when exceeding REST rate limit.
     */
    @Test
    @DisplayName("should return 429 Too Many Requests when exceeding REST rate limit")
    void givenHighRequestRate_whenCreateRoomRepeatedly_thenReturn429() {
        // Only this scenario needs rate limiting on; the rest of the class relies on it being off (e.g. the
        // concurrent-join test fires more than the REST bucket allows), so enable it per method via the shared
        // seam rather than reflecting on the field directly or flipping it for the whole class.
        enableRateLimiting();

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

    /**
     * Protects the contract that the system should return 413 Payload Too Large when request body exceeds limit.
     */
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
        JsonNode host = createRoom(roomName, "Host", 2);
        int attempts = 8;
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        List<Future<String>> outcomes = new ArrayList<>();

        try {
            for (int index = 0; index < attempts; index++) {
                String player = "Player" + index;
                outcomes.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
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

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
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
            assertThat(room.path("players")).extracting(player -> player.path("name").asText())
                    .containsExactlyInAnyOrder("Host", admitted.getFirst());
        } finally {
            stopExecutor(executor);
        }
    }

    /**
     * Protects the contract that a game start ordered before a late join rejects that join and leaves the
     * room and game rosters agreeing on exactly the pre-start players.
     *
     * <p>The ordering is made deterministic rather than probabilistic by holding the room lease and queuing
     * the late join behind it (the technique {@code MembershipAtomicityIntegrationTest} uses), then running
     * the start in the lease-holding thread before releasing. A started game can never admit the late joiner.
     */
    @Test
    @DisplayName("start ordered before join rejects the late join and keeps rosters coherent")
    void givenStartOrderedBeforeJoin_whenJoinQueuedBehindLease_thenJoinRejectedAndRostersAgree() throws Exception {
        String roomName = uniqueName("StartFirstRace");
        String roomId = roomService.createRoom(new CreateRoomRequest(roomName, "Host", 6, 10, 20, 1000, null));
        roomService.joinRoom(roomId, new JoinRoomRequest(roomName, "Player2", null));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Throwable> lateJoin;
        try {
            try (LockLease _ = durableMutationAspect.getRoomCoordinator().acquire(roomId)) {
                // Queue the late join behind the lease so it cannot interleave with the start.
                lateJoin = executor.submit(() -> {
                    try {
                        roomService.joinRoom(roomId, new JoinRoomRequest(roomName, "Racer", null));
                        return null;
                    } catch (Throwable failure) {
                        return failure;
                    }
                });
                await().atMost(Duration.ofSeconds(5))
                        .until(() -> durableMutationAspect.getRoomCoordinator().getLockRegistry().reservationCount(roomId) == 2);

                // Start happens first, while the join is still queued behind the lease.
                gameLifecycleService.createGameFromRoom(roomId);
            }

            // The queued join now runs against a started game and must be rejected.
            assertThat(lateJoin.get(5, TimeUnit.SECONDS)).isInstanceOf(BadRequestException.class);

            Room room = roomService.getRoom(roomId);
            Game game = gameLifecycleService.getGame(roomId);
            assertThat(room.isGameStarted()).isTrue();
            assertThat(game).isNotNull();
            // Rosters agree on exactly the two pre-start players; the racer is in neither.
            assertThat(room.getPlayers()).containsExactlyInAnyOrder("Host", "Player2");
            assertThat(gamePlayerNames(game)).containsExactlyInAnyOrder("Host", "Player2");
            assertThat(room.hasPlayer("Racer")).isFalse();
        } finally {
            stopExecutor(executor);
        }
    }

    /**
     * Protects the contract that a join ordered before the start admits that player with a single stable
     * membership that carries into the started game, with the room and game rosters agreeing.
     *
     * <p>Deterministic ordering is achieved by queuing the start behind the room lease and performing the
     * join in the lease-holding thread first, mirroring {@code MembershipAtomicityIntegrationTest}.
     */
    @Test
    @DisplayName("join ordered before start admits the player with a stable membership in both rosters")
    void givenJoinOrderedBeforeStart_whenStartQueuedBehindLease_thenPlayerAdmittedWithSameMembership() throws Exception {
        String roomName = uniqueName("JoinFirstRace");
        String roomId = roomService.createRoom(new CreateRoomRequest(roomName, "Host", 6, 10, 20, 1000, null));
        String hostMembership = roomService.getRoom(roomId).getMembershipId("Host");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Throwable> start;
        String racerMembership;
        try {
            try (LockLease _ = durableMutationAspect.getRoomCoordinator().acquire(roomId)) {
                // Queue the start behind the lease; it will find two players once the join below commits.
                start = executor.submit(() -> {
                    try {
                        gameLifecycleService.createGameFromRoom(roomId);
                        return null;
                    } catch (Throwable failure) {
                        return failure;
                    }
                });
                await().atMost(Duration.ofSeconds(5))
                        .until(() -> durableMutationAspect.getRoomCoordinator().getLockRegistry().reservationCount(roomId) == 2);

                // Join happens first, while the start is still queued behind the lease.
                roomService.joinRoom(roomId, new JoinRoomRequest(roomName, "Racer", null));
                racerMembership = roomService.getRoom(roomId).getMembershipId("Racer");
                assertThat(racerMembership).isNotBlank();
            }

            // The queued start now runs against a two-player room and must succeed.
            assertThat(start.get(5, TimeUnit.SECONDS)).isNull();

            Room room = roomService.getRoom(roomId);
            Game game = gameLifecycleService.getGame(roomId);
            assertThat(room.isGameStarted()).isTrue();
            assertThat(game).isNotNull();
            // Rosters agree on exactly the two players, including the pre-start joiner.
            assertThat(room.getPlayers()).containsExactlyInAnyOrder("Host", "Racer");
            assertThat(gamePlayerNames(game)).containsExactlyInAnyOrder("Host", "Racer");
            // The membership assigned at join is stable across the start and still validates for the game:
            // the racer's token (bound to that membership) reads game state, proving the identity carried in.
            assertThat(room.getMembershipId("Racer")).isEqualTo(racerMembership);
            assertThat(room.getMembershipId("Host")).isEqualTo(hostMembership);
            String racerToken = jwtService.generateToken("Racer", roomId, racerMembership);
            assertThat(readGameState(roomId, racerToken).path("players"))
                    .anyMatch(player -> "Racer".equals(player.path("name").asText()));
        } finally {
            stopExecutor(executor);
        }
    }

    /**
     * Collects the player names of a started game's roster.
     *
     * @param game started game to inspect
     * @return player names in the game
     */
    private static List<String> gamePlayerNames(Game game) {
        return game.getPlayers().stream().map(com.pokergame.model.Player::getName).toList();
    }
}
