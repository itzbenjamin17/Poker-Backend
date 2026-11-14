package com.pokergame.persistence.transaction;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Queues side effects (such as WebSocket broadcasts and timer scheduling) for a room change so
 * they run only after the state change succeeds and its persistence attempt completes.
 * <p>
 * Nested changes for the same room share one thread-local queue. If the change fails before
 * completion, queued actions are discarded without running.
 * </p>
 */
public final class PostSaveActions {
    private static final Logger logger = LoggerFactory.getLogger(PostSaveActions.class);
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private PostSaveActions() {
    }

    /**
     * Queues an action to run after the active room change succeeds and finishes saving.
     * If persistence falls back to memory-only mode after the change was accepted, queued actions
     * still run once. If no room save is active on the current thread, the action runs immediately.
     *
     * @param action the side effect to run after saving completes
     */
    public static void runAfterSave(Runnable action) {
        requireNonNull(action, "action must not be null");
        Context context = CURRENT.get();
        if (context == null) {
            action.run();
        } else {
            context.actions().add(action);
        }
    }

    /**
     * Starts tracking post-save actions for a room change on the current thread.
     *
     * @param roomId the ID of the room being modified
     */
    static void begin(String roomId) {
        CURRENT.set(new Context(roomId, new ArrayList<>()));
    }

    /**
     * Returns the room ID currently being saved on this thread, if any.
     *
     * @return the active room ID, or {@code null} when no save is active
     */
    static String currentRoomId() {
        Context context = CURRENT.get();
        return context == null ? null : context.roomId();
    }

    /**
     * Runs all queued actions independently after the save attempt finishes, even if saving fell
     * back to memory-only mode. A failure in one action is logged and does not stop remaining
     * actions from running.
     */
    static void executeAll() {
        Context context = CURRENT.get();
        CURRENT.remove();
        if (context != null) {
            for (Runnable action : context.actions()) {
                try {
                    action.run();
                } catch (RuntimeException failure) {
                    logger.error("Post-save action failed for room {} ({})",
                            context.roomId(), failure.getClass().getSimpleName());
                }
            }
        }
    }

    /**
     * Discards all queued actions for the current thread when the room change fails.
     */
    static void clear() {
        CURRENT.remove();
    }

    /**
     * Holds the room ID and queued post-save actions for the active thread.
     *
     * @param roomId  the room being modified
     * @param actions actions to run after the change is accepted and saved
     */
    private record Context(String roomId, List<Runnable> actions) {
    }
}
