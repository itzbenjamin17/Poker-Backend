package com.pokergame.persistence.transaction;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups a room mutation with effects released after it is accepted.
 * <p>
 * Nested mutations share a thread-local context. Effects are queued until the
 * persistence attempt finishes, including when a valid mutation continues in memory.
 * </p>
 */
public final class DurableTransactionContext {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(DurableTransactionContext.class);
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    /**
     * Prevents creation directly, as the save process should only be managed by the interceptor.
     */
    private DurableTransactionContext() {
    }

    /**
     * Starts tracking a new save operation for a room.
     *
     * @param roomId the ID of the room being saved
     */
    static void begin(String roomId) {
        CURRENT.set(new Context(roomId, new ArrayList<>()));
    }

    /**
     * Checks which room we are currently saving. This helps us catch mistakes if
     * someone tries to save a different room while this one is still saving.
     *
     * @return the active room ID, or null if we aren't saving right now
     */
    static String currentRoomId() {
        Context context = CURRENT.get();
        return context == null ? null : context.roomId();
    }

    /**
     * Queues an effect to run only after the current mutation commits successfully to the durable WAL.
     * If the domain mutation fails, queued callbacks are discarded without execution.
     * If no durable transaction context is active on the current thread, the effect runs immediately.
     *
     * @param action effect to run after successful durable commit
     */
    public static void afterCommit(Runnable action) {
        Context context = CURRENT.get();
        if (context == null) {
            action.run();
        } else {
            context.afterCommit().add(action);
        }
    }

    /**
     * Executes all queued post-commit effects independently after a successful durable commit.
     * A failure in one callback is logged and does not prevent subsequent callbacks from executing.
     */
    static void complete() {
        Context context = CURRENT.get();
        CURRENT.remove();
        if (context != null) {
            for (Runnable action : context.afterCommit()) {
                try {
                    action.run();
                } catch (RuntimeException failure) {
                    logger.error("Accepted mutation callback failed for room {} ({})",
                            context.roomId(), failure.getClass().getSimpleName());
                }
            }
        }
    }

    /**
     * Discards effects when the domain mutation fails, without publishing success.
     */
    static void discard() {
        CURRENT.remove();
    }

    /**
     * Holds the room ID and a list of actions to run when the save finishes.
     *
     * @param roomId      the room being saved
     * @param afterCommit effects to run once the mutation is accepted
     */
    private record Context(String roomId, List<Runnable> afterCommit) {
    }
}
