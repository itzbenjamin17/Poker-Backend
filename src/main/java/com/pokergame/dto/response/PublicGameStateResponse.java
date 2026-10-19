package com.pokergame.dto.response;

import com.pokergame.model.Card;
import com.pokergame.enums.GamePhase;

import java.util.List;
import com.pokergame.enums.PlayerAction;

/**
 * Represents the public state of a game.
 * 
 * @param maxPlayers                    The maximum number of players in the
 *                                      game.
 * @param pot                           The total pot size.
 * @param pots                          The pot sizes for each pot. (For side
 *                                      pots)
 * @param uncalledAmount                The uncalled amount. (Total amount of
 *                                      chips only one player has ability to
 *                                      win, usually in split pot scenarios)
 * @param phase                         The current game phase.
 * @param currentBet                    The current bet amount.
 * @param communityCards                The community cards.
 * @param players                       The list of public player states.
 * @param currentPlayerName             The name of the current player.
 * @param currentPlayerId               The ID of the current player.
 * @param winners                       The list of winners.
 * @param winningsPerPlayer             The winnings per player.
 * @param isAutoAdvancing               Whether the game is auto advancing.
 * @param autoAdvanceMessage            The auto advance message.
 * @param isReadyCountdownActive        Whether the post-round ready countdown
 *                                      is active.
 * @param readyCountdownDeadlineEpochMs The UTC epoch ms when ready countdown
 *                                      expires.
 * @param claimWinAvailable             Whether claim win is available.
 * @param claimWinPlayerName            The name of the player who can claim
 *                                      win.
 * @param handSettled                   Whether retained settlement metadata proves this hand completed; an empty
 *                                      winner list can describe a void hand with carried-over chips.
 * @param carryOverAmount               The explicit unresolved chip amount retained for a later hand or terminal payout.
 * @param legalActions                  Authoritative actions for the current actor; empty means no actor can act.
 * @param minimumBetAmount              Minimum bet in incremental chip units, nullable when no actor can bet.
 * @param minimumRaiseAmount            Full minimum raise in incremental chip units, including the call amount; a
 *                                      short whole-stack all-in may still be legal below this amount.
 */
