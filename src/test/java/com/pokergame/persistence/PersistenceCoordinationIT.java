package com.pokergame.persistence;

import com.pokergame.config.WebSocketAuthInterceptor;
import com.pokergame.config.WebSocketEventListener;
import com.pokergame.controller.GameController;
import com.pokergame.controller.RoomController;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.config.PersistenceException;
import com.pokergame.persistence.config.PersistenceProperties;
import com.pokergame.persistence.snapshot.AggregateSnapshotMapper;
import com.pokergame.persistence.snapshot.RecoveredAggregate;
import com.pokergame.persistence.transaction.DurableMutation;
import com.pokergame.persistence.transaction.DurableMutationAspect;
import com.pokergame.persistence.transaction.PostSaveActions;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.persistence.wal.EncryptionKeyring;
import com.pokergame.persistence.wal.WalFaultInjector;
import com.pokergame.persistence.wal.WalFaultPoint;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.PlayerActionService;
import com.pokergame.service.RoomCoordinator;
import com.pokergame.service.RoomService;
import com.pokergame.testutil.TestRoomFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.pokergame.testutil.TestRoomFixtures.stompSubscription;
import static com.pokergame.testutil.TestRoomLocks.withRoomLock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Verifies durable WAL coordination, rollback snapshot fidelity, retry recovery,
 * cleanup intent survival, and durable-mode membership atomicity under {@link RoomCoordinator}.
 */
@Tag("integration")
@DisplayName("Persistence coordination integration tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(
        locations = "classpath:application-test.properties",
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "poker.rate-limiting.enabled=false",
                "poker.lifecycle.game-end-display-delay-ms=60000",
                "poker.lifecycle.ready-countdown-ms=60000"
        })
@Import(PersistenceCoordinationIT.CoordinationTestConfig.class)
class PersistenceCoordinationIT {

    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(5);
    private static final AtomicReference<WalFaultInjector> FAULT_INJECTOR =
            new AtomicReference<>(WalFaultInjector.NONE);

