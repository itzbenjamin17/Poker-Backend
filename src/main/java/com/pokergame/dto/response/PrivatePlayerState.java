package com.pokergame.dto.response;

import com.pokergame.model.Card;

import java.util.List;

/**
 * Represents the private state of a player in the game.
 * 
 * @param playerId   The ID of the player.
 * @param handNumber The hand number this private state belongs to.
 * @param holeCards  The hole cards of the player.
 */

public record PrivatePlayerState(
        String playerId,
        long handNumber,
        List<Card> holeCards
) {
    public PrivatePlayerState {
        holeCards = holeCards == null ? List.of() : List.copyOf(holeCards);
    }
}
