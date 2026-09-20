package com.pokergame.persistence.transaction;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps track of the active save operation and any actions we want to run right after.
 * <p>
 * We use a ThreadLocal here because all player moves happen one at a time on a single thread.
 * Any future background work (like a 30-second turn timer) is saved to the database as data
 * instead of running while we're trying to save.
 * </p>
 */
public final class DurableTransactionContext {
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
     * Delays an action (like sending a message to players) until the game finishes saving.
     * If we aren't currently saving a game, it just runs the action immediately.
     *
     * @param action the code to run after saving finishes
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
     * Clears out the tracking state, then runs all the delayed actions we collected.
     * We clear the state first so that these actions don't accidentally think they are
     * part of the save operation that just finished.
     */
    static void complete() {
        Context context = CURRENT.get();
        CURRENT.remove();
        if (context != null) {
            context.afterCommit().forEach(Runnable::run);
        }
    }

    /**
     * Throws away all the delayed actions if the save failed. We don't want to notify
     * players about a game change that never actually got saved.
     */
    static void discard() {
        CURRENT.remove();
    }

    /**
     * Holds the room ID and a list of actions to run when the save finishes.
     *
     * @param roomId      the room being saved
     * @param afterCommit actions to run once the game is successfully saved
     */
    private record Context(String roomId, List<Runnable> afterCommit) {
    }
}
