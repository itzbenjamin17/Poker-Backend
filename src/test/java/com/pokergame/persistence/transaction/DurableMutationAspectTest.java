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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.persistence.wal.EncryptionKeyring;
import com.pokergame.persistence.wal.WalFaultPoint;
import com.pokergame.persistence.snapshot.AggregateSnapshotMapper;
import com.pokergame.persistence.config.PersistenceProperties;
import com.pokergame.persistence.config.PersistenceException;

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
     * A failed save must not reject an applied move or suppress its messages and timers.
     */
    @Test
    void commitFailurePublishesAppliedMoveAndAllowsFollowingMoves() throws Throwable {
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
        when(snapshotMapper.serialize(any(), any(), any())).thenReturn(new byte[]{1, 2, 3});

        assertEquals("ok", aspect.persist(joinPoint, mutation));
        assertEquals(List.of("state-1", "timer-1"), effects);
        assertEquals("ok", aspect.persist(joinPoint, mutation));
        assertEquals(2, moves.get(), "The following move must execute exactly once");
        assertEquals(List.of("state-1", "timer-1", "state-2", "timer-2"), effects);

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

    /** A prepare outage must still execute each move once and save the newest accepted state on retry. */
    @Test
    void prepareFailureRetriesLatestAcceptedStateWithoutReplayingMoves() throws Throwable {
        AtomicBoolean unavailable = new AtomicBoolean(true);
        EncryptedWalStore store = failingStore(unavailable);
        DurableMutationAspect aspect = aspectForRoom(store, "room-retry");
        AtomicInteger moves = new AtomicInteger();
        List<Integer> published = new ArrayList<>();
        ProceedingJoinPoint joinPoint = mutationPoint("room-retry", () -> {
            int value = moves.incrementAndGet();
            DurableTransactionContext.afterCommit(() -> published.add(value));
            return value;
        });
        when(snapshotMapper.serialize(any(), any(), any())).thenAnswer(invocation ->
                Integer.toString(moves.get()).getBytes(StandardCharsets.UTF_8));

        assertEquals(1, aspect.persist(joinPoint, validMutation()));
        assertEquals(2, aspect.persist(joinPoint, validMutation()));
        assertFalse(store.isHealthy());
        assertEquals(List.of(1, 2), published);
        unavailable.set(false);
        aspect.retryPendingSaves();

        assertEquals(2, moves.get());
        assertEquals(List.of(1, 2), published, "Retry must not resend effects or replay the move");
        assertTrue(store.isHealthy());
        assertArrayEquals("2".getBytes(StandardCharsets.UTF_8), store.recoverLatest("room-retry").orElseThrow());
    }

    /** Unchecked failures inside the storage boundary must also preserve accepted actions. */
    @Test
    void uncheckedStorageFailureStillPublishesAcceptedMove() throws Throwable {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring, (point, roomId) -> {
            throw new IllegalStateException("injected unchecked storage failure");
        });
        DurableMutationAspect aspect = aspectForRoom(store, "room-unchecked");
        when(snapshotMapper.serialize(any(), any(), any())).thenReturn(new byte[]{1});
        List<String> effects = new ArrayList<>();
        ProceedingJoinPoint joinPoint = mutationPoint("room-unchecked", () -> {
            DurableTransactionContext.afterCommit(() -> effects.add("published"));
            return "accepted";
        });

        assertEquals("accepted", aspect.persist(joinPoint, validMutation()));
        assertEquals(List.of("published"), effects);
        assertFalse(store.isHealthy());
    }

    /** A failed serialization must invalidate the older pending image before a retry can save it. */
    @Test
    void serializationFailureCannotRestoreAnOlderPendingImage() throws Throwable {
        AtomicBoolean unavailable = new AtomicBoolean(true);
        EncryptedWalStore store = failingStore(unavailable);
        DurableMutationAspect aspect = aspectForRoom(store, "room-serialize");
        AtomicInteger moves = new AtomicInteger();
        ProceedingJoinPoint joinPoint = mutationPoint("room-serialize", moves::incrementAndGet);
        when(snapshotMapper.serialize(any(), any(), any())).thenReturn(new byte[]{1})
                .thenThrow(new IllegalArgumentException("injected serialization failure"))
                .thenReturn(new byte[]{2});

        assertEquals(1, aspect.persist(joinPoint, validMutation()));
        assertEquals(2, aspect.persist(joinPoint, validMutation()));
        unavailable.set(false);
        aspect.retryPendingSaves();

        assertEquals(2, moves.get());
        assertArrayEquals(new byte[]{2}, store.recoverLatest("room-serialize").orElseThrow());
        assertTrue(store.isHealthy());
    }

    /** Deletion remains pending until even an idle room's tombstone can be saved. */
    @Test
    void idleDeletionRetriesWithoutResurrectingRoom() throws Throwable {
        AtomicBoolean unavailable = new AtomicBoolean(false);
        EncryptedWalStore store = failingStore(unavailable);
        DurableMutationAspect aspect = aspectForRoom(store, "room-delete");
        when(snapshotMapper.serialize(any(), any(), any())).thenReturn(new byte[]{1});
        ProceedingJoinPoint joinPoint = mutationPoint("room-delete", () -> "accepted");
        aspect.persist(joinPoint, validMutation());
        unavailable.set(true);
        when(roomService.getRoom("room-delete")).thenReturn(null);
        when(snapshotMapper.serializeDeletion("room-delete")).thenReturn(new byte[]{9});

        assertEquals("accepted", aspect.persist(joinPoint, validMutation()));
        assertFalse(store.isHealthy());
        unavailable.set(false);
        aspect.retryPendingSaves();

        assertTrue(store.isHealthy());
        assertTrue(store.recoverLatest("room-delete").isEmpty());
    }

    /** Persistence fallback must not turn a rejected domain mutation into a successful command. */
    @Test
    void failedDomainMutationStillRejectsAndDiscardsEffects() throws Throwable {
        AtomicBoolean unavailable = new AtomicBoolean(true);
        EncryptedWalStore store = failingStore(unavailable);
        DurableMutationAspect aspect = new DurableMutationAspect(
                store, snapshotMapper, roomServiceProvider, gameLifecycleServiceProvider, properties);
        List<String> published = new ArrayList<>();
        ProceedingJoinPoint joinPoint = mutationPoint("room-invalid", () -> {
            DurableTransactionContext.afterCommit(() -> published.add("invalid"));
            throw new IllegalArgumentException("invalid action");
        });

        assertThrows(IllegalArgumentException.class, () -> aspect.persist(joinPoint, validMutation()));
        assertTrue(published.isEmpty());
        assertNull(DurableTransactionContext.currentRoomId());
        verify(snapshotMapper, never()).serialize(any(), any(), any());
    }

    /** A serialization retry must never capture objects changed by a later rejected mutation. */
    @Test
    void rejectedMutationBlocksRecaptureUntilAnotherMutationSucceeds() throws Throwable {
        AtomicBoolean unavailable = new AtomicBoolean(false);
        EncryptedWalStore store = failingStore(unavailable);
        store.commit(store.prepare("room-partial"), new byte[]{0});
        DurableMutationAspect aspect = aspectForRoom(store, "room-partial");
        when(snapshotMapper.serialize(any(), any(), any()))
                .thenThrow(new PersistenceException("injected serialization failure"))
                .thenReturn(new byte[]{3});
        ProceedingJoinPoint accepted = mutationPoint("room-partial", () -> "accepted");
        assertEquals("accepted", aspect.persist(accepted, validMutation()));
        ProceedingJoinPoint rejected = mutationPoint("room-partial", () -> {
            throw new IllegalArgumentException("partially failed mutation");
        });
        assertThrows(IllegalArgumentException.class, () -> aspect.persist(rejected, validMutation()));
        aspect.retryPendingSaves();
        verify(snapshotMapper, times(1)).serialize(any(), any(), any());
        assertArrayEquals(new byte[]{0}, store.recoverLatest("room-partial").orElseThrow());
        assertFalse(store.isHealthy());

        assertEquals("accepted", aspect.persist(accepted, validMutation()));
        assertTrue(store.isHealthy());
        assertArrayEquals(new byte[]{3}, store.recoverLatest("room-partial").orElseThrow());
    }

    /** An accepted action's timer must still run if its preceding broadcast callback fails. */
    @Test
    void callbackFailureDoesNotRejectAcceptedMutationOrSuppressNextCallback() throws Throwable {
        AtomicBoolean unavailable = new AtomicBoolean(false);
        EncryptedWalStore store = failingStore(unavailable);
        DurableMutationAspect aspect = aspectForRoom(store, "room-callback");
        when(snapshotMapper.serialize(any(), any(), any())).thenReturn(new byte[]{1});
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

    private EncryptedWalStore failingStore(AtomicBoolean unavailable) {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        return new EncryptedWalStore(tempDir, keyring, (point, roomId) -> {
            if (unavailable.get()) throw new PersistenceException("injected storage outage");
        });
    }

    private DurableMutationAspect aspectForRoom(EncryptedWalStore store, String roomId) {
        when(roomServiceProvider.getObject()).thenReturn(roomService);
        when(gameLifecycleServiceProvider.getObject()).thenReturn(gameLifecycleService);
        when(roomService.getRoom(roomId)).thenReturn(mock(Room.class));
        return new DurableMutationAspect(store, snapshotMapper, roomServiceProvider, gameLifecycleServiceProvider, properties);
    }

    private DurableMutation validMutation() throws Exception {
        return TestTarget.class.getMethod("validMutation", String.class).getAnnotation(DurableMutation.class);
    }

    private ProceedingJoinPoint mutationPoint(String roomId, java.util.concurrent.Callable<Object> body) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(TestTarget.class.getMethod("validMutation", String.class));
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{roomId});
        when(joinPoint.proceed()).thenAnswer(invocation -> body.call());
        return joinPoint;
    }
}
