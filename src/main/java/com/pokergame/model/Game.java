package com.pokergame.model;

import com.pokergame.dto.internal.PlayerDecision;
import com.pokergame.dto.internal.TurnOutcome;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ReadyCountdownStartOutcome;
import com.pokergame.enums.ReadyCountdownTimeoutOutcome;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.PotCalculator.PotComputationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Aggregate root for a live poker game: owns the deck, players, pot accounting, hand phase,
 * and coordinates seating, betting, readiness, and scheduled-task collaborators.
 */
public class Game {
    private static final Logger logger = LoggerFactory.getLogger(Game.class);

    private final String gameId;
    private final int maxPlayers;
    private final int smallBlind;
    private final int bigBlind;
    private final List<Player> players;
    private final List<Player> activePlayers;
    private final List<Card> communityCards;
    private final Map<String, Integer> handContributions;
    private final TableSeating seating;
    private final BettingRound bettingRound;
    private final ReadyGate readyGate;
    private final GameScheduling scheduling;

    private Deck deck;
    private int pot;
    private int carryOverPot;
    private GamePhase currentPhase;
    private HandSettlementResult lastSettlementResult;
    private long handNumber;
    private boolean gameOver;
    private boolean finalPotSettled;
    private boolean forfeitPending;
    private GameEndResponse gameEndResult;

    /**
     * Creates a new poker game with the specified table settings and seated players.
     *
     * @param gameId        unique identifier for the game
     * @param players       seated players participating in the game (at least 2)
     * @param maxPlayers    table capacity configured on the room (between 2 and 10)
     * @param smallBlind    small blind amount
     * @param bigBlind      big blind amount
     * @throws IllegalStateException if {@code gameId}, {@code maxPlayers}, or {@code players} is invalid
     */
    public Game(
            String gameId,
            List<Player> players,
            int maxPlayers,
            int smallBlind,
            int bigBlind) {
        if (gameId == null || gameId.trim().isEmpty()) {
            throw new IllegalStateException("Game ID cannot be null or empty");
        }
        if (players == null || players.size() < Room.MIN_PLAYERS) {
            throw new IllegalStateException(
                    "At least " + Room.MIN_PLAYERS + " players are required to start a game");
        }
        if (players.stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException("Invalid players list. Please try again.");
        }
        if (maxPlayers < Room.MIN_PLAYERS || maxPlayers > Room.MAX_PLAYERS) {
            throw new IllegalStateException(
                    "Max players must be between " + Room.MIN_PLAYERS + " and " + Room.MAX_PLAYERS + ".");
        }

        this.gameId = gameId;
        this.maxPlayers = maxPlayers;
        this.smallBlind = smallBlind;
        this.bigBlind = bigBlind;
        this.players = new ArrayList<>(players);
        this.activePlayers = new ArrayList<>(players);
        this.deck = new Deck();
        this.communityCards = new ArrayList<>();
        this.pot = 0;
        this.carryOverPot = 0;
        this.currentPhase = GamePhase.PRE_FLOP;
        this.handContributions = new HashMap<>();
        this.lastSettlementResult = null;
        this.handNumber = 0;
        this.gameOver = false;
        this.finalPotSettled = false;
        this.forfeitPending = false;
        this.gameEndResult = null;
        this.seating = new TableSeating(this.activePlayers, this.players);
        this.bettingRound = new BettingRound();
        this.readyGate = new ReadyGate(this.players);
        this.scheduling = new GameScheduling();
    }

