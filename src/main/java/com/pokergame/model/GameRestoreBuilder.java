package com.pokergame.model;

import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.ScheduledGameTask;

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
    private int maxPlayers = Room.MAX_PLAYERS;
    private List<Player> players;
    private List<String> activePlayerIds;
    private List<Card> remainingDeck;
    private List<Card> communityCards;
    private int pot;
    private String currentPlayerId;
    private int currentHighestBet;
    private int carryOverPot;
    private GamePhase currentPhase;
    private boolean gameOver;
    private int smallBlind;
    private int bigBlind;
    private Map<String, Integer> handContributions;
    private HandSettlementResult lastSettlementResult;
    private int lastFullRaise;
    private Map<String, Integer> previousBets = new HashMap<>();
    private List<String> handSeatOrder = new ArrayList<>();
    private String dealerPlayerId;
    private String smallBlindPlayerId;
    private String bigBlindPlayerId;
    private boolean finalPotSettled;
    private boolean pausedForDisconnectedSurvivor;
    private Long readyCountdownDeadlineEpochMs;
    private ReadyGateState readyGateState = ReadyGateState.CLOSED;
    private long handNumber;
    private long stateRevision;
    private GameEndResponse gameEndResult;
    private boolean allPlayersHadInitialTurn;
    private Set<String> actedPlayersInRound;
    private Map<ScheduledGameTask, Long> scheduledTimes;
    private Map<ScheduledGameTask, Long> timerVersions;

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

    public GameRestoreBuilder maxPlayers(int maxPlayers) {
        this.maxPlayers = maxPlayers;
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

    public GameRestoreBuilder currentPlayerId(String currentPlayerId) {
        this.currentPlayerId = currentPlayerId;
        return this;
    }

    public GameRestoreBuilder currentHighestBet(int currentHighestBet) {
        this.currentHighestBet = currentHighestBet;
        return this;
    }

    public GameRestoreBuilder carryOverPot(int amount) {
        this.carryOverPot = amount;
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

    public GameRestoreBuilder lastFullRaise(int value) {
        this.lastFullRaise = value;
        return this;
    }

    public GameRestoreBuilder previousBets(Map<String, Integer> value) {
        this.previousBets = value;
        return this;
    }

    public GameRestoreBuilder handSeatOrder(List<String> value) {
        this.handSeatOrder = value;
        return this;
    }

    public GameRestoreBuilder dealerPlayerId(String value) {
        this.dealerPlayerId = value;
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

    public GameRestoreBuilder finalPotSettled(boolean value) {
        this.finalPotSettled = value;
        return this;
    }

    public GameRestoreBuilder pausedForDisconnectedSurvivor(boolean value) {
        this.pausedForDisconnectedSurvivor = value;
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

    public GameRestoreBuilder gameEndResult(GameEndResponse value) {
        this.gameEndResult = value;
        return this;
    }

    public GameRestoreBuilder allPlayersHadInitialTurn(boolean allPlayersHadInitialTurn) {
        this.allPlayersHadInitialTurn = allPlayersHadInitialTurn;
        return this;
    }

    public GameRestoreBuilder actedPlayersInRound(Set<String> actedPlayersInRound) {
        this.actedPlayersInRound = actedPlayersInRound;
        return this;
    }

    public GameRestoreBuilder scheduledTimes(Map<ScheduledGameTask, Long> scheduledTimes) {
        this.scheduledTimes = scheduledTimes;
        return this;
    }

    public GameRestoreBuilder timerVersions(Map<ScheduledGameTask, Long> value) {
        this.timerVersions = value;
        return this;
    }

    public Game build() {
        return new Game(this);
    }

    String getGameId() {
        return gameId;
    }

    int getMaxPlayers() {
        return maxPlayers;
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

    String getCurrentPlayerId() {
        return currentPlayerId;
    }

    int getCurrentHighestBet() {
        return currentHighestBet;
    }

    int getCarryOverPot() {
        return carryOverPot;
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

    int getLastFullRaise() {
        return lastFullRaise;
    }

    Map<String, Integer> getPreviousBets() {
        return previousBets;
    }

    List<String> getHandSeatOrder() {
        return handSeatOrder;
    }

    String getDealerPlayerId() {
        return dealerPlayerId;
    }

    String getSmallBlindPlayerId() {
        return smallBlindPlayerId;
    }

    String getBigBlindPlayerId() {
        return bigBlindPlayerId;
    }

    boolean isFinalPotSettled() {
        return finalPotSettled;
    }

    boolean isPausedForDisconnectedSurvivor() {
        return pausedForDisconnectedSurvivor;
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

    GameEndResponse getGameEndResult() {
        return gameEndResult;
    }

    boolean isAllPlayersHadInitialTurn() {
        return allPlayersHadInitialTurn;
    }

    Set<String> getActedPlayersInRound() {
        return actedPlayersInRound;
    }

    Map<ScheduledGameTask, Long> getScheduledTimes() {
        return scheduledTimes;
    }

    Map<ScheduledGameTask, Long> getTimerVersions() {
        return timerVersions;
    }
}
