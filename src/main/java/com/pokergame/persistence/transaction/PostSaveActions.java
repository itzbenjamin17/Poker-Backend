package com.pokergame.persistence.transaction;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds broadcasts and timer registrations in a per-thread queue during a {@link DurableMutation}
 * so they only run after the room change succeeds and finishes saving to the WAL.
 * <p>
 * Nested calls for the same room share one queue. If the change throws an error, the queue is
 * discarded so half-finished state is never broadcast.
 * </p>
 */
public final class PostSaveActions {
    private static final Logger logger = LoggerFactory.getLogger(PostSaveActions.class);
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private PostSaveActions() {
    }

    /**
     * Queues {@code action} to run after the current {@link DurableMutation} finishes saving
     * (including if disk saving falls back to memory-only mode). If no save is active on this thread
     * (for example, when persistence is disabled), {@code action} runs immediately.
     * <p>
     * Any {@link RuntimeException} thrown by {@code action} is caught and logged instead of failing
     * the caller.
     * </p>
     *
     * @param action the broadcast, event, or timer registration to run after saving
     */
    public static void runAfterSave(Runnable action) {
        requireNonNull(action, "action must not be null");
        Context context = CURRENT.get();
        if (context == null) {
            runSafely(null, action);
        } else {
            context.actions().add(action);
        }
    }

    /**
     * Opens a new post-save queue on the current thread for {@code roomId}.
     *
     * @param roomId the room being modified
     */
    static void begin(String roomId) {
        CURRENT.set(new Context(roomId, new ArrayList<>()));
    }

    /**
     * Returns the room ID of the active post-save queue on this thread, or {@code null} if none.
     *
     * @return the active room ID, or {@code null}
     */
    static String currentRoomId() {
        Context context = CURRENT.get();
        return context == null ? null : context.roomId();
    }

    /**
     * Clears the current thread's queue and runs each queued action in order.
     * A failure in one action is logged and does not prevent the remaining actions from running.
     */
    static void executeAll() {
        Context context = CURRENT.get();
        CURRENT.remove();
        if (context != null) {
            for (Runnable action : context.actions()) {
                runSafely(context.roomId(), action);
            }
        }
    }

    /**
     * Discards the current thread's queue without running any queued actions.
     */
    static void clear() {
        CURRENT.remove();
    }

    private static void runSafely(String roomId, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            String roomSuffix = roomId == null ? "" : " for room " + roomId;
            logger.error("Post-save action failed{} ({})",
                    roomSuffix, failure.getClass().getSimpleName(), failure);
        }
    }

    private record Context(String roomId, List<Runnable> actions) {
    }
}
