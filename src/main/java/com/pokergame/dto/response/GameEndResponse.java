package com.pokergame.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Represents the terminal game-end message broadcast when a winner is determined
 * or the game is abandoned due to inactivity.
 *
 * @param type        wire-format message category, always {@code "GAME_END"}.
 * @param gameId      the unique identifier of the game.
 * @param winner      the name of the winning player, or null if abandoned.
 * @param winnerChips the winner's final chip stack, or null if abandoned.
 * @param isForfeit   true if the game ended due to a player leaving/disconnecting.
 * @param abandoned   true if the game was abandoned without a winner.
 * @param reason      terminal reason, e.g. "INACTIVITY" when abandoned.
 * @param message     a human-readable summary of the result.
 * @param finalState  a frozen final-state snapshot that the client can freeze
 *                    and use for the post-game review screen.
 */
public record GameEndResponse(
        String type,
        String gameId,
        String winner,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer winnerChips,
        boolean isForfeit,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean abandoned,
        @JsonInclude(JsonInclude.Include.NON_NULL) String reason,
        String message,
        PublicGameStateResponse finalState) {

    public GameEndResponse(
            String type,
            String gameId,
            String winner,
            Integer winnerChips,
            boolean isForfeit,
            String message,
            PublicGameStateResponse finalState) {
        this(type, gameId, winner, winnerChips, isForfeit, false, null, message, finalState);
    }
}
