package com.pokergame.service;

import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.model.Game;
import com.pokergame.model.Room;
import com.pokergame.persistence.config.PersistenceException;
import com.pokergame.persistence.config.PersistenceProperties;
import com.pokergame.persistence.snapshot.AggregateSnapshotMapper;
import com.pokergame.persistence.snapshot.AggregateStateImage;
import com.pokergame.persistence.transaction.DurableMutation;
import com.pokergame.persistence.transaction.DurableMutationAspect;
import com.pokergame.persistence.transaction.DurableTransactionContext;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.persistence.wal.EncryptionKeyring;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/** Behavioral regressions for rejected commands after accepted in-memory gameplay. */
class DurableRollbackProgressionTest {
    @TempDir Path directory;
    private RoomService rooms;
    private GameLifecycleService games;
    private AggregateSnapshotMapper mapper;
    private DurableMutationAspect aspect;
    private EncryptedWalStore store;
    private final List<ScheduledFuture<?>> futures = new ArrayList<>();
    private final List<Runnable> callbacks = new ArrayList<>();
    private final List<Instant> executionTimes = new ArrayList<>();
    private static final String ID = "rollback-room";

    /** Target metadata supplies the same top-level durable boundary as real commands. */
    public static class Target {
        @DurableMutation(roomId = "#roomId")
        public void mutate(String roomId) { }
    }

