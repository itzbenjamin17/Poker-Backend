package com.pokergame.enums;

/**
 * Enum for response messages.
 */

public enum ResponseMessage {
    ROOM_CREATED("ROOM_CREATED"),
    PLAYER_JOINED("PLAYER_JOINED"),
    PLAYER_LEFT("PLAYER_LEFT"),
    ROOM_CLOSED("ROOM_CLOSED"),
    GAME_STARTED("GAME_STARTED"),
    GAME_END("GAME_END"),
    AUTO_ADVANCE_START("AUTO_ADVANCE_START"),
    AUTO_ADVANCE_COMPLETE("AUTO_ADVANCE_COMPLETE"),
    ACTION_ERROR("ACTION_ERROR"),
    PLAYER_NOTIFICATION("PLAYER_NOTIFICATION");

    private final String message;

    ResponseMessage(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