public record PublicGameStateResponse(
                int maxPlayers,
                int pot,
                List<Integer> pots,
                Integer uncalledAmount,
                GamePhase phase,
                int currentBet,
                List<Card> communityCards,
                List<PublicPlayerState> players,
                String currentPlayerName,
                String currentPlayerId,
                // For showdowns
                List<String> winners,
                // These use objects so they can be null
                Integer winningsPerPlayer,
                // For auto advancing
                Boolean isAutoAdvancing,
                String autoAdvanceMessage,
                // Ready-countdown metadata
                Boolean isReadyCountdownActive,
                Long readyCountdownDeadlineEpochMs,
                // Claim-win metadata
                Boolean claimWinAvailable,
                String claimWinPlayerName,
                Boolean handSettled,
                Integer carryOverAmount,
                List<PlayerAction> legalActions,
                Integer minimumBetAmount,
                Integer minimumRaiseAmount) {

        /** Backward-compatible constructor for snapshots before legal-action metadata. */
        public PublicGameStateResponse(int maxPlayers, int pot, List<Integer> pots, Integer uncalledAmount,
                        GamePhase phase, int currentBet, List<Card> communityCards, List<PublicPlayerState> players,
                        String currentPlayerName, String currentPlayerId, List<String> winners,
                        Integer winningsPerPlayer, Boolean isAutoAdvancing, String autoAdvanceMessage,
                        Boolean isReadyCountdownActive, Long readyCountdownDeadlineEpochMs,
                        Boolean claimWinAvailable, String claimWinPlayerName, Boolean handSettled,
                        Integer carryOverAmount) {
                this(maxPlayers, pot, pots, uncalledAmount, phase, currentBet, communityCards, players,
                                currentPlayerName, currentPlayerId, winners, winningsPerPlayer, isAutoAdvancing,
                                autoAdvanceMessage, isReadyCountdownActive, readyCountdownDeadlineEpochMs,
                                claimWinAvailable, claimWinPlayerName, handSettled, carryOverAmount, null, null, null);
        }

        /** Backward-compatible full constructor for snapshots predating settlement metadata. */
        public PublicGameStateResponse(int maxPlayers, int pot, List<Integer> pots, Integer uncalledAmount,
                        GamePhase phase, int currentBet, List<Card> communityCards, List<PublicPlayerState> players,
                        String currentPlayerName, String currentPlayerId, List<String> winners,
                        Integer winningsPerPlayer, Boolean isAutoAdvancing, String autoAdvanceMessage,
                        Boolean isReadyCountdownActive, Long readyCountdownDeadlineEpochMs,
                        Boolean claimWinAvailable, String claimWinPlayerName) {
                this(maxPlayers, pot, pots, uncalledAmount, phase, currentBet, communityCards, players,
                                currentPlayerName, currentPlayerId, winners, winningsPerPlayer, isAutoAdvancing,
                                autoAdvanceMessage, isReadyCountdownActive, readyCountdownDeadlineEpochMs,
                                claimWinAvailable, claimWinPlayerName, null, null);
        }

        /**
         * Creates a normal in-hand projection without showdown, countdown, or
         * claim-win metadata.
         *
         * @param maxPlayers maximum game capacity
         * @param pot total committed chips
         * @param pots main and side-pot amounts
         * @param uncalledAmount chips refundable to the sole eligible player
         * @param phase current game phase
         * @param currentBet current table bet
         * @param communityCards public board cards
         * @param players public player projections
         * @param currentPlayerName current player's display name
         * @param currentPlayerId current player's stable identifier
         */
        public PublicGameStateResponse(int maxPlayers,
                        int pot,
                        List<Integer> pots,
                        Integer uncalledAmount,
                        GamePhase phase,
                        int currentBet,
                        List<Card> communityCards,
                        List<PublicPlayerState> players,
                        String currentPlayerName,
                        String currentPlayerId) {
                this(
                                maxPlayers,
                                pot,
                                pots,
                                uncalledAmount,
                                phase,
                                currentBet,
                                communityCards,
                                players,
                                currentPlayerName,
                                currentPlayerId,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null);

        }

        /**
         * Creates a normal in-hand projection with disconnect claim-win metadata.
         *
         * @param maxPlayers maximum game capacity
         * @param pot total committed chips
         * @param pots main and side-pot amounts
         * @param uncalledAmount chips refundable to the sole eligible player
         * @param phase current game phase
         * @param currentBet current table bet
         * @param communityCards public board cards
         * @param players public player projections
         * @param currentPlayerName current player's display name
         * @param currentPlayerId current player's stable identifier
         * @param claimWinAvailable whether an immediate disconnect win is available
         * @param claimWinPlayerName player currently eligible to claim
         */
        public PublicGameStateResponse(int maxPlayers,
                        int pot,
                        List<Integer> pots,
                        Integer uncalledAmount,
                        GamePhase phase,
                        int currentBet,
                        List<Card> communityCards,
                        List<PublicPlayerState> players,
                        String currentPlayerName,
                        String currentPlayerId,
                        Boolean claimWinAvailable,
                        String claimWinPlayerName) {
                this(
                                maxPlayers,
                                pot,
                                pots,
                                uncalledAmount,
                                phase,
                                currentBet,
                                communityCards,
                                players,
                                currentPlayerName,
                                currentPlayerId,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                claimWinAvailable,
                                claimWinPlayerName);

        }
        /**
         * Creates a showdown projection with winner information.
         *
         * @param maxPlayers maximum game capacity
         * @param pot total committed chips
         * @param pots main and side-pot amounts
         * @param uncalledAmount chips refundable to the sole eligible player
         * @param phase current game phase
         * @param currentBet current table bet
         * @param communityCards public board cards
         * @param players public player projections
         * @param currentPlayerName current player's display name
         * @param currentPlayerId current player's stable identifier
         * @param winners winning player names
         * @param winningsPerPlayer equal winnings represented by this legacy projection
         */
        public PublicGameStateResponse(int maxPlayers,
                        int pot,
                        List<Integer> pots,
                        Integer uncalledAmount,
                        GamePhase phase,
                        int currentBet,
                        List<Card> communityCards,
                        List<PublicPlayerState> players,
                        String currentPlayerName,
                        String currentPlayerId,
                        List<String> winners,
                        Integer winningsPerPlayer) {
                this(
                                maxPlayers,
                                pot,
                                pots,
                                uncalledAmount,
                                phase,
                                currentBet,
                                communityCards,
                                players,
                                currentPlayerName,
                                currentPlayerId,
                                winners,
                                winningsPerPlayer,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null);

        }
}
