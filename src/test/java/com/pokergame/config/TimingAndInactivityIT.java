package com.pokergame.config;

import static com.pokergame.testutil.TestRoomLocks.runWithRoomLock;
import static com.pokergame.testutil.TestRoomLocks.withRoomLock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.TokenResponse;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Game;
import com.pokergame.persistence.RoomAggregates;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.GameStateService;
import com.pokergame.service.PlayerActionService;
import com.pokergame.service.RoomService;
import com.pokergame.testutil.ControllableTaskScheduler;
import com.pokergame.testutil.MutableClock;
import com.pokergame.testutil.TestRoomFixtures;
import com.pokergame.util.RoomCoordinator;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Deterministic clock-driven integration tests for lobby inactivity, game inactivity abandonment,
 * and turn deadlines under {@link RoomCoordinator}. Scheduled timers never fire by themselves: each
 * test moves the {@link MutableClock} and then runs the due timers on demand.
 */
@Tag("integration")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "poker.game.ready-countdown-ms=600000")
@ActiveProfiles("test")
@Import(TimingTestConfiguration.class)
@DisplayName("Lobby inactivity, game inactivity, and turn deadline timing integration")
class TimingAndInactivityIT {

    private static final long T0 = TimingTestConfiguration.START_EPOCH_MS;
    private static final long FIFTEEN_MINUTES_MS = 15 * 60_000L;
    private static final long THIRTY_MINUTES_MS = 30 * 60_000L;
    private static final AtomicLong UNIQUE_COUNTER = new AtomicLong();

    @LocalServerPort
    private int port;

    @Autowired
    private RoomAggregates roomAggregates;

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
    private WebSocketEventListener webSocketEventListener;

    @Autowired
    private GameTimingProperties gameTimingProperties;

    @Autowired
    private MutableClock clock;

    @Autowired
    private ControllableTaskScheduler taskScheduler;

    @Autowired
    private ObjectMapper objectMapper;

    private final Set<String> trackedRoomIds = ConcurrentHashMap.newKeySet();
    private TestRoomFixtures roomFixtures;
    private RestClient restClient;

