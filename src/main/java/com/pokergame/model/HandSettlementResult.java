package com.pokergame.model;

import java.util.Map;
import java.util.Set;

/**
 * Immutable accounting result for one completed hand. Refunds remain separate from contested awards.
 * {@code wentToShowdown} is true only when two or more players had their hands evaluated against each other.
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
        if (remainder < 0) throw new IllegalArgumentException("Settlement remainder cannot be negative");
    }
}