    @TempDir
    static Path walDirectory;

    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        String encodedKey = Base64.getEncoder().encodeToString(new byte[32]);
        registry.add("poker.persistence.enabled", () -> "true");
        registry.add("poker.persistence.directory", () -> walDirectory.toString().replace('\\', '/'));
        registry.add("poker.persistence.current-key-id", () -> "v1");
        registry.add("poker.persistence.keys.v1", () -> encodedKey);
    }

    @Autowired
    private RoomService roomService;

    @Autowired
    private GameLifecycleService gameLifecycleService;

    @Autowired
    private PlayerActionService playerActionService;

    @Autowired
    private RoomCoordinator roomCoordinator;

    @Autowired
    private EncryptedWalStore walStore;

    @Autowired
    private AggregateSnapshotMapper snapshotMapper;

    @Autowired
    private DurableMutationAspect durableMutationAspect;

    @Autowired
    private PersistenceProperties persistenceProperties;

    @Autowired
    private ObjectProvider<RoomService> roomServiceProvider;

    @Autowired
    private ObjectProvider<GameLifecycleService> gameLifecycleServiceProvider;

    @Autowired
    private RoomController roomController;

    @Autowired
    private GameController gameController;

    @Autowired
    private WebSocketAuthInterceptor webSocketAuthInterceptor;

    @Autowired
    private WebSocketEventListener webSocketEventListener;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private FailingMutationProbe failingMutationProbe;

    private final Set<String> trackedRoomIds = new HashSet<>();
    private TestRoomFixtures roomFixtures;

    @BeforeEach
    void setUpFixtures() {
        roomFixtures = new TestRoomFixtures(roomService, roomCoordinator, trackedRoomIds);
    }

    @AfterEach
    void cleanUp() {
        FAULT_INJECTOR.set(WalFaultInjector.NONE);
        awaitPendingSavesFlushed();
        for (String roomId : List.copyOf(trackedRoomIds)) {
            withRoomLock(roomCoordinator, roomId, () -> {
                gameLifecycleService.evictGame(roomId);
                roomService.destroyRoom(roomId);
                return null;
            });
            walStore.delete(roomId);
        }
        awaitPendingSavesFlushed();
        trackedRoomIds.clear();
    }

    private void awaitPendingSavesFlushed() {
        await().atMost(ASYNC_TIMEOUT).untilAsserted(() -> {
            durableMutationAspect.retryPendingSaves();
            assertThat(walStore.isHealthy()).isTrue();
            assertThat(walStore.unsavedRoomCount()).isZero();
        });
    }

    @Test
    @DisplayName("Rollback replacement while request queued restores objects and observes authorization")
    void rollbackReplacementWhileRequestQueuedObservesCurrentObjectsAndAuthorization() throws Exception {
        String roomId = roomFixtures.createAndConnectRoom("Rollback Queue Room", "Host", "Alice");
        gameLifecycleService.createGameFromRoom(roomId);

        CountDownLatch mutationInsideLock = new CountDownLatch(1);
        CountDownLatch releaseFailure = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Throwable> failingFuture = executor.submit(() -> {
                try {
                    failingMutationProbe.executeDirtyFailingMutation(roomId, roomService, gameLifecycleService, () -> {
                        mutationInsideLock.countDown();
                        if (!releaseFailure.await(ASYNC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                            throw new IllegalStateException("Timed out waiting to fail mutation");
                        }
                    });
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });

            assertThat(mutationInsideLock.await(ASYNC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

            Future<PlayerPrincipal> queuedActionFuture = executor.submit(() -> {
                PlayerPrincipal alicePrincipal = roomFixtures.principalFor(roomId, "Alice");
                withRoomLock(roomCoordinator, roomId, () -> {
                    Room currentRoom = roomService.getRoom(roomId);
                    Game currentGame = gameLifecycleService.getGame(roomId);
                    assertThat(currentRoom.hasPlayer("DirtyRogue")).isFalse();
                    assertThat(currentGame.getPlayers()).hasSize(2);
                    return null;
                });
                return alicePrincipal;
            });

            await().atMost(ASYNC_TIMEOUT)
                    .until(() -> roomCoordinator.lockReservationCount(roomId) >= 2);

            releaseFailure.countDown();

            Throwable failure = failingFuture.get(ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(failure).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Simulated dirty mutation failure");

            PlayerPrincipal observedPrincipal = queuedActionFuture.get(ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(observedPrincipal.membershipId()).isNotBlank();

            withRoomLock(roomCoordinator, roomId, () -> {
                Room currentRoom = roomService.getRoom(roomId);
                Game currentGame = gameLifecycleService.getGame(roomId);
                assertThat(currentRoom.getPlayers()).containsExactlyInAnyOrder("Host", "Alice");
                assertThat(currentGame.getPlayers())
                        .allSatisfy(p -> assertThat(p.getChips() + p.getCurrentBet()).isEqualTo(1000));
                return null;
            });
        }
    }

    @Test
    @DisplayName("Persistence recovery coordinates under RoomCoordinator and serializes incoming commands")
    void persistenceRecoveryCoordinatesUnderRoomCoordinatorAndExcludesIncomingCommands() throws Exception {
        String roomId = roomFixtures.createAndConnectRoom("Recovery Coord Room", "Alice", "Bob");
        String guestMembership;
        withRoomLock(roomCoordinator, roomId, () -> {
            gameLifecycleService.evictGame(roomId);
            roomService.evictRoom(roomId);
            return null;
        });

        CountDownLatch insideRestore = new CountDownLatch(1);
        CountDownLatch releaseRecovery = new CountDownLatch(1);
        RoomService unwrappedRoomService = AopTestUtils.getTargetObject(roomService);
        RoomService spyRoomService = Mockito.spy(unwrappedRoomService);
        Mockito.doAnswer(invocation -> {
            assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isTrue();
            insideRestore.countDown();
            if (!releaseRecovery.await(ASYNC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Timed out waiting inside recovery");
            }
            return invocation.callRealMethod();
        }).when(spyRoomService).restoreRoom(Mockito.any(Room.class), Mockito.anyString());

        PersistenceRecovery recovery = new PersistenceRecovery(
                walStore, snapshotMapper, spyRoomService, gameLifecycleService, webSocketEventListener,
                applicationContext, 5_000L, Clock.systemUTC(), roomCoordinator);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> recoveryFuture = executor.submit(recovery::recover);
            assertThat(insideRestore.await(ASYNC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

            assertThat(roomCoordinator.tryAcquire(roomId)).isNull();

            Future<String> incomingJoin = executor.submit(() -> {
                roomService.joinRoom(roomId, new JoinRoomRequest("Recovery Coord Room", "Charlie", null));
                return withRoomLock(roomCoordinator, roomId, () -> roomService.getRoom(roomId).getMembershipId("Charlie"));
            });

            await().atMost(ASYNC_TIMEOUT)
                    .until(() -> roomCoordinator.lockReservationCount(roomId) >= 2);
            assertThat(incomingJoin.isDone()).isFalse();

            releaseRecovery.countDown();
            recoveryFuture.get(ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            guestMembership = incomingJoin.get(ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        assertThat(guestMembership).isNotBlank();
        assertThat(recovery.isComplete()).isTrue();
        withRoomLock(roomCoordinator, roomId, () -> {
            assertThat(roomService.getRoom(roomId).getPlayers())
                    .containsExactlyInAnyOrder("Alice", "Bob", "Charlie");
            return null;
        });
    }

    @Test
    @DisplayName("Durable storage outage preserves accepted state and post-save order until retry heals")
    void persistenceFailureAndRetryPreservesAcceptedStateAndOrdering_DurableMode() {
        String roomId = roomFixtures.createAndConnectRoom("Outage Ordering Room", "Host", "Alice");

        FAULT_INJECTOR.set((point, id) -> {
            if (point == WalFaultPoint.BEFORE_COMMIT_WRITE || point == WalFaultPoint.BEFORE_FLUSH) {
                throw new PersistenceException("Simulated commit write outage");
            }
        });

        List<String> postSaveOrder = new ArrayList<>();
        failingMutationProbe.succeedWithEffect(roomId, () -> {
            roomService.joinRoom(roomId, new JoinRoomRequest("Outage Ordering Room", "Bob", null));
            PostSaveActions.runAfterSave(() -> postSaveOrder.add("first"));
            PostSaveActions.runAfterSave(() -> postSaveOrder.add("second"));
        });

        assertThat(postSaveOrder).containsExactly("first", "second");
        assertThat(walStore.isHealthy()).isFalse();
        assertThat(walStore.unsavedRoomCount()).isEqualTo(1);
        withRoomLock(roomCoordinator, roomId, () -> {
            assertThat(roomService.getRoom(roomId).getPlayers())
                    .containsExactlyInAnyOrder("Host", "Alice", "Bob");
            return null;
        });

        FAULT_INJECTOR.set(WalFaultInjector.NONE);
        awaitPendingSavesFlushed();
        RecoveredAggregate recovered = snapshotMapper.deserialize(walStore.recoverLatest(roomId).orElseThrow());
        assertThat(recovered.room().getPlayers()).containsExactlyInAnyOrder("Host", "Alice", "Bob");
    }

    @Test
    @DisplayName("Memory-only DurableMutationAspect serializes concurrent mutations under RoomCoordinator")
    void persistenceOrderingAndCoordination_MemoryOnlyMode() throws Exception {
        String roomId = "memory-only-" + UUID.randomUUID();
        DurableMutationAspect memoryOnlyAspect = new DurableMutationAspect(
                null, snapshotMapper, roomServiceProvider, gameLifecycleServiceProvider, persistenceProperties, roomCoordinator);
        MemoryOnlyTarget target = new MemoryOnlyTarget(roomCoordinator, roomId);
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(memoryOnlyAspect);
        MemoryOnlyTarget proxy = factory.getProxy();

        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger concurrentInside = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        List<String> executedOrder = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    barrier.await(ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                    proxy.runCoordinated(roomId, () -> {
                        int active = concurrentInside.incrementAndGet();
                        maxConcurrent.accumulateAndGet(active, Math::max);
                        executedOrder.add("task-" + index);
                        concurrentInside.decrementAndGet();
                    });
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            }
        }

        assertThat(maxConcurrent.get()).isEqualTo(1);
        assertThat(executedOrder).hasSize(threads);
        assertThat(roomCoordinator.lockReservationCount(roomId)).isZero();
    }

    @Test
    @DisplayName("Queued retired membership cannot act, read private state, leave, or subscribe under durable WAL")
    void queuedRetiredMembershipRejectsReplacementUnderDurablePersistence() throws Exception {
        String roomId = roomFixtures.createAndConnectRoom("Durable Membership Room", "Host", "Alice");
        PlayerPrincipal retiredAlice = roomFixtures.principalFor(roomId, "Alice");

        CountDownLatch insideReplacement = new CountDownLatch(1);
        CountDownLatch releaseReplacement = new CountDownLatch(1);
        withRoomLock(roomCoordinator, roomId, () -> {
            roomService.leavePlayersBatch(roomId, List.of("Alice"), "Host");
            return null;
        });

        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            Future<PlayerPrincipal> rejoinFuture = executor.submit(() -> withRoomLock(roomCoordinator, roomId, () -> {
                insideReplacement.countDown();
                try {
                    if (!releaseReplacement.await(ASYNC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                        throw new IllegalStateException("Timed out waiting to rejoin");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                roomService.joinRoom(roomId, new JoinRoomRequest("Durable Membership Room", "Alice", null));
                Room room = roomService.getRoom(roomId);
                room.setPlayerConnected("Alice", true);
                gameLifecycleService.createGameFromRoom(roomId);
                return new PlayerPrincipal("Alice", roomId, room.getMembershipId("Alice"));
            }));

            assertThat(insideReplacement.await(ASYNC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

            Future<Throwable> queuedAction = executor.submit(() -> {
                try {
                    gameController.performAction(roomId, new PlayerActionRequest(PlayerAction.FOLD, null), retiredAlice);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            Future<Throwable> queuedPrivateRead = executor.submit(() -> {
                try {
                    gameController.getPrivateState(roomId, retiredAlice);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            Future<Throwable> queuedLeave = executor.submit(() -> {
                try {
                    roomController.leaveRoom(roomId, retiredAlice);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });

            await().atMost(ASYNC_TIMEOUT)
                    .until(() -> roomCoordinator.lockReservationCount(roomId) >= 4);
            releaseReplacement.countDown();

            PlayerPrincipal newAlice = rejoinFuture.get(ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(newAlice.membershipId()).isNotEqualTo(retiredAlice.membershipId());

            assertThat(queuedAction.get(ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                    .isInstanceOfAny(AccessDeniedException.class, UnauthorisedActionException.class);
            assertThat(queuedPrivateRead.get(ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                    .isInstanceOf(AccessDeniedException.class);
            assertThat(queuedLeave.get(ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                    .isInstanceOf(AccessDeniedException.class);
        }

        assertThatThrownBy(() -> webSocketAuthInterceptor.preSend(stompSubscription(retiredAlice), null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatCode(() -> webSocketAuthInterceptor.preSend(stompSubscription(roomFixtures.principalFor(roomId, "Alice")), null))
                .doesNotThrowAnyException();

        RecoveredAggregate recovered = snapshotMapper.deserialize(walStore.recoverLatest(roomId).orElseThrow());
        assertThat(recovered.room().getMembershipId("Alice")).isNotEqualTo(retiredAlice.membershipId());
    }

    @Test
    @DisplayName("Rejection after serialization failure retains retry, restores connectivity/intents, and discards rejected effects")
    void rollbackPreservesRetryRestoresConnectivityAndIntents() {
        String roomId = roomFixtures.createAndConnectRoom("Rollback Invariants Room", "Host", "Alice");

        // First force a WAL save outage on an accepted lobby join so a pending retry exists.
        FAULT_INJECTOR.set((point, id) -> {
            if (point == WalFaultPoint.BEFORE_COMMIT_WRITE || point == WalFaultPoint.BEFORE_FLUSH) {
                throw new PersistenceException("Simulated write failure");
            }
        });
        roomService.joinRoom(roomId, new JoinRoomRequest("Rollback Invariants Room", "Bob", null));
        assertThat(walStore.isHealthy()).isFalse();
        assertThat(walStore.unsavedRoomCount()).isEqualTo(1);

        withRoomLock(roomCoordinator, roomId, () -> {
            roomService.getRoom(roomId).setPlayerConnected("Bob", true);
            return null;
        });
        gameLifecycleService.createGameFromRoom(roomId);

        long expectedAbsenceDeadline = System.currentTimeMillis() + 45_000L;
        long expectedTurnDeadline = System.currentTimeMillis() + 25_000L;
        withRoomLock(roomCoordinator, roomId, () -> {
            Room room = roomService.getRoom(roomId);
            room.setPlayerConnected("Bob", false);
            room.setAbsenceDeadline("Bob", expectedAbsenceDeadline);
            Game game = gameLifecycleService.getGame(roomId);
            game.setForfeitPending(true);
            game.scheduleTask(ScheduledGameTask.TURN_TIMEOUT, expectedTurnDeadline);
            return null;
        });

        List<String> rejectedEffects = new CopyOnWriteArrayList<>();
        assertThatThrownBy(() -> failingMutationProbe.mutateConnectivityAndIntentsThenThrow(
                roomId, roomService, gameLifecycleService, "Bob", rejectedEffects))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Simulated failure after connectivity and progression change");

        assertThat(rejectedEffects).isEmpty();
        withRoomLock(roomCoordinator, roomId, () -> {
            Room room = roomService.getRoom(roomId);
            Game game = gameLifecycleService.getGame(roomId);
            assertThat(room.isPlayerConnected("Bob")).isFalse();
            assertThat(room.getAbsenceDeadline("Bob")).isEqualTo(expectedAbsenceDeadline);
            assertThat(game.isForfeitPending()).isTrue();
            assertThat(game.getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT)).isEqualTo(expectedTurnDeadline);
            assertThat(room.getPlayers()).containsExactlyInAnyOrder("Host", "Alice", "Bob");
            return null;
        });

        FAULT_INJECTOR.set(WalFaultInjector.NONE);
        awaitPendingSavesFlushed();
        RecoveredAggregate recovered = snapshotMapper.deserialize(walStore.recoverLatest(roomId).orElseThrow());
        assertThat(recovered.room().getPlayers()).containsExactlyInAnyOrder("Host", "Alice", "Bob");
    }

    @Test
    @DisplayName("Snapshot prepare failure does not block valid in-memory gameplay or its post-save effects")
    void prepareFailureDoesNotPreventAcceptedGameplayOrEffects() {
        String roomId = roomFixtures.createAndConnectRoom("Prepare Fault Room", "Host", "Alice");
        gameLifecycleService.createGameFromRoom(roomId);

        FAULT_INJECTOR.set((point, id) -> {
            if (point == WalFaultPoint.BEFORE_PREPARE_WRITE) {
                throw new PersistenceException("Simulated prepare outage");
            }
        });

        String actor = withRoomLock(roomCoordinator, roomId, () -> gameLifecycleService.getGame(roomId).getCurrentPlayer().getName());
        assertThatCode(() -> playerActionService.processPlayerAction(
                roomId, new PlayerActionRequest(PlayerAction.CALL, null), actor))
                .doesNotThrowAnyException();

        assertThat(walStore.isHealthy()).isFalse();
        assertThat(walStore.unsavedRoomCount()).isEqualTo(1);

        FAULT_INJECTOR.set(WalFaultInjector.NONE);
        awaitPendingSavesFlushed();
    }

    @Test
    @DisplayName("Failed deletion WAL write retains retryable deletion tombstone across subsequent errors and retry cleans WAL")
    void failedDeletionRetainsAndRetriesDeletionTombstone() {
        String roomId = roomFixtures.createAndConnectRoom("Tombstone Room", "Host", "Alice");

        FAULT_INJECTOR.set((point, id) -> {
            if (point == WalFaultPoint.BEFORE_DELETE || point == WalFaultPoint.BEFORE_FLUSH) {
                throw new PersistenceException("Simulated deletion outage");
            }
        });

        roomService.destroyRoom(roomId);
        assertThat(walStore.unsavedRoomCount()).isEqualTo(1);

        // Subsequent rejected operation against the deleted room must not erase the deletion tombstone.
        assertThatThrownBy(() -> failingMutationProbe.failWithoutPreExistingRoom(roomId))
                .isInstanceOf(IllegalStateException.class);
        assertThat(walStore.unsavedRoomCount()).isEqualTo(1);

        FAULT_INJECTOR.set(WalFaultInjector.NONE);
        awaitPendingSavesFlushed();
        assertThat(walStore.recoverLatest(roomId)).isEmpty();
    }

    @Test
    @DisplayName("Rejected mutation on missing room during write outage does not degrade persistence health")
    void rejectedMutationOnMissingRoomDuringWriteOutageDoesNotDegradePersistence() {
        String missingRoomId = UUID.randomUUID().toString();
        FAULT_INJECTOR.set((point, id) -> {
            if (point == WalFaultPoint.BEFORE_PREPARE_WRITE
                    || point == WalFaultPoint.BEFORE_COMMIT_WRITE
                    || point == WalFaultPoint.BEFORE_FLUSH
                    || point == WalFaultPoint.BEFORE_DELETE) {
                throw new PersistenceException("Simulated storage outage");
            }
        });

        assertThatThrownBy(() -> failingMutationProbe.failWithoutPreExistingRoom(missingRoomId))
                .isInstanceOf(IllegalStateException.class);

        assertThat(walStore.isHealthy()).isTrue();
        assertThat(walStore.unsavedRoomCount()).isZero();
    }

    @Test
    @DisplayName("Committed terminal cleanup intent removes room, game, and WAL during recovery once expired")
    void committedCleanupIntentSurvivesRecoveryAndRemovesAggregates() {
        String roomId = roomFixtures.createAndConnectRoom("Cleanup Recovery Room", "Host", "Alice");
        gameLifecycleService.createGameFromRoom(roomId);

        long pastCleanupDeadline = 1_700_000_000_000L;
        withRoomLock(roomCoordinator, roomId, () -> {
            Game game = gameLifecycleService.getGame(roomId);
            game.markGameOver();
            game.scheduleTask(ScheduledGameTask.CLEANUP, pastCleanupDeadline);
            Room room = roomService.getRoom(roomId);
            byte[] image = snapshotMapper.serialize(room, roomService.getCurrentHost(roomId), game);
            walStore.checkpoint(roomId, image);
            gameLifecycleService.evictGame(roomId);
            roomService.evictRoom(roomId);
            return null;
        });

        assertThat(walStore.recoverLatest(roomId)).isPresent();

        Clock afterDeadlineClock = Clock.fixed(Instant.ofEpochMilli(pastCleanupDeadline + 10_000L), ZoneOffset.UTC);
        PersistenceRecovery recovery = new PersistenceRecovery(
                walStore, snapshotMapper, roomService, gameLifecycleService, webSocketEventListener,
                applicationContext, 5_000L, afterDeadlineClock, roomCoordinator);
        recovery.recover();

        withRoomLock(roomCoordinator, roomId, () -> {
            assertThat(roomService.getRoom(roomId)).isNull();
            assertThat(gameLifecycleService.getGame(roomId)).isNull();
            return null;
        });
        assertThat(walStore.recoverLatest(roomId)).isEmpty();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CoordinationTestConfig {
        @Bean
        @Primary
        EncryptedWalStore encryptedWalStore(PersistenceProperties properties, EncryptionKeyring keyring) {
            return new EncryptedWalStore(properties.getDirectory(), keyring,
                    (point, roomId) -> FAULT_INJECTOR.get().check(point, roomId));
        }

        @Bean
        FailingMutationProbe failingMutationProbe() {
            return new FailingMutationProbe();
        }
    }

    static class FailingMutationProbe {
        @FunctionalInterface
        interface ThrowingRunnable {
            void run() throws Exception;
        }

        @DurableMutation(roomId = "#roomId")
        public void executeDirtyFailingMutation(String roomId, RoomService rooms, GameLifecycleService games,
                ThrowingRunnable onInsideLock) throws Exception {
            Room room = rooms.getRoom(roomId);
            room.addPlayer("DirtyRogue");
            Game game = games.getGame(roomId);
            Player first = game.getPlayers().getFirst();
            first.addChips(999_999);
            onInsideLock.run();
            throw new IllegalStateException("Simulated dirty mutation failure");
        }

        @DurableMutation(roomId = "#roomId")
        public void succeedWithEffect(String roomId, Runnable mutation) {
            mutation.run();
        }

        @DurableMutation(roomId = "#roomId")
        public void mutateConnectivityAndIntentsThenThrow(String roomId, RoomService rooms,
                GameLifecycleService games, String playerName, List<String> effects) {
            PostSaveActions.runAfterSave(() -> effects.add("rejected"));
            Room room = rooms.getRoom(roomId);
            room.setPlayerConnected(playerName, true);
            Game game = games.getGame(roomId);
            game.setForfeitPending(false);
            game.scheduleTask(ScheduledGameTask.TURN_TIMEOUT, System.currentTimeMillis() + 999_999L);
            throw new IllegalStateException("Simulated failure after connectivity and progression change");
        }

        @DurableMutation(roomId = "#roomId")
        public void failWithoutPreExistingRoom(String roomId) {
            throw new IllegalStateException("Simulated rejection against missing room");
        }
    }

    static class MemoryOnlyTarget {
        private final RoomCoordinator roomCoordinator;
        private final String expectedRoomId;

        MemoryOnlyTarget(RoomCoordinator roomCoordinator, String expectedRoomId) {
            this.roomCoordinator = roomCoordinator;
            this.expectedRoomId = expectedRoomId;
        }

        @DurableMutation(roomId = "#roomId")
        public void runCoordinated(String roomId, Runnable action) {
            roomCoordinator.assertHeldByCurrentThread(expectedRoomId);
            action.run();
        }
    }
}
