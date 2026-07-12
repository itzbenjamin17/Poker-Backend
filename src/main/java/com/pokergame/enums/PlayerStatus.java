package com.pokergame.enums;

/**
 * Represents the status of a player.
 */

public enum PlayerStatus {
    FOLDED("FOLDED"),
    OUT("OUT"),
    DISCONNECTED("DISCONNECTED"),
    ACTIVE("ACTIVE"),
    ALL_IN("ALL_IN");

    private final String status;

    /**
     * Associates a player state with its wire value.
     *
     * @param status serialized status value
     */
    PlayerStatus(String status) {
        this.status = status;
    }

    /**
     * Returns the serialized status value.
     *
     * @return wire-format status
     */
    public String getStatus() {
        return status;
    }
}
