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
        when(snapshotMapper.serialize(any(), any(), any())).thenReturn(new byte[]{1, 2, 3});

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
        when(snapshotMapper.serialize(any(), any(), any())).thenThrow(new PersistenceException("injected serialization failure"));

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
        lenient().when(roomServiceProvider.getObject()).thenReturn(roomService);
        lenient().when(gameLifecycleServiceProvider.getObject()).thenReturn(gameLifecycleService);
        lenient().when(roomService.getRoom(roomId)).thenReturn(mock(Room.class));
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
        lenient().when(joinPoint.proceed()).thenAnswer(invocation -> body.call());
        return joinPoint;
    }
}
