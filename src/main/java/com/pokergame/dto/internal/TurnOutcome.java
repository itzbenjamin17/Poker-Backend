package com.pokergame.dto.internal;

import com.pokergame.model.Player;
import java.util.List;

public record TurnOutcome(
    TurnOutcomeType type,
    String conversionMessage,
    List<Player> winners,
    int winningsPerPlayer
) {
    public enum TurnOutcomeType {
        NEXT_PLAYER,
        PHASE_ADVANCED,
        AUTO_ADVANCING,
        SHOWDOWN
    }
}