    /**
     * Reconstructs a {@link Game} from saved snapshot state supplied by {@link GameRestoreBuilder},
     * clamping {@code maxPlayers} and restoring collaborator state without running live hand setup.
     *
     * @param builder populated snapshot builder
     * @throws IllegalStateException if {@code gameId} or {@code players} are invalid, an active
     *                               player ID is unknown, or the snapshot has no deck
     */
    Game(GameRestoreBuilder builder) {
        if (builder.getGameId() == null || builder.getGameId().trim().isEmpty()) {
            throw new IllegalStateException("Game ID cannot be null or empty");
        }
        List<Player> builderPlayers = builder.getPlayers();
        if (builderPlayers == null || builderPlayers.stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException("Invalid players list. Please try again.");
        }

        this.gameId = builder.getGameId();
        this.players = new ArrayList<>(builderPlayers);
        this.maxPlayers = (builder.getMaxPlayers() >= Room.MIN_PLAYERS && builder.getMaxPlayers() <= Room.MAX_PLAYERS)
                ? builder.getMaxPlayers()
                : Math.clamp(this.players.size(), Room.MIN_PLAYERS, Room.MAX_PLAYERS);

        Map<String, Player> playersById = new HashMap<>();
        this.players.forEach(player -> playersById.put(player.getPlayerId(), player));
        this.activePlayers = new ArrayList<>();
        List<String> activeIds = builder.getActivePlayerIds();
        if (activeIds != null) {
            for (String playerId : activeIds) {
                Player player = playersById.get(playerId);
                if (player == null) {
                    throw new IllegalStateException("Snapshot references an unknown active player");
                }
                this.activePlayers.add(player);
            }
        }

        List<Card> remainingDeck = builder.getRemainingDeck();
        if (remainingDeck == null) {
            throw new IllegalStateException("Saved game " + this.gameId + " has no deck");
        }
        this.deck = Deck.restore(remainingDeck);
        this.communityCards = builder.getCommunityCards() != null
                ? new ArrayList<>(builder.getCommunityCards())
                : new ArrayList<>();
        this.pot = builder.getPot();
        this.carryOverPot = builder.getPreHandCarryOver();
        this.currentPhase = builder.getCurrentPhase();
        this.gameOver = builder.isGameOver();
        this.smallBlind = builder.getSmallBlind();
        this.bigBlind = builder.getBigBlind();
        this.handContributions = builder.getHandContributions() != null
                ? new HashMap<>(builder.getHandContributions())
                : new HashMap<>();
        this.lastSettlementResult = builder.getLastSettlementResult();
        this.finalPotSettled = builder.isTerminalCarryOverSettled();
        this.forfeitPending = builder.isForfeitPending();
        this.handNumber = builder.getHandNumber();
        this.gameEndResult = builder.getRetainedTerminalResult();

        this.seating = new TableSeating(
                builder.getHandSeatOrder(),
                builder.getDealerAnchorPlayerId(),
                builder.getSmallBlindPlayerId(),
                builder.getBigBlindPlayerId(),
                builder.getCurrentPlayerId(),
                this.players);
        this.bettingRound = new BettingRound(
                builder.getCurrentHighestBet(),
                builder.isEveryoneHasHadInitialTurn(),
                builder.getActedPlayersInRound(),
                builder.getLastFullRaise(),
                builder.getLastActedWagerByPlayer());
        this.readyGate = new ReadyGate(
                this.players,
                builder.getReadyGateState(),
                builder.isReadyCountdownActive(),
                builder.getReadyCountdownDeadlineEpochMs());
        this.scheduling = new GameScheduling(
                builder.getScheduledTaskDeadlines(),
                builder.getScheduledTaskGenerations(),
                builder.getStateRevision());
    }

    /**
     * Creates a builder for restoring a {@link Game} instance from a saved snapshot.
     *
     * @return a new {@link GameRestoreBuilder}
     */
    public static GameRestoreBuilder restoreBuilder() {
        return new GameRestoreBuilder();
    }

    /**
     * Eliminates zero-chip players, resets table state if at least two active players remain,
     * and rotates dealer and blind positions for the next hand.
     *
     * @return {@code true} if the game has ended (one or fewer active players remain), {@code false} otherwise
     */
    public boolean resetForNewHand() {
        int previousPot = pot;

        if (logger.isDebugEnabled()) {
            logger.debug("Resetting hand for game {} | carryOverPot={} | previousContributions={}",
                    gameId, previousPot, handContributions);
        }

        for (Player player : players) {
            if (player.getChips() == 0) {
                logger.info("Setting {} as out (0 chips)", player.getName());
                player.setIsOut();
            }
        }
        activePlayers.removeIf(Player::getIsOut);

        if (activePlayers.size() <= 1) {
            logger.info("Game over - only {} active players remaining", activePlayers.size());
            gameOver = true;
            return true;
        }
        if (gameOver) {
            return true;
        }

        // Only reclassify the pot as carry-over after confirming another hand can begin.
        carryOverPot = previousPot;
        deck = new Deck();
        communityCards.clear();
        pot = previousPot;
        currentPhase = GamePhase.PRE_FLOP;
        bettingRound.resetForNewHand();
        handContributions.clear();
        lastSettlementResult = null;
        finalPotSettled = false;
        closeReadyCountdown();

        activePlayers.clear();
        for (Player player : players) {
            player.setReadyForNextHand(false);
            if (!player.getIsOut()) {
                player.resetAttributes();
                activePlayers.add(player);
            }
        }

        gameOver = false;
        seating.advancePositions(activePlayers);
        logger.debug("Hand reset complete for game {} | contributions cleared", gameId);
        return false;
    }

