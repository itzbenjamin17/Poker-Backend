package com.pokergame.model;

import com.pokergame.dto.internal.PlayerDecision;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.service.HandEvaluatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import com.pokergame.dto.internal.TurnOutcome;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.model.PotCalculator.PotComputationResult;

/**
 * Represents a poker game instance with players, betting rounds, and game state
 * management.
 * Handles game progression, betting logic, and hand evaluation.
 */
public class Game {

    private static final Logger logger = LoggerFactory.getLogger(Game.class);

    private final String gameId;
    private final List<Player> players;
    private final List<Player> activePlayers;
    private Deck deck;
    private final List<Card> communityCards;
    private int pot;
    private int dealerPosition;
    private int smallBlindPosition;
    private int bigBlindPosition;
    private int currentPlayerPosition;
    private int currentHighestBet;
    private int preHandCarryOver;
    private GamePhase currentPhase;
    private boolean gameOver;
    private final HandEvaluatorService handEvaluator;
    private final int smallBlind;
    private final int bigBlind;
    private final Map<String, Integer> handContributions;
    private HandSettlementResult lastSettlementResult;
    private final Map<String, Integer> lastFullRaiseByStreet;
    private final Map<String, Integer> lastActedWagerByPlayer;
    private final List<String> handSeatOrder;
    private String dealerAnchorPlayerId;
    private String smallBlindPlayerId;
    private String bigBlindPlayerId;
    private boolean terminalCarryOverSettled;
    private boolean forfeitPending;
    private boolean readyCountdownActive;
    private Long readyCountdownDeadlineEpochMs;
    private ReadyGateState readyGateState;
    private long handNumber;
    private long stateRevision;
    private GameEndResponse retainedTerminalResult;

    // Track if everyone has had their initial turn in the current betting round
    private boolean everyoneHasHadInitialTurn;
    private final Set<String> actedPlayersInRound;
    private final Map<ScheduledGameTask, Long> scheduledTaskDeadlines;
    private final Map<ScheduledGameTask, Long> scheduledTaskGenerations;

    /**
     * Creates a new poker game with the specified players and betting parameters.
     *
     * @param gameId        unique identifier for the game
     * @param players       list of players participating in the game (must have at
     *                      least 2 players)
     * @param smallBlind    the small blind amount
     * @param bigBlind      the big blind amount
     * @param handEvaluator service for evaluating poker hands
     * @throws BadRequestException if gameId is null/empty, players list is
     *                             invalid, or contains null elements
     */
    public Game(String gameId, List<Player> players, int smallBlind, int bigBlind, HandEvaluatorService handEvaluator) {
        if (gameId == null || gameId.trim().isEmpty()) {
            logger.error("[Game] Invalid gameId: '{}'", gameId);
            throw new BadRequestException("Game ID cannot be null or empty");
        }
        if (players == null || players.size() < 2) {
            logger.error("[Game] Invalid players list: null or too few players (size: {})",
                    players == null ? 0 : players.size());
            throw new BadRequestException("At least 2 players are required to start a game");
        }
        if (players.stream().anyMatch(Objects::isNull)) {
            logger.error("[Game] Players list contains null element(s)");
            throw new BadRequestException("Invalid players list. Please try again.");
        }
        this.gameId = gameId;
        this.players = new ArrayList<>(players);
        this.activePlayers = new ArrayList<>(players);
        this.deck = new Deck();
        this.communityCards = new ArrayList<>();
        this.pot = 0;
        this.dealerPosition = 0;
        updatePositionsForCurrentTable();
        this.currentHighestBet = 0;
        this.preHandCarryOver = 0;
        this.currentPhase = GamePhase.PRE_FLOP;
        this.gameOver = false;
        this.smallBlind = smallBlind;
        this.bigBlind = bigBlind;
        this.handEvaluator = handEvaluator;
        this.everyoneHasHadInitialTurn = false;
        this.actedPlayersInRound = new HashSet<>();
        this.handContributions = new HashMap<>();
        this.lastSettlementResult = null;
        this.lastFullRaiseByStreet = new HashMap<>();
        this.lastActedWagerByPlayer = new HashMap<>();
        this.handSeatOrder = new ArrayList<>(players.stream().map(Player::getPlayerId).toList());
        this.dealerAnchorPlayerId = activePlayers.isEmpty() ? null : activePlayers.get(dealerPosition).getPlayerId();
        this.terminalCarryOverSettled = false;
        this.forfeitPending = false;
        this.readyCountdownActive = false;
        this.readyCountdownDeadlineEpochMs = null;
        this.readyGateState = ReadyGateState.CLOSED;
        this.handNumber = 0;
        this.stateRevision = 0;
        this.retainedTerminalResult = null;
        this.scheduledTaskDeadlines = new EnumMap<>(ScheduledGameTask.class);
        this.scheduledTaskGenerations = new EnumMap<>(ScheduledGameTask.class);
    }

