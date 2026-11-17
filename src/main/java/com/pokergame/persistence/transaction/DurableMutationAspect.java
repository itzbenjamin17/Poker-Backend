package com.pokergame.persistence.transaction;

import com.pokergame.model.Room;
import com.pokergame.persistence.RoomAggregate;
import com.pokergame.persistence.RoomAggregates;
import com.pokergame.persistence.config.PersistenceException;
import com.pokergame.persistence.config.PersistenceProperties;
import com.pokergame.persistence.snapshot.AggregateSnapshotMapper;
import com.pokergame.persistence.snapshot.AggregateStateImage;
import com.pokergame.persistence.snapshot.RecoveredAggregate;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.persistence.wal.WalTransaction;
import com.pokergame.util.RoomCoordinator;
import com.pokergame.util.MdcKeys;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
import com.pokergame.util.LockLease;
import org.springframework.scheduling.annotation.Scheduled;

import static java.util.Objects.requireNonNull;

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
    private final RoomAggregates aggregates;
    private final PersistenceProperties properties;
    private final RoomCoordinator roomCoordinator;
    private final ConcurrentHashMap<String, AtomicInteger> recordsSinceCompaction = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingSave> pendingSaves = new ConcurrentHashMap<>();
    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();

    /**
     * Creates this aspect.
     *
     * @param store                storage for saving game data, or null for room locking only
     * @param snapshotMapper       helper to convert the game into a format we can save
     * @param aggregates           the live rooms and games
     * @param properties           settings for when to clean up old save files
     * @param roomCoordinator      coordinator managing room-scoped concurrency boundaries
     */
    public DurableMutationAspect(EncryptedWalStore store, AggregateSnapshotMapper snapshotMapper,
            RoomAggregates aggregates, PersistenceProperties properties, RoomCoordinator roomCoordinator) {
        this.store = store;
        this.snapshotMapper = snapshotMapper;
        this.aggregates = aggregates;
        this.properties = properties;
        this.roomCoordinator = roomCoordinator;
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
            LockLease lease = roomCoordinator.acquire(roomId);
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
            String activeRoom = PostSaveActions.currentRoomId();
            if (activeRoom != null) {
                if (!activeRoom.equals(roomId)) {
                    throw new PersistenceException("Nested durable mutations cannot cross room boundaries");
                }
                logger.debug("Nested durable mutation joined existing transaction for roomId={}", roomId);
                return joinPoint.proceed();
            }

            try (LockLease _ = roomCoordinator.acquire(roomId)) {
                PostSaveActions.begin(roomId);
                RollbackState beforeMutation = null;
                try {
                    beforeMutation = captureRollbackState(roomId);
                } catch (RuntimeException captureFailure) {
                    // Snapshot trouble must not refuse an otherwise valid in-memory command.
                    recordStorageFailure(roomId, captureFailure);
                }
                boolean hasPreExistingRoom = beforeMutation != null && beforeMutation.image() != null;
                boolean prepareFailed = false;
                WalTransaction transaction = null;
                if (hasPreExistingRoom && store.isHealthy(roomId) && !store.requiresCheckpoint(roomId)) {
                    try {
                        transaction = store.prepare(roomId);
                    } catch (RuntimeException failure) {
                        prepareFailed = true;
                        recordStorageFailure(roomId, failure);
                    }
                }
                Object result;
                try {
                    result = joinPoint.proceed();
                } catch (Throwable failure) {
                    logger.debug("Durable mutation discarded due to failure: class={} roomId={}", failure.getClass().getSimpleName(), roomId);
                    PostSaveActions.clear();
                    rollbackOrEvictAggregate(roomId, beforeMutation, prepareFailed, transaction != null);
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
                PostSaveActions.executeAll();
                return result;
            } finally {
                PostSaveActions.clear();
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
    @Scheduled(fixedDelayString = "#{@'poker.persistence-com.pokergame.persistence.config.PersistenceProperties'.retryIntervalMs}",
            initialDelayString = "#{@'poker.persistence-com.pokergame.persistence.config.PersistenceProperties'.retryIntervalMs}")
    public void retryPendingSaves() {
        for (String roomId : pendingSaves.keySet()) {
            LockLease lease = roomCoordinator.tryAcquire(roomId);
            if (lease == null) continue;
            try (lease) {
                try {
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
    }

    private PendingSave captureState(String roomId) {
        roomCoordinator.assertHeldByCurrentThread(roomId);
        RoomAggregate aggregate = aggregates.find(roomId);
        if (aggregate == null) return new PendingSave(snapshotMapper.serializeDeletion(roomId), true);
        // Keep capture and serialization within the stable room coordination boundary.
        AggregateStateImage stateImage = snapshotMapper.captureStateImage(aggregate.room(), aggregate.game());
        return new PendingSave(snapshotMapper.serialize(stateImage), false);
    }

    private void saveState(String roomId, PendingSave pending, WalTransaction transaction) {
        boolean checkpoint = transaction == null || !store.isHealthy(roomId) || store.requiresCheckpoint(roomId);
        if (checkpoint) {
            store.checkpoint(roomId, pending.image());
            recordsSinceCompaction.remove(roomId);
        } else {
            store.commit(transaction, pending.image());
        }
        if (pending.deleted()) {
            if (!store.isHealthy(roomId)) return;
            store.delete(roomId);
            recordsSinceCompaction.remove(roomId);
        } else if (!checkpoint && shouldCompact(roomId)) {
            store.compact(roomId, pending.image());
            recordsSinceCompaction.remove(roomId);
        }
        pendingSaves.remove(roomId, pending);
    }

    private void recordStorageFailure(String roomId, RuntimeException failure) {
        store.recordWriteFailure(roomId, failure);
        logger.warn("Persistence unavailable for room {}; continuing in memory ({})",
                roomId, failure.getClass().getSimpleName());
    }

    private record PendingSave(byte[] image, boolean deleted) {
        private PendingSave { image = image == null ? null : image.clone(); }
    }

    private RollbackState captureRollbackState(String roomId) {
        roomCoordinator.assertHeldByCurrentThread(roomId);
        RoomAggregate aggregate = aggregates.find(roomId);
        if (aggregate == null) return new RollbackState(null, Map.of());
        Room room = aggregate.room();
        AggregateStateImage image = snapshotMapper.captureStateImage(room, aggregate.game());
        Map<String, ConnectionState> connections = new HashMap<>();
        for (String player : room.getPlayers()) {
            connections.put(player, new ConnectionState(room.isPlayerConnected(player), room.getAbsenceDeadline(player)));
        }
        return new RollbackState(image, Map.copyOf(connections));
    }

    /** Process-local connectivity is intentionally separate from the persisted image. */
    private record ConnectionState(boolean connected, Long absenceDeadline) { }

    /** A null image denotes a room that did not exist before the rejected command. */
    private record RollbackState(AggregateStateImage image, Map<String, ConnectionState> connections) { }

    /**
     * Restores the independent pre-mutation RAM image after a rejection. Disk may lag
     * accepted gameplay, so WAL bytes are never a safe rollback source.
     */
    private void rollbackOrEvictAggregate(String roomId, RollbackState beforeMutation, boolean prepareFailed, boolean prepared) {
        roomCoordinator.assertHeldByCurrentThread(roomId);
        try {
            if (beforeMutation != null && beforeMutation.image() != null) {
                RecoveredAggregate aggregate = snapshotMapper.restoreStateImage(beforeMutation.image());
                // The one place connection flags are carried over: they are process-local, not in the image.
                beforeMutation.connections().forEach((player, state) -> {
                    aggregate.room().setPlayerConnected(player, state.connected());
                    aggregate.room().setAbsenceDeadline(player, state.absenceDeadline());
                });
                aggregates.replace(new RoomAggregate(aggregate.room(), aggregate.game()));
                if (prepareFailed) queuePrepareRecoverySaveIfNeeded(roomId);
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
            cleanupAbortedCreation(roomId, prepared);
        }
    }

    /** Ensures a pre-mutation WAL prepare failure on an existing room is retried later. */
    private void queuePrepareRecoverySaveIfNeeded(String roomId) {
        if (pendingSaves.containsKey(roomId)) return;
        try {
            pendingSaves.put(roomId, captureState(roomId));
        } catch (RuntimeException captureFailure) {
            pendingSaves.putIfAbsent(roomId, new PendingSave(null, false));
            recordStorageFailure(roomId, captureFailure);
        }
    }

    // A rejected createRoom ends up here: no room was announced and queued effects were already
    // discarded, so nothing is published for it.
    private void evictAggregate(String roomId) {
        try {
            aggregates.remove(roomId);
        } catch (Exception e) {
            logger.warn("Error evicting room {} during rollback cleanup", roomId, e);
        }
    }

    /** Removes an uncommitted creation WAL only when prepare ran and no accepted room remains. */
    private void cleanupAbortedCreation(String roomId, boolean prepared) {
        roomCoordinator.assertHeldByCurrentThread(roomId);
        try {
            if (aggregates.find(roomId) == null) {
                recordsSinceCompaction.remove(roomId);
                if (prepared && store.isHealthy(roomId) && store.recoverLatest(roomId).isEmpty()) {
                    store.delete(roomId);
                }
            }
        } catch (Exception cleanupError) {
            logger.error("Failed to clean up aborted WAL for room {}", roomId, cleanupError);
        }
    }

    private boolean shouldCompact(String roomId) {
        int records = recordsSinceCompaction.computeIfAbsent(roomId, ignored -> new AtomicInteger())
                .addAndGet(2);
        return records >= properties.getCompactAfterRecords()
                || store.walSize(roomId) >= properties.getCompactAfterBytes();
    }

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
