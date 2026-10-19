package com.pokergame.service;

import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.config.PersistenceException;
import com.pokergame.persistence.config.PersistenceProperties;
import com.pokergame.persistence.snapshot.AggregateSnapshotMapper;
import com.pokergame.persistence.snapshot.RecoveredAggregate;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.persistence.wal.EncryptionKeyring;
import com.pokergame.persistence.wal.WalFaultPoint;
import com.pokergame.persistence.wal.WalFaultInjector;
import com.pokergame.persistence.transaction.DurableMutation;
import com.pokergame.persistence.transaction.DurableMutationAspect;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PersistenceCleanupRecoveryTest {
    private static final String ROOM_ID = "cleanup-room";

    @TempDir
    Path tempDir;

    @Mock RoomService roomService;
    @Mock ObjectProvider<RoomService> roomServiceProvider;
    @Mock ObjectProvider<GameLifecycleService> lifecycleProvider;
    @Mock GameStateService gameStateService;
    @Mock SimpMessagingTemplate messagingTemplate;
    @Mock TaskScheduler taskScheduler;
    @Test
    void committedCleanupIntentSurvivesInterruptedCallbackAndRecoveredCleanupRemovesAggregates() throws Throwable {
        HandEvaluatorService evaluator = new HandEvaluatorService();
        AggregateSnapshotMapper mapper = mapper(evaluator);
        EncryptedWalStore store = store((point, roomId) -> {});
        Room room = new Room(ROOM_ID, "Cleanup", "Host", 6, 5, 10, 1000, null);
        room.addPlayer("Host");
        room.addPlayer("Player2");
        room.setGameStarted(true);
        Game game = new Game(ROOM_ID, List.of(
                new Player("Host", "host-id", 1000), new Player("Player2", "player-2-id", 1000)), 5, 10, evaluator);
        long deadline = System.currentTimeMillis() + 60_000;
        game.scheduleTask(ScheduledGameTask.CLEANUP, deadline);

        GameLifecycleService lifecycle = lifecycle(roomService, evaluator);
        lifecycle.restoreGame(game);
        GameLifecycleService cleanupProxy = mock(GameLifecycleService.class);
        lifecycle.setSelf(cleanupProxy);
        doThrow(new IllegalStateException("simulated stop before deletion callback")).when(cleanupProxy).performGameCleanup(ROOM_ID);
        AtomicReference<Room> roomReference = new AtomicReference<>(room);
        when(roomService.getRoom(ROOM_ID)).thenAnswer(invocation -> roomReference.get());
        when(roomService.getCurrentHost(ROOM_ID)).thenReturn("Host");
        doAnswer(invocation -> { roomReference.set(null); return null; }).when(roomService).destroyRoom(ROOM_ID);
        when(roomServiceProvider.getObject()).thenReturn(roomService);
        when(lifecycleProvider.getObject()).thenReturn(lifecycle);
        DurableMutationAspect aspect = new DurableMutationAspect(store, mapper, roomServiceProvider, lifecycleProvider,
                new PersistenceProperties());
        Method method = CleanupTaskTarget.class.getMethod("runCleanup", String.class);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{ROOM_ID});
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            lifecycle.executeScheduledTask(ROOM_ID, ScheduledGameTask.CLEANUP, deadline,
                    game.getHandNumber(), game.getScheduledTaskGeneration(ScheduledGameTask.CLEANUP));
            return null;
        });
        aspect.persist(joinPoint, method.getAnnotation(DurableMutation.class));
        verify(roomService, never()).destroyRoom(ROOM_ID);
        verify(cleanupProxy).performGameCleanup(ROOM_ID);
        assertEquals(deadline, game.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP));

        byte[] recoveredImage = store.recoverLatest(ROOM_ID).orElseThrow();
        RecoveredAggregate recovered = mapper.deserialize(recoveredImage);
        assertNotNull(recovered.room());
        assertNotNull(recovered.game());
        assertEquals(deadline, recovered.game().getScheduledTaskDeadline(ScheduledGameTask.CLEANUP));

        GameLifecycleService restartedLifecycle = lifecycle(roomService, evaluator);
        restartedLifecycle.restoreGame(recovered.game());
        roomReference.set(recovered.room());
        when(roomServiceProvider.getObject()).thenReturn(roomService);
        AtomicReference<GameLifecycleService> lifecycleProxyRef = new AtomicReference<>();
        when(lifecycleProvider.getObject()).thenAnswer(invocation -> lifecycleProxyRef.get());
        DurableMutationAspect recoveryAspect = new DurableMutationAspect(store, mapper, roomServiceProvider,
                lifecycleProvider, new PersistenceProperties());
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(restartedLifecycle);
        proxyFactory.addAspect(recoveryAspect);
        GameLifecycleService recoveredProxy = proxyFactory.getProxy();
        lifecycleProxyRef.set(recoveredProxy);
        restartedLifecycle.setSelf(recoveredProxy);
        recoveredProxy.executeScheduledTask(ROOM_ID, ScheduledGameTask.CLEANUP, deadline,
                recovered.game().getHandNumber(), recovered.game().getScheduledTaskGeneration(ScheduledGameTask.CLEANUP));

        assertFalse(restartedLifecycle.gameExists(ROOM_ID));
        assertNull(roomReference.get(), "The recovered cleanup removes the Room as well as the Game");
        assertTrue(store.recoverLatest(ROOM_ID).isEmpty(), "The durable cleanup tombstone prevents restart resurrection");
        verify(roomService).destroyRoom(ROOM_ID);
    }

    @Test
    void failedDeletionSaveRetainsAndRetriesDeletionTombstone() throws Throwable {
        HandEvaluatorService evaluator = new HandEvaluatorService();
        AggregateSnapshotMapper mapper = mapper(evaluator);
        AtomicBoolean failCommit = new AtomicBoolean(true);
        EncryptedWalStore store = store((point, roomId) -> {
            if (failCommit.get() && point == WalFaultPoint.BEFORE_COMMIT_WRITE) {
                throw new PersistenceException("injected deletion commit failure");
            }
        });
        when(roomServiceProvider.getObject()).thenReturn(roomService);
        when(roomService.getRoom(ROOM_ID)).thenReturn(null);
        DurableMutationAspect aspect = new DurableMutationAspect(store, mapper, roomServiceProvider,
                lifecycleProvider, new PersistenceProperties());
        Method method = DeletionTarget.class.getMethod("deleteRoom", String.class);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{ROOM_ID});

        aspect.persist(joinPoint, method.getAnnotation(DurableMutation.class));
        assertEquals(1, store.unsavedRoomCount(), "The failed deletion remains marked unsaved");
        failCommit.set(false);
        aspect.retryPendingSaves();

        assertEquals(0, store.unsavedRoomCount());
        assertTrue(store.recoverLatest(ROOM_ID).isEmpty(), "The retry checkpoints and removes the deleted room's WAL");
        verify(joinPoint, times(1)).proceed();
    }

    public static final class DeletionTarget {
        @DurableMutation(roomId = "#roomId")
        public void deleteRoom(String roomId) {}
    }

    public static final class CleanupTaskTarget {
        @DurableMutation(roomId = "#roomId")
        public void runCleanup(String roomId) {}
    }

    private AggregateSnapshotMapper mapper(HandEvaluatorService evaluator) {
        return new AggregateSnapshotMapper(JsonMapper.builder().findAndAddModules().build(), evaluator);
    }

    private EncryptedWalStore store(WalFaultInjector faultInjector) {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        return new EncryptedWalStore(tempDir, keyring, faultInjector);
    }

    private GameLifecycleService lifecycle(RoomService rooms, HandEvaluatorService evaluator) {
        return new GameLifecycleService(rooms, evaluator, gameStateService, messagingTemplate, taskScheduler);
    }
}
