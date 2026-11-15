package com.pokergame.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.BadRequestException;
import com.pokergame.model.Game;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.GameStateService;
import com.pokergame.service.PlayerActionService;
import com.pokergame.service.RoomCoordinator;
import com.pokergame.service.RoomService;
import com.pokergame.testutil.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static com.pokergame.testutil.TestRoomLocks.withRoomLock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic clock-driven integration tests for lobby inactivity, game inactivity abandonment,
 * and turn deadlines under {@link RoomCoordinator}.
 */
@Tag("integration")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "poker.persistence.enabled=false",
                "poker.rate-limiting.enabled=false",
                "poker.lifecycle.round-end-display-delay-ms=0",
                "poker.lifecycle.ready-countdown-ms=600000"
        })
@ActiveProfiles("test")
@Import(TimingAndInactivityIT.TimeConfiguration.class)
@DisplayName("Lobby inactivity, game inactivity, and turn deadline timing integration")
class TimingAndInactivityIT {

    private static final long T0 = 1_900_000_000_000L;
    private static final long FIFTEEN_MINUTES_MS = 15 * 60_000L;
    private static final long THIRTY_MINUTES_MS = 30 * 60_000L;
    private static final AtomicLong UNIQUE_COUNTER = new AtomicLong();

    @LocalServerPort
    private int port;

    @Autowired
    private GameLifecycleService lifecycle;

    @Autowired
    private GameStateService states;

    @Autowired
    private RoomService rooms;

    @Autowired
    private PlayerActionService playerActionService;

    @Autowired
    private RoomCoordinator roomCoordinator;

    @Autowired
    private LifecycleTimingProperties lifecycleTimingProperties;

    @Autowired
    private MutableClock clock;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<String> trackedRoomIds = ConcurrentHashMap.newKeySet();
    private RestClient restClient;

