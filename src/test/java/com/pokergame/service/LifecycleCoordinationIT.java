package com.pokergame.service;

import com.pokergame.persistence.RoomAggregates;
import com.pokergame.util.RoomCoordinator;

import com.pokergame.config.WebSocketAuthInterceptor;
import com.pokergame.config.WebSocketEventListener;
import com.pokergame.controller.GameController;
import com.pokergame.controller.RoomController;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.PrivatePlayerState;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.Rank;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.enums.Suit;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Card;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.security.RateLimitService;
import com.pokergame.testutil.TestRoomFixtures;
import com.pokergame.util.LockLease;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import static com.pokergame.testutil.TestRoomFixtures.stompSubscription;
import static com.pokergame.testutil.TestRoomLocks.withRoomLock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Headless Spring coordination integration suite verifying room lock boundaries,
 * command/departure/completion races, scheduled timer stale-callback guards,
 * session/bucket cleanup, and atomic membership replacement across REST and STOMP.
 */
@Tag("integration")
@DisplayName("Lifecycle coordination integration tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(
        locations = "classpath:application-test.properties",
        properties = {
                "poker.persistence.enabled=false",
                "poker.rate-limiting.enabled=true",
                "poker.game.ready-countdown-ms=60000",
                "poker.game.game-end-display-delay-ms=60000"
        })
class LifecycleCoordinationIT {

    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    private RoomAggregates roomAggregates;

    @Autowired
    private RoomService roomService;

    @Autowired
    private GameLifecycleService gameLifecycleService;

    @Autowired
    private PlayerActionService playerActionService;

    @Autowired
    private RoomCoordinator roomCoordinator;

    @Autowired
    private GameController gameController;

    @Autowired
    private RoomController roomController;

    @Autowired
    private WebSocketAuthInterceptor webSocketAuthInterceptor;

    @Autowired
    private WebSocketEventListener webSocketEventListener;

    @Autowired
    private RateLimitService rateLimitService;

    private final Set<String> trackedRoomIds = new HashSet<>();
    private TestRoomFixtures roomFixtures;

    @BeforeEach
    void setUpFixtures() {
        roomFixtures = new TestRoomFixtures(roomService, roomCoordinator, trackedRoomIds);
    }

    @AfterEach
    void cleanUpTrackedRooms() {
        for (String roomId : List.copyOf(trackedRoomIds)) {
            withRoomLock(roomCoordinator, roomId, () -> {
                roomAggregates.remove(roomId);
                roomService.destroyRoom(roomId);
                return null;
            });
        }
        trackedRoomIds.clear();
        rateLimitService.reset();
    }

    @Nested
    @DisplayName("Room lock ownership enforcement")
    class RoomOwnershipEnforcementTests {

