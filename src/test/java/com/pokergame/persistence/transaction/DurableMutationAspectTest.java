package com.pokergame.persistence.transaction;

import com.pokergame.model.Room;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.persistence.wal.EncryptionKeyring;
import com.pokergame.persistence.wal.WalFaultPoint;
import com.pokergame.persistence.snapshot.AggregateSnapshotMapper;
import com.pokergame.persistence.snapshot.AggregateStateImage;
import com.pokergame.persistence.snapshot.RecoveredAggregate;
import com.pokergame.service.HandEvaluatorService;
import org.junit.jupiter.api.DisplayName;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.persistence.config.PersistenceProperties;
import com.pokergame.persistence.config.PersistenceException;
import java.util.concurrent.Callable;
import org.mockito.Mockito;
import tools.jackson.databind.json.JsonMapper;

/** Tests durable mutation aspect transaction boundaries and SpEL resolution. */
@ExtendWith(MockitoExtension.class)
class DurableMutationAspectTest {

    @TempDir
    Path tempDir;

    @Mock
    private AggregateSnapshotMapper snapshotMapper;

    @Mock
    private RoomService roomService;

    @Mock
    private GameLifecycleService gameLifecycleService;

    @Mock
    private ObjectProvider<RoomService> roomServiceProvider;

    @Mock
    private ObjectProvider<GameLifecycleService> gameLifecycleServiceProvider;

    private PersistenceProperties properties;

    public static class TestTarget {
        @DurableMutation(roomId = "#roomId")
        public void validMutation(String roomId) {
        }

        @DurableMutation(roomId = "#nonexistent")
        public void invalidSpelMutation(String roomId) {
        }
    }

    @BeforeEach
    void setUp() {
        properties = new PersistenceProperties();
        AggregateStateImage mockImage = Mockito.mock(AggregateStateImage.class);
        Mockito.lenient().when(snapshotMapper.captureStateImage(any(), any(), any())).thenReturn(mockImage);
        Mockito.lenient().when(snapshotMapper.serialize(any(AggregateStateImage.class))).thenReturn(new byte[]{1, 2, 3});
        Mockito.lenient().when(snapshotMapper.serialize(any(Room.class), any(), any())).thenReturn(new byte[]{1, 2, 3});
    }

    @AfterEach
    void tearDown() {
        DurableTransactionContext.discard();
    }

    /**
     * Protects the contract that nested durable mutations reject calls crossing room boundaries.
     */
    @Test
    void nestedDurableMutationCrossRoomBoundaryThrowsPersistenceException() throws Throwable {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring);
        DurableMutationAspect aspect = new DurableMutationAspect(
                store, snapshotMapper, roomServiceProvider, gameLifecycleServiceProvider, properties);