    /**
     * Package-private constructor for restoring a game instance from snapshot state.
     * Invoked exclusively by {@link GameRestoreBuilder#build()}.
     */
    Game(GameRestoreBuilder builder) {
        if (builder.getGameId() == null || builder.getGameId().trim().isEmpty()) {
            logger.error("[Game] Invalid gameId: '{}'", builder.getGameId());
            throw new BadRequestException("Game ID cannot be null or empty");
        }
        List<Player> builderPlayers = builder.getPlayers();
        if (builderPlayers == null || builderPlayers.stream().anyMatch(Objects::isNull)) {
            logger.error("[Game] Players list contains null element(s) or is null");
            throw new BadRequestException("Invalid players list. Please try again.");
        }
        this.gameId = builder.getGameId();
        this.players = new ArrayList<>(builderPlayers);

        Map<String, Player> playersById = new HashMap<>();
        this.players.forEach(player -> playersById.put(player.getPlayerId(), player));
        this.activePlayers = new ArrayList<>();
        List<String> activeIds = builder.getActivePlayerIds();
        if (activeIds != null) {
            for (String playerId : activeIds) {
                Player player = playersById.get(playerId);
                if (player == null) {
                    throw new BadRequestException("Snapshot references an unknown active player");
                }
                this.activePlayers.add(player);
            }
        }

        List<Card> remainingDeck = builder.getRemainingDeck();
        this.deck = remainingDeck != null ? Deck.restore(remainingDeck) : new Deck();
        this.communityCards = builder.getCommunityCards() != null ? new ArrayList<>(builder.getCommunityCards()) : new ArrayList<>();
        this.pot = builder.getPot();
        this.dealerPosition = builder.getDealerPosition();
        this.smallBlindPosition = builder.getSmallBlindPosition();
        this.bigBlindPosition = builder.getBigBlindPosition();
        this.currentPlayerPosition = builder.getCurrentPlayerPosition();
        this.currentHighestBet = builder.getCurrentHighestBet();
        this.preHandCarryOver = builder.getPreHandCarryOver();
        this.currentPhase = builder.getCurrentPhase();
        this.gameOver = builder.isGameOver();
        this.smallBlind = builder.getSmallBlind();
        this.bigBlind = builder.getBigBlind();
        this.handEvaluator = builder.getHandEvaluator();

        Map<String, Integer> handContributions = builder.getHandContributions();
        this.handContributions = handContributions != null ? new HashMap<>(handContributions) : new HashMap<>();
        this.lastSettlementResult = builder.getLastSettlementResult();

        Map<String, Integer> lastFullRaiseByStreet = builder.getLastFullRaiseByStreet();
        this.lastFullRaiseByStreet = lastFullRaiseByStreet != null ? new HashMap<>(lastFullRaiseByStreet) : new HashMap<>();

        Map<String, Integer> lastActedWagerByPlayer = builder.getLastActedWagerByPlayer();
        this.lastActedWagerByPlayer = lastActedWagerByPlayer != null ? new HashMap<>(lastActedWagerByPlayer) : new HashMap<>();

        List<String> handSeatOrder = builder.getHandSeatOrder();
        this.handSeatOrder = (handSeatOrder != null && !handSeatOrder.isEmpty())
                ? new ArrayList<>(handSeatOrder)
                : new ArrayList<>(this.players.stream().map(Player::getPlayerId).toList());

        this.dealerAnchorPlayerId = builder.getDealerAnchorPlayerId();
        this.smallBlindPlayerId = builder.getSmallBlindPlayerId();
        this.bigBlindPlayerId = builder.getBigBlindPlayerId();
        this.terminalCarryOverSettled = builder.isTerminalCarryOverSettled();
        this.forfeitPending = builder.isForfeitPending();
        this.readyCountdownActive = builder.isReadyCountdownActive();
        this.readyCountdownDeadlineEpochMs = builder.getReadyCountdownDeadlineEpochMs();

        ReadyGateState gateState = builder.getReadyGateState();
        this.readyGateState = ReadyGate.resolveInitialState(gateState, this.readyCountdownActive);

        this.handNumber = builder.getHandNumber();
        this.stateRevision = builder.getStateRevision();
        this.retainedTerminalResult = builder.getRetainedTerminalResult();
        this.everyoneHasHadInitialTurn = builder.isEveryoneHasHadInitialTurn();

        Set<String> actedPlayersInRound = builder.getActedPlayersInRound();
        this.actedPlayersInRound = actedPlayersInRound != null ? new HashSet<>(actedPlayersInRound) : new HashSet<>();

        this.scheduledTaskDeadlines = new EnumMap<>(ScheduledGameTask.class);
        Map<ScheduledGameTask, Long> scheduledTaskDeadlines = builder.getScheduledTaskDeadlines();
        if (scheduledTaskDeadlines != null) {
            this.scheduledTaskDeadlines.putAll(scheduledTaskDeadlines);
        }

        this.scheduledTaskGenerations = new EnumMap<>(ScheduledGameTask.class);
        Map<ScheduledGameTask, Long> scheduledTaskGenerations = builder.getScheduledTaskGenerations();
        if (scheduledTaskGenerations != null) {
            this.scheduledTaskGenerations.putAll(scheduledTaskGenerations);
        }
    }

    /**
     * Creates a builder for restoring a game instance from snapshot state.
     *
     * @return a new GameRestoreBuilder
     */
    public static GameRestoreBuilder restoreBuilder() {
        return new GameRestoreBuilder();
    }



    /**
     * Resets the game state for a new hand.
     * Cleans up the finished hand and reports whether the game has ended.
     *
     * @return true if the game is over after hand  clean-up, false otherwise
     */
    public boolean resetForNewHand() {
        // Doing this for readability
        int carryOverPot = pot;

        if (logger.isDebugEnabled()) {
            logger.debug("Resetting hand for game {} | carryOverPot={} | previousContributions={}",
                    gameId,
                    carryOverPot,
                    handContributions);
        }

        cleanupAfterHand();

        if (gameOver) {
            return true;
        }

        // Only reclassify the pot as a pre-hand remainder after this state has
        // been accepted as the start of another hand. An early terminal return
        // must retain its original contribution ledger and carry-over category.
        preHandCarryOver = carryOverPot;
        deck = new Deck();
        communityCards.clear();
        pot = carryOverPot;
        currentHighestBet = 0;
        currentPhase = GamePhase.PRE_FLOP;
        everyoneHasHadInitialTurn = false;
        handContributions.clear();
        actedPlayersInRound.clear();
        lastFullRaiseByStreet.clear();
        lastActedWagerByPlayer.clear();
        lastSettlementResult = null;
        terminalCarryOverSettled = false;
        ReadyGate.ReadyGateStateTransition readyReset = ReadyGate.closeCountdown();
        readyCountdownActive = readyReset.active();
        readyCountdownDeadlineEpochMs = readyReset.deadlineEpochMs();
        readyGateState = readyReset.state();

        logger.debug("Hand reset complete for game {} | contributions cleared", gameId);

        activePlayers.clear();
        for (Player player : players) {
            player.setReadyForNextHand(false);
            if (!player.getIsOut()) {
                player.resetAttributes();
                activePlayers.add(player);
            }
        }

        if (activePlayers.size() <= 1) {
            // The lifecycle coordinator freezes the winner and emits GAME_END
            // after this outcome is known; do not mark terminal before that event
            // can be captured exactly once.
            return true;
        } else {
            gameOver = false;
            advancePositions();
            return false;
        }
    }