    @BeforeEach
    void setUp() {
        clock.setMillis(T0);
        taskScheduler.clearTrackedTasks();
        roomFixtures = new TestRoomFixtures(rooms, roomCoordinator, webSocketEventListener, trackedRoomIds);
        restClient = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @AfterEach
    void tearDown() {
        for (String roomId : List.copyOf(trackedRoomIds)) {
            try {
                runWithRoomLock(roomCoordinator, roomId, () -> {
                    rooms.destroyRoom(roomId);
                    roomAggregates.remove(roomId);
                });
            } catch (RuntimeException ignored) {
                // Best-effort per-room cleanup so one failure does not leak later rooms.
            }
        }
        trackedRoomIds.clear();
        // Clear after the rooms are gone: destroying a room cancels its timers and queues cleanup work.
        taskScheduler.clearTrackedTasks();
    }

    @Nested
    @DisplayName("Lobby inactivity deadlines")
    class LobbyInactivityTests {

        @Test
        @DisplayName("creation publishes 30-minute deadline and the server time")
        void creationPublishesThirtyMinuteDeadlineAndServerTime() {
            TokenResponse created = createRoom(uniqueName("IdleLobby"), "Host", 6);

            JsonNode room = getRoom(created.roomId(), created.token());

            assertThat(room.path("inactivityDeadlineEpochMs").asLong()).isEqualTo(T0 + THIRTY_MINUTES_MS);
            assertThat(room.path("serverTimeEpochMs").asLong()).isEqualTo(T0);
        }

        @Test
        @DisplayName("accepted join resets lobby inactivity deadline")
        void acceptedJoinResetsDeadline() {
            String roomName = uniqueName("JoinReset");
            TokenResponse created = createRoom(roomName, "Host", 6);

            long joinTime = T0 + 10 * 60_000L;
            clock.setMillis(joinTime);
            joinRoom(roomName, "Guest");

            JsonNode room = getRoom(created.roomId(), created.token());
            assertThat(room.path("inactivityDeadlineEpochMs").asLong()).isEqualTo(joinTime + THIRTY_MINUTES_MS);
            assertThat(room.path("serverTimeEpochMs").asLong()).isEqualTo(joinTime);
        }

        @Test
        @DisplayName("accepted player leave resets lobby inactivity deadline")
        void acceptedPlayerLeaveResetsDeadline() {
            String roomName = uniqueName("LeaveReset");
            TokenResponse created = createRoom(roomName, "Host", 6);

            clock.setMillis(T0 + 5 * 60_000L);
            TokenResponse guest = joinRoom(roomName, "Guest");

            long leaveTime = T0 + 15 * 60_000L;
            clock.setMillis(leaveTime);
            leaveRoom(created.roomId(), guest.token());

            JsonNode room = getRoom(created.roomId(), created.token());
            assertThat(room.path("inactivityDeadlineEpochMs").asLong()).isEqualTo(leaveTime + THIRTY_MINUTES_MS);
            assertThat(room.path("serverTimeEpochMs").asLong()).isEqualTo(leaveTime);
        }

        @Test
        @DisplayName("host leave closes lobby immediately")
        void hostLeaveClosesLobbyImmediately() {
            TokenResponse created = createRoom(uniqueName("HostLeave"), "Host", 6);

            leaveRoom(created.roomId(), created.token());

            assertThatThrownBy(() -> getRoom(created.roomId(), created.token()))
                    .isInstanceOf(HttpClientErrorException.NotFound.class);
        }

        @Test
        @DisplayName("failed join does not reset lobby inactivity deadline")
        void failedJoinDoesNotResetDeadline() {
            String roomName = uniqueName("FailedJoin");
            TokenResponse created = createRoom(roomName, "Host", 6);

            clock.setMillis(T0 + 10 * 60_000L);
            assertThatThrownBy(() -> joinRoom(roomName, "Host"))
                    .isInstanceOf(HttpClientErrorException.BadRequest.class);

            JsonNode room = getRoom(created.roomId(), created.token());
            assertThat(room.path("inactivityDeadlineEpochMs").asLong()).isEqualTo(T0 + THIRTY_MINUTES_MS);
        }

        @Test
        @DisplayName("lobby timer closes the lobby at the exact deadline and not one millisecond earlier")
        void exactExpiryClosesLobbyAndReturnsNotFound() {
            TokenResponse created = createRoom(uniqueName("ExpireLobby"), "Host", 6);
            String roomId = created.roomId();
            // The host has connected, so the first-connection grace timer that falls due on the way is a no-op.
            lifecycle.markPlayerReconnected(roomId, "Host");

            clock.setMillis(T0 + THIRTY_MINUTES_MS - 1L);
            taskScheduler.triggerDueTasks(clock.instant());
            assertThat(roomExists(roomId)).isTrue();

            clock.setMillis(T0 + THIRTY_MINUTES_MS);
            // Expired, but nothing has touched the lobby or fired its timer yet.
            assertThat(roomExists(roomId)).isTrue();
            assertThat(taskScheduler.triggerDueTasks(clock.instant())).isPositive();

            assertThat(roomExists(roomId)).isFalse();
            assertThatThrownBy(() -> getRoom(roomId, created.token()))
                    .isInstanceOf(HttpClientErrorException.NotFound.class);
        }

        @Test
        @DisplayName("delayed scheduler does not revive expired lobby on late join")
        void delayedSchedulerDoesNotReviveExpiredLobbyOnJoin() {
            String roomName = uniqueName("LateJoinRevive");
            TokenResponse created = createRoom(roomName, "Host", 6);

            clock.setMillis(T0 + THIRTY_MINUTES_MS + 5_000L);

            assertThatThrownBy(() -> joinRoom(roomName, "LateGuest"))
                    .isInstanceOf(HttpClientErrorException.BadRequest.class);
            assertThatThrownBy(() -> getRoom(created.roomId(), created.token()))
                    .isInstanceOf(HttpClientErrorException.NotFound.class);
        }

        @Test
        @DisplayName("delayed scheduler does not revive expired lobby on late start")
        void delayedSchedulerDoesNotReviveExpiredLobbyOnStart() {
            String roomName = uniqueName("LateStartRevive");
            TokenResponse created = createRoom(roomName, "Host", 6);

            long joinTime = T0 + 60_000L;
            clock.setMillis(joinTime);
            joinRoom(roomName, "Guest");

            clock.setMillis(joinTime + THIRTY_MINUTES_MS + 5_000L);

            assertThatThrownBy(() -> startGame(created.roomId(), created.token()))
                    .isInstanceOf(HttpClientErrorException.NotFound.class);
            assertThatThrownBy(() -> getRoom(created.roomId(), created.token()))
                    .isInstanceOf(HttpClientErrorException.NotFound.class);
        }

        @Test
        @DisplayName("delayed scheduler does not revive expired lobby on late leave")
        void delayedSchedulerDoesNotReviveExpiredLobbyOnLeave() {
            String roomName = uniqueName("LateLeaveRevive");
            TokenResponse created = createRoom(roomName, "Host", 6);

            clock.setMillis(T0 + THIRTY_MINUTES_MS + 5_000L);

            assertThatThrownBy(() -> leaveRoom(created.roomId(), created.token()))
                    .isInstanceOf(HttpClientErrorException.NotFound.class);
            assertThatThrownBy(() -> getRoom(created.roomId(), created.token()))
                    .isInstanceOf(HttpClientErrorException.NotFound.class);
        }

        @Test
        @DisplayName("game start retires the 30-minute lobby deadline so its timer cannot close the started game")
        void gameStartRetiresLobbyInactivityDeadline() {
            String roomName = uniqueName("StartRetiresLobby");
            TokenResponse created = createRoom(roomName, "Host", 6);
            String roomId = created.roomId();
            joinRoom(roomName, "Guest");
            Instant lobbyDeadline = Instant.ofEpochMilli(T0 + THIRTY_MINUTES_MS);
            assertThat(taskScheduler.pendingStartTimes()).contains(lobbyDeadline);

            // Start one half minute before the lobby deadline. The turn timer (60s) and game inactivity timer
            // (15 minutes) both fall after T0 + 30 minutes, so only the lobby timer is due at that moment.
            long startTime = T0 + THIRTY_MINUTES_MS - 30_000L;
            clock.setMillis(startTime);
            startGame(roomId, created.token());
            lifecycle.markPlayerReconnected(roomId, "Host");
            lifecycle.markPlayerReconnected(roomId, "Guest");
            assertThat(taskScheduler.pendingStartTimes())
                    .as("starting the game cancels the lobby timer")
                    .doesNotContain(lobbyDeadline);

            clock.setMillis(T0 + THIRTY_MINUTES_MS);
            taskScheduler.triggerDueTasks(clock.instant());

            JsonNode room = getRoom(roomId, created.token());
            assertThat(room.path("gameStarted").asBoolean()).isTrue();
            assertThat(room.path("inactivityDeadlineEpochMs").asLong()).isEqualTo(startTime + FIFTEEN_MINUTES_MS);
            assertThat(roomExists(roomId)).isTrue();
            assertThat(withRoomLock(roomCoordinator, roomId, () -> lifecycle.getGame(roomId).isGameOver())).isFalse();
        }
    }

    @Nested
    @DisplayName("Game inactivity abandonment")
    class GameInactivityTests {

        @Test
        @DisplayName("starting game replaces lobby deadline with 15-minute game deadline")
        void startingGameReplacesLobbyDeadlineWithFifteenMinuteGameDeadline() {
            String name = uniqueName("GameInactivityStart");
            TokenResponse host = createRoom(name, "Host", 4);
            joinRoom(name, "Guest");

            long startTime = clock.millis() + 5 * 60_000L;
            clock.setMillis(startTime);
            startGame(host.roomId(), host.token());

            runWithRoomLock(roomCoordinator, host.roomId(), () -> {
                assertThat(rooms.getRoom(host.roomId()).getInactivityDeadlineEpochMs())
                        .isEqualTo(startTime + FIFTEEN_MINUTES_MS);
                var snapshot = states.getPublicGameStateSnapshot(lifecycle.getGame(host.roomId()));
                assertThat(snapshot.inactivityDeadlineEpochMs()).isEqualTo(startTime + FIFTEEN_MINUTES_MS);
            });
        }

        @Test
        @DisplayName("deliberate poker action resets game inactivity deadline")
        void deliberatePokerActionResetsInactivityDeadline() {
            String roomId = createStartedAndConnectedGame("ActionReset", "Guest");

            String actor = currentPlayerName(roomId);
            long actionTime = clock.millis() + 30_000L;
            clock.setMillis(actionTime);

            PlayerAction legalAction = withRoomLock(roomCoordinator, roomId, () -> {
                Game game = lifecycle.getGame(roomId);
                return game.getLegalActionsFor(game.getCurrentPlayer()).contains(PlayerAction.CHECK)
                        ? PlayerAction.CHECK
                        : PlayerAction.CALL;
            });
            playerActionService.processPlayerAction(roomId, new PlayerActionRequest(legalAction, 0), actor);

            runWithRoomLock(roomCoordinator, roomId, () -> {
                assertThat(rooms.getRoom(roomId).getInactivityDeadlineEpochMs())
                        .isEqualTo(actionTime + FIFTEEN_MINUTES_MS);
                assertThat(states.getPublicGameStateSnapshot(lifecycle.getGame(roomId)).inactivityDeadlineEpochMs())
                        .isEqualTo(actionTime + FIFTEEN_MINUTES_MS);
            });
        }

        @Test
        @DisplayName("readiness changes by an eligible player reset inactivity deadline")
        void readinessChangeByEligiblePlayerResetsInactivityDeadline() {
            String roomId = createStartedAndConnectedGame("ReadyReset", "Guest", "Guest2");

            reachShowdownReadyGateByFolding(roomId);
            clock.advanceByMillis(5 * 60_000L);

            long markReadyTime = clock.millis() + 2 * 60_000L;
            clock.setMillis(markReadyTime);
            lifecycle.markPlayerReadyForNextHand(roomId, "Host");
            assertThat(inactivityDeadlineEpochMs(roomId)).isEqualTo(markReadyTime + FIFTEEN_MINUTES_MS);

            long unmarkReadyTime = clock.millis() + 2 * 60_000L;
            clock.setMillis(unmarkReadyTime);
            lifecycle.unmarkPlayerReadyForNextHand(roomId, "Host");
            assertThat(inactivityDeadlineEpochMs(roomId)).isEqualTo(unmarkReadyTime + FIFTEEN_MINUTES_MS);
        }

        @Test
        @DisplayName("duplicate readiness command does not reset inactivity deadline")
        void duplicateReadinessCommandDoesNotResetDeadline() {
            String roomId = createStartedAndConnectedGame("DupReady", "Guest", "Guest2");

            reachShowdownReadyGateByFolding(roomId);

            clock.advanceByMillis(60_000L);
            lifecycle.markPlayerReadyForNextHand(roomId, "Host");
            long deadline1 = inactivityDeadlineEpochMs(roomId);

            clock.advanceByMillis(3 * 60_000L);
            lifecycle.markPlayerReadyForNextHand(roomId, "Host");
            long deadline2 = inactivityDeadlineEpochMs(roomId);

            assertThat(deadline2).isEqualTo(deadline1);
        }

        @Test
        @DisplayName("late activity at the inactivity deadline is rejected and the game is abandoned when timers fire")
        void lateActivityAtOrAfterInactivityDeadlineIsRejectedAndAbandonsGame() {
            String roomId = createStartedAndConnectedGame("LateActivity", "Guest");
            String actor = currentPlayerName(roomId);
            long initialDeadline = inactivityDeadlineEpochMs(roomId);

            clock.setMillis(initialDeadline);

            assertThatThrownBy(() ->
                    playerActionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.FOLD, 0), actor))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("inactivity");
            assertThat(isGameOver(roomId)).isFalse();

            assertThat(taskScheduler.triggerDueTasks(clock.instant())).isPositive();

            assertThat(isGameOver(roomId)).isTrue();
            PublicGameStateResponse review = reviewAs(roomId, actor);
            assertThat(review.gameEnd()).isNotNull();
            assertThat(review.gameEnd().abandoned()).isTrue();
            assertThat(review.gameEnd().reason()).isEqualTo("INACTIVITY");
            assertThat(review.gameEnd().winner()).isNull();
        }

