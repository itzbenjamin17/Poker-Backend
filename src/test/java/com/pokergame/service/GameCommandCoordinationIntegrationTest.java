package com.pokergame.service;

import com.pokergame.PokerApplication;
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
import com.pokergame.enums.Suit;
import com.pokergame.model.Card;
import com.pokergame.model.Room;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.util.LockLease;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.access.AccessDeniedException;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.pokergame.util.ConcurrentTestSupport.stopExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Integration regressions for Ticket #20:
 * Asserts explicit coordination of game commands, projections, chip conservation,
 * immutable response boundaries, and rejection of queued retired-membership requests.
 */
class GameCommandCoordinationIntegrationTest {

    @TempDir
    Path directory;

    private ConfigurableApplicationContext startApplication(boolean persistenceEnabled) {
        return new SpringApplicationBuilder(PokerApplication.class)
                .profiles("test").web(WebApplicationType.NONE)
                .run("--poker.persistence.enabled=" + persistenceEnabled,
                        "--poker.persistence.directory=" + directory.toString().replace('\\', '/'),
                        "--poker.persistence.current-key-id=current",
                        "--poker.persistence.keys.current=" + Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Test
    @DisplayName("Public and private projections return deeply immutable or detached collections")
    void projectionsReturnDeeplyImmutableCollections() throws Exception {
        try (ConfigurableApplicationContext context = startApplication(false)) {
            RoomService rooms = context.getBean(RoomService.class);
            GameController gameController = context.getBean(GameController.class);
            RoomController roomController = context.getBean(RoomController.class);

            String roomId = rooms.createRoom(new CreateRoomRequest("Immutable Room", "Host", 6, 5, 10, 1000, null));
            rooms.joinRoom(new JoinRoomRequest("Immutable Room", "Alice", null));

            Room room = rooms.getRoom(roomId);
            room.setPlayerConnected("Host", true);
            room.setPlayerConnected("Alice", true);

            PlayerPrincipal hostPrincipal = new PlayerPrincipal("Host", roomId, room.getMembershipId("Host"));
            roomController.startGame(roomId, hostPrincipal);

            PublicGameStateResponse publicState = gameController.getGameState(roomId, hostPrincipal).getBody();
            assertThat(publicState).isNotNull();

            // Mutating public projection collections must throw UnsupportedOperationException
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
            PublicPlayerState firstPlayer = publicState.players().get(0);
            if (firstPlayer.bestHand() != null) {
                assertThatThrownBy(() -> firstPlayer.bestHand().clear())
                        .isInstanceOf(UnsupportedOperationException.class);
            }

            PrivatePlayerState privateState = gameController.getPrivateState(roomId, hostPrincipal).getBody();
            assertThat(privateState).isNotNull();
            assertThat(privateState.holeCards()).hasSize(2);

            // Mutating private projection hole cards must throw UnsupportedOperationException
            assertThatThrownBy(() -> privateState.holeCards().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> privateState.holeCards().add(new Card(Rank.ACE, Suit.SPADES)))
                    .isInstanceOf(UnsupportedOperationException.class);

            RoomDataResponse roomData = roomController.getRoomInfo(roomId, hostPrincipal).getBody();
            assertThat(roomData).isNotNull();
            assertThatThrownBy(() -> roomData.players().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Commands racing with departure preserve chip conservation and StateRevision monotonicity")
    void commandsRacingWithDeparturePreserveChipsAndStateRevision(boolean persistenceEnabled) throws Exception {
        try (ConfigurableApplicationContext context = startApplication(persistenceEnabled)) {
            RoomService rooms = context.getBean(RoomService.class);
            GameController gameController = context.getBean(GameController.class);
            RoomController roomController = context.getBean(RoomController.class);

            int buyIn = 1000;
            int totalInitialChips = 4 * buyIn; // 4000
            String roomId = rooms.createRoom(new CreateRoomRequest("Race Room", "Host", 6, 5, 10, buyIn, null));
            rooms.joinRoom(new JoinRoomRequest("Race Room", "Alice", null));
            rooms.joinRoom(new JoinRoomRequest("Race Room", "Bob", null));
            rooms.joinRoom(new JoinRoomRequest("Race Room", "Charlie", null));

            Room room = rooms.getRoom(roomId);
            room.setPlayerConnected("Host", true);
            room.setPlayerConnected("Alice", true);
            room.setPlayerConnected("Bob", true);
            room.setPlayerConnected("Charlie", true);

            PlayerPrincipal hostPrincipal = new PlayerPrincipal("Host", roomId, room.getMembershipId("Host"));
            PlayerPrincipal alicePrincipal = new PlayerPrincipal("Alice", roomId, room.getMembershipId("Alice"));
            PlayerPrincipal charliePrincipal = new PlayerPrincipal("Charlie", roomId, room.getMembershipId("Charlie"));

            roomController.startGame(roomId, hostPrincipal);

            PublicGameStateResponse initialSnapshot = gameController.getGameState(roomId, hostPrincipal).getBody();
            assertThat(initialSnapshot).isNotNull();
            long initialRevision = initialSnapshot.stateRevision();

            int initialPot = initialSnapshot.pot();
            int initialPlayerChips = initialSnapshot.players().stream().mapToInt(PublicPlayerState::chips).sum();
            assertThat(initialPot + initialPlayerChips).isEqualTo(totalInitialChips);

            // Record Charlie's chips before departure
            int charlieChipsBeforeDeparture = initialSnapshot.players().stream()
                    .filter(p -> p.name().equals("Charlie"))
                    .findFirst()
                    .map(PublicPlayerState::chips)
                    .orElse(buyIn);

            ExecutorService executor = Executors.newFixedThreadPool(4);
            try {
                CyclicBarrier barrier = new CyclicBarrier(3);
                AtomicLong lastObservedRevision = new AtomicLong(initialRevision);
                List<Future<Void>> futures = new ArrayList<>();

                // Task 1: Charlie leaves the game concurrently
                futures.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    gameController.leaveGame(roomId, charliePrincipal);
                    return null;
                }));

                // Task 2: Reader polls snapshots concurrently
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

                // Task 3: Alice marks ready or unready
                futures.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        gameController.markReady(roomId, alicePrincipal);
                    } catch (Exception ignored) {
                        // Mark ready is only valid during ready gate countdown; safe to catch
                    }
                    return null;
                }));

                for (Future<Void> future : futures) {
                    future.get(5, TimeUnit.SECONDS);
                }

                // Final invariant verification:
                // Chips must remain fully conserved across active players, pot, and departed players
                PublicGameStateResponse finalSnapshot = gameController.getGameState(roomId, hostPrincipal).getBody();
                assertThat(finalSnapshot).isNotNull();
                assertThat(finalSnapshot.stateRevision()).isGreaterThanOrEqualTo(initialRevision);

                int finalPot = finalSnapshot.pot();
                int activeChips = finalSnapshot.players().stream().mapToInt(PublicPlayerState::chips).sum();
                assertThat(finalPot + activeChips + charlieChipsBeforeDeparture).isEqualTo(totalInitialChips);

            } finally {
                stopExecutor(executor);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Commands racing with game completion preserve settlement and chip conservation")
    void commandsRacingWithGameCompletionPreserveSettlementAndChips(boolean persistenceEnabled) throws Exception {
        try (ConfigurableApplicationContext context = startApplication(persistenceEnabled)) {
            RoomService rooms = context.getBean(RoomService.class);
            GameController gameController = context.getBean(GameController.class);
            RoomController roomController = context.getBean(RoomController.class);

            int buyIn = 1000;
            int totalChips = 2000;
            String roomId = rooms.createRoom(new CreateRoomRequest("Forfeit Room", "Host", 6, 5, 10, buyIn, null));
            rooms.joinRoom(new JoinRoomRequest("Forfeit Room", "Alice", null));

            Room room = rooms.getRoom(roomId);
            room.setPlayerConnected("Host", true);
            room.setPlayerConnected("Alice", true);

            PlayerPrincipal hostPrincipal = new PlayerPrincipal("Host", roomId, room.getMembershipId("Host"));
            PlayerPrincipal alicePrincipal = new PlayerPrincipal("Alice", roomId, room.getMembershipId("Alice"));

            roomController.startGame(roomId, hostPrincipal);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                CyclicBarrier barrier = new CyclicBarrier(2);
                AtomicReference<PublicGameStateResponse> snapshotRead = new AtomicReference<>();

                // Thread 1: Alice departs (forfeit -> Host wins automatically)
                Future<Void> departureFuture = executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    gameController.leaveGame(roomId, alicePrincipal);
                    return null;
                });

                // Thread 2: Host reads public state
                Future<Void> readFuture = executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        snapshotRead.set(gameController.getGameState(roomId, hostPrincipal).getBody());
                    } catch (com.pokergame.exception.ResourceNotFoundException ignored) {
                        // If game already concluded via forfeit, active state read correctly yields 404
                    }
                    return null;
                });

                departureFuture.get(5, TimeUnit.SECONDS);
                readFuture.get(5, TimeUnit.SECONDS);

                // Review can be obtained by host
                GameEndResponse review = gameController.getGameReview(roomId, "Host", hostPrincipal).getBody();
                assertThat(review).isNotNull();
                assertThat(review.winner()).isEqualTo("Host");
                assertThat(review.isForfeit()).isTrue();
                // Winner receives the hand pot + uncalled refund; chips remain conserved with Alice's remaining chips
                assertThat(review.winnerChips()).isEqualTo(1005);
                int aliceRemainingChips = 995;
                assertThat(review.winnerChips() + aliceRemainingChips).isEqualTo(totalChips);

                // Any snapshot observed by host preserves chip conservation
                PublicGameStateResponse observed = snapshotRead.get();
                if (observed != null) {
                    int observedChips = observed.pot() + observed.players().stream().mapToInt(PublicPlayerState::chips).sum();
                    assertThat(observedChips).isEqualTo(totalChips);
                }

            } finally {
                stopExecutor(executor);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Queued retired-membership requests cannot expose PrivateProjection or remove replacement membership")
    void queuedRetiredMembershipCannotExposePrivateStateOrRemoveReplacement(boolean persistenceEnabled) throws Exception {
        try (ConfigurableApplicationContext context = startApplication(persistenceEnabled)) {
            RoomService rooms = context.getBean(RoomService.class);
            GameLifecycleService games = context.getBean(GameLifecycleService.class);
            GameController gameController = context.getBean(GameController.class);
            RoomCoordinator roomCoordinator = context.getBean(RoomCoordinator.class);

            String roomId = rooms.createRoom(new CreateRoomRequest("Queued Room", "Host", 6, 5, 10, 1000, null));
            rooms.joinRoom(new JoinRoomRequest("Queued Room", "Alice", null));

            Room room = rooms.getRoom(roomId);
            room.setPlayerConnected("Host", true);
            room.setPlayerConnected("Alice", true);

            String initialMembershipId = room.getMembershipId("Alice");
            PlayerPrincipal retiredPrincipal = new PlayerPrincipal("Alice", roomId, initialMembershipId);

            ExecutorService executor = Executors.newFixedThreadPool(3);
            try {
                Future<Throwable> privateReadFuture;
                Future<Throwable> leaveFuture;
                Future<Throwable> actionFuture;

                // Thread 1: Hold the coordination lease
                try (LockLease _ = roomCoordinator.acquire(roomId)) {
                    // Queue 1: Retired PrivateProjection read
                    privateReadFuture = executor.submit(() -> {
                        try {
                            gameController.getPrivateState(roomId, retiredPrincipal);
                            return null;
                        } catch (Throwable t) {
                            return t;
                        }
                    });

                    // Queue 2: Retired leaveGame command
                    leaveFuture = executor.submit(() -> {
                        try {
                            gameController.leaveGame(roomId, retiredPrincipal);
                            return null;
                        } catch (Throwable t) {
                            return t;
                        }
                    });

                    // Queue 3: Retired performAction command
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

                    // Wait deterministically until all 3 requests are queued in the lock registry
                    await().atMost(Duration.ofSeconds(5))
                            .until(() -> roomCoordinator.getLockRegistry().reservationCount(roomId) >= 4);

                    // Under the lock: retire Alice and replace with new membership
                    games.departParticipants(roomId, List.of("Alice"));
                    rooms.joinRoom(new JoinRoomRequest("Queued Room", "Alice", null));
                    String replacementMembershipId = rooms.getRoom(roomId).getMembershipId("Alice");
                    assertThat(replacementMembershipId).isNotEqualTo(initialMembershipId);

                    games.createGameFromRoom(roomId);

                    // When lease is released, queued tasks will wake up
                }

                // Verify all 3 queued operations failed with AccessDeniedException
                assertThat(privateReadFuture.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(AccessDeniedException.class);
                assertThat(leaveFuture.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(AccessDeniedException.class);
                assertThat(actionFuture.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(AccessDeniedException.class);

                // Replacement Alice is still present in the room and game
                assertThat(room.hasPlayer("Alice")).isTrue();
                String activeMembership = room.getMembershipId("Alice");
                assertThat(activeMembership).isNotNull().isNotEqualTo(initialMembershipId);

                // Replacement Alice can query private state successfully
                PlayerPrincipal replacementPrincipal = new PlayerPrincipal("Alice", roomId, activeMembership);
                PrivatePlayerState replacementState = gameController.getPrivateState(roomId, replacementPrincipal).getBody();
                assertThat(replacementState).isNotNull();
                assertThat(replacementState.holeCards()).hasSize(2);

            } finally {
                stopExecutor(executor);
            }
        }
    }
}
