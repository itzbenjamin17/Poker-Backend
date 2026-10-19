package com.pokergame.model;

import com.pokergame.dto.internal.PlayerDecision;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.service.HandEvaluatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import com.pokergame.dto.internal.TurnOutcome;

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
    private boolean readyCountdownActive;
    private Long readyCountdownDeadlineEpochMs;

    // Track if everyone has had their initial turn in the current betting round
    private boolean everyoneHasHadInitialTurn;
    private final Set<String> actedPlayersInRound;
    private final Map<ScheduledGameTask, Long> scheduledTaskDeadlines;

    /**
     * Internal constructor that allows bypassing initial player count validation,
     * which is required when restoring a snapshot of a game where players have left.
     */
    private Game(String gameId, List<Player> players, int smallBlind, int bigBlind, HandEvaluatorService handEvaluator, boolean skipValidation) {
        if (gameId == null || gameId.trim().isEmpty()) {
            logger.error("[Game] Invalid gameId: '{}'", gameId);
            throw new BadRequestException("Game ID cannot be null or empty");
        }
        if (!skipValidation && (players == null || players.size() < 2)) {
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
        this.readyCountdownActive = false;
        this.readyCountdownDeadlineEpochMs = null;
        this.scheduledTaskDeadlines = new EnumMap<>(ScheduledGameTask.class);
    }

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
        this(gameId, players, smallBlind, bigBlind, handEvaluator, false);
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
     * Builder for reconstructing a game instance from snapshot state.
     */
    public static class GameRestoreBuilder {
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
        private boolean readyCountdownActive;
        private Long readyCountdownDeadlineEpochMs;
        private boolean everyoneHasHadInitialTurn;
        private Set<String> actedPlayersInRound;
        private Map<ScheduledGameTask, Long> scheduledTaskDeadlines;
        private HandEvaluatorService handEvaluator;

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

        public GameRestoreBuilder lastFullRaiseByStreet(Map<String, Integer> value) { this.lastFullRaiseByStreet = value; return this; }
        public GameRestoreBuilder lastActedWagerByPlayer(Map<String, Integer> value) { this.lastActedWagerByPlayer = value; return this; }
        public GameRestoreBuilder handSeatOrder(List<String> value) { this.handSeatOrder = value; return this; }
        public GameRestoreBuilder dealerAnchorPlayerId(String value) { this.dealerAnchorPlayerId = value; return this; }
        public GameRestoreBuilder smallBlindPlayerId(String value) { this.smallBlindPlayerId = value; return this; }
        public GameRestoreBuilder bigBlindPlayerId(String value) { this.bigBlindPlayerId = value; return this; }
        public GameRestoreBuilder terminalCarryOverSettled(boolean value) { this.terminalCarryOverSettled = value; return this; }

        public GameRestoreBuilder readyCountdownActive(boolean readyCountdownActive) {
            this.readyCountdownActive = readyCountdownActive;
            return this;
        }

        public GameRestoreBuilder readyCountdownDeadlineEpochMs(Long readyCountdownDeadlineEpochMs) {
            this.readyCountdownDeadlineEpochMs = readyCountdownDeadlineEpochMs;
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

        public GameRestoreBuilder handEvaluator(HandEvaluatorService handEvaluator) {
            this.handEvaluator = handEvaluator;
            return this;
        }

        public Game build() {
            Game game = new Game(gameId, players, smallBlind, bigBlind, handEvaluator, true);
            Map<String, Player> playersById = new HashMap<>();
            players.forEach(player -> playersById.put(player.getPlayerId(), player));
            game.activePlayers.clear();
            for (String playerId : activePlayerIds) {
                Player player = playersById.get(playerId);
                if (player == null) {
                    throw new BadRequestException("Snapshot references an unknown active player");
                }
                game.activePlayers.add(player);
            }
            game.deck = Deck.restore(remainingDeck);
            game.communityCards.clear();
            game.communityCards.addAll(communityCards);
            game.pot = pot;
            game.dealerPosition = dealerPosition;
            game.smallBlindPosition = smallBlindPosition;
            game.bigBlindPosition = bigBlindPosition;
            game.currentPlayerPosition = currentPlayerPosition;
            game.currentHighestBet = currentHighestBet;
            game.preHandCarryOver = preHandCarryOver;
            game.currentPhase = currentPhase;
            game.gameOver = gameOver;
            game.handContributions.clear();
            game.handContributions.putAll(handContributions);
            game.lastSettlementResult = lastSettlementResult;
            game.lastFullRaiseByStreet.clear();
            game.lastFullRaiseByStreet.putAll(lastFullRaiseByStreet);
            game.lastActedWagerByPlayer.clear();
            game.lastActedWagerByPlayer.putAll(lastActedWagerByPlayer);
            game.handSeatOrder.clear();
            game.handSeatOrder.addAll(handSeatOrder.isEmpty() ? players.stream().map(Player::getPlayerId).toList() : handSeatOrder);
            game.dealerAnchorPlayerId = dealerAnchorPlayerId;
            game.smallBlindPlayerId = smallBlindPlayerId;
            game.bigBlindPlayerId = bigBlindPlayerId;
            game.terminalCarryOverSettled = terminalCarryOverSettled;
            game.readyCountdownActive = readyCountdownActive;
            game.readyCountdownDeadlineEpochMs = readyCountdownDeadlineEpochMs;
            game.everyoneHasHadInitialTurn = everyoneHasHadInitialTurn;
            game.actedPlayersInRound.clear();
            game.actedPlayersInRound.addAll(actedPlayersInRound);
            game.scheduledTaskDeadlines.clear();
            game.scheduledTaskDeadlines.putAll(scheduledTaskDeadlines);
            return game;
        }
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
        lastFullRaiseByStreet.clear();
        lastActedWagerByPlayer.clear();
        lastSettlementResult = null;
        terminalCarryOverSettled = false;
        readyCountdownActive = false;
        readyCountdownDeadlineEpochMs = null;

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
            gameOver = true;
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
        // floorMod handles negative indices correctly but this isn't necessary for my
        // code as I never subtract from the blind positions
        int tableSize = activePlayers.size();
        smallBlindPosition = Math.floorMod(smallBlindPosition, tableSize);
        bigBlindPosition = Math.floorMod(bigBlindPosition, tableSize);
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

        PlayerDecision actualDecision = decision;
        String conversionMessage = null;

        if (player == null || player.getHasFolded() || player.getIsOut() || player.getIsAllIn()) {
            throw new UnauthorisedActionException("Player cannot act in the current hand", "ACTION_NOT_AVAILABLE", "Player is not eligible to act");
        }

        int callDifference = Math.max(0, currentHighestBet - player.getCurrentBet());
        boolean aggressionContestable = hasOtherActionableOpponent(player);
        if (actualDecision.action() == PlayerAction.CALL && callDifference == 0) {
            throw new UnauthorisedActionException("There is no call amount owed", "CALL_NOT_AVAILABLE", "Call amount: 0");
        }
        if (actualDecision.action() == PlayerAction.RAISE && currentHighestBet == 0) {
            throw new UnauthorisedActionException("There is no active wager to raise", "RAISE_NOT_AVAILABLE", "Use BET when no wager is active");
        }
        if (!aggressionContestable && (actualDecision.action() == PlayerAction.BET
                || actualDecision.action() == PlayerAction.RAISE
                || (actualDecision.action() == PlayerAction.ALL_IN
                && player.getCurrentBet() + player.getChips() > currentHighestBet))) {
            throw new UnauthorisedActionException("No other player can contest an additional wager",
                    actualDecision.action() == PlayerAction.BET ? "BET_NOT_AVAILABLE" : "RAISE_NOT_AVAILABLE",
                    callDifference > 0 ? "Call or fold" : "Check or fold");
        }
        if ((actualDecision.action() == PlayerAction.RAISE || actualDecision.action() == PlayerAction.ALL_IN)
                && !isRaiseOpenTo(player)
                && player.getCurrentBet() + (actualDecision.action() == PlayerAction.ALL_IN ? player.getChips() : actualDecision.amount()) > currentHighestBet) {
            throw new UnauthorisedActionException("Betting has not reopened for this player", "RAISE_NOT_REOPENED", "Call or fold");
        }

        // Validate raise amounts (only if still a raise after conversion)
        if (actualDecision.action() == PlayerAction.RAISE) {
            if (actualDecision.amount() == 0) {
                throw new BadRequestException("Raise amount must be greater than 0", "RAISE_AMOUNT_REQUIRED", "Raise must be positive; yours: 0");
            }

            if (actualDecision.amount() > player.getChips()) {
                throw new BadRequestException("Raise amount cannot exceed available chips", "INSUFFICIENT_CHIPS", "Available: " + player.getChips() + "; yours: " + actualDecision.amount());
            }

            int totalBetAfterRaise = player.getCurrentBet() + actualDecision.amount();
            if (totalBetAfterRaise <= currentHighestBet) {
                int minimumRaise = callDifference + getLastFullRaise();
                logger.warn("[Game] Invalid raise: player {} tried to raise to {}, current highest bet is {}",
                        player.getName(), totalBetAfterRaise, currentHighestBet);
                throw new UnauthorisedActionException(
                        "Raise amount must result in a bet higher than current highest bet of " + currentHighestBet +
                                ". Your current bet is " + player.getCurrentBet() +
                                ", so the minimum incremental raise is " + minimumRaise,
                        "MIN_RAISE", "Min raise: " + minimumRaise + "; yours: " + actualDecision.amount());
            }

            int raiseIncrement = totalBetAfterRaise - currentHighestBet;
            int minimumRaise = callDifference + getLastFullRaise();
            if (raiseIncrement < getLastFullRaise() && actualDecision.amount() < player.getChips()) {
                throw new BadRequestException(
                        "Raise increment must be at least " + getLastFullRaise() +
                        ". You tried to raise by " + raiseIncrement,
                        "MIN_RAISE", "Min raise: " + minimumRaise
                                + "; yours: " + actualDecision.amount());
            }
        }

        if (actualDecision.action() == PlayerAction.BET) {
            if (currentHighestBet > 0) {
                throw new UnauthorisedActionException(
                        "Cannot bet when there is an active bet. You must call, raise, or fold.",
                        "BET_NOT_AVAILABLE", "Active bet: " + currentHighestBet + "; call or raise");
            }

            if (actualDecision.amount() == 0) {
                throw new BadRequestException("Bet amount must be greater than 0", "BET_AMOUNT_REQUIRED", "Bet must be positive; yours: 0");
            }

            if (actualDecision.amount() > player.getChips()) {
                throw new BadRequestException("Bet amount cannot exceed available chips", "INSUFFICIENT_CHIPS", "Available: " + player.getChips() + "; yours: " + actualDecision.amount());
            }

            if (actualDecision.amount() < bigBlind && actualDecision.amount() < player.getChips()) {
                throw new BadRequestException("Bet amount must be at least the big blind of " + bigBlind,
                        "MIN_BET", "Min bet: " + bigBlind + "; yours: " + actualDecision.amount());
            }
        }

        if (actualDecision.action() == PlayerAction.CHECK) {
            if (currentHighestBet > player.getCurrentBet()) {
                throw new UnauthorisedActionException(
                        "Cannot check when there is an active bet. You must call, raise, or fold.",
                        "CHECK_NOT_AVAILABLE", "To call: " + (currentHighestBet - player.getCurrentBet()));
            }
        }

        if (actualDecision.action() == PlayerAction.CALL) {
            int requiredCall = Math.max(0, currentHighestBet - player.getCurrentBet());
            if (requiredCall > player.getChips()) {
                logger.info("Player {} attempted CALL for {} with only {} chips. Converting to ALL_IN",
                        player.getName(), requiredCall, player.getChips());
                actualDecision = new PlayerDecision(PlayerAction.ALL_IN, 0, decision.playerId());
                conversionMessage = "Your call was converted to all-in because the call amount exceeded your available chips.";
            }
        }

        int potBeforeDecision = this.pot;
        int wagerBeforeDecision = currentHighestBet;

        switch (actualDecision.action()) {
            case FOLD, CHECK -> player.doAction(actualDecision.action(), 0, this.pot);
            case CALL, BET, RAISE -> {
                int amount = calculateActualAmount(player, actualDecision);
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
        long actionable = activePlayers.stream().filter(p -> !p.getHasFolded() && !p.getIsOut()
                && !p.getIsAllIn() && p.getChips() > 0).count();
        if (actionable <= 1) {
            currentHighestBet = activePlayers.stream().filter(p -> !p.getHasFolded() && !p.getIsOut())
                    .mapToInt(Player::getCurrentBet).max().orElse(0);
        }
        return reconcileProgressionAfterMutation(null, false);
    }

    /** Resolves whether forced blinds leave an actor or require an automatic runout. */
    public TurnOutcome reconcileAfterForcedBlinds() {
        if (activePlayers.isEmpty()) return new TurnOutcome(TurnOutcome.TurnOutcomeType.NEXT_PLAYER, null, null, 0);
        if (isHandOver()) return reconcileProgressionAfterMutation(null, false);
        if (shouldAutoRunout()) return new TurnOutcome(TurnOutcome.TurnOutcomeType.AUTO_ADVANCING, null, null, 0);
        Player actor = getCurrentPlayer();
        if (actor.getHasFolded() || actor.getIsOut() || actor.getIsAllIn()) nextPlayer();
        return new TurnOutcome(TurnOutcome.TurnOutcomeType.NEXT_PLAYER, null, null, 0);
    }

    private TurnOutcome reconcileProgressionAfterMutation(String conversionMessage, boolean selectNextActor) {
        List<Player> actionablePlayers = activePlayers.stream()
                .filter(p -> !p.getHasFolded() && !p.getIsOut() && !p.getIsAllIn())
                .toList();
        if (!actionablePlayers.isEmpty()
                && actionablePlayers.stream().allMatch(p -> actedPlayersInRound.contains(p.getPlayerId()))
                && !isBettingRoundComplete()) {
            everyoneHasHadInitialTurn = true;
        }

        if (isHandOver()) {
            int potBeforeDistribution = getPot();
            List<Player> winners = conductShowdown();
            int winningsPerPlayer = winners.isEmpty() ? 0 : potBeforeDistribution / winners.size();
            return new TurnOutcome(TurnOutcome.TurnOutcomeType.SHOWDOWN, conversionMessage, winners, winningsPerPlayer);
        }

        boolean bettingRoundComplete = isBettingRoundComplete();
        if (bettingRoundComplete && currentPhase == GamePhase.RIVER) {
            int potBeforeDistribution = getPot();
            List<Player> winners = conductShowdown();
            int winningsPerPlayer = winners.isEmpty() ? 0 : potBeforeDistribution / winners.size();
            return new TurnOutcome(TurnOutcome.TurnOutcomeType.SHOWDOWN, conversionMessage, winners, winningsPerPlayer);
        }

        if (shouldAutoRunout()) {
            return new TurnOutcome(TurnOutcome.TurnOutcomeType.AUTO_ADVANCING, conversionMessage, null, 0);
        }

        if (bettingRoundComplete) {
            actedPlayersInRound.clear();
            advancePhase();
            return new TurnOutcome(TurnOutcome.TurnOutcomeType.PHASE_ADVANCED, conversionMessage, null, 0);
        }

        if (selectNextActor) nextPlayer();
        return new TurnOutcome(TurnOutcome.TurnOutcomeType.NEXT_PLAYER, conversionMessage, null, 0);
    }

    /**
     * Calculates the actual chip amount required for a player's action.
     *
     * @param player   the player making the action
     * @param decision the player's decision containing the action and amount
     * @return the calculated chip amount needed for the action
     */
    private int calculateActualAmount(Player player, PlayerDecision decision) {
        return switch (decision.action()) {
            case CALL -> currentHighestBet - player.getCurrentBet();
            case BET, RAISE -> decision.amount();
            default -> 0;
        };
    }

    private boolean hasActionablePlayerOwingCall() {
        return activePlayers.stream().anyMatch(p -> !p.getHasFolded() && !p.getIsOut() && !p.getIsAllIn()
                && p.getCurrentBet() < currentHighestBet);
    }

    private boolean hasOtherActionableOpponent(Player player) {
        return activePlayers.stream().anyMatch(p -> p != player && !p.getHasFolded() && !p.getIsOut()
                && !p.getIsAllIn() && p.getChips() > 0);
    }

    public int getLastFullRaise() {
        return lastFullRaiseByStreet.getOrDefault(currentPhase.name(), bigBlind);
    }

    public int getCallDifference(Player player) {
        return Math.max(0, currentHighestBet - player.getCurrentBet());
    }

    public int getMinimumRaiseAmount(Player player) {
        return getCallDifference(player) + getLastFullRaise();
    }

    /** True when no remaining actionable player owes chips before a forced runout. */
    public boolean shouldAutoRunout() {
        long actionable = activePlayers.stream().filter(p -> !p.getHasFolded() && !p.getIsOut() && !p.getIsAllIn()).count();
        boolean owedCall = activePlayers.stream().anyMatch(p -> !p.getHasFolded() && !p.getIsOut()
                && !p.getIsAllIn() && p.getCurrentBet() < currentHighestBet);
        return actionable <= 1 && !owedCall;
    }

    public boolean isRaiseOpenTo(Player player) {
        Integer actedAt = lastActedWagerByPlayer.get(player.getPlayerId());
        return actedAt == null || currentHighestBet - actedAt >= getLastFullRaise();
    }

    public List<PlayerAction> getLegalActionsFor(Player player) {
        if (player == null || !players.contains(player) || player.getHasFolded() || player.getIsOut() || player.getIsAllIn()
                || currentPhase == GamePhase.SHOWDOWN || getCurrentPlayer() == null
                || !player.getPlayerId().equals(getCurrentPlayer().getPlayerId())) return List.of();
        List<PlayerAction> actions = new ArrayList<>(List.of(PlayerAction.FOLD));
        int call = getCallDifference(player);
        actions.add(call == 0 ? PlayerAction.CHECK : PlayerAction.CALL);
        boolean aggressionContestable = hasOtherActionableOpponent(player);
        if (aggressionContestable && isRaiseOpenTo(player) && player.getChips() > call) {
            actions.add(currentHighestBet == 0 ? PlayerAction.BET : PlayerAction.RAISE);
            actions.add(PlayerAction.ALL_IN);
        } else if (player.getChips() > 0 && player.getChips() <= call) {
            actions.add(PlayerAction.ALL_IN);
        }
        return List.copyOf(actions);
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
        if (player == null || amount <= 0) {
            return;
        }

        handContributions.merge(player.getPlayerId(), amount, Integer::sum);

        logger.debug("Tracked contribution in game {} | player={} | added={} | totalContribution={} | contributions={}",
                gameId,
                player.getName(),
                amount,
                handContributions.getOrDefault(player.getPlayerId(), 0),
                handContributions);
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
        boolean hasPlayerWhoNeedsToAct = activePlayers.stream()
                .anyMatch(p -> p.getCurrentBet() < currentHighestBet &&
                        !p.getHasFolded() && !p.getIsOut() &&
                        !p.getIsAllIn());

        long playersAbleToAct = activePlayers.stream()
                .filter(p -> !p.getHasFolded() && !p.getIsOut() && !p.getIsAllIn())
                .count();
        if (playersAbleToAct <= 1 && !hasPlayerWhoNeedsToAct) {
            return true;
        }

        if (!everyoneHasHadInitialTurn) {
            logger.debug("Betting round not complete: Not everyone has had initial turn");
            return false;
        }

        logger.debug("Checking if betting round complete - Current highest bet: {}, Everyone has had initial turn: {}",
                currentHighestBet, true);

        if (logger.isDebugEnabled()) {
            activePlayers.forEach(p -> logger.debug("Player {}: bet={}, folded={}, all-in={}, needsToAct={}",
                    p.getName(), p.getCurrentBet(), p.getHasFolded(), p.getIsAllIn(),
                    (p.getCurrentBet() < currentHighestBet && !p.getHasFolded() && !p.getIsAllIn())));
        }

        boolean isComplete = !hasPlayerWhoNeedsToAct;
        logger.debug("Betting round result: {} (hasPlayerWhoNeedsToAct={})",
                (isComplete ? "Complete" : "Not complete"), hasPlayerWhoNeedsToAct);

        return isComplete;
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
        for (Player player : players) {
            HandEvaluationResult result = handEvaluator.getBestHand(communityCards, player.getHoleCards());
            player.setBestHand(result.bestHand());
            player.setHandRank(result.handRank());
        }
    }

    /**
     * Resolves and distributes all side pots at showdown.
     *
     * @return ordered set of unique players who won at least one allocation
     */
    private List<Player> distributeSidePotsAtShowdown(List<Player> showdownPlayers) {
        logger.info("Distributing side pots for game {}", gameId);

        // Build contested allocations and identify uncalled refunds.
        PotComputationResult computation = computePotAllocationsAndUncalled(showdownPlayers);
        List<PotAllocation> allocations = computation.allocations();
        if (logger.isDebugEnabled()) {
            logger.debug("Side-pot allocations for game {}: {}", gameId, allocationSummary(allocations));
        }

        Map<String, Integer> refunds = new HashMap<>();
        // Refund single-owner (uncalled) layers before contested awards.
        for (Map.Entry<String, Integer> entry : computation.uncalledByPlayer().entrySet()) {
            Player player = players.stream()
                    .filter(p -> p.getPlayerId().equals(entry.getKey()))
                    .findFirst()
                    .orElse(null);

            if (player == null || player.getHasFolded() || player.getIsOut() || entry.getValue() <= 0) {
                continue;
            }

            player.addChips(entry.getValue());
            refunds.merge(player.getPlayerId(), entry.getValue(), Integer::sum);
            logger.info("Refunded uncalled chips for game {} | player={} | amount={}",
                    gameId,
                    player.getName(),
                    entry.getValue());
        }

        Set<Player> uniqueWinners = new LinkedHashSet<>();
        int remainder = 0;
        Map<String, Integer> awards = new HashMap<>();

        // Resolve each contested allocation, determine winners, and split chips.
        for (int i = 0; i < allocations.size(); i++) {
            PotAllocation allocation = allocations.get(i);
            List<Player> potWinners = determineWinners(allocation.eligiblePlayers());

            if (potWinners.isEmpty()) {
                logger.warn("Pot {} has no winners. Keeping {} chips in pot.", i, allocation.amount());
                remainder += allocation.amount();
                continue;
            }

            int share = allocation.amount() / potWinners.size();
            // Keep modulo chips as table remainder.
            int potRemainder = allocation.amount() % potWinners.size();
            remainder += potRemainder;

            logger.info(
                    "Resolved side pot {} for game {} | amount={} | eligible={} | winners={} | share={} | remainder={}",
                    i,
                    gameId,
                    allocation.amount(),
                    playerNames(allocation.eligiblePlayers()),
                    playerNames(potWinners),
                    share,
                    potRemainder);

            for (Player winner : potWinners) {
                winner.addChips(share);
                awards.merge(winner.getPlayerId(), share, Integer::sum);
            }

            uniqueWinners.addAll(potWinners);
        }

        // Keep integer remainders in the table pot for the next hand.
        pot = remainder;
        lastSettlementResult = new HandSettlementResult(awards, refunds,
                uniqueWinners.stream().map(Player::getPlayerId).collect(java.util.stream.Collectors.toSet()),
                remainder, true);
        logger.info(
                "Side-pot distribution complete for game {} | uniqueWinners={} | refundedUncalled={} | potRemainder={}",
                gameId,
                playerNames(new ArrayList<>(uniqueWinners)),
                refunds.values().stream().mapToInt(Integer::intValue).sum(),
                pot);

        return new ArrayList<>(uniqueWinners);
    }

    /**
     * Decomposes the current pot into contested allocation layers and uncalled
     * layers.
     *
     * @return a full breakdown used for payout distribution and UI pot rendering
     */
    private PotComputationResult computePotAllocationsAndUncalled(List<Player> eligibleHandContenders) {
        List<PotAllocation> allocations = new ArrayList<>();
        Map<String, Integer> uncalledByPlayer = new HashMap<>();

        logger.debug("Building pot allocations for game {} | pot={} | contributions={}",
                gameId,
                pot,
                handContributions);

        // Only considering players who contributed chips to the pot.
        Map<String, Integer> contributions = handContributions.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .collect(HashMap::new,
                        (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                        HashMap::putAll);

        if (!contributions.isEmpty()) {
            // Side-pot boundaries are the distinct total contribution amounts.
            List<Integer> contributionLevels = contributions.values().stream()
                    .distinct()
                    .sorted()
                    .toList();

            logger.debug("Contribution levels for game {}: {}", gameId, contributionLevels);

            int previousLevel = 0;
            for (int level : contributionLevels) {
                // Processing each incremental layer between adjacent contribution levels.
                int layer = level - previousLevel;
                previousLevel = level;

                if (layer <= 0) {
                    continue;
                }

                // The contribution ledger is authoritative for layer size, including
                // contributors who folded or departed after committing chips.
                List<String> contributorIds = contributions.entrySet().stream()
                        .filter(entry -> entry.getValue() >= level)
                        .map(Map.Entry::getKey)
                        .toList();
                List<Player> participants = players.stream()
                        .filter(player -> contributorIds.contains(player.getPlayerId()))
                        .toList();

                int amount = layer * contributorIds.size();
                if (amount <= 0) {
                    continue;
                }

                if (contributorIds.size() == 1) {
                    // Refund only genuinely unmatched excess to a retained eligible owner.
                    String ownerId = contributorIds.getFirst();
                    Player owner = players.stream().filter(p -> p.getPlayerId().equals(ownerId)
                                    && !p.getHasFolded() && !p.getIsOut()).findFirst().orElse(null);
                    if (owner != null) {
                        uncalledByPlayer.merge(ownerId, amount, Integer::sum);
                    } else {
                        routeDeadLayerToNearestEligible(allocations, amount, eligibleHandContenders);
                    }
                    logger.debug("Detected uncalled layer for game {} | level={} | owner={} | amount={}",
                            gameId,
                            level,
                            ownerId,
                            amount);
                    continue;
                }

                List<Player> eligiblePlayers = participants.stream()
                        .filter(player -> !player.getHasFolded() && !player.getIsOut())
                        .toList();

                if (!eligiblePlayers.isEmpty()) {
                    // Multi-participant layers become contested allocations.
                    appendAllocationCoalescingSameEligibility(allocations, amount, eligiblePlayers);
                    logger.debug(
                            "Allocation layer for game {} | level={} | layer={} | participants={} | eligible={} | amount={}",
                            gameId,
                            level,
                            layer,
                            playerNames(participants),
                            playerNames(eligiblePlayers),
                            amount);
                } else {
                    // Dead upper layers follow the nearest lower allocation that
                    // still has an eligible recipient.
                    routeDeadLayerToNearestEligible(allocations, amount, eligibleHandContenders);
                }
            }
        } else {
            logger.debug("No tracked contributions for game {} while building allocations", gameId);
        }

        int uncalledTotal = uncalledByPlayer.values().stream().mapToInt(Integer::intValue).sum();
        int allocatedAmount = allocations.stream().mapToInt(PotAllocation::amount).sum();
        int accounted = allocatedAmount + uncalledTotal;
        int ledgerTotal = contributions.values().stream().mapToInt(Integer::intValue).sum();
        if (ledgerTotal + preHandCarryOver != pot) {
            throw new IllegalStateException("Contribution ledger and carry-over do not match the committed pot for game " + gameId);
        }
        if (preHandCarryOver > 0) {
            List<Player> eligible = activePlayers.stream()
                    .filter(p -> !p.getHasFolded() && !p.getIsOut())
                    .toList();
            if (!eligible.isEmpty()) {
                if (allocations.isEmpty()) allocations.add(new PotAllocation(preHandCarryOver, eligible));
                else {
                    PotAllocation main = allocations.getFirst();
                    allocations.set(0, new PotAllocation(main.amount() + preHandCarryOver, main.eligiblePlayers()));
                }
                accounted += preHandCarryOver;
            }
        }
        int unallocatedAmount = pot - accounted;

        logger.debug("Allocation totals for game {} | allocated={} | uncalled={} | unallocated={} | pot={}",
                gameId,
                allocatedAmount,
                uncalledTotal,
                unallocatedAmount,
                pot);

        // Unexplained or pre-hand carry-over money remains explicit in the pot.

        if (logger.isDebugEnabled()) {
            logger.debug("Final allocations for game {}: {}", gameId, allocationSummary(allocations));
            logger.debug("Uncalled map for game {}: {}", gameId, uncalledByPlayer);
        }

        return new PotComputationResult(allocations, uncalledByPlayer, uncalledTotal);
    }

    private static void routeDeadLayerToNearestEligible(List<PotAllocation> allocations, int amount,
            List<Player> eligibleHandContenders) {
        for (int i = allocations.size() - 1; i >= 0; i--) {
            PotAllocation lower = allocations.get(i);
            if (!lower.eligiblePlayers().isEmpty()) {
                allocations.set(i, new PotAllocation(lower.amount() + amount, lower.eligiblePlayers()));
                return;
            }
        }
        if (!eligibleHandContenders.isEmpty()) {
            allocations.add(0, new PotAllocation(amount, List.copyOf(eligibleHandContenders)));
        }
    }

    private static void appendAllocationCoalescingSameEligibility(List<PotAllocation> allocations, int amount,
            List<Player> eligiblePlayers) {
        if (!allocations.isEmpty()) {
            PotAllocation previous = allocations.getLast();
            Set<String> previousIds = previous.eligiblePlayers().stream().map(Player::getPlayerId).collect(java.util.stream.Collectors.toSet());
            Set<String> currentIds = eligiblePlayers.stream().map(Player::getPlayerId).collect(java.util.stream.Collectors.toSet());
            if (previousIds.equals(currentIds)) {
                allocations.set(allocations.size() - 1,
                        new PotAllocation(previous.amount() + amount, previous.eligiblePlayers()));
                return;
            }
        }
        allocations.add(new PotAllocation(amount, List.copyOf(eligiblePlayers)));
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
     * Represents a single pot layer (main pot or a side pot) and the players
     * who are eligible to win that layer.
     */
    private record PotAllocation(int amount, List<Player> eligiblePlayers) {
    }

    /**
     * Result container for pot decomposition:
     * - allocations: contested pots to award at showdown
     * - uncalledByPlayer: chips that must be refunded (single-participant layers)
     * - uncalledTotal: sum of all uncalled chips
     */
    private record PotComputationResult(List<PotAllocation> allocations,
            Map<String, Integer> uncalledByPlayer,
            int uncalledTotal) {
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
        if (pot <= 0) {
            logger.debug("Pot breakdown requested for game {} with empty pot", gameId);
            return List.of();
        }

        if (lastSettlementResult != null && lastSettlementResult.settled()) {
            return List.of(pot);
        }

        boolean hasAllIn = activePlayers.stream()
                .anyMatch(player -> player.getIsAllIn() && !player.getHasFolded());

        // Live money above an all-in cap remains pending until the owing players
        // respond. Do not present it as a finalized refund or expose temporary
        // side-pot boundaries while the street is still open.
        boolean pendingResponse = activePlayers.stream()
                .anyMatch(player -> !player.getHasFolded() && !player.getIsOut() && !player.getIsAllIn()
                        && currentHighestBet > player.getCurrentBet());
        if (pendingResponse) {
            int maxAllInCap = handContributions.entrySet().stream()
                    .filter(entry -> activePlayers.stream().anyMatch(player -> player.getPlayerId().equals(entry.getKey())
                            && player.getIsAllIn() && !player.getHasFolded() && !player.getIsOut()))
                    .mapToInt(Map.Entry::getValue).max().orElse(0);
            boolean owingBelowCap = activePlayers.stream().anyMatch(player -> !player.getHasFolded()
                    && !player.getIsOut() && !player.getIsAllIn() && currentHighestBet > player.getCurrentBet()
                    && handContributions.getOrDefault(player.getPlayerId(), 0) < maxAllInCap);
            if (owingBelowCap || maxAllInCap == 0) return List.of(pot);

            List<Integer> caps = handContributions.entrySet().stream()
                    .filter(entry -> activePlayers.stream().anyMatch(player -> player.getPlayerId().equals(entry.getKey())
                            && player.getIsAllIn() && !player.getHasFolded() && !player.getIsOut()))
                    .map(Map.Entry::getValue).distinct().sorted().toList();
            List<Integer> liveLayers = new ArrayList<>();
            int previousCap = 0;
            for (int cap : caps) {
                int layerWidth = cap - previousCap;
                int lowerBoundary = previousCap;
                int layer = handContributions.values().stream()
                        .mapToInt(value -> Math.max(0, Math.min(value - lowerBoundary, layerWidth)))
                        .sum();
                if (liveLayers.isEmpty()) layer = Math.addExact(layer, preHandCarryOver);
                if (layer > 0) liveLayers.add(layer);
                previousCap = cap;
            }
            int projected = liveLayers.stream().mapToInt(Integer::intValue).sum();
            if (pot > projected) liveLayers.add(pot - projected);
            return List.copyOf(liveLayers);
        }

        if (!hasAllIn) {
            logger.debug("Pot breakdown for game {} without all-ins: [{}]", gameId, pot);
            return List.of(pot);
        }

        PotComputationResult computation = computePotAllocationsAndUncalled(activePlayers.stream()
                .filter(player -> !player.getHasFolded() && !player.getIsOut()).toList());
        List<Integer> breakdown = computation.allocations().stream()
                .map(PotAllocation::amount)
                .toList();

        logger.debug("Pot breakdown for game {} with all-ins: {} | uncalled={}",
                gameId,
                breakdown,
                computation.uncalledTotal());

        return breakdown;
    }

    /**
     * Returns the total uncalled amount currently detected in the pot
     * decomposition.
     *
     * @return total chips that should be refunded to players as uncalled
     */
    public int getUncalledAmount() {
        if (pot <= 0) {
            return 0;
        }

        if (lastSettlementResult != null && lastSettlementResult.settled()) {
            return 0;
        }

        boolean hasAllIn = activePlayers.stream()
                .anyMatch(player -> player.getIsAllIn() && !player.getHasFolded());

        boolean pendingResponse = activePlayers.stream()
                .anyMatch(player -> !player.getHasFolded() && !player.getIsOut() && !player.getIsAllIn()
                        && currentHighestBet > player.getCurrentBet());
        if (pendingResponse) return 0;

        if (!hasAllIn) {
            return 0;
        }

        return computePotAllocationsAndUncalled(activePlayers.stream()
                .filter(player -> !player.getHasFolded() && !player.getIsOut()).toList()).uncalledTotal();
    }

    /**
     * Helper for concise player-name logging in side-pot diagnostics.
     */
    private List<String> playerNames(List<Player> players) {
        return players.stream().map(Player::getName).toList();
    }

    /**
     * Helper for concise allocation logging in side-pot diagnostics.
     */
    private List<String> allocationSummary(List<PotAllocation> allocations) {
        List<String> summary = new ArrayList<>();
        for (int i = 0; i < allocations.size(); i++) {
            PotAllocation allocation = allocations.get(i);
            summary.add("pot[" + i + "] amount=" + allocation.amount() + " eligible="
                    + playerNames(allocation.eligiblePlayers()));
        }
        return summary;
    }

    /**
     * Determines the winning player(s) from the given list by comparing hand ranks.
     * Handles ties by checking for multiple players with the same best hand.
     *
     * @param players list of players to compare
     * @return list of winning players (may contain multiple players in case of a
     *         tie)
     */
    private List<Player> determineWinners(List<Player> players) {
        // Create a mutable copy of the list for sorting
        List<Player> sortablePlayers = new ArrayList<>(players);
        sortablePlayers.sort(Comparator.comparing(Player::getHandRank).reversed());

        if (sortablePlayers.isEmpty()) {
            return new ArrayList<>();
        }

        Player bestPlayer = sortablePlayers.getFirst();
        List<Player> winners = new ArrayList<>();
        winners.add(bestPlayer);

        for (int i = 1; i < sortablePlayers.size(); i++) {
            Player currentPlayer = sortablePlayers.get(i);
            if (currentPlayer.getHandRank() == bestPlayer.getHandRank()) {
                boolean challengerBeatsBest = handEvaluator.isBetterHandOfSameRank(
                        currentPlayer.getBestHand(),
                        bestPlayer.getBestHand(),
                        bestPlayer.getHandRank());

                boolean bestBeatsChallenger = handEvaluator.isBetterHandOfSameRank(
                        bestPlayer.getBestHand(),
                        currentPlayer.getBestHand(),
                        bestPlayer.getHandRank());

                if (challengerBeatsBest && !bestBeatsChallenger) {
                    bestPlayer = currentPlayer;
                    winners.clear();
                    winners.add(currentPlayer);
                } else if (!challengerBeatsBest && !bestBeatsChallenger) {
                    winners.add(currentPlayer);
                }
            } else {
                break;
            }
        }
        return winners;
    }

    /**
     * Distributes the pot evenly among the winning players.
     * Any remainder from integer division stays in the pot for the next hand.
     *
     * @param winners list of players who won the hand
     */
    private void distributePot(List<Player> winners) {
        logger.info("Distributing pot for game {}", gameId);
        if (winners.isEmpty()) {
            logger.warn("No winners to distribute pot to!");
            return;
        }

        logger.info("Pot to distribute: {}, Number of winners: {}", pot, winners.size());

        int potShare = pot / winners.size();
        logger.info("Each winner gets: {} chips", potShare);

        for (Player winner : winners) {
            int chipsBefore = winner.getChips();
            winner.addChips(potShare);
            logger.debug("Player {}: {} -> {} chips", winner.getName(), chipsBefore, winner.getChips());
        }

        pot = pot % winners.size(); // Any remainder stays for the next hand

        logger.debug("Pot remainder for next hand: {}", pot);
        logger.info("Pot distribution complete");
    }

    /**
     * Advances dealer, small blind, big blind, and current player positions
     * for the next hand. Rotates positions clockwise around the table.
     */
    public void advancePositions() {
        if (activePlayers.isEmpty()) return;
        int anchorIndex = handSeatOrder.indexOf(dealerAnchorPlayerId);
        for (int offset = 1; offset <= handSeatOrder.size(); offset++) {
            String candidateId = handSeatOrder.get(Math.floorMod(anchorIndex + offset, handSeatOrder.size()));
            int activeIndex = indexOfActivePlayer(candidateId);
            if (activeIndex >= 0 && !activePlayers.get(activeIndex).getIsOut()) {
                dealerPosition = activeIndex;
                dealerAnchorPlayerId = candidateId;
                break;
            }
        }
        updatePositionsForCurrentTable();
        smallBlindPlayerId = null;
        bigBlindPlayerId = null;
    }

    private int indexOfActivePlayer(String playerId) {
        for (int i = 0; i < activePlayers.size(); i++) if (activePlayers.get(i).getPlayerId().equals(playerId)) return i;
        return -1;
    }

    /**
     * Recomputes blind and first-action positions after table-size changes. Heads-up
     * poker is handled separately because the dealer is also the small blind and acts
     * first pre-flop.
     */
    private void updatePositionsForCurrentTable() {
        int tableSize = activePlayers.size();

        if (tableSize == 2) {
            // Heads-up: dealer posts small blind and acts first pre-flop.
            smallBlindPosition = dealerPosition;
            bigBlindPosition = (dealerPosition + 1) % tableSize;
            currentPlayerPosition = dealerPosition;
            return;
        }

        smallBlindPosition = (dealerPosition + 1) % tableSize;
        bigBlindPosition = (smallBlindPosition + 1) % tableSize;
        currentPlayerPosition = (bigBlindPosition + 1) % tableSize;
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
        readyCountdownActive = true;
        readyCountdownDeadlineEpochMs = deadlineEpochMs;
        for (Player player : players) {
            player.setReadyForNextHand(false);
        }
    }

    /**
     * Closes the ready-confirmation gate.
     */
    public void closeReadyCountdown() {
        readyCountdownActive = false;
        readyCountdownDeadlineEpochMs = null;
    }

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
        List<Player> eligiblePlayers = getReadyEligiblePlayers();
        if (eligiblePlayers.isEmpty()) {
            return false;
        }
        return eligiblePlayers.stream().allMatch(Player::getIsReadyForNextHand);
    }

    /**
     * Marks all currently eligible players as ready.
     */
    public void forceReadyForEligiblePlayers() {
        for (Player player : getReadyEligiblePlayers()) {
            player.setReadyForNextHand(true);
        }
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
     * Excludes disconnected, eliminated, and zero-chip players from the ready gate so an absent
     * or knocked-out player cannot stall the next hand indefinitely.
     *
     * @return players whose readiness is required
     */
    private List<Player> getReadyEligiblePlayers() {
        return players.stream()
                .filter(player -> !player.getIsOut())
                .filter(player -> !player.getIsDisconnected())
                .filter(player -> player.getChips() > 0)
                .toList();
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

        Player previousCurrent = null;
        if (!activePlayers.isEmpty()) {
            normalizeCurrentPlayerPosition();
            previousCurrent = activePlayers.get(currentPlayerPosition);
        }

        players.remove(playerToRemove);
        activePlayers.remove(playerToRemove);
        actedPlayersInRound.remove(playerToRemove.getPlayerId());
        lastActedWagerByPlayer.remove(playerToRemove.getPlayerId());

        // Keep position indexes aligned with retained identities where possible.
        // The anchor itself remains even if the dealer has left this hand.
        int anchoredDealerIndex = indexOfActivePlayer(dealerAnchorPlayerId);
        if (anchoredDealerIndex >= 0) dealerPosition = anchoredDealerIndex;
        if (smallBlindPlayerId != null) smallBlindPosition = indexOfActivePlayer(smallBlindPlayerId);
        if (bigBlindPlayerId != null) bigBlindPosition = indexOfActivePlayer(bigBlindPlayerId);

        if (activePlayers.isEmpty()) {
            currentPlayerPosition = 0;
            return;
        }

        String sbId = smallBlindPlayerId;
        String bbId = bigBlindPlayerId;

        // Preserve the same player turn when someone else leaves and that player is
        // still present.
        if (previousCurrent != null && !previousCurrent.equals(playerToRemove)) {
            int preservedIndex = activePlayers.indexOf(previousCurrent);
            if (preservedIndex >= 0) {
                currentPlayerPosition = preservedIndex;
                return;
            }
        }

        if (sbId != null) smallBlindPosition = Math.max(0, indexOfActivePlayer(sbId));
        if (bbId != null) bigBlindPosition = Math.max(0, indexOfActivePlayer(bbId));

        // If current player left, point to the successor seat and skip non-actionable
        // seats.
        normalizeCurrentPlayerPosition();
        Player current = activePlayers.get(currentPlayerPosition);
        if (current.getHasFolded() || current.getIsAllIn()) {
            nextPlayer();
        }
    }

    private int firstActionableSeatAfterDealerAnchor() {
        if (activePlayers.isEmpty()) return 0;
        int anchorIndex = handSeatOrder.indexOf(dealerAnchorPlayerId);
        if (anchorIndex < 0) anchorIndex = Math.max(0, Math.min(dealerPosition, handSeatOrder.size() - 1));
        for (int offset = 1; offset <= handSeatOrder.size(); offset++) {
            String candidateId = handSeatOrder.get(Math.floorMod(anchorIndex + offset, handSeatOrder.size()));
            int activeIndex = indexOfActivePlayer(candidateId);
            if (activeIndex < 0) continue;
            Player candidate = activePlayers.get(activeIndex);
            if (!candidate.getHasFolded() && !candidate.getIsAllIn() && !candidate.getIsOut()) return activeIndex;
        }
        return 0;
    }

    /**
     * Normalizes the current player position to be within the bounds of the active
     * players list.
     */
    private void normalizeCurrentPlayerPosition() {
        if (activePlayers.isEmpty()) {
            currentPlayerPosition = 0;
            return;
        }
        // floorMod handles negative indices correctly but this isn't necessary for my
        // code as I never subtract from currentPlayerPosition
        currentPlayerPosition = Math.floorMod(currentPlayerPosition, activePlayers.size());
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
        if (activePlayers.isEmpty()) {
            currentPlayerPosition = 0;
            return;
        }

        normalizeCurrentPlayerPosition();

        int originalPosition = currentPlayerPosition;
        do {
            currentPlayerPosition = (currentPlayerPosition + 1) % activePlayers.size();
        } while ((activePlayers.get(currentPlayerPosition).getHasFolded() ||
                activePlayers.get(currentPlayerPosition).getIsAllIn()) &&
                currentPlayerPosition != originalPosition);
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
        scheduledTaskDeadlines.put(task, deadlineEpochMs);
    }

    /**
     * Returns the current generation token for a delayed task. Timer callbacks compare
     * it with their expected deadline so stale callbacks become harmless no-ops.
     *
     * @param task delayed work type
     * @return current absolute deadline, or {@code null} when not scheduled
     */
    public Long getScheduledTaskDeadline(ScheduledGameTask task) {
        return scheduledTaskDeadlines.get(task);
    }

    /**
     * Clears durable intent in the same mutation that performs the work, making
     * overdue recovery execution idempotent.
     *
     * @param task completed delayed work type
     */
    public void clearScheduledTask(ScheduledGameTask task) {
        scheduledTaskDeadlines.remove(task);
    }

    /**
     * Copies scheduled intent for state-image capture without exposing the live task
     * map to mutation.
     *
     * @return immutable task-to-deadline map
     */
    public Map<ScheduledGameTask, Long> getScheduledTaskDeadlinesSnapshot() {
        return Map.copyOf(scheduledTaskDeadlines);
    }
}
