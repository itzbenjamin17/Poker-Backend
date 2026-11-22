package com.pokergame.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pokergame.enums.RejectedAction;

/**
 * Private WebSocket contract for rejected actions and player-facing notices on
 * {@code /user/queue/private}.
 *
 * @param type    feedback category ({@link Type#ACTION_ERROR} or {@link Type#PLAYER_NOTIFICATION})
 * @param message client-facing explanation or notice text
 * @param code    machine-readable rejection code for {@code ACTION_ERROR}, otherwise {@code null}
 * @param action  rejected gameplay or readiness action for {@code ACTION_ERROR}, otherwise {@code null}
 */
public record PlayerFeedbackResponse(
        Type type,
        String message,
        @JsonInclude(JsonInclude.Include.NON_NULL) String code,
        @JsonInclude(JsonInclude.Include.NON_NULL) RejectedAction action) {

    /**
     * Creates a structured rejection payload for a failed gameplay or readiness command.
     *
     * @param code    application error code
     * @param action  rejected action descriptor
     * @param message client-facing rejection reason
     * @return action error feedback payload
     */
    public static PlayerFeedbackResponse actionError(String code, RejectedAction action, String message) {
        return new PlayerFeedbackResponse(Type.ACTION_ERROR, message, code, action);
    }

    /**
     * Creates a plain informational notice for the authenticated player.
     *
     * @param message notice text to display
     * @return player notification feedback payload
     */
    public static PlayerFeedbackResponse notice(String message) {
        return new PlayerFeedbackResponse(Type.PLAYER_NOTIFICATION, message, null, null);
    }

    /** Wire message categories sent to a player's private queue. */
    public enum Type {
        ACTION_ERROR,
        PLAYER_NOTIFICATION
    }
}
