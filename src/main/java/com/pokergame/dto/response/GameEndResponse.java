package com.pokergame.dto.response;

/**
 * Represents the terminal game-end message broadcast when a winner is determined.
 *
 * @param type       wire-format message category, always {@code "GAME_END"}.
 * @param gameId     the unique identifier of the game.
 * @param winner     the name of the winning player.
 * @param winnerChips the winner's final chip stack.
 * @param isForfeit  true if the game ended due to a player leaving/disconnecting.
 * @param message    a human-readable summary of the result.
 * @param finalState a complete, revealed final-state snapshot (board, stacks, and
 *                   showdown hole cards when applicable) that the client can freeze
 *                   and use for the post-game review screen.
 */
public record GameEndResponse(
        String type,
        String gameId,
        String winner,
        int winnerChips,
        boolean isForfeit,
        String message,
        PublicGameStateResponse finalState) {
}
