package com.pokergame.dto.response;

import com.pokergame.model.Card;
import com.pokergame.enums.HandRank;

import java.util.List;

/**
 * Represents the public state of a player in the game.
 * 
 * @param id                 The ID of the player.
 * @param name               The name of the player.
 * @param chips              The number of chips the player has.
 * @param currentBet         The current bet amount of the player.
 * @param status             The current status of the player.
 * @param isAllIn            Whether the player is all in.
 * @param isCurrentPlayer    Whether the player is the current player.
 * @param hasFolded          Whether the player has folded.
 * @param isSmallBlind       Whether the player is the small blind.
 * @param isBigBlind         Whether the player is the big blind.
 * @param handRank           The hand rank of the player.
 * @param bestHand           The best hand of the player.
 * @param isWinner           Whether the player is the winner.
 * @param chipsWon           The number of chips the player has won.
 * @param holeCards          The hole cards of the player.
 * @param disconnectDeadlineEpochMs The deadline for the player to disconnect.
 */
public record PublicPlayerState(
                String id,
                String name,
                int chips,
                int currentBet,
                String status,
                boolean isAllIn,
                boolean isCurrentPlayer,
                boolean hasFolded,
                boolean isSmallBlind,
                boolean isBigBlind,
                // Showdown-specific fields
                HandRank handRank,
                List<Card> bestHand,
                Boolean isWinner,
                Integer chipsWon,
                List<Card> holeCards,
                Long disconnectDeadlineEpochMs

) {
        // For non-showdown states
        public PublicPlayerState(String id, String name, int chips, int currentBet, String status,
                        boolean isAllIn, boolean isCurrentPlayer, boolean hasFolded, boolean isSmallBlind,
                        boolean isBigBlind) {
                this(id,
                                name,
                                chips,
                                currentBet,
                                status,
                                isAllIn,
                                isCurrentPlayer,
                                hasFolded,
                                isSmallBlind,
                                isBigBlind,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null);
        }
}
