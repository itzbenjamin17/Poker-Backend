package com.pokergame.enums;

public enum PlayerStatus {
    FOLDED("FOLDED"),
    OUT("OUT"),
    DISCONNECTED("DISCONNECTED"),
    ACTIVE("ACTIVE"),
    ALL_IN("ALL_IN");

    private final String status;

    PlayerStatus(String status) {
        this.status = status;
    }

    public String getStatus() {
        return status;
    }
}
