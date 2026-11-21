package com.pokergame.persistence.transaction;

import com.pokergame.model.Room;
import com.pokergame.persistence.PersistenceException;
import com.pokergame.persistence.RoomAggregate;
import com.pokergame.persistence.RoomAggregates;
import com.pokergame.persistence.config.PersistenceProperties;
import com.pokergame.persistence.snapshot.RecoveredRoom;
import com.pokergame.persistence.snapshot.RoomSnapshot;
import com.pokergame.persistence.snapshot.RoomSnapshotMapper;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.persistence.wal.WalTransaction;
import com.pokergame.util.LockLease;
import com.pokergame.util.MdcUtils;
import com.pokergame.util.RoomCoordinator;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Intercepts {@link DurableMutation} methods to lock each room, roll back on failure,
 * save the updated {@code RoomSnapshot} to the WAL, and run queued {@link PostSaveActions}.
 * Nested calls for the same room share one save, failed disk writes retry in the background,
 * and disabling storage keeps room locking active.
 */
@Aspect
public final class DurableMutationAspect {
    private static final Logger logger = LoggerFactory.getLogger(DurableMutationAspect.class);
    // A save appends a prepare record and a commit record to the room's log.
    private static final int WAL_RECORDS_PER_SAVE = 2;

    private final EncryptedWalStore store;
    private final RoomSnapshotMapper snapshotMapper;
    private final RoomAggregates aggregates;
    private final PersistenceProperties properties;
    private final RoomCoordinator roomCoordinator;

    private final ConcurrentHashMap<String, AtomicInteger> recordsSinceCompaction = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingSave> pendingSaves = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Method, Integer> roomIdParameterIndexes = new ConcurrentHashMap<>();

    /**
     * Creates this aspect.
     *
     * @param store                storage for saving game data, or null for room locking only
     * @param snapshotMapper       helper to convert the game into a format we can save
     * @param aggregates           the live rooms and games
     * @param properties           settings for when to clean up old save files
     * @param roomCoordinator      coordinator managing room-scoped concurrency boundaries
     */
    public DurableMutationAspect(EncryptedWalStore store, RoomSnapshotMapper snapshotMapper,
            RoomAggregates aggregates, PersistenceProperties properties, RoomCoordinator roomCoordinator) {
        this.store = store;
        this.snapshotMapper = snapshotMapper;
        this.aggregates = aggregates;
        this.properties = properties;
        this.roomCoordinator = roomCoordinator;
    }

    /**
     * Runs the intercepted method under the room lock identified by {@link RoomId} or
     * {@link GameId}, saving to the WAL and firing {@link PostSaveActions} when storage is enabled.
     *
     * @param joinPoint the intercepted method
     * @return the method's return value
     * @throws Throwable if the intercepted method throws
     */
    @Around("@annotation(com.pokergame.persistence.transaction.DurableMutation)")
    public Object persist(ProceedingJoinPoint joinPoint) throws Throwable {
        String roomId = resolveRoomId(joinPoint);
        try (MdcUtils.MdcScope _ = MdcUtils.withRoomId(roomId)) {
            return store == null ? runWithoutStorage(joinPoint, roomId) : runWithStorage(joinPoint, roomId);
        }
    }

    /**
     * Periodically retries pending room saves that previously failed due to transient storage errors.
     */
    @Scheduled(fixedDelayString = "#{@'poker.persistence-com.pokergame.persistence.config.PersistenceProperties'.retryIntervalMs()}",
            initialDelayString = "#{@'poker.persistence-com.pokergame.persistence.config.PersistenceProperties'.retryIntervalMs()}")
    public void retryPendingSaves() {
        for (String roomId : pendingSaves.keySet()) {
            LockLease lease = roomCoordinator.tryAcquire(roomId);
            if (lease == null) continue;
            try (lease) {
                PendingSave pending = pendingSaves.get(roomId);
                if (pending == null) continue;
                if (pending.image() == null) {
                    try {
                        pending = captureState(roomId);
                    } catch (RuntimeException captureFailure) {
                        recordStorageFailure(roomId, captureFailure);
                        continue;
                    }
                    pendingSaves.put(roomId, pending);
                }
                try {
                    saveState(roomId, pending, null);
                } catch (RuntimeException saveFailure) {
                    // The store has already recorded this failure.
                    logStorageUnavailable(roomId, saveFailure);
                }
            }
        }
    }