    /**
     * Increments the hand number and resets the state revision for the new hand.
     *
     * @return the newly started hand number
     */
    public long beginHand() {
        this.handNumber = handNumber <= 0 ? 1L : handNumber + 1L;
        scheduling.resetStateRevisionForNewHand();
        return this.handNumber;
    }

    /**
     * Deals two hole cards from the deck to each active player.
     */
    public void dealHoleCards() {
        for (Player player : activePlayers) {
            player.getHoleCards().addAll(deck.dealCards(2));
        }
    }

    /**
     * Deducts the small and big blinds from the assigned blind players, going all-in when a
     * player's stack is smaller than the blind.
     *
     * @throws IllegalStateException if fewer than two active players are seated
     */
    public void postBlinds() {
        if (activePlayers.size() < 2) {
            throw new IllegalStateException(
                    String.format("Trying to post blinds for a game %s which has less than 2 players", gameId));
        }
        seating.assignBlindsForHand(activePlayers);
        Player smallBlindPlayer = findActivePlayerById(seating.getSmallBlindPlayerId(activePlayers));
        Player bigBlindPlayer = findActivePlayerById(seating.getBigBlindPlayerId(activePlayers));

        logger.debug("Posting blinds for game {} | SB={} (chips={}) | BB={} (chips={}) | potBefore={}",
                gameId, smallBlindPlayer.getName(), smallBlindPlayer.getChips(),
                bigBlindPlayer.getName(), bigBlindPlayer.getChips(), pot);

        postBlind(smallBlindPlayer, smallBlind);
        postBlind(bigBlindPlayer, bigBlind);
        bettingRound.setHighestBet(Math.max(smallBlindPlayer.getCurrentBet(), bigBlindPlayer.getCurrentBet()));

        logger.debug(
                "Blinds posted for game {} | sbBet={} | bbBet={} | potAfter={} | currentHighestBet={} | contributions={}",
                gameId, smallBlindPlayer.getCurrentBet(), bigBlindPlayer.getCurrentBet(),
                pot, bettingRound.getCurrentHighestBet(), handContributions);
    }

    /**
     * Evaluates whether forced blinds leave an actionable player or trigger an automatic runout or showdown.
     *
     * @return the next turn or phase outcome
     */
    public TurnOutcome advanceAfterBlinds() {
        if (activePlayers.isEmpty()) {
            return new TurnOutcome(TurnOutcome.TurnOutcomeType.NEXT_PLAYER, null);
        }
        BettingRound.ForcedBlindProgression progression = bettingRound.afterForcedBlinds(activePlayers, isHandOver());
        if (progression == BettingRound.ForcedBlindProgression.HAND_OVER) {
            return advanceTurnOrPhase(null, false);
        }
        if (progression == BettingRound.ForcedBlindProgression.AUTO_RUNOUT) {
            return new TurnOutcome(TurnOutcome.TurnOutcomeType.AUTO_ADVANCING, null);
        }
        if (BettingRound.needsNextActor(getCurrentPlayer())) {
            seating.nextPlayer(activePlayers);
        }
        return new TurnOutcome(TurnOutcome.TurnOutcomeType.NEXT_PLAYER, null);
    }

    /**
     * Validates and applies {@code player}'s betting decision and advances the turn, street, or showdown.
     *
     * @param player   the acting player
     * @param decision the requested action and chip amount
     * @return a {@link TurnOutcome} describing the resulting progression
     * @throws IllegalStateException       if {@code decision} is missing or has a negative amount
     * @throws BadRequestException         if the wager amount violates bet or raise sizing rules
     * @throws UnauthorisedActionException if the action is not available to {@code player}
     */
    public TurnOutcome processPlayerDecision(Player player, PlayerDecision decision) {
        if (decision == null || decision.action() == null) {
            throw new IllegalStateException("Invalid player action");
        }
        if (decision.amount() < 0) {
            throw new IllegalStateException("Action amount cannot be negative");
        }

        logger.debug(
                "Processing decision in game {} | player={} | decision={} | currentBet={} | currentHighestBet={} | pot={}",
                gameId, player.getName(), decision, player.getCurrentBet(),
                bettingRound.getCurrentHighestBet(), pot);

        BettingRound.DecisionResult result = bettingRound.prepareDecision(
                player, decision, activePlayers, bigBlind);
        PlayerDecision actualDecision = result.decision();

        int potBeforeDecision = this.pot;
        int wagerBeforeDecision = bettingRound.getCurrentHighestBet();

        switch (actualDecision.action()) {
            case FOLD, CHECK -> player.doAction(actualDecision.action(), 0, this.pot);
            case CALL, BET, RAISE -> this.pot = player.doAction(actualDecision.action(), result.amount(), this.pot);
            case ALL_IN -> this.pot = player.doAction(PlayerAction.ALL_IN, 0, this.pot);
        }

        PotCalculator.trackContribution(handContributions, player, this.pot - potBeforeDecision, gameId);
        bettingRound.recordAppliedDecision(player, bigBlind, wagerBeforeDecision);

        logger.debug(
                "Decision applied in game {} | player={} | action={} | potDelta={} | potNow={} | playerBet={} | playerChips={} | currentHighestBet={} | contributions={}",
                gameId, player.getName(), actualDecision.action(), (this.pot - potBeforeDecision),
                this.pot, player.getCurrentBet(), player.getChips(),
                bettingRound.getCurrentHighestBet(), handContributions);

        return advanceTurnOrPhase(result.conversionMessage(), true);
    }

