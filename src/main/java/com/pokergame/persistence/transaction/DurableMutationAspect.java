package com.pokergame.persistence.transaction;

import com.pokergame.model.Game;
import com.pokergame.model.Room;
import com.pokergame.persistence.config.PersistenceException;
import com.pokergame.persistence.config.PersistenceProperties;
import com.pokergame.persistence.snapshot.AggregateSnapshotMapper;
import com.pokergame.persistence.snapshot.AggregateStateImage;
import com.pokergame.persistence.snapshot.RecoveredAggregate;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.persistence.wal.WalTransaction;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import com.pokergame.util.MdcKeys;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import com.pokergame.util.KeyedLockRegistry;
import com.pokergame.util.LockLease;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Automatically saves the poker game to disk after certain methods finish running.
 * <p>
 * If one saved method calls another, it groups them into a single save operation at
 * the end. It also makes sure we only modify one room at a time, preventing corrupted saves.
 * Storage failures retain the accepted in-memory state and its queued effects for a later save retry.
 * Without a storage bean, the same room locks serialize operations without snapshot or disk work.
 * </p>
 */
@Aspect
public final class DurableMutationAspect {
    private static final Logger logger = LoggerFactory.getLogger(DurableMutationAspect.class);
    private final EncryptedWalStore store;
    private final AggregateSnapshotMapper snapshotMapper;
    private final ObjectProvider<RoomService> roomService;
    private final ObjectProvider<GameLifecycleService> gameLifecycleService;
    private final PersistenceProperties properties;
    private final KeyedLockRegistry<String> roomLocks = new KeyedLockRegistry<>();
    private final ConcurrentHashMap<String, AtomicInteger> recordsSinceCompaction = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingSave> pendingSaves = new ConcurrentHashMap<>();
    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();

    /**
     * Creates this aspect using lazy services to avoid circular dependency issues when setting up the app.
     *
     * @param store                storage for saving game data, or null for room locking only
     * @param snapshotMapper       helper to convert the game into a format we can save
     * @param roomService          lazy provider for the room service
     * @param gameLifecycleService lazy provider for the game service
     * @param properties           settings for when to clean up old save files
     */
    public DurableMutationAspect(EncryptedWalStore store, AggregateSnapshotMapper snapshotMapper,
            ObjectProvider<RoomService> roomService, ObjectProvider<GameLifecycleService> gameLifecycleService,
            PersistenceProperties properties) {
        this.store = store;
        this.snapshotMapper = snapshotMapper;
        this.roomService = roomService;
        this.gameLifecycleService = gameLifecycleService;
        this.properties = properties;
    }

    /**
     * Obtains the dedicated reentrant lock for a room.
     *
     * @param roomId the room identifier
     * @return the room lock
     */
    public LockLease acquireRoomLock(String roomId) {
        return roomLocks.acquire(roomId);
    }

    /**
     * Attempts to obtain the dedicated reentrant lock for a room without blocking.
     *
     * @param roomId the room identifier
     * @return the room lock lease if immediately available, or {@code null} if held by another thread
     */
    public LockLease tryAcquireRoomLock(String roomId) {
        return roomLocks.tryAcquire(roomId);
    }

    /**
     * Returns the keyed lock registry managing per-room persistence locks.
     *
     * @return the room lock registry
     */
    public KeyedLockRegistry<String> getLockRegistry() {
        return roomLocks;
    }

    /**
     * Serializes the method with other operations on the room. When storage is configured,
     * saves accepted state and releases effects even if persistence is temporarily unavailable.
     * Without storage, executes under the same lease without snapshots or transaction buffering.
     *
     * @param joinPoint the intercepted method
     * @param mutation details about the method
     * @return the result of the method
     * @throws Throwable if the domain mutation itself fails
     */
    @Around("@annotation(mutation)")
    public Object persist(ProceedingJoinPoint joinPoint, DurableMutation mutation) throws Throwable {
        String roomId = resolveRoomId(joinPoint, mutation.roomId());
        if (store == null) {
            LockLease lease = roomLocks.acquire(roomId);
            try {
                return joinPoint.proceed();
            } finally {
                lease.close();
            }
        }
        String previousRoom = MDC.get(MdcKeys.ROOM_ID);
        MDC.put(MdcKeys.ROOM_ID, roomId);
        try {
            logger.debug("Durable mutation enter: method={} roomId={}", joinPoint.getSignature().toShortString(), roomId);
            String activeRoom = DurableTransactionContext.currentRoomId();
            if (activeRoom != null) {
                if (!activeRoom.equals(roomId)) {
                    throw new PersistenceException("Nested durable mutations cannot cross room boundaries");
                }
                logger.debug("Nested durable mutation joined existing transaction for roomId={}", roomId);
                return joinPoint.proceed();
            }

            try (LockLease lease = roomLocks.acquire(roomId)) {
                DurableTransactionContext.begin(roomId);
                RollbackState beforeMutation = null;
                try {
                    beforeMutation = captureRollbackState(roomId);
                } catch (RuntimeException captureFailure) {
                    // Snapshot trouble must not refuse an otherwise valid in-memory command.
                    recordStorageFailure(roomId, captureFailure);
                }
                WalTransaction transaction = null;
                if (store.isHealthy() && !store.requiresCheckpoint(roomId)) {
                    try {
                        transaction = store.prepare(roomId);
                    } catch (RuntimeException failure) {
                        recordStorageFailure(roomId, failure);
                    }
                }
                Object result;
                try {
                    result = joinPoint.proceed();
                } catch (Throwable failure) {
                    logger.debug("Durable mutation discarded due to failure: class={} roomId={}", failure.getClass().getSimpleName(), roomId);
                    DurableTransactionContext.discard();
                    rollbackOrEvictAggregate(roomId, beforeMutation);
                    throw failure;
                }

                // An accepted newer mutation supersedes any old retry image before serialization starts.
                pendingSaves.put(roomId, new PendingSave(null, false));
                try {
                    PendingSave pending = captureState(roomId);
                    pendingSaves.put(roomId, pending);
                    saveState(roomId, pending, transaction);
                } catch (RuntimeException saveFailure) {
                    recordStorageFailure(roomId, saveFailure);
                }
                DurableTransactionContext.complete();
                return result;
            } finally {
                DurableTransactionContext.discard();
            }
        } finally {
            if (previousRoom == null) {
                MDC.remove(MdcKeys.ROOM_ID);
            } else {
                MDC.put(MdcKeys.ROOM_ID, previousRoom);
            }
        }
    }