    /**
     * Deals two hole cards to each active player from the deck.
     */
    public void dealHoleCards() {
        for (Player player : activePlayers) {
            player.getHoleCards().addAll(deck.dealCards(2));
        }
    }

    /**
     * Deducts the small and big blind from the appropriate players.
     * If a player has insufficient chips, they automatically go all-in.
     * Updates the pot and sets the current highest bet to the big blind amount.
     */
    public void postBlinds() {
        if (activePlayers.size() >= 2) {
            normalizeBlindPositions();
            Player smallBlindPlayer = activePlayers.get(smallBlindPosition);
            Player bigBlindPlayer = activePlayers.get(bigBlindPosition);
            smallBlindPlayerId = smallBlindPlayer.getPlayerId();
            bigBlindPlayerId = bigBlindPlayer.getPlayerId();

            logger.debug("Posting blinds for game {} | SB={} (chips={}) | BB={} (chips={}) | potBefore={}",
                    gameId,
                    smallBlindPlayer.getName(),
                    smallBlindPlayer.getChips(),
                    bigBlindPlayer.getName(),
                    bigBlindPlayer.getChips(),
                    pot);

            postBlind(smallBlindPlayer, smallBlind);
            postBlind(bigBlindPlayer, bigBlind);

            currentHighestBet = Math.max(smallBlindPlayer.getCurrentBet(), bigBlindPlayer.getCurrentBet());

            logger.debug(
                    "Blinds posted for game {} | sbBet={} | bbBet={} | potAfter={} | currentHighestBet={} | contributions={}",
                    gameId,
                    smallBlindPlayer.getCurrentBet(),
                    bigBlindPlayer.getCurrentBet(),
                    pot,
                    currentHighestBet,
                    handContributions);
        } else {
            throw new BadRequestException(
                    String.format("Trying to post blinds for a game %s which has less than 2 players", gameId));
        }
    }

    /**
     * Normalizes the blind positions to be within the bounds of the active players
     * list.
     */
    private void normalizeBlindPositions() {
        if (activePlayers.isEmpty()) {
            smallBlindPosition = 0;
            bigBlindPosition = 0;
            return;
        }
        TableSeating.BlindPositions positions = TableSeating.normalizeBlindPositions(
                activePlayers, smallBlindPosition, bigBlindPosition);
        this.smallBlindPosition = positions.smallBlindPosition();
        this.bigBlindPosition = positions.bigBlindPosition();
    }

    /**
     * Forces a player to post a blind amount, pushing them all-in if they don't have enough chips.
     * Updates the game pot and tracks the player's contribution.
     *
     * @param player      the player posting the blind
     * @param blindAmount the target blind amount
     */
    private void postBlind(Player player, int blindAmount) {
        int betBefore = player.getCurrentBet();
        if (player.getChips() <= blindAmount) {
            this.pot = player.doAction(PlayerAction.ALL_IN, 0, this.pot);
        } else {
            this.pot = player.doAction(PlayerAction.BET, blindAmount, this.pot);
        }
        trackContribution(player, player.getCurrentBet() - betBefore);
    }

    /**
     * Processes a player's betting decision, updates the game state, and handles turn progression.
     *
     * @param player   the player making the decision
     * @param decision the player's betting decision (action and amount)
     * @return a TurnOutcome detailing what happened (e.g., whether the phase advanced or the hand went to showdown)
     * @throws UnauthorisedActionException if an action or raise amount is invalid
     */
    public TurnOutcome processPlayerDecision(Player player, PlayerDecision decision) {
        if (decision == null || decision.action() == null) {
            throw new BadRequestException("Invalid player action");
        }

        if (decision.amount() < 0) {
            throw new BadRequestException("Action amount cannot be negative", "NEGATIVE_AMOUNT", "Amount must be positive; yours: " + decision.amount());
        }

        logger.debug(
                "Processing decision in game {} | player={} | decision={} | currentBet={} | currentHighestBet={} | pot={}",
                gameId,
                player.getName(),
                decision,
                player.getCurrentBet(),
                currentHighestBet,
                pot);

        BettingRound.DecisionResult result = BettingRound.prepareDecision(player, decision, activePlayers,
                currentHighestBet, bigBlind, lastActedWagerByPlayer, getLastFullRaise());
        PlayerDecision actualDecision = result.decision();
        String conversionMessage = result.conversionMessage();

        int potBeforeDecision = this.pot;
        int wagerBeforeDecision = currentHighestBet;

        switch (actualDecision.action()) {
            case FOLD, CHECK -> player.doAction(actualDecision.action(), 0, this.pot);
            case CALL, BET, RAISE -> {
                int amount = result.amount();
                this.pot = player.doAction(actualDecision.action(), amount, this.pot);
                if (player.getCurrentBet() > currentHighestBet) {
                    currentHighestBet = player.getCurrentBet();
                }
            }
            case ALL_IN -> {
                this.pot = player.doAction(PlayerAction.ALL_IN, 0, this.pot);
                if (player.getCurrentBet() > currentHighestBet) {
                    currentHighestBet = player.getCurrentBet();
                }
            }
        }

        trackContribution(player, this.pot - potBeforeDecision);

        if (player.getCurrentBet() > wagerBeforeDecision) {
            int increase = player.getCurrentBet() - wagerBeforeDecision;
            if (increase >= getLastFullRaise()) lastFullRaiseByStreet.put(currentPhase.name(), increase);
        }
        lastActedWagerByPlayer.put(player.getPlayerId(), player.getCurrentBet());

        logger.debug(
                "Decision applied in game {} | player={} | action={} | potDelta={} | potNow={} | playerBet={} | playerChips={} | currentHighestBet={} | contributions={}",
                gameId,
                player.getName(),
                actualDecision.action(),
                (this.pot - potBeforeDecision),
                this.pot,
                player.getCurrentBet(),
                player.getChips(),
                currentHighestBet,
                handContributions);

        actedPlayersInRound.add(player.getPlayerId());

        return reconcileProgressionAfterMutation(conversionMessage, true);
    }

