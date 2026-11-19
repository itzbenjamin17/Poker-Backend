package com.pokergame.model;

import java.util.Map;
import java.util.Set;

/**
 * Payout summary for one completed hand, keeping returned uncalled bets separate from pot winnings.
 *
 * @param potWinnings    chips won from the main and side pots, by player ID
 * @param refunds        uncalled bet chips returned to the player who bet them, by player ID
 * @param winnerIds      IDs of players who won a pot
 * @param remainder      chips left in the pot to carry over to the next hand
 * @param wentToShowdown {@code true} only when two or more players compared hands at showdown
 */
public record HandPayout(
        Map<String, Integer> potWinnings,
        Map<String, Integer> refunds,
        Set<String> winnerIds,
        int remainder,
        boolean wentToShowdown) {
    public HandPayout {
        potWinnings = Map.copyOf(potWinnings);
        refunds = Map.copyOf(refunds);
        winnerIds = Set.copyOf(winnerIds);
        if (remainder < 0) {
            throw new IllegalArgumentException("Remainder cannot be negative");
        }
    }
}
