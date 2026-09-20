package com.pokergame.persistence.wal;

/**
 * A helper interface that lets us intentionally cause errors during automated tests.
 * This helps us check if the game saves safely when the server crashes, without breaking the real code.
 */
@FunctionalInterface
public interface WalFaultInjector {
    /**
     * A version that does nothing. This is what we use in production when real players are playing.
     */
    WalFaultInjector NONE = (point, roomId) -> { };

    /**
     * Trigger a crash or error if we are running a test and want to simulate a failure right now.
     *
     * @param point  the exact step where we want the crash to happen
     * @param roomId the room we are currently saving
     */
    void check(WalFaultPoint point, String roomId);
}