    /**
     * Reconciles hand progression after a membership change. The current player
     * pointer has already been preserved or moved to the departing actor's
     * successor by {@link #removePlayerFromGame(Player)}.
     */
    public TurnOutcome reconcileAfterPlayerRemoval() {
        if (activePlayers.isEmpty()) return new TurnOutcome(TurnOutcome.TurnOutcomeType.NEXT_PLAYER, null, null, 0);
        currentHighestBet = BettingRound.highestBetAfterRemoval(activePlayers, currentHighestBet);
        return reconcileProgressionAfterMutation(null, false);
    }

    /** Resolves whether forced blinds leave an actor or require an automatic runout. */
    public TurnOutcome reconcileAfterForcedBlinds() {
        if (activePlayers.isEmpty()) return new TurnOutcome(TurnOutcome.TurnOutcomeType.NEXT_PLAYER, null, null, 0);
        BettingRound.ForcedBlindProgression progression = BettingRound.afterForcedBlinds(isHandOver(), shouldAutoRunout());
        if (progression == BettingRound.ForcedBlindProgression.RECONCILE) return reconcileProgressionAfterMutation(null, false);
        if (progression == BettingRound.ForcedBlindProgression.AUTO_RUNOUT) return new TurnOutcome(TurnOutcome.TurnOutcomeType.AUTO_ADVANCING, null, null, 0);
        if (BettingRound.needsNextActor(getCurrentPlayer())) nextPlayer();
        return new TurnOutcome(TurnOutcome.TurnOutcomeType.NEXT_PLAYER, null, null, 0);
    }

    private TurnOutcome reconcileProgressionAfterMutation(String conversionMessage, boolean selectNextActor) {
        boolean roundComplete = isBettingRoundComplete();
        if (BettingRound.allActionablePlayersActed(activePlayers, actedPlayersInRound) && !roundComplete) {
            everyoneHasHadInitialTurn = true;
            roundComplete = isBettingRoundComplete();
        }

        TurnOutcome.TurnOutcomeType outcome = BettingRound.progression(
                isHandOver(), roundComplete, currentPhase, shouldAutoRunout());
        if (outcome == TurnOutcome.TurnOutcomeType.SHOWDOWN) {
            int potBeforeDistribution = getPot();
            List<Player> winners = conductShowdown();
            int winningsPerPlayer = winners.isEmpty() ? 0 : potBeforeDistribution / winners.size();
            return new TurnOutcome(outcome, conversionMessage, winners, winningsPerPlayer);
        }
        if (outcome == TurnOutcome.TurnOutcomeType.PHASE_ADVANCED) {
            actedPlayersInRound.clear();
            advancePhase();
        } else if (outcome == TurnOutcome.TurnOutcomeType.NEXT_PLAYER && selectNextActor) {
            nextPlayer();
        }
        return new TurnOutcome(outcome, conversionMessage, null, 0);
    }

    /**
     * Returns the current hand phase's last full raise increment, using the nominal
     * big blind until a full wager establishes another increment.
     *
     * @return minimum full raise increment in chips
     */
    public int getLastFullRaise() {
        return BettingRound.lastFullRaise(lastFullRaiseByStreet, currentPhase, bigBlind);
    }

    /**
     * Calculates chips still owed against the current wager for a seated player.
     *
     * @param player non-null player whose current bet belongs to this hand phase
     * @return nonnegative call amount before applying the player's available stack
     */
    public int getCallDifference(Player player) {
        return BettingRound.callDifference(player, currentHighestBet);
    }

    /**
     * Calculates the additional chips a player must commit for a full raise,
     * including both the outstanding call and the last full raise increment.
     * Short all-ins are authorized separately rather than reducing this amount.
     *
     * @param player non-null seated player
     * @return additional chips required, rather than the resulting total wager
     */
    public int getMinimumRaiseAmount(Player player) {
        return getCallDifference(player) + getLastFullRaise();
    }

    /** True when no remaining actionable player owes chips before a forced runout. */
    public boolean shouldAutoRunout() {
        return BettingRound.shouldAutoRunout(activePlayers, currentHighestBet);
    }

    /**
     * Checks reopening history for a non-null seated player. An unacted player may
     * raise; a prior actor needs a cumulative increase of at least one full raise.
     * Turn ownership, available chips, and opponent eligibility are checked separately.
     *
     * @param player player whose acted-wager history is queried
     * @return whether wager history permits raising
     */
    public boolean isRaiseOpenTo(Player player) {
        return BettingRound.raiseOpenTo(player, lastActedWagerByPlayer, currentHighestBet, getLastFullRaise());
    }

    /**
     * Projects allowed actions for the current eligible actor, accounting for call
     * amount, reopening, stack size, and a contestable opponent.
     *
     * @param player proposed actor, or null when no player is selected
     * @return immutable actions, empty for an ineligible or non-current player
     */
    public List<PlayerAction> getLegalActionsFor(Player player) {
        if (player == null || !players.contains(player) || player.getHasFolded() || player.getIsOut() || player.getIsAllIn()
                || currentPhase == GamePhase.SHOWDOWN) return List.of();
        return BettingRound.legalActions(player, players, activePlayers, getCurrentPlayer(), currentPhase,
                currentHighestBet, lastActedWagerByPlayer, getLastFullRaise());
    }

    /**
     * Tracks how many chips each player has committed during the current hand.
     * This contribution map is later used to build main/side pots and detect
     * uncalled chips.
     *
     * @param player the player whose contribution changed
     * @param amount number of chips newly committed to the pot
     */
    private void trackContribution(Player player, int amount) {
        PotCalculator.trackContribution(this.handContributions, player, amount, this.gameId);
    }

    /**
     * Advances the game to the next logical phase by dealing the appropriate community cards.
     */
    public void advancePhase() {
        actedPlayersInRound.clear();
        lastActedWagerByPlayer.clear();
        switch (currentPhase) {
            case PRE_FLOP -> dealFlop();
            case FLOP -> dealTurn();
            case TURN -> dealRiver();
            case RIVER, SHOWDOWN -> {}
        }
    }

