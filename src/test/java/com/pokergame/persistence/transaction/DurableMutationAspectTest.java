package com.pokergame.persistence.transaction;

import com.pokergame.config.RoomTimingProperties;
import com.pokergame.enums.PlayerAction;
import com.pokergame.model.Room;
import com.pokergame.persistence.RoomAggregate;
import com.pokergame.persistence.RoomAggregates;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.pokergame.testutil.TestRoomLocks.withRoomLock;
import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.pokergame.util.LockLease;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.persistence.wal.EncryptionKeyring;
import com.pokergame.persistence.wal.WalFaultPoint;
import com.pokergame.persistence.snapshot.RoomSnapshotMapper;
import com.pokergame.persistence.snapshot.RoomSnapshot;
import com.pokergame.persistence.snapshot.RecoveredRoom;
import com.pokergame.util.MdcKeys;
import com.pokergame.util.RoomCoordinator;
import org.junit.jupiter.api.DisplayName;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.persistence.config.PersistenceProperties;
import com.pokergame.persistence.PersistenceException;
import java.util.concurrent.Callable;
import org.mockito.Mockito;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

/** Tests durable mutation aspect transaction boundaries and room ID parameter resolution. */
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

    @BeforeEach
    void setUp() {
        properties = new PersistenceProperties(false, Path.of("data", "poker-wal"), null, Map.of(), 1000, 16L * 1024 * 1024, 5000);
        roomCoordinator = new RoomCoordinator();
        RoomSnapshot mockImage = Mockito.mock(RoomSnapshot.class);
        Mockito.lenient().when(snapshotMapper.captureStateImage(any(), any())).thenReturn(mockImage);
        Mockito.lenient().when(snapshotMapper.serialize(any(RoomSnapshot.class))).thenReturn(new byte[]{1, 2, 3});
    }

    @AfterEach
    void tearDown() {
        PostSaveActions.clear();
    }

    /**
     * Protects the contract that nested durable mutations reject calls crossing room boundaries.
     */
    @Test
    void nestedDurableMutationCrossRoomBoundaryThrowsPersistenceException() throws Throwable {
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

    /**
     * Commit failure retains accepted effects and later commands without replay.
     */
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

    /** A blank or null value in the marked parameter cannot name a room, so nothing runs. */
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

    /** A durable method that marks neither {@code @RoomId} nor {@code @GameId} is a coding error and never runs. */
    @Test
    void methodWithNeitherRoomIdNorGameIdParameterFails() throws Throwable {
        for (DurableMutationAspect aspect : List.of(aspectWithStorage(), aspectWithoutStorage())) {
            ProceedingJoinPoint joinPoint = pointFor("unmarkedMutation", String.class, "room-6");

            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> aspect.persist(joinPoint));
            assertThat(ex.getMessage()).contains("unmarkedMutation").contains("@RoomId").contains("@GameId");
            verify(joinPoint, never()).proceed();
        }
    }

    /** A durable method that marks both {@code @RoomId} and {@code @GameId} is ambiguous and never runs. */
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

    /** A {@code @GameId} parameter in any position names the room that is locked. */
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

    /** The room ID is in the logging context while the mutation runs, with or without storage, and restored after. */
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

    /** A prepare outage must still allow the domain mutation and its effects. */
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

    /** Serialization failure leaves gameplay available and releases accepted effects. */
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
        when(snapshotMapper.serialize(any(RoomSnapshot.class))).thenThrow(new PersistenceException("injected serialization failure"));

        assertEquals(1, aspect.persist(joinPoint));
        assertEquals(List.of("effect-1"), effects);
        assertEquals(2, aspect.persist(joinPoint));
        assertEquals(2, moves.get());
    }

    /** Persistence fallback must not turn a rejected domain mutation into a successful command. */
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
        verify(snapshotMapper, never()).serialize(any(RoomSnapshot.class));
    }

    @Test
    @DisplayName("captureState holds the shared room coordinator through capture and encoding")
    void captureState_UsesSharedRoomCoordinatorForCaptureAndEncoding() throws Throwable {

        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring, roomCoordinator);
        RoomSnapshotMapper realMapper = spy(new RoomSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build()));
        DurableMutationAspect aspect = new DurableMutationAspect(
                store, realMapper, aggregates, properties, roomCoordinator);

        Room room = spy(new Room("room-capture", "Capture Room", "Host", 6, 10, 20, 1000, null));
        room.addPlayer("Host");
        room.addPlayer("Guest");
        Game game = spy(new Game("room-capture",
                List.of(new Player("Host", "p1", 1000), new Player("Guest", "p2", 1000)),
                6, 10, 20));

        when(aggregates.find("room-capture")).thenReturn(new RoomAggregate(room, game));

        doAnswer(inv -> {
            assertTrue(roomCoordinator.isHeldByCurrentThread("room-capture"),
                    "Room state must be read under the room coordination boundary");
            return inv.callRealMethod();
        }).when(room).getPlayersWithJoinTimeSnapshot();

        doAnswer(inv -> {
            assertTrue(roomCoordinator.isHeldByCurrentThread("room-capture"),
                    "Game state must be read under the room coordination boundary");
            return inv.callRealMethod();
        }).when(game).getPlayers();

        doAnswer(inv -> {
            assertTrue(roomCoordinator.isHeldByCurrentThread("room-capture"),
                    "Encoding must retain the established room coordination boundary");
            return inv.callRealMethod();
        }).when(realMapper).serialize(any(RoomSnapshot.class));

        ProceedingJoinPoint joinPoint = mutationPoint("room-capture", () -> "done");
        aspect.persist(joinPoint);

        verify(realMapper, times(2)).captureStateImage(room, game);
        verify(realMapper).serialize(any(RoomSnapshot.class));
    }

    /** An accepted action's timer must still run if its preceding broadcast callback fails. */
    @Test
    void callbackFailureDoesNotRejectAcceptedMutationOrSuppressNextCallback() throws Throwable {
        AtomicBoolean unavailable = new AtomicBoolean(false);
        EncryptedWalStore store = failingStore(unavailable);
        DurableMutationAspect aspect = aspectForRoom(store, "room-callback");
        when(snapshotMapper.serialize(any(RoomSnapshot.class))).thenReturn(new byte[]{1});
        List<String> effects = new ArrayList<>();
        ProceedingJoinPoint joinPoint = mutationPoint("room-callback", () -> {
            PostSaveActions.runAfterSave(() -> { throw new IllegalStateException("broker unavailable"); });
            PostSaveActions.runAfterSave(() -> effects.add("scheduled"));
            return "accepted";
        });

        assertEquals("accepted", aspect.persist(joinPoint));
        assertEquals(List.of("scheduled"), effects);
        assertTrue(store.isHealthy());
    }

    /**
     * Mutation failure restores the independent pre-mutation image, without reading the WAL.
     */
    @Test
    @DisplayName("When a mutation throws, in-memory aggregate is restored from its pre-mutation image")
    void failedMutationRestoresPreMutationImage() throws Throwable {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring, roomCoordinator);
        DurableMutationAspect aspect = new DurableMutationAspect(
                store, snapshotMapper, aggregates, properties, roomCoordinator);

        String roomId = "room-rollback-test";
        Room originalRoom = new Room(roomId, "Rollback Room", "Alice", 6, 10, 20, 1000, null);
        originalRoom.addPlayer("Alice");
        originalRoom.addPlayer("Bob");

        Player alice = new Player("Alice", "p1", 1000);
        Player bob = new Player("Bob", "p2", 1000);
        Game originalGame = new Game(roomId, List.of(alice, bob), 6, 10, 20);

        byte[] committedPayload = new byte[]{42, 43, 44};
        withRoomLock(roomCoordinator, roomId, () -> {
            store.checkpoint(roomId, committedPayload);
            return null;
        });

        when(snapshotMapper.restoreStateImage(any(RoomSnapshot.class)))
                .thenReturn(new RecoveredRoom(originalRoom, originalGame, false));

        lenient().when(aggregates.find(roomId)).thenReturn(new RoomAggregate(originalRoom, originalGame));

        ProceedingJoinPoint joinPoint = mutationPoint(roomId, () -> {
            // Simulate dirty uncommitted mutation before throwing
            bob.doAction(PlayerAction.BET, 500);
            throw new RuntimeException("Simulated domain action failure");
        });

        assertThrows(RuntimeException.class, () -> aspect.persist(joinPoint));

        // Verify that the dirty mutation was rolled back by replacing the live room and game
        verify(aggregates).replace(new RoomAggregate(originalRoom, originalGame));
        verify(aggregates, never()).remove(roomId);
    }

    /**
     * Mutation failure evicts a room that did not exist before the attempted creation.
     */
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

        // A failed creation must not leave an uncommitted aggregate in the active registry
        verify(aggregates).remove(roomId);
        verify(aggregates, never()).replace(any());
        verify(snapshotMapper, never()).deserialize(any());
    }

    /**
     * Absent pre-mutation state must not resurrect an obsolete WAL room.
     */
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

        // No pre-mutation room exists; stale WAL must not resurrect an old membership.

        ProceedingJoinPoint joinPoint = mutationPoint(roomId, () -> {
            throw new RuntimeException("Domain error");
        });

        assertThrows(RuntimeException.class, () -> aspect.persist(joinPoint));

        // Verify no old WAL room is resurrected into the live registry
        verify(aggregates).remove(roomId);
    }

    /**
     * Subsequent mutation failure restores accepted in-memory pending save state.
     */
    @Test
    @DisplayName("Subsequent mutation failure rolls back to accepted pending save image")
    void failedMutationRollsBackToAcceptedPendingSaveState() throws Throwable {
        AtomicBoolean storageOutage = new AtomicBoolean(false);
        EncryptedWalStore store = failingStore(storageOutage);
        DurableMutationAspect aspect = aspectForRoom(store, "room-pending-rollback");

        Room room = new Room("room-pending-rollback", "Room", "Host", 6, 10, 20, 1000, null);
        room.addPlayer("Host");
        room.addPlayer("Guest");
        Player host = new Player("Host", "p1", 1000);
        Player guest = new Player("Guest", "p2", 1000);
        Game game = new Game("room-pending-rollback", List.of(host, guest), 6, 10, 20);

        when(aggregates.find("room-pending-rollback")).thenReturn(new RoomAggregate(room, game));

        byte[] acceptedBytes = new byte[]{50, 51, 52};
        RoomSnapshot capturedImage = mock(RoomSnapshot.class);
        when(snapshotMapper.captureStateImage(any(), any())).thenReturn(capturedImage);
        when(snapshotMapper.serialize(capturedImage)).thenReturn(acceptedBytes);
        when(snapshotMapper.restoreStateImage(capturedImage)).thenReturn(new RecoveredRoom(room, game, false));

        // First mutation: domain succeeds, but storage fails (saving pendingSave with acceptedBytes)
        storageOutage.set(true);
        ProceedingJoinPoint joinPoint1 = mutationPoint("room-pending-rollback", () -> "accepted-move");
        assertEquals("accepted-move", aspect.persist(joinPoint1));

        // Second mutation: domain throws
        ProceedingJoinPoint joinPoint2 = mutationPoint("room-pending-rollback", () -> {
            throw new RuntimeException("Second move failed");
        });

        assertThrows(RuntimeException.class, () -> aspect.persist(joinPoint2));

        // Rolled back to accepted pending save state
        verify(aggregates).replace(new RoomAggregate(room, game));
        verify(aggregates, never()).remove("room-pending-rollback");
    }

    /**
     * Storage and serialization failures do not leave in-memory aggregate corrupted.
     */
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

        // Serialization fails on first mutation
        when(snapshotMapper.serialize(any(RoomSnapshot.class)))
                .thenThrow(new PersistenceException("transient serialization failure"))
                .thenReturn(new byte[]{7, 8, 9});

        assertEquals(1, aspect.persist(joinPoint1));
        assertEquals(List.of("effect-1"), effects, "Accepted mutation effects are executed");

        // Second mutation succeeds and serializes cleanly without corruption
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

    /**
     * When mutation fails on lobby room without active game,
     * room is restored and game is evicted without destroying the room.
     */
    @Test
    @DisplayName("When lobby room mutation fails, room is restored and game is evicted without destroying room")
    void lobbyMutationFailureEvictsGameAndPreservesLobbyRoom() throws Throwable {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring, roomCoordinator);
        DurableMutationAspect aspect = new DurableMutationAspect(
                store, snapshotMapper, aggregates, properties, roomCoordinator);

        String roomId = "room-lobby-rollback";
        byte[] snapshotBytes = new byte[]{10, 20, 30};
        withRoomLock(roomCoordinator, roomId, () -> {
            store.checkpoint(roomId, snapshotBytes);
            return null;
        });

        Room lobbyRoom = new Room(roomId, "Lobby Room", "Alice", 6, 10, 20, 1000, null);
        lobbyRoom.addPlayer("Alice");
        RecoveredRoom lobbyAggregate = new RecoveredRoom(lobbyRoom, null, false);
        when(aggregates.find(roomId)).thenReturn(new RoomAggregate(lobbyRoom, null));
        when(snapshotMapper.restoreStateImage(any(RoomSnapshot.class))).thenReturn(lobbyAggregate);

        ProceedingJoinPoint joinPoint = mutationPoint(roomId, () -> {
            throw new RuntimeException("Lobby mutation failure");
        });

        assertThrows(RuntimeException.class, () -> aspect.persist(joinPoint));

        verify(aggregates).replace(new RoomAggregate(lobbyRoom, null));
        verify(aggregates, never()).remove(roomId);
    }

    @Test
    void failedDeletionSaveRetainsAndRetriesDeletionTombstone() throws Throwable {
        String roomId = "room-deletion-retry";
        RoomSnapshotMapper realMapper = new RoomSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build());
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
        verify(joinPoint, times(1)).proceed();
    }

    @Test
    void prepareFailureBeforeRejectedMutationQueuesRetryForExistingRoom() throws Throwable {
        String roomId = "room-prepare-rejected-retry";
        RoomSnapshotMapper realMapper = new RoomSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build());
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
    void retryPendingSavesHoldsRoomLockWhenRecordingSnapshotFailure() throws Throwable {
        String roomId = "room-retry-failure-lock";
        RoomSnapshotMapper failingMapper = spy(new RoomSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build()));
        doThrow(new PersistenceException("injected serialization outage"))
                .when(failingMapper).serialize(any(RoomSnapshot.class));
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = spy(new EncryptedWalStore(tempDir, keyring, roomCoordinator));
        Room existingRoom = new Room(roomId, "Retry Lock Room", "Alice", 6, 10, 20, 1000, null);
        existingRoom.addPlayer("Alice");
        when(aggregates.find(roomId)).thenReturn(new RoomAggregate(existingRoom, null));

        DurableMutationAspect aspect = new DurableMutationAspect(
                store, failingMapper, aggregates, properties, roomCoordinator);
        ProceedingJoinPoint joinPoint = mutationPoint(roomId, () -> "ok");

        assertEquals("ok", aspect.persist(joinPoint));
        assertEquals(1, store.unsavedRoomCount());

        AtomicBoolean lockHeldOnRecordFailure = new AtomicBoolean(false);
        doAnswer(invocation -> {
            lockHeldOnRecordFailure.set(roomCoordinator.isHeldByCurrentThread(roomId));
            return invocation.callRealMethod();
        }).when(store).recordWriteFailure(eq(roomId), any());

        aspect.retryPendingSaves();

        assertTrue(lockHeldOnRecordFailure.get(),
                "Room lock must be held while retryPendingSaves records storage failure");
        verify(store, times(2)).recordWriteFailure(eq(roomId), any());
        assertFalse(roomCoordinator.isHeldByCurrentThread(roomId),
                "Room lock must be released after retryPendingSaves completes");
    }

    @Test
    void storeFailuresAreRecordedOnlyByTheStoreAndRetryStillHeals() throws Throwable {
        String roomId = "room-store-records-once";
        RoomSnapshotMapper realMapper = new RoomSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build());
        AtomicReference<WalFaultPoint> outage = new AtomicReference<>(WalFaultPoint.BEFORE_COMMIT_WRITE);
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = spy(new EncryptedWalStore(tempDir, keyring, (point, id) -> {
            if (point == outage.get()) {
                throw new PersistenceException("injected " + point);
            }
        }, roomCoordinator));
        Room existingRoom = new Room(roomId, "Record Once Room", "Alice", 6, 10, 20, 1000, null);
        existingRoom.addPlayer("Alice");
        when(aggregates.find(roomId)).thenReturn(new RoomAggregate(existingRoom, null));
        DurableMutationAspect aspect = new DurableMutationAspect(
                store, realMapper, aggregates, properties, roomCoordinator);

        assertEquals("ok", aspect.persist(mutationPoint(roomId, () -> "ok")));
        verify(store, times(1)).recordWriteFailure(eq(roomId), any());

        outage.set(WalFaultPoint.BEFORE_PREPARE_WRITE);
        aspect.retryPendingSaves();
        verify(store, times(2)).recordWriteFailure(eq(roomId), any());
        assertEquals(1, store.unsavedRoomCount());

        outage.set(null);
        aspect.retryPendingSaves();
        assertEquals(0, store.unsavedRoomCount());
        assertTrue(withRoomLock(roomCoordinator, roomId, () -> store.recoverLatest(roomId)).isPresent());
    }

    @Test
    void missingRoomRejectedMutationDuringPrepareOutageDoesNotDegradeStore() throws Throwable {
        String roomId = "room-missing-prepare-outage";
        RoomSnapshotMapper realMapper = new RoomSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build());
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
        RoomSnapshotMapper realMapper = new RoomSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build());
        AtomicReference<String> failRoom = new AtomicReference<>();
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = spy(new EncryptedWalStore(tempDir, keyring, (point, roomId) -> {
            if (roomId.equals(failRoom.get()) && point == WalFaultPoint.BEFORE_COMMIT_WRITE) {
                throw new PersistenceException("injected outage for " + roomId);
            }
        }, roomCoordinator));

        Room roomA = new Room("room-A", "Room A", "Alice", 6, 10, 20, 1000, null);
        roomA.addPlayer("Alice");
        Room roomB = new Room("room-B", "Room B", "Bob", 6, 10, 20, 1000, null);
        roomB.addPlayer("Bob");
        AtomicBoolean roomBDeleted = new AtomicBoolean(false);
        when(aggregates.find("room-A")).thenReturn(new RoomAggregate(roomA, null));
        when(aggregates.find("room-B")).thenAnswer(inv -> roomBDeleted.get() ? null : new RoomAggregate(roomB, null));

        DurableMutationAspect aspect = new DurableMutationAspect(
                store, realMapper, aggregates, properties, roomCoordinator);

        // Create initial WAL files for both rooms
        aspect.persist(mutationPoint("room-A", () -> "init-a"));
        aspect.persist(mutationPoint("room-B", () -> "init-b"));
        clearInvocations(store);

        // Room A fails a commit write, degrading global store health
        failRoom.set("room-A");
        aspect.persist(mutationPoint("room-A", () -> "accepted-a"));
        failRoom.set(null);
        assertFalse(store.isHealthy());
        assertEquals(1, store.unsavedRoomCount());

        // Room B still uses prepare + commit (ordinary append), NOT a forced full checkpoint
        aspect.persist(mutationPoint("room-B", () -> "accepted-b"));
        verify(store).prepare("room-B");
        verify(store).commit(argThat(tx -> "room-B".equals(tx.roomId())), any());
        verify(store, never()).checkpoint(eq("room-B"), any());

        // Deleting room B still executes store.delete("room-B") instead of skipping deletion
        aspect.persist(mutationPoint("room-B", () -> {
            roomBDeleted.set(true);
            return null;
        }));
        verify(store).delete("room-B");
        assertTrue(withRoomLock(roomCoordinator, "room-B", () -> store.recoverLatest("room-B")).isEmpty());
        assertEquals(1, store.unsavedRoomCount());
    }

    private EncryptedWalStore failingStore(AtomicBoolean unavailable) {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        return new EncryptedWalStore(tempDir, keyring, (point, roomId) -> {
            if (unavailable.get()) throw new PersistenceException("injected storage outage");
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

    private ProceedingJoinPoint mutationPoint(String roomId, Callable<Object> body) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(TestTarget.class.getMethod("validMutation", String.class));
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{roomId});
        lenient().when(joinPoint.proceed()).thenAnswer(invocation -> body.call());
        return joinPoint;
    }
}
