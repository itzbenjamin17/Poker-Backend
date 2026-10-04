package com.pokergame.util;

/**
 * Common constants for Mapped Diagnostic Context (MDC) logging keys.
 * Used to correlate log entries by room, player, and websocket session.
 */
public final class MdcKeys {

    /**
     * MDC key for the active room or game identifier.
     */
    public static final String ROOM_ID = "roomId";

    /**
     * MDC key for the authenticated player name.
     */
    public static final String PLAYER_NAME = "playerName";

    /**
     * MDC key for the STOMP / WebSocket session identifier.
     */
    public static final String SESSION_ID = "sessionId";

    private MdcKeys() {
        // utility class
    }
}