    /**
     * Checks if the current betting round is complete.
     * A betting round is complete when everyone has had their initial turn
     * and no active player needs to act (all non-folded, non-all-in players
     * have matched the current highest bet).
     *
     * @return true if the betting round is complete, false otherwise
     */
    public boolean isBettingRoundComplete() {
        return BettingRound.isComplete(activePlayers, currentHighestBet, everyoneHasHadInitialTurn);
    }

    /**
     * Sets whether all players have had their initial turn in the current betting
     * round.
     *
     * @param value true if everyone has had their initial turn, false otherwise
     */
    public void setEveryoneHasHadInitialTurn(boolean value) {
        this.everyoneHasHadInitialTurn = value;
        logger.debug("Set everyoneHasHadInitialTurn = {}", value);
    }

    /**
     * Deals the flop (first three community cards) and advances to the FLOP phase.
     * Resets all player bets for the new betting round.
     */
    public void dealFlop() {
        communityCards.addAll(deck.dealCards(3));
        currentPhase = GamePhase.FLOP;
        resetBetsForRound();
    }

    /**
     * Deals the turn (fourth community card) and advances to the TURN phase.
     * Resets all player bets for the new betting round.
     */
    public void dealTurn() {
        communityCards.add(deck.dealCard());
        currentPhase = GamePhase.TURN;
        resetBetsForRound();
    }

    /**
     * Deals the river (fifth and final community card) and advances to the RIVER
     * phase.
     * Resets all player bets for the new betting round.
     */
    public void dealRiver() {
        communityCards.add(deck.dealCard());
        currentPhase = GamePhase.RIVER;
        resetBetsForRound();
    }

    /**
     * Conducts the showdown phase where remaining players' hands are evaluated
     * and winners are determined. Distributes the pot to the winner(s).
     *
     * @return list of winning players
     */
    public List<Player> conductShowdown() {
        if (lastSettlementResult != null && lastSettlementResult.settled()) {
            return players.stream().filter(p -> lastSettlementResult.winnerIds().contains(p.getPlayerId())).toList();
        }

        logger.info("Conducting showdown for game {}", gameId);
        currentPhase = GamePhase.SHOWDOWN;

        List<Player> showdownPlayers = activePlayers.stream()
                .filter(p -> !p.getHasFolded() && !p.getIsOut())
                .toList();

        logger.info("Showdown players: {}, Current pot: {}",
                showdownPlayers.stream().map(Player::getName).toList(), pot);

        if (showdownPlayers.isEmpty()) {
            // No retained hand contender can receive this money. Keep it as named
            // carry-over so the normal ready/new-hand path can continue safely.
            lastSettlementResult = new HandSettlementResult(Map.of(), Map.of(), Set.of(), pot, true);
            resetHandAccountingAfterShowdown();
            return List.of();
        }

        if (showdownPlayers.size() > 1) {
            logger.debug("Evaluating hands for {} players", showdownPlayers.size());
            evaluateHands(showdownPlayers);
        }

        if (logger.isDebugEnabled()) {
            showdownPlayers
                    .forEach(p -> logger.debug("Player {}: {} with {}", p.getName(), p.getHandRank(), p.getBestHand()));
        }

        logger.debug("Determining winners from {} players", showdownPlayers.size());
        List<Player> winners = distributeSidePotsAtShowdown(showdownPlayers);

        resetHandAccountingAfterShowdown();

        logger.debug("Pot after distribution: {}", pot);
        if (logger.isDebugEnabled()) {
            winners.forEach(p -> logger.debug("Player {} now has {} chips", p.getName(), p.getChips()));
        }

        logger.info("Showdown complete");
        return winners;
    }

    /**
     * Evaluates the poker hands for all given players using community cards
     * and their hole cards. Updates each player's best hand and hand rank.
     *
     * @param players list of players whose hands should be evaluated
     */
    private void evaluateHands(List<Player> players) {
        ShowdownResolver.evaluateHands(handEvaluator, communityCards, players);
    }

    /**
     * Resolves and distributes all side pots at showdown.
     *
     * @return ordered set of unique players who won at least one allocation
     */
    private List<Player> distributeSidePotsAtShowdown(List<Player> showdownPlayers) {
        logger.info("Distributing side pots for game {}", gameId);
        PotComputationResult computation = computePotAllocationsAndUncalled(showdownPlayers);
        ShowdownResolver.Distribution distribution =
                ShowdownResolver.distribute(gameId, handEvaluator, computation, players);
        pot = distribution.remainder();
        lastSettlementResult = distribution.settlement();
        return distribution.winners();
    }

    /**
     * Decomposes the current pot into contested allocation layers and uncalled
     * layers.
     *
     * @return a full breakdown used for payout distribution and UI pot rendering
     */
    private PotComputationResult computePotAllocationsAndUncalled(List<Player> eligibleHandContenders) {
        return PotCalculator.computePotAllocationsAndUncalled(
                pot, preHandCarryOver, handContributions, activePlayers, players, eligibleHandContenders, gameId);
    }

    /**
     * Clears per-hand contribution ledger and round action bookkeeping
     * after showdown distribution completes.
     */
    private void resetHandAccountingAfterShowdown() {
        handContributions.clear();
        actedPlayersInRound.clear();
        logger.debug("Reset hand accounting after showdown for game {} | potRemainder={}", gameId, pot);
    }

    /**
     * Returns the contested pot amounts for display (main pot first, then side
     * pots).
     * Uncalled amounts are intentionally excluded from this list because they are
     * refunded, not contested.
     *
     * @return list of contested pot amounts in showdown order
     */
    public List<Integer> getPotBreakdown() {
        return PotCalculator.getPotBreakdown(
                pot,
                preHandCarryOver,
                handContributions,
                activePlayers,
                players,
                lastSettlementResult,
                currentHighestBet,
                gameId);
    }

    /**
     * Returns the total uncalled amount currently detected in the pot
     * decomposition.
     *
     * @return total chips that should be refunded to players as uncalled
     */
    public int getUncalledAmount() {
        return PotCalculator.getUncalledAmount(
                pot,
                preHandCarryOver,
                handContributions,
                activePlayers,
                players,
                lastSettlementResult,
                currentHighestBet,
                gameId);
    }