    @BeforeEach
    void setUp() {
        clock.setMillis(T0);
        restClient = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @AfterEach
    void tearDown() {
        for (String roomId : List.copyOf(trackedRoomIds)) {
            withRoomLock(roomCoordinator, roomId, () -> {
                lifecycle.evictGame(roomId);
                rooms.destroyRoom(roomId);
                return null;
            });
        }
        trackedRoomIds.clear();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean
        @Primary
        MutableClock timingTestClock() {
            return new MutableClock(T0);
        }
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

    private JsonNode getRoom(String roomId, String token) throws Exception {
        String body = restClient.get()
                .uri("/api/room/" + roomId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(String.class);
        return objectMapper.readTree(body);
    }

    private void leaveRoom(String roomId, String token) {
        restClient.post()
                .uri("/api/room/" + roomId + "/leave")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .toBodilessEntity();
    }

    private void startGame(String roomId, String hostToken) {
        restClient.post()
                .uri("/api/room/" + roomId + "/start-game")
                .header("Authorization", "Bearer " + hostToken)
                .retrieve()
                .toBodilessEntity();
    }

    private String createStartedAndConnectedGame(String prefix, String... guests) throws Exception {
        String name = uniqueName(prefix);
        JsonNode host = createRoom(name, "Host", 4);
        for (String guest : guests) {
            joinRoom(name, guest);
        }
        String roomId = host.path("roomId").asText();
        startGame(roomId, host.path("token").asText());
        lifecycle.markPlayerReconnected(roomId, "Host");
        for (String guest : guests) {
            lifecycle.markPlayerReconnected(roomId, guest);
        }
        return roomId;
    }

    private void reachShowdownReadyGateByFolding(String roomId) {
        for (int i = 0; i < 4; i++) {
            String actor = withRoomLock(roomCoordinator, roomId, () -> {
                Game game = lifecycle.getGame(roomId);
                return Boolean.TRUE.equals(states.getPublicGameStateSnapshot(game).isReadyCountdownActive())
                        ? null
                        : game.getCurrentPlayer().getName();
            });
            if (actor == null) {
                return;
            }
            playerActionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.FOLD, null), actor);
        }
        throw new IllegalStateException("Ready gate did not open after folding");
    }

    @Nested
    @DisplayName("Lobby inactivity deadlines")
    class LobbyInactivityTests {

        @Test
        @DisplayName("creation publishes 30-minute deadline and authoritative serverTimeEpochMs")
        void creationPublishesThirtyMinuteDeadlineAndServerTime() throws Exception {
            JsonNode created = createRoom(uniqueName("IdleLobby"), "Host", 6);
            JsonNode room = getRoom(created.path("roomId").asText(), created.path("token").asText());
            assertThat(room.path("inactivityDeadlineEpochMs").asLong()).isEqualTo(T0 + THIRTY_MINUTES_MS);
            assertThat(room.path("serverTimeEpochMs").asLong()).isEqualTo(T0);
        }

        @Test
        @DisplayName("accepted join resets lobby inactivity deadline")
        void acceptedJoinResetsDeadline() throws Exception {
            String roomName = uniqueName("JoinReset");
            JsonNode created = createRoom(roomName, "Host", 6);
            String roomId = created.path("roomId").asText();
            String hostToken = created.path("token").asText();

            long joinTime = T0 + 10 * 60_000L;
            clock.setMillis(joinTime);
            joinRoom(roomName, "Guest");

            JsonNode room = getRoom(roomId, hostToken);
            assertThat(room.path("inactivityDeadlineEpochMs").asLong()).isEqualTo(joinTime + THIRTY_MINUTES_MS);
            assertThat(room.path("serverTimeEpochMs").asLong()).isEqualTo(joinTime);
        }

        @Test
        @DisplayName("accepted player leave resets lobby inactivity deadline")
        void acceptedPlayerLeaveResetsDeadline() throws Exception {
            String roomName = uniqueName("LeaveReset");
            JsonNode created = createRoom(roomName, "Host", 6);
            String roomId = created.path("roomId").asText();
            String hostToken = created.path("token").asText();

            clock.setMillis(T0 + 5 * 60_000L);
            String guestToken = joinRoom(roomName, "Guest").path("token").asText();

            long leaveTime = T0 + 15 * 60_000L;
            clock.setMillis(leaveTime);
            leaveRoom(roomId, guestToken);

            JsonNode room = getRoom(roomId, hostToken);
            assertThat(room.path("inactivityDeadlineEpochMs").asLong()).isEqualTo(leaveTime + THIRTY_MINUTES_MS);
            assertThat(room.path("serverTimeEpochMs").asLong()).isEqualTo(leaveTime);
        }

        @Test
        @DisplayName("host leave closes lobby immediately")
        void hostLeaveClosesLobbyImmediately() throws Exception {
            JsonNode created = createRoom(uniqueName("HostLeave"), "Host", 6);
            String roomId = created.path("roomId").asText();
            String hostToken = created.path("token").asText();

            leaveRoom(roomId, hostToken);

            assertThatThrownBy(() -> getRoom(roomId, hostToken))
                    .isInstanceOf(HttpClientErrorException.NotFound.class);
        }

        @Test
        @DisplayName("failed join does not reset lobby inactivity deadline")
        void failedJoinDoesNotResetDeadline() throws Exception {
            String roomName = uniqueName("FailedJoin");
            JsonNode created = createRoom(roomName, "Host", 6);
            String roomId = created.path("roomId").asText();
            String hostToken = created.path("token").asText();

            clock.setMillis(T0 + 10 * 60_000L);
            assertThatThrownBy(() -> joinRoom(roomName, "Host"))
                    .isInstanceOf(HttpClientErrorException.BadRequest.class);

            JsonNode room = getRoom(roomId, hostToken);
            assertThat(room.path("inactivityDeadlineEpochMs").asLong()).isEqualTo(T0 + THIRTY_MINUTES_MS);
        }

        @Test
        @DisplayName("exact expiry closes lobby and returns 404 NotFound")
        void exactExpiryClosesLobbyAndReturnsNotFound() throws Exception {
            JsonNode created = createRoom(uniqueName("ExpireLobby"), "Host", 6);
            String roomId = created.path("roomId").asText();
            String hostToken = created.path("token").asText();

            clock.setMillis(T0 + THIRTY_MINUTES_MS + 1L);

            assertThatThrownBy(() -> getRoom(roomId, hostToken))
                    .isInstanceOf(HttpClientErrorException.NotFound.class);
        }

        @Test
        @DisplayName("delayed scheduler does not revive expired lobby on late join")
        void delayedSchedulerDoesNotReviveExpiredLobbyOnJoin() throws Exception {
            String roomName = uniqueName("LateJoinRevive");
            JsonNode created = createRoom(roomName, "Host", 6);
            String roomId = created.path("roomId").asText();
            String hostToken = created.path("token").asText();

            clock.setMillis(T0 + THIRTY_MINUTES_MS + 5_000L);

            assertThatThrownBy(() -> joinRoom(roomName, "LateGuest"))
                    .isInstanceOf(HttpClientErrorException.class);
            assertThatThrownBy(() -> getRoom(roomId, hostToken))
                    .isInstanceOf(HttpClientErrorException.NotFound.class);
        }

        @Test
        @DisplayName("delayed scheduler does not revive expired lobby on late start")
        void delayedSchedulerDoesNotReviveExpiredLobbyOnStart() throws Exception {
            String roomName = uniqueName("LateStartRevive");
            JsonNode created = createRoom(roomName, "Host", 6);
            String roomId = created.path("roomId").asText();
            String hostToken = created.path("token").asText();

            long joinTime = T0 + 60_000L;
            clock.setMillis(joinTime);
            joinRoom(roomName, "Guest");

            clock.setMillis(joinTime + THIRTY_MINUTES_MS + 5_000L);

            assertThatThrownBy(() -> startGame(roomId, hostToken))
                    .isInstanceOf(HttpClientErrorException.class);
            assertThatThrownBy(() -> getRoom(roomId, hostToken))
                    .isInstanceOf(HttpClientErrorException.NotFound.class);
        }

        @Test
        @DisplayName("game start retires 30-minute lobby deadline in favor of 15-minute game deadline")
        void gameStartRetiresLobbyInactivityDeadline() throws Exception {
            String roomName = uniqueName("StartRetiresLobby");
            JsonNode created = createRoom(roomName, "Host", 6);
            String roomId = created.path("roomId").asText();
            String hostToken = created.path("token").asText();
            joinRoom(roomName, "Guest");

            clock.setMillis(T0 + 2 * 60_000L);
            startGame(roomId, hostToken);

            clock.setMillis(T0 + THIRTY_MINUTES_MS + 60_000L);
            JsonNode room = getRoom(roomId, hostToken);
            assertThat(room.path("gameStarted").asBoolean()).isTrue();
            assertThat(room.path("inactivityDeadlineEpochMs").asLong())
                    .isEqualTo(T0 + 2 * 60_000L + FIFTEEN_MINUTES_MS);
        }
    }

    @Nested
    @DisplayName("Game inactivity abandonment")
    class GameInactivityTests {

        @Test
        @DisplayName("starting game replaces lobby deadline with 15-minute game deadline")
        void startingGameReplacesLobbyDeadlineWithFifteenMinuteGameDeadline() throws Exception {
            String name = uniqueName("GameInactivityStart");
            JsonNode host = createRoom(name, "Host", 4);
            joinRoom(name, "Guest");
            String roomId = host.path("roomId").asText();
            String hostToken = host.path("token").asText();

            long startTime = clock.millis() + 5 * 60_000L;
            clock.setMillis(startTime);
            startGame(roomId, hostToken);

            withRoomLock(roomCoordinator, roomId, () -> {
                assertThat(rooms.getRoom(roomId).getInactivityDeadlineEpochMs())
                        .isEqualTo(startTime + FIFTEEN_MINUTES_MS);
                var snapshot = states.getPublicGameStateSnapshot(lifecycle.getGame(roomId));
                assertThat(snapshot.inactivityDeadlineEpochMs()).isEqualTo(startTime + FIFTEEN_MINUTES_MS);
                return null;
            });
        }

        @Test
        @DisplayName("deliberate poker action resets game inactivity deadline")
        void deliberatePokerActionResetsInactivityDeadline() throws Exception {
            String roomId = createStartedAndConnectedGame("ActionReset", "Guest");

            String actor = withRoomLock(roomCoordinator, roomId, () -> lifecycle.getGame(roomId).getCurrentPlayer().getName());
            long actionTime = clock.millis() + 30_000L;
            clock.setMillis(actionTime);

            PlayerAction legalAction = withRoomLock(roomCoordinator, roomId, () -> {
                Game game = lifecycle.getGame(roomId);
                return game.getLegalActionsFor(game.getCurrentPlayer()).contains(PlayerAction.CHECK)
                        ? PlayerAction.CHECK
                        : PlayerAction.CALL;
            });
            playerActionService.processPlayerAction(roomId, new PlayerActionRequest(legalAction, 0), actor);

            withRoomLock(roomCoordinator, roomId, () -> {
                assertThat(rooms.getRoom(roomId).getInactivityDeadlineEpochMs())
                        .isEqualTo(actionTime + FIFTEEN_MINUTES_MS);
                assertThat(states.getPublicGameStateSnapshot(lifecycle.getGame(roomId)).inactivityDeadlineEpochMs())
                        .isEqualTo(actionTime + FIFTEEN_MINUTES_MS);
                return null;
            });
        }

        @Test
        @DisplayName("readiness changes by an eligible player reset inactivity deadline")
        void readinessChangeByEligiblePlayerResetsInactivityDeadline() throws Exception {
            String roomId = createStartedAndConnectedGame("ReadyReset", "Guest", "Guest2");

            reachShowdownReadyGateByFolding(roomId);
            clock.advanceByMillis(5 * 60_000L);

            long markReadyTime = clock.millis() + 2 * 60_000L;
            clock.setMillis(markReadyTime);
            lifecycle.markPlayerReadyForNextHand(roomId, "Host");
            withRoomLock(roomCoordinator, roomId, () -> {
                assertThat(rooms.getRoom(roomId).getInactivityDeadlineEpochMs())
                        .isEqualTo(markReadyTime + FIFTEEN_MINUTES_MS);
                return null;
            });

            long unmarkReadyTime = clock.millis() + 2 * 60_000L;
            clock.setMillis(unmarkReadyTime);
            lifecycle.unmarkPlayerReadyForNextHand(roomId, "Host");
            withRoomLock(roomCoordinator, roomId, () -> {
                assertThat(rooms.getRoom(roomId).getInactivityDeadlineEpochMs())
                        .isEqualTo(unmarkReadyTime + FIFTEEN_MINUTES_MS);
                return null;
            });
        }

        @Test
        @DisplayName("duplicate readiness command does not reset inactivity deadline")
        void duplicateReadinessCommandDoesNotResetDeadline() throws Exception {
            String roomId = createStartedAndConnectedGame("DupReady", "Guest", "Guest2");

            reachShowdownReadyGateByFolding(roomId);

            clock.advanceByMillis(60_000L);
            lifecycle.markPlayerReadyForNextHand(roomId, "Host");
            long deadline1 = withRoomLock(roomCoordinator, roomId, () -> rooms.getRoom(roomId).getInactivityDeadlineEpochMs());

            clock.advanceByMillis(3 * 60_000L);
            lifecycle.markPlayerReadyForNextHand(roomId, "Host");
            long deadline2 = withRoomLock(roomCoordinator, roomId, () -> rooms.getRoom(roomId).getInactivityDeadlineEpochMs());

            assertThat(deadline2).isEqualTo(deadline1);
        }

        @Test
        @DisplayName("late activity at or after inactivity deadline is rejected without abandoning until timer fires")
        void lateActivityAtOrAfterInactivityDeadlineIsRejectedAndAbandonsGame() throws Exception {
            String roomId = createStartedAndConnectedGame("LateActivity", "Guest");
            String actor = withRoomLock(roomCoordinator, roomId, () -> lifecycle.getGame(roomId).getCurrentPlayer().getName());
            long initialDeadline = withRoomLock(roomCoordinator, roomId, () -> rooms.getRoom(roomId).getInactivityDeadlineEpochMs());

            clock.setMillis(initialDeadline);

            assertThatThrownBy(() ->
                    playerActionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.FOLD, 0), actor))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("inactivity");

            withRoomLock(roomCoordinator, roomId, () -> {
                assertThat(lifecycle.getGame(roomId).isGameOver()).isFalse();
                return null;
            });

            lifecycle.abandonGameIfInactive(roomId, initialDeadline);

            withRoomLock(roomCoordinator, roomId, () -> {
                assertThat(lifecycle.getGame(roomId).isGameOver()).isTrue();
                return null;
            });
            GameEndResponse review = lifecycle.getGameReview(roomId, actor);
            assertThat(review.abandoned()).isTrue();
            assertThat(review.reason()).isEqualTo("INACTIVITY");
            assertThat(review.winner()).isNull();
        }