        @Test
        @DisplayName("inactivity abandonment takes precedence over a delayed turn timeout callback")
        void inactivityAbandonmentTakesPrecedenceOverTurnTimeout() {
            String roomId = createStartedAndConnectedGame("Precedence", "Guest");
            clock.setMillis(inactivityDeadlineEpochMs(roomId) + 5_000L);

            long[] timerMeta = withRoomLock(roomCoordinator, roomId, () -> {
                Game game = lifecycle.getGame(roomId);
                return new long[]{
                        game.getScheduledTaskTime(ScheduledGameTask.TURN_TIMEOUT),
                        game.getHandNumber(),
                        game.getTimerVersion(ScheduledGameTask.TURN_TIMEOUT)
                };
            });

            lifecycle.executeScheduledTask(roomId, ScheduledGameTask.TURN_TIMEOUT, timerMeta[0], timerMeta[1], timerMeta[2]);

            assertThat(isGameOver(roomId)).isTrue();
            PublicGameStateResponse review = reviewAs(roomId, "Host");
            assertThat(review.gameEnd()).isNotNull();
            assertThat(review.gameEnd().abandoned()).isTrue();
            assertThat(review.gameEnd().reason()).isEqualTo("INACTIVITY");
            assertThat(review.gameEnd().winner()).isNull();
        }

