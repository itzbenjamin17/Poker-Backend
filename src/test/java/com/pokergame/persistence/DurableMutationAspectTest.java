package com.pokergame.persistence;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
     * Protects the contract that commit failure cleans up thread-local transaction context.
     */
    @Test
    void commitFailureCleansUpThreadLocalTransactionContext() throws Throwable {
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
        when(joinPoint.proceed()).thenReturn("ok");

        Room room = mock(Room.class);
        when(roomService.getRoom("room-4")).thenReturn(room);
        when(snapshotMapper.serialize(any(), any(), any())).thenReturn(new byte[]{1, 2, 3});

        assertThrows(PersistenceException.class, () -> aspect.persist(joinPoint, mutation));

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
}