    /**
     * Advances dealer, small blind, big blind, and current player positions
     * for the next hand. Rotates positions clockwise around the table.
     */
    public void advancePositions() {
        if (activePlayers.isEmpty()) return;
        TableSeating.PositionRotationResult result = TableSeating.advancePositions(
                activePlayers, handSeatOrder, dealerAnchorPlayerId);
        this.dealerPosition = result.dealerPosition();
        this.dealerAnchorPlayerId = result.dealerAnchorPlayerId();
        this.smallBlindPosition = result.smallBlindPosition();
        this.bigBlindPosition = result.bigBlindPosition();
        this.currentPlayerPosition = result.currentPlayerPosition();
        smallBlindPlayerId = null;
        bigBlindPlayerId = null;
    }

    /**
     * Recomputes blind and first-action positions after table-size changes. Heads-up
     * poker is handled separately because the dealer is also the small blind and acts
     * first pre-flop.
     */
    private void updatePositionsForCurrentTable() {
        TableSeating.TablePositions positions = TableSeating.updatePositionsForCurrentTable(
                activePlayers, dealerPosition);
        this.smallBlindPosition = positions.smallBlindPosition();
        this.bigBlindPosition = positions.bigBlindPosition();
        this.currentPlayerPosition = positions.currentPlayerPosition();
    }

    /**
     * Cleans up after a hand by removing players with zero chips from active play.
     * Sets the game as over if only one or fewer active players remain.
     */
    public void cleanupAfterHand() {
        logger.debug("Cleaning up after hand for game {}", gameId);

        if (logger.isDebugEnabled()) {
            logger.debug("Players before cleanup:");
            players.forEach(p -> logger.debug("  {}: {} chips, isOut: {}", p.getName(), p.getChips(), p.getIsOut()));
        }

        players.forEach(p -> {
            if (p.getChips() == 0) {
                logger.info("Setting {} as out (0 chips)", p.getName());
                p.setIsOut();
            }
        });

        int sizeBefore = activePlayers.size();
        activePlayers.removeIf(Player::getIsOut);
        int sizeAfter = activePlayers.size();

        logger.info("Active players: {} -> {}", sizeBefore, sizeAfter);
        logger.debug("Active players after cleanup: {}", activePlayers.stream().map(Player::getName).toList());

        if (activePlayers.size() <= 1) {
            logger.info("Game over - only {} active players remaining", activePlayers.size());
            gameOver = true;
        } else {
            logger.debug("Game continues with {} active players", activePlayers.size());
        }
    }

    /**
     * Checks if the current hand is over (only one or zero active players remain
     * who haven't folded).
     *
     * @return true if the hand is over, false otherwise
     */
    public boolean isHandOver() {
        long activePlayerCount = activePlayers.stream().filter(p -> !p.getHasFolded() && !p.getIsOut()).count();
        return activePlayerCount <= 1;
    }

    /**
     * Opens the ready-confirmation gate and clears prior ready states.
     *
     * @param deadlineEpochMs UTC epoch milliseconds when countdown expires
     */
    public void openReadyCountdown(long deadlineEpochMs) {
        ReadyGate.ReadyGateStateTransition transition = ReadyGate.openCountdown(players, deadlineEpochMs);
        this.readyCountdownActive = transition.active();
        this.readyCountdownDeadlineEpochMs = transition.deadlineEpochMs();
        this.readyGateState = transition.state();
    }

    /**
     * Closes the ready-confirmation gate.
     */
    public void closeReadyCountdown() {
        ReadyGate.ReadyGateStateTransition transition = ReadyGate.closeCountdown();
        this.readyCountdownActive = transition.active();
        this.readyCountdownDeadlineEpochMs = transition.deadlineEpochMs();
        this.readyGateState = transition.state();
    }

    /** Closes ticking readiness while keeping the completed hand available for review. */
    public void waitForReadyPlayers() {
        ReadyGate.ReadyGateStateTransition transition = ReadyGate.waitForPlayers(players);
        this.readyCountdownActive = transition.active();
        this.readyCountdownDeadlineEpochMs = transition.deadlineEpochMs();
        this.readyGateState = transition.state();
    }

    /** Begins a new numbered hand after the previous hand has settled. */
    public long beginHand() {
        this.handNumber = ReadyGate.beginHand(this.handNumber);
        this.stateRevision = GameScheduling.resetStateRevisionForNewHand();
        return this.handNumber;
    }

    public long getHandNumber() { return handNumber; }

    public ReadyGateState getReadyGateState() { return readyGateState; }

    public int getReadyEligibleCount() { return ReadyGate.getReadyEligibleCount(players); }

    /**
     * Returns whether the ready countdown gate is currently active.
     *
     * @return true when ready gate is active, false otherwise
     */
    public boolean isReadyCountdownActive() {
        return readyCountdownActive;
    }

    /**
     * Returns the UTC epoch milliseconds when the ready countdown expires.
     *
     * @return deadline timestamp, or null if no ready gate is active
     */
    public Long getReadyCountdownDeadlineEpochMs() {
        return readyCountdownDeadlineEpochMs;
    }

    /**
     * Returns true when all currently eligible players are marked ready.
     *
     * <p>
     * Eligible players are connected players that are not out of the game.
     * </p>
     *
     * @return true if all eligible players are ready, false otherwise
     */
    public boolean areAllReadyEligiblePlayersReady() {
        return ReadyGate.areAllEligiblePlayersReady(players);
    }

    /**
     * Marks all currently eligible players as ready.
     */
    public void forceReadyForEligiblePlayers() {
        ReadyGate.forceReadyForEligiblePlayers(players);
    }

    /**
     * Checks if the tournament/game is over (one or zero players with chips remain).
     *
     * @return true if the tournament is over, false otherwise
     */
    public boolean isTournamentOver() {
        long playersWithChips = players.stream()
                .filter(player -> !player.getIsOut() && player.getChips() > 0)
                .count();
        return playersWithChips <= 1;
    }

