package com.pokergame.service;

import static com.pokergame.testutil.TestRoomFixtures.stompSubscription;
import static com.pokergame.testutil.TestRoomLocks.runWithRoomLock;
import static com.pokergame.testutil.TestRoomLocks.withRoomLock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.pokergame.config.WebSocketAuthInterceptor;
import com.pokergame.config.WebSocketEventListener;
import com.pokergame.controller.GameController;
import com.pokergame.controller.RoomController;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.PrivatePlayerState;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.Rank;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.enums.Suit;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Card;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.RoomAggregates;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.security.RateLimitService;
import com.pokergame.testutil.TestRoomFixtures;
import com.pokergame.util.LockLease;
import com.pokergame.util.RoomCoordinator;
import java.time.Clock;
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

/**
 * Headless Spring coordination integration suite verifying room lock boundaries,
 * command/departure/completion races, scheduled timer stale-callback guards,
 * session/bucket cleanup, and atomic membership replacement across REST and STOMP.
 */
@Tag("integration")
@DisplayName("Lifecycle coordination integration tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "poker.rate-limiting.enabled=true")
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

    @Autowired
    private Clock clock;

    private final Set<String> trackedRoomIds = new HashSet<>();
    private TestRoomFixtures roomFixtures;

    @BeforeEach
    void setUpFixtures() {
        roomFixtures = new TestRoomFixtures(roomService, roomCoordinator, webSocketEventListener, trackedRoomIds);
    }

    @AfterEach
    void cleanUpTrackedRooms() {
        for (String roomId : List.copyOf(trackedRoomIds)) {
            try {
                runWithRoomLock(roomCoordinator, roomId, () -> {
                    roomAggregates.remove(roomId);
                    roomService.destroyRoom(roomId);
                });
            } catch (RuntimeException ignored) {
                // Best-effort per-room cleanup so one failure does not leak later rooms.
            }
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

            runWithRoomLock(roomCoordinator, "owned-room", () ->
                    assertThatCode(() -> roomCoordinator.assertHeldByCurrentThread("owned-room"))
                            .doesNotThrowAnyException());

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
                    roomId, new PlayerActionRequest(PlayerAction.CALL, 0), actorName))
                    .doesNotThrowAnyException();

            runWithRoomLock(roomCoordinator, roomId, () -> {
                assertThatCode(() -> roomService.broadcastGameStarted(roomId)).doesNotThrowAnyException();
                roomService.removeMembers(roomId, List.of("Guest"), "Host");
                assertThat(roomService.getRoom(roomId).getPlayers()).containsExactly("Host");
            });

            // @DurableMutation public service entry points also acquire the room lock when called directly
            assertThatCode(() -> roomService.destroyRoom(roomId)).doesNotThrowAnyException();
            runWithRoomLock(roomCoordinator, roomId, () -> assertThat(roomService.getRoom(roomId)).isNull());
        }

        @Test
        @DisplayName("same-room nested locks join reentrantly while cross-room nesting is rejected")
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
            // A hand in progress always has an actor, so legal actions are present
            assertThat(publicState.legalActions()).isNotEmpty();
            assertThatThrownBy(() -> publicState.legalActions().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            // winners and best hands only exist once a hand is settled; GameStateServiceTest covers their
            // immutability on a settled showdown snapshot.

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
                    + initialSnapshot.players().stream().mapToInt(PublicGameStateResponse.PlayerState::chips).sum())
                    .isEqualTo(totalInitialChips);

            int charlieChipsBeforeDeparture = initialSnapshot.players().stream()
                    .filter(p -> p.name().equals("Charlie"))
                    .findFirst()
                    .map(PublicGameStateResponse.PlayerState::chips)
                    .orElse(buyIn);

            ExecutorService executor = Executors.newFixedThreadPool(4);
            try {
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
            } finally {
                shutDown(executor);
            }

            PublicGameStateResponse finalSnapshot = gameController.getGameState(roomId, hostPrincipal).getBody();
            assertThat(finalSnapshot).isNotNull();
            assertThat(finalSnapshot.stateRevision()).isGreaterThanOrEqualTo(initialRevision);
            assertThat(finalSnapshot.players()).extracting(PublicGameStateResponse.PlayerState::name)
                    .doesNotContain("Charlie");
            int finalPot = finalSnapshot.pot();
            int activeChips = finalSnapshot.players().stream().mapToInt(PublicGameStateResponse.PlayerState::chips).sum();
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
            AtomicReference<ResourceNotFoundException> snapshotRejection = new AtomicReference<>();
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
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
                    } catch (ResourceNotFoundException notFound) {
                        // Active state read after game over yields 404
                        snapshotRejection.set(notFound);
                    }
                    return null;
                });
                departureFuture.get(5, TimeUnit.SECONDS);
                readFuture.get(5, TimeUnit.SECONDS);
            } finally {
                shutDown(executor);
            }

            PublicGameStateResponse review = gameController.getGameReview(roomId, hostPrincipal).getBody();
            assertThat(review).isNotNull();
            assertThat(review.gameEnd()).isNotNull();
            assertThat(review.gameEnd().winner()).isEqualTo("Host");
            assertThat(review.gameEnd().isForfeit()).isTrue();
            assertThat(review.gameEnd().winnerChips()).isEqualTo(1005);
            assertThat(review.pot() + review.players().stream()
                    .mapToInt(PublicGameStateResponse.PlayerState::chips).sum()).isEqualTo(totalChips);

            // Whichever side won the race, the read either saw a consistent table or was refused as not found.
            PublicGameStateResponse observed = snapshotRead.get();
            if (observed != null) {
                int observedChips = observed.pot() + observed.players().stream()
                        .mapToInt(PublicGameStateResponse.PlayerState::chips).sum();
                assertThat(observedChips).isEqualTo(totalChips);
            } else {
                assertThat(snapshotRejection.get()).isNotNull();
            }
        }

        @Test
        @DisplayName("queued retired-membership requests reject after replacement across REST and STOMP")
        void queuedRetiredMembershipRequestsRejectReplacementMembership() throws Exception {
            String roomId = roomFixtures.createAndConnectRoom("Queued Room", "Host", 1000, "Alice");
            PlayerPrincipal retiredPrincipal = roomFixtures.principalFor(roomId, "Alice");

            ExecutorService executor = Executors.newFixedThreadPool(4);
            try {
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

                    // The lock holder plus the four queued requests
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
            } finally {
                shutDown(executor);
            }
        }

        @Test
        @DisplayName("start ordered before queued join rejects the late join and keeps rosters coherent")
        void startOrderedBeforeQueuedJoinRejectsLateJoin() throws Exception {
            String roomName = "Start First Race";
            String roomId = roomFixtures.createAndConnectRoom(roomName, "Host", "Player2");

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
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

                assertThat(lateJoin.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(BadRequestException.class)
                        .hasMessage("Cannot join room. The game has already started.");
                runWithRoomLock(roomCoordinator, roomId, () -> {
                    Room room = roomService.getRoom(roomId);
                    Game game = gameLifecycleService.getGame(roomId);
                    assertThat(room.isGameStarted()).isTrue();
                    assertThat(game).isNotNull();
                    assertThat(room.getPlayers()).containsExactlyInAnyOrder("Host", "Player2");
                    assertThat(game.getPlayers().stream().map(Player::getName).toList())
                            .containsExactlyInAnyOrder("Host", "Player2");
                    assertThat(room.hasPlayer("Racer")).isFalse();
                });
            } finally {
                shutDown(executor);
            }
        }

        @Test
        @DisplayName("join ordered before queued start admits the player with a stable membership in both rosters")
        void joinOrderedBeforeQueuedStartAdmitsWithStableMembership() throws Exception {
            String roomName = "Join First Race";
            String roomId = roomFixtures.createAndConnectRoom(roomName, "Host");
            String hostMembership = roomFixtures.principalFor(roomId, "Host").membershipId();

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
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
                runWithRoomLock(roomCoordinator, roomId, () -> {
                    Room room = roomService.getRoom(roomId);
                    Game game = gameLifecycleService.getGame(roomId);
                    assertThat(room.isGameStarted()).isTrue();
                    assertThat(game).isNotNull();
                    assertThat(room.getPlayers()).containsExactlyInAnyOrder("Host", "Racer");
                    assertThat(game.getPlayers().stream().map(Player::getName).toList())
                            .containsExactlyInAnyOrder("Host", "Racer");
                    assertThat(room.getMembershipId("Racer")).isEqualTo(racerMembership);
                    assertThat(room.getMembershipId("Host")).isEqualTo(hostMembership);
                });

                PlayerPrincipal racerPrincipal = roomFixtures.principalFor(roomId, "Racer");
                assertThat(racerPrincipal.membershipId()).isEqualTo(racerMembership);
                PublicGameStateResponse racerView = gameController.getGameState(roomId, racerPrincipal).getBody();
                assertThat(racerView).isNotNull();
                assertThat(racerView.players()).extracting(PublicGameStateResponse.PlayerState::name)
                        .containsExactlyInAnyOrder("Host", "Racer");
            } finally {
                shutDown(executor);
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

            TimerSnapshot turn1 = snapshotTurn(roomId);

            playerActionService.processPlayerAction(
                    roomId, new PlayerActionRequest(PlayerAction.CALL, 0), turn1.actor());

            TimerSnapshot turn2 = snapshotTurn(roomId);
            assertThat(turn2.actor()).isNotEqualTo(turn1.actor());

            // Stale callback from Turn 1
            gameLifecycleService.executeScheduledTask(
                    roomId, ScheduledGameTask.TURN_TIMEOUT, turn1.deadline(), turn1.hand(), turn1.timerVersion());

            assertThat(snapshotTurn(roomId)).isEqualTo(turn2);
            withRoomLock(roomCoordinator, roomId, () -> {
                assertThat(roomService.getRoom(roomId).hasPlayer(turn2.actor())).isTrue();
                assertThat(gameLifecycleService.getGame(roomId).isGameOver()).isFalse();
                return null;
            });

            // Fold remaining players to complete hand 1 -> opens ready gate
            playerActionService.processPlayerAction(
                    roomId, new PlayerActionRequest(PlayerAction.FOLD, 0), turn2.actor());
            playerActionService.processPlayerAction(
                    roomId, new PlayerActionRequest(PlayerAction.FOLD, 0), snapshotTurn(roomId).actor());

            // Start hand 2 via READY_TIMEOUT expiry
            TimerSnapshot readyTimer = withRoomLock(roomCoordinator, roomId, () -> {
                Game game = gameLifecycleService.getGame(roomId);
                return new TimerSnapshot(
                        null,
                        game.getScheduledTaskTime(ScheduledGameTask.READY_TIMEOUT),
                        game.getHandNumber(),
                        game.getTimerVersion(ScheduledGameTask.READY_TIMEOUT));
            });
            gameLifecycleService.executeScheduledTask(
                    roomId, ScheduledGameTask.READY_TIMEOUT, readyTimer.deadline(), readyTimer.hand(), readyTimer.timerVersion());

            TimerSnapshot hand2Turn = snapshotTurn(roomId);
            assertThat(hand2Turn.hand()).isGreaterThan(turn1.hand());

            // Stale Turn 1 callback from Hand 1 during Hand 2 is a no-op
            gameLifecycleService.executeScheduledTask(
                    roomId, ScheduledGameTask.TURN_TIMEOUT, turn1.deadline(), turn1.hand(), turn1.timerVersion());

            assertThat(snapshotTurn(roomId)).isEqualTo(hand2Turn);
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

            long staleDeadline = clock.millis() - 2_000L;
            gameLifecycleService.markPlayerDisconnected(roomId, "Alice", staleDeadline);
            withRoomLock(roomCoordinator, roomId, () -> {
                roomService.getRoom(roomId).setAbsenceDeadline("Alice", staleDeadline);
                assertThat(gameLifecycleService.getGame(roomId).findPlayerByName("Alice").isDisconnected()).isTrue();
                return null;
            });

            gameLifecycleService.markPlayerReconnected(roomId, "Alice");
            withRoomLock(roomCoordinator, roomId, () -> {
                roomService.getRoom(roomId).setPlayerConnected("Alice", true);
                assertThat(gameLifecycleService.getGame(roomId).findPlayerByName("Alice").isDisconnected()).isFalse();
                return null;
            });

            // Alice is back, so the elapsed deadline must not remove her
            gameLifecycleService.removeDisconnectedPlayer(roomId, "Alice", aliceMid, staleDeadline);
            assertThat(withRoomLock(roomCoordinator, roomId, () -> roomService.getRoom(roomId).hasPlayer("Alice"))).isTrue();
            assertThat(withRoomLock(roomCoordinator, roomId, () -> gameLifecycleService.playerExistsInGame(roomId, "Alice"))).isTrue();

            // The renewed deadline has also elapsed, so only the exact-deadline guard protects Alice
            long renewedDeadline = clock.millis() - 1_000L;
            gameLifecycleService.markPlayerDisconnected(roomId, "Alice", renewedDeadline);
            withRoomLock(roomCoordinator, roomId, () -> {
                roomService.getRoom(roomId).setAbsenceDeadline("Alice", renewedDeadline);
                return null;
            });

            gameLifecycleService.removeDisconnectedPlayer(roomId, "Alice", aliceMid, staleDeadline);
            assertThat(withRoomLock(roomCoordinator, roomId, () -> roomService.getRoom(roomId).hasPlayer("Alice"))).isTrue();
            assertThat(withRoomLock(roomCoordinator, roomId, () -> gameLifecycleService.playerExistsInGame(roomId, "Alice"))).isTrue();

            // The matching elapsed deadline does remove her
            gameLifecycleService.removeDisconnectedPlayer(roomId, "Alice", aliceMid, renewedDeadline);
            assertThat(withRoomLock(roomCoordinator, roomId, () -> roomService.getRoom(roomId).hasPlayer("Alice"))).isFalse();
        }

        @Test
        @DisplayName("turn timer racing with action and state reads preserves chip conservation")
        void timerRacesWithActionsAndReadsPreserveInvariants() throws Exception {
            int buyIn = 1000;
            int totalChips = 3 * buyIn;
            String roomId = roomFixtures.createAndConnectRoom("Timer Race Room", "Host", buyIn, "Alice", "Bob");
            gameLifecycleService.createGameFromRoom(roomId);

            TimerSnapshot turn = snapshotTurn(roomId);
            PlayerPrincipal actorPrincipal = roomFixtures.principalFor(roomId, turn.actor());
            // The timeout may remove the actor, so reads go through a player who is not acting
            String observer = List.of("Host", "Alice", "Bob").stream()
                    .filter(name -> !name.equals(turn.actor()))
                    .findFirst()
                    .orElseThrow();
            PlayerPrincipal observerPrincipal = roomFixtures.principalFor(roomId, observer);

            PublicGameStateResponse initialSnapshot = gameController.getGameState(roomId, observerPrincipal).getBody();
            assertThat(initialSnapshot).isNotNull();
            long initialRevision = initialSnapshot.stateRevision();
            int actorChipsBefore = initialSnapshot.players().stream()
                    .filter(p -> p.name().equals(turn.actor()))
                    .mapToInt(PublicGameStateResponse.PlayerState::chips)
                    .sum();

            ExecutorService executor = Executors.newFixedThreadPool(3);
            try {
                CyclicBarrier barrier = new CyclicBarrier(3);
                AtomicLong lastObservedRevision = new AtomicLong(initialRevision);
                List<Future<Void>> futures = new ArrayList<>();

                futures.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        gameController.performAction(roomId, new PlayerActionRequest(PlayerAction.CALL, 0), actorPrincipal);
                    } catch (AccessDeniedException | BadRequestException | UnauthorisedActionException ignored) {
                        // The turn timeout can remove the actor or advance the turn first
                    }
                    return null;
                }));
                futures.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    gameLifecycleService.executeScheduledTask(
                            roomId, ScheduledGameTask.TURN_TIMEOUT, turn.deadline(), turn.hand(), turn.timerVersion());
                    return null;
                }));
                futures.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < 5; i++) {
                        PublicGameStateResponse snapshot = gameController.getGameState(roomId, observerPrincipal).getBody();
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
            } finally {
                shutDown(executor);
            }

            PublicGameStateResponse finalSnapshot = gameController.getGameState(roomId, observerPrincipal).getBody();
            assertThat(finalSnapshot).isNotNull();
            assertThat(finalSnapshot.stateRevision()).isGreaterThanOrEqualTo(initialRevision);
            boolean actorRemoved = finalSnapshot.players().stream().noneMatch(p -> p.name().equals(turn.actor()));
            int remainingChips = finalSnapshot.pot()
                    + finalSnapshot.players().stream().mapToInt(PublicGameStateResponse.PlayerState::chips).sum();
            // A removed actor takes their stack out of play; their bet stays in the pot
            assertThat(remainingChips + (actorRemoved ? actorChipsBefore : 0)).isEqualTo(totalChips);
        }

        @Test
        @DisplayName("stale inactivity callback after activity or a renewed deadline is a no-op until the matching deadline expires")
        void staleInactivityCallbackIsNoOpUntilMatchingDeadlineExpires() {
            String roomId = roomFixtures.createAndConnectRoom("Inactivity Stale Room", "Host", 1000, "Alice");
            gameLifecycleService.createGameFromRoom(roomId);

            long originalDeadline = clock.millis() + 10_000L;
            withRoomLock(roomCoordinator, roomId, () -> {
                roomService.getRoom(roomId).setInactivityDeadlineEpochMs(originalDeadline);
                return null;
            });
            String actor = withRoomLock(roomCoordinator, roomId, () -> gameLifecycleService.getGame(roomId).getCurrentPlayer().getName());

            // Player activity pushes the deadline out, so the original deadline is stale
            playerActionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.CALL, 0), actor);
            long renewedDeadline = withRoomLock(roomCoordinator, roomId, () ->
                    roomService.getRoom(roomId).getInactivityDeadlineEpochMs());
            assertThat(renewedDeadline).isGreaterThan(originalDeadline);
            gameLifecycleService.abandonGameIfInactive(roomId, originalDeadline);
            assertGameStillActive(roomId);

            // The renewed deadline has elapsed, so only the exact-deadline guard keeps the game alive
            long elapsedRenewedDeadline = clock.millis() - 1_000L;
            withRoomLock(roomCoordinator, roomId, () -> {
                roomService.getRoom(roomId).setInactivityDeadlineEpochMs(elapsedRenewedDeadline);
                return null;
            });
            gameLifecycleService.abandonGameIfInactive(roomId, originalDeadline);
            assertGameStillActive(roomId);

            // The matching elapsed deadline abandons the game
            gameLifecycleService.abandonGameIfInactive(roomId, elapsedRenewedDeadline);
            withRoomLock(roomCoordinator, roomId, () -> {
                assertThat(gameLifecycleService.getGame(roomId).isGameOver()).isTrue();
                assertThat(roomService.getRoom(roomId).isFinished()).isTrue();
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
            playerActionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.FOLD, 0), firstActor);

            gameLifecycleService.markPlayerReadyForNextHand(roomId, "Host");
            long deadline = withRoomLock(roomCoordinator, roomId, () ->
                    gameLifecycleService.getGame(roomId).getScheduledTaskTime(ScheduledGameTask.READY_TIMEOUT));
            long timerVersion = withRoomLock(roomCoordinator, roomId, () ->
                    gameLifecycleService.getGame(roomId).getTimerVersion(ScheduledGameTask.READY_TIMEOUT));

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                CyclicBarrier start = new CyclicBarrier(2);
                Future<?> ready = executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        gameLifecycleService.markPlayerReadyForNextHand(roomId, "Alice");
                    } catch (UnauthorisedActionException alreadyAdvanced) {
                        assertThat(alreadyAdvanced).hasMessage("The ready check has already closed.");
                    }
                    return null;
                });
                Future<?> timeout = executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    gameLifecycleService.executeScheduledTask(
                            roomId, ScheduledGameTask.READY_TIMEOUT, deadline, hand, timerVersion);
                    return null;
                });
                ready.get(5, TimeUnit.SECONDS);
                timeout.get(5, TimeUnit.SECONDS);
            } finally {
                shutDown(executor);
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
        @DisplayName("cleanup timer on an active game is a no-op and removes both game and room after abandonment")
        void cleanupTimerOnActiveGameIsNoOpAndRemovesAfterAbandonment() {
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
                assertThat(roomService.getRoom(roomId).isFinished()).isTrue();
                return game.getScheduledTaskTime(ScheduledGameTask.CLEANUP);
            });
            long hand = withRoomLock(roomCoordinator, roomId, () -> gameLifecycleService.getGame(roomId).getHandNumber());
            long timerVersion = withRoomLock(roomCoordinator, roomId, () ->
                    gameLifecycleService.getGame(roomId).getTimerVersion(ScheduledGameTask.CLEANUP));

            gameLifecycleService.executeScheduledTask(
                    roomId, ScheduledGameTask.CLEANUP, cleanupDeadline, hand, timerVersion);

            withRoomLock(roomCoordinator, roomId, () -> {
                assertThat(gameLifecycleService.gameExists(roomId)).isFalse();
                assertThat(roomService.getRoom(roomId)).isNull();
                return null;
            });
        }

        private TimerSnapshot snapshotTurn(String roomId) {
            return withRoomLock(roomCoordinator, roomId, () -> {
                Game game = gameLifecycleService.getGame(roomId);
                return new TimerSnapshot(
                        game.getCurrentPlayer().getName(),
                        game.getScheduledTaskTime(ScheduledGameTask.TURN_TIMEOUT),
                        game.getHandNumber(),
                        game.getTimerVersion(ScheduledGameTask.TURN_TIMEOUT));
            });
        }

        private void assertGameStillActive(String roomId) {
            withRoomLock(roomCoordinator, roomId, () -> {
                assertThat(gameLifecycleService.gameExists(roomId)).isTrue();
                assertThat(gameLifecycleService.getGame(roomId).isGameOver()).isFalse();
                assertThat(roomService.getRoom(roomId).isFinished()).isFalse();
                return null;
            });
        }
    }

    @Nested
    @DisplayName("Registration, timer, and lock cleanup")
    class RegistrationCleanupTests {

        @Test
        @DisplayName("player departures and cleanup remove session buckets, the game, the room, and lock reservations")
        void departuresAndCleanupLeaveNoBucketsGameRoomOrLocks() {
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
            // A disconnect event for the departed guest's retired session must not change the table or the room
            List<Object> beforeStaleDisconnect = gameAndRoomState(roomId, guest);
            StompHeaderAccessor staleGuestHeaders = StompHeaderAccessor.create(StompCommand.DISCONNECT);
            staleGuestHeaders.setSessionId("session-guest-1");
            webSocketEventListener.handleWebSocketDisconnectListener(new SessionDisconnectEvent(
                    this, MessageBuilder.createMessage(new byte[0], staleGuestHeaders.getMessageHeaders()),
                    "session-guest-1", null));
            assertThat(gameAndRoomState(roomId, guest)).isEqualTo(beforeStaleDisconnect);
            assertThat(rateLimitService.hasWsBucket(guestKey)).isFalse();

            // Host departs during the retained game review: the CLEANUP timer remains scheduled
            gameLifecycleService.removePlayers(roomId, List.of(host));
            await().atMost(ASYNC_TIMEOUT).untilAsserted(() ->
                    assertThat(rateLimitService.hasWsBucket(hostKey)).isFalse());
            assertThat(withRoomLock(roomCoordinator, roomId,
                    () -> gameLifecycleService.getGame(roomId))).isNotNull();

            long cleanupDeadline = withRoomLock(roomCoordinator, roomId, () ->
                    gameLifecycleService.getGame(roomId).getScheduledTaskTime(ScheduledGameTask.CLEANUP));
            assertThat(cleanupDeadline).isPositive();
            long hand = withRoomLock(roomCoordinator, roomId, () -> gameLifecycleService.getGame(roomId).getHandNumber());
            long timerVersion = withRoomLock(roomCoordinator, roomId, () ->
                    gameLifecycleService.getGame(roomId).getTimerVersion(ScheduledGameTask.CLEANUP));

            gameLifecycleService.executeScheduledTask(
                    roomId, ScheduledGameTask.CLEANUP, cleanupDeadline, hand, timerVersion);

            await().atMost(ASYNC_TIMEOUT).untilAsserted(() -> {
                assertThat(withRoomLock(roomCoordinator, roomId, () -> gameLifecycleService.getGame(roomId))).isNull();
                assertThat(withRoomLock(roomCoordinator, roomId, () -> roomService.getRoom(roomId))).isNull();
                assertThat(roomCoordinator.lockReservationCount(roomId)).isZero();
            });
        }
    }

    // Game revision, cleanup deadline, seated players and the player's room membership, read under the room lock.
    private List<Object> gameAndRoomState(String roomId, String playerName) {
        return withRoomLock(roomCoordinator, roomId, () -> {
            var game = gameLifecycleService.getGame(roomId);
            return List.of(
                    game.getStateRevision(),
                    game.getScheduledTaskTime(ScheduledGameTask.CLEANUP),
                    game.getPlayers().stream().map(player -> player.getName()).toList(),
                    roomService.getRoom(roomId).hasPlayer(playerName));
        });
    }

    // Interrupts workers parked on a lock or barrier and waits a bounded time for them to exit.
    private static void shutDown(ExecutorService executor) {
        executor.shutdownNow();
        try {
            executor.awaitTermination(ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private record TimerSnapshot(String actor, Long deadline, long hand, long timerVersion) {
    }
}
