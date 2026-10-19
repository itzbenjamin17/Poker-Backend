package com.pokergame.persistence.transaction;

import com.pokergame.model.Game;
import com.pokergame.model.Room;
import com.pokergame.persistence.config.PersistenceException;
import com.pokergame.persistence.config.PersistenceProperties;
import com.pokergame.persistence.snapshot.AggregateSnapshotMapper;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Automatically saves the poker game to disk after certain methods finish running.
 * <p>
 * If one saved method calls another, it groups them into a single save operation at
 * the end. It also makes sure we only modify one room at a time, preventing corrupted saves.
 * Storage failures retain the accepted in-memory state and its queued effects for a later save retry.
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
     * Obtains the dedicated reentrant lock for a room.
     *
     * @param roomId the room identifier
     * @return the room lock
     */
    public ReentrantLock getRoomLock(String roomId) {
        return roomLocks.computeIfAbsent(roomId, ignored -> new ReentrantLock());
    }

    /**
     * Runs the method, saves the final game result, and only lets players see the changes
     * once accepted, even if persistence is temporarily unavailable.
     *
     * @param joinPoint the intercepted method
     * @param mutation details about the method
     * @return the result of the method
     * @throws Throwable if the domain mutation itself fails
     */
    @Around("@annotation(mutation)")
    public Object persist(ProceedingJoinPoint joinPoint, DurableMutation mutation) throws Throwable {
        String roomId = resolveRoomId(joinPoint, mutation.roomId());
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

            ReentrantLock lock = getRoomLock(roomId);
            lock.lock();
            long lockStartNano = System.nanoTime();
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
                    logger.debug("Durable mutation discarded due to failure: class={} roomId={}", failure.getClass().getSimpleName(), roomId);
                    cleanupAbortedCreation(roomId);
                    throw failure;
                }

                // An accepted newer mutation supersedes any old retry image before serialization starts.
                blockedSnapshotRooms.remove(roomId);
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
                lock.unlock();
            }
        } finally {
            if (previousRoom == null) {
                MDC.remove(MdcKeys.ROOM_ID);
            } else {
                MDC.put(MdcKeys.ROOM_ID, previousRoom);
            }
        }
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void retryPendingSaves() {
        for (String roomId : pendingSaves.keySet()) {
            ReentrantLock lock = getRoomLock(roomId);
            if (!lock.tryLock()) continue;
            try {
                PendingSave pending = pendingSaves.get(roomId);
                if (pending == null) continue;
                if (pending.image() == null) {
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

    private PendingSave captureState(String roomId) {
        Room room = roomService.getObject().getRoom(roomId);
        if (room == null) return new PendingSave(snapshotMapper.serializeDeletion(roomId), true);
        Game game = gameLifecycleService.getObject().getGame(roomId);
        return new PendingSave(snapshotMapper.serialize(room,
                roomService.getObject().getCurrentHost(roomId), game), false);
    }

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

    private void recordStorageFailure(String roomId, RuntimeException failure) {
        store.recordWriteFailure(roomId, failure);
        logger.warn("Persistence unavailable for room {}; continuing in memory ({})",
                roomId, failure.getClass().getSimpleName());
    }

    private record PendingSave(byte[] image, boolean deleted) {
        private PendingSave { image = image == null ? null : image.clone(); }
    }

    private void cleanupAbortedCreation(String roomId) {
        try {
            if (roomService.getObject().getRoom(roomId) == null && store.recoverLatest(roomId).isEmpty()) {
                store.delete(roomId);
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
