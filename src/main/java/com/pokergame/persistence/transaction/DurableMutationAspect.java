package com.pokergame.persistence.transaction;

import com.pokergame.model.Game;
import com.pokergame.model.Room;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import com.pokergame.util.MdcKeys;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.persistence.wal.WalTransaction;
import com.pokergame.persistence.snapshot.AggregateSnapshotMapper;
import com.pokergame.persistence.config.PersistenceProperties;
import com.pokergame.persistence.config.PersistenceException;

/**
 * Automatically saves the poker game to disk after certain methods finish running.
 * <p>
 * If one saved method calls another, it groups them into a single save operation at
 * the end. It also makes sure we only modify one room at a time, preventing corrupted saves.
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
    private final ConcurrentHashMap<String, ReentrantLock> roomLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> recordsSinceCompaction = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingSave> pendingSaves = new ConcurrentHashMap<>();
    private final Set<String> blockedSnapshotRooms = ConcurrentHashMap.newKeySet();
    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();

    /**
     * Creates this aspect using lazy services to avoid circular dependency issues when setting up the app.
     *
     * @param store                storage for saving game data
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
     * Serializes a room mutation and attempts to save it before publishing effects.
     * Storage failures retain the accepted in-memory state and its queued effects;
     * failures from the mutation itself still propagate without success effects.
     *
     * @param joinPoint intercepted domain mutation
     * @param mutation room identity expression
     * @return the domain result, including when persistence is temporarily unavailable
     * @throws Throwable if identity resolution or the domain mutation fails
     */
    @Around("@annotation(mutation)")
    public Object persist(ProceedingJoinPoint joinPoint, DurableMutation mutation) throws Throwable {
        String roomId = resolveRoomId(joinPoint, mutation.roomId());
        String previousRoom = MDC.get(MdcKeys.ROOM_ID);
        MDC.put(MdcKeys.ROOM_ID, roomId);
        try {
            String activeRoom = DurableTransactionContext.currentRoomId();
            if (activeRoom != null) {
                if (!activeRoom.equals(roomId)) {
                    throw new PersistenceException("Nested durable mutations cannot cross room boundaries");
                }
                return joinPoint.proceed();
            }

            ReentrantLock lock = roomLocks.computeIfAbsent(roomId, ignored -> new ReentrantLock());
            lock.lock();
            try {
                DurableTransactionContext.begin(roomId);
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
                    DurableTransactionContext.discard();
                    PendingSave pending = pendingSaves.get(roomId);
                    if (pending != null && pending.image() == null) blockedSnapshotRooms.add(roomId);
                    cleanupAbortedCreation(roomId);
                    throw failure;
                }

                // Invalidate an older accepted image before serialization can fail.
                blockedSnapshotRooms.remove(roomId);
                pendingSaves.put(roomId, new PendingSave(null, false));
                try {
                    PendingSave pending = captureState(roomId);
                    pendingSaves.put(roomId, pending);
                    saveState(roomId, pending, transaction);
                } catch (RuntimeException failure) {
                    recordStorageFailure(roomId, failure);
                }
                DurableTransactionContext.complete();
                return result;
            } finally {
                DurableTransactionContext.discard();
                lock.unlock();
            }
        } finally {
            if (previousRoom == null) MDC.remove(MdcKeys.ROOM_ID);
            else MDC.put(MdcKeys.ROOM_ID, previousRoom);
        }
    }

    /**
     * Retries accepted state images without replaying player actions. Non-blocking
     * room locks avoid delaying active mutations or introducing cross-room lock order.
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void retryPendingSaves() {
        for (String roomId : pendingSaves.keySet()) {
            ReentrantLock lock = roomLocks.computeIfAbsent(roomId, ignored -> new ReentrantLock());
            if (!lock.tryLock()) continue;
            try {
                PendingSave pending = pendingSaves.get(roomId);
                if (pending == null) continue;
                if (pending.image() == null) {
                    // A rejected mutation may have partially changed live objects.
                    // Only a later accepted mutation can supply a replacement image.
                    if (blockedSnapshotRooms.contains(roomId)) continue;
                    pending = captureState(roomId);
                    pendingSaves.put(roomId, pending);
                }
                saveState(roomId, pending, null);
            } catch (RuntimeException failure) {
                recordStorageFailure(roomId, failure);
            } finally {
                lock.unlock();
            }
        }
    }

    /** Captures a complete image or tombstone while the room mutation lock is held. */
    private PendingSave captureState(String roomId) {
        Room room = roomService.getObject().getRoom(roomId);
        if (room == null) return new PendingSave(snapshotMapper.serializeDeletion(roomId), true);
        Game game = gameLifecycleService.getObject().getGame(roomId);
        return new PendingSave(snapshotMapper.serialize(room,
                roomService.getObject().getCurrentHost(roomId), game), false);
    }

    /** Saves a current image, replacing uncertain WAL tails rather than appending to them. */
    private void saveState(String roomId, PendingSave pending, WalTransaction transaction) {
        boolean checkpoint = transaction == null || !store.isHealthy() || store.requiresCheckpoint(roomId);
        if (checkpoint) {
            store.checkpoint(roomId, pending.image());
            recordsSinceCompaction.remove(roomId);
        } else {
            store.commit(transaction, pending.image());
        }
        if (pending.deleted()) {
            // A saved tombstone is sufficient while another room still needs repair.
            if (!store.isHealthy()) return;
            store.delete(roomId);
            recordsSinceCompaction.remove(roomId);
        } else if (!checkpoint && shouldCompact(roomId)) {
            store.compact(roomId, pending.image());
            recordsSinceCompaction.remove(roomId);
        }
        pendingSaves.remove(roomId, pending);
    }

    /** Marks recovery protection degraded without rejecting an accepted game mutation. */
    private void recordStorageFailure(String roomId, RuntimeException failure) {
        store.recordWriteFailure(roomId, failure);
        logger.warn("Persistence unavailable for room {}; continuing in memory ({})",
                roomId, failure.getClass().getSimpleName());
    }

    /** Removes a PREPARE-only WAL for a rejected room creation, retaining existing games. */
    private void cleanupAbortedCreation(String roomId) {
        try {
            if (!store.isHealthy() || roomService.getObject().getRoom(roomId) != null) return;
            if (store.recoverLatest(roomId).isEmpty()) store.delete(roomId);
        } catch (RuntimeException failure) {
            logger.warn("Could not clean up rejected room creation {} ({})",
                    roomId, failure.getClass().getSimpleName());
        }
    }

    /** Owns immutable serialized state until a full save or deletion succeeds. */
    private record PendingSave(byte[] image, boolean deleted) {
        private PendingSave {
            image = image == null ? null : image.clone();
        }
    }

    /**
     * Checks if we need to clean up old save files to save disk space and improve load times.
     * We look at both the number of updates and the total file size.
     *
     * @param roomId the ID of the room
     * @return true if we should compact the save files
     */
    private boolean shouldCompact(String roomId) {
        int records = recordsSinceCompaction.computeIfAbsent(roomId, ignored -> new AtomicInteger())
                .addAndGet(2);
        return records >= properties.getCompactAfterRecords()
                || store.walSize(roomId) >= properties.getCompactAfterBytes();
    }

    /**
     * Extracts the room ID directly from the method arguments using the provided expression.
     * We do this early so we can lock the room before looking at any game data.
     *
     * @param joinPoint the intercepted method
     * @param expression the rule for finding the room ID
     * @return the resolved room ID
     * @throws PersistenceException if a valid room ID cannot be found
     */
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
