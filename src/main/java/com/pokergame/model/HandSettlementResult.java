package com.pokergame.model;

import java.util.Map;
import java.util.Set;

/**
 * Immutable accounting result for one completed hand. Refunds remain separate from contested awards.
 *
 * @param contestedAwards chips won from contested main/side pots, keyed by player ID
 * @param refunds         uncalled bet chips returned to their contributor, keyed by player ID
 * @param winnerIds       player IDs that won at least one contested pot
 * @param remainder       chips left unallocated in the pot to carry over to the next hand
 * @param wentToShowdown  {@code true} only when two or more players had their hands evaluated against each other
 */
public record HandSettlementResult(Map<String, Integer> contestedAwards,
                                   Map<String, Integer> refunds,
                                   Set<String> winnerIds,
                                   int remainder,
                                   boolean wentToShowdown) {
    public HandSettlementResult {
        contestedAwards = Map.copyOf(contestedAwards);
        refunds = Map.copyOf(refunds);
        winnerIds = Set.copyOf(winnerIds);
        if (remainder < 0) {
            throw new IllegalArgumentException("Settlement remainder cannot be negative");
        }
    }
}