    /**
     * Advances turn or street progression after a player has been removed from the table.
     *
     * @return the resulting {@link TurnOutcome}
     */
    public TurnOutcome advanceAfterPlayerRemoval() {
        if (activePlayers.isEmpty()) {
            return new TurnOutcome(TurnOutcome.TurnOutcomeType.NEXT_PLAYER, null);
        }
        bettingRound.reconcileHighestBetAfterRemoval(activePlayers);
        return advanceTurnOrPhase(null, false);
    }

    /**
     * Deals the flop (first three community cards), sets the phase to {@link GamePhase#FLOP},
     * and resets round bets.
     */
    public void dealFlop() {
        communityCards.addAll(deck.dealCards(3));
        currentPhase = GamePhase.FLOP;
        resetBetsForRound();
    }

    /**
     * Deals the turn (fourth community card), sets the phase to {@link GamePhase#TURN},
     * and resets round bets.
     */
    public void dealTurn() {
        communityCards.add(deck.dealCard());
        currentPhase = GamePhase.TURN;
        resetBetsForRound();
    }

    /**
     * Deals the river (fifth community card), sets the phase to {@link GamePhase#RIVER},
     * and resets round bets.
     */
    public void dealRiver() {
        communityCards.add(deck.dealCard());
        currentPhase = GamePhase.RIVER;
        resetBetsForRound();
    }

    /**
     * Evaluates remaining contenders, refunds uncalled chips, distributes contested side pots,
     * and records the settled hand result.
     *
     * @return list of winning players
     */
    public List<Player> conductShowdown() {
        if (lastSettlementResult != null) {
            return players.stream()
                    .filter(p -> lastSettlementResult.winnerIds().contains(p.getPlayerId()))
                    .toList();
        }

        logger.info("Conducting showdown for game {}", gameId);
        currentPhase = GamePhase.SHOWDOWN;
        bettingRound.clearLastFullRaise();

        List<Player> showdownPlayers = activePlayers.stream()
                .filter(Player::isInHand)
                .toList();

        logger.info("Showdown players: {}, Current pot: {}",
                showdownPlayers.stream().map(Player::getName).toList(), pot);

        if (showdownPlayers.isEmpty()) {
            // Keep unclaimed chips as carry-over so the ready/new-hand flow can continue safely.
            lastSettlementResult = new HandSettlementResult(Map.of(), Map.of(), Set.of(), pot, false);
            resetHandAccountingAfterShowdown();
            return List.of();
        }

        if (showdownPlayers.size() > 1) {
            logger.debug("Evaluating hands for {} players", showdownPlayers.size());
            ShowdownResolver.evaluateHands(communityCards, showdownPlayers);
        }

        if (logger.isDebugEnabled()) {
            showdownPlayers.forEach(p -> logger.debug("Player {}: {} with {}",
                    p.getName(), p.getHandRank(), p.getBestHand()));
        }

        List<Player> winners = distributeSidePotsAtShowdown(showdownPlayers, showdownPlayers.size() > 1);
        resetHandAccountingAfterShowdown();

        logger.debug("Pot after distribution: {}", pot);
        if (logger.isDebugEnabled()) {
            winners.forEach(p -> logger.debug("Player {} now has {} chips", p.getName(), p.getChips()));
        }
        logger.info("Showdown complete");
        return winners;
    }

    /**
     * Removes {@code player} from the game rosters and reconciles seating and betting state.
     *
     * @param player player to remove
     */
    public void removePlayer(Player player) {
        if (player == null) {
            return;
        }
        bettingRound.removePlayer(player.getPlayerId());
        players.remove(player);
        activePlayers.remove(player);
        seating.removePlayer(activePlayers, player.getPlayerId());
    }

