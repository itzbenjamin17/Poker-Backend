package com.pokergame.persistence.transaction;

import static com.pokergame.testutil.TestRoomLocks.withRoomLock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pokergame.enums.PlayerAction;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.PersistenceException;
import com.pokergame.persistence.RoomAggregate;
import com.pokergame.persistence.RoomAggregates;
import com.pokergame.persistence.config.PersistenceProperties;
import com.pokergame.persistence.snapshot.RecoveredRoom;
import com.pokergame.persistence.snapshot.RoomSnapshot;
import com.pokergame.persistence.snapshot.RoomSnapshotMapper;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.persistence.wal.EncryptionKeyring;
import com.pokergame.persistence.wal.WalFaultPoint;
import com.pokergame.util.LockLease;
import com.pokergame.util.MdcKeys;
import com.pokergame.util.RoomCoordinator;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import tools.jackson.databind.json.JsonMapper;

/** Unit tests for {@link DurableMutationAspect} transaction boundaries, rollback, and room ID resolution. */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class DurableMutationAspectTest {

    @TempDir
    Path tempDir;

    @Mock
    private RoomSnapshotMapper snapshotMapper;

    @Mock
    private RoomAggregates aggregates;

    private PersistenceProperties properties;
    private RoomCoordinator roomCoordinator;
    private RoomSnapshotMapper realMapper;

    public static class TestTarget {
        @DurableMutation
        public void validMutation(@RoomId String roomId) {
        }

        @DurableMutation
        public void gameIdMutation(String playerName, @GameId String gameId) {
        }

        @DurableMutation
        public void unmarkedMutation(String roomId) {
        }

        @DurableMutation
        public void doublyMarkedMutation(@RoomId String roomId, @GameId String gameId) {
        }
    }

    static class MemoryOnlyTarget {
        private final RoomCoordinator roomCoordinator;
        private final String expectedRoomId;

        MemoryOnlyTarget(RoomCoordinator roomCoordinator, String expectedRoomId) {
            this.roomCoordinator = roomCoordinator;
            this.expectedRoomId = expectedRoomId;
        }

        @DurableMutation
        public void runCoordinated(@RoomId String roomId, Runnable action) {
            roomCoordinator.assertHeldByCurrentThread(expectedRoomId);
            action.run();
        }
    }

    @BeforeEach
    void setUp() {
        properties = new PersistenceProperties(
                false, Path.of("data", "poker-wal"), null, Map.of(), 1000, 16L * 1024 * 1024, 5000);
        roomCoordinator = new RoomCoordinator();
        realMapper = new RoomSnapshotMapper(JsonMapper.builder().findAndAddModules().build());
        RoomSnapshot mockSnapshot = mock(RoomSnapshot.class);
        lenient().when(snapshotMapper.captureSnapshot(any(), any())).thenReturn(mockSnapshot);
        lenient().when(snapshotMapper.serialize(any(RoomSnapshot.class))).thenReturn(new byte[]{1, 2, 3});
    }

    @AfterEach
    void tearDown() {
        PostSaveActions.clear();
    }

    @Test
    void nestedMutationCrossRoomBoundaryThrowsPersistenceException() throws Throwable {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring, roomCoordinator);
        DurableMutationAspect aspect = new DurableMutationAspect(
                store, snapshotMapper, aggregates, properties, roomCoordinator);

        Method method = TestTarget.class.getMethod("validMutation", String.class);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"room-B"});

        PostSaveActions.begin("room-A");
        try {
            PersistenceException ex = assertThrows(PersistenceException.class,
                    () -> aspect.persist(joinPoint));
            assertEquals("Nested durable mutations cannot cross room boundaries", ex.getMessage());
            verify(joinPoint, never()).proceed();
        } finally {
            PostSaveActions.clear();
        }
    }

    @Test
    void nestedCrossRoomMutationThrowsIllegalStateExceptionWhenPersistenceDisabled() throws Throwable {
        DurableMutationAspect aspect = new DurableMutationAspect(
                null, snapshotMapper, aggregates, properties, roomCoordinator);

        Method method = TestTarget.class.getMethod("validMutation", String.class);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"room-B"});

        try (LockLease _ = roomCoordinator.acquire("room-A")) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> aspect.persist(joinPoint));
            assertThat(ex.getMessage()).contains("room-B").contains("room-A");
            verify(joinPoint, never()).proceed();
        }
    }

    @Test
    void nestedCrossRoomMutationThrowsIllegalStateExceptionWhenPersistenceEnabled() throws Throwable {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring, roomCoordinator);
        DurableMutationAspect aspect = new DurableMutationAspect(
                store, snapshotMapper, aggregates, properties, roomCoordinator);

        Method method = TestTarget.class.getMethod("validMutation", String.class);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"room-B"});

        try (LockLease _ = roomCoordinator.acquire("room-A")) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> aspect.persist(joinPoint));
            assertThat(ex.getMessage()).contains("room-B").contains("room-A");
            verify(joinPoint, never()).proceed();
        }
    }

    @Test
    void sameRoomNestedMutationSucceedsWhenPersistenceDisabled() throws Throwable {
        DurableMutationAspect aspect = new DurableMutationAspect(
                null, snapshotMapper, aggregates, properties, roomCoordinator);

        Method method = TestTarget.class.getMethod("validMutation", String.class);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"room-A"});
        when(joinPoint.proceed()).thenReturn("success");

        try (LockLease _ = roomCoordinator.acquire("room-A")) {
            Object result = aspect.persist(joinPoint);
            assertThat(result).isEqualTo("success");
            verify(joinPoint).proceed();
            assertThat(roomCoordinator.isHeldByCurrentThread("room-A")).isTrue();
        }
        assertThat(roomCoordinator.isHeldByCurrentThread("room-A")).isFalse();
    }

    @Test
    void sameRoomNestedMutationWhenPersistenceEnabledSharesOuterSaveAndFiresPostSaveActionsOnce() throws Throwable {
        String roomId = "room-nested-enabled";
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        AtomicInteger commitWrites = new AtomicInteger();
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring, (point, id) -> {
            if (point == WalFaultPoint.BEFORE_COMMIT_WRITE) {
                commitWrites.incrementAndGet();
            }
        }, roomCoordinator);

        Room room = new Room(roomId, "Nested Room", "Alice", 6, 10, 20, 1000, null);
        room.addPlayer("Alice");
        when(aggregates.find(roomId)).thenReturn(new RoomAggregate(room, null));

        DurableMutationAspect aspect = new DurableMutationAspect(
                store, realMapper, aggregates, properties, roomCoordinator);

        List<String> events = new ArrayList<>();
        ProceedingJoinPoint innerJoinPoint = mutationPoint(roomId, () -> {
            room.addPlayer("Bob");
            PostSaveActions.runAfterSave(() -> events.add("inner-after-save"));
            return "inner-ok";
        });
        ProceedingJoinPoint outerJoinPoint = mutationPoint(roomId, () -> {
            PostSaveActions.runAfterSave(() -> events.add("outer-before-inner"));
            assertEquals("inner-ok", aspect.persist(innerJoinPoint));
            assertThat(events).isEmpty();
            PostSaveActions.runAfterSave(() -> events.add("outer-after-inner"));
            return "outer-ok";
        });

        assertEquals("outer-ok", aspect.persist(outerJoinPoint));
        assertThat(events).containsExactly("outer-before-inner", "inner-after-save", "outer-after-inner");
        assertThat(commitWrites.get()).as("nested mutation joins the outer save").isEqualTo(1);
        RecoveredRoom recovered = withRoomLock(roomCoordinator, roomId,
                () -> realMapper.deserialize(store.recoverLatest(roomId).orElseThrow()));
        assertThat(recovered.room().getPlayers()).containsExactly("Alice", "Bob");
    }

    @Test
    void persistenceOrderingAndCoordination_MemoryOnlyMode() throws Exception {
        String roomId = "memory-only-room";
        DurableMutationAspect memoryOnlyAspect = new DurableMutationAspect(
                null, snapshotMapper, aggregates, properties, roomCoordinator);
        MemoryOnlyTarget target = new MemoryOnlyTarget(roomCoordinator, roomId);
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(memoryOnlyAspect);
        MemoryOnlyTarget proxy = factory.getProxy();

        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger concurrentInside = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        List<String> executedOrder = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
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
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(maxConcurrent.get()).isEqualTo(1);
        assertThat(executedOrder).hasSize(threads);
        assertThat(roomCoordinator.lockReservationCount(roomId)).isZero();
    }

    @Test
    void commitFailureKeepsAcceptedEffectsAndLaterMutationAvailable() throws Throwable {
        AtomicReference<WalFaultPoint> failure = new AtomicReference<>(WalFaultPoint.BEFORE_COMMIT_WRITE);
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring,
                (point, roomId) -> {
                    if (point == failure.get()) {
                        throw new PersistenceException("injected " + point);
                    }
                }, roomCoordinator);

        DurableMutationAspect aspect = new DurableMutationAspect(
                store, snapshotMapper, aggregates, properties, roomCoordinator);

        Method method = TestTarget.class.getMethod("validMutation", String.class);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"room-4"});
        AtomicInteger moves = new AtomicInteger();
        List<String> effects = new ArrayList<>();
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            int move = moves.incrementAndGet();
            PostSaveActions.runAfterSave(() -> effects.add("state-" + move));
            PostSaveActions.runAfterSave(() -> effects.add("timer-" + move));
            return "ok";
        });

        Room room = mock(Room.class);
        when(aggregates.find("room-4")).thenReturn(new RoomAggregate(room, null));

        assertEquals("ok", aspect.persist(joinPoint));
        assertEquals(List.of("state-1", "timer-1"), effects, "Accepted effects are released exactly once");
        failure.set(null);
        assertEquals("ok", aspect.persist(joinPoint));
        assertEquals(2, moves.get(), "Later accepted commands still run");
        assertEquals(2, effects.stream().filter(value -> value.startsWith("state-")).count());
        aspect.retryPendingSaves();
        assertNull(PostSaveActions.currentRoomId(),
                "Thread-local PostSaveActions should be null after commit failure");
    }

    @Test
    void blankRoomIdArgumentThrowsPersistenceException() throws Throwable {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring, roomCoordinator);
        DurableMutationAspect aspect = new DurableMutationAspect(
                store, snapshotMapper, aggregates, properties, roomCoordinator);
        for (String badRoomId : new String[]{" ", null}) {
            ProceedingJoinPoint joinPoint = pointFor("validMutation", String.class, badRoomId);

            PersistenceException ex = assertThrows(PersistenceException.class,
                    () -> aspect.persist(joinPoint));
            assertEquals("Durable mutation did not resolve a room ID", ex.getMessage());
            verify(joinPoint, never()).proceed();
            assertNull(PostSaveActions.currentRoomId());
        }
    }

    @Test
    void methodWithNeitherRoomIdNorGameIdParameterFails() throws Throwable {
        for (DurableMutationAspect aspect : List.of(aspectWithStorage(), aspectWithoutStorage())) {
            ProceedingJoinPoint joinPoint = pointFor("unmarkedMutation", String.class, "room-6");

            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> aspect.persist(joinPoint));
            assertThat(ex.getMessage()).contains("unmarkedMutation").contains("@RoomId").contains("@GameId");
            verify(joinPoint, never()).proceed();
        }
    }

    @Test
    void methodWithBothRoomIdAndGameIdParametersFails() throws Throwable {
        for (DurableMutationAspect aspect : List.of(aspectWithStorage(), aspectWithoutStorage())) {
            ProceedingJoinPoint joinPoint = pointFor("doublyMarkedMutation", new Class<?>[]{String.class, String.class},
                    "room-7", "room-7");

            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> aspect.persist(joinPoint));
            assertThat(ex.getMessage()).contains("doublyMarkedMutation");
            verify(joinPoint, never()).proceed();
        }
    }

    @Test
    void gameIdParameterSelectsTheLockedRoom() throws Throwable {
        for (DurableMutationAspect aspect : List.of(aspectWithStorage(), aspectWithoutStorage())) {
            ProceedingJoinPoint joinPoint = pointFor("gameIdMutation", new Class<?>[]{String.class, String.class},
                    "Alice", "room-game-id");
            AtomicBoolean lockedRoom = new AtomicBoolean();
            AtomicBoolean otherRoomLocked = new AtomicBoolean();
            when(joinPoint.proceed()).thenAnswer(invocation -> {
                lockedRoom.set(roomCoordinator.isHeldByCurrentThread("room-game-id"));
                otherRoomLocked.set(roomCoordinator.isHeldByCurrentThread("Alice"));
                return "done";
            });

            assertEquals("done", aspect.persist(joinPoint));
            assertTrue(lockedRoom.get());
            assertFalse(otherRoomLocked.get());
        }
    }

    @Test
    void roomIdIsInLoggingContextDuringMutationAndRestoredAfterward() throws Throwable {
        for (DurableMutationAspect aspect : List.of(aspectWithStorage(), aspectWithoutStorage())) {
            AtomicReference<String> seen = new AtomicReference<>();
            ProceedingJoinPoint joinPoint = mutationPoint("room-mdc", () -> {
                seen.set(MDC.get(MdcKeys.ROOM_ID));
                return "ok";
            });
            MDC.put(MdcKeys.ROOM_ID, "outer-room");
            try {
                aspect.persist(joinPoint);
                assertEquals("room-mdc", seen.get());
                assertEquals("outer-room", MDC.get(MdcKeys.ROOM_ID));
            } finally {
                MDC.remove(MdcKeys.ROOM_ID);
            }
        }
    }

    @Test
    void prepareFailureContinuesMutationAndEffects() throws Throwable {
        AtomicBoolean unavailable = new AtomicBoolean(true);
        EncryptedWalStore store = failingStore(unavailable);
        DurableMutationAspect aspect = aspectForRoom(store, "room-prepare");
        AtomicInteger moves = new AtomicInteger();
        ProceedingJoinPoint joinPoint = mutationPoint("room-prepare", moves::incrementAndGet);

        assertEquals(1, aspect.persist(joinPoint));
        assertEquals(1, moves.get(), "Mutation proceeds when prepare fails");
    }

    @Test
    void serializationFailureContinuesMutationAndLaterCommand() throws Throwable {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring, roomCoordinator);
        DurableMutationAspect aspect = aspectForRoom(store, "room-serialize");
        AtomicInteger moves = new AtomicInteger();
        List<String> effects = new ArrayList<>();
        ProceedingJoinPoint joinPoint = mutationPoint("room-serialize", () -> {
            int move = moves.incrementAndGet();
            PostSaveActions.runAfterSave(() -> effects.add("effect-" + move));
            return move;
        });
        when(snapshotMapper.serialize(any(RoomSnapshot.class)))
                .thenThrow(new PersistenceException("injected serialization failure"));

        assertEquals(1, aspect.persist(joinPoint));
        assertEquals(List.of("effect-1"), effects);
        assertEquals(2, aspect.persist(joinPoint));
        assertEquals(2, moves.get());
    }

    @Test
    void failedDomainMutationStillRejectsAndDiscardsEffects() throws Throwable {
        AtomicBoolean unavailable = new AtomicBoolean(false);
        EncryptedWalStore store = failingStore(unavailable);
        DurableMutationAspect aspect = aspectForRoom(store, "room-invalid");
        List<String> published = new ArrayList<>();
        ProceedingJoinPoint joinPoint = mutationPoint("room-invalid", () -> {
            PostSaveActions.runAfterSave(() -> published.add("invalid"));
            throw new IllegalArgumentException("invalid action");
        });

        assertThrows(IllegalArgumentException.class, () -> aspect.persist(joinPoint));
        assertTrue(published.isEmpty());
        assertNull(PostSaveActions.currentRoomId());
        verify(snapshotMapper, never()).serialize(any(RoomSnapshot.class));
    }

    @Test
    @DisplayName("captureState holds the shared room coordinator through capture and encoding")
    void captureState_UsesSharedRoomCoordinatorForCaptureAndEncoding() throws Throwable {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring, roomCoordinator);
        DurableMutationAspect aspect = new DurableMutationAspect(
                store, snapshotMapper, aggregates, properties, roomCoordinator);

        Room room = new Room("room-capture", "Capture Room", "Host", 6, 10, 20, 1000, null);
        room.addPlayer("Host");
        room.addPlayer("Guest");
        Game game = new Game("room-capture",
                List.of(new Player("Host", "p1", 1000), new Player("Guest", "p2", 1000)),
                6, 10, 20);

        when(aggregates.find("room-capture")).thenReturn(new RoomAggregate(room, game));

        AtomicBoolean lockHeldOnCapture = new AtomicBoolean();
        AtomicBoolean lockHeldOnSerialize = new AtomicBoolean();
        when(snapshotMapper.captureSnapshot(room, game)).thenAnswer(inv -> {
            lockHeldOnCapture.set(roomCoordinator.isHeldByCurrentThread("room-capture"));
            return realMapper.captureSnapshot(room, game);
        });
        when(snapshotMapper.serialize(any(RoomSnapshot.class))).thenAnswer(inv -> {
            lockHeldOnSerialize.set(roomCoordinator.isHeldByCurrentThread("room-capture"));
            return realMapper.serialize(inv.getArgument(0));
        });

        ProceedingJoinPoint joinPoint = mutationPoint("room-capture", () -> "done");
        aspect.persist(joinPoint);

        assertTrue(lockHeldOnCapture.get(), "Room lock must be held during snapshot capture");
        assertTrue(lockHeldOnSerialize.get(), "Room lock must be held during snapshot serialization");
    }

    @Test
    void callbackFailureDoesNotRejectAcceptedMutationOrSuppressNextCallback() throws Throwable {
        AtomicBoolean unavailable = new AtomicBoolean(false);
        EncryptedWalStore store = failingStore(unavailable);
        DurableMutationAspect aspect = aspectForRoom(store, "room-callback");
        when(snapshotMapper.serialize(any(RoomSnapshot.class))).thenReturn(new byte[]{1});
        List<String> effects = new ArrayList<>();
        ProceedingJoinPoint joinPoint = mutationPoint("room-callback", () -> {
            PostSaveActions.runAfterSave(() -> {
                throw new IllegalStateException("broker unavailable");
            });
            PostSaveActions.runAfterSave(() -> effects.add("scheduled"));
            return "accepted";
        });

        assertEquals("accepted", aspect.persist(joinPoint));
        assertEquals(List.of("scheduled"), effects);
        assertTrue(store.isHealthy());
    }

    @Test
    @DisplayName("When a mutation throws, in-memory aggregate is restored from its pre-mutation snapshot and connection state is preserved")
    void failedMutationRestoresPreMutationSnapshotAndConnectionState() throws Throwable {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring, roomCoordinator);
        DurableMutationAspect aspect = new DurableMutationAspect(
                store, realMapper, aggregates, properties, roomCoordinator);

        String roomId = "room-rollback-test";
        Room originalRoom = new Room(roomId, "Rollback Room", "Alice", 6, 10, 20, 1000, null);
        originalRoom.addPlayer("Alice");
        originalRoom.addPlayer("Bob");
        originalRoom.setPlayerConnected("Alice", true);
        originalRoom.setPlayerConnected("Bob", false);
        originalRoom.setAbsenceDeadline("Bob", 1_700_000_045_000L);

        Player alice = new Player("Alice", "p1", 1000);
        Player bob = new Player("Bob", "p2", 1000);
        Game originalGame = new Game(roomId, List.of(alice, bob), 6, 10, 20);

        byte[] committedPayload = realMapper.serialize(realMapper.captureSnapshot(originalRoom, originalGame));
        withRoomLock(roomCoordinator, roomId, () -> {
            store.checkpoint(roomId, committedPayload);
            return null;
        });

        when(aggregates.find(roomId)).thenReturn(new RoomAggregate(originalRoom, originalGame));

        ProceedingJoinPoint joinPoint = mutationPoint(roomId, () -> {
            bob.doAction(PlayerAction.BET, 500);
            originalRoom.setPlayerConnected("Alice", false);
            originalRoom.setPlayerConnected("Bob", true);
            originalRoom.setAbsenceDeadline("Bob", null);
            throw new RuntimeException("Simulated domain action failure");
        });

        assertThrows(RuntimeException.class, () -> aspect.persist(joinPoint));
        assertEquals(500, bob.getChips(), "Live dirty instance was mutated before rollback");

        ArgumentCaptor<RoomAggregate> captor = ArgumentCaptor.forClass(RoomAggregate.class);
        verify(aggregates).replace(captor.capture());
        verify(aggregates, never()).remove(roomId);

        RoomAggregate rolledBack = captor.getValue();
        Player restoredBob = rolledBack.game().findPlayerByName("Bob");
        assertEquals(1000, restoredBob.getChips());
        assertEquals(0, restoredBob.getCurrentBet());
        assertTrue(rolledBack.room().isPlayerConnected("Alice"));
        assertFalse(rolledBack.room().isPlayerConnected("Bob"));
        assertEquals(1_700_000_045_000L, rolledBack.room().getAbsenceDeadline("Bob"));

        byte[] walBytes = withRoomLock(roomCoordinator, roomId, () -> store.recoverLatest(roomId).orElseThrow());
        assertArrayEquals(committedPayload, walBytes);
    }

    @Test
    @DisplayName("When creation fails and no pre-mutation room existed, aggregate is evicted")
    void failedCreationEvictsAggregateWhenNoPreMutationRoomExists() throws Throwable {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring, roomCoordinator);
        DurableMutationAspect aspect = new DurableMutationAspect(
                store, snapshotMapper, aggregates, properties, roomCoordinator);

        String roomId = "room-uncommitted-fail";
        when(aggregates.find(roomId)).thenReturn(null);

        ProceedingJoinPoint joinPoint = mutationPoint(roomId, () -> {
            throw new IllegalStateException("Creation failure inside joinPoint.proceed()");
        });

        assertThrows(IllegalStateException.class, () -> aspect.persist(joinPoint));

        verify(aggregates).remove(roomId);
        verify(aggregates, never()).replace(any());
        verify(snapshotMapper, never()).deserialize(any());
    }

    @Test
    @DisplayName("When no pre-mutation room exists, old WAL records are not restored")
    void failedMutationDoesNotResurrectOldWalWithoutPreMutationRoom() throws Throwable {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring, roomCoordinator);
        DurableMutationAspect aspect = new DurableMutationAspect(
                store, snapshotMapper, aggregates, properties, roomCoordinator);

        String roomId = "room-recovery-fail";
        byte[] payload = new byte[]{1, 2, 3};
        withRoomLock(roomCoordinator, roomId, () -> {
            store.checkpoint(roomId, payload);
            return null;
        });

        ProceedingJoinPoint joinPoint = mutationPoint(roomId, () -> {
            throw new RuntimeException("Domain error");
        });

        assertThrows(RuntimeException.class, () -> aspect.persist(joinPoint));

        verify(aggregates).remove(roomId);
    }

    @Test
    @DisplayName("Subsequent mutation failure rolls back to accepted pending save state during storage outage")
    void failedMutationRollsBackToAcceptedPendingSaveState() throws Throwable {
        String roomId = "room-pending-rollback";
        AtomicBoolean storageOutage = new AtomicBoolean(false);
        EncryptedWalStore store = failingStore(storageOutage);
        DurableMutationAspect aspect = new DurableMutationAspect(
                store, realMapper, aggregates, properties, roomCoordinator);

        Room room = new Room(roomId, "Room", "Host", 6, 10, 20, 1000, null);
        room.addPlayer("Host");
        room.addPlayer("Guest");
        Player host = new Player("Host", "p1", 1000);
        Player guest = new Player("Guest", "p2", 1000);
        Game game = new Game(roomId, List.of(host, guest), 6, 10, 20);

        byte[] initialPayload = realMapper.serialize(realMapper.captureSnapshot(room, game));
        withRoomLock(roomCoordinator, roomId, () -> {
            store.checkpoint(roomId, initialPayload);
            return null;
        });

        AtomicReference<RoomAggregate> currentAggregate = new AtomicReference<>(new RoomAggregate(room, game));
        when(aggregates.find(roomId)).thenAnswer(inv -> currentAggregate.get());

        // Move 1: domain mutates state and succeeds, but storage fails during commit
        storageOutage.set(true);
        ProceedingJoinPoint joinPoint1 = mutationPoint(roomId, () -> {
            host.doAction(PlayerAction.BET, 200);
            return "accepted-move";
        });
        assertEquals("accepted-move", aspect.persist(joinPoint1));
        assertEquals(800, host.getChips());
        assertEquals(200, host.getCurrentBet());

        // Move 2: domain mutates state further and throws
        ProceedingJoinPoint joinPoint2 = mutationPoint(roomId, () -> {
            guest.doAction(PlayerAction.BET, 400);
            throw new RuntimeException("Second move failed");
        });

        assertThrows(RuntimeException.class, () -> aspect.persist(joinPoint2));

        ArgumentCaptor<RoomAggregate> captor = ArgumentCaptor.forClass(RoomAggregate.class);
        verify(aggregates).replace(captor.capture());
        verify(aggregates, never()).remove(roomId);

        RoomAggregate rolledBack = captor.getValue();
        Player restoredHost = rolledBack.game().findPlayerByName("Host");
        Player restoredGuest = rolledBack.game().findPlayerByName("Guest");
        assertEquals(800, restoredHost.getChips(), "Move 1 accepted chips must be preserved");
        assertEquals(200, restoredHost.getCurrentBet(), "Move 1 accepted bet must be preserved");
        assertEquals(1000, restoredGuest.getChips(), "Move 2 failed bet must be rolled back");
        assertEquals(0, restoredGuest.getCurrentBet(), "Move 2 failed bet must be rolled back");

        byte[] walBytes = withRoomLock(roomCoordinator, roomId, () -> store.recoverLatest(roomId).orElseThrow());
        assertArrayEquals(initialPayload, walBytes, "WAL still holds pre-outage initial payload until retry");
    }

    @Test
    @DisplayName("When serialization fails on accepted mutation, aggregate is preserved and subsequent mutation succeeds")
    void serializationFailurePreservesAcceptedStateWithoutCorruption() throws Throwable {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring, roomCoordinator);
        DurableMutationAspect aspect = aspectForRoom(store, "room-serialize-safe");

        AtomicInteger moves = new AtomicInteger();
        List<String> effects = new ArrayList<>();
        ProceedingJoinPoint joinPoint1 = mutationPoint("room-serialize-safe", () -> {
            int move = moves.incrementAndGet();
            PostSaveActions.runAfterSave(() -> effects.add("effect-" + move));
            return move;
        });

        when(snapshotMapper.serialize(any(RoomSnapshot.class)))
                .thenThrow(new PersistenceException("transient serialization failure"))
                .thenReturn(new byte[]{7, 8, 9});

        assertEquals(1, aspect.persist(joinPoint1));
        assertEquals(List.of("effect-1"), effects, "Accepted mutation effects are executed");

        ProceedingJoinPoint joinPoint2 = mutationPoint("room-serialize-safe", () -> {
            int move = moves.incrementAndGet();
            PostSaveActions.runAfterSave(() -> effects.add("effect-" + move));
            return move;
        });

        assertEquals(2, aspect.persist(joinPoint2));
        assertEquals(2, moves.get());
        assertEquals(List.of("effect-1", "effect-2"), effects);
        verify(aggregates, never()).remove("room-serialize-safe");
    }

    @Test
    @DisplayName("When lobby room mutation fails, room is restored and game is evicted without destroying room")
    void lobbyMutationFailureEvictsGameAndPreservesLobbyRoom() throws Throwable {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring, roomCoordinator);
        DurableMutationAspect aspect = new DurableMutationAspect(
                store, realMapper, aggregates, properties, roomCoordinator);

        String roomId = "room-lobby-rollback";
        Room lobbyRoom = new Room(roomId, "Lobby Room", "Alice", 6, 10, 20, 1000, null);
        lobbyRoom.addPlayer("Alice");
        when(aggregates.find(roomId)).thenReturn(new RoomAggregate(lobbyRoom, null));

        ProceedingJoinPoint joinPoint = mutationPoint(roomId, () -> {
            lobbyRoom.addPlayer("DirtyGuest");
            throw new RuntimeException("Lobby mutation failure");
        });

        assertThrows(RuntimeException.class, () -> aspect.persist(joinPoint));

        ArgumentCaptor<RoomAggregate> captor = ArgumentCaptor.forClass(RoomAggregate.class);
        verify(aggregates).replace(captor.capture());
        verify(aggregates, never()).remove(roomId);
        assertThat(captor.getValue().room().getPlayers()).containsExactly("Alice");
        assertThat(captor.getValue().game()).isNull();
    }

    @Test
    void failedDeletionSaveRetainsAndRetriesDeletionTombstone() throws Throwable {
        String roomId = "room-deletion-retry";
        AtomicBoolean failCommit = new AtomicBoolean(true);
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring, (point, id) -> {
            if (failCommit.get() && point == WalFaultPoint.BEFORE_COMMIT_WRITE) {
                throw new PersistenceException("injected deletion commit failure");
            }
        }, roomCoordinator);
        when(aggregates.find(roomId)).thenReturn(null);
        DurableMutationAspect aspect = new DurableMutationAspect(
                store, realMapper, aggregates, properties, roomCoordinator);
        ProceedingJoinPoint joinPoint = mutationPoint(roomId, () -> null);

        aspect.persist(joinPoint);
        assertEquals(1, store.unsavedRoomCount(), "The failed deletion remains marked unsaved");

        failCommit.set(false);
        aspect.retryPendingSaves();

        assertEquals(0, store.unsavedRoomCount());
        assertTrue(withRoomLock(roomCoordinator, roomId, () -> store.recoverLatest(roomId)).isEmpty(),
                "The retry checkpoints and removes the deleted room's WAL");
        verify(joinPoint).proceed();
    }

    @Test
    void prepareFailureBeforeRejectedMutationQueuesRetryForExistingRoom() throws Throwable {
        String roomId = "room-prepare-rejected-retry";
        AtomicBoolean failPrepare = new AtomicBoolean(true);
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring, (point, id) -> {
            if (failPrepare.get() && point == WalFaultPoint.BEFORE_PREPARE_WRITE) {
                throw new PersistenceException("injected prepare outage");
            }
        }, roomCoordinator);
        Room existingRoom = new Room(roomId, "Retry Prepare Room", "Alice", 6, 10, 20, 1000, null);
        existingRoom.addPlayer("Alice");
        when(aggregates.find(roomId)).thenReturn(new RoomAggregate(existingRoom, null));

        DurableMutationAspect aspect = new DurableMutationAspect(
                store, realMapper, aggregates, properties, roomCoordinator);
        ProceedingJoinPoint joinPoint = mutationPoint(roomId, () -> {
            throw new IllegalStateException("rejected mutation");
        });

        assertThrows(IllegalStateException.class, () -> aspect.persist(joinPoint));
        verify(aggregates).replace(any(RoomAggregate.class));
        assertFalse(store.isHealthy());
        assertEquals(1, store.unsavedRoomCount());

        failPrepare.set(false);
        aspect.retryPendingSaves();

        assertTrue(store.isHealthy(), "Retry checkpoints and clears failedRooms for the existing room");
        assertEquals(0, store.unsavedRoomCount());
        assertTrue(withRoomLock(roomCoordinator, roomId, () -> store.recoverLatest(roomId)).isPresent());
    }

    @Test
    void retryPendingSavesSkipsRoomWhileLockedAndSucceedsOnceUnlocked() throws Throwable {
        String roomId = "room-retry-skip-locked";
        AtomicBoolean failCommit = new AtomicBoolean(true);
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring, (point, id) -> {
            if (failCommit.get() && point == WalFaultPoint.BEFORE_COMMIT_WRITE) {
                throw new PersistenceException("injected commit outage");
            }
        }, roomCoordinator);
        Room room = new Room(roomId, "Locked Retry Room", "Alice", 6, 10, 20, 1000, null);
        room.addPlayer("Alice");
        when(aggregates.find(roomId)).thenReturn(new RoomAggregate(room, null));

        DurableMutationAspect aspect = new DurableMutationAspect(
                store, realMapper, aggregates, properties, roomCoordinator);
        aspect.persist(mutationPoint(roomId, () -> "ok"));
        assertEquals(1, store.unsavedRoomCount());

        failCommit.set(false);
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = executor.submit(() -> {
                try (LockLease _ = roomCoordinator.acquire(roomId)) {
                    lockAcquired.countDown();
                    releaseLock.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(lockAcquired.await(5, TimeUnit.SECONDS));

            aspect.retryPendingSaves();
            assertEquals(1, store.unsavedRoomCount(), "Locked room must be skipped without blocking");

            releaseLock.countDown();
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        aspect.retryPendingSaves();
        assertEquals(0, store.unsavedRoomCount());
        assertTrue(store.isHealthy());
    }

    @Test
    void automaticWalCompactionTriggersWhenRecordCountReachesThreshold() throws Throwable {
        String roomId = "room-auto-compact";
        PersistenceProperties compactProps = new PersistenceProperties(
                true, tempDir, "current", Map.of(), 6, 16L * 1024 * 1024, 5000);
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        AtomicInteger compactionCount = new AtomicInteger();
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring, (point, id) -> {
            if (point == WalFaultPoint.BEFORE_COMPACTION_REPLACE) {
                compactionCount.incrementAndGet();
            }
        }, roomCoordinator);

        Room room = new Room(roomId, "Compaction Room", "Alice", 6, 10, 20, 1000, null);
        room.addPlayer("Alice");
        when(aggregates.find(roomId)).thenReturn(new RoomAggregate(room, null));

        DurableMutationAspect aspect = new DurableMutationAspect(
                store, realMapper, aggregates, compactProps, roomCoordinator);

        // Each save appends a PREPARE and a COMMIT record; the threshold of 6 is reached on save 3.
        aspect.persist(mutationPoint(roomId, () -> "save-1"));
        long sizeAfterFirstSave = withRoomLock(roomCoordinator, roomId, () -> store.walSize(roomId));

        aspect.persist(mutationPoint(roomId, () -> "save-2"));
        long sizeAfterSecondSave = withRoomLock(roomCoordinator, roomId, () -> store.walSize(roomId));
        assertThat(sizeAfterSecondSave).isGreaterThan(sizeAfterFirstSave);
        assertThat(compactionCount.get()).isZero();

        aspect.persist(mutationPoint(roomId, () -> {
            room.addPlayer("Bob");
            return "save-3";
        }));
        long sizeAfterCompaction = withRoomLock(roomCoordinator, roomId, () -> store.walSize(roomId));
        assertThat(compactionCount.get()).isEqualTo(1);
        assertThat(sizeAfterCompaction).isLessThan(sizeAfterSecondSave);
        RecoveredRoom recovered = withRoomLock(roomCoordinator, roomId,
                () -> realMapper.deserialize(store.recoverLatest(roomId).orElseThrow()));
        assertThat(recovered.room().getPlayers()).containsExactly("Alice", "Bob");
    }

    @Test
    void rollbackRestorationFailureEvictsRoomAndMarksStoreUnhealthy() throws Throwable {
        String roomId = "room-restore-fail";
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring, roomCoordinator);
        Room room = new Room(roomId, "Restore Fail Room", "Alice", 6, 10, 20, 1000, null);
        room.addPlayer("Alice");
        when(aggregates.find(roomId)).thenReturn(new RoomAggregate(room, null));
        when(snapshotMapper.restoreSnapshot(any(RoomSnapshot.class)))
                .thenThrow(new PersistenceException("corrupted pre-mutation snapshot"));

        DurableMutationAspect aspect = new DurableMutationAspect(
                store, snapshotMapper, aggregates, properties, roomCoordinator);
        ProceedingJoinPoint joinPoint = mutationPoint(roomId, () -> {
            throw new IllegalStateException("domain failure");
        });

        assertThrows(IllegalStateException.class, () -> aspect.persist(joinPoint));

        verify(aggregates).remove(roomId);
        verify(aggregates, never()).replace(any());
        assertFalse(store.isHealthy(roomId));
        assertEquals(1, store.unsavedRoomCount());
    }

    @Test
    void preMutationCaptureFailureStillAllowsAcceptedMutationAndQueuesRetry() throws Throwable {
        String roomId = "room-pre-capture-fail";
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring, roomCoordinator);
        Room room = new Room(roomId, "Pre-Capture Fail Room", "Alice", 6, 10, 20, 1000, null);
        room.addPlayer("Alice");
        when(aggregates.find(roomId)).thenReturn(new RoomAggregate(room, null));

        RoomSnapshot validSnapshot = realMapper.captureSnapshot(room, null);
        when(snapshotMapper.captureSnapshot(room, null))
                .thenThrow(new PersistenceException("pre-mutation capture failed"))
                .thenReturn(validSnapshot);
        when(snapshotMapper.serialize(validSnapshot)).thenReturn(realMapper.serialize(validSnapshot));

        DurableMutationAspect aspect = new DurableMutationAspect(
                store, snapshotMapper, aggregates, properties, roomCoordinator);

        assertEquals("accepted", aspect.persist(mutationPoint(roomId, () -> "accepted")));
        assertTrue(store.isHealthy(roomId), "Post-mutation checkpoint heals the pre-mutation capture failure");
        assertEquals(0, store.unsavedRoomCount());
    }

    @Test
    void preMutationCaptureFailureFollowedByDomainFailureEvictsAggregateToPreventDirtyState() throws Throwable {
        String roomId = "room-pre-capture-and-domain-fail";
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring, roomCoordinator);
        Room room = new Room(roomId, "Double Fail Room", "Alice", 6, 10, 20, 1000, null);
        room.addPlayer("Alice");
        when(aggregates.find(roomId)).thenReturn(new RoomAggregate(room, null));
        when(snapshotMapper.captureSnapshot(room, null))
                .thenThrow(new PersistenceException("pre-mutation capture failed"));

        DurableMutationAspect aspect = new DurableMutationAspect(
                store, snapshotMapper, aggregates, properties, roomCoordinator);
        ProceedingJoinPoint joinPoint = mutationPoint(roomId, () -> {
            throw new IllegalStateException("domain rejected");
        });

        assertThrows(IllegalStateException.class, () -> aspect.persist(joinPoint));

        verify(aggregates).remove(roomId);
        verify(aggregates, never()).replace(any());
        assertFalse(store.isHealthy(roomId));
    }

    @Test
    void storeFailuresAreRecordedAndRetryHealsStore() throws Throwable {
        String roomId = "room-store-records-once";
        AtomicReference<WalFaultPoint> outage = new AtomicReference<>(WalFaultPoint.BEFORE_COMMIT_WRITE);
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring, (point, id) -> {
            if (point == outage.get()) {
                throw new PersistenceException("injected " + point);
            }
        }, roomCoordinator);
        Room existingRoom = new Room(roomId, "Record Once Room", "Alice", 6, 10, 20, 1000, null);
        existingRoom.addPlayer("Alice");
        when(aggregates.find(roomId)).thenReturn(new RoomAggregate(existingRoom, null));
        DurableMutationAspect aspect = new DurableMutationAspect(
                store, realMapper, aggregates, properties, roomCoordinator);

        assertEquals("ok", aspect.persist(mutationPoint(roomId, () -> "ok")));
        assertEquals(1, store.unsavedRoomCount());
        assertFalse(store.isHealthy(roomId));

        outage.set(WalFaultPoint.BEFORE_PREPARE_WRITE);
        aspect.retryPendingSaves();
        assertEquals(1, store.unsavedRoomCount());
        assertFalse(store.isHealthy(roomId));

        outage.set(null);
        aspect.retryPendingSaves();
        assertEquals(0, store.unsavedRoomCount());
        assertTrue(store.isHealthy());
        assertTrue(withRoomLock(roomCoordinator, roomId, () -> store.recoverLatest(roomId)).isPresent());
    }

    @Test
    void missingRoomRejectedMutationDuringPrepareOutageDoesNotDegradeStore() throws Throwable {
        String roomId = "room-missing-prepare-outage";
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring, (point, id) -> {
            if (point == WalFaultPoint.BEFORE_PREPARE_WRITE
                    || point == WalFaultPoint.BEFORE_COMMIT_WRITE
                    || point == WalFaultPoint.BEFORE_FLUSH
                    || point == WalFaultPoint.BEFORE_DELETE) {
                throw new PersistenceException("injected write outage");
            }
        }, roomCoordinator);
        when(aggregates.find(roomId)).thenReturn(null);

        DurableMutationAspect aspect = new DurableMutationAspect(
                store, realMapper, aggregates, properties, roomCoordinator);
        ProceedingJoinPoint joinPoint = mutationPoint(roomId, () -> {
            throw new IllegalStateException("missing room");
        });

        assertThrows(IllegalStateException.class, () -> aspect.persist(joinPoint));
        assertTrue(store.isHealthy());
        assertEquals(0, store.unsavedRoomCount());
    }

    @Test
    @DisplayName("Room B uses ordinary append and deletes normally even when room A has a write failure")
    void otherRoomUsesOrdinaryAppendAndDeletesNormallyWhenAnotherRoomHasWriteFailure() throws Throwable {
        AtomicReference<String> failRoom = new AtomicReference<>();
        List<String> preparedRooms = new ArrayList<>();
        List<String> deletedRooms = new ArrayList<>();
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring, (point, roomId) -> {
            if (point == WalFaultPoint.BEFORE_PREPARE_WRITE) {
                preparedRooms.add(roomId);
            }
            if (point == WalFaultPoint.BEFORE_DELETE) {
                deletedRooms.add(roomId);
            }
            if (roomId.equals(failRoom.get()) && point == WalFaultPoint.BEFORE_COMMIT_WRITE) {
                throw new PersistenceException("injected outage for " + roomId);
            }
        }, roomCoordinator);

        Room roomA = new Room("room-A", "Room A", "Alice", 6, 10, 20, 1000, null);
        roomA.addPlayer("Alice");
        Room roomB = new Room("room-B", "Room B", "Bob", 6, 10, 20, 1000, null);
        roomB.addPlayer("Bob");
        AtomicBoolean roomBDeleted = new AtomicBoolean(false);
        when(aggregates.find("room-A")).thenReturn(new RoomAggregate(roomA, null));
        when(aggregates.find("room-B")).thenAnswer(inv -> roomBDeleted.get() ? null : new RoomAggregate(roomB, null));

        DurableMutationAspect aspect = new DurableMutationAspect(
                store, realMapper, aggregates, properties, roomCoordinator);

        aspect.persist(mutationPoint("room-A", () -> "init-a"));
        aspect.persist(mutationPoint("room-B", () -> "init-b"));
        preparedRooms.clear();

        failRoom.set("room-A");
        aspect.persist(mutationPoint("room-A", () -> "accepted-a"));
        failRoom.set(null);
        assertFalse(store.isHealthy());
        assertEquals(1, store.unsavedRoomCount());

        long roomBSizeBeforeAppend = withRoomLock(roomCoordinator, "room-B", () -> store.walSize("room-B"));
        aspect.persist(mutationPoint("room-B", () -> "accepted-b"));
        long roomBSizeAfterAppend = withRoomLock(roomCoordinator, "room-B", () -> store.walSize("room-B"));
        assertThat(roomBSizeAfterAppend).isGreaterThan(roomBSizeBeforeAppend);

        aspect.persist(mutationPoint("room-B", () -> {
            roomBDeleted.set(true);
            return null;
        }));
        assertThat(deletedRooms).contains("room-B");
        assertFalse(Files.exists(tempDir.resolve("room-B.wal")));
        assertTrue(withRoomLock(roomCoordinator, "room-B", () -> store.recoverLatest("room-B")).isEmpty());
        assertEquals(1, store.unsavedRoomCount());
    }

    private EncryptedWalStore failingStore(AtomicBoolean unavailable) {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        return new EncryptedWalStore(tempDir, keyring, (point, roomId) -> {
            if (unavailable.get()) {
                throw new PersistenceException("injected storage outage");
            }
        }, roomCoordinator);
    }

    private DurableMutationAspect aspectForRoom(EncryptedWalStore store, String roomId) {
        lenient().when(aggregates.find(roomId)).thenReturn(new RoomAggregate(mock(Room.class), null));
        return new DurableMutationAspect(
                store, snapshotMapper, aggregates, properties, roomCoordinator);
    }

    private DurableMutationAspect aspectWithStorage() {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        return new DurableMutationAspect(new EncryptedWalStore(tempDir, keyring, roomCoordinator),
                snapshotMapper, aggregates, properties, roomCoordinator);
    }

    private DurableMutationAspect aspectWithoutStorage() {
        return new DurableMutationAspect(null, snapshotMapper, aggregates, properties, roomCoordinator);
    }

    private ProceedingJoinPoint pointFor(String methodName, Class<?> parameterType, Object argument) throws Exception {
        return pointFor(methodName, new Class<?>[]{parameterType}, argument);
    }

    private ProceedingJoinPoint pointFor(String methodName, Class<?>[] parameterTypes, Object... arguments)
            throws Exception {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        lenient().when(signature.getMethod()).thenReturn(TestTarget.class.getMethod(methodName, parameterTypes));
        lenient().when(joinPoint.getSignature()).thenReturn(signature);
        lenient().when(joinPoint.getArgs()).thenReturn(arguments);
        return joinPoint;
    }

    private ProceedingJoinPoint mutationPoint(String roomId, MutationBody body) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(TestTarget.class.getMethod("validMutation", String.class));
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{roomId});
        lenient().when(joinPoint.proceed()).thenAnswer(invocation -> body.run());
        return joinPoint;
    }

    @FunctionalInterface
    private interface MutationBody {
        Object run() throws Throwable;
    }
}
