package com.pokergame.dto.response;

import com.pokergame.enums.RejectedAction;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ResponseMessage;

/** The private WebSocket contract for a rejected poker action. */
public record ActionErrorResponse(ResponseMessage type, String code, RejectedAction action, String message) {
    /** Creates a complete action error with the protocol type. */
    public ActionErrorResponse(String code, RejectedAction action, String message) {
        this(ResponseMessage.ACTION_ERROR, code, action, message);
    }

    public ActionErrorResponse(String code, PlayerAction action, String message) {
        this(ResponseMessage.ACTION_ERROR, code, RejectedAction.from(action), message);
    }
}
