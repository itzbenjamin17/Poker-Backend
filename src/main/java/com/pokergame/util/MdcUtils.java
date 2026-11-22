package com.pokergame.util;

import static java.util.Objects.requireNonNull;
import org.slf4j.MDC;

/**
 * Utility methods for scoping Mapped Diagnostic Context (MDC) keys during execution.
 * Ensures consistent propagation and cleanup across threads and scopes.
 */
public final class MdcUtils {

    private MdcUtils() {
        // utility class
    }

    /**
     * Scopes {@link MdcKeys#ROOM_ID} in the current thread's MDC until the returned scope closes.
     *
     * @param roomId the room identifier
     * @return an auto-closeable scope that restores the previous room ID on close
     */
    public static MdcScope withRoomId(String roomId) {
        requireNonNull(roomId, "The room ID cannot be null");
        String prevRoom = MDC.get(MdcKeys.ROOM_ID);
        MDC.put(MdcKeys.ROOM_ID, roomId);
        return new MdcScope(prevRoom, false, null, null);
    }

    /**
     * Scopes {@link MdcKeys#ROOM_ID} and {@link MdcKeys#PLAYER_NAME} in the current thread's MDC
     * until the returned scope closes.
     *
     * @param roomId the room identifier
     * @param playerName the player name
     * @return an auto-closeable scope that restores the previous context on close
     */
    public static MdcScope withMdc(String roomId, String playerName) {
        return withMdc(roomId, playerName, null);
    }

    /**
     * Scopes {@link MdcKeys#ROOM_ID}, {@link MdcKeys#PLAYER_NAME}, and {@link MdcKeys#SESSION_ID}
     * in the current thread's MDC until the returned scope closes.
     *
     * @param roomId the room identifier
     * @param playerName the player name
     * @param sessionId the session identifier
     * @return an auto-closeable scope that restores the previous context on close
     */
    public static MdcScope withMdc(String roomId, String playerName, String sessionId) {
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

        return new MdcScope(prevRoom, true, prevPlayer, prevSession);
    }

    private static void restoreOrRemove(String key, String previousValue) {
        if (previousValue != null) {
            MDC.put(key, previousValue);
        } else {
            MDC.remove(key);
        }
    }

    /**
     * Auto-closeable MDC scope that restores prior MDC values when exited.
     */
    public static final class MdcScope implements AutoCloseable {

        private final String prevRoom;
        private final boolean restorePlayerAndSession;
        private final String prevPlayer;
        private final String prevSession;
        private boolean closed;

        private MdcScope(
                String prevRoom,
                boolean restorePlayerAndSession,
                String prevPlayer,
                String prevSession) {
            this.prevRoom = prevRoom;
            this.restorePlayerAndSession = restorePlayerAndSession;
            this.prevPlayer = prevPlayer;
            this.prevSession = prevSession;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            restoreOrRemove(MdcKeys.ROOM_ID, prevRoom);
            if (restorePlayerAndSession) {
                restoreOrRemove(MdcKeys.PLAYER_NAME, prevPlayer);
                restoreOrRemove(MdcKeys.SESSION_ID, prevSession);
            }
        }
    }
}
