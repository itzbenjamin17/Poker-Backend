package com.pokergame.dto.internal;

import com.pokergame.exception.BadRequestException;
import com.pokergame.enums.PlayerAction;

/**
 * Represents a player's decision in the game.
 * 
 * @param action   The action the player wants to take.
 * @param amount   The amount the player wants to bet or raise.
 * @param playerId The ID of the player making the decision.
 */

public record PlayerDecision(PlayerAction action, int amount, String playerId) {
    /**
     * Validates that a decision identifies an action, player, and non-negative amount.
     *
     * @throws BadRequestException if any required decision value is invalid
     */
    public PlayerDecision {
        if (action == null) {
            throw new BadRequestException("No action specified. Please select an action");
        }
        if (playerId == null || playerId.trim().isEmpty()) {
            throw new BadRequestException("Invalid player ID. Please try again.");
        }
        if (amount < 0) {
            throw new BadRequestException("Amount must be positive");
        }
    }
}
