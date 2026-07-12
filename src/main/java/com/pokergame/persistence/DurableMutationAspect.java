package com.pokergame.persistence;

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

/**
 * Enforces the durability boundary around {@link DurableMutation} service seams.
 * <p>
 * One outer transaction owns a room lock and WAL pair while nested same-room
 * service calls join it. This keeps business services composable without allowing
 * partial snapshots or cross-room transactions that the per-room WAL cannot make
 * atomic.
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
     * Creates the advice with lazy service providers to avoid a circular dependency
     * between proxied mutation services and the snapshot capture boundary.
     *
     * @param store                encrypted WAL store
     * @param snapshotMapper       aggregate compatibility mapper
     * @param roomService          lazy room service provider
     * @param gameLifecycleService lazy game service provider
     * @param properties           compaction policy
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
     * Flushes prepare state before mutation, commits the resulting state image, and
     * releases client-visible callbacks only after that commit succeeds.
     * <p>
     * Post-commit compaction and deletion failures degrade health rather than
     * retroactively failing an already durable and possibly visible command.
     * </p>
     *
     * @param joinPoint intercepted mutation invocation
     * @param mutation durable-mutation metadata
     * @return intercepted method result
     * @throws Throwable if preparation, mutation, snapshot capture, or commit fails
     */
    @Around("@annotation(mutation)")
    public Object persist(ProceedingJoinPoint joinPoint, DurableMutation mutation) throws Throwable {
        String roomId = resolveRoomId(joinPoint, mutation.roomId());
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
            WalTransaction transaction = store.prepare(roomId);
            DurableTransactionContext.begin(roomId);
            Object result;
            try {
                result = joinPoint.proceed();
            } catch (Throwable failure) {
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
                // The COMMIT is already durable and client callbacks have run. Keep
                // this mutation successful while the store's unhealthy state rejects
                // every subsequent mutation until an operator intervenes.
                logger.error("Post-commit WAL maintenance failed for room {}", roomId, maintenanceFailure);
            }
            return result;
        } catch (Throwable failure) {
            DurableTransactionContext.discard();
            throw failure;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Applies both record-count and byte-size thresholds because many small updates
     * and a few unusually large snapshots create different operational pressure.
     *
     * @param roomId room whose WAL was just extended
     * @return whether the WAL should be replaced with its latest committed image
     */
    private boolean shouldCompact(String roomId) {
        int records = recordsSinceCompaction.computeIfAbsent(roomId, ignored -> new AtomicInteger())
                .addAndGet(2);
        return records >= properties.getCompactAfterRecords()
                || store.walSize(roomId) >= properties.getCompactAfterBytes();
    }

    /**
     * Resolves room identity from the service contract so transaction ownership is
     * stable before any mutable aggregate is inspected.
     *
     * @param joinPoint intercepted invocation and arguments
     * @param expression configured room-ID expression
     * @return resolved nonblank room ID
     * @throws PersistenceException if the expression does not resolve a room ID
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
