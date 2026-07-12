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

    public DurableMutationAspect(EncryptedWalStore store, AggregateSnapshotMapper snapshotMapper,
            ObjectProvider<RoomService> roomService, ObjectProvider<GameLifecycleService> gameLifecycleService,
            PersistenceProperties properties) {
        this.store = store;
        this.snapshotMapper = snapshotMapper;
        this.roomService = roomService;
        this.gameLifecycleService = gameLifecycleService;
        this.properties = properties;
    }

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