    /**
     * Periodically retries pending room saves that previously failed due to transient storage errors.
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void retryPendingSaves() {
        for (String roomId : pendingSaves.keySet()) {
            LockLease lease = roomLocks.tryAcquire(roomId);
            if (lease == null) continue;
            try (lease) {
                PendingSave pending = pendingSaves.get(roomId);
                if (pending == null) continue;
                if (pending.image() == null) {
                    pending = captureState(roomId);
                    pendingSaves.put(roomId, pending);
                }
                saveState(roomId, pending, null);
            } catch (RuntimeException failure) {
                recordStorageFailure(roomId, failure);
            }
        }
    }

    /** Captures and encodes the latest accepted room state for a save attempt. */
    private PendingSave captureState(String roomId) {
        Room room = roomService.getObject().getRoom(roomId);
        if (room == null) return new PendingSave(snapshotMapper.serializeDeletion(roomId), true);
        Game game = gameLifecycleService.getObject().getGame(roomId);
        String currentHost = roomService.getObject().getCurrentHost(roomId);
        // snapshotMapper.captureStateImage enforces synchronized (room) -> synchronized (game) lock ordering
        AggregateStateImage stateImage = snapshotMapper.captureStateImage(room, currentHost, game);
        return new PendingSave(snapshotMapper.serialize(stateImage), false);
    }

    /** Saves accepted bytes without replaying the domain command or its effects. */
    private void saveState(String roomId, PendingSave pending, WalTransaction transaction) {
        boolean checkpoint = transaction == null || !store.isHealthy() || store.requiresCheckpoint(roomId);
        if (checkpoint) {
            store.checkpoint(roomId, pending.image());
            recordsSinceCompaction.remove(roomId);
        } else {
            store.commit(transaction, pending.image());
        }
        if (pending.deleted()) {
            if (!store.isHealthy()) return;
            store.delete(roomId);
            recordsSinceCompaction.remove(roomId);
        } else if (!checkpoint && shouldCompact(roomId)) {
            store.compact(roomId, pending.image());
            recordsSinceCompaction.remove(roomId);
        }
        pendingSaves.remove(roomId, pending);
    }

    /** Reports storage degradation while keeping accepted gameplay available. */
    private void recordStorageFailure(String roomId, RuntimeException failure) {
        store.recordWriteFailure(roomId, failure);
        logger.warn("Persistence unavailable for room {}; continuing in memory ({})",
                roomId, failure.getClass().getSimpleName());
    }

    private record PendingSave(byte[] image, boolean deleted) {
        private PendingSave { image = image == null ? null : image.clone(); }
    }

    /** Captures live rollback data independently of serialization and WAL availability. */
    private RollbackState captureRollbackState(String roomId) {
        Room room = roomService.getObject().getRoom(roomId);
        if (room == null) return new RollbackState(null, Map.of());
        synchronized (room) {
            AggregateStateImage image = snapshotMapper.captureStateImage(room,
                    roomService.getObject().getCurrentHost(roomId), gameLifecycleService.getObject().getGame(roomId));
            Map<String, ConnectionState> connections = new HashMap<>();
            for (String player : room.getPlayers()) {
                connections.put(player, new ConnectionState(room.isPlayerConnected(player), room.getAbsenceDeadline(player)));
            }
            return new RollbackState(image, Map.copyOf(connections));
        }
    }

    /** Process-local connectivity is intentionally separate from the persisted image. */
    private record ConnectionState(boolean connected, Long absenceDeadline) { }

    /** A null image denotes a room that did not exist before the rejected command. */
    private record RollbackState(AggregateStateImage image, Map<String, ConnectionState> connections) { }

