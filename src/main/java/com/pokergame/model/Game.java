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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Represents a live poker game: owns the deck, players, pot, and hand phase, and coordinates
 * seating, betting, between-hand readiness, and scheduled timers.
 */
public class Game {
    private static final Logger logger = LoggerFactory.getLogger(Game.class);
    static final long INITIAL_HAND_REVISION = 1L;

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
    private HandPayout lastHandPayout;
    private long handNumber;
    private long stateRevision;
    private boolean gameOver;
    private boolean finalPotSettled;
    private boolean pausedForDisconnectedSurvivor;
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
        this.currentPhase = GamePhase.PRE_FLOP;
        this.handContributions = new HashMap<>();
        this.seating = new TableSeating(this.activePlayers, this.players);
        this.bettingRound = new BettingRound();
        this.readyGate = new ReadyGate(this.players);
        this.scheduling = new GameScheduling();
    }

    /**
     * Reconstructs a {@link Game} from saved snapshot state supplied by {@link GameRestoreBuilder},
     * validating {@code maxPlayers} and restoring collaborator state without running live hand setup.
     *
     * @param builder populated snapshot builder
     * @throws IllegalStateException if {@code gameId}, {@code maxPlayers}, or {@code players} are invalid,
     *                               an active player ID is unknown, or the snapshot has no deck
     */
    private Game(GameRestoreBuilder builder) {
        validateRestoreBuilder(builder);

        this.gameId = builder.gameId;
        this.players = new ArrayList<>(builder.players);
        this.maxPlayers = builder.maxPlayers;
        this.activePlayers = restoreActivePlayers(builder, this.players);
        this.deck = restoreDeck(builder);
        this.communityCards = builder.communityCards != null
                ? new ArrayList<>(builder.communityCards)
                : new ArrayList<>();
        this.pot = builder.pot;
        this.carryOverPot = builder.carryOverPot;
        this.currentPhase = builder.currentPhase;
        this.gameOver = builder.gameOver;
        this.smallBlind = builder.smallBlind;
        this.bigBlind = builder.bigBlind;
        this.handContributions = builder.handContributions != null
                ? new HashMap<>(builder.handContributions)
                : new HashMap<>();
        this.lastHandPayout = builder.lastHandPayout;
        this.finalPotSettled = builder.finalPotSettled;
        this.pausedForDisconnectedSurvivor = builder.pausedForDisconnectedSurvivor;
        this.handNumber = builder.handNumber;
        this.stateRevision = builder.stateRevision;
        this.gameEndResult = builder.gameEndResult;

        this.seating = new TableSeating(
                builder.handSeatOrder,
                builder.dealerPlayerId,
                builder.smallBlindPlayerId,
                builder.bigBlindPlayerId,
                builder.currentPlayerId,
                this.players);
        this.bettingRound = new BettingRound(
                builder.currentHighestBet,
                builder.allPlayersHadInitialTurn,
                builder.actedPlayersInRound,
                builder.lastFullRaise,
                builder.previousBets);
        this.readyGate = new ReadyGate(
                this.players,
                builder.readyGateState,
                builder.readyCountdownDeadlineEpochMs);
        this.scheduling = new GameScheduling(
                builder.scheduledTimes,
                builder.timerVersions);
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
        logger.debug("Resetting hand for game {} | carryOverPot={} | previousContributions={}",
                gameId, pot, handContributions);

        eliminateBrokePlayers();
        if (activePlayers.size() <= 1) {
            logger.info("Game over - only {} active players remaining", activePlayers.size());
            gameOver = true;
            return true;
        }
        if (gameOver) {
            return true;
        }

        // Only reclassify the pot as carry-over after confirming another hand can begin.
        carryOverPot = pot;
        deck = new Deck();
        communityCards.clear();
        currentPhase = GamePhase.PRE_FLOP;
        bettingRound.resetForNewHand();
        handContributions.clear();
        lastHandPayout = null;
        finalPotSettled = false;
        closeReadyCountdown();

        activePlayers.forEach(Player::resetAttributes);
        seating.advancePositions(activePlayers);
        logger.debug("Hand reset complete for game {} | contributions cleared", gameId);
        return false;
    }

    /**
     * Increments the hand number and resets the state revision for the new hand.
     */
    public void beginHand() {
        handNumber++;
        resetStateRevisionForNewHand();
    }

    /**
     * Deals two hole cards from the deck to each active player.
     */
    public void dealHoleCards() {
        for (Player player : activePlayers) {
            player.setHoleCards(deck.dealCards(2));
        }
    }

    /**
     * Deducts the small and big blinds from the assigned blind players, going all-in when a
     * player's chip stack is smaller than the blind.
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
     * Evaluates whether forced blinds leave a player who can act or trigger an automatic advance or showdown.
     *
     * @return the next turn or phase outcome
     */
    public TurnOutcome advanceAfterBlinds() {
        TurnOutcome outcome = advanceTurnOrPhase(null, false);
        if (outcome.type() == TurnOutcome.TurnOutcomeType.NEXT_PLAYER && !getCurrentPlayer().canAct()) {
            seating.nextPlayer(activePlayers);
        }
        return outcome;
    }

    /**
     * Validates and applies {@code player}'s betting decision and advances the turn, betting round, or showdown.
     *
     * @param player   the acting player
     * @param decision the requested action and chip amount
     * @return a {@link TurnOutcome} describing the resulting progression
     * @throws IllegalStateException       if {@code decision} is missing
     * @throws BadRequestException         if the bet amount violates bet or raise sizing rules
     * @throws UnauthorisedActionException if the action is not available to {@code player}
     */
    public TurnOutcome processPlayerDecision(Player player, PlayerDecision decision) {
        if (decision == null) {
            throw new IllegalStateException("Invalid player action");
        }

        BettingRound.ValidatedDecision validated = bettingRound.validateDecision(
                player, decision, activePlayers, bigBlind);
        PlayerDecision appliedDecision = validated.decision();

        int potBeforeDecision = this.pot;
        int highestBetBeforeDecision = bettingRound.getCurrentHighestBet();

        this.pot += player.doAction(appliedDecision.action(), validated.amount());

        PotCalculator.trackContribution(handContributions, player, this.pot - potBeforeDecision, gameId);
        bettingRound.recordAppliedDecision(player, bigBlind, highestBetBeforeDecision);

        return advanceTurnOrPhase(validated.playerActionNotice(), true);
    }

    /**
     * Advances turn or phase progression after a player has been removed from the table.
     *
     * @return the resulting {@link TurnOutcome}
     * @throws IllegalStateException if no active players remain
     */
    public TurnOutcome advanceAfterPlayerRemoval() {
        if (activePlayers.isEmpty()) {
            throw new IllegalStateException("No active players remain after removal");
        }
        bettingRound.recalculateHighestBetAfterRemoval(activePlayers);
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
     * and records the hand's payout summary.
     */
    public void conductShowdown() {
        if (lastHandPayout != null) {
            return;
        }

        currentPhase = GamePhase.SHOWDOWN;
        bettingRound.clearLastFullRaise();

        List<Player> showdownPlayers = activePlayers.stream()
                .filter(Player::isInHand)
                .toList();

        if (showdownPlayers.isEmpty()) {
            // Keep unclaimed chips as carry-over so the ready/new-hand flow can continue safely.
            lastHandPayout = new HandPayout(Map.of(), Map.of(), Set.of(), pot, false);
            resetHandAccountingAfterShowdown();
            logger.debug("Showdown complete for game {} | contenders=0 | potRemainder={}", gameId, pot);
            return;
        }

        if (showdownPlayers.size() > 1) {
            ShowdownResolver.evaluateHands(communityCards, showdownPlayers);
        }

        distributeSidePotsAtShowdown(showdownPlayers, showdownPlayers.size() > 1);
        resetHandAccountingAfterShowdown();
        logger.debug("Showdown complete for game {} | contenders={} | winners={} | potRemainder={}",
                gameId, showdownPlayers.size(), lastHandPayout.winnerIds(), pot);
    }

    /**
     * Removes {@code player} from the game rosters and updates seating and betting state.
     *
     * @param player player to remove
     */
    public void removePlayer(Player player) {
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
     * Evaluates readiness conditions after a hand and opens the between-hand ready countdown or
     * enters {@link ReadyGateState#WAITING_FOR_PLAYERS} when fewer than two eligible players are available.
     *
     * @param deadlineEpochMs countdown expiration timestamp in epoch milliseconds
     * @return outcome describing the resulting gate action
     */
    public ReadyCountdownStartOutcome openOrWaitReadyGate(long deadlineEpochMs) {
        if (pausedForDisconnectedSurvivor || gameOver) {
            return ReadyCountdownStartOutcome.IGNORE;
        }
        if (isGameDecided()) {
            return ReadyCountdownStartOutcome.GAME_DECIDED;
        }
        if (currentPhase != GamePhase.SHOWDOWN) {
            return ReadyCountdownStartOutcome.IGNORE;
        }
        return readyGate.openOrWait(deadlineEpochMs, scheduling);
    }

    /**
     * Evaluates expiration of the between-hand ready countdown timer for {@code expectedDeadlineEpochMs}.
     *
     * @param expectedDeadlineEpochMs deadline captured when the timer was scheduled
     * @return outcome describing whether the expiration was stale, waiting, or forced ready
     */
    public ReadyCountdownTimeoutOutcome expireReadyCountdown(long expectedDeadlineEpochMs) {
        return readyGate.expireCountdown(pausedForDisconnectedSurvivor, expectedDeadlineEpochMs, scheduling);
    }

    /**
     * Repairs a recovered between-hand ready countdown missing its deadline or scheduled task entry.
     *
     * @param fallbackDeadlineEpochMs fallback deadline to apply if the recovered countdown had no deadline
     * @return {@code true} if the gate state was repaired and requires a snapshot broadcast
     */
    public boolean repairRecoveredReadyGate(long fallbackDeadlineEpochMs) {
        return readyGate.repairRecoveredGate(fallbackDeadlineEpochMs, scheduling);
    }

    /**
     * Closes the between-hand ready countdown without clearing player ready flags.
     */
    public void closeReadyCountdown() {
        readyGate.closeCountdown();
    }

    /**
     * Records the run time for {@code task} and increments its schedule count so earlier timers
     * for the same task are ignored.
     *
     * @param task         delayed task type
     * @param runAtEpochMs target execution time in epoch milliseconds
     */
    public void scheduleTask(ScheduledGameTask task, long runAtEpochMs) {
        scheduling.scheduleTask(task, runAtEpochMs);
    }

    /**
     * Removes the scheduled run time for {@code task} and increments its schedule count so any
     * in-flight timer callback is ignored.
     *
     * @param task delayed task type
     */
    public void clearScheduledTask(ScheduledGameTask task) {
        scheduling.clearTask(task);
    }

    /**
     * Checks whether a scheduled timer should still run, and clears one-time tasks (everything
     * except {@link ScheduledGameTask#CLEANUP}) so they only run once.
     *
     * @param task                 task type that fired
     * @param expectedRunAtEpochMs scheduled run time captured when the timer was scheduled
     * @param expectedHandNumber   hand number captured when the timer was scheduled
     * @param expectedTimerVersion schedule count captured when the timer was scheduled
     * @return {@code true} if the task is still current and can start, {@code false} otherwise
     */
    public boolean startScheduledTaskIfCurrent(
            ScheduledGameTask task,
            long expectedRunAtEpochMs,
            long expectedHandNumber,
            long expectedTimerVersion) {
        return scheduling.startScheduledTaskIfCurrent(
                pausedForDisconnectedSurvivor, handNumber, task, expectedRunAtEpochMs, expectedHandNumber, expectedTimerVersion);
    }

    /**
     * Increments the public state revision counter.
     */
    public void incrementStateRevision() {
        stateRevision++;
    }

    /**
     * Marks the game over without eliminating remaining showdown state so post-game review stays intact.
     */
    public void markGameOver() {
        gameOver = true;
    }

    /**
     * Ends the game due to inactivity, closes the ready countdown, and clears all scheduled tasks.
     */
    public void abandonDueToInactivity() {
        gameOver = true;
        readyGate.closeCountdown();
        scheduling.clearAllTasks();
    }

    /**
     * Closes the ready countdown and clears all scheduled tasks (used when cleaning up an empty abandoned game).
     */
    public void clearReadyCountdownAndScheduledTasks() {
        readyGate.closeCountdown();
        scheduling.clearAllTasks();
    }

    /**
     * Pauses the game after its other contenders leave, because the only contender left is
     * disconnected. Marks {@code leavingPlayers} out, moves readiness to waiting state until
     * the survivor reconnects or their grace period ends, and drops queued hand tasks. The cleanup
     * task is kept.
     *
     * @param leavingPlayers the players who are leaving the game
     */
    public void pauseForDisconnectedSurvivor(List<Player> leavingPlayers) {
        for (Player leavingPlayer : leavingPlayers) {
            leavingPlayer.setIsOut();
        }
        pausedForDisconnectedSurvivor = true;
        readyGate.waitForPlayers();
        scheduling.invalidateProgressionTasks();
    }

    /**
     * Clears the disconnected-survivor pause once the remaining contender reconnects or forfeit settlement proceeds.
     */
    public void clearPausedForDisconnectedSurvivor() {
        this.pausedForDisconnectedSurvivor = false;
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
        for (Player player : players) {
            if (forfeitedPlayerNames.contains(player.getName())) {
                player.setIsOut();
            }
        }
        if (!isHandSettled()) {
            conductShowdown();
        }
        Player winner = resolveWinner(forfeitWinner);
        if (!finalPotSettled) {
            if (pot > 0) {
                winner.addChips(pot);
                pot = 0;
            }
            finalPotSettled = true;
        }
        markGameOver();
        closeReadyCountdown();
        scheduling.invalidateProgressionTasks();
        return winner;
    }

    /**
     * Captures an immutable snapshot of internal collaborator and accounting state for persistence.
     *
     * @return immutable persistence snapshot of internal game fields
     */
    public Snapshot toSnapshot() {
        return new Snapshot(
                deck.getRemainingCardsSnapshot(),
                carryOverPot,
                Map.copyOf(handContributions),
                bettingRound.hasAllPlayersHadInitialTurn(),
                bettingRound.getActedPlayerIdsSnapshot(),
                scheduling.scheduledTimesSnapshot(),
                scheduling.timerVersionsSnapshot(),
                bettingRound.getLastFullRaise(),
                bettingRound.getPreviousBetsSnapshot(),
                seating.getHandSeatOrderSnapshot(),
                seating.getDealerPlayerId(),
                seating.getCurrentPlayerId());
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
     * Returns whether the current or most recent hand settled a contested showdown with evaluated
     * hands rather than an uncontested fold win or void hand.
     *
     * @return {@code true} if the hand is settled and two or more players had their hands evaluated
     */
    public boolean wasContestedShowdown() {
        return lastHandPayout != null && lastHandPayout.wentToShowdown();
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
     * Returns whether {@code player} still qualifies as a contender in this game.
     *
     * @param player player to evaluate
     * @return {@code true} if the player is not out and either the hand is unsettled or they hold chips
     */
    public boolean isContender(Player player) {
        if (player.isOut()) {
            return false;
        }
        return isHandInProgress() || player.getChips() > 0;
    }

    /**
     * Counts the players in this game who still qualify as contenders.
     *
     * @return number of contending players
     */
    public int countContenders() {
        return (int) players.stream().filter(this::isContender).count();
    }

    /**
     * Finds the sole connected contender eligible to claim an immediate disconnect win, if one exists.
     *
     * @return {@code Optional} containing the sole connected contender when at least two contenders
     *         remain, exactly one is connected, at least one is disconnected, and the game has not
     *         finished or settled as decided; otherwise {@code Optional.empty()}
     */
    public Optional<Player> findClaimWinPlayer() {
        if (gameOver || (isHandSettled() && isGameDecided())) {
            return Optional.empty();
        }
        List<Player> contenders = players.stream().filter(this::isContender).toList();
        if (contenders.size() < 2) {
            return Optional.empty();
        }
        List<Player> connected = contenders.stream()
                .filter(player -> !player.isDisconnected())
                .toList();
        if (connected.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(connected.getFirst());
    }

    /**
     * Checks whether {@code playerName} may claim an immediate disconnect win.
     *
     * @param playerName player name to check
     * @return {@code true} if {@code playerName} is eligible to claim a disconnect win
     */
    public boolean canClaimWin(String playerName) {
        return findClaimWinPlayer()
                .filter(claimant -> claimant.getName().equals(playerName))
                .isPresent();
    }

    /**
     * Returns the names of all disconnected contenders opposing {@code playerName}.
     *
     * @param playerName claiming player to exclude
     * @return list of disconnected opponent names
     */
    public List<String> disconnectedOpponentsOf(String playerName) {
        return players.stream()
                .filter(player -> !player.getName().equals(playerName))
                .filter(player -> isContender(player) && player.isDisconnected())
                .map(Player::getName)
                .toList();
    }

    /**
     * Returns whether one or zero non-eliminated players with chips remain.
     *
     * @return {@code true} if the game is decided
     */
    public boolean isGameDecided() {
        long playersWithChips = players.stream()
                .filter(player -> !player.isOut() && player.getChips() > 0)
                .count();
        return playersWithChips <= 1;
    }

    /**
     * Resolves the game winner, returning {@code forfeitWinner} when provided and eligible, or
     * otherwise picking the chip leader among players who are not out (breaking ties by earliest
     * seat).
     *
     * @param forfeitWinner explicit forfeit survivor, or {@code null} for chip-based resolution
     * @return resolved non-out winner on the game roster
     * @throws IllegalStateException if {@code forfeitWinner} is not an eligible seated player or
     *                               no non-out player exists on the roster
     */
    Player resolveWinner(Player forfeitWinner) {
        if (forfeitWinner != null) {
            if (!players.contains(forfeitWinner) || forfeitWinner.isOut()) {
                throw new IllegalStateException(
                        "Forfeit winner must be seated on the roster and not out: " + forfeitWinner.getName());
            }
            return forfeitWinner;
        }
        if (players.isEmpty()) {
            throw new IllegalStateException("Cannot resolve winner from an empty roster");
        }
        return resolveChipWinner();
    }

    /**
     * Resets the public state revision counter to {@link #INITIAL_HAND_REVISION} for a new hand.
     */
    void resetStateRevisionForNewHand() {
        stateRevision = INITIAL_HAND_REVISION;
    }

    /**
     * Finds the first player in {@code tablePlayers} whose name matches {@code playerName}.
     *
     * @param tablePlayers player list to search
     * @param playerName   target player name
     * @return matching player, or {@code null} if not found
     */
    static Player findPlayerByName(List<Player> tablePlayers, String playerName) {
        return tablePlayers.stream()
                .filter(player -> player.getName().equals(playerName))
                .findFirst()
                .orElse(null);
    }

    private void eliminateBrokePlayers() {
        activePlayers.clear();
        for (Player player : players) {
            if (player.getChips() == 0) {
                logger.info("Setting {} as out (0 chips)", player.getName());
                player.setIsOut();
            }
            if (!player.isOut()) {
                activePlayers.add(player);
            }
        }
    }

    private PotCalculator.PotInputs potInputs() {
        return new PotCalculator.PotInputs(
                pot, carryOverPot, handContributions, activePlayers, players,
                lastHandPayout, bettingRound.getCurrentHighestBet(), gameId);
    }

    private Player resolveChipWinner() {
        // Tie-break by earliest seat in roster order so abandonments and forfeits resolve
        // deterministically to a single table winner matching table seat priority without inventing a draw.
        return players.stream()
                .filter(player -> !player.isOut())
                .max(Comparator.comparingInt(Player::getChips)
                        .thenComparing(Comparator.comparingInt(players::indexOf).reversed()))
                .orElseThrow(() -> new IllegalStateException("Cannot resolve winner when every player is out"));
    }

    private Player findActivePlayerById(String playerId) {
        return activePlayers.stream()
                .filter(p -> p.getPlayerId().equals(playerId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Blind player not found in active players: " + playerId));
    }

    private void postBlind(Player player, int blindAmount) {
        int betBefore = player.getCurrentBet();
        if (player.getChips() <= blindAmount) {
            this.pot += player.doAction(PlayerAction.ALL_IN, 0);
        } else {
            this.pot += player.doAction(PlayerAction.BET, blindAmount);
        }
        PotCalculator.trackContribution(handContributions, player, player.getCurrentBet() - betBefore, gameId);
    }

    private TurnOutcome advanceTurnOrPhase(String playerActionNotice, boolean moveToNextPlayer) {
        bettingRound.markInitialTurnsIfAllActed(activePlayers);
        TurnOutcome.TurnOutcomeType outcome = bettingRound.nextProgression(activePlayers, isHandOver(), currentPhase);
        if (outcome == TurnOutcome.TurnOutcomeType.SHOWDOWN) {
            conductShowdown();
            return new TurnOutcome(outcome, playerActionNotice);
        }
        if (outcome == TurnOutcome.TurnOutcomeType.PHASE_ADVANCED) {
            advancePhase();
        } else if (outcome == TurnOutcome.TurnOutcomeType.NEXT_PLAYER && moveToNextPlayer) {
            seating.nextPlayer(activePlayers);
        }
        return new TurnOutcome(outcome, playerActionNotice);
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

    private void distributeSidePotsAtShowdown(List<Player> showdownPlayers, boolean wentToShowdown) {
        PotComputationResult computation = PotCalculator.computePotAllocationsAndUncalled(
                pot, carryOverPot, handContributions, activePlayers, players, showdownPlayers, gameId);
        ShowdownResolver.Distribution distribution =
                ShowdownResolver.distribute(gameId, computation, players,
                        seating.getHandSeatOrderSnapshot(), seating.getDealerPlayerId(), wentToShowdown);
        pot = distribution.remainder();
        lastHandPayout = distribution.payout();
    }

    private void resetHandAccountingAfterShowdown() {
        handContributions.clear();
        bettingRound.clearActedPlayers();
    }

    private static void validateRestoreBuilder(GameRestoreBuilder builder) {
        if (builder.gameId == null || builder.gameId.trim().isEmpty()) {
            throw new IllegalStateException("Game ID cannot be null or empty");
        }
        List<Player> builderPlayers = builder.players;
        if (builderPlayers == null || builderPlayers.stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException("Invalid players list. Please try again.");
        }
        int restoredMaxPlayers = builder.maxPlayers;
        if (restoredMaxPlayers < Room.MIN_PLAYERS || restoredMaxPlayers > Room.MAX_PLAYERS) {
            throw new IllegalStateException(
                    "Max players must be between " + Room.MIN_PLAYERS + " and " + Room.MAX_PLAYERS + ".");
        }
    }

    private static List<Player> restoreActivePlayers(GameRestoreBuilder builder, List<Player> tablePlayers) {
        Map<String, Player> playersById = new HashMap<>();
        tablePlayers.forEach(player -> playersById.put(player.getPlayerId(), player));

        List<Player> restoredActive = new ArrayList<>();
        List<String> activeIds = builder.activePlayerIds;
        if (activeIds != null) {
            for (String playerId : activeIds) {
                Player player = playersById.get(playerId);
                if (player == null) {
                    throw new IllegalStateException("Snapshot references an unknown active player");
                }
                restoredActive.add(player);
            }
        }
        return restoredActive;
    }

    private static Deck restoreDeck(GameRestoreBuilder builder) {
        List<Card> remainingDeck = builder.remainingDeck;
        if (remainingDeck == null) {
            throw new IllegalStateException("Saved game " + builder.gameId + " has no deck");
        }
        return Deck.restore(remainingDeck);
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

    public Player getCurrentPlayer() {
        return seating.getCurrentPlayer(activePlayers);
    }

    public List<Card> getCommunityCards() {
        return communityCards;
    }

    public int getPot() {
        return pot;
    }

    public GamePhase getCurrentPhase() {
        return currentPhase;
    }

    public int getCurrentHighestBet() {
        return bettingRound.getCurrentHighestBet();
    }

    public HandPayout getLastHandPayout() {
        return lastHandPayout;
    }

    public boolean isHandSettled() {
        return lastHandPayout != null;
    }

    public String getSmallBlindPlayerId() {
        return seating.getSmallBlindPlayerId(activePlayers);
    }

    public String getBigBlindPlayerId() {
        return seating.getBigBlindPlayerId(activePlayers);
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

    public Long getScheduledTaskTime(ScheduledGameTask task) {
        return scheduling.getScheduledTime(task);
    }

    public boolean isAutoAdvancing() {
        return scheduling.getScheduledTime(ScheduledGameTask.AUTO_ADVANCE) != null;
    }

    public long getTimerVersion(ScheduledGameTask task) {
        return scheduling.getTimerVersion(task);
    }

    public boolean isCleanupExpired(long nowEpochMs) {
        return scheduling.isCleanupExpired(nowEpochMs);
    }

    public long getStateRevision() {
        return stateRevision;
    }

    public boolean isGameOver() {
        return gameOver;
    }

    public boolean isFinalPotSettled() {
        return finalPotSettled;
    }

    public boolean isPausedForDisconnectedSurvivor() {
        return pausedForDisconnectedSurvivor;
    }

    public boolean isMatchConcluded() {
        return !pausedForDisconnectedSurvivor && (gameOver || (isHandSettled() && isGameDecided()));
    }

    public boolean isHandInProgress() {
        return currentPhase != GamePhase.SHOWDOWN || !isHandSettled();
    }

    public GameEndResponse getGameEndResult() {
        return gameEndResult;
    }

    public void setGameEndResult(GameEndResponse gameEndResult) {
        this.gameEndResult = gameEndResult;
    }

    public boolean isAbandoned() {
        return gameEndResult != null && gameEndResult.abandoned();
    }

    /**
     * Immutable persistence snapshot of internal {@link Game} collaborator and accounting state.
     *
     * @param remainingDeck            undealt cards left in the deck
     * @param carryOverPot             chips carried over from an earlier hand
     * @param handContributions        chips put into the pot this hand by player ID
     * @param allPlayersHadInitialTurn whether every active player has had a turn this round
     * @param actedPlayerIds           player IDs that have acted in the current betting round
     * @param scheduledTimes           scheduled background task run times by task type
     * @param timerVersions            schedule counters by task type
     * @param lastFullRaise            last full-raise increment in the current betting round
     * @param previousBets             each player's total bet in the current round after their last turn
     * @param handSeatOrder            ordered player IDs for the current hand
     * @param dealerPlayerId           player ID holding the dealer button
     * @param currentPlayerId          player ID whose turn it currently is
     */
    public record Snapshot(
            List<Card> remainingDeck,
            int carryOverPot,
            Map<String, Integer> handContributions,
            boolean allPlayersHadInitialTurn,
            Set<String> actedPlayerIds,
            Map<ScheduledGameTask, Long> scheduledTimes,
            Map<ScheduledGameTask, Long> timerVersions,
            int lastFullRaise,
            Map<String, Integer> previousBets,
            List<String> handSeatOrder,
            String dealerPlayerId,
            String currentPlayerId) {
        public Snapshot {
            remainingDeck = List.copyOf(remainingDeck);
            handContributions = Map.copyOf(handContributions);
            actedPlayerIds = Set.copyOf(actedPlayerIds);
            scheduledTimes = Map.copyOf(scheduledTimes);
            timerVersions = Map.copyOf(timerVersions);
            previousBets = Map.copyOf(previousBets);
            handSeatOrder = List.copyOf(handSeatOrder);
        }
    }

    /**
     * Builder for reconstructing a {@link Game} instance from saved snapshot state.
     */
    public static class GameRestoreBuilder {
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
        private HandPayout lastHandPayout;
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

        private GameRestoreBuilder() {
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

        public GameRestoreBuilder lastHandPayout(HandPayout result) {
            this.lastHandPayout = result;
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
    }
}