    /**
     * Validates eligibility and updates {@code playerName}'s ready state during an open ready countdown.
     *
     * @param playerName player name requesting the change
     * @param ready      {@code true} to mark ready, {@code false} to unmark
     * @throws UnauthorisedActionException if the countdown is not active or the player is ineligible
     */
    public void setPlayerReadyForNextHand(String playerName, boolean ready) {
        readyGate.setPlayerReady(playerName, ready);
    }

    /**
     * Evaluates readiness conditions after a hand and opens the countdown or enters waiting state.
     *
     * @param deadlineEpochMs countdown expiration timestamp in epoch milliseconds
     * @return outcome describing the resulting gate action
     */
    public ReadyCountdownStartOutcome openOrWaitReadyGate(long deadlineEpochMs) {
        return readyGate.openOrWait(
                forfeitPending, gameOver, isGameDecided(), currentPhase, deadlineEpochMs, scheduling);
    }

    /**
     * Evaluates expiration of the ready countdown timer for {@code expectedDeadlineEpochMs}.
     *
     * @param expectedDeadlineEpochMs deadline captured when the timer was scheduled
     * @return outcome describing whether the expiration was stale, waiting, or forced ready
     */
    public ReadyCountdownTimeoutOutcome expireReadyCountdown(long expectedDeadlineEpochMs) {
        return readyGate.expireCountdown(forfeitPending, expectedDeadlineEpochMs, scheduling);
    }

    /**
     * Repairs a recovered ready countdown missing its deadline or scheduled task entry.
     *
     * @param fallbackDeadlineEpochMs fallback deadline to apply if the recovered countdown had no deadline
     * @return {@code true} if the gate state was repaired and requires a snapshot broadcast
     */
    public boolean repairRecoveredReadyGate(long fallbackDeadlineEpochMs) {
        return readyGate.repairRecoveredGate(fallbackDeadlineEpochMs, scheduling);
    }

    /**
     * Closes the between-hand ready gate without clearing player ready flags.
     */
    public void closeReadyCountdown() {
        readyGate.closeCountdown();
    }

    /**
     * Stops ticking readiness while keeping the completed hand visible in waiting state.
     */
    public void waitForReadyPlayers() {
        readyGate.waitForPlayers();
    }

    /**
     * Records the deadline for {@code task} and increments its schedule count so earlier timers
     * for the same task are ignored.
     *
     * @param task            delayed task type
     * @param deadlineEpochMs target execution deadline in epoch milliseconds
     */
    public void scheduleTask(ScheduledGameTask task, long deadlineEpochMs) {
        scheduling.scheduleTask(task, deadlineEpochMs);
    }

    /**
     * Removes the scheduled deadline for {@code task} and increments its schedule count so any
     * in-flight timer callback is ignored.
     *
     * @param task delayed task type
     */
    public void clearScheduledTask(ScheduledGameTask task) {
        scheduling.clearTask(task);
    }

    /**
     * Clears all scheduled gameplay tasks except {@link ScheduledGameTask#CLEANUP} and increments
     * their schedule counts.
     */
    public void invalidateProgressionTasks() {
        scheduling.invalidateProgressionTasks();
    }

    /**
     * Claims execution of a firing timer callback against current game state.
     *
     * @param task               task type that fired
     * @param expectedDeadline   deadline captured when the timer was scheduled
     * @param expectedHandNumber hand number captured when the timer was scheduled
     * @param expectedGeneration schedule count captured when the timer was scheduled
     * @return {@code true} if the task was claimed for execution, {@code false} otherwise
     */
    public boolean claimScheduledTask(
            ScheduledGameTask task,
            long expectedDeadline,
            long expectedHandNumber,
            long expectedGeneration) {
        return scheduling.claimTask(
                forfeitPending, gameOver, handNumber, task, expectedDeadline, expectedHandNumber, expectedGeneration);
    }

    /**
     * Increments the public state revision counter.
     */
    public void incrementStateRevision() {
        scheduling.incrementStateRevision();
    }

    /**
     * Marks the game over without eliminating remaining showdown state so post-game review stays intact.
     */
    public void markGameOver() {
        gameOver = true;
    }

    /**
     * Pauses the game after its other contenders leave, because the only contender left is
     * disconnected. Marks {@code leavingPlayers} out, holds the game with a pending forfeit until
     * the survivor reconnects or their grace period ends, and drops queued hand tasks. The cleanup
     * task is kept.
     *
     * @param leavingPlayers the players who are leaving the game
     */
    public void pauseForDisconnectedSurvivor(List<Player> leavingPlayers) {
        for (Player leavingPlayer : leavingPlayers) {
            leavingPlayer.setIsOut();
        }
        forfeitPending = true;
        waitForReadyPlayers();
        invalidateProgressionTasks();
    }

