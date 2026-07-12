package com.pokergame.dto.response;

import com.pokergame.model.Card;
import com.pokergame.enums.HandRank;

import java.util.List;

/**
 * Represents the public state of a player in the game.
 * 
 * @param id                        The ID of the player.
 * @param name                      The name of the player.
 * @param chips                     The number of chips the player has.
 * @param currentBet                The current bet amount of the player.
 * @param status                    The current status of the player.
 * @param isAllIn                   Whether the player is all in.
 * @param isCurrentPlayer           Whether the player is the current player.
 * @param hasFolded                 Whether the player has folded.
 * @param isSmallBlind              Whether the player is the small blind.
 * @param isBigBlind                Whether the player is the big blind.
 * @param handRank                  The hand rank of the player.
 * @param bestHand                  The best hand of the player.
 * @param isWinner                  Whether the player is the winner.
 * @param chipsWon                  The number of chips the player has won.
 * @param holeCards                 The hole cards of the player.
 * @param disconnectDeadlineEpochMs The UTC epoch time when reconnect grace expires.
 * @param isReadyForNextHand        Whether the player has confirmed ready for
 *                                  the next hand.
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
                Long disconnectDeadlineEpochMs,
                Boolean isReadyForNextHand

) {
        /**
         * Creates the public player projection used outside showdown, leaving all
         * showdown and reconnect metadata absent.
         *
         * @param id stable player identifier
         * @param name player display name
         * @param chips remaining chips
         * @param currentBet current-round contribution
         * @param status public player status
         * @param isAllIn whether the player is all-in
         * @param isCurrentPlayer whether it is this player's turn
         * @param hasFolded whether the player folded this hand
         * @param isSmallBlind whether the player posted the small blind
         * @param isBigBlind whether the player posted the big blind
         */
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
                                null,
                                null);
        }
}