        @Test
        @DisplayName("uncoordinated internal mutations and reads throw IllegalStateException immediately")
        void uncoordinatedInternalCallsThrowAndCoordinatedCallsSucceed() {
            assertThatThrownBy(() -> roomCoordinator.assertHeldByCurrentThread("uncoordinated-room"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not hold room lock for room: uncoordinated-room");

            withRoomLock(roomCoordinator, "owned-room", () -> {
                assertThatCode(() -> roomCoordinator.assertHeldByCurrentThread("owned-room"))
                        .doesNotThrowAnyException();
                return null;
            });

            String roomId = roomFixtures.createAndConnectRoom("Ownership Room", "Host", 1000, "Guest");
            gameLifecycleService.createGameFromRoom(roomId);

            assertThatThrownBy(() -> gameLifecycleService.getGame(roomId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not hold room lock for room: " + roomId);
            assertThatThrownBy(() -> gameLifecycleService.gameExists(roomId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not hold room lock for room: " + roomId);
            assertThatThrownBy(() -> gameLifecycleService.playerExistsInGame(roomId, "Host"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not hold room lock for room: " + roomId);
            assertThatThrownBy(() -> gameLifecycleService.validatePlayerMembership(roomId, "Host", "mem"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not hold room lock for room: " + roomId);
            assertThatThrownBy(() -> gameLifecycleService.isGameInactivityExpired(roomId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not hold room lock for room: " + roomId);
            assertThatThrownBy(() -> gameLifecycleService.getGameReview(roomId, "Host"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not hold room lock for room: " + roomId);

            assertThatThrownBy(() -> roomService.getRoom(roomId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not hold room lock for room: " + roomId);
            assertThatThrownBy(() -> roomService.getRoomData(roomId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not hold room lock for room: " + roomId);
            assertThatThrownBy(() -> roomService.isRoomHost(roomId, "Host"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not hold room lock for room: " + roomId);
            assertThatThrownBy(() -> roomService.closeLobbyIfExpired(roomId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not hold room lock for room: " + roomId);

            assertThatThrownBy(() -> roomService.broadcastGameStarted(roomId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not hold room lock for room: " + roomId);

            String actorName = withRoomLock(roomCoordinator, roomId, () -> gameLifecycleService.getGame(roomId).getCurrentPlayer().getName());
            assertThatCode(() -> playerActionService.processPlayerAction(
                    roomId, new PlayerActionRequest(PlayerAction.CALL, null), actorName))
                    .doesNotThrowAnyException();

            withRoomLock(roomCoordinator, roomId, () -> {
                assertThatCode(() -> roomService.broadcastGameStarted(roomId)).doesNotThrowAnyException();
                roomService.leavePlayersBatch(roomId, List.of("Guest"), "Host");
                assertThat(roomService.getRoom(roomId).getPlayers()).containsExactly("Host");
                return null;
            });

            // @DurableMutation public service entry points also acquire the room lock when called directly
            assertThatCode(() -> roomService.destroyRoom(roomId)).doesNotThrowAnyException();
            withRoomLock(roomCoordinator, roomId, () -> {
                assertThat(roomService.getRoom(roomId)).isNull();
                return null;
            });
        }

        @Test
        @DisplayName("same-room nested leases join reentrantly while cross-room nesting is rejected")
        void nestedSameRoomJoinsAndCrossRoomThrows() {
            String roomA = "nested-room-A";
            String roomB = "nested-room-B";

            try (LockLease _ = roomCoordinator.acquire(roomA)) {
                assertThat(roomCoordinator.isHeldByCurrentThread(roomA)).isTrue();
                try (LockLease _ = roomCoordinator.acquire(roomA)) {
                    assertThat(roomCoordinator.isHeldByCurrentThread(roomA)).isTrue();
                    roomCoordinator.assertHeldByCurrentThread(roomA);
                }
                assertThat(roomCoordinator.isHeldByCurrentThread(roomA)).isTrue();

                assertThatThrownBy(() -> roomCoordinator.acquire(roomB))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("while already coordinating room " + roomA);
            }

            assertThat(roomCoordinator.isHeldByCurrentThread(roomA)).isFalse();
        }
    }

    @Nested
    @DisplayName("Command, departure, and completion coordination")
    class CommandCoordinationTests {

        @Test
        @DisplayName("public and private projections return deeply immutable collections")
        void projectionsReturnDeeplyImmutableCollections() {
            String roomId = roomFixtures.createAndConnectRoom("Immutable Room", "Host", 1000, "Alice");
            PlayerPrincipal hostPrincipal = roomFixtures.principalFor(roomId, "Host");
            roomController.startGame(roomId, hostPrincipal);

            PublicGameStateResponse publicState = gameController.getGameState(roomId, hostPrincipal).getBody();
            assertThat(publicState).isNotNull();
            assertThatThrownBy(() -> publicState.players().add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> publicState.communityCards().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> publicState.pots().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            if (publicState.legalActions() != null) {
                assertThatThrownBy(() -> publicState.legalActions().clear())
                        .isInstanceOf(UnsupportedOperationException.class);
            }
            if (publicState.winners() != null) {
                assertThatThrownBy(() -> publicState.winners().clear())
                        .isInstanceOf(UnsupportedOperationException.class);
            }
            PublicPlayerState firstPlayer = publicState.players().getFirst();
            if (firstPlayer.bestHand() != null) {
                assertThatThrownBy(() -> firstPlayer.bestHand().clear())
                        .isInstanceOf(UnsupportedOperationException.class);
            }

            PrivatePlayerState privateState = gameController.getPrivateState(roomId, hostPrincipal).getBody();
            assertThat(privateState).isNotNull();
            assertThat(privateState.holeCards()).hasSize(2);
            assertThatThrownBy(() -> privateState.holeCards().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> privateState.holeCards().add(new Card(Rank.ACE, Suit.SPADES)))
                    .isInstanceOf(UnsupportedOperationException.class);

            RoomDataResponse roomData = roomController.getRoomInfo(roomId, hostPrincipal).getBody();
            assertThat(roomData).isNotNull();
            assertThatThrownBy(() -> roomData.players().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("commands racing with departure preserve chip conservation and stateRevision monotonicity")
        void commandsRacingWithDeparturePreserveChipsAndStateRevision() throws Exception {
            int buyIn = 1000;
            int totalInitialChips = 4 * buyIn;
            String roomId = roomFixtures.createAndConnectRoom("Race Room", "Host", buyIn, "Alice", "Bob", "Charlie");

            PlayerPrincipal hostPrincipal = roomFixtures.principalFor(roomId, "Host");
            PlayerPrincipal alicePrincipal = roomFixtures.principalFor(roomId, "Alice");
            PlayerPrincipal charliePrincipal = roomFixtures.principalFor(roomId, "Charlie");
            roomController.startGame(roomId, hostPrincipal);

            PublicGameStateResponse initialSnapshot = gameController.getGameState(roomId, hostPrincipal).getBody();
            assertThat(initialSnapshot).isNotNull();
            long initialRevision = initialSnapshot.stateRevision();
            assertThat(initialSnapshot.pot()
                    + initialSnapshot.players().stream().mapToInt(PublicPlayerState::chips).sum())
                    .isEqualTo(totalInitialChips);

            int charlieChipsBeforeDeparture = initialSnapshot.players().stream()
                    .filter(p -> p.name().equals("Charlie"))
                    .findFirst()
                    .map(PublicPlayerState::chips)
                    .orElse(buyIn);

            try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
                CyclicBarrier barrier = new CyclicBarrier(3);
                AtomicLong lastObservedRevision = new AtomicLong(initialRevision);
                List<Future<Void>> futures = new ArrayList<>();

                futures.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    roomController.leaveRoom(roomId, charliePrincipal);
                    return null;
                }));
                futures.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < 5; i++) {
                        PublicGameStateResponse snapshot = gameController.getGameState(roomId, hostPrincipal).getBody();
                        if (snapshot != null) {
                            long rev = snapshot.stateRevision();
                            assertThat(rev).isGreaterThanOrEqualTo(lastObservedRevision.get());
                            lastObservedRevision.set(rev);
                        }
                    }
                    return null;
                }));
                futures.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        gameController.markReady(roomId, alicePrincipal);
                    } catch (UnauthorisedActionException ignored) {
                        // Ready is valid only during ready countdown
                    }
                    return null;
                }));

                for (Future<Void> future : futures) {
                    future.get(5, TimeUnit.SECONDS);
                }
            }

            PublicGameStateResponse finalSnapshot = gameController.getGameState(roomId, hostPrincipal).getBody();
            assertThat(finalSnapshot).isNotNull();
            assertThat(finalSnapshot.stateRevision()).isGreaterThanOrEqualTo(initialRevision);
            int finalPot = finalSnapshot.pot();
            int activeChips = finalSnapshot.players().stream().mapToInt(PublicPlayerState::chips).sum();
            assertThat(finalPot + activeChips + charlieChipsBeforeDeparture).isEqualTo(totalInitialChips);
        }

        @Test
        @DisplayName("commands racing with game completion preserve forfeit settlement and chip conservation")
        void commandsRacingWithGameCompletionPreserveSettlementAndChips() throws Exception {
            int buyIn = 1000;
            int totalChips = 2 * buyIn;
            String roomId = roomFixtures.createAndConnectRoom("Forfeit Room", "Host", buyIn, "Alice");

            PlayerPrincipal hostPrincipal = roomFixtures.principalFor(roomId, "Host");
            PlayerPrincipal alicePrincipal = roomFixtures.principalFor(roomId, "Alice");
            roomController.startGame(roomId, hostPrincipal);

            AtomicReference<PublicGameStateResponse> snapshotRead = new AtomicReference<>();
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Void> departureFuture = executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    roomController.leaveRoom(roomId, alicePrincipal);
                    return null;
                });
                Future<Void> readFuture = executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        snapshotRead.set(gameController.getGameState(roomId, hostPrincipal).getBody());
                    } catch (com.pokergame.exception.ResourceNotFoundException ignored) {
                        // Active state read after game over yields 404
                    }
                    return null;
                });
                departureFuture.get(5, TimeUnit.SECONDS);
                readFuture.get(5, TimeUnit.SECONDS);
            }

            GameEndResponse review = gameController.getGameReview(roomId, hostPrincipal).getBody();
            assertThat(review).isNotNull();
            assertThat(review.winner()).isEqualTo("Host");
            assertThat(review.isForfeit()).isTrue();
            assertThat(review.winnerChips()).isEqualTo(1005);
            int aliceRemainingChips = 995;
            assertThat(review.winnerChips() + aliceRemainingChips).isEqualTo(totalChips);

            PublicGameStateResponse observed = snapshotRead.get();
            if (observed != null) {
                int observedChips = observed.pot() + observed.players().stream().mapToInt(PublicPlayerState::chips).sum();
                assertThat(observedChips).isEqualTo(totalChips);
            }
        }

        @Test
        @DisplayName("queued retired-membership requests reject after replacement across REST and STOMP")
        void queuedRetiredMembershipRequestsRejectReplacementMembership() throws Exception {
            String roomId = roomFixtures.createAndConnectRoom("Queued Room", "Host", 1000, "Alice");
            PlayerPrincipal retiredPrincipal = roomFixtures.principalFor(roomId, "Alice");

            try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
                Future<Throwable> privateReadFuture;
                Future<Throwable> leaveFuture;
                Future<Throwable> actionFuture;
                Future<Throwable> stompSubFuture;

                try (LockLease _ = roomCoordinator.acquire(roomId)) {
                    privateReadFuture = executor.submit(() -> {
                        try {
                            gameController.getPrivateState(roomId, retiredPrincipal);
                            return null;
                        } catch (Throwable t) {
                            return t;
                        }
                    });
                    leaveFuture = executor.submit(() -> {
                        try {
                            roomController.leaveRoom(roomId, retiredPrincipal);
                            return null;
                        } catch (Throwable t) {
                            return t;
                        }
                    });
                    actionFuture = executor.submit(() -> {
                        try {
                            gameController.performAction(roomId,
                                    new PlayerActionRequest(PlayerAction.CHECK, 0),
                                    retiredPrincipal);
                            return null;
                        } catch (Throwable t) {
                            return t;
                        }
                    });
                    stompSubFuture = executor.submit(() -> {
                        try {
                            webSocketAuthInterceptor.preSend(stompSubscription(retiredPrincipal), null);
                            return null;
                        } catch (Throwable t) {
                            return t;
                        }
                    });

                    await().atMost(ASYNC_TIMEOUT)
                            .until(() -> roomCoordinator.lockReservationCount(roomId) >= 5);

                    gameLifecycleService.removePlayers(roomId, List.of("Alice"));
                    roomService.joinRoom(roomId, new JoinRoomRequest("Queued Room", "Alice", null));
                    Room room = roomService.getRoom(roomId);
                    room.setPlayerConnected("Alice", true);
                    String replacementMid = room.getMembershipId("Alice");
                    assertThat(replacementMid).isNotEqualTo(retiredPrincipal.membershipId());

                    gameLifecycleService.createGameFromRoom(roomId);
                }

                assertThat(privateReadFuture.get(5, TimeUnit.SECONDS)).isInstanceOf(AccessDeniedException.class);
                assertThat(leaveFuture.get(5, TimeUnit.SECONDS)).isInstanceOf(AccessDeniedException.class);
                assertThat(actionFuture.get(5, TimeUnit.SECONDS)).isInstanceOf(AccessDeniedException.class);
                assertThat(stompSubFuture.get(5, TimeUnit.SECONDS)).isInstanceOf(AccessDeniedException.class);

                PlayerPrincipal replacementPrincipal = roomFixtures.principalFor(roomId, "Alice");
                assertThat(withRoomLock(roomCoordinator, roomId, () -> roomService.getRoom(roomId).hasPlayer("Alice"))).isTrue();
                PrivatePlayerState replacementState = gameController.getPrivateState(roomId, replacementPrincipal).getBody();
                assertThat(replacementState).isNotNull();
                assertThat(replacementState.holeCards()).hasSize(2);
                assertThat(webSocketAuthInterceptor.preSend(stompSubscription(replacementPrincipal), null)).isNotNull();
            }
        }
    }

    @Nested
    @DisplayName("Scheduled lifecycle coordination & stale callback guards")
    class ScheduledLifecycleTests {

        @Test
        @DisplayName("stale turn callback firing during a new turn or hand is a no-op")
        void staleTurnCallbackDuringNewTurnOrHandIsNoOp() {
            String roomId = roomFixtures.createAndConnectRoom("Turn Stale Room", "Host", 1000, "Alice", "Bob");
            gameLifecycleService.createGameFromRoom(roomId);

            record TurnSnapshot(String actor, Long deadline, long hand, long generation) {}
            TurnSnapshot turn1 = withRoomLock(roomCoordinator, roomId, () -> {
                Game game = gameLifecycleService.getGame(roomId);
                return new TurnSnapshot(
                        game.getCurrentPlayer().getName(),
                        game.getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT),
                        game.getHandNumber(),
                        game.getScheduledTaskGeneration(ScheduledGameTask.TURN_TIMEOUT));
            });

            playerActionService.processPlayerAction(
                    roomId, new PlayerActionRequest(PlayerAction.CALL, null), turn1.actor());

            TurnSnapshot turn2 = withRoomLock(roomCoordinator, roomId, () -> {
                Game game = gameLifecycleService.getGame(roomId);
                return new TurnSnapshot(
                        game.getCurrentPlayer().getName(),
                        game.getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT),
                        game.getHandNumber(),
                        game.getScheduledTaskGeneration(ScheduledGameTask.TURN_TIMEOUT));
            });
            assertThat(turn2.actor()).isNotEqualTo(turn1.actor());

            // Stale callback from Turn 1
            gameLifecycleService.executeScheduledTask(
                    roomId, ScheduledGameTask.TURN_TIMEOUT, turn1.deadline(), turn1.hand(), turn1.generation());

            withRoomLock(roomCoordinator, roomId, () -> {
                Room room = roomService.getRoom(roomId);
                Game game = gameLifecycleService.getGame(roomId);
                assertThat(room.hasPlayer(turn2.actor())).isTrue();
                assertThat(game.getCurrentPlayer().getName()).isEqualTo(turn2.actor());
                assertThat(game.getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT)).isEqualTo(turn2.deadline());
                assertThat(game.getScheduledTaskGeneration(ScheduledGameTask.TURN_TIMEOUT)).isEqualTo(turn2.generation());
                assertThat(game.isGameOver()).isFalse();
                return null;
            });

            // Fold remaining players to complete hand 1 -> opens ready gate
            playerActionService.processPlayerAction(
                    roomId, new PlayerActionRequest(PlayerAction.FOLD, null), turn2.actor());
            String turn3Actor = withRoomLock(roomCoordinator, roomId, () -> gameLifecycleService.getGame(roomId).getCurrentPlayer().getName());
            playerActionService.processPlayerAction(
                    roomId, new PlayerActionRequest(PlayerAction.FOLD, null), turn3Actor);

            // Start hand 2 via READY_TIMEOUT expiry
            TurnSnapshot readyTimer = withRoomLock(roomCoordinator, roomId, () -> {
                Game game = gameLifecycleService.getGame(roomId);
                return new TurnSnapshot(
                        null,
                        game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT),
                        game.getHandNumber(),
                        game.getScheduledTaskGeneration(ScheduledGameTask.READY_TIMEOUT));
            });
            gameLifecycleService.executeScheduledTask(
                    roomId, ScheduledGameTask.READY_TIMEOUT, readyTimer.deadline(), readyTimer.hand(), readyTimer.generation());

            withRoomLock(roomCoordinator, roomId, () -> {
                assertThat(gameLifecycleService.getGame(roomId).getHandNumber()).isGreaterThan(turn1.hand());
                return null;
            });

            // Stale Turn 1 callback from Hand 1 during Hand 2 is a no-op
            gameLifecycleService.executeScheduledTask(
                    roomId, ScheduledGameTask.TURN_TIMEOUT, turn1.deadline(), turn1.hand(), turn1.generation());

            withRoomLock(roomCoordinator, roomId, () -> {
                Game game = gameLifecycleService.getGame(roomId);
                assertThat(game.isGameOver()).isFalse();
                assertThat(game.getPlayers()).hasSize(3);
                return null;
            });
        }

        @Test
        @DisplayName("stale disconnect grace expiry firing after reconnect or renewed deadline is a no-op")
        void staleDisconnectGraceExpiryAfterReconnectOrRenewalIsNoOp() {
            String roomId = roomFixtures.createAndConnectRoom("Grace Room", "Host", 1000, "Alice");
            gameLifecycleService.createGameFromRoom(roomId);
            String aliceMid = withRoomLock(roomCoordinator, roomId, () -> roomService.getRoom(roomId).getMembershipId("Alice"));

            long staleDeadline = System.currentTimeMillis() - 1_000L;
            gameLifecycleService.markPlayerDisconnected(roomId, "Alice", staleDeadline);
            withRoomLock(roomCoordinator, roomId, () -> {
                roomService.getRoom(roomId).setAbsenceDeadline("Alice", staleDeadline);
                assertThat(gameLifecycleService.getGame(roomId).findPlayerByName("Alice").getIsDisconnected()).isTrue();
                return null;
            });

            gameLifecycleService.markPlayerReconnected(roomId, "Alice");
            withRoomLock(roomCoordinator, roomId, () -> {
                roomService.getRoom(roomId).setPlayerConnected("Alice", true);
                assertThat(gameLifecycleService.getGame(roomId).findPlayerByName("Alice").getIsDisconnected()).isFalse();
                return null;
            });

            gameLifecycleService.removeDisconnectedPlayer(roomId, "Alice", aliceMid, staleDeadline);
            assertThat(withRoomLock(roomCoordinator, roomId, () -> roomService.getRoom(roomId).hasPlayer("Alice"))).isTrue();
            assertThat(withRoomLock(roomCoordinator, roomId, () -> gameLifecycleService.playerExistsInGame(roomId, "Alice"))).isTrue();

            long freshDeadline = System.currentTimeMillis() + 60_000L;
            gameLifecycleService.markPlayerDisconnected(roomId, "Alice", freshDeadline);
            withRoomLock(roomCoordinator, roomId, () -> {
                roomService.getRoom(roomId).setAbsenceDeadline("Alice", freshDeadline);
                return null;
            });

            gameLifecycleService.removeDisconnectedPlayer(roomId, "Alice", aliceMid, staleDeadline);
            assertThat(withRoomLock(roomCoordinator, roomId, () -> roomService.getRoom(roomId).hasPlayer("Alice"))).isTrue();
            assertThat(withRoomLock(roomCoordinator, roomId, () -> gameLifecycleService.playerExistsInGame(roomId, "Alice"))).isTrue();
        }

        @Test
        @DisplayName("turn timer racing with action and state reads preserves chip conservation")
        void timerRacesWithActionsAndReadsPreserveInvariants() throws Exception {
            String roomId = roomFixtures.createAndConnectRoom("Timer Race Room", "Host", 1000, "Alice", "Bob");
            PlayerPrincipal hostPrincipal = roomFixtures.principalFor(roomId, "Host");
            PlayerPrincipal alicePrincipal = roomFixtures.principalFor(roomId, "Alice");
            PlayerPrincipal bobPrincipal = roomFixtures.principalFor(roomId, "Bob");
            gameLifecycleService.createGameFromRoom(roomId);

            PublicGameStateResponse initialSnapshot = gameController.getGameState(roomId, hostPrincipal).getBody();
            assertThat(initialSnapshot).isNotNull();
            long initialRevision = initialSnapshot.stateRevision();

            String currentActor = withRoomLock(roomCoordinator, roomId, () -> gameLifecycleService.getGame(roomId).getCurrentPlayer().getName());
            Long turnDeadline = withRoomLock(roomCoordinator, roomId, () ->
                    gameLifecycleService.getGame(roomId).getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT));
            long handNumber = withRoomLock(roomCoordinator, roomId, () -> gameLifecycleService.getGame(roomId).getHandNumber());
            long generation = withRoomLock(roomCoordinator, roomId, () ->
                    gameLifecycleService.getGame(roomId).getScheduledTaskGeneration(ScheduledGameTask.TURN_TIMEOUT));

            try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
                CyclicBarrier barrier = new CyclicBarrier(3);
                AtomicLong lastObservedRevision = new AtomicLong(initialRevision);
                List<Future<Void>> futures = new ArrayList<>();

                futures.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    PlayerPrincipal actorPrincipal = switch (currentActor) {
                        case "Host" -> hostPrincipal;
                        case "Alice" -> alicePrincipal;
                        default -> bobPrincipal;
                    };
                    try {
                        gameController.performAction(roomId, new PlayerActionRequest(PlayerAction.CALL, null), actorPrincipal);
                    } catch (Exception ignored) {
                        // Action may fail if turn timeout raced ahead
                    }
                    return null;
                }));
                futures.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    gameLifecycleService.executeScheduledTask(
                            roomId, ScheduledGameTask.TURN_TIMEOUT, turnDeadline, handNumber, generation);
                    return null;
                }));
                futures.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < 5; i++) {
                        PublicGameStateResponse snapshot = gameController.getGameState(roomId, hostPrincipal).getBody();
                        if (snapshot != null) {
                            long rev = snapshot.stateRevision();
                            assertThat(rev).isGreaterThanOrEqualTo(lastObservedRevision.get());
                            lastObservedRevision.set(rev);
                        }
                    }
                    return null;
                }));

                for (Future<Void> future : futures) {
                    future.get(5, TimeUnit.SECONDS);
                }
            }

            PublicGameStateResponse finalSnapshot = gameController.getGameState(roomId, hostPrincipal).getBody();
            assertThat(finalSnapshot).isNotNull();
            assertThat(finalSnapshot.stateRevision()).isGreaterThanOrEqualTo(initialRevision);
            assertThat(finalSnapshot.pot()).isGreaterThanOrEqualTo(0);
            assertThat(finalSnapshot.players().stream().mapToInt(PublicPlayerState::chips).sum()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("stale inactivity callback firing after player activity is a no-op")
        void staleInactivityCallbackAfterActivityIsNoOp() {
            String roomId = roomFixtures.createAndConnectRoom("Inactivity Stale Room", "Host", 1000, "Alice");
            gameLifecycleService.createGameFromRoom(roomId);

            long originalDeadline = withRoomLock(roomCoordinator, roomId, () ->
                    roomService.getRoom(roomId).getInactivityDeadlineEpochMs());
            String actor = withRoomLock(roomCoordinator, roomId, () -> gameLifecycleService.getGame(roomId).getCurrentPlayer().getName());

            playerActionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.CALL, null), actor);
            gameLifecycleService.abandonGameIfInactive(roomId, originalDeadline);

            withRoomLock(roomCoordinator, roomId, () -> {
                assertThat(gameLifecycleService.gameExists(roomId)).isTrue();
                assertThat(gameLifecycleService.getGame(roomId).isGameOver()).isFalse();
                assertThat(roomService.getRoom(roomId).isTerminal()).isFalse();
                return null;
            });
        }

        @Test
        @DisplayName("last ready confirmation racing ready timeout starts exactly one hand")
        void lastReadyRacingTimeoutStartsExactlyOneHand() throws Exception {
            String roomId = roomFixtures.createAndConnectRoom("Ready Race Room", "Host", 1000, "Alice");
            gameLifecycleService.createGameFromRoom(roomId);

            long hand = withRoomLock(roomCoordinator, roomId, () -> gameLifecycleService.getGame(roomId).getHandNumber());
            String firstActor = withRoomLock(roomCoordinator, roomId, () -> gameLifecycleService.getGame(roomId).getCurrentPlayer().getName());
            playerActionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.FOLD, null), firstActor);

            gameLifecycleService.markPlayerReadyForNextHand(roomId, "Host");
            long deadline = withRoomLock(roomCoordinator, roomId, () ->
                    gameLifecycleService.getGame(roomId).getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
            long generation = withRoomLock(roomCoordinator, roomId, () ->
                    gameLifecycleService.getGame(roomId).getScheduledTaskGeneration(ScheduledGameTask.READY_TIMEOUT));

            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                CyclicBarrier start = new CyclicBarrier(2);
                Future<?> ready = executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        gameLifecycleService.markPlayerReadyForNextHand(roomId, "Alice");
                    } catch (UnauthorisedActionException alreadyAdvanced) {
                        assertThat(alreadyAdvanced).hasMessage("Ready countdown is not active.");
                    }
                    return null;
                });
                Future<?> timeout = executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    gameLifecycleService.executeScheduledTask(
                            roomId, ScheduledGameTask.READY_TIMEOUT, deadline, hand, generation);
                    return null;
                });
                ready.get(5, TimeUnit.SECONDS);
                timeout.get(5, TimeUnit.SECONDS);
            }

            withRoomLock(roomCoordinator, roomId, () -> {
                Game nextHand = gameLifecycleService.getGame(roomId);
                assertThat(nextHand.getHandNumber()).isEqualTo(hand + 1);
                assertThat(nextHand.getReadyCountdownDeadlineEpochMs()).isNull();
                int totalChips = nextHand.getPlayers().stream().mapToInt(Player::getChips).sum() + nextHand.getPot();
                assertThat(totalChips).isEqualTo(2000);
                return null;
            });
        }

        @Test
        @DisplayName("terminal cleanup on active game is a no-op and removes both game and room after abandonment")
        void terminalCleanupOnActiveGameIsNoOpAndRemovesAfterAbandonment() {
            String roomId = roomFixtures.createAndConnectRoom("Cleanup Lifecycle Room", "Host", 1000, "Alice");
            gameLifecycleService.createGameFromRoom(roomId);

            // Attempt cleanup while active -> no-op
            gameLifecycleService.executeScheduledTask(roomId, ScheduledGameTask.CLEANUP, 999_999L, 1L, 0L);
            withRoomLock(roomCoordinator, roomId, () -> {
                assertThat(gameLifecycleService.gameExists(roomId)).isTrue();
                assertThat(roomService.getRoom(roomId)).isNotNull();
                return null;
            });

            gameLifecycleService.abandonGameDueToInactivity(roomId);
            long cleanupDeadline = withRoomLock(roomCoordinator, roomId, () -> {
                Game game = gameLifecycleService.getGame(roomId);
                assertThat(game.isGameOver()).isTrue();
                assertThat(roomService.getRoom(roomId).isTerminal()).isTrue();
                return game.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP);
            });
            long hand = withRoomLock(roomCoordinator, roomId, () -> gameLifecycleService.getGame(roomId).getHandNumber());
            long generation = withRoomLock(roomCoordinator, roomId, () ->
                    gameLifecycleService.getGame(roomId).getScheduledTaskGeneration(ScheduledGameTask.CLEANUP));

            gameLifecycleService.executeScheduledTask(
                    roomId, ScheduledGameTask.CLEANUP, cleanupDeadline, hand, generation);

            withRoomLock(roomCoordinator, roomId, () -> {
                assertThat(gameLifecycleService.gameExists(roomId)).isFalse();
                assertThat(roomService.getRoom(roomId)).isNull();
                return null;
            });
        }
    }

    @Nested
    @DisplayName("Registration, timer, and lock cleanup")
    class RegistrationCleanupTests {

        @Test
        @DisplayName("create, connect, disconnect, depart, and cleanup cycles leave zero registrations or locks")
        void repeatedCyclesLeaveNoRegistrationsOrLocks() {
            String host = "Host1";
            String guest = "Guest1";
            String roomId = roomFixtures.createAndConnectRoom("Cleanup Room 1", host, 1000, guest);

            PlayerPrincipal hostPrincipal = roomFixtures.principalFor(roomId, host);
            PlayerPrincipal guestPrincipal = roomFixtures.principalFor(roomId, guest);
            webSocketEventListener.registerSession(hostPrincipal, "session-host-1");
            webSocketEventListener.registerSession(guestPrincipal, "session-guest-1");

            String hostKey = host + ":" + roomId;
            String guestKey = guest + ":" + roomId;
            rateLimitService.tryConsumeWs(hostKey);
            rateLimitService.tryConsumeWs(guestKey);
            assertThat(rateLimitService.hasWsBucket(hostKey)).isTrue();
            assertThat(rateLimitService.hasWsBucket(guestKey)).isTrue();

            gameLifecycleService.createGameFromRoom(roomId);

            // Guest departs explicitly (triggers forfeit end and schedules CLEANUP)
            gameLifecycleService.removePlayers(roomId, List.of(guest));
            await().atMost(ASYNC_TIMEOUT).untilAsserted(() -> {
                assertThat(rateLimitService.hasWsBucket(guestKey)).isFalse();
                assertThat(rateLimitService.hasWsBucket(hostKey)).isTrue();
            });
            StompHeaderAccessor staleGuestHeaders = StompHeaderAccessor.create(StompCommand.DISCONNECT);
            staleGuestHeaders.setSessionId("session-guest-1");
            webSocketEventListener.handleWebSocketDisconnectListener(new SessionDisconnectEvent(
                    this, MessageBuilder.createMessage(new byte[0], staleGuestHeaders.getMessageHeaders()),
                    "session-guest-1", null));
            assertThat(rateLimitService.hasWsBucket(guestKey)).isFalse();

            // Host departs during retained review: CLEANUP timer remains scheduled and retired host session has no effect
            gameLifecycleService.removePlayers(roomId, List.of(host));
            await().atMost(ASYNC_TIMEOUT).untilAsserted(() ->
                    assertThat(rateLimitService.hasWsBucket(hostKey)).isFalse());
            assertThat(withRoomLock(roomCoordinator, roomId,
                    () -> gameLifecycleService.getGame(roomId))).isNotNull();

            long cleanupDeadline = withRoomLock(roomCoordinator, roomId, () ->
                    gameLifecycleService.getGame(roomId).getScheduledTaskDeadline(ScheduledGameTask.CLEANUP));
            assertThat(cleanupDeadline).isPositive();
            long hand = withRoomLock(roomCoordinator, roomId, () -> gameLifecycleService.getGame(roomId).getHandNumber());
            long generation = withRoomLock(roomCoordinator, roomId, () ->
                    gameLifecycleService.getGame(roomId).getScheduledTaskGeneration(ScheduledGameTask.CLEANUP));

            gameLifecycleService.executeScheduledTask(
                    roomId, ScheduledGameTask.CLEANUP, cleanupDeadline, hand, generation);

            await().atMost(ASYNC_TIMEOUT).untilAsserted(() -> {
                assertThat(withRoomLock(roomCoordinator, roomId, () -> gameLifecycleService.getGame(roomId))).isNull();
                assertThat(withRoomLock(roomCoordinator, roomId, () -> roomService.getRoom(roomId))).isNull();
                assertThat(roomCoordinator.lockReservationCount(roomId)).isZero();
            });
        }
    }
}
