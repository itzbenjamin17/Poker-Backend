package com.pokergame.wal;

public class WalContext {
    private static final ThreadLocal<Boolean> replaying = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<String> overrideRoomId = new ThreadLocal<>();
    private static boolean globalReplayMode = false;

    public static void setOverrideRoomId(String id) { overrideRoomId.set(id); }
    public static String getOverrideRoomId() { return overrideRoomId.get(); }

    public static boolean isReplaying() {
        return globalReplayMode || replaying.get();
    }

    public static void setGlobalReplayMode(boolean replaying) {
        globalReplayMode = replaying;
    }

    public static void setThreadReplayMode(boolean replaying) {
        WalContext.replaying.set(replaying);
    }

    public static void clear() {
        replaying.remove();
        overrideRoomId.remove();
    }
}
