package com.pokergame.persistence;

/**
 * Provides deterministic crash points for durability tests without adding
 * production-only branches to storage logic.
 */
@FunctionalInterface
public interface WalFaultInjector {
    /**
     * Production no-op used when fault injection is not explicitly configured.
     */
    WalFaultInjector NONE = (point, roomId) -> { };

    /**
     * Gives tests a chance to fail at a named storage boundary.
     *
     * @param point  durability boundary being crossed
     * @param roomId room affected by the operation
     */
    void check(WalFaultPoint point, String roomId);
}
