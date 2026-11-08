package com.pokergame.model;

import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.service.HandEvaluatorService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builder for reconstructing a {@link Game} instance from snapshot state.
 * Extracted collaborator handling snapshot restoration for {@link Game}.
 */
public class GameRestoreBuilder {

    private String gameId;
    private List<Player> players;
    private List<String> activePlayerIds;
    private List<Card> remainingDeck;
    private List<Card> communityCards;
    private int pot;
    private int dealerPosition;
    private int smallBlindPosition;
    private int bigBlindPosition;
    private int currentPlayerPosition;
    private int currentHighestBet;
    private int preHandCarryOver;
    private GamePhase currentPhase;
    private boolean gameOver;
    private int smallBlind;
    private int bigBlind;
    private Map<String, Integer> handContributions;
    private HandSettlementResult lastSettlementResult;
    private Map<String, Integer> lastFullRaiseByStreet = new HashMap<>();
    private Map<String, Integer> lastActedWagerByPlayer = new HashMap<>();
    private List<String> handSeatOrder = new ArrayList<>();
    private String dealerAnchorPlayerId;
    private String smallBlindPlayerId;
    private String bigBlindPlayerId;
    private boolean terminalCarryOverSettled;
    private boolean forfeitPending;
    private boolean readyCountdownActive;
    private Long readyCountdownDeadlineEpochMs;
    private ReadyGateState readyGateState = ReadyGateState.CLOSED;
    private long handNumber;
    private long stateRevision;
    private GameEndResponse retainedTerminalResult;
    private boolean everyoneHasHadInitialTurn;
    private Set<String> actedPlayersInRound;
    private Map<ScheduledGameTask, Long> scheduledTaskDeadlines;
    private Map<ScheduledGameTask, Long> scheduledTaskGenerations;
    private HandEvaluatorService handEvaluator;

    /**
     * Package-private constructor so callers outside com.pokergame.model
     * instantiate via {@link Game#restoreBuilder()}.
     */
    GameRestoreBuilder() {
    }

    public GameRestoreBuilder gameId(String gameId) {
        this.gameId = gameId;
        return this;
    }

    public GameRestoreBuilder players(List<Player> players) {
        this.players = players;
        return this;
    }

    public GameRestoreBuilder activePlayerIds(List<String> activePlayerIds) {
        this.activePlayerIds = activePlayerIds;
        return this;
    }

    public GameRestoreBuilder remainingDeck(List<Card> remainingDeck) {
        this.remainingDeck = remainingDeck;
        return this;
    }

    public GameRestoreBuilder communityCards(List<Card> communityCards) {
        this.communityCards = communityCards;
        return this;
    }

    public GameRestoreBuilder pot(int pot) {
        this.pot = pot;
        return this;
    }

    public GameRestoreBuilder dealerPosition(int dealerPosition) {
        this.dealerPosition = dealerPosition;
        return this;
    }

    public GameRestoreBuilder smallBlindPosition(int smallBlindPosition) {
        this.smallBlindPosition = smallBlindPosition;
        return this;
    }

    public GameRestoreBuilder bigBlindPosition(int bigBlindPosition) {
        this.bigBlindPosition = bigBlindPosition;
        return this;
    }

    public GameRestoreBuilder currentPlayerPosition(int currentPlayerPosition) {
        this.currentPlayerPosition = currentPlayerPosition;
        return this;
    }

    public GameRestoreBuilder currentHighestBet(int currentHighestBet) {
        this.currentHighestBet = currentHighestBet;
        return this;
    }

    public GameRestoreBuilder preHandCarryOver(int amount) {
        this.preHandCarryOver = amount;
        return this;
    }

    public GameRestoreBuilder currentPhase(GamePhase currentPhase) {
        this.currentPhase = currentPhase;
        return this;
    }

    public GameRestoreBuilder gameOver(boolean gameOver) {
        this.gameOver = gameOver;
        return this;
    }

    public GameRestoreBuilder smallBlind(int smallBlind) {
        this.smallBlind = smallBlind;
        return this;
    }

    public GameRestoreBuilder bigBlind(int bigBlind) {
        this.bigBlind = bigBlind;
        return this;
    }

    public GameRestoreBuilder handContributions(Map<String, Integer> handContributions) {
        this.handContributions = handContributions;
        return this;
    }

    public GameRestoreBuilder lastSettlementResult(HandSettlementResult result) {
        this.lastSettlementResult = result;
        return this;
    }

    public GameRestoreBuilder lastFullRaiseByStreet(Map<String, Integer> value) {
        this.lastFullRaiseByStreet = value;
        return this;
    }

    public GameRestoreBuilder lastActedWagerByPlayer(Map<String, Integer> value) {
        this.lastActedWagerByPlayer = value;
        return this;
    }

    public GameRestoreBuilder handSeatOrder(List<String> value) {
        this.handSeatOrder = value;
        return this;
    }

    public GameRestoreBuilder dealerAnchorPlayerId(String value) {
        this.dealerAnchorPlayerId = value;
        return this;
    }

    public GameRestoreBuilder smallBlindPlayerId(String value) {
        this.smallBlindPlayerId = value;
        return this;
    }