        @Test
        @DisplayName("claim-win is rejected once inactivity expired, and the game is abandoned when timers fire")
        void claimWinRejectedWhenInactivityExpired() {
            String roomId = createStartedAndConnectedGame("ClaimExpired", "Guest");
            runWithRoomLock(roomCoordinator, roomId,
                    () -> lifecycle.getGame(roomId).findPlayerByName("Guest").setDisconnected(true));

            long inactivityDeadline = inactivityDeadlineEpochMs(roomId);
            clock.setMillis(inactivityDeadline);

            assertThatThrownBy(() -> lifecycle.claimWin(roomId, "Host"))
                    .isInstanceOf(BadRequestException.class);
            assertThat(isGameOver(roomId)).isFalse();

            assertThat(taskScheduler.triggerDueTasks(clock.instant())).isPositive();

            assertThat(isGameOver(roomId)).isTrue();
            PublicGameStateResponse review = reviewAs(roomId, "Host");
            assertThat(review.gameEnd()).isNotNull();
            assertThat(review.gameEnd().abandoned()).isTrue();
            assertThat(review.gameEnd().winner()).isNull();
        }

        @Test
        @DisplayName("abandonment keeps completed payouts, leaves the current pot unawarded, hides cards, and schedules cleanup")
        void abandonmentPreservesCompletedPayoutsLeavesPotUnawardedHidesCards() {
            String roomId = createStartedAndConnectedGame("AbandonmentPreservation", "Guest");

            // Hand 1: the player to act folds, so the other player wins the blinds (10/20 blinds, 1,000 buy-ins).
            reachShowdownReadyGateByFolding(roomId);
            Map<String, Integer> stacksAfterHandOne = chipsByPlayer(roomId);
            assertThat(stacksAfterHandOne).containsOnlyKeys("Host", "Guest");
            assertThat(stacksAfterHandOne.values()).containsExactlyInAnyOrder(990, 1010);

            // Hand 2: both players ready up, the next hand is dealt, and its blinds are in the pot.
            lifecycle.markPlayerReadyForNextHand(roomId, "Host");
            lifecycle.markPlayerReadyForNextHand(roomId, "Guest");
            PublicGameStateResponse handTwo = publicState(roomId);
            assertThat(handTwo.handNumber()).isEqualTo(2);
            assertThat(handTwo.pot()).isEqualTo(30);
            Map<String, Integer> stacksBeforeAbandon = chipsByPlayer(roomId);
            assertThat(stacksBeforeAbandon.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(2000 - 30);
            for (PublicGameStateResponse.PlayerState player : handTwo.players()) {
                assertThat(player.chips() + player.currentBet()).isEqualTo(stacksAfterHandOne.get(player.name()));
            }

            clock.setMillis(inactivityDeadlineEpochMs(roomId));
            assertThat(taskScheduler.triggerDueTasks(clock.instant())).isPositive();

            PublicGameStateResponse review = reviewAs(roomId, "Host");
            assertThat(isGameOver(roomId)).isTrue();
            assertThat(review.gameEnd()).isNotNull();
            assertThat(review.gameEnd().abandoned()).isTrue();
            assertThat(review.gameEnd().reason()).isEqualTo("INACTIVITY");
            assertThat(review.gameEnd().winner()).isNull();
            assertThat(review.pot()).isEqualTo(30);
            assertThat(review.winners()).isEmpty();
            Map<String, Integer> finalStacks = new LinkedHashMap<>();
            for (PublicGameStateResponse.PlayerState player : review.players()) {
                finalStacks.put(player.name(), player.chips());
                assertThat(player.holeCards()).isNull();
                assertThat(player.handRank()).isNull();
                assertThat(player.isWinner()).isFalse();
            }
            assertThat(finalStacks).isEqualTo(stacksBeforeAbandon);

            Long cleanupDeadline = withRoomLock(roomCoordinator, roomId, () ->
                    lifecycle.getGame(roomId).getScheduledTaskTime(ScheduledGameTask.CLEANUP));
            assertThat(cleanupDeadline).isEqualTo(clock.millis() + gameTimingProperties.gameEndDisplayDelayMs());
        }
    }

