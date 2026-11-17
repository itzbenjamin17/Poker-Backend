package com.pokergame.service;

import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.model.Card;
import com.pokergame.model.Game;
import java.util.List;

/**
 * Assembles a {@link PublicGameStateResponse} by field name instead of by position.
 *
 * <p>{@link #forGame(Game, long)} fills in the table facts every view shares (pot, board, phase,
 * hand number, ready gate and state revision). Every other field starts as {@code null} and is
 * set only by the views that report it, so each view lists exactly the fields it cares about.
 * Create the builder after any state-revision bump so the response carries the new revision.
 */
final class PublicGameStateBuilder {
    private int maxPlayers;
    private int pot;
    private List<Integer> pots;
    private Integer uncalledAmount;
    private GamePhase phase;
    private int currentBet;
    private List<Card> communityCards;
    private List<PublicPlayerState> players;
    private String currentPlayerName;
    private String currentPlayerId;
    private List<String> winners;
    private Integer winningsPerPlayer;
    private Boolean autoAdvancing;
    private String autoAdvanceMessage;
    private Boolean readyCountdownActive;
    private Long readyCountdownDeadlineEpochMs;
    private Boolean claimWinAvailable;
    private String claimWinPlayerName;
    private Boolean handSettled;
    private Integer carryOverAmount;
    private List<PlayerAction> legalActions;
    private Integer minimumBetAmount;
    private Integer minimumRaiseAmount;
    private long handNumber;
    private ReadyGateState readyGateState;
    private long serverTimeEpochMs;
    private long stateRevision;
    private Long turnDeadlineEpochMs;
    private Long inactivityDeadlineEpochMs;

    private PublicGameStateBuilder() {
    }

    /**
     * Starts a builder holding the table facts every public view shares.
     *
     * @param game            the game being described; its state revision is read now
     * @param serverTimeEpochMs the server time to stamp on the response
     * @return a builder with only the shared table facts set
     */
    static PublicGameStateBuilder forGame(Game game, long serverTimeEpochMs) {
        PublicGameStateBuilder builder = new PublicGameStateBuilder();
        builder.maxPlayers = game.getMaxPlayers();
        builder.pot = game.getPot();
        builder.pots = game.getPotBreakdown();
        builder.uncalledAmount = game.getUncalledAmount();
        builder.phase = game.getCurrentPhase();
        builder.currentBet = game.getCurrentHighestBet();
        builder.communityCards = game.getCommunityCards();
        builder.handNumber = game.getHandNumber();
        builder.readyGateState = game.getReadyGateState();
        builder.serverTimeEpochMs = serverTimeEpochMs;
        builder.stateRevision = game.getStateRevision();
        return builder;
    }

    PublicGameStateBuilder players(List<PublicPlayerState> players) {
        this.players = players;
        return this;
    }

    PublicGameStateBuilder currentPlayer(String name, String id) {
        this.currentPlayerName = name;
        this.currentPlayerId = id;
        return this;
    }

    PublicGameStateBuilder winners(List<String> winners) {
        this.winners = winners;
        return this;
    }

    PublicGameStateBuilder winningsPerPlayer(Integer winningsPerPlayer) {
        this.winningsPerPlayer = winningsPerPlayer;
        return this;
    }

    PublicGameStateBuilder autoAdvance(Boolean autoAdvancing, String message) {
        this.autoAdvancing = autoAdvancing;
        this.autoAdvanceMessage = message;
        return this;
    }

    PublicGameStateBuilder readyCountdown(Boolean active, Long deadlineEpochMs) {
        this.readyCountdownActive = active;
        this.readyCountdownDeadlineEpochMs = deadlineEpochMs;
        return this;
    }

    PublicGameStateBuilder claimWin(Boolean available, String playerName) {
        this.claimWinAvailable = available;
        this.claimWinPlayerName = playerName;
        return this;
    }

    PublicGameStateBuilder handSettled(Boolean handSettled) {
        this.handSettled = handSettled;
        return this;
    }

    PublicGameStateBuilder carryOverAmount(Integer carryOverAmount) {
        this.carryOverAmount = carryOverAmount;
        return this;
    }

    PublicGameStateBuilder legalActions(List<PlayerAction> legalActions) {
        this.legalActions = legalActions;
        return this;
    }

    PublicGameStateBuilder minimumBetAmount(Integer minimumBetAmount) {
        this.minimumBetAmount = minimumBetAmount;
        return this;
    }

    PublicGameStateBuilder minimumRaiseAmount(Integer minimumRaiseAmount) {
        this.minimumRaiseAmount = minimumRaiseAmount;
        return this;
    }

    PublicGameStateBuilder readyGateState(ReadyGateState readyGateState) {
        this.readyGateState = readyGateState;
        return this;
    }

    PublicGameStateBuilder turnDeadlineEpochMs(Long turnDeadlineEpochMs) {
        this.turnDeadlineEpochMs = turnDeadlineEpochMs;
        return this;
    }

    PublicGameStateBuilder inactivityDeadlineEpochMs(Long inactivityDeadlineEpochMs) {
        this.inactivityDeadlineEpochMs = inactivityDeadlineEpochMs;
        return this;
    }

    PublicGameStateResponse build() {
        return new PublicGameStateResponse(
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
                autoAdvancing,
                autoAdvanceMessage,
                readyCountdownActive,
                readyCountdownDeadlineEpochMs,
                claimWinAvailable,
                claimWinPlayerName,
                handSettled,
                carryOverAmount,
                legalActions,
                minimumBetAmount,
                minimumRaiseAmount,
                handNumber,
                readyGateState,
                serverTimeEpochMs,
                stateRevision,
                turnDeadlineEpochMs,
                inactivityDeadlineEpochMs);
    }
}