    private Object runWithoutStorage(ProceedingJoinPoint joinPoint, String roomId) throws Throwable {
        try (LockLease _ = roomCoordinator.acquire(roomId)) {
            return joinPoint.proceed();
        }
    }

    private Object runWithStorage(ProceedingJoinPoint joinPoint, String roomId) throws Throwable {
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
                    // The store has already recorded this failure.
                    prepareFailed = true;
                    logStorageUnavailable(roomId, failure);
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

            captureAndSave(roomId, transaction);
            PostSaveActions.executeAll();
            return result;
        } finally {
            PostSaveActions.clear();
        }
    }

    private void captureAndSave(String roomId, WalTransaction transaction) {
        // An accepted newer mutation supersedes any old retry image before serialization starts.
        pendingSaves.put(roomId, PendingSave.needsCapture());
        PendingSave pending;
        try {
            pending = captureState(roomId);
        } catch (RuntimeException captureFailure) {
            recordStorageFailure(roomId, captureFailure);
            return;
        }
        pendingSaves.put(roomId, pending);
        try {
            saveState(roomId, pending, transaction);
        } catch (RuntimeException saveFailure) {
            // The store has already recorded this failure.
            logStorageUnavailable(roomId, saveFailure);
        }
    }

    private PendingSave captureState(String roomId) {
        roomCoordinator.assertHeldByCurrentThread(roomId);
        RoomAggregate aggregate = aggregates.find(roomId);
        if (aggregate == null) return new PendingSave(snapshotMapper.serializeDeletion(roomId), true);
        // Keep capture and serialization within the stable room coordination boundary.
        RoomSnapshot snapshot = snapshotMapper.captureSnapshot(aggregate.room(), aggregate.game());
        return new PendingSave(snapshotMapper.serialize(snapshot), false);
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

    /*
     * Records a failure that happened in this aspect (snapshot, serialization or restore) so the store
     * marks the room unsaved. Failures inside store calls are recorded by the store itself.
     */
    private void recordStorageFailure(String roomId, RuntimeException failure) {
        store.recordWriteFailure(roomId, failure);
        logStorageUnavailable(roomId, failure);
    }

    private void logStorageUnavailable(String roomId, RuntimeException failure) {
        logger.warn("Persistence unavailable for room {}; continuing in memory ({})",
                roomId, failure.getClass().getSimpleName());
    }

    private RollbackState captureRollbackState(String roomId) {
        roomCoordinator.assertHeldByCurrentThread(roomId);
        RoomAggregate aggregate = aggregates.find(roomId);
        if (aggregate == null) return new RollbackState(null, Map.of());
        Room room = aggregate.room();
        RoomSnapshot image = snapshotMapper.captureSnapshot(room, aggregate.game());
        Map<String, ConnectionState> connections = new HashMap<>();
        for (String player : room.getPlayers()) {
            connections.put(player, new ConnectionState(room.isPlayerConnected(player), room.getAbsenceDeadline(player)));
        }
        return new RollbackState(image, Map.copyOf(connections));
    }

    // Rolls back using the in-memory pre-change snapshot (never the WAL on disk, which may
    // be behind live gameplay during a disk outage).
    private void rollbackOrEvictAggregate(String roomId, RollbackState beforeMutation, boolean prepareFailed, boolean prepared) {
        roomCoordinator.assertHeldByCurrentThread(roomId);
        try {
            if (beforeMutation != null && beforeMutation.image() != null) {
                RecoveredRoom restored = snapshotMapper.restoreSnapshot(beforeMutation.image());
                // Copy live WebSocket connection flags back onto the restored room (they are not stored in RoomSnapshot).
                beforeMutation.connections().forEach((player, state) -> {
                    restored.room().setPlayerConnected(player, state.connected());
                    restored.room().setAbsenceDeadline(player, state.absenceDeadline());
                });
                aggregates.replace(new RoomAggregate(restored.room(), restored.game()));
                if (prepareFailed) queuePrepareRecoverySaveIfNeeded(roomId);
            } else {
                // If we failed to capture a pre-change backup (beforeMutation == null), cancel any pending
                // retry save so we never persist broken state. If the room genuinely didn't exist before
                // (beforeMutation.image() == null), keep any pending deletion tombstone so the old WAL still deletes.
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

    // Ensures a pre-mutation WAL prepare failure on an existing room is retried later.
    private void queuePrepareRecoverySaveIfNeeded(String roomId) {
        if (pendingSaves.containsKey(roomId)) return;
        try {
            pendingSaves.put(roomId, captureState(roomId));
        } catch (RuntimeException captureFailure) {
            pendingSaves.putIfAbsent(roomId, PendingSave.needsCapture());
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

    // Removes an uncommitted creation WAL only when prepare ran and no accepted room remains.
    private void cleanupAbortedCreation(String roomId, boolean prepared) {
        roomCoordinator.assertHeldByCurrentThread(roomId);
        try {
            if (aggregates.find(roomId) == null) {
                recordsSinceCompaction.remove(roomId);
                if (prepared && store.isHealthy(roomId) && !store.hasCommittedState(roomId)) {
                    store.delete(roomId);
                }
            }
        } catch (Exception cleanupError) {
            logger.error("Failed to clean up aborted WAL for room {}", roomId, cleanupError);
        }
    }

    private boolean shouldCompact(String roomId) {
        int records = recordsSinceCompaction.computeIfAbsent(roomId, ignored -> new AtomicInteger())
                .addAndGet(WAL_RECORDS_PER_SAVE);
        return records >= properties.compactAfterRecords()
                || store.walSize(roomId) >= properties.compactAfterBytes();
    }

    private String resolveRoomId(ProceedingJoinPoint joinPoint) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        int index = roomIdParameterIndexes.computeIfAbsent(method, DurableMutationAspect::findRoomIdParameter);
        if (!(joinPoint.getArgs()[index] instanceof String roomId) || roomId.isBlank()) {
            throw new PersistenceException("Durable mutation did not resolve a room ID");
        }
        return roomId;
    }

    // Finds the one parameter marked @RoomId or @GameId; the result is cached per method.
    private static int findRoomIdParameter(Method method) {
        Parameter[] parameters = method.getParameters();
        int found = -1;
        int markers = 0;
        for (int i = 0; i < parameters.length; i++) {
            int onParameter = (parameters[i].isAnnotationPresent(RoomId.class) ? 1 : 0)
                    + (parameters[i].isAnnotationPresent(GameId.class) ? 1 : 0);
            if (onParameter > 0) found = i;
            markers += onParameter;
        }
        if (markers != 1) {
            throw new IllegalStateException("@DurableMutation method " + method.toGenericString()
                    + " must mark exactly one parameter with @RoomId or @GameId, but marks " + markers);
        }
        return found;
    }

    private record PendingSave(byte[] image, boolean deleted) {
        private PendingSave { image = image == null ? null : image.clone(); }

        /** A placeholder meaning "no image yet; take a fresh snapshot before saving". */
        static PendingSave needsCapture() {
            return new PendingSave(null, false);
        }
    }

    // Process-local connectivity is intentionally separate from the persisted image.
    private record ConnectionState(boolean connected, Long absenceDeadline) { }

    // A null image denotes a room that did not exist before the rejected command.
    private record RollbackState(RoomSnapshot image, Map<String, ConnectionState> connections) { }
}