    public void setForfeitPending(boolean value) {
        this.forfeitPending = value;
    }

    /**
     * Marks forfeited players out, settles any unsettled hand and remaining pot once, resolves the
     * winner, and moves the game into its finished state.
     *
     * @param forfeitWinner        explicit forfeit survivor, or {@code null} for chip-based resolution
     * @param forfeitedPlayerNames names of players who forfeited before settlement
     * @return resolved winner
     * @throws IllegalStateException if no eligible winner can be resolved
     */
    public Player settleGameEnd(Player forfeitWinner, Set<String> forfeitedPlayerNames) {
        if (forfeitedPlayerNames != null) {
            for (Player player : players) {
                if (forfeitedPlayerNames.contains(player.getName())) {
                    player.setIsOut();
                }
            }
        }
        if (!isHandSettled()) {
            conductShowdown();
        }
        Player winner = GameEligibility.resolveWinner(this, forfeitWinner);
        if (!finalPotSettled) {
            if (pot > 0) {
                winner.addChips(pot);
                pot = 0;
            }
            finalPotSettled = true;
        }
        markGameOver();
        closeReadyCountdown();
        invalidateProgressionTasks();
        return winner;
    }

    public void setGameEndResult(GameEndResponse gameEndResult) {
        this.gameEndResult = gameEndResult;
    }