        @Test
        @DisplayName("inactivity abandonment takes precedence over turn timeout")
        void inactivityAbandonmentTakesPrecedenceOverTurnTimeout() throws Exception {
            String roomId = createStartedAndConnectedGame("Precedence", "Guest");
            long inactivityDeadline = withRoomLock(roomCoordinator, roomId, () -> rooms.getRoom(roomId).getInactivityDeadlineEpochMs());
            clock.setMillis(inactivityDeadline + 5_000L);

            long[] timerMeta = withRoomLock(roomCoordinator, roomId, () -> {
                Game game = lifecycle.getGame(roomId);
                return new long[]{
                        game.getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT),
                        game.getHandNumber(),
                        game.getScheduledTaskGeneration(ScheduledGameTask.TURN_TIMEOUT)
                };
            });

            lifecycle.executeScheduledTask(roomId, ScheduledGameTask.TURN_TIMEOUT, timerMeta[0], timerMeta[1], timerMeta[2]);

            withRoomLock(roomCoordinator, roomId, () -> {
                assertThat(lifecycle.getGame(roomId).isGameOver()).isTrue();
                return null;
            });
            GameEndResponse review = lifecycle.getGameReview(roomId, "Host");
            assertThat(review.abandoned()).isTrue();
            assertThat(review.reason()).isEqualTo("INACTIVITY");
            assertThat(review.winner()).isNull();
        }

        @Test
        @DisplayName("claim-win rejected when inactivity expired without abandoning until timer fires")
        void claimWinRejectedWhenInactivityExpired() throws Exception {
            String roomId = createStartedAndConnectedGame("ClaimExpired", "Guest");
            withRoomLock(roomCoordinator, roomId, () -> {
                lifecycle.getGame(roomId).findPlayerByName("Guest").setDisconnected(true);
                return null;
            });

            long inactivityDeadline = withRoomLock(roomCoordinator, roomId, () -> rooms.getRoom(roomId).getInactivityDeadlineEpochMs());
            clock.setMillis(inactivityDeadline);

            assertThatThrownBy(() -> lifecycle.claimWin(roomId, "Host"))
                    .isInstanceOf(BadRequestException.class);

            withRoomLock(roomCoordinator, roomId, () -> {
                assertThat(lifecycle.getGame(roomId).isGameOver()).isFalse();
                return null;
            });

            lifecycle.abandonGameIfInactive(roomId, inactivityDeadline);

            withRoomLock(roomCoordinator, roomId, () -> {
                assertThat(lifecycle.getGame(roomId).isGameOver()).isTrue();
                return null;
            });
            GameEndResponse review = lifecycle.getGameReview(roomId, "Host");
            assertThat(review.abandoned()).isTrue();
            assertThat(review.winner()).isNull();
        }

        @Test
        @DisplayName("abandonment preserves completed payouts, leaves pot unawarded, hides cards, and schedules cleanup")
        void abandonmentPreservesCompletedPayoutsLeavesPotUnawardedHidesCards() throws Exception {
            String roomId = createStartedAndConnectedGame("AbandonmentPreservation", "Guest");
            int potBefore = withRoomLock(roomCoordinator, roomId, () -> lifecycle.getGame(roomId).getPot());
            assertThat(potBefore).isGreaterThan(0);

            lifecycle.abandonGameDueToInactivity(roomId);

            withRoomLock(roomCoordinator, roomId, () -> {
                assertThat(lifecycle.getGame(roomId).isGameOver()).isTrue();
                return null;
            });
            GameEndResponse review = lifecycle.getGameReview(roomId, "Host");
            assertThat(review.abandoned()).isTrue();
            assertThat(review.reason()).isEqualTo("INACTIVITY");
            assertThat(review.winner()).isNull();
            assertThat(review.finalState().pot()).isEqualTo(potBefore);
            assertThat(review.finalState().winners()).isEmpty();
            for (var p : review.finalState().players()) {
                assertThat(p.holeCards()).isNull();
                assertThat(p.handRank()).isNull();
                assertThat(p.isWinner()).isFalse();
            }

            Long cleanupDeadline = withRoomLock(roomCoordinator, roomId, () ->
                    lifecycle.getGame(roomId).getScheduledTaskDeadline(ScheduledGameTask.CLEANUP));
            assertThat(cleanupDeadline).isEqualTo(clock.millis() + lifecycleTimingProperties.gameEndDisplayDelayMs());
        }
    }

    @Nested
    @DisplayName("Turn deadlines")
    class TurnDeadlineTests {

        @Test
        @DisplayName("starting a hand publishes one minute for the current decision")
        void startingAHandPublishesOneMinuteForCurrentDecision() throws Exception {
            String roomName = uniqueName("TurnDeadline");
            var host = createRoom(roomName, "Host", 4);
            joinRoom(roomName, "Guest");
            long before = clock.millis();
            String roomId = host.path("roomId").asText();
            startGame(roomId, host.path("token").asText());

            var state = withRoomLock(roomCoordinator, roomId, () -> states.getPublicGameStateSnapshot(lifecycle.getGame(roomId)));
            assertThat(state.turnDeadlineEpochMs()).isBetween(before + 60_000, clock.millis() + 60_000);
        }

        @Test
        @DisplayName("turn expiry removes the actor and awards normal heads-up victory")
        void expiryRemovesActorAndAwardsHeadsUpVictory() throws Exception {
            String roomId = createStartedAndConnectedGame("TurnRemoval", "Guest");

            record TurnMeta(String actor, long deadline, long hand, long generation) {}
            TurnMeta meta = withRoomLock(roomCoordinator, roomId, () -> {
                Game game = lifecycle.getGame(roomId);
                return new TurnMeta(
                        game.getCurrentPlayer().getName(),
                        states.getPublicGameStateSnapshot(game).turnDeadlineEpochMs(),
                        game.getHandNumber(),
                        game.getScheduledTaskGeneration(ScheduledGameTask.TURN_TIMEOUT));
            });

            clock.setMillis(meta.deadline());
            lifecycle.executeScheduledTask(
                    roomId, ScheduledGameTask.TURN_TIMEOUT, meta.deadline(), meta.hand(), meta.generation());

            withRoomLock(roomCoordinator, roomId, () -> {
                assertThat(rooms.getRoom(roomId).hasPlayer(meta.actor())).isFalse();
                assertThat(lifecycle.getGame(roomId).isGameOver()).isTrue();
                return null;
            });
            String survivor = "Host".equals(meta.actor()) ? "Guest" : "Host";
            assertThat(lifecycle.getGameReview(roomId, survivor).winner()).isEqualTo(survivor);
        }

        @Test
        @DisplayName("action just before expiry is accepted and resets deadline for next actor")
        void actionJustBeforeExpiryIsAcceptedAndResetsDeadline() throws Exception {
            String roomId = createStartedAndConnectedGame("TurnTimely", "Guest1", "Guest2");

            String actor1 = withRoomLock(roomCoordinator, roomId, () -> lifecycle.getGame(roomId).getCurrentPlayer().getName());
            long deadline1 = withRoomLock(roomCoordinator, roomId, () ->
                    states.getPublicGameStateSnapshot(lifecycle.getGame(roomId)).turnDeadlineEpochMs());

            clock.setMillis(deadline1 - 1);
            playerActionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.CALL, null), actor1);

            withRoomLock(roomCoordinator, roomId, () -> {
                Game game = lifecycle.getGame(roomId);
                String nextActor = game.getCurrentPlayer().getName();
                assertThat(nextActor).isNotEqualTo(actor1);
                long deadline2 = states.getPublicGameStateSnapshot(game).turnDeadlineEpochMs();
                assertThat(deadline2).isEqualTo(clock.millis() + 60_000);
                return null;
            });
        }

        @Test
        @DisplayName("action at or after turn expiry is rejected")
        void actionAtOrAfterExpiryIsRejected() throws Exception {
            String roomId = createStartedAndConnectedGame("TurnLate", "Guest");

            String actor = withRoomLock(roomCoordinator, roomId, () -> lifecycle.getGame(roomId).getCurrentPlayer().getName());
            long deadline = withRoomLock(roomCoordinator, roomId, () ->
                    states.getPublicGameStateSnapshot(lifecycle.getGame(roomId)).turnDeadlineEpochMs());

            clock.setMillis(deadline);
            assertThatThrownBy(() ->
                    playerActionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.CALL, null), actor))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Turn deadline expired");

            clock.setMillis(deadline + 1_000L);
            assertThatThrownBy(() ->
                    playerActionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.CALL, null), actor))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Turn deadline expired");
        }

        @Test
        @DisplayName("multi-contender timeout removes actor, preserves pot, and sets fresh deadline")
        void multiContenderTimeoutRemovesActorAndContinuesPlay() throws Exception {
            String roomId = createStartedAndConnectedGame("TurnMulti", "Guest1", "Guest2");

            record TurnMeta(String actor, int pot, long deadline, long hand, long generation) {}
            TurnMeta meta = withRoomLock(roomCoordinator, roomId, () -> {
                Game game = lifecycle.getGame(roomId);
                return new TurnMeta(
                        game.getCurrentPlayer().getName(),
                        game.getPot(),
                        states.getPublicGameStateSnapshot(game).turnDeadlineEpochMs(),
                        game.getHandNumber(),
                        game.getScheduledTaskGeneration(ScheduledGameTask.TURN_TIMEOUT));
            });

            clock.setMillis(meta.deadline());
            lifecycle.executeScheduledTask(
                    roomId, ScheduledGameTask.TURN_TIMEOUT, meta.deadline(), meta.hand(), meta.generation());

            withRoomLock(roomCoordinator, roomId, () -> {
                Game game = lifecycle.getGame(roomId);
                assertThat(rooms.getRoom(roomId).hasPlayer(meta.actor())).isFalse();
                assertThat(game.isGameOver()).isFalse();
                assertThat(game.getActivePlayers()).hasSize(2);
                assertThat(game.getPot()).isEqualTo(meta.pot());
                var nextActor = game.getCurrentPlayer();
                assertThat(nextActor).isNotNull();
                assertThat(nextActor.getName()).isNotEqualTo(meta.actor());
                assertThat(states.getPublicGameStateSnapshot(game).turnDeadlineEpochMs())
                        .isEqualTo(clock.millis() + 60_000);
                return null;
            });
        }

        @Test
        @DisplayName("stale or duplicate turn callbacks are ignored")
        void staleOrDuplicateCallbacksAreIgnored() throws Exception {
            String roomId = createStartedAndConnectedGame("TurnStale", "Guest1", "Guest2");

            record TurnMeta(String actor, long deadline, long hand, long generation) {}
            TurnMeta stale = withRoomLock(roomCoordinator, roomId, () -> {
                Game game = lifecycle.getGame(roomId);
                return new TurnMeta(
                        game.getCurrentPlayer().getName(),
                        states.getPublicGameStateSnapshot(game).turnDeadlineEpochMs(),
                        game.getHandNumber(),
                        game.getScheduledTaskGeneration(ScheduledGameTask.TURN_TIMEOUT));
            });

            clock.advanceByMillis(5_000L);
            playerActionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.CALL, null), stale.actor());

            String actor2 = withRoomLock(roomCoordinator, roomId, () -> lifecycle.getGame(roomId).getCurrentPlayer().getName());
            assertThat(actor2).isNotEqualTo(stale.actor());

            clock.setMillis(stale.deadline());
            lifecycle.executeScheduledTask(
                    roomId, ScheduledGameTask.TURN_TIMEOUT, stale.deadline(), stale.hand(), stale.generation());

            withRoomLock(roomCoordinator, roomId, () -> {
                assertThat(rooms.getRoom(roomId).hasPlayer(actor2)).isTrue();
                assertThat(lifecycle.getGame(roomId).getCurrentPlayer().getName()).isEqualTo(actor2);
                return null;
            });
        }

        @Test
        @DisplayName("raise returning turn to earlier actor grants a fresh 60-second allowance")
        void raiseReturningToPlayerReceivesFreshSixtySeconds() throws Exception {
            String roomId = createStartedAndConnectedGame("TurnRaise", "Guest");

            String firstActor = withRoomLock(roomCoordinator, roomId, () -> lifecycle.getGame(roomId).getCurrentPlayer().getName());
            clock.advanceByMillis(20_000L);
            playerActionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.CALL, null), firstActor);

            String secondActor = withRoomLock(roomCoordinator, roomId, () -> lifecycle.getGame(roomId).getCurrentPlayer().getName());
            clock.advanceByMillis(15_000L);
            playerActionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.RAISE, 40), secondActor);

            withRoomLock(roomCoordinator, roomId, () -> {
                Game game = lifecycle.getGame(roomId);
                assertThat(game.getCurrentPlayer().getName()).isEqualTo(firstActor);
                assertThat(states.getPublicGameStateSnapshot(game).turnDeadlineEpochMs())
                        .isEqualTo(clock.millis() + 60_000);
                return null;
            });
        }

        @Test
        @DisplayName("no turn deadline is published during showdown")
        void noTurnDeadlineDuringShowdown() throws Exception {
            String roomId = createStartedAndConnectedGame("TurnShowdown", "Guest");
            String firstActor = withRoomLock(roomCoordinator, roomId, () -> lifecycle.getGame(roomId).getCurrentPlayer().getName());
            playerActionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.FOLD, null), firstActor);

            var state = withRoomLock(roomCoordinator, roomId, () -> states.getPublicGameStateSnapshot(lifecycle.getGame(roomId)));
            assertThat(state.turnDeadlineEpochMs()).isNull();
        }
    }
}
