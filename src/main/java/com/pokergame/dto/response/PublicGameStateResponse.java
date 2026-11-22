package com.pokergame.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pokergame.enums.ForfeitReason;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.HandRank;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.model.Card;
import java.util.List;

/**
 * Public snapshot of a poker game returned by {@code GET /api/game/{gameId}/state},
 * {@code GET /api/game/{gameId}/review}, and {@code POST /api/game/{gameId}/claim-win}, and
 * broadcast to {@code /game/{gameId}} for live play, showdowns, auto-advance, and game completion.
 *
 * @param maxPlayers                    maximum number of players in the game
 * @param pot                           total pot size
 * @param pots                          main and side-pot sizes
 * @param uncalledAmount                chips eligible only to a single player
 * @param phase                         current game phase
 * @param currentBet                    current highest bet in the round
 * @param communityCards                shared board cards
 * @param players                       public state of each seated player
 * @param currentPlayerName             current acting player's display name, or {@code null}
 * @param currentPlayerId               current acting player's stable ID, or {@code null}
 * @param winners                       winning player names for a settled hand or completed match
 * @param winningsPerPlayer             equal chip award per winner when all winners won the same amount
 * @param isAutoAdvancing               whether the game is auto-advancing all-in streets
 * @param autoAdvanceMessage            status banner shown during auto-advance
 * @param isReadyCountdownActive        whether the post-hand ready countdown is active
 * @param readyCountdownDeadlineEpochMs UTC epoch ms when the ready countdown expires
 * @param claimWinAvailable             whether an immediate disconnect win claim is available
 * @param claimWinPlayerName            player currently eligible to claim win
 * @param handSettled                   whether retained settlement metadata confirms this hand completed
 * @param carryOverAmount               unresolved chip amount carried to a later hand or final payout
 * @param legalActions                  authoritative legal actions for the current actor
 * @param minimumBetAmount              minimum incremental bet, or {@code null} when no actor can bet
 * @param minimumRaiseAmount            minimum incremental full raise, or {@code null} when no actor can raise
 * @param handNumber                    sequence number of the current hand
 * @param readyGateState                state of the between-hand ready gate
 * @param serverTimeEpochMs             server clock timestamp in UTC epoch ms
 * @param stateRevision                 monotonically increasing revision number within the hand
 * @param turnDeadlineEpochMs           absolute deadline for the current turn, or {@code null}
 * @param inactivityDeadlineEpochMs     absolute deadline for game inactivity abandonment, or {@code null}
 * @param gameEnd                       final match summary when the game has finished or been abandoned,
 *                                      otherwise {@code null}
 */