    public String getGameId() {
        return gameId;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public int getSmallBlind() {
        return smallBlind;
    }

    public int getBigBlind() {
        return bigBlind;
    }

    public List<Player> getPlayers() {
        return players;
    }

    public List<Player> getActivePlayers() {
        return activePlayers;
    }

    /**
     * Returns the player whose turn it currently is.
     *
     * @return the current player
     * @throws IllegalStateException if no active players remain
     */
    public Player getCurrentPlayer() {
        return seating.getCurrentPlayer(activePlayers);
    }

    /**
     * Finds a seated player by exact display name.
     *
     * @param playerName player name to match
     * @return matching player, or {@code null} if not present
     */
    public Player findPlayerByName(String playerName) {
        return findPlayerByName(players, playerName);
    }

    public List<Card> getCommunityCards() {
        return communityCards;
    }

    /**
     * Returns an immutable copy of the remaining deck cards in draw order.
     *
     * @return remaining deck cards
     */
    public List<Card> getRemainingDeckSnapshot() {
        return deck.getRemainingCardsSnapshot();
    }

    public int getPot() {
        return pot;
    }

    public int getCarryOverPot() {
        return carryOverPot;
    }

    /**
     * Returns contested pot amounts for display (main pot first, then side pots), excluding
     * uncalled amounts that are refunded.
     *
     * @return list of contested pot amounts
     */
    public List<Integer> getPotBreakdown() {
        return PotCalculator.getPotBreakdown(potInputs());
    }

    /**
     * Returns the total uncalled chips detected in the current pot breakdown.
     *
     * @return total uncalled chips eligible for refund
     */
    public int getUncalledAmount() {
        return PotCalculator.getUncalledAmount(potInputs());
    }

    private PotCalculator.PotInputs potInputs() {
        return new PotCalculator.PotInputs(
                pot, carryOverPot, handContributions, activePlayers, players,
                lastSettlementResult, bettingRound.getCurrentHighestBet(), gameId);
    }

    public GamePhase getCurrentPhase() {
        return currentPhase;
    }

    public int getCurrentHighestBet() {
        return bettingRound.getCurrentHighestBet();
    }

    /**
     * Calculates the additional chips {@code player} must commit for a minimum full raise.
     *
     * @param player non-null seated player
     * @return additional chips required for a full raise
     */
    public int getMinimumRaiseAmount(Player player) {
        return bettingRound.getMinimumRaiseAmount(player, bigBlind);
    }

    /**
     * Returns the immutable list of legal actions currently available to {@code player}.
     *
     * @param player proposed actor, or {@code null}
     * @return legal actions for {@code player}, or empty if they cannot act
     */
    public List<PlayerAction> getLegalActionsFor(Player player) {
        return bettingRound.legalActionsFor(
                player, players, activePlayers, seating, currentPhase, bigBlind);
    }

    /**
     * Returns whether every active player has had at least one turn in the current betting round.
     *
     * @return {@code true} if all active players have had an initial turn
     */
    public boolean hasAllPlayersHadInitialTurn() {
        return bettingRound.hasAllPlayersHadInitialTurn();
    }

    /**
     * Returns an immutable copy of player IDs that have acted in the current betting round.
     *
     * @return set of acted player IDs
     */
    public Set<String> getActedPlayerIdsSnapshot() {
        return bettingRound.getActedPlayerIdsSnapshot();
    }

    /**
     * Returns an immutable copy of each player's chip contributions in the current hand.
     *
     * @return player ID to contributed chips map
     */
    public Map<String, Integer> getHandContributionsSnapshot() {
        return Map.copyOf(handContributions);
    }

    public int getLastFullRaise() {
        return bettingRound.getLastFullRaise();
    }

    public Map<String, Integer> getLastActedWagerByPlayerSnapshot() {
        return bettingRound.getLastActedWagerByPlayerSnapshot();
    }

    public HandSettlementResult getLastSettlementResult() {
        return lastSettlementResult;
    }

    /**
     * Returns whether the current or most recent hand has completed pot settlement.
     *
     * @return {@code true} if hand settlement is complete
     */
    public boolean isHandSettled() {
        return lastSettlementResult != null;
    }

    /**
     * Returns whether the current or most recent hand settled a contested showdown with evaluated
     * hands rather than an uncontested fold win or void hand.
     *
     * @return {@code true} if the hand is settled and two or more players had their hands evaluated
     */
    public boolean wasContestedShowdown() {
        return lastSettlementResult != null && lastSettlementResult.wentToShowdown();
    }

    public List<String> getHandSeatOrderSnapshot() {
        return seating.getHandSeatOrderSnapshot();
    }

    public String getDealerPlayerId() {
        return seating.getDealerPlayerId();
    }

    public String getSmallBlindPlayerId() {
        return seating.getSmallBlindPlayerId(activePlayers);
    }

    public String getBigBlindPlayerId() {
        return seating.getBigBlindPlayerId(activePlayers);
    }

    public String getCurrentPlayerId() {
        return seating.getCurrentPlayerId();
    }

    public long getHandNumber() {
        return handNumber;
    }

    public ReadyGateState getReadyGateState() {
        return readyGate.getState();
    }

    public boolean isReadyCountdownActive() {
        return readyGate.isCountdownActive();
    }

    public Long getReadyCountdownDeadlineEpochMs() {
        return readyGate.getCountdownDeadlineEpochMs();
    }

    public int getReadyEligibleCount() {
        return readyGate.getReadyEligibleCount();
    }

    /**
     * Returns whether at least two eligible players exist and all eligible players are marked ready.
     *
     * @return {@code true} if all eligible players are ready for the next hand
     */
    public boolean areAllEligiblePlayersReady() {
        return readyGate.areAllEligiblePlayersReady();
    }

    /**
     * Returns the scheduled deadline in epoch milliseconds for {@code task}, or {@code null} when not scheduled.
     *
     * @param task delayed task type
     * @return scheduled deadline in epoch milliseconds, or {@code null}
     */
    public Long getScheduledTaskDeadline(ScheduledGameTask task) {
        return scheduling.getDeadline(task);
    }

    /**
     * Returns whether an all-in auto-advance to showdown is currently scheduled.
     *
     * @return {@code true} if the auto-advance task has a scheduled deadline
     */
    public boolean isAutoAdvancing() {
        return scheduling.getDeadline(ScheduledGameTask.AUTO_ADVANCE) != null;
    }

    /**
     * Returns the current monotonic schedule count for {@code task}.
     *
     * @param task delayed task type
     * @return schedule count, or {@code 0} if never scheduled
     */
    public long getScheduledTaskGeneration(ScheduledGameTask task) {
        return scheduling.getGeneration(task);
    }

    /**
     * Returns an immutable copy of all currently scheduled task deadlines.
     *
     * @return task-to-deadline map
     */
    public Map<ScheduledGameTask, Long> getScheduledTaskDeadlinesSnapshot() {
        return scheduling.deadlinesSnapshot();
    }

    public Map<ScheduledGameTask, Long> getScheduledTaskGenerationsSnapshot() {
        return scheduling.generationsSnapshot();
    }

    /**
     * Returns whether this game has reached or passed its scheduled cleanup deadline.
     *
     * @param nowEpochMs current epoch timestamp in milliseconds
     * @return {@code true} if cleanup deadline is set and {@code nowEpochMs} is at or past it
     */
    public boolean isCleanupExpired(long nowEpochMs) {
        return scheduling.isCleanupExpired(nowEpochMs);
    }

    public long getStateRevision() {
        return scheduling.getStateRevision();
    }

    /**
     * Returns whether one or zero non-eliminated players with chips remain.
     *
     * @return {@code true} if the game is decided
     */
    public boolean isGameDecided() {
        return GameEligibility.isGameDecided(players);
    }

    public boolean isGameOver() {
        return gameOver;
    }

    public boolean isFinalPotSettled() {
        return finalPotSettled;
    }

    public boolean isForfeitPending() {
        return forfeitPending;
    }

    /**
     * Returns whether the game is settled or over without a pending forfeit.
     *
     * @return {@code true} if the game has finished or can finalize without a pending forfeit
     */
    public boolean isFinishedOrEliminated() {
        return !forfeitPending && (gameOver || (isHandSettled() && isGameDecided()));
    }

    /**
     * Returns whether an ended game without a saved end summary concluded before a settled showdown.
     *
     * @return {@code true} if the hand was not settled in {@link GamePhase#SHOWDOWN}
     */
    public boolean endedBeforeShowdown() {
        return currentPhase != GamePhase.SHOWDOWN || !isHandSettled();
    }

    public GameEndResponse getGameEndResult() {
        return gameEndResult;
    }

    /**
     * Returns whether the game concluded via abandonment rather than normal or forfeit settlement.
     *
     * @return {@code true} if a terminal game-end result is stored and marked abandoned
     */
    public boolean isAbandoned() {
        return gameEndResult != null && gameEndResult.abandoned();
    }

    /**
     * Finds the first player in {@code tablePlayers} whose name matches {@code playerName}.
     *
     * @param tablePlayers player list to search
     * @param playerName   target player name
     * @return matching player, or {@code null} if not found
     */
    static Player findPlayerByName(List<Player> tablePlayers, String playerName) {
        if (tablePlayers == null) {
            return null;
        }
        return tablePlayers.stream()
                .filter(Objects::nonNull)
                .filter(player -> player.getName().equals(playerName))
                .findFirst()
                .orElse(null);
    }

    private Player findActivePlayerById(String playerId) {
        return activePlayers.stream()
                .filter(p -> p != null && p.getPlayerId().equals(playerId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Blind player not found in active players: " + playerId));
    }

    private void postBlind(Player player, int blindAmount) {
        int betBefore = player.getCurrentBet();
        if (player.getChips() <= blindAmount) {
            this.pot = player.doAction(PlayerAction.ALL_IN, 0, this.pot);
        } else {
            this.pot = player.doAction(PlayerAction.BET, blindAmount, this.pot);
        }
        PotCalculator.trackContribution(handContributions, player, player.getCurrentBet() - betBefore, gameId);
    }

    private TurnOutcome advanceTurnOrPhase(String conversionMessage, boolean moveToNextPlayer) {
        bettingRound.markInitialTurnsIfAllActed(activePlayers);
        TurnOutcome.TurnOutcomeType outcome = bettingRound.nextProgression(activePlayers, isHandOver(), currentPhase);
        if (outcome == TurnOutcome.TurnOutcomeType.SHOWDOWN) {
            conductShowdown();
            return new TurnOutcome(outcome, conversionMessage);
        }
        if (outcome == TurnOutcome.TurnOutcomeType.PHASE_ADVANCED) {
            advancePhase();
        } else if (outcome == TurnOutcome.TurnOutcomeType.NEXT_PLAYER && moveToNextPlayer) {
            seating.nextPlayer(activePlayers);
        }
        return new TurnOutcome(outcome, conversionMessage);
    }

    private void advancePhase() {
        switch (currentPhase) {
            case PRE_FLOP -> dealFlop();
            case FLOP -> dealTurn();
            case TURN -> dealRiver();
            case RIVER, SHOWDOWN -> {
            }
        }
    }

    private void resetBetsForRound() {
        bettingRound.resetForNewRound(activePlayers);
        seating.moveToFirstPostFlopActor(activePlayers);
    }

    private boolean isHandOver() {
        return activePlayers.stream().filter(Player::isInHand).count() <= 1;
    }

    private List<Player> distributeSidePotsAtShowdown(List<Player> showdownPlayers, boolean wentToShowdown) {
        logger.info("Distributing side pots for game {}", gameId);
        PotComputationResult computation = PotCalculator.computePotAllocationsAndUncalled(
                pot, carryOverPot, handContributions, activePlayers, players, showdownPlayers, gameId);
        ShowdownResolver.Distribution distribution =
                ShowdownResolver.distribute(gameId, computation, players,
                        seating.getHandSeatOrderSnapshot(), seating.getDealerPlayerId(), wentToShowdown);
        pot = distribution.remainder();
        lastSettlementResult = distribution.settlement();
        return distribution.winners();
    }

    private void resetHandAccountingAfterShowdown() {
        handContributions.clear();
        bettingRound.clearActedPlayers();
        logger.debug("Reset hand accounting after showdown for game {} | potRemainder={}", gameId, pot);
    }
}
