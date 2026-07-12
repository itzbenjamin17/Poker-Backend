package com.pokergame.persistence;

import java.util.ArrayList;
import java.util.List;

public final class DurableTransactionContext {
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private DurableTransactionContext() {
    }

    static void begin(String roomId) {
        CURRENT.set(new Context(roomId, new ArrayList<>()));
    }

    static String currentRoomId() {
        Context context = CURRENT.get();
        return context == null ? null : context.roomId();
    }

    public static void afterCommit(Runnable action) {
        Context context = CURRENT.get();
        if (context == null) {
            action.run();
        } else {
            context.afterCommit().add(action);
        }
    }

    static void complete() {
        Context context = CURRENT.get();
        CURRENT.remove();
        if (context != null) {
            context.afterCommit().forEach(Runnable::run);
        }
    }

    static void discard() {
        CURRENT.remove();
    }

    private record Context(String roomId, List<Runnable> afterCommit) {
    }
}