    @Nested
    @DisplayName("Turn deadlines")
    class TurnDeadlineTests {

        @Test
        @DisplayName("starting a hand publishes one minute for the current decision")
        void startingAHandPublishesOneMinuteForCurrentDecision() {
            String roomName = uniqueName("TurnDeadline");
            TokenResponse host = createRoom(roomName, "Host", 4);
            joinRoom(roomName, "Guest");
            startGame(host.roomId(), host.token());

            assertThat(turnDeadlineEpochMs(host.roomId())).isEqualTo(clock.millis() + 60_000);
        }

        @Test
        @DisplayName("turn expiry removes the actor and awards normal heads-up victory")
        void expiryRemovesActorAndAwardsHeadsUpVictory() {
            String roomId = createStartedAndConnectedGame("TurnRemoval", "Guest");
            String actor = currentPlayerName(roomId);
            long deadline = turnDeadlineEpochMs(roomId);

            clock.setMillis(deadline);
            assertThat(taskScheduler.triggerDueTasks(clock.instant())).isPositive();

            String survivor = "Host".equals(actor) ? "Guest" : "Host";
            runWithRoomLock(roomCoordinator, roomId, () -> {
                assertThat(rooms.getRoom(roomId).hasPlayer(actor)).isFalse();
                assertThat(lifecycle.getGame(roomId).isGameOver()).isTrue();
                assertThat(lifecycle.getGameReview(roomId, survivor).gameEnd().winner()).isEqualTo(survivor);
            });
        }

