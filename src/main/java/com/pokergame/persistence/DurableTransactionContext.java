package com.pokergame.persistence;

import java.util.ArrayList;
import java.util.List;

/**
 * Carries transaction ownership and deferred side effects across nested service
 * calls on the current thread.
 * <p>
 * This is intentionally thread-local because the supported deployment is
 * single-process and mutation execution is synchronous; runtime scheduled work is
 * persisted as data before it crosses a thread boundary.
 * </p>
 */
public final class DurableTransactionContext {
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    /**
     * Prevents construction because transaction state must be scoped through the
     * static advice lifecycle.
     */
    private DurableTransactionContext() {
    }

    /**
     * Starts the outer transaction context used by nested same-room mutations.
     *
     * @param roomId room that owns the transaction
     */
    static void begin(String roomId) {
        CURRENT.set(new Context(roomId, new ArrayList<>()));
    }

    /**
     * Exposes transaction ownership so nested advice can join the same room while
     * rejecting unsupported cross-room nesting.
     *
     * @return active room ID, or {@code null} outside a durable transaction
     */
    static String currentRoomId() {
        Context context = CURRENT.get();
        return context == null ? null : context.roomId();
    }

    /**
     * Defers client visibility and runtime scheduling until the state image is
     * committed. Outside a durable mutation the action runs immediately so query
     * and legacy nonpersistent paths retain their normal behavior.
     *
     * @param action side effect that requires committed state
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
     * Removes transaction state before running callbacks so callback-triggered work
     * cannot accidentally join a transaction whose commit has already finished.
     */
    static void complete() {
        Context context = CURRENT.get();
        CURRENT.remove();
        if (context != null) {
            context.afterCommit().forEach(Runnable::run);
        }
    }

    /**
     * Drops callbacks after a failed mutation or commit because clients must never
     * observe state that recovery cannot reproduce.
     */
    static void discard() {
        CURRENT.remove();
    }

    /**
     * Groups the room owner and ordered callbacks as one thread-confined value.
     *
     * @param roomId     room owning the outer transaction
     * @param afterCommit callbacks released after the durable commit
     */
    private record Context(String roomId, List<Runnable> afterCommit) {
    }
}