    /**
     * Resets all player bets to zero for a new betting round.
     * Also resets the current highest bet and the initial turn tracking flag.
     */
    public void resetBetsForRound() {
        for (Player player : activePlayers) {
            player.resetCurrentBet();
        }
        currentHighestBet = 0;
        everyoneHasHadInitialTurn = false; // Reset for the new betting round
        actedPlayersInRound.clear();
        lastActedWagerByPlayer.clear();
        // The compacted active-player index can shift after a departure. Resolve the
        // first post-flop actor from the immutable hand seat order and dealer anchor.
        currentPlayerPosition = firstActionableSeatAfterDealerAnchor();
    }

    /**
     * Returns the unique game identifier.
     *
     * @return the game ID
     */
    public String getGameId() {
        return gameId;
    }

    /**
     * Returns all players in the game, including those who are out.
     *
     * @return the list of all players
     */
    public List<Player> getPlayers() {
        return players;
    }

    /**
     * Returns the list of active players (players still in the game with chips).
     *
     * @return the list of active players
     */
    public List<Player> getActivePlayers() {
        return activePlayers;
    }

    /**
     * Removes a player from the game and reconciles turn index so it always points
     * to a valid seat in {@code activePlayers}.
     *
     * @param playerToRemove the player to remove from game and active lists
     */
    public void removePlayerFromGame(Player playerToRemove) {
        if (playerToRemove == null) {
            return;
        }

        actedPlayersInRound.remove(playerToRemove.getPlayerId());
        lastActedWagerByPlayer.remove(playerToRemove.getPlayerId());

        TableSeating.PlayerRemovalResult result = TableSeating.removePlayerFromGame(
                players,
                activePlayers,
                playerToRemove,
                dealerPosition,
                smallBlindPosition,
                bigBlindPosition,
                currentPlayerPosition,
                dealerAnchorPlayerId,
                smallBlindPlayerId,
                bigBlindPlayerId
        );

        this.dealerPosition = result.dealerPosition();
        this.smallBlindPosition = result.smallBlindPosition();
        this.bigBlindPosition = result.bigBlindPosition();
        this.currentPlayerPosition = result.currentPlayerPosition();
    }

    private int firstActionableSeatAfterDealerAnchor() {
        return TableSeating.firstActionableSeatAfterDealerAnchor(
                activePlayers, handSeatOrder, dealerAnchorPlayerId, dealerPosition);
    }

    /**
     * Normalizes the current player position to be within the bounds of the active
     * players list.
     */
    private void normalizeCurrentPlayerPosition() {
        this.currentPlayerPosition = TableSeating.normalizeCurrentPlayerPosition(activePlayers, currentPlayerPosition);
    }

    /**
     * Returns the player whose turn it currently is.
     *
     * @return the current player
     */
    public Player getCurrentPlayer() {
        if (activePlayers.isEmpty()) {
            throw new IllegalStateException("No active players available");
        }

        normalizeCurrentPlayerPosition();
        return activePlayers.get(currentPlayerPosition);
    }

    /**
     * Advances to the next player in turn order.
     */
    public void nextPlayer() {
        this.currentPlayerPosition = TableSeating.nextPlayer(activePlayers, currentPlayerPosition);
    }

    /**
     * Returns the community cards currently on the table.
     *
     * @return the list of community cards
     */
    public List<Card> getCommunityCards() {
        return communityCards;
    }

    /**
     * Returns the current pot amount.
     *
     * @return the pot value
     */
    public int getPot() {
        return pot;
    }

    /**
     * Returns the current phase of the game (PRE_FLOP, FLOP, TURN, RIVER, or
     * SHOWDOWN).
     *
     * @return the current game phase
     */
    public GamePhase getCurrentPhase() {
        return currentPhase;
    }

    /**
     * Returns whether the game is over (one or fewer active players remain).
     *
     * @return true if the game is over, false otherwise
     */
    public boolean isGameOver() {
        return gameOver;
    }

    /**
     * Marks the game as terminal without running {@link #cleanupAfterHand()}, so the
     * game-end path can flag the game as finished while leaving the showdown
     * projection (active players, revealed hands) untouched for the review broadcast.
     */
    public void markGameOver() {
        gameOver = true;
    }

    /**
     * Determines the actual winner of the tournament by chip count rather than by seat
     * order. Among players who are not out, returns the one holding the most chips.
     * Ties (which should not occur once {@link #isTournamentOver()} holds) are resolved
     * deterministically by seat index in {@link #getPlayers()}.
     *
     * @return the winning player, or null if no eligible player remains
     */
    public Player resolveGameWinner() {
        return players.stream()
                .filter(player -> !player.getIsOut())
                .max(Comparator.comparingInt(Player::getChips)
                        .thenComparing(Comparator.comparingInt(players::indexOf).reversed()))
                .orElse(null);
    }

    /**
     * Returns the current highest bet in the current betting round.
     *
     * @return the highest bet amount
     */
    public int getCurrentHighestBet() {
        return currentHighestBet;
    }

    /**
     * Returns the position index of the dealer in the active players list.
     *
     * @return the dealer position
     */
    public int getDealerPosition() {
        return dealerPosition;
    }

    /**
     * Returns the ID of the player currently assigned as small blind.
     *
     * @return small blind player ID, or null when no active players exist
     */
    public String getSmallBlindPlayerId() {
        if (smallBlindPlayerId != null) return smallBlindPlayerId;
        if (activePlayers.isEmpty()) {
            return null;
        }

        normalizeBlindPositions();

        return activePlayers.get(smallBlindPosition).getPlayerId();
    }

    /**
     * Returns the ID of the player currently assigned as big blind.
     *
     * @return big blind player ID, or null when no active players exist
     */
    public String getBigBlindPlayerId() {
        if (bigBlindPlayerId != null) return bigBlindPlayerId;
        if (activePlayers.isEmpty()) {
            return null;
        }

        normalizeBlindPositions();

        return activePlayers.get(bigBlindPosition).getPlayerId();
    }

    /**
     * Captures future draw order without exposing the live deck to mutation.
     *
     * @return immutable remaining-deck snapshot
     */
    public List<Card> getRemainingDeckSnapshot() {
        return deck.getRemainingCardsSnapshot();
    }

