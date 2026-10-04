package com.pokergame.util;

import org.slf4j.MDC;

/**
 * Utility methods for scoping Mapped Diagnostic Context (MDC) keys during execution.
 * Ensures consistent propagation and cleanup across threads and closures.
 */
public final class MdcUtils {

    private MdcUtils() {
        // utility class
    }

    /**
     * Executes a runnable within a scoped room ID, restoring the previous context upon completion.
     *
     * @param roomId the room identifier
     * @param action the task to execute
     */
    public static void runWithRoomId(String roomId, Runnable action) {
        String prevRoom = MDC.get(MdcKeys.ROOM_ID);
        if (roomId != null) {
            MDC.put(MdcKeys.ROOM_ID, roomId);
        }
        try {
            action.run();
        } finally {
            restoreOrRemove(MdcKeys.ROOM_ID, prevRoom);
        }
    }

    /**
     * Executes a runnable within a scoped room ID and player name, restoring previous context.
     *
     * @param roomId the room identifier
     * @param playerName the player name
     * @param action the task to execute
     */
    public static void runWithMdc(String roomId, String playerName, Runnable action) {
        runWithMdc(roomId, playerName, null, action);
    }

    /**
     * Executes a runnable within a scoped room ID, player name, and session ID, restoring previous context.
     *
     * @param roomId the room identifier
     * @param playerName the player name
     * @param sessionId the session identifier
     * @param action the task to execute
     */
    public static void runWithMdc(String roomId, String playerName, String sessionId, Runnable action) {
        String prevRoom = MDC.get(MdcKeys.ROOM_ID);
        String prevPlayer = MDC.get(MdcKeys.PLAYER_NAME);
        String prevSession = MDC.get(MdcKeys.SESSION_ID);

        if (roomId != null) {
            MDC.put(MdcKeys.ROOM_ID, roomId);
        }
        if (playerName != null) {
            MDC.put(MdcKeys.PLAYER_NAME, playerName);
        }
        if (sessionId != null) {
            MDC.put(MdcKeys.SESSION_ID, sessionId);
        }

        try {
            action.run();
        } finally {
            restoreOrRemove(MdcKeys.ROOM_ID, prevRoom);
            restoreOrRemove(MdcKeys.PLAYER_NAME, prevPlayer);
            restoreOrRemove(MdcKeys.SESSION_ID, prevSession);
        }
    }

    private static void restoreOrRemove(String key, String previousValue) {
        if (previousValue != null) {
            MDC.put(key, previousValue);
        } else {
            MDC.remove(key);
        }
    }
}
