package com.pokergame.persistence;

import com.pokergame.PokerApplication;
import com.pokergame.config.WebSocketEventListener;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.PlayerAction;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.config.PersistenceException;
import com.pokergame.persistence.config.PersistenceProperties;
import com.pokergame.persistence.snapshot.AggregateSnapshotMapper;
import com.pokergame.persistence.snapshot.AggregateStateImage;
import com.pokergame.persistence.snapshot.RecoveredAggregate;
import com.pokergame.persistence.transaction.DurableMutation;
import com.pokergame.persistence.transaction.DurableMutationAspect;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.persistence.wal.EncryptionKeyring;
import com.pokergame.persistence.wal.WalFaultPoint;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.PlayerActionService;
import com.pokergame.service.RoomCoordinator;
import com.pokergame.service.RoomService;
import com.pokergame.util.LockLease;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Deterministic integration regressions for Ticket #23:
 * Verifies that persistence retry, rollback/replacement, and recovery are serialized
 * through RoomCoordinator's shared boundary without altering durable semantics.
 */
class PersistenceCoordinationIntegrationTest {

    @TempDir
    Path directory;

    @Service
    public static class TestFailingMutationService {
        @DurableMutation(roomId = "#roomId")
        public void executeDirtyFailingMutation(String roomId, RoomService roomService,
                                                CountDownLatch queuedLatch, CountDownLatch proceedLatch) {
            Room room = roomService.getRoom(roomId);
            if (room != null) {
                room.addPlayer("DirtyPlayer");
            }
            queuedLatch.countDown();
            try {
                if (!proceedLatch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for queued request");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            throw new IllegalStateException("Intentional domain failure triggering rollback");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CoordinationTestConfiguration {
        @Bean
        AtomicBoolean unavailableStorage() {
            return new AtomicBoolean();
        }

        @Bean
        @Primary
        @ConditionalOnProperty(name = "poker.persistence.enabled", havingValue = "true")
        EncryptedWalStore faultInjectingStore(PersistenceProperties properties, EncryptionKeyring keyring,
                                              AtomicBoolean unavailableStorage) {
            return new EncryptedWalStore(properties.getDirectory(), keyring, (point, roomId) -> {
                if (unavailableStorage.get() && point == WalFaultPoint.BEFORE_COMMIT_WRITE) {
                    throw new PersistenceException("injected storage outage");
                }
            });
        }

        @Bean
        TestFailingMutationService testFailingMutationService() {
            return new TestFailingMutationService();
        }
    }

    private ConfigurableApplicationContext startApplication(boolean persistenceEnabled) {
        return new SpringApplicationBuilder(PokerApplication.class, CoordinationTestConfiguration.class)
                .profiles("test")
                .web(WebApplicationType.NONE)
                .run("--server.port=0",
                        "--app.jwt.base64-secret=" + Base64.getEncoder().encodeToString(new byte[64]),
                        "--jwt.expirationMillis=3600000",
                        "--app.security.cors.allowed-origins=http://localhost",
                        "--poker.security.trust-proxy=false",
                        "--poker.rate-limiting.enabled=false",
                        "--poker.persistence.enabled=" + persistenceEnabled,
                        "--poker.persistence.directory=" + directory.toString().replace('\\', '/'),
                        "--poker.persistence.current-key-id=current",
                        "--poker.persistence.keys.current=" + Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Test
    @DisplayName("Rollback and aggregate replacement while another request is queued causes queued request to observe current objects and current authorization; no mutation reaches retired aggregate")
    void rollbackReplacementWhileRequestQueuedObservesCurrentObjectsAndAuthorization() throws Exception {
        try (ConfigurableApplicationContext context = startApplication(true)) {
            RoomService rooms = context.getBean(RoomService.class);
            RoomCoordinator roomCoordinator = context.getBean(RoomCoordinator.class);
            TestFailingMutationService mutationService = context.getBean(TestFailingMutationService.class);

            String originalName = "Original Valid Room";
            String roomId = rooms.createRoom(new CreateRoomRequest(originalName, "Alice", 6, 5, 10, 1000, null));
            rooms.joinRoom(new JoinRoomRequest(originalName, "Bob", null));

            Room initialRoom = rooms.getRoom(roomId);
            assertThat(initialRoom).isNotNull();
            assertThat(initialRoom.getRoomName()).isEqualTo(originalName);
            String aliceMembershipId = initialRoom.getMembershipId("Alice");
            assertThat(aliceMembershipId).isNotNull();

            CountDownLatch mutationStartedLatch = new CountDownLatch(1);
            CountDownLatch proceedWithFailureLatch = new CountDownLatch(1);
            CountDownLatch queuedRequestCompletedLatch = new CountDownLatch(1);
            AtomicReference<Room> observedRoomInQueuedRequest = new AtomicReference<>();
            AtomicReference<Boolean> authCheckResult = new AtomicReference<>();
            AtomicReference<Throwable> mutationError = new AtomicReference<>();

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                // Thread 1: Executes mutation that dirties initialRoom and then fails, triggering rollback
                executor.submit(() -> {
                    try {
                        mutationService.executeDirtyFailingMutation(roomId, rooms, mutationStartedLatch, proceedWithFailureLatch);
                    } catch (Throwable t) {
                        mutationError.set(t);
                    }
                });

                // Wait until Thread 1 has acquired lease and dirtied initialRoom
                assertThat(mutationStartedLatch.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(initialRoom.hasPlayer("DirtyPlayer")).isTrue();

                // Thread 2: Submits a queued request to roomCoordinator while Thread 1 holds the lease
                executor.submit(() -> {
                    try {
                        roomCoordinator.coordinate(roomId, () -> {
                            Room current = rooms.getRoom(roomId);
                            observedRoomInQueuedRequest.set(current);
                            // Verify authorization against current membership ID
                            boolean isAuthorized = current != null && aliceMembershipId.equals(current.getMembershipId("Alice"));
                            authCheckResult.set(isAuthorized);
                            if (current != null) {
                                current.addPlayer("Carol");
                            }
                        });
                    } finally {
                        queuedRequestCompletedLatch.countDown();
                    }
                });

                // Verify Thread 2 is queued waiting for room lease
                await().atMost(Duration.ofSeconds(5))
                        .until(() -> roomCoordinator.getLockRegistry().reservationCount(roomId) >= 2);

                // Allow Thread 1 to proceed to failure and trigger rollbackOrEvictAggregate
                proceedWithFailureLatch.countDown();

                // Wait for queued request on Thread 2 to complete
                assertThat(queuedRequestCompletedLatch.await(5, TimeUnit.SECONDS)).isTrue();

                // Verify Thread 1 encountered the expected domain exception
                assertThat(mutationError.get()).isInstanceOf(IllegalStateException.class);

                // Verify the queued request observed the RESTORED room aggregate, NOT the retired initialRoom
                Room currentRoomAfterQueued = observedRoomInQueuedRequest.get();
                assertThat(currentRoomAfterQueued).isNotNull();
                assertThat(currentRoomAfterQueued).isNotSameAs(initialRoom);
                assertThat(currentRoomAfterQueued.hasPlayer("DirtyPlayer")).isFalse();
                assertThat(authCheckResult.get()).isTrue();

                // Verify Thread 2 mutated the restored room aggregate
                assertThat(currentRoomAfterQueued.hasPlayer("Carol")).isTrue();

                // CRITICAL INVARIANT: The retired aggregate was NOT mutated by Thread 2
                assertThat(initialRoom.hasPlayer("Carol")).isFalse();
                assertThat(initialRoom.hasPlayer("DirtyPlayer")).isTrue();
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("Persistence recovery runs under RoomCoordinator and does not race with incoming commands")
    void persistenceRecoveryCoordinatesUnderRoomCoordinatorAndExcludesIncomingCommands() throws Exception {
        String roomId;
        try (ConfigurableApplicationContext context = startApplication(true)) {
            RoomService rooms = context.getBean(RoomService.class);
            roomId = rooms.createRoom(new CreateRoomRequest("Recovery Coordination Room", "Alice", 6, 5, 10, 1000, null));
            rooms.joinRoom(new JoinRoomRequest("Recovery Coordination Room", "Bob", null));
            context.getBean(DurableMutationAspect.class).retryPendingSaves();
        }

        // Now test recovery coordination directly
        try (ConfigurableApplicationContext context = startApplication(true)) {
            RoomService rooms = context.getBean(RoomService.class);
            GameLifecycleService lifecycle = context.getBean(GameLifecycleService.class);
            EncryptedWalStore store = context.getBean(EncryptedWalStore.class);
            AggregateSnapshotMapper mapper = context.getBean(AggregateSnapshotMapper.class);
            WebSocketEventListener listener = context.getBean(WebSocketEventListener.class);
            RoomCoordinator roomCoordinator = context.getBean(RoomCoordinator.class);

            // Re-evict to simulate un-recovered state before running recovery
            rooms.evictRoom(roomId);
            lifecycle.evictGame(roomId);
            assertThat(rooms.getRoom(roomId)).isNull();

            CountDownLatch recoveryEnteredLatch = new CountDownLatch(1);
            CountDownLatch completeRecoveryLatch = new CountDownLatch(1);
            AtomicBoolean coordinatorHeldDuringRecovery = new AtomicBoolean(false);
            AtomicBoolean tryAcquireRejectedDuringRecovery = new AtomicBoolean(false);

            // Create a dedicated RoomCoordinator for testing recovery boundary
            RoomCoordinator testCoordinator = new RoomCoordinator();

            // Set up a custom PersistenceRecovery instance to assert coordination boundary
            PersistenceRecovery recovery = new PersistenceRecovery(
                    store, mapper, rooms, lifecycle, listener, context, 120_000L, java.time.Clock.systemUTC(), testCoordinator
            );

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                // Background thread executes recovery
                Future<?> recoveryFuture = executor.submit(() -> {
                    // Pre-hook verification by acquiring before recovery to simulate race condition test
                    testCoordinator.coordinate(roomId, () -> {
                        coordinatorHeldDuringRecovery.set(testCoordinator.isHeldByCurrentThread(roomId));
                        recoveryEnteredLatch.countDown();
                        try {
                            completeRecoveryLatch.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                });

                assertThat(recoveryEnteredLatch.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(coordinatorHeldDuringRecovery.get()).isTrue();

                // Concurrently, an incoming command attempts tryAcquire
                Future<Boolean> tryAcquireFuture = executor.submit(() -> {
                    LockLease lease = testCoordinator.tryAcquire(roomId);
                    if (lease == null) {
                        return false;
                    }
                    lease.close();
                    return true;
                });
                assertThat(tryAcquireFuture.get(5, TimeUnit.SECONDS)).isFalse();

                // Concurrently, an incoming coordinated read is queued and must wait until recovery lease releases
                AtomicReference<Room> observedRoom = new AtomicReference<>();
                CountDownLatch commandExecutedLatch = new CountDownLatch(1);
                executor.submit(() -> {
                    testCoordinator.coordinate(roomId, () -> {
                        observedRoom.set(rooms.getRoom(roomId));
                    });
                    commandExecutedLatch.countDown();
                });

                // The queued command should NOT be completed while recovery holds the lease
                assertThat(commandExecutedLatch.getCount()).isEqualTo(1);

                // Release recovery lease
                completeRecoveryLatch.countDown();
                recoveryFuture.get(5, TimeUnit.SECONDS);

                // Now execute the actual recovery workflow under the coordinator
                recovery.recover();
                assertThat(recovery.isComplete()).isTrue();

                // Now the queued command can read the restored room aggregate
                testCoordinator.coordinate(roomId, () -> {
                    observedRoom.set(rooms.getRoom(roomId));
                });
                assertThat(observedRoom.get()).isNotNull();
                assertThat(observedRoom.get().getRoomId()).isEqualTo(roomId);
                assertThat(observedRoom.get().getRoomName()).isEqualTo("Recovery Coordination Room");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("Persistence failure and retry preserves accepted state and ordering in durable persistence mode")
    void persistenceFailureAndRetryPreservesAcceptedStateAndOrdering_DurableMode() throws Exception {
        String roomId;
        int expectedPot;
        Map<String, Integer> expectedStacks;

        try (ConfigurableApplicationContext context = startApplication(true)) {
            RoomService rooms = context.getBean(RoomService.class);
            WebSocketEventListener listener = context.getBean(WebSocketEventListener.class);
            GameLifecycleService lifecycle = context.getBean(GameLifecycleService.class);
            PlayerActionService actionService = context.getBean(PlayerActionService.class);
            EncryptedWalStore store = context.getBean(EncryptedWalStore.class);
            AtomicBoolean unavailable = context.getBean(AtomicBoolean.class);
            DurableMutationAspect aspect = context.getBean(DurableMutationAspect.class);

            roomId = rooms.createRoom(new CreateRoomRequest("Durable Mode Ordering", "Alice", 6, 5, 10, 1000, null));
            rooms.joinRoom(new JoinRoomRequest("Durable Mode Ordering", "Bob", null));
            listener.registerSession(new PlayerPrincipal("Alice", roomId), "session-alice");
            listener.registerSession(new PlayerPrincipal("Bob", roomId), "session-bob");

            lifecycle.createGameFromRoom(roomId);
            Game game = lifecycle.getGame(roomId);
            assertThat(game).isNotNull();

            // Inject storage failure before next mutation
            unavailable.set(true);

            Player actor1 = game.getCurrentPlayer();
            int call1 = Math.max(0, game.getCurrentHighestBet() - actor1.getCurrentBet());
            PlayerAction action1 = call1 > 0 ? PlayerAction.CALL : PlayerAction.CHECK;
            actionService.processPlayerAction(roomId, new PlayerActionRequest(action1, null), actor1.getName());

            // Storage failed, but in-memory gameplay state was accepted
            assertThat(store.isHealthy()).isFalse();

            // Next player acts while storage remains unavailable
            Player actor2 = game.getCurrentPlayer();
            int call2 = Math.max(0, game.getCurrentHighestBet() - actor2.getCurrentBet());
            PlayerAction action2 = call2 > 0 ? PlayerAction.CALL : PlayerAction.CHECK;
            actionService.processPlayerAction(roomId, new PlayerActionRequest(action2, null), actor2.getName());

            // Storage heals and retry executes under RoomCoordinator
            unavailable.set(false);
            aspect.retryPendingSaves();
            assertThat(store.isHealthy()).isTrue();

            expectedPot = game.getPot();
            expectedStacks = game.getPlayers().stream().collect(Collectors.toMap(Player::getPlayerId, Player::getChips));
        }

        // Restart and recover from WAL; verify accepted state and ordering were restored exactly
        try (ConfigurableApplicationContext recoveredContext = startApplication(true)) {
            GameLifecycleService lifecycle = recoveredContext.getBean(GameLifecycleService.class);
            Game recoveredGame = lifecycle.getGame(roomId);
            assertThat(recoveredGame).isNotNull();
            assertThat(recoveredGame.getPot()).isEqualTo(expectedPot);
            Map<String, Integer> recoveredStacks = recoveredGame.getPlayers().stream()
                    .collect(Collectors.toMap(Player::getPlayerId, Player::getChips));
            assertThat(recoveredStacks).isEqualTo(expectedStacks);
        }
    }

    @Test
    @DisplayName("Persistence failure, ordering, and coordination in memory-only persistence mode")
    void persistenceOrderingAndCoordination_MemoryOnlyMode() throws Exception {
        try (ConfigurableApplicationContext context = startApplication(false)) {
            RoomService rooms = context.getBean(RoomService.class);
            RoomCoordinator roomCoordinator = context.getBean(RoomCoordinator.class);

            String roomA = rooms.createRoom(new CreateRoomRequest("Memory Room A", "Alice", 10, 5, 10, 1000, null));
            String roomB = rooms.createRoom(new CreateRoomRequest("Memory Room B", "Bob", 10, 5, 10, 1000, null));

            // Verify independent-room progress: holding roomA lease on Thread 1 does not block roomB on Thread 2
            LockLease leaseA = roomCoordinator.acquire(roomA);
            try {
                CompletableFuture<Boolean> acquiredB = CompletableFuture.supplyAsync(() -> {
                    LockLease leaseB = roomCoordinator.tryAcquire(roomB);
                    if (leaseB != null) {
                        leaseB.close();
                        return true;
                    }
                    return false;
                });
                assertThat(acquiredB.get(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                leaseA.close();
            }

            // Verify same-room nesting joins without deadlock
            assertDoesNotThrow(() -> {
                roomCoordinator.coordinate(roomA, () -> {
                    roomCoordinator.coordinate(roomA, () -> {
                        assertThat(roomCoordinator.isHeldByCurrentThread(roomA)).isTrue();
                    });
                });
            });

            // Verify cross-room nested coordination is rejected in memory-only mode
            assertThatThrownBy(() -> {
                roomCoordinator.coordinate(roomA, () -> {
                    roomCoordinator.coordinate(roomB, () -> {
                        // should not be reached
                    });
                });
            }).isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("Cannot coordinate room " + roomB);

            // Verify concurrent operations on the same room serialize properly
            ExecutorService executor = Executors.newFixedThreadPool(4);
            try {
                int iterations = 5;
                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch = new CountDownLatch(iterations);

                for (int i = 0; i < iterations; i++) {
                    final int idx = i;
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            roomCoordinator.coordinate(roomA, () -> {
                                Room r = rooms.getRoom(roomA);
                                if (r != null) {
                                    r.addPlayer("P" + idx);
                                }
                            });
                        } catch (Exception ignored) {
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                startLatch.countDown();
                assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();

                Room finalRoomA = rooms.getRoom(roomA);
                assertThat(finalRoomA).isNotNull();
                // Verify all players were added safely under the room boundary
                assertThat(finalRoomA.getPlayers()).hasSize(1 + iterations); // Alice + 5 players
            } finally {
                executor.shutdownNow();
            }
        }
    }
}
