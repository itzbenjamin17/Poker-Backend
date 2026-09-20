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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
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
     * Runs the method, saves the final game result, and only lets players see the changes
     * if the save was completely successful.
     * <p>
     * If cleaning up old save files fails later, we just log it instead of undoing the successful save.
     * </p>
     *
     * @param joinPoint the intercepted method
     * @param mutation details about the method
     * @return the result of the method
     * @throws Throwable if preparing, running, or saving fails
     */
    @Around("@annotation(mutation)")
    public Object persist(ProceedingJoinPoint joinPoint, DurableMutation mutation) throws Throwable {
        String roomId = resolveRoomId(joinPoint, mutation.roomId());
        String activeRoom = DurableTransactionContext.currentRoomId();
        // Prevent operations from bleeding across different poker rooms
        if (activeRoom != null) {
            if (!activeRoom.equals(roomId)) {
                throw new PersistenceException("Nested durable mutations cannot cross room boundaries");
            }
            return joinPoint.proceed();
        }

        ReentrantLock lock = roomLocks.computeIfAbsent(roomId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            WalTransaction transaction = store.prepare(roomId);
            DurableTransactionContext.begin(roomId);
            Object result;
            try {
                result = joinPoint.proceed();
            } catch (Throwable failure) {
                // Discard pending post-save actions since the method failed
                DurableTransactionContext.discard();
                throw failure;
            }

            Room room = roomService.getObject().getRoom(roomId);
            byte[] image;
            boolean deleted = room == null;
            if (deleted) {
                image = snapshotMapper.serializeDeletion(roomId);
            } else {
                Game game = gameLifecycleService.getObject().getGame(roomId);
                image = snapshotMapper.serialize(room, roomService.getObject().getCurrentHost(roomId), game);
            }
            store.commit(transaction, image);

            DurableTransactionContext.complete();
            try {
                if (deleted) {
                    store.delete(roomId);
                } else if (shouldCompact(roomId)) {
                    store.compact(roomId, image);
                    recordsSinceCompaction.get(roomId).set(0);
                }
            } catch (PersistenceException maintenanceFailure) {
                // The save was successful and players may have been notified.
                // Log the file cleanup error, but let the game continue.
                logger.error("Post-commit WAL maintenance failed for room {}", roomId, maintenanceFailure);
            }
            return result;
        } catch (Throwable failure) {
            DurableTransactionContext.discard();
            try {
                // Remove the room save files entirely if it was completely empty
                if (store.recoverLatest(roomId).isEmpty()) {
                    store.delete(roomId);
                }
            } catch (Exception cleanupError) {
                logger.error("Failed to clean up aborted WAL for room {}", roomId, cleanupError);
            }
            throw failure;
        } finally {
            lock.unlock();
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