    @BeforeEach
    void setUp() {
        var messaging = mock(SimpMessagingTemplate.class);
        rooms = new RoomService(messaging);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(call -> {
            callbacks.add(call.getArgument(0));
            executionTimes.add(call.getArgument(1));
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            futures.add(future);
            return future;
        });
        HandEvaluatorService evaluator = new HandEvaluatorService();
        games = new GameLifecycleService(rooms, evaluator, mock(GameStateService.class), messaging, scheduler,
                rooms.getRoomCoordinator());
        Room room = new Room(ID, "Rollback room", "Host", 6, 10, 20, 1000, null);
        room.addPlayer("Host");
        room.addPlayer("Guest");
        rooms.restoreRoom(room, "Host");
        games.createGameFromRoom(ID);
        mapper = spy(new AggregateSnapshotMapper(JsonMapper.builder().findAndAddModules().build(), evaluator));
        store = new EncryptedWalStore(directory, new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES"))));
        ObjectProvider<RoomService> roomProvider = mock(ObjectProvider.class);
        ObjectProvider<GameLifecycleService> gameProvider = mock(ObjectProvider.class);
        when(roomProvider.getObject()).thenReturn(rooms);
        when(gameProvider.getObject()).thenReturn(games);
        aspect = new DurableMutationAspect(store, mapper, roomProvider, gameProvider, new PersistenceProperties(),
                rooms.getRoomCoordinator());
    }

    @Test
    void rejectionPreservesAcceptedStateWhenItsSerializationFailedAndRetrySavesIt() throws Throwable {
        invoke(() -> "baseline");
        AtomicBoolean serializationUnavailable = new AtomicBoolean(true);
        doAnswer(call -> {
            if (serializationUnavailable.get()) throw new PersistenceException("serialization unavailable");
            return call.callRealMethod();
        }).when(mapper).serialize(any(AggregateStateImage.class));
        List<String> effects = new ArrayList<>();
        invoke(() -> {
            games.getGame(ID).getPlayers().getFirst().setChips(777);
            DurableTransactionContext.afterCommit(() -> effects.add("accepted"));
            return "accepted";
        });
        assertThrows(IllegalArgumentException.class, () -> invoke(() -> {
            games.getGame(ID).getPlayers().getFirst().setChips(1);
            DurableTransactionContext.afterCommit(() -> effects.add("rejected"));
            throw new IllegalArgumentException("invalid command");
        }));
        assertEquals(777, games.getGame(ID).getPlayers().getFirst().getChips());
        assertEquals(List.of("accepted"), effects);
        serializationUnavailable.set(false);
        aspect.retryPendingSaves();
        assertEquals(777, mapper.deserialize(store.recoverLatest(ID).orElseThrow()).game().getPlayers().getFirst().getChips());
        assertEquals(List.of("accepted"), effects, "Retry must not replay accepted effects");
    }

    @Test
    void captureFailureDoesNotPreventAcceptedGameplayOrItsEffects() throws Throwable {
        doThrow(new PersistenceException("capture temporarily unavailable"))
                .doCallRealMethod().when(mapper).captureStateImage(any(Room.class), any(), any(Game.class));
        List<String> effects = new ArrayList<>();
        assertEquals("accepted", invoke(() -> {
            games.getGame(ID).getPlayers().getFirst().setChips(777);
            DurableTransactionContext.afterCommit(() -> effects.add("accepted"));
            return "accepted";
        }));
        assertEquals(777, games.getGame(ID).getPlayers().getFirst().getChips());
        assertEquals(List.of("accepted"), effects);
        assertEquals(777, mapper.deserialize(store.recoverLatest(ID).orElseThrow()).game().getPlayers().getFirst().getChips());
    }

    @Test
    void rejectedMutationRestoresProcessLocalRoomConnectivityAndAbsenceDeadline() throws Throwable {
        Room room = rooms.getRoom(ID);
        room.setPlayerConnected("Host", true);
        room.setPlayerConnected("Guest", false);
        room.setAbsenceDeadline("Guest", 123_456L);
        assertThrows(IllegalArgumentException.class, () -> invoke(() -> {
            room.setPlayerConnected("Host", false);
            room.setPlayerConnected("Guest", true);
            room.setAbsenceDeadline("Guest", null);
            throw new IllegalArgumentException("rejected membership mutation");
        }));
        Room restored = rooms.getRoom(ID);
        assertTrue(restored.isPlayerConnected("Host"));
        assertFalse(restored.isPlayerConnected("Guest"));
        assertEquals(123_456L, restored.getAbsenceDeadline("Guest"));
    }

    @Test
    void rejectionAfterAcceptedDeletionKeepsItsUnsavedTombstoneRetryable() throws Throwable {
        invoke(() -> "baseline");
        AtomicBoolean serializationUnavailable = new AtomicBoolean(true);
        doAnswer(call -> {
            if (serializationUnavailable.get()) throw new PersistenceException("deletion serialization unavailable");
            return call.callRealMethod();
        }).when(mapper).serializeDeletion(ID);
        invoke(() -> {
            games.evictGame(ID);
            rooms.destroyRoom(ID);
            return "deleted";
        });
        assertThrows(IllegalArgumentException.class, () -> invoke(() -> {
            throw new IllegalArgumentException("command after deletion");
        }));
        serializationUnavailable.set(false);
        aspect.retryPendingSaves();
        assertNull(rooms.getRoom(ID));
        assertTrue(store.recoverLatest(ID).isEmpty(), "Retry must preserve accepted deletion instead of leaving old recoverable state");
    }

    @Test
    void rejectedMutationRestoresExistingProgressionIntentsWithoutPublishingRejectedEffects() throws Throwable {
        invoke(() -> {
            games.scheduleAutoAdvance(ID);
            games.scheduleReadyCountdownOpen(ID, 60_000);
            games.scheduleGameCleanup(ID, 90_000);
            return null;
        });
        Map<ScheduledGameTask, Long> deadlines = games.getGame(ID).getScheduledTaskDeadlinesSnapshot();
        List<ScheduledFuture<?>> acceptedFutures = List.copyOf(futures);
        List<String> effects = new ArrayList<>();
        assertThrows(IllegalArgumentException.class, () -> invoke(() -> {
            DurableTransactionContext.afterCommit(() -> effects.add("rejected"));
            throw new IllegalArgumentException("late action");
        }));
        assertEquals(deadlines, games.getGame(ID).getScheduledTaskDeadlinesSnapshot());
        assertEquals(4, games.getRuntimeTaskCount(ID), "Restored aggregate must retain executable progression");
        acceptedFutures.forEach(future -> verify(future).cancel(false));
        assertTrue(effects.isEmpty());
        int cleanupIndex = 0;
        for (int index = 1; index < executionTimes.size(); index++) {
            if (executionTimes.get(index).isAfter(executionTimes.get(cleanupIndex))) cleanupIndex = index;
        }
        Runnable cleanup = callbacks.get(cleanupIndex);
        cleanup.run();
        assertNull(games.getGame(ID), "Restored cleanup callback must still remove the game");
        cleanup.run();
        assertNull(games.getGame(ID), "Repeated obsolete cleanup remains harmless");
    }

    private Object invoke(Callable<Object> body) throws Throwable {
        var method = Target.class.getMethod("mutate", String.class);
        ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(point.getSignature()).thenReturn(signature);
        when(point.getArgs()).thenReturn(new Object[]{ID});
        when(point.proceed()).thenAnswer(call -> body.call());
        return aspect.persist(point, method.getAnnotation(DurableMutation.class));
    }
}
