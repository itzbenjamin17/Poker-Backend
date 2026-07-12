package com.pokergame.persistence;

@FunctionalInterface
public interface WalFaultInjector {
    WalFaultInjector NONE = (point, roomId) -> { };

    void check(WalFaultPoint point, String roomId);
}
