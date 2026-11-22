package com.pokergame.enums;

/** Action names used only in a rejection response, including readiness destinations. */
public enum RejectedAction {
    FOLD, CHECK, CALL, BET, RAISE, ALL_IN, READY, UNREADY;

    public static RejectedAction from(PlayerAction action) {
        return action == null ? null : valueOf(action.name());
    }
}
