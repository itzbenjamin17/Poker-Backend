package com.pokergame.dto.request;

import com.pokergame.enums.PlayerAction;
import com.pokergame.exception.BadRequestException;
import jakarta.validation.constraints.NotNull;

/**
 * DTO for player action requests.
 * Player identity is now determined from the JWT token (Principal),
 * not from the request body.
 */
public record PlayerActionRequest(

        @NotNull(message = "Action is required") PlayerAction action,

        // Amount is optional - only needed for BET and RAISE actions
        Integer amount

) {
    public PlayerActionRequest {
        // Validate amount is provided for betting actions
        if ((action == PlayerAction.BET || action == PlayerAction.RAISE)) {
            if (amount == null || amount <= 0) {
                throw new BadRequestException("Please enter a positive amount for BET or RAISE actions");
            }
            // Adding arbitrary max bet limit, maybe remove
            if (amount > 10000) {
                throw new BadRequestException("Bet/raise amount cannot exceed 10,000 chips");
            }
        }

        // Amount should not be provided for non-betting actions
        if ((action == PlayerAction.FOLD || action == PlayerAction.CHECK ||
                action == PlayerAction.CALL || action == PlayerAction.ALL_IN)) {
            if (amount != null && amount != 0) {
                throw new BadRequestException(
                        String.format("No amount is needed for %s actions", action));
            }
        }
    }
}