    /**
     * Persists blind placement because recalculating it would alter turn order.
     *
     * @return small-blind index
     */
    public int getSmallBlindPosition() {
        return smallBlindPosition;
    }

    /**
     * Persists blind placement because recalculating it would alter turn order.
     *
     * @return big-blind index
     */
    public int getBigBlindPosition() {
        return bigBlindPosition;
    }

    /**
     * Persists the exact actor so recovery cannot grant a different player the turn.
     *
     * @return current-player index
     */
    public int getCurrentPlayerPosition() {
        return currentPlayerPosition;
    }

    /**
     * Keeps the game snapshot as the authoritative compatibility boundary instead of
     * coupling recovery to a separate room snapshot.
     *
     * @return small blind
     */
    public int getSmallBlind() {
        return smallBlind;
    }

    /**
     * Keeps the game snapshot as the authoritative compatibility boundary instead of
     * coupling recovery to a separate room snapshot.
     *
     * @return big blind
     */
    public int getBigBlind() {
        return bigBlind;
    }

    /**
     * Copies the contribution ledger because exact side-pot settlement depends on
     * amounts that may no longer be represented by current bets.
     *
     * @return immutable player contribution ledger
     */
    public Map<String, Integer> getHandContributionsSnapshot() {
        return Map.copyOf(handContributions);
    }

    public int getPreHandCarryOver() { return preHandCarryOver; }

    public HandSettlementResult getLastSettlementResult() { return lastSettlementResult; }
    public boolean isTerminalCarryOverSettled() { return terminalCarryOverSettled; }
    public boolean isForfeitPending() { return forfeitPending; }
    public void setForfeitPending(boolean value) { forfeitPending = value; }

    /** Completes a terminal game by awarding retained remainder exactly once to its resolved winner. */
    public void completeGameSettlement(Player winner) {
        if (winner == null) return;
        if (lastSettlementResult == null || !lastSettlementResult.settled()) conductShowdown();
        if (terminalCarryOverSettled) return;
        if (pot > 0) {
            winner.addChips(pot);
            pot = 0;
        }
        terminalCarryOverSettled = true;
    }

    public Map<String, Integer> getLastFullRaiseByStreetSnapshot() { return Map.copyOf(lastFullRaiseByStreet); }
    public Map<String, Integer> getLastActedWagerByPlayerSnapshot() { return Map.copyOf(lastActedWagerByPlayer); }
    public List<String> getHandSeatOrderSnapshot() { return List.copyOf(handSeatOrder); }
    public String getDealerAnchorPlayerId() { return dealerAnchorPlayerId; }

    /**
     * Exposes a betting-round progress invariant needed to preserve legal action
     * transitions after recovery.
     *
     * @return whether every eligible player received an initial turn
     */
    public boolean hasEveryoneHadInitialTurn() {
        return everyoneHasHadInitialTurn;
    }

    /**
     * Copies per-round action history so a restart cannot end or extend a betting
     * round incorrectly.
     *
     * @return immutable IDs of players that acted this round
     */
    public Set<String> getActedPlayerIdsSnapshot() {
        return Set.copyOf(actedPlayersInRound);
    }

    /**
     * Records delayed work as domain state before a runtime timer is created, closing
     * the crash window between a committed game transition and scheduler submission.
     *
     * @param task            delayed work type
     * @param deadlineEpochMs absolute execution deadline
     */
    public void scheduleTask(ScheduledGameTask task, long deadlineEpochMs) {
        GameScheduling.scheduleTask(scheduledTaskDeadlines, scheduledTaskGenerations, task, deadlineEpochMs);
    }

    /**
     * Returns the current generation token for a delayed task. Timer callbacks compare
     * it with their expected deadline so stale callbacks become harmless no-ops.
     *
     * @param task delayed work type
     * @return current absolute deadline, or {@code null} when not scheduled
     */
    public Long getScheduledTaskDeadline(ScheduledGameTask task) {
        return GameScheduling.getScheduledTaskDeadline(scheduledTaskDeadlines, task);
    }

    public long getScheduledTaskGeneration(ScheduledGameTask task) {
        return GameScheduling.getScheduledTaskGeneration(scheduledTaskGenerations, task);
    }

    public Map<ScheduledGameTask, Long> getScheduledTaskGenerationsSnapshot() {
        return GameScheduling.getScheduledTaskGenerationsSnapshot(scheduledTaskGenerations);
    }

    /**
     * Clears durable intent in the same mutation that performs the work, making
     * overdue recovery execution idempotent.
     *
     * @param task completed delayed work type
     */
    public void clearScheduledTask(ScheduledGameTask task) {
        GameScheduling.clearScheduledTask(scheduledTaskDeadlines, scheduledTaskGenerations, task);
    }

    /** Invalidates all durable work except terminal cleanup. */
    public void invalidateProgressionTasks() {
        GameScheduling.invalidateProgressionTasks(scheduledTaskDeadlines, scheduledTaskGenerations);
    }

    /**
     * Copies scheduled intent for state-image capture without exposing the live task
     * map to mutation.
     *
     * @return immutable task-to-deadline map
     */
    public Map<ScheduledGameTask, Long> getScheduledTaskDeadlinesSnapshot() {
        return GameScheduling.getScheduledTaskDeadlinesSnapshot(scheduledTaskDeadlines);
    }

    public long getStateRevision() {
        return stateRevision;
    }

    public void incrementStateRevision() {
        this.stateRevision = GameScheduling.incrementStateRevision(this.stateRevision);
    }

    /**
     * Gets the frozen terminal result retained for post-game review and late reconnects.
     *
     * @return the retained terminal {@link com.pokergame.dto.response.GameEndResponse}, or {@code null} if the game has not concluded
     */
    public GameEndResponse getRetainedTerminalResult() {
        return retainedTerminalResult;
    }

    /**
     * Retains the frozen terminal result for post-game review and durable snapshot persistence.
     *
     * @param retainedTerminalResult the frozen terminal {@link com.pokergame.dto.response.GameEndResponse} to retain
     */
    public void setRetainedTerminalResult(GameEndResponse retainedTerminalResult) {
        this.retainedTerminalResult = retainedTerminalResult;
    }
}