        Method method = TestTarget.class.getMethod("validMutation", String.class);
        DurableMutation mutation = method.getAnnotation(DurableMutation.class);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"room-B"});

        DurableTransactionContext.begin("room-A");
        try {
            PersistenceException ex = assertThrows(PersistenceException.class,
                    () -> aspect.persist(joinPoint, mutation));
            assertEquals("Nested durable mutations cannot cross room boundaries", ex.getMessage());
            verify(joinPoint, never()).proceed();
        } finally {
            DurableTransactionContext.discard();
        }
    }

    /**
     * Commit failure retains accepted effects and later commands without replay.
     */
    @Test
    void commitFailureKeepsAcceptedEffectsAndLaterMutationAvailable() throws Throwable {
        when(roomServiceProvider.getObject()).thenReturn(roomService);
        when(gameLifecycleServiceProvider.getObject()).thenReturn(gameLifecycleService);

        AtomicReference<WalFaultPoint> failure = new AtomicReference<>(WalFaultPoint.BEFORE_COMMIT_WRITE);
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring,
                (point, roomId) -> {
                    if (point == failure.get()) {
                        throw new PersistenceException("injected " + point);
                    }
                });

        DurableMutationAspect aspect = new DurableMutationAspect(
                store, snapshotMapper, roomServiceProvider, gameLifecycleServiceProvider, properties);

        Method method = TestTarget.class.getMethod("validMutation", String.class);
        DurableMutation mutation = method.getAnnotation(DurableMutation.class);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"room-4"});
        AtomicInteger moves = new AtomicInteger();
        List<String> effects = new ArrayList<>();
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            int move = moves.incrementAndGet();
            DurableTransactionContext.afterCommit(() -> effects.add("state-" + move));
            DurableTransactionContext.afterCommit(() -> effects.add("timer-" + move));
            return "ok";
        });

        Room room = mock(Room.class);
        when(roomService.getRoom("room-4")).thenReturn(room);
        Mockito.lenient().when(snapshotMapper.serialize(any(), any(), any())).thenReturn(new byte[]{1, 2, 3});

        assertEquals("ok", aspect.persist(joinPoint, mutation));
        assertEquals(List.of("state-1", "timer-1"), effects, "Accepted effects are released exactly once");
        failure.set(null);
        assertEquals("ok", aspect.persist(joinPoint, mutation));
        assertEquals(2, moves.get(), "Later accepted commands still run");
        assertEquals(2, effects.stream().filter(value -> value.startsWith("state-")).count());
        aspect.retryPendingSaves();
        assertNull(DurableTransactionContext.currentRoomId(),
                "Thread-local DurableTransactionContext should be null after commit failure");
    }

    /**
     * Protects the contract that SpEL expression failing to resolve a room ID throws PersistenceException.
     */
    @Test
    void spelResolutionFailureThrowsPersistenceException() throws Throwable {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring);
        DurableMutationAspect aspect = new DurableMutationAspect(
                store, snapshotMapper, roomServiceProvider, gameLifecycleServiceProvider, properties);

        Method method = TestTarget.class.getMethod("invalidSpelMutation", String.class);
        DurableMutation mutation = method.getAnnotation(DurableMutation.class);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"room-5"});

        PersistenceException ex = assertThrows(PersistenceException.class,
                () -> aspect.persist(joinPoint, mutation));
        assertEquals("Durable mutation did not resolve a room ID", ex.getMessage());
        verify(joinPoint, never()).proceed();
        assertNull(DurableTransactionContext.currentRoomId());
    }

    /** A prepare outage must still allow the domain mutation and its effects. */
    @Test
    void prepareFailureContinuesMutationAndEffects() throws Throwable {
        AtomicBoolean unavailable = new AtomicBoolean(true);
        EncryptedWalStore store = failingStore(unavailable);
        DurableMutationAspect aspect = aspectForRoom(store, "room-prepare");
        AtomicInteger moves = new AtomicInteger();
        ProceedingJoinPoint joinPoint = mutationPoint("room-prepare", moves::incrementAndGet);

        assertEquals(1, aspect.persist(joinPoint, validMutation()));
        assertEquals(1, moves.get(), "Mutation proceeds when prepare fails");
    }

    /** Serialization failure leaves gameplay available and releases accepted effects. */
    @Test
    void serializationFailureContinuesMutationAndLaterCommand() throws Throwable {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring);
        DurableMutationAspect aspect = aspectForRoom(store, "room-serialize");
        AtomicInteger moves = new AtomicInteger();
        List<String> effects = new ArrayList<>();
        ProceedingJoinPoint joinPoint = mutationPoint("room-serialize", () -> {
            int move = moves.incrementAndGet();
            DurableTransactionContext.afterCommit(() -> effects.add("effect-" + move));
            return move;
        });
        when(snapshotMapper.serialize(any(AggregateStateImage.class))).thenThrow(new PersistenceException("injected serialization failure"));

        assertEquals(1, aspect.persist(joinPoint, validMutation()));
        assertEquals(List.of("effect-1"), effects);
        assertEquals(2, aspect.persist(joinPoint, validMutation()));
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
            DurableTransactionContext.afterCommit(() -> published.add("invalid"));
            throw new IllegalArgumentException("invalid action");
        });

        assertThrows(IllegalArgumentException.class, () -> aspect.persist(joinPoint, validMutation()));
        assertTrue(published.isEmpty());
        assertNull(DurableTransactionContext.currentRoomId());
        verify(snapshotMapper, never()).serialize(any(), any(), any());
        verify(snapshotMapper, never()).serialize(any(AggregateStateImage.class));
    }

    @Test
    @DisplayName("captureState acquires Room monitor before Game monitor and serializes outside monitors")
    void captureState_AcquiresMonitorsInOrderAndEncodesOutsideMonitors() throws Throwable {
        when(roomServiceProvider.getObject()).thenReturn(roomService);
        when(gameLifecycleServiceProvider.getObject()).thenReturn(gameLifecycleService);

        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring);
        AggregateSnapshotMapper realMapper = spy(new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build(),
                new HandEvaluatorService()));
        DurableMutationAspect aspect = new DurableMutationAspect(
                store, realMapper, roomServiceProvider, gameLifecycleServiceProvider, properties);

        Room room = spy(new Room("room-capture", "Capture Room", "Host", 6, 10, 20, 1000, null));
        room.addPlayer("Host");
        room.addPlayer("Guest");
        Game game = spy(new Game("room-capture", List.of(new Player("Host", "p1", 1000), new Player("Guest", "p2", 1000)), 10, 20, new HandEvaluatorService()));

        when(roomService.getRoom("room-capture")).thenReturn(room);
        when(gameLifecycleService.getGame("room-capture")).thenReturn(game);
        when(roomService.getCurrentHost("room-capture")).thenReturn("Host");

        doAnswer(inv -> {
            assertTrue(Thread.holdsLock(room), "Room state must be read under Room monitor");
            assertFalse(Thread.holdsLock(game), "Game monitor must not be held when capturing Room state");
            return inv.callRealMethod();
        }).when(room).getPlayersWithJoinTimeSnapshot();

        doAnswer(inv -> {
            assertTrue(Thread.holdsLock(room), "Room monitor must still be held during Game state capture");
            assertTrue(Thread.holdsLock(game), "Game monitor must be held during Game state capture");
            return inv.callRealMethod();
        }).when(game).getPlayers();

        doAnswer(inv -> {
            assertFalse(Thread.holdsLock(room), "Room monitor must be released before JSON serialization");
            assertFalse(Thread.holdsLock(game), "Game monitor must be released before JSON serialization");
            return inv.callRealMethod();
        }).when(realMapper).serialize(any(AggregateStateImage.class));

        ProceedingJoinPoint joinPoint = mutationPoint("room-capture", () -> "done");
        aspect.persist(joinPoint, validMutation());

        verify(realMapper, times(2)).captureStateImage(room, "Host", game);
        verify(realMapper).serialize(any(AggregateStateImage.class));
    }

    /** An accepted action's timer must still run if its preceding broadcast callback fails. */
    @Test
    void callbackFailureDoesNotRejectAcceptedMutationOrSuppressNextCallback() throws Throwable {
        AtomicBoolean unavailable = new AtomicBoolean(false);
        EncryptedWalStore store = failingStore(unavailable);
        DurableMutationAspect aspect = aspectForRoom(store, "room-callback");
        when(snapshotMapper.serialize(any(AggregateStateImage.class))).thenReturn(new byte[]{1});
        List<String> effects = new ArrayList<>();
        ProceedingJoinPoint joinPoint = mutationPoint("room-callback", () -> {
            DurableTransactionContext.afterCommit(() -> { throw new IllegalStateException("broker unavailable"); });
            DurableTransactionContext.afterCommit(() -> effects.add("scheduled"));
            return "accepted";
        });

        assertEquals("accepted", aspect.persist(joinPoint, validMutation()));
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
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring);
        DurableMutationAspect aspect = new DurableMutationAspect(
                store, snapshotMapper, roomServiceProvider, gameLifecycleServiceProvider, properties);

        when(roomServiceProvider.getObject()).thenReturn(roomService);
        when(gameLifecycleServiceProvider.getObject()).thenReturn(gameLifecycleService);

        String roomId = "room-rollback-test";
        Room originalRoom = new Room(roomId, "Rollback Room", "Alice", 6, 10, 20, 1000, null);
        originalRoom.addPlayer("Alice");
        originalRoom.addPlayer("Bob");

        Player alice = new Player("Alice", "p1", 1000);
        Player bob = new Player("Bob", "p2", 1000);
        Game originalGame = new Game(roomId, List.of(alice, bob), 10, 20, new HandEvaluatorService());

        byte[] committedPayload = new byte[]{42, 43, 44};
        store.checkpoint(roomId, committedPayload);

        when(snapshotMapper.restoreStateImage(any(AggregateStateImage.class)))
                .thenReturn(new RecoveredAggregate(originalRoom, "Alice", originalGame, false));

        lenient().when(roomService.getRoom(roomId)).thenReturn(originalRoom);
        lenient().when(gameLifecycleService.getGame(roomId)).thenReturn(originalGame);

        ProceedingJoinPoint joinPoint = mutationPoint(roomId, () -> {
            // Simulate dirty uncommitted mutation before throwing
            bob.setChips(500);
            throw new RuntimeException("Simulated domain action failure");
        });

        assertThrows(RuntimeException.class, () -> aspect.persist(joinPoint, validMutation()));

        // Verify that the dirty mutation was rolled back via restoreRoom and restoreGame
        verify(roomService).restoreRoom(originalRoom, "Alice");
        verify(gameLifecycleService).restoreGame(originalGame);
        verify(roomService, never()).destroyRoom(roomId);
    }

    /**
     * Mutation failure evicts a room that did not exist before the attempted creation.
     */
    @Test
    @DisplayName("When creation fails and no pre-mutation room existed, aggregate is evicted")
    void failedCreationEvictsAggregateWhenNoPreMutationRoomExists() throws Throwable {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring);
        DurableMutationAspect aspect = new DurableMutationAspect(
                store, snapshotMapper, roomServiceProvider, gameLifecycleServiceProvider, properties);

        when(roomServiceProvider.getObject()).thenReturn(roomService);
        when(gameLifecycleServiceProvider.getObject()).thenReturn(gameLifecycleService);

        String roomId = "room-uncommitted-fail";
        when(roomService.getRoom(roomId)).thenReturn(null);
        lenient().when(gameLifecycleService.getGame(roomId)).thenReturn(null);

        ProceedingJoinPoint joinPoint = mutationPoint(roomId, () -> {
            throw new IllegalStateException("Creation failure inside joinPoint.proceed()");
        });

        assertThrows(IllegalStateException.class, () -> aspect.persist(joinPoint, validMutation()));

        // A failed creation must not leave an uncommitted aggregate in the active registry
        verify(gameLifecycleService).evictGame(roomId);
        verify(roomService).destroyRoom(roomId);
        verify(gameLifecycleService, never()).restoreGame(any());
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
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring);
        DurableMutationAspect aspect = new DurableMutationAspect(
                store, snapshotMapper, roomServiceProvider, gameLifecycleServiceProvider, properties);

        when(roomServiceProvider.getObject()).thenReturn(roomService);
        when(gameLifecycleServiceProvider.getObject()).thenReturn(gameLifecycleService);

        String roomId = "room-recovery-fail";
        byte[] payload = new byte[]{1, 2, 3};
        store.checkpoint(roomId, payload);

        // No pre-mutation room exists; stale WAL must not resurrect an old membership.

        ProceedingJoinPoint joinPoint = mutationPoint(roomId, () -> {
            throw new RuntimeException("Domain error");
        });

        assertThrows(RuntimeException.class, () -> aspect.persist(joinPoint, validMutation()));

        // Verify no old WAL room is resurrected into the live registry
        verify(roomService).destroyRoom(roomId);
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
        Game game = new Game("room-pending-rollback", List.of(host, guest), 10, 20, new HandEvaluatorService());

        when(roomService.getRoom("room-pending-rollback")).thenReturn(room);
        when(gameLifecycleService.getGame("room-pending-rollback")).thenReturn(game);

        byte[] acceptedBytes = new byte[]{50, 51, 52};
        AggregateStateImage capturedImage = mock(AggregateStateImage.class);
        when(snapshotMapper.captureStateImage(any(), any(), any())).thenReturn(capturedImage);
        when(snapshotMapper.serialize(capturedImage)).thenReturn(acceptedBytes);
        when(snapshotMapper.restoreStateImage(capturedImage)).thenReturn(new RecoveredAggregate(room, "Host", game, false));

        // First mutation: domain succeeds, but storage fails (saving pendingSave with acceptedBytes)
        storageOutage.set(true);
        ProceedingJoinPoint joinPoint1 = mutationPoint("room-pending-rollback", () -> "accepted-move");
        assertEquals("accepted-move", aspect.persist(joinPoint1, validMutation()));

        // Second mutation: domain throws
        ProceedingJoinPoint joinPoint2 = mutationPoint("room-pending-rollback", () -> {
            throw new RuntimeException("Second move failed");
        });

        assertThrows(RuntimeException.class, () -> aspect.persist(joinPoint2, validMutation()));

        // Rolled back to accepted pending save state
        verify(roomService).restoreRoom(room, "Host");
        verify(gameLifecycleService).restoreGame(game);
        verify(roomService, never()).destroyRoom("room-pending-rollback");
    }

    /**
     * Storage and serialization failures do not leave in-memory aggregate corrupted.
     */
    @Test
    @DisplayName("When serialization fails on accepted mutation, aggregate is preserved and subsequent mutation succeeds")
    void serializationFailurePreservesAcceptedStateWithoutCorruption() throws Throwable {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring);
        DurableMutationAspect aspect = aspectForRoom(store, "room-serialize-safe");

        AtomicInteger moves = new AtomicInteger();
        List<String> effects = new ArrayList<>();
        ProceedingJoinPoint joinPoint1 = mutationPoint("room-serialize-safe", () -> {
            int move = moves.incrementAndGet();
            DurableTransactionContext.afterCommit(() -> effects.add("effect-" + move));
            return move;
        });

        // Serialization fails on first mutation
        when(snapshotMapper.serialize(any(AggregateStateImage.class)))
                .thenThrow(new PersistenceException("transient serialization failure"))
                .thenReturn(new byte[]{7, 8, 9});

        assertEquals(1, aspect.persist(joinPoint1, validMutation()));
        assertEquals(List.of("effect-1"), effects, "Accepted mutation effects are executed");

        // Second mutation succeeds and serializes cleanly without corruption
        ProceedingJoinPoint joinPoint2 = mutationPoint("room-serialize-safe", () -> {
            int move = moves.incrementAndGet();
            DurableTransactionContext.afterCommit(() -> effects.add("effect-" + move));
            return move;
        });

        assertEquals(2, aspect.persist(joinPoint2, validMutation()));
        assertEquals(2, moves.get());
        assertEquals(List.of("effect-1", "effect-2"), effects);
        verify(roomService, never()).destroyRoom("room-serialize-safe");
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
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring);
        DurableMutationAspect aspect = new DurableMutationAspect(
                store, snapshotMapper, roomServiceProvider, gameLifecycleServiceProvider, properties);

        when(roomServiceProvider.getObject()).thenReturn(roomService);
        when(gameLifecycleServiceProvider.getObject()).thenReturn(gameLifecycleService);

        String roomId = "room-lobby-rollback";
        byte[] snapshotBytes = new byte[]{10, 20, 30};
        store.checkpoint(roomId, snapshotBytes);

        Room lobbyRoom = new Room(roomId, "Lobby Room", "Alice", 6, 10, 20, 1000, null);
        lobbyRoom.addPlayer("Alice");
        RecoveredAggregate lobbyAggregate = new RecoveredAggregate(lobbyRoom, "Alice", null, false);
        when(roomService.getRoom(roomId)).thenReturn(lobbyRoom);
        when(snapshotMapper.restoreStateImage(any(AggregateStateImage.class))).thenReturn(lobbyAggregate);

        ProceedingJoinPoint joinPoint = mutationPoint(roomId, () -> {
            throw new RuntimeException("Lobby mutation failure");
        });

        assertThrows(RuntimeException.class, () -> aspect.persist(joinPoint, validMutation()));

        verify(roomService).restoreRoom(lobbyRoom, "Alice");
        verify(gameLifecycleService).evictGame(roomId);
        verify(roomService, never()).destroyRoom(roomId);
    }

    /**
     * RoomService.restoreRoom reconciles inconsistent host mappings
     * and preserves legitimate player state under lock.
     */
    @Test
    @DisplayName("RoomService.restoreRoom reconciles inconsistent host mappings and preserves connected players")
    void roomServiceRestoreRoomReconcilesInconsistentHostAndPreservesConnectedPlayers() {
        RoomService realRoomService = new RoomService(null);
        String roomId = "room-restore-test";
        Room originalRoom = new Room(roomId, "Restore Test", "Alice", 6, 10, 20, 1000, null);
        originalRoom.addPlayer("Alice");
        originalRoom.addPlayer("Bob");
        originalRoom.setPlayerConnected("Alice", true);
        originalRoom.setAbsenceDeadline("Bob", 123456789L);

        realRoomService.restoreRoom(originalRoom, "Alice");
        assertEquals("Alice", realRoomService.getCurrentHost(roomId));
        assertTrue(realRoomService.isRoomHost(roomId, "Alice"));

        // Now restore with a host that does not exist in the room
        Room restoredRoom = new Room(roomId, "Restore Test", "Alice", 6, 10, 20, 1000, null);
        restoredRoom.addPlayer("Alice");
        restoredRoom.addPlayer("Bob");

        realRoomService.restoreRoom(restoredRoom, "NonExistentStranger");
        // Must reconcile to valid room host/player, not leak NonExistentStranger
        assertEquals("Alice", realRoomService.getCurrentHost(roomId));
        assertFalse(realRoomService.isRoomHost(roomId, "NonExistentStranger"));
        assertTrue(restoredRoom.isPlayerConnected("Alice"));
        assertEquals(123456789L, restoredRoom.getAbsenceDeadline("Bob"));

        // Host succession to Bob
        realRoomService.restoreRoom(restoredRoom, "Bob");
        assertEquals("Bob", realRoomService.getCurrentHost(roomId));
        assertTrue(realRoomService.isRoomHost(roomId, "Bob"));
    }

    private EncryptedWalStore failingStore(AtomicBoolean unavailable) {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        return new EncryptedWalStore(tempDir, keyring, (point, roomId) -> {
            if (unavailable.get()) throw new PersistenceException("injected storage outage");
        });
    }

    private DurableMutationAspect aspectForRoom(EncryptedWalStore store, String roomId) {
        lenient().when(roomServiceProvider.getObject()).thenReturn(roomService);
        lenient().when(gameLifecycleServiceProvider.getObject()).thenReturn(gameLifecycleService);
        lenient().when(roomService.getRoom(roomId)).thenReturn(mock(Room.class));
        return new DurableMutationAspect(store, snapshotMapper, roomServiceProvider, gameLifecycleServiceProvider, properties);
    }

    private DurableMutation validMutation() throws Exception {
        return TestTarget.class.getMethod("validMutation", String.class).getAnnotation(DurableMutation.class);
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
