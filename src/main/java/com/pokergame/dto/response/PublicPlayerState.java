package com.pokergame.dto.response;

import com.pokergame.model.Card;
import com.pokergame.enums.HandRank;

import java.util.List;

public record PublicPlayerState(
        String id,
        String name,
        int chips,
        int currentBet,
        String status,
        boolean isAllIn,
        boolean isCurrentPlayer,
        boolean hasFolded,
        // Showdown-specific fields
        HandRank handRank,
        List<Card> bestHand,
        Boolean isWinner,
        Integer chipsWon,
        List<Card> holeCards

) {
    // For non-showdown states
    public PublicPlayerState(String id, String name, int chips, int currentBet, String status,
            boolean isAllIn, boolean isCurrentPlayer, boolean hasFolded) {
        this(id, name, chips, currentBet, status, isAllIn, isCurrentPlayer, hasFolded, null, null, null, null, null);
    }
}