    /**
     * Restores the independent pre-mutation RAM image after a rejection. Disk may lag
     * accepted gameplay, so WAL bytes are never a safe rollback source.
     *
     * @param roomId identifier of the room whose failed mutation must be rolled back
     * @param beforeMutation immutable state captured before the rejected command, or null if capture failed
     */
    private void rollbackOrEvictAggregate(String roomId, RollbackState beforeMutation) {
        try {
            if (beforeMutation != null && beforeMutation.image() != null) {
                RecoveredAggregate aggregate = snapshotMapper.restoreStateImage(beforeMutation.image());
                if (aggregate.deleted() || aggregate.room() == null) {
                    evictAggregate(roomId);
                } else {
                    restoreAggregate(roomId, aggregate);
                    synchronized (aggregate.room()) {
                        beforeMutation.connections().forEach((player, state) -> {
                            aggregate.room().setPlayerConnected(player, state.connected());
                            aggregate.room().setAbsenceDeadline(player, state.absenceDeadline());
                        });
                    }
                    if (aggregate.game() != null) gameLifecycleService.getObject().resumeRollbackTimers(roomId);
                }
            } else {
                // A captured absence is itself accepted state: retain its pending tombstone.
                // Only an unavailable preimage makes a retry unsafe after rejection.
                if (beforeMutation == null) pendingSaves.remove(roomId);
                evictAggregate(roomId);
            }
        } catch (Exception restoreFailure) {
            logger.error("Failed to restore aggregate for room {} after mutation failure; evicting to prevent dirty commits",
                    roomId, restoreFailure);
            evictAggregate(roomId);
            pendingSaves.remove(roomId);
            recordStorageFailure(roomId, restoreFailure instanceof RuntimeException re ? re
                    : new PersistenceException("Failed to restore aggregate for room " + roomId, restoreFailure));
        } finally {
            cleanupAbortedCreation(roomId);
        }
    }

    /**
     * Rehydrates the room and optional game state into the active service registries
     * from a recovered snapshot, or evicts any uncommitted game if the snapshot was in lobby state.
     *
     * @param roomId    identifier of the room being restored
     * @param aggregate deserialized room and game snapshot to restore
     */
    private void restoreAggregate(String roomId, RecoveredAggregate aggregate) {
        Room restoredRoom = aggregate.room();
        if (restoredRoom != null) {
            roomService.getObject().restoreRoom(restoredRoom, aggregate.currentHost());
        }
        Game restoredGame = aggregate.game();
        if (restoredGame != null) {
            gameLifecycleService.getObject().restoreGame(restoredGame);
        } else {
            try {
                GameLifecycleService games = gameLifecycleService.getObject();
                if (games != null) {
                    games.evictGame(roomId);
                }
            } catch (Exception e) {
                logger.warn("Error evicting game for room {} during aggregate restoration", roomId, e);
            }
        }
    }

    /**
     * Cancels active runtime tasks and removes both the game and room aggregates
     * from the in-memory registries when no committed state can be restored.
     *
     * @param roomId identifier of the room and game aggregate to evict
     */
    private void evictAggregate(String roomId) {
        try {
            GameLifecycleService games = gameLifecycleService.getObject();
            if (games != null) {
                games.cancelRuntimeTasks(roomId, false);
                games.evictGame(roomId);
            }
        } catch (Exception e) {
            logger.warn("Error cleaning up game for room {} during eviction", roomId, e);
        }
        try {
            RoomService rooms = roomService.getObject();
            if (rooms != null) {
                rooms.destroyRoom(roomId);
            }
        } catch (Exception e) {
            logger.warn("Error destroying room {} during eviction", roomId, e);
        }
    }

    /** Removes an uncommitted creation WAL only when no accepted room remains. */
    private void cleanupAbortedCreation(String roomId) {
        try {
            recordsSinceCompaction.remove(roomId);
            RoomService rooms = roomService.getObject();
            if ((rooms == null || rooms.getRoom(roomId) == null) && store.recoverLatest(roomId).isEmpty()) {
                store.delete(roomId);
            }
        } catch (Exception cleanupError) {
            logger.error("Failed to clean up aborted WAL for room {}", roomId, cleanupError);
        }
    }

    /** Determines whether the accepted WAL prefix is large enough to compact. */
    private boolean shouldCompact(String roomId) {
        int records = recordsSinceCompaction.computeIfAbsent(roomId, ignored -> new AtomicInteger())
                .addAndGet(2);
        return records >= properties.getCompactAfterRecords()
                || store.walSize(roomId) >= properties.getCompactAfterBytes();
    }

    /** Resolves the annotated aggregate identity before any mutation is executed. */
    private String resolveRoomId(ProceedingJoinPoint joinPoint, String expression) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        String[] names = parameterNames.getParameterNames(method);
        EvaluationContext context = new StandardEvaluationContext();
        Object[] arguments = joinPoint.getArgs();
        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                context.setVariable(names[i], arguments[i]);
            }
        }
        Object value = expressionParser.parseExpression(expression).getValue(context);
        if (!(value instanceof String roomId) || roomId.isBlank()) {
            throw new PersistenceException("Durable mutation did not resolve a room ID");
        }
        return roomId;
    }
}