public record PublicGameStateResponse(
        int maxPlayers,
        int pot,
        List<Integer> pots,
        Integer uncalledAmount,
        GamePhase phase,
        int currentBet,
        List<Card> communityCards,
        List<PlayerState> players,
        String currentPlayerName,
        String currentPlayerId,
        List<String> winners,
        Integer winningsPerPlayer,
        Boolean isAutoAdvancing,
        String autoAdvanceMessage,
        Boolean isReadyCountdownActive,
        Long readyCountdownDeadlineEpochMs,
        Boolean claimWinAvailable,
        String claimWinPlayerName,
        Boolean handSettled,
        Integer carryOverAmount,
        List<PlayerAction> legalActions,
        Integer minimumBetAmount,
        Integer minimumRaiseAmount,
        long handNumber,
        ReadyGateState readyGateState,
        long serverTimeEpochMs,
        long stateRevision,
        Long turnDeadlineEpochMs,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long inactivityDeadlineEpochMs,
        @JsonInclude(JsonInclude.Include.NON_NULL) GameEnd gameEnd) {

    public PublicGameStateResponse {
        pots = pots == null ? null : List.copyOf(pots);
        communityCards = communityCards == null ? null : List.copyOf(communityCards);
        players = players == null ? null : List.copyOf(players);
        winners = winners == null ? null : List.copyOf(winners);
        legalActions = legalActions == null ? null : List.copyOf(legalActions);
    }

    /**
     * Public state of a single seated player inside {@link PublicGameStateResponse}.
     *
     * @param id                        stable player identifier
     * @param name                      player display name
     * @param chips                     remaining chip stack
     * @param currentBet                chips bet in the current round
     * @param status                    public player status string
     * @param isAllIn                   whether the player is all-in
     * @param isCurrentPlayer           whether it is this player's turn
     * @param hasFolded                 whether the player folded this hand
     * @param isSmallBlind              whether the player posted the small blind
     * @param isBigBlind                whether the player posted the big blind
     * @param isDealer                  whether the player holds the dealer button
     * @param handRank                  evaluated hand rank at showdown, or {@code null}
     * @param bestHand                  best 5-card hand at showdown, or {@code null}
     * @param isWinner                  whether the player won the settled hand or match, or {@code null}
     * @param chipsWon                  chips won in the settled hand, or {@code null}
     * @param holeCards                 revealed hole cards at a contested showdown, or {@code null}
     * @param disconnectDeadlineEpochMs UTC epoch ms when reconnect grace expires, or {@code null}
     * @param isReadyForNextHand        whether the player confirmed ready for the next hand
     */
    public record PlayerState(
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
            boolean isDealer,
            HandRank handRank,
            List<Card> bestHand,
            Boolean isWinner,
            Integer chipsWon,
            List<Card> holeCards,
            Long disconnectDeadlineEpochMs,
            Boolean isReadyForNextHand) {

        public PlayerState {
            bestHand = bestHand == null ? null : List.copyOf(bestHand);
            holeCards = holeCards == null ? null : List.copyOf(holeCards);
        }
    }

    /**
     * Final match summary embedded in {@link PublicGameStateResponse#gameEnd()} when the match
     * completes with a winner or is abandoned due to inactivity.
     *
     * @param winner      winning player's name, or {@code null} when abandoned
     * @param winnerChips winner's final chip stack, or {@code null} when abandoned
     * @param isForfeit        {@code true} when the match ended by opponent departure or disconnect claim
     * @param abandoned        {@code true} when the match ended without a winner
     * @param reason           abandonment reason (e.g. {@code "INACTIVITY"}), the {@link ForfeitReason} name of a
     *                         forfeit, or {@code null} when unknown or not applicable
     * @param forfeitedPlayers names of the players who forfeited, in seat order, or {@code null} when not a forfeit
     *                         or unknown
     * @param message          human-readable summary of the final result
     */
    public record GameEnd(
            String winner,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer winnerChips,
            boolean isForfeit,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean abandoned,
            @JsonInclude(JsonInclude.Include.NON_NULL) String reason,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<String> forfeitedPlayers,
            String message) {

        public GameEnd {
            forfeitedPlayers = forfeitedPlayers == null ? null : List.copyOf(forfeitedPlayers);
        }

        /**
         * Creates a won-game summary without a recorded forfeit reason.
         *
         * @param winner      winning player's name
         * @param winnerChips winner's final chip stack
         * @param isForfeit   {@code true} when the match ended by forfeit
         * @param message     human-readable summary of the result
         * @return won-game summary
         */
        public static GameEnd won(String winner, int winnerChips, boolean isForfeit, String message) {
            return new GameEnd(winner, winnerChips, isForfeit, false, null, null, message);
        }

        /**
         * Creates a summary for a match won because the other players forfeited.
         *
         * @param winner           winning player's name
         * @param winnerChips      winner's final chip stack
         * @param reason           why the other players forfeited
         * @param forfeitedPlayers names of the players who forfeited, in seat order
         * @param message          human-readable summary of the result
         * @return forfeit-win summary
         */
        public static GameEnd wonByForfeit(
                String winner, int winnerChips, ForfeitReason reason, List<String> forfeitedPlayers, String message) {
            return new GameEnd(winner, winnerChips, true, false, reason.name(), forfeitedPlayers, message);
        }

        /**
         * Creates an abandoned-game summary.
         *
         * @param reason  abandonment reason code
         * @param message human-readable summary of the abandonment
         * @return abandoned-game summary
         */
        public static GameEnd abandoned(String reason, String message) {
            return new GameEnd(null, null, false, true, reason, null, message);
        }
    }
}