    public GameRestoreBuilder bigBlindPlayerId(String value) {
        this.bigBlindPlayerId = value;
        return this;
    }

    public GameRestoreBuilder terminalCarryOverSettled(boolean value) {
        this.terminalCarryOverSettled = value;
        return this;
    }

    public GameRestoreBuilder forfeitPending(boolean value) {
        this.forfeitPending = value;
        return this;
    }

    public GameRestoreBuilder readyCountdownActive(boolean readyCountdownActive) {
        this.readyCountdownActive = readyCountdownActive;
        return this;
    }

    public GameRestoreBuilder readyCountdownDeadlineEpochMs(Long readyCountdownDeadlineEpochMs) {
        this.readyCountdownDeadlineEpochMs = readyCountdownDeadlineEpochMs;
        return this;
    }

    public GameRestoreBuilder readyGateState(ReadyGateState value) {
        this.readyGateState = value;
        return this;
    }

    public GameRestoreBuilder handNumber(long value) {
        this.handNumber = value;
        return this;
    }

    public GameRestoreBuilder stateRevision(long value) {
        this.stateRevision = value;
        return this;
    }

    public GameRestoreBuilder retainedTerminalResult(GameEndResponse value) {
        this.retainedTerminalResult = value;
        return this;
    }

    public GameRestoreBuilder everyoneHasHadInitialTurn(boolean everyoneHasHadInitialTurn) {
        this.everyoneHasHadInitialTurn = everyoneHasHadInitialTurn;
        return this;
    }

    public GameRestoreBuilder actedPlayersInRound(Set<String> actedPlayersInRound) {
        this.actedPlayersInRound = actedPlayersInRound;
        return this;
    }

    public GameRestoreBuilder scheduledTaskDeadlines(Map<ScheduledGameTask, Long> scheduledTaskDeadlines) {
        this.scheduledTaskDeadlines = scheduledTaskDeadlines;
        return this;
    }

    public GameRestoreBuilder scheduledTaskGenerations(Map<ScheduledGameTask, Long> value) {
        this.scheduledTaskGenerations = value;
        return this;
    }

    public GameRestoreBuilder handEvaluator(HandEvaluatorService handEvaluator) {
        this.handEvaluator = handEvaluator;
        return this;
    }

    public Game build() {
        return new Game(this);
    }

    String getGameId() {
        return gameId;
    }

    List<Player> getPlayers() {
        return players;
    }

    List<String> getActivePlayerIds() {
        return activePlayerIds;
    }

    List<Card> getRemainingDeck() {
        return remainingDeck;
    }

    List<Card> getCommunityCards() {
        return communityCards;
    }

    int getPot() {
        return pot;
    }

    int getDealerPosition() {
        return dealerPosition;
    }

    int getSmallBlindPosition() {
        return smallBlindPosition;
    }

    int getBigBlindPosition() {
        return bigBlindPosition;
    }

    int getCurrentPlayerPosition() {
        return currentPlayerPosition;
    }

    int getCurrentHighestBet() {
        return currentHighestBet;
    }

    int getPreHandCarryOver() {
        return preHandCarryOver;
    }

    GamePhase getCurrentPhase() {
        return currentPhase;
    }

    boolean isGameOver() {
        return gameOver;
    }

    int getSmallBlind() {
        return smallBlind;
    }

    int getBigBlind() {
        return bigBlind;
    }

    Map<String, Integer> getHandContributions() {
        return handContributions;
    }

    HandSettlementResult getLastSettlementResult() {
        return lastSettlementResult;
    }

    Map<String, Integer> getLastFullRaiseByStreet() {
        return lastFullRaiseByStreet;
    }

    Map<String, Integer> getLastActedWagerByPlayer() {
        return lastActedWagerByPlayer;
    }

    List<String> getHandSeatOrder() {
        return handSeatOrder;
    }

    String getDealerAnchorPlayerId() {
        return dealerAnchorPlayerId;
    }

    String getSmallBlindPlayerId() {
        return smallBlindPlayerId;
    }

    String getBigBlindPlayerId() {
        return bigBlindPlayerId;
    }

    boolean isTerminalCarryOverSettled() {
        return terminalCarryOverSettled;
    }

    boolean isForfeitPending() {
        return forfeitPending;
    }

    boolean isReadyCountdownActive() {
        return readyCountdownActive;
    }

    Long getReadyCountdownDeadlineEpochMs() {
        return readyCountdownDeadlineEpochMs;
    }

    ReadyGateState getReadyGateState() {
        return readyGateState;
    }

    long getHandNumber() {
        return handNumber;
    }

    long getStateRevision() {
        return stateRevision;
    }

    GameEndResponse getRetainedTerminalResult() {
        return retainedTerminalResult;
    }

    boolean isEveryoneHasHadInitialTurn() {
        return everyoneHasHadInitialTurn;
    }

    Set<String> getActedPlayersInRound() {
        return actedPlayersInRound;
    }

    Map<ScheduledGameTask, Long> getScheduledTaskDeadlines() {
        return scheduledTaskDeadlines;
    }

    Map<ScheduledGameTask, Long> getScheduledTaskGenerations() {
        return scheduledTaskGenerations;
    }

    HandEvaluatorService getHandEvaluator() {
        return handEvaluator;
    }
}
