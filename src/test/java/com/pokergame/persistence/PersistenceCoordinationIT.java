package com.pokergame.persistence;

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
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.config.PersistenceProperties;
import com.pokergame.persistence.snapshot.RecoveredRoom;
import com.pokergame.persistence.snapshot.RoomSnapshotMapper;
import com.pokergame.persistence.transaction.DurableMutation;
import com.pokergame.persistence.transaction.DurableMutationAspect;
import com.pokergame.persistence.transaction.PostSaveActions;
import com.pokergame.persistence.transaction.RoomId;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.persistence.wal.EncryptionKeyring;
import com.pokergame.persistence.wal.WalFaultInjector;
import com.pokergame.persistence.wal.WalFaultPoint;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.GameStateService;
import com.pokergame.service.PlayerActionService;
import com.pokergame.service.RoomService;
import com.pokergame.testutil.TestRoomFixtures;
import com.pokergame.util.RoomCoordinator;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Verifies that room mutations saved to the WAL stay coordinated under {@link RoomCoordinator}: a
 * failed change rolls memory back to the last accepted state, failed saves are retried without
 * blocking gameplay, and recovery and membership changes are serialized with incoming commands.
 * It also covers the single-context WAL cleanup paths of room creation, replacement, and game end.
 */
@Tag("integration")
@DisplayName("Persistence coordination integration tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
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
    private RoomAggregates roomAggregates;

    @Autowired
    private GameLifecycleService gameLifecycleService;

    @Autowired
    private PlayerActionService playerActionService;

    @Autowired
    private RoomCoordinator roomCoordinator;

    @Autowired
    private EncryptedWalStore walStore;

    @Autowired
    private RoomSnapshotMapper snapshotMapper;

    @Autowired
    private DurableMutationAspect durableMutationAspect;

    @Autowired
    private RoomController roomController;

    @Autowired
    private GameController gameController;

    @Autowired
    private WebSocketAuthInterceptor webSocketAuthInterceptor;

    @Autowired
    private WebSocketEventListener webSocketEventListener;

    @Autowired
    private RoomRecoveryHandler roomRecoveryHandler;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private FailingMutationProbe failingMutationProbe;

    @Autowired
    private Clock clock;

    @Autowired
    @Qualifier("taskScheduler")
    private ThreadPoolTaskScheduler taskScheduler;

    @Autowired
    @Qualifier("pokerPersistenceHealth")
    private HealthIndicator pokerPersistenceHealth;

    @MockitoSpyBean
    private SimpMessagingTemplate messagingTemplate;

    @MockitoSpyBean
    private GameStateService gameStateService;

    private final Set<String> trackedRoomIds = new HashSet<>();
    private TestRoomFixtures roomFixtures;

    @BeforeEach
    void setUpFixtures() {
        roomFixtures = new TestRoomFixtures(roomService, roomCoordinator, webSocketEventListener, trackedRoomIds);
    }

    @AfterEach
    void cleanUp() {
        FAULT_INJECTOR.set(WalFaultInjector.NONE);
        awaitPendingSavesFlushed();
        for (String roomId : List.copyOf(trackedRoomIds)) {
            try {
                runWithRoomLock(roomCoordinator, roomId, () -> {
                    roomService.destroyRoom(roomId);
                    roomAggregates.remove(roomId);
                    walStore.delete(roomId);
                });
            } catch (RuntimeException ignored) {
                // Best-effort per-room cleanup so one failure does not leak later rooms.
            }
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

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
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
                runWithRoomLock(roomCoordinator, roomId, () -> {
                    Room currentRoom = roomService.getRoom(roomId);
                    Game currentGame = gameLifecycleService.getGame(roomId);
                    assertThat(currentRoom.hasPlayer("DirtyRogue")).isFalse();
                    assertThat(currentGame.getPlayers()).hasSize(2);
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

            runWithRoomLock(roomCoordinator, roomId, () -> {
                Room currentRoom = roomService.getRoom(roomId);
                Game currentGame = gameLifecycleService.getGame(roomId);
                assertThat(currentRoom.getPlayers()).containsExactlyInAnyOrder("Host", "Alice");
                assertThat(currentGame.getPlayers())
                        .allSatisfy(p -> assertThat(p.getChips() + p.getCurrentBet()).isEqualTo(1000));
            });
        } finally {
            releaseFailure.countDown();
            stopExecutor(executor);
        }
    }

    @Test
    @DisplayName("Persistence recovery coordinates under RoomCoordinator and serializes incoming commands")
    void persistenceRecoveryCoordinatesUnderRoomCoordinatorAndExcludesIncomingCommands() throws Exception {
        String roomId = roomFixtures.createAndConnectRoom("Recovery Coord Room", "Alice", "Bob");
        String guestMembership;
        runWithRoomLock(roomCoordinator, roomId, () -> roomAggregates.remove(roomId));

        CountDownLatch insideRestore = new CountDownLatch(1);
        CountDownLatch releaseRecovery = new CountDownLatch(1);
        RoomRecoveryHandler blockingHandler = recovered -> {
            assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isTrue();
            insideRestore.countDown();
            try {
                if (!releaseRecovery.await(ASYNC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("Timed out waiting inside recovery");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return roomRecoveryHandler.restore(recovered);
        };

        PersistenceRecovery recovery = new PersistenceRecovery(
                walStore, snapshotMapper, blockingHandler, applicationContext, roomCoordinator);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> recoveryFuture = executor.submit(() -> recovery.run(null));
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
        } finally {
            releaseRecovery.countDown();
            stopExecutor(executor);
        }

        assertThat(guestMembership).isNotBlank();
        assertThat(recovery.isComplete()).isTrue();
        runWithRoomLock(roomCoordinator, roomId, () ->
                assertThat(roomService.getRoom(roomId).getPlayers())
                        .containsExactlyInAnyOrder("Alice", "Bob", "Charlie"));
    }

    @Test
    @DisplayName("Storage outage keeps accepted lobby state and post-save order, reports MEMORY_ONLY until retry heals")
    void storageOutageKeepsAcceptedLobbyStateAndPostSaveOrderUntilRetryHeals() {
        String roomId = roomFixtures.createAndConnectRoom("Outage Ordering Room", "Host", "Alice");
        assertPersistenceHealth("DURABLE", true, 0);

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
        assertPersistenceHealth("MEMORY_ONLY", false, 1);
        runWithRoomLock(roomCoordinator, roomId, () ->
                assertThat(roomService.getRoom(roomId).getPlayers())
                        .containsExactlyInAnyOrder("Host", "Alice", "Bob"));

        FAULT_INJECTOR.set(WalFaultInjector.NONE);
        awaitPendingSavesFlushed();
        assertPersistenceHealth("DURABLE", true, 0);
        RecoveredRoom recovered = withRoomLock(roomCoordinator, roomId,
                () -> snapshotMapper.deserialize(walStore.recoverLatest(roomId).orElseThrow()));
        assertThat(recovered.room().getPlayers()).containsExactlyInAnyOrder("Host", "Alice", "Bob");
    }

    @Test
    @DisplayName("Queued retired membership cannot act, read private state, leave, or subscribe under durable WAL")
    void queuedRetiredMembershipRejectsReplacementUnderDurablePersistence() throws Exception {
        String roomId = roomFixtures.createAndConnectRoom("Durable Membership Room", "Host", "Alice");
        PlayerPrincipal retiredAlice = roomFixtures.principalFor(roomId, "Alice");

        CountDownLatch insideReplacement = new CountDownLatch(1);
        CountDownLatch releaseReplacement = new CountDownLatch(1);
        runWithRoomLock(roomCoordinator, roomId, () -> roomService.removeMembers(roomId, List.of("Alice"), "Host"));

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
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
                    gameController.performAction(roomId, new PlayerActionRequest(PlayerAction.FOLD, 0), retiredAlice);
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
        } finally {
            releaseReplacement.countDown();
            stopExecutor(executor);
        }

        assertThatThrownBy(() -> webSocketAuthInterceptor.preSend(stompSubscription(retiredAlice), null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatCode(() -> webSocketAuthInterceptor.preSend(stompSubscription(roomFixtures.principalFor(roomId, "Alice")), null))
                .doesNotThrowAnyException();

        RecoveredRoom recovered = withRoomLock(roomCoordinator, roomId,
                () -> snapshotMapper.deserialize(walStore.recoverLatest(roomId).orElseThrow()));
        assertThat(recovered.room().getMembershipId("Alice")).isNotEqualTo(retiredAlice.membershipId());
    }

    @Test
    @DisplayName("Rejection after a save outage keeps the pending retry, restores connectivity and deadlines, and discards rejected effects")
    void rollbackKeepsRetryRestoresConnectivityAndDeadlinesAndDiscardsRejectedEffects() {
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

        runWithRoomLock(roomCoordinator, roomId, () -> roomService.getRoom(roomId).setPlayerConnected("Bob", true));
        gameLifecycleService.createGameFromRoom(roomId);

        long expectedAbsenceDeadline = clock.millis() + 45_000L;
        long expectedTurnDeadline = clock.millis() + 25_000L;
        runWithRoomLock(roomCoordinator, roomId, () -> {
            Room room = roomService.getRoom(roomId);
            room.setPlayerConnected("Bob", false);
            room.setAbsenceDeadline("Bob", expectedAbsenceDeadline);
            Game game = gameLifecycleService.getGame(roomId);
            game.pauseForDisconnectedSurvivor(List.of());
            game.scheduleTask(ScheduledGameTask.TURN_TIMEOUT, expectedTurnDeadline);
        });

        List<String> rejectedEffects = new CopyOnWriteArrayList<>();
        assertThatThrownBy(() -> failingMutationProbe.mutateConnectivityAndTimersThenThrow(
                roomId, roomService, gameLifecycleService, "Bob", rejectedEffects))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Simulated failure after connectivity and progression change");

        assertThat(rejectedEffects).isEmpty();
        runWithRoomLock(roomCoordinator, roomId, () -> {
            Room room = roomService.getRoom(roomId);
            Game game = gameLifecycleService.getGame(roomId);
            assertThat(room.isPlayerConnected("Bob")).isFalse();
            assertThat(room.getAbsenceDeadline("Bob")).isEqualTo(expectedAbsenceDeadline);
            assertThat(game.isPausedForDisconnectedSurvivor()).isTrue();
            assertThat(game.getScheduledTaskTime(ScheduledGameTask.TURN_TIMEOUT)).isEqualTo(expectedTurnDeadline);
            assertThat(room.getPlayers()).containsExactlyInAnyOrder("Host", "Alice", "Bob");
        });

        FAULT_INJECTOR.set(WalFaultInjector.NONE);
        awaitPendingSavesFlushed();
        RecoveredRoom recovered = withRoomLock(roomCoordinator, roomId,
                () -> snapshotMapper.deserialize(walStore.recoverLatest(roomId).orElseThrow()));
        assertThat(recovered.room().getPlayers()).containsExactlyInAnyOrder("Host", "Alice", "Bob");
    }

    @ParameterizedTest
    @EnumSource(
            value = WalFaultPoint.class,
            names = {"BEFORE_PREPARE_WRITE", "BEFORE_COMMIT_WRITE", "BEFORE_FLUSH"})
    @DisplayName("Storage outage keeps gameplay and its broadcasts running in MEMORY_ONLY mode and saves the latest state once healed")
    void storageOutageKeepsGameplayRunningAndSavesLatestStateOnceHealed(WalFaultPoint failingPoint) {
        String roomId = roomFixtures.createAndConnectRoom("Gameplay Outage Room", "Host", "Alice");
        gameLifecycleService.createGameFromRoom(roomId);
        assertPersistenceHealth("DURABLE", true, 0);
        long revisionBefore = withRoomLock(roomCoordinator, roomId,
                () -> gameLifecycleService.getGame(roomId).getStateRevision());
        String actor = currentActor(roomId);

        FAULT_INJECTOR.set((point, id) -> {
            if (point == failingPoint) {
                throw new PersistenceException("Simulated storage outage at " + failingPoint);
            }
        });
        Mockito.clearInvocations(messagingTemplate);

        assertThatCode(() -> playerActionService.processPlayerAction(
                roomId, new PlayerActionRequest(PlayerAction.CALL, 0), actor))
                .doesNotThrowAnyException();

        assertThat(walStore.isHealthy()).isFalse();
        assertThat(walStore.unsavedRoomCount()).isEqualTo(1);
        assertPersistenceHealth("MEMORY_ONLY", false, 1);
        Mockito.verify(messagingTemplate, Mockito.atLeastOnce())
                .convertAndSend(Mockito.eq("/game/" + roomId), Mockito.any(Object.class));
        long liveRevision = withRoomLock(roomCoordinator, roomId,
                () -> gameLifecycleService.getGame(roomId).getStateRevision());
        assertThat(liveRevision).isGreaterThan(revisionBefore);

        FAULT_INJECTOR.set(WalFaultInjector.NONE);
        awaitPendingSavesFlushed();
        assertPersistenceHealth("DURABLE", true, 0);
        RecoveredRoom recovered = withRoomLock(roomCoordinator, roomId,
                () -> snapshotMapper.deserialize(walStore.recoverLatest(roomId).orElseThrow()));
        assertThat(recovered.game().getStateRevision()).isEqualTo(liveRevision);
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
        runWithRoomLock(roomCoordinator, roomId, () -> assertThat(walStore.recoverLatest(roomId)).isEmpty());
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
    @DisplayName("Per-room write failure isolates affected room while another room persists and deletes normally")
    void perRoomWriteFailureIsolatesFailedRoomWhileOtherRoomPersistsAndDeletes() {
        String failedRoomId = roomFixtures.createAndConnectRoom("Failed Isolation Room", "HostA", "Alice");
        String healthyRoomId = roomFixtures.createAndConnectRoom("Healthy Isolation Room", "HostB", "Bob");

        FAULT_INJECTOR.set((point, id) -> {
            if (failedRoomId.equals(id)
                    && (point == WalFaultPoint.BEFORE_COMMIT_WRITE || point == WalFaultPoint.BEFORE_FLUSH)) {
                throw new PersistenceException("Simulated room-scoped write outage");
            }
        });

        roomService.joinRoom(failedRoomId, new JoinRoomRequest("Failed Isolation Room", "Charlie", null));
        assertThat(walStore.isHealthy()).isFalse();
        assertThat(walStore.isHealthy(failedRoomId)).isFalse();
        assertThat(walStore.isHealthy(healthyRoomId)).isTrue();
        assertThat(walStore.unsavedRoomCount()).isEqualTo(1);

        // Healthy room can still persist mutations and delete its WAL while failedRoomId remains degraded.
        roomService.joinRoom(healthyRoomId, new JoinRoomRequest("Healthy Isolation Room", "Dana", null));
        runWithRoomLock(roomCoordinator, healthyRoomId, () -> {
            RecoveredRoom healthySnapshot = snapshotMapper.deserialize(walStore.recoverLatest(healthyRoomId).orElseThrow());
            assertThat(healthySnapshot.room().getPlayers()).containsExactlyInAnyOrder("HostB", "Bob", "Dana");
            assertThat(walStore.requiresCheckpoint(healthyRoomId)).isFalse();
        });

        roomService.destroyRoom(healthyRoomId);
        runWithRoomLock(roomCoordinator, healthyRoomId, () ->
                assertThat(walStore.recoverLatest(healthyRoomId)).isEmpty());
        assertThat(walStore.unsavedRoomCount()).isEqualTo(1);
        assertThat(walStore.isHealthy(failedRoomId)).isFalse();

        FAULT_INJECTOR.set(WalFaultInjector.NONE);
        awaitPendingSavesFlushed();
        RecoveredRoom recoveredFailed = withRoomLock(roomCoordinator, failedRoomId,
                () -> snapshotMapper.deserialize(walStore.recoverLatest(failedRoomId).orElseThrow()));
        assertThat(recoveredFailed.room().getPlayers()).containsExactlyInAnyOrder("HostA", "Alice", "Charlie");
    }

    @Test
    @DisplayName("Joining an expired lobby under persistence throws expiry error, closes the lobby, and sends ROOM_CLOSED")
    void joiningExpiredLobbyUnderPersistenceRejectsAndClosesLobby() {
        String roomName = "Expired Join Persistence Room";
        String roomId = roomFixtures.createAndConnectRoom(roomName, "Host");

        expireInactivityDeadline(roomId);
        Mockito.clearInvocations(messagingTemplate);

        assertThatThrownBy(() -> roomController.joinRoom(
                new JoinRoomRequest(roomName, "LateGuest", null),
                new MockHttpServletRequest()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("expired due to inactivity");

        runWithRoomLock(roomCoordinator, roomId, () -> {
            assertThat(roomService.getRoom(roomId)).isNull();
            assertThat(walStore.recoverLatest(roomId)).isEmpty();
        });
        Mockito.verify(messagingTemplate).convertAndSend(
                Mockito.eq("/room/" + roomId),
                Mockito.argThat((Object payload) -> payload instanceof RoomDataResponse roomData
                        && "INACTIVITY".equals(roomData.closedReason())));
    }

    @Test
    @DisplayName("Acting in an expired game throws GAME_EXPIRED and the inactivity timer abandons the game")
    void actingInExpiredGameThrowsGameExpiredAndTimerAbandonsGame() {
        String roomId = roomFixtures.createAndConnectRoom("Expired Act Room", "Host", "Alice");
        gameLifecycleService.createGameFromRoom(roomId);
        PlayerPrincipal actorPrincipal = roomFixtures.principalFor(roomId, currentActor(roomId));
        expireInactivityDeadline(roomId);

        assertThatThrownBy(() -> gameController.performAction(
                roomId, new PlayerActionRequest(PlayerAction.FOLD, 0), actorPrincipal))
                .isInstanceOfSatisfying(BadRequestException.class,
                        ex -> assertThat(ex.actionErrorCode()).isEqualTo("GAME_EXPIRED"));

        awaitGameAbandoned(roomId);
    }

    @Test
    @DisplayName("Readying in an expired game throws GAME_EXPIRED and the inactivity timer abandons the game")
    void readyingInExpiredGameThrowsGameExpiredAndTimerAbandonsGame() {
        String roomId = roomFixtures.createAndConnectRoom("Expired Ready Room", "Host", "Alice");
        gameLifecycleService.createGameFromRoom(roomId);
        // Folding ends the hand, which opens the ready countdown.
        playerActionService.processPlayerAction(
                roomId, new PlayerActionRequest(PlayerAction.FOLD, 0), currentActor(roomId));
        PlayerPrincipal host = roomFixtures.principalFor(roomId, "Host");
        runWithRoomLock(roomCoordinator, roomId, () ->
                assertThat(gameLifecycleService.getGame(roomId).isReadyCountdownActive()).isTrue());
        expireInactivityDeadline(roomId);

        assertThatThrownBy(() -> gameController.markReady(roomId, host))
                .isInstanceOfSatisfying(BadRequestException.class,
                        ex -> assertThat(ex.actionErrorCode()).isEqualTo("GAME_EXPIRED"));

        awaitGameAbandoned(roomId);
    }

    @Test
    @DisplayName("Claiming a win in an expired game throws GAME_EXPIRED and the inactivity timer abandons the game")
    void claimingWinInExpiredGameThrowsGameExpiredAndTimerAbandonsGame() {
        String roomId = roomFixtures.createAndConnectRoom("Expired Claim Room", "Host", "Alice");
        gameLifecycleService.createGameFromRoom(roomId);
        PlayerPrincipal host = roomFixtures.principalFor(roomId, "Host");
        runWithRoomLock(roomCoordinator, roomId, () -> {
            roomService.getRoom(roomId).setPlayerConnected("Alice", false);
            gameLifecycleService.getGame(roomId).findPlayerByName("Alice").setDisconnected(true);
        });
        expireInactivityDeadline(roomId);

        assertThatThrownBy(() -> gameController.claimWin(roomId, host))
                .isInstanceOfSatisfying(BadRequestException.class,
                        ex -> assertThat(ex.actionErrorCode()).isEqualTo("GAME_EXPIRED"));

        awaitGameAbandoned(roomId);
    }

    @Test
    @DisplayName("Failed post-action applyTurnOutcome throws, rolls back game state, and suppresses broadcasts")
    void failedApplyTurnOutcomeThrowsAndRollsBackGameState() {
        String roomId = roomFixtures.createAndConnectRoom("Action Rollback Room", "Host", "Alice", "Bob");
        gameLifecycleService.createGameFromRoom(roomId);

        record PreActionSnapshot(
                String actorName,
                int actorChips,
                int actorBet,
                boolean actorFolded,
                int pot,
                long stateRevision,
                Long turnDeadline) {}
        PreActionSnapshot before = withRoomLock(roomCoordinator, roomId, () -> {
            Game game = gameLifecycleService.getGame(roomId);
            Player actor = game.getCurrentPlayer();
            return new PreActionSnapshot(
                    actor.getName(),
                    actor.getChips(),
                    actor.getCurrentBet(),
                    actor.hasFolded(),
                    game.getPot(),
                    game.getStateRevision(),
                    game.getScheduledTaskTime(ScheduledGameTask.TURN_TIMEOUT));
        });
        assertThat(before.turnDeadline()).isNotNull();
        Mockito.clearInvocations(messagingTemplate);

        Mockito.doThrow(new IllegalStateException("Simulated applyTurnOutcome failure"))
                .when(gameStateService)
                .broadcastGameState(Mockito.argThat(g -> g != null && roomId.equals(g.getGameId())));

        assertThatThrownBy(() -> playerActionService.processPlayerAction(
                roomId, new PlayerActionRequest(PlayerAction.CALL, 0), before.actorName()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Simulated applyTurnOutcome failure");

        runWithRoomLock(roomCoordinator, roomId, () -> {
            Game game = gameLifecycleService.getGame(roomId);
            Player actor = game.findPlayerByName(before.actorName());
            assertThat(game.getCurrentPlayer().getName()).isEqualTo(before.actorName());
            assertThat(actor.getChips()).isEqualTo(before.actorChips());
            assertThat(actor.getCurrentBet()).isEqualTo(before.actorBet());
            assertThat(actor.hasFolded()).isEqualTo(before.actorFolded());
            assertThat(game.getPot()).isEqualTo(before.pot());
            assertThat(game.getStateRevision()).isEqualTo(before.stateRevision());
            assertThat(game.getScheduledTaskTime(ScheduledGameTask.TURN_TIMEOUT)).isEqualTo(before.turnDeadline());
        });
        Mockito.verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("Game replacement and eviction cancel runtime timers while keeping the room")
    void replacementAndEvictionCancelGameTimersAndKeepTheRoom() {
        long baselineFutures = countActiveScheduledFutures();
        String roomId = roomFixtures.createAndConnectRoom("Replacement Room", "Alice", "Bob");
        gameLifecycleService.createGameFromRoom(roomId);
        assertThat(countActiveScheduledFutures()).isGreaterThan(baselineFutures);

        Game original = withRoomLock(roomCoordinator, roomId, () -> gameLifecycleService.getGame(roomId));
        byte[] savedState = withRoomLock(roomCoordinator, roomId,
                () -> snapshotMapper.serialize(snapshotMapper.captureSnapshot(roomService.getRoom(roomId), original)));
        Game replacement = snapshotMapper.deserialize(savedState).game();
        assertThat(replacement).isNotSameAs(original);

        runWithRoomLock(roomCoordinator, roomId, () -> {
            roomAggregates.replace(new RoomAggregate(roomService.getRoom(roomId), replacement));
            assertThat(gameLifecycleService.getGame(roomId)).isSameAs(replacement);
            assertThat(roomService.getRoom(roomId).isGameStarted()).isTrue();
        });
        assertThat(countActiveScheduledFutures()).isGreaterThan(baselineFutures);

        runWithRoomLock(roomCoordinator, roomId, () -> roomAggregates.remove(roomId));
        assertThat(countActiveScheduledFutures()).isEqualTo(baselineFutures);
        runWithRoomLock(roomCoordinator, roomId, () -> {
            assertThat(gameLifecycleService.getGame(roomId)).isNull();
            assertThat(roomService.getRoom(roomId)).isNull();
        });
    }

    @Test
    @DisplayName("Game cleanup removes the room and game and deletes the WAL file")
    void gameCleanupRemovesRoomAndGameAndDeletesWalFile() {
        String roomId = roomFixtures.createAndConnectRoom("Normal End", "Alice", "Bob");
        gameLifecycleService.createGameFromRoom(roomId);
        assertThat(walFile(roomId)).exists();

        gameLifecycleService.markPlayerDisconnected(roomId, "Bob", clock.millis() + 60_000L);
        gameLifecycleService.claimWin(roomId, "Alice");

        record CleanupTimer(long runAtEpochMs, long handNumber, long timerVersion) {}
        CleanupTimer cleanupTimer = withRoomLock(roomCoordinator, roomId, () -> {
            Game game = gameLifecycleService.getGame(roomId);
            Long runAtEpochMs = game.getScheduledTaskTime(ScheduledGameTask.CLEANUP);
            assertThat(game.isGameOver()).isTrue();
            assertThat(runAtEpochMs).isNotNull();
            return new CleanupTimer(runAtEpochMs, game.getHandNumber(), game.getTimerVersion(ScheduledGameTask.CLEANUP));
        });
        // The finished game is still saved while its result is on display.
        assertThat(walFile(roomId)).exists();

        // Runs the cleanup exactly as its timer callback does, without waiting out the display delay.
        gameLifecycleService.executeScheduledTask(
                roomId,
                ScheduledGameTask.CLEANUP,
                cleanupTimer.runAtEpochMs(),
                cleanupTimer.handNumber(),
                cleanupTimer.timerVersion());

        runWithRoomLock(roomCoordinator, roomId, () -> {
            assertThat(roomService.getRoom(roomId)).isNull();
            assertThat(gameLifecycleService.getGame(roomId)).isNull();
        });
        assertThat(walFile(roomId)).doesNotExist();
    }

    @Test
    @DisplayName("Rejected room creation for a taken name leaves no WAL file on disk")
    void rejectedRoomCreationForTakenNameLeavesNoWalFile() {
        roomFixtures.createAndConnectRoom("Unique Name", "Alice");
        String duplicateRoomId = UUID.randomUUID().toString();
        trackedRoomIds.add(duplicateRoomId);

        assertThatThrownBy(() -> roomService.createRoom(
                duplicateRoomId, new CreateRoomRequest("Unique Name", "Bob", 4, 5, 10, 1000, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already taken");

        assertThat(walFile(duplicateRoomId)).doesNotExist();
    }

    @Test
    @DisplayName("Failed room creation cleans up room registry, active games, timers, and WAL")
    void failedRoomCreationCleansUpRoomsActiveGamesTimersAndWal() {
        String failedRoomId = UUID.randomUUID().toString();
        trackedRoomIds.add(failedRoomId);
        long baselineFutures = countActiveScheduledFutures();

        assertThatThrownBy(() -> failingMutationProbe.createRoomAndThrow(
                failedRoomId, roomService, new CreateRoomRequest("Aborted Room", "Alice", 4, 5, 10, 1000, null)))
                .isInstanceOf(IllegalStateException.class);

        runWithRoomLock(roomCoordinator, failedRoomId, () -> {
            assertThat(roomService.getRoom(failedRoomId)).isNull();
            assertThat(gameLifecycleService.getGame(failedRoomId)).isNull();
        });
        assertThat(countActiveScheduledFutures()).isEqualTo(baselineFutures);
        assertThat(walFile(failedRoomId)).doesNotExist();
    }

    private void assertPersistenceHealth(String mode, boolean persistenceHealthy, int unsavedRoomCount) {
        Health health = pokerPersistenceHealth.health();
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails())
                .containsEntry("recoveryComplete", true)
                .containsEntry("mode", mode)
                .containsEntry("persistenceHealthy", persistenceHealthy)
                .containsEntry("unsavedRoomCount", unsavedRoomCount);
    }

    private String currentActor(String roomId) {
        return withRoomLock(roomCoordinator, roomId,
                () -> gameLifecycleService.getGame(roomId).getCurrentPlayer().getName());
    }

    private void expireInactivityDeadline(String roomId) {
        runWithRoomLock(roomCoordinator, roomId, () ->
                roomService.getRoom(roomId).setInactivityDeadlineEpochMs(clock.millis() - 1_000L));
    }

    private void awaitGameAbandoned(String roomId) {
        await().atMost(ASYNC_TIMEOUT).untilAsserted(() -> runWithRoomLock(roomCoordinator, roomId, () -> {
            Game game = gameLifecycleService.getGame(roomId);
            assertThat(game).isNotNull();
            assertThat(game.isGameOver()).isTrue();
            assertThat(game.getGameEndResult()).isNotNull();
            assertThat(game.getGameEndResult().gameEnd()).isNotNull();
            assertThat(game.getGameEndResult().gameEnd().abandoned()).isTrue();
            assertThat(game.getGameEndResult().gameEnd().reason()).isEqualTo("INACTIVITY");
        }));
    }

    private long countActiveScheduledFutures() {
        return taskScheduler.getScheduledThreadPoolExecutor().getQueue().stream()
                .filter(ScheduledFuture.class::isInstance)
                .map(ScheduledFuture.class::cast)
                .filter(future -> !future.isCancelled() && !future.isDone())
                .count();
    }

    private static Path walFile(String roomId) {
        return walDirectory.resolve(roomId + ".wal");
    }

    private static void stopExecutor(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CoordinationTestConfig {
        @Bean
        @Primary
        EncryptedWalStore encryptedWalStore(PersistenceProperties properties, EncryptionKeyring keyring,
                RoomCoordinator roomCoordinator) {
            return new EncryptedWalStore(properties.directory(), keyring,
                    (point, roomId) -> FAULT_INJECTOR.get().check(point, roomId), roomCoordinator);
        }

        @Bean
        FailingMutationProbe failingMutationProbe(Clock clock) {
            return new FailingMutationProbe(clock);
        }
    }

    static class FailingMutationProbe {
        @FunctionalInterface
        interface ThrowingRunnable {
            void run() throws Exception;
        }

        private final Clock clock;

        FailingMutationProbe(Clock clock) {
            this.clock = clock;
        }

        @DurableMutation
        public void executeDirtyFailingMutation(@RoomId String roomId, RoomService rooms, GameLifecycleService games,
                ThrowingRunnable onInsideLock) throws Exception {
            Room room = rooms.getRoom(roomId);
            room.addPlayer("DirtyRogue");
            Game game = games.getGame(roomId);
            Player first = game.getPlayers().getFirst();
            first.addChips(999_999);
            onInsideLock.run();
            throw new IllegalStateException("Simulated dirty mutation failure");
        }

        @DurableMutation
        public void succeedWithEffect(@RoomId String roomId, Runnable mutation) {
            mutation.run();
        }

        @DurableMutation
        public void mutateConnectivityAndTimersThenThrow(@RoomId String roomId, RoomService rooms,
                GameLifecycleService games, String playerName, List<String> effects) {
            PostSaveActions.runAfterSave(() -> effects.add("rejected"));
            Room room = rooms.getRoom(roomId);
            room.setPlayerConnected(playerName, true);
            Game game = games.getGame(roomId);
            game.clearPausedForDisconnectedSurvivor();
            game.scheduleTask(ScheduledGameTask.TURN_TIMEOUT, clock.millis() + 999_999L);
            throw new IllegalStateException("Simulated failure after connectivity and progression change");
        }

        @DurableMutation
        public void failWithoutPreExistingRoom(@RoomId String roomId) {
            throw new IllegalStateException("Simulated rejection against missing room");
        }

        @DurableMutation
        public void createRoomAndThrow(@RoomId String roomId, RoomService rooms, CreateRoomRequest request) {
            rooms.createRoom(roomId, request);
            throw new IllegalStateException("Simulated post-create failure");
        }
    }
}