        @Test
        @DisplayName("action just before expiry is accepted and resets deadline for next actor")
        void actionJustBeforeExpiryIsAcceptedAndResetsDeadline() {
            String roomId = createStartedAndConnectedGame("TurnTimely", "Guest1", "Guest2");

            String actor1 = currentPlayerName(roomId);
            long deadline1 = turnDeadlineEpochMs(roomId);

            clock.setMillis(deadline1 - 1);
            playerActionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.CALL, 0), actor1);

            assertThat(currentPlayerName(roomId)).isNotEqualTo(actor1);
            assertThat(turnDeadlineEpochMs(roomId)).isEqualTo(clock.millis() + 60_000);
        }

        @Test
        @DisplayName("action at or after turn expiry is rejected")
        void actionAtOrAfterExpiryIsRejected() {
            String roomId = createStartedAndConnectedGame("TurnLate", "Guest");

            String actor = currentPlayerName(roomId);
            long deadline = turnDeadlineEpochMs(roomId);

            clock.setMillis(deadline);
            assertThatThrownBy(() ->
                    playerActionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.CALL, 0), actor))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Turn deadline expired");

            clock.setMillis(deadline + 1_000L);
            assertThatThrownBy(() ->
                    playerActionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.CALL, 0), actor))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Turn deadline expired");
        }

        @Test
        @DisplayName("multi-contender timeout removes actor, preserves pot, and sets fresh deadline")
        void multiContenderTimeoutRemovesActorAndContinuesPlay() {
            String roomId = createStartedAndConnectedGame("TurnMulti", "Guest1", "Guest2");

            String actor = currentPlayerName(roomId);
            int potBefore = withRoomLock(roomCoordinator, roomId, () -> lifecycle.getGame(roomId).getPot());
            long deadline = turnDeadlineEpochMs(roomId);

            clock.setMillis(deadline);
            assertThat(taskScheduler.triggerDueTasks(clock.instant())).isPositive();

            runWithRoomLock(roomCoordinator, roomId, () -> {
                Game game = lifecycle.getGame(roomId);
                assertThat(rooms.getRoom(roomId).hasPlayer(actor)).isFalse();
                assertThat(game.isGameOver()).isFalse();
                assertThat(game.getActivePlayers()).hasSize(2);
                assertThat(game.getPot()).isEqualTo(potBefore);
                var nextActor = game.getCurrentPlayer();
                assertThat(nextActor).isNotNull();
                assertThat(nextActor.getName()).isNotEqualTo(actor);
                assertThat(states.getPublicGameStateSnapshot(game).turnDeadlineEpochMs())
                        .isEqualTo(clock.millis() + 60_000);
            });
        }

        @Test
        @DisplayName("stale or duplicate turn callbacks are ignored")
        void staleOrDuplicateCallbacksAreIgnored() {
            String roomId = createStartedAndConnectedGame("TurnStale", "Guest1", "Guest2");

            record TurnMeta(String actor, long deadline, long hand, long timerVersion) { }
            TurnMeta stale = withRoomLock(roomCoordinator, roomId, () -> {
                Game game = lifecycle.getGame(roomId);
                return new TurnMeta(
                        game.getCurrentPlayer().getName(),
                        states.getPublicGameStateSnapshot(game).turnDeadlineEpochMs(),
                        game.getHandNumber(),
                        game.getTimerVersion(ScheduledGameTask.TURN_TIMEOUT));
            });

            clock.advanceByMillis(5_000L);
            playerActionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.CALL, 0), stale.actor());

            String actor2 = currentPlayerName(roomId);
            assertThat(actor2).isNotEqualTo(stale.actor());

            clock.setMillis(stale.deadline());
            lifecycle.executeScheduledTask(
                    roomId, ScheduledGameTask.TURN_TIMEOUT, stale.deadline(), stale.hand(), stale.timerVersion());

            runWithRoomLock(roomCoordinator, roomId, () -> {
                assertThat(rooms.getRoom(roomId).hasPlayer(actor2)).isTrue();
                assertThat(lifecycle.getGame(roomId).getCurrentPlayer().getName()).isEqualTo(actor2);
            });
        }

        @Test
        @DisplayName("raise returning turn to earlier actor grants a fresh 60-second allowance")
        void raiseReturningToPlayerReceivesFreshSixtySeconds() {
            String roomId = createStartedAndConnectedGame("TurnRaise", "Guest");

            String firstActor = currentPlayerName(roomId);
            clock.advanceByMillis(20_000L);
            playerActionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.CALL, 0), firstActor);

            String secondActor = currentPlayerName(roomId);
            clock.advanceByMillis(15_000L);
            playerActionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.RAISE, 40), secondActor);

            assertThat(currentPlayerName(roomId)).isEqualTo(firstActor);
            assertThat(turnDeadlineEpochMs(roomId)).isEqualTo(clock.millis() + 60_000);
        }

        @Test
        @DisplayName("no turn deadline is published during showdown")
        void noTurnDeadlineDuringShowdown() {
            String roomId = createStartedAndConnectedGame("TurnShowdown", "Guest");
            String firstActor = currentPlayerName(roomId);
            playerActionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.FOLD, 0), firstActor);

            var state = withRoomLock(roomCoordinator, roomId,
                    () -> states.getPublicGameStateSnapshot(lifecycle.getGame(roomId)));
            assertThat(state.turnDeadlineEpochMs()).isNull();
        }
    }

    @Nested
    @DisplayName("Disconnect grace after game over")
    class DisconnectGraceAfterGameOverTests {

        @Test
        @DisplayName("player disconnected before game over stays a room member after grace expires and can fetch GET /review")
        void disconnectedPlayerRemainsRoomMemberAfterGraceExpiresPostGameOverAndGetsReview() {
            String roomName = uniqueName("DisconnectReview");
            TokenResponse host = createRoom(roomName, "Host", 4);
            TokenResponse guest = joinRoom(roomName, "Guest");
            String roomId = host.roomId();

            startGame(roomId, host.token());
            lifecycle.markPlayerReconnected(roomId, "Host");
            lifecycle.markPlayerReconnected(roomId, "Guest");

            long gracePeriodMs = 100L;
            assertThat(gracePeriodMs).isLessThan(gameTimingProperties.gameEndDisplayDelayMs());
            long graceDeadline = clock.millis() + gracePeriodMs;

            // Guest drops: the game records the disconnect and the listener schedules the grace-expiry cleanup.
            lifecycle.markPlayerDisconnected(roomId, "Guest", graceDeadline);
            webSocketEventListener.scheduleRecoveredDisconnect(roomId, "Guest", graceDeadline);

            lifecycle.abandonGameDueToInactivity(roomId);
            long cleanupDeadline = withRoomLock(roomCoordinator, roomId, () ->
                    lifecycle.getGame(roomId).getScheduledTaskTime(ScheduledGameTask.CLEANUP));
            assertThat(graceDeadline).isLessThan(cleanupDeadline);

            clock.setMillis(graceDeadline);
            // Only the grace-expiry cleanup is due; the game's own cleanup is scheduled for later.
            assertThat(taskScheduler.triggerDueTasks(clock.instant())).isPositive();

            boolean guestStillInRoom = withRoomLock(roomCoordinator, roomId, () ->
                    rooms.getRoom(roomId).hasPlayer("Guest"));
            assertThat(guestStillInRoom).isTrue();

            var response = restClient.get()
                    .uri("/api/game/" + roomId + "/review")
                    .headers(headers -> headers.setBearerAuth(guest.token()))
                    .retrieve()
                    .toEntity(PublicGameStateResponse.class);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().gameEnd()).isNotNull();
            assertThat(response.getBody().gameEnd().abandoned()).isTrue();
            assertThat(response.getBody().gameEnd().reason()).isEqualTo("INACTIVITY");
        }
    }

    @Nested
    @DisplayName("Turn timer retention")
    class TurnTimerRetentionTests {

        @Test
        @DisplayName("invalid CHECK when a call is owed rejects the action without cancelling the turn timer")
        void invalidCheckKeepsTurnTimerScheduledAndTimesOutActor() {
            String roomId = roomFixtures.createAndConnectRoom("Timer Retain Room", "Host", "Alice", "Bob");
            lifecycle.createGameFromRoom(roomId);

            String actor = currentPlayerName(roomId);
            Long turnDeadlineBefore = withRoomLock(roomCoordinator, roomId, () ->
                    lifecycle.getGame(roomId).getScheduledTaskTime(ScheduledGameTask.TURN_TIMEOUT));
            assertThat(turnDeadlineBefore).isNotNull();

            assertThatThrownBy(() -> playerActionService.processPlayerAction(
                    roomId, new PlayerActionRequest(PlayerAction.CHECK, 0), actor))
                    .isInstanceOf(UnauthorisedActionException.class)
                    .satisfies(ex -> assertThat(((UnauthorisedActionException) ex).actionErrorCode())
                            .isEqualTo("CHECK_NOT_AVAILABLE"));

            runWithRoomLock(roomCoordinator, roomId, () -> {
                Game game = lifecycle.getGame(roomId);
                assertThat(game.getCurrentPlayer().getName()).isEqualTo(actor);
                assertThat(game.getScheduledTaskTime(ScheduledGameTask.TURN_TIMEOUT)).isEqualTo(turnDeadlineBefore);
            });

            clock.setMillis(turnDeadlineBefore);
            assertThat(taskScheduler.triggerDueTasks(clock.instant())).isPositive();

            runWithRoomLock(roomCoordinator, roomId, () -> {
                Game game = lifecycle.getGame(roomId);
                assertThat(rooms.getRoom(roomId).hasPlayer(actor)).isFalse();
                assertThat(game.findPlayerByName(actor)).isNull();
                assertThat(game.getCurrentPlayer()).isNotNull();
                assertThat(game.getCurrentPlayer().getName()).isNotEqualTo(actor);
            });
        }
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UNIQUE_COUNTER.incrementAndGet();
    }

    private TokenResponse createRoom(String roomName, String hostName, int maxPlayers) {
        TokenResponse created = restClient.post()
                .uri("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateRoomRequest(roomName, hostName, maxPlayers, 10, 20, 1000, null))
                .retrieve()
                .body(TokenResponse.class);
        assertThat(created).isNotNull();
        trackedRoomIds.add(created.roomId());
        return created;
    }

    private TokenResponse joinRoom(String roomName, String playerName) {
        TokenResponse joined = restClient.post()
                .uri("/api/room/join")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new JoinRoomRequest(roomName, playerName, null))
                .retrieve()
                .body(TokenResponse.class);
        assertThat(joined).isNotNull();
        return joined;
    }

    private JsonNode getRoom(String roomId, String token) {
        String body = restClient.get()
                .uri("/api/room/" + roomId)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(String.class);
        return objectMapper.readTree(body);
    }

    private void leaveRoom(String roomId, String token) {
        restClient.post()
                .uri("/api/room/" + roomId + "/leave")
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .toBodilessEntity();
    }

    private void startGame(String roomId, String hostToken) {
        restClient.post()
                .uri("/api/room/" + roomId + "/start-game")
                .headers(headers -> headers.setBearerAuth(hostToken))
                .retrieve()
                .toBodilessEntity();
    }

    private String createStartedAndConnectedGame(String prefix, String... guests) {
        String name = uniqueName(prefix);
        TokenResponse host = createRoom(name, "Host", 4);
        for (String guest : guests) {
            joinRoom(name, guest);
        }
        startGame(host.roomId(), host.token());
        lifecycle.markPlayerReconnected(host.roomId(), "Host");
        for (String guest : guests) {
            lifecycle.markPlayerReconnected(host.roomId(), guest);
        }
        return host.roomId();
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
            playerActionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.FOLD, 0), actor);
        }
        throw new IllegalStateException("Ready gate did not open after folding");
    }

    private boolean roomExists(String roomId) {
        return withRoomLock(roomCoordinator, roomId, () -> rooms.getRoom(roomId) != null);
    }

    private boolean isGameOver(String roomId) {
        return withRoomLock(roomCoordinator, roomId, () -> lifecycle.getGame(roomId).isGameOver());
    }

    private String currentPlayerName(String roomId) {
        return withRoomLock(roomCoordinator, roomId, () -> lifecycle.getGame(roomId).getCurrentPlayer().getName());
    }

    private long inactivityDeadlineEpochMs(String roomId) {
        return withRoomLock(roomCoordinator, roomId, () -> rooms.getRoom(roomId).getInactivityDeadlineEpochMs());
    }

    private Long turnDeadlineEpochMs(String roomId) {
        return publicState(roomId).turnDeadlineEpochMs();
    }

    private PublicGameStateResponse publicState(String roomId) {
        return withRoomLock(roomCoordinator, roomId, () -> states.getPublicGameStateSnapshot(lifecycle.getGame(roomId)));
    }

    private Map<String, Integer> chipsByPlayer(String roomId) {
        Map<String, Integer> chips = new LinkedHashMap<>();
        for (PublicGameStateResponse.PlayerState player : publicState(roomId).players()) {
            chips.put(player.name(), player.chips());
        }
        return chips;
    }

    private PublicGameStateResponse reviewAs(String roomId, String playerName) {
        return withRoomLock(roomCoordinator, roomId, () -> lifecycle.getGameReview(roomId, playerName));
    }
}
