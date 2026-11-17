package com.pokergame.model;

import com.pokergame.dto.internal.PlayerDecision;
import com.pokergame.dto.internal.TurnOutcome;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.PlayerAction;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.UnauthorisedActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns per-hand and per-street betting state, wager validation, and round-completion rules for {@link Game}.
 *
 * <p>{@link #legalActionsFor} and {@link #prepareDecision} share the same rule functions
 * ({@link #amountOwed}, {@link #getMinimumRaiseAmount}, {@link #bettingReopenedFor} and
 * {@link #hasOtherActionableOpponent}), so the actions offered to a player are the actions the server accepts.
 */
final class BettingRound {
    private static final Logger logger = LoggerFactory.getLogger(BettingRound.class);

    private int currentHighestBet;
    private boolean allPlayersHadInitialTurn;
    private final Set<String> actedPlayersInRound;
    /** Size of the last full raise this street, or 0 when none has been made yet (the big blind applies). */
    private int lastFullRaise;
    private final Map<String, Integer> lastActedWagerByPlayer;

    /** Creates fresh betting-round state for a new game. */
    BettingRound() {
        this.currentHighestBet = 0;
        this.allPlayersHadInitialTurn = false;
        this.actedPlayersInRound = new HashSet<>();
        this.lastFullRaise = 0;
        this.lastActedWagerByPlayer = new HashMap<>();
    }

    /**
     * Reconstructs betting-round state from saved snapshot fields.
     *
     * @param currentHighestBet       highest street bet facing players
     * @param allPlayersHadInitialTurn whether every active player has had at least one turn this street
     * @param actedPlayersInRound     IDs of players who have acted in the current street
     * @param lastFullRaise           last full-raise increment this street, or 0 if none has been made yet
     * @param lastActedWagerByPlayer  last total street wager committed by each player ID
     */
    BettingRound(
            int currentHighestBet,
            boolean allPlayersHadInitialTurn,
            Set<String> actedPlayersInRound,
            int lastFullRaise,
            Map<String, Integer> lastActedWagerByPlayer) {
        this.currentHighestBet = currentHighestBet;
        this.allPlayersHadInitialTurn = allPlayersHadInitialTurn;
        this.actedPlayersInRound = actedPlayersInRound != null ? new HashSet<>(actedPlayersInRound) : new HashSet<>();
        this.lastFullRaise = lastFullRaise;
        this.lastActedWagerByPlayer = lastActedWagerByPlayer != null ? new HashMap<>(lastActedWagerByPlayer) : new HashMap<>();
    }

    /**
     * Resets all betting state for the start of a new hand.
     */
    void resetForNewHand() {
        currentHighestBet = 0;
        allPlayersHadInitialTurn = false;
        actedPlayersInRound.clear();
        lastFullRaise = 0;
        lastActedWagerByPlayer.clear();
    }

    /** Forgets the street's last full raise, so the minimum raise falls back to the big blind (used at showdown). */
    void clearLastFullRaise() {
        lastFullRaise = 0;
    }

    void clearActedPlayers() {
        actedPlayersInRound.clear();
    }

    /**
     * Resets per-player street bets and round-tracking flags when advancing to a new street.
     *
     * @param activePlayers active players in the current hand
     */
    void resetForNewRound(List<Player> activePlayers) {
        for (Player player : activePlayers) {
            player.resetCurrentBet();
        }
        currentHighestBet = 0;
        allPlayersHadInitialTurn = false;
        actedPlayersInRound.clear();
        lastFullRaise = 0;
        lastActedWagerByPlayer.clear();
    }

    void setHighestBet(int highestBet) {
        this.currentHighestBet = highestBet;
    }

    /**
     * Validates and prepares a player's betting decision without mutating player or round state.
     *
     * @param player        acting player
     * @param decision      requested action and wager
     * @param activePlayers active players in the hand
     * @param bigBlind      table big blind amount
     * @return validated decision, chip amount to apply, and optional conversion notice
     */
    DecisionResult prepareDecision(Player player, PlayerDecision decision, List<Player> activePlayers, int bigBlind) {
        if (player == null || !player.canAct()) {
            throw new UnauthorisedActionException(
                    "Player cannot act in the current hand", "ACTION_NOT_AVAILABLE", "Player is not eligible to act");
        }
        return switch (decision.action()) {
            case FOLD -> new DecisionResult(decision, 0, null);
            case CHECK -> prepareCheck(player, decision);
            case CALL -> prepareCall(player, decision);
            case BET -> prepareBet(player, decision, activePlayers, bigBlind);
            case RAISE -> prepareRaise(player, decision, activePlayers, bigBlind);
            case ALL_IN -> prepareAllIn(player, decision, activePlayers, bigBlind);
        };
    }

    private DecisionResult prepareCheck(Player player, PlayerDecision decision) {
        int owed = amountOwed(player);
        if (owed > 0) {
            throw new UnauthorisedActionException(
                    "Cannot check when there is an active bet. You must call, raise, or fold.",
                    "CHECK_NOT_AVAILABLE", "To call: " + owed);
        }
        return new DecisionResult(decision, 0, null);
    }

    private DecisionResult prepareCall(Player player, PlayerDecision decision) {
        int owed = amountOwed(player);
        if (owed == 0) {
            throw new UnauthorisedActionException("There is no call amount owed", "CALL_NOT_AVAILABLE", "Call amount: 0");
        }
        if (owed > player.getChips()) {
            logger.info("Player {} attempted CALL for {} with only {} chips. Converting to ALL_IN",
                    player.getName(), owed, player.getChips());
            PlayerDecision appliedDecision = new PlayerDecision(PlayerAction.ALL_IN, 0, decision.playerId());
            return new DecisionResult(appliedDecision, 0,
                    "Your call was converted to all-in because the call amount exceeded your available chips.");
        }
        return new DecisionResult(decision, owed, null);
    }

    private DecisionResult prepareBet(Player player, PlayerDecision decision, List<Player> activePlayers, int bigBlind) {
        int amount = decision.amount();
        requireContestable(player, activePlayers, PlayerAction.BET);
        if (currentHighestBet > 0) {
            throw new UnauthorisedActionException(
                    "Cannot bet when there is an active bet. You must call, raise, or fold.",
                    "BET_NOT_AVAILABLE", "Active bet: " + currentHighestBet + "; call or raise");
        }
        if (amount == 0) {
            throw new BadRequestException("Bet amount must be greater than 0", "BET_AMOUNT_REQUIRED", "Bet must be positive; yours: 0");
        }
        if (amount > player.getChips()) {
            throw new BadRequestException("Bet amount cannot exceed available chips", "INSUFFICIENT_CHIPS",
                    "Available: " + player.getChips() + "; yours: " + amount);
        }
        if (amount < bigBlind && amount < player.getChips()) {
            throw new BadRequestException("Bet amount must be at least the big blind of " + bigBlind,
                    "MIN_BET", "Min bet: " + bigBlind + "; yours: " + amount);
        }
        return new DecisionResult(decision, amount, null);
    }

    private DecisionResult prepareRaise(Player player, PlayerDecision decision, List<Player> activePlayers, int bigBlind) {
        int amount = decision.amount();
        if (currentHighestBet == 0) {
            throw new UnauthorisedActionException(
                    "There is no active wager to raise", "RAISE_NOT_AVAILABLE", "Use BET when no wager is active");
        }
        requireContestable(player, activePlayers, PlayerAction.RAISE);
        int totalBetAfterRaise = player.getCurrentBet() + amount;
        requireReopenedIfExceedingHighestBet(player, bigBlind, totalBetAfterRaise);
        if (amount == 0) {
            throw new BadRequestException("Raise amount must be greater than 0", "RAISE_AMOUNT_REQUIRED", "Raise must be positive; yours: 0");
        }
        if (amount > player.getChips()) {
            throw new BadRequestException("Raise amount cannot exceed available chips", "INSUFFICIENT_CHIPS",
                    "Available: " + player.getChips() + "; yours: " + amount);
        }
        int minimumRaise = getMinimumRaiseAmount(player, bigBlind);
        if (totalBetAfterRaise <= currentHighestBet) {
            logger.debug("[Game] Invalid raise: player {} tried to raise to {}, current highest bet is {}",
                    player.getName(), totalBetAfterRaise, currentHighestBet);
            throw new BadRequestException(
                    "Raise amount must result in a bet higher than current highest bet of " + currentHighestBet
                            + ". Your current bet is " + player.getCurrentBet()
                            + ", so the minimum incremental raise is " + minimumRaise,
                    "MIN_RAISE", "Min raise: " + minimumRaise + "; yours: " + amount);
        }
        int raiseIncrement = totalBetAfterRaise - currentHighestBet;
        int fullRaise = fullRaiseSize(bigBlind);
        if (raiseIncrement < fullRaise && amount < player.getChips()) {
            throw new BadRequestException(
                    "Raise increment must be at least " + fullRaise + ". You tried to raise by " + raiseIncrement,
                    "MIN_RAISE", "Min raise: " + minimumRaise + "; yours: " + amount);
        }
        return new DecisionResult(decision, amount, null);
    }

    private DecisionResult prepareAllIn(Player player, PlayerDecision decision, List<Player> activePlayers, int bigBlind) {
        int totalBetAfterAllIn = player.getCurrentBet() + player.getChips();
        if (totalBetAfterAllIn > currentHighestBet) {
            requireContestable(player, activePlayers, PlayerAction.ALL_IN);
            requireReopenedIfExceedingHighestBet(player, bigBlind, totalBetAfterAllIn);
        }
        return new DecisionResult(decision, 0, null);
    }

    private void requireContestable(Player player, List<Player> activePlayers, PlayerAction action) {
        if (!hasOtherActionableOpponent(activePlayers, player)) {
            throw new UnauthorisedActionException("No other player can contest an additional wager",
                    action == PlayerAction.BET ? "BET_NOT_AVAILABLE" : "RAISE_NOT_AVAILABLE",
                    amountOwed(player) > 0 ? "Call or fold" : "Check or fold");
        }
    }

    private void requireReopenedIfExceedingHighestBet(Player player, int bigBlind, int totalBetAfterAction) {
        if (totalBetAfterAction > currentHighestBet && !bettingReopenedFor(player, bigBlind)) {
            throw new UnauthorisedActionException("Betting has not reopened for this player", "RAISE_NOT_REOPENED", "Call or fold");
        }
    }

    /**
     * Records a player's applied wager, updating highest bet, full-raise increment, and acted-player history.
     *
     * @param player              acting player after chips were committed
     * @param bigBlind            table big blind amount
     * @param wagerBeforeDecision highest bet before this decision was applied
     */
    void recordAppliedDecision(Player player, int bigBlind, int wagerBeforeDecision) {
        currentHighestBet = Math.max(currentHighestBet, player.getCurrentBet());
        if (player.getCurrentBet() > wagerBeforeDecision) {
            int increase = player.getCurrentBet() - wagerBeforeDecision;
            if (increase >= fullRaiseSize(bigBlind)) {
                lastFullRaise = increase;
            }
        }
        lastActedWagerByPlayer.put(player.getPlayerId(), player.getCurrentBet());
        actedPlayersInRound.add(player.getPlayerId());
    }

    /**
     * Removes round-tracking history for a departing player.
     *
     * @param playerId departing player ID
     */
    void removePlayer(String playerId) {
        actedPlayersInRound.remove(playerId);
        lastActedWagerByPlayer.remove(playerId);
    }

    /**
     * Recalculates the highest active bet when a player departure leaves at most one actionable player.
     *
     * @param activePlayers remaining active players
     */
    void reconcileHighestBetAfterRemoval(List<Player> activePlayers) {
        long actionable = activePlayers.stream()
                .filter(p -> p.canAct() && p.getChips() > 0)
                .count();
        if (actionable <= 1) {
            currentHighestBet = activePlayers.stream()
                    .filter(Player::isInHand)
                    .mapToInt(Player::getCurrentBet)
                    .max()
                    .orElse(0);
        }
    }

    /**
     * Determines how hand progression should continue immediately after blinds are posted.
     *
     * @param activePlayers active players in the hand
     * @param handOver      whether at most one non-folded player remains
     * @return forced-blind progression choice
     */
    ForcedBlindProgression afterForcedBlinds(List<Player> activePlayers, boolean handOver) {
        if (handOver) {
            return ForcedBlindProgression.HAND_OVER;
        }
        return shouldAutoRunout(activePlayers) ? ForcedBlindProgression.AUTO_RUNOUT : ForcedBlindProgression.SELECT_ACTOR;
    }

    /**
     * Marks the street as having had its initial turn once every actionable player has acted but the street is
     * not yet complete. Call this before {@link #nextProgression}, which only reports.
     *
     * @param activePlayers active players in the hand
     */
    void markInitialTurnsIfAllActed(List<Player> activePlayers) {
        if (!isComplete(activePlayers) && allActionablePlayersActed(activePlayers)) {
            allPlayersHadInitialTurn = true;
        }
    }

    /**
     * Evaluates whether the betting round is complete and selects the next hand progression step.
     * Does not change round state.
     *
     * @param activePlayers active players in the hand
     * @param handOver      whether at most one non-folded player remains
     * @param phase         current game phase
     * @return next progression outcome
     */
    TurnOutcome.TurnOutcomeType nextProgression(List<Player> activePlayers, boolean handOver, GamePhase phase) {
        boolean roundComplete = isComplete(activePlayers);
        if (handOver || (roundComplete && phase == GamePhase.RIVER)) {
            return TurnOutcome.TurnOutcomeType.SHOWDOWN;
        }
        if (shouldAutoRunout(activePlayers)) {
            return TurnOutcome.TurnOutcomeType.AUTO_ADVANCING;
        }
        if (roundComplete) {
            return TurnOutcome.TurnOutcomeType.PHASE_ADVANCED;
        }
        return TurnOutcome.TurnOutcomeType.NEXT_PLAYER;
    }

    /**
     * Returns the additional chips {@code player} must commit for a minimum legal full raise.
     *
     * @param player   seated player
     * @param bigBlind table big blind amount
     * @return minimum incremental raise amount
     */
    int getMinimumRaiseAmount(Player player, int bigBlind) {
        return amountOwed(player) + fullRaiseSize(bigBlind);
    }

    /**
     * Returns the immutable list of legal actions for {@code player}.
     *
     * @param player        prospective actor
     * @param players       all table players
     * @param activePlayers active players in the hand
     * @param seating       table seating collaborator used to resolve the active turn owner
     * @param phase         current game phase
     * @param bigBlind      table big blind amount
     * @return immutable list of legal actions, or empty if {@code player} cannot act
     */
    List<PlayerAction> legalActionsFor(
            Player player,
            List<Player> players,
            List<Player> activePlayers,
            TableSeating seating,
            GamePhase phase,
            int bigBlind) {
        if (player == null
                || !players.contains(player)
                || !player.canAct()
                || phase == GamePhase.SHOWDOWN
                || activePlayers.isEmpty()) {
            return List.of();
        }
        Player currentPlayer = seating.getCurrentPlayer(activePlayers);
        if (currentPlayer == null || !player.getPlayerId().equals(currentPlayer.getPlayerId())) {
            return List.of();
        }
        List<PlayerAction> actions = new ArrayList<>(List.of(PlayerAction.FOLD));
        int owed = amountOwed(player);
        actions.add(owed == 0 ? PlayerAction.CHECK : PlayerAction.CALL);
        if (hasOtherActionableOpponent(activePlayers, player)
                && bettingReopenedFor(player, bigBlind)
                && player.getChips() > owed) {
            actions.add(currentHighestBet == 0 ? PlayerAction.BET : PlayerAction.RAISE);
            actions.add(PlayerAction.ALL_IN);
        } else if (player.getChips() > 0 && player.getChips() <= owed) {
            actions.add(PlayerAction.ALL_IN);
        }
        return List.copyOf(actions);
    }

    /**
     * Returns whether {@code player} cannot act further in this hand (folded, out, or all-in).
     *
     * @param player player to inspect
     * @return {@code true} if turn rotation should advance past this player
     */
    static boolean needsNextActor(Player player) {
        return !player.canAct();
    }

    /**
     * Returns whether any non-folded, non-out, non-all-in player still owes chips to match {@code highestBet}.
     *
     * @param activePlayers players active in the hand
     * @param highestBet    current highest street bet
     * @return {@code true} if at least one actionable player has wagered less than {@code highestBet}
     */
    static boolean hasActionablePlayerOwingCall(List<Player> activePlayers, int highestBet) {
        return activePlayers.stream()
                .anyMatch(p -> p.canAct() && p.getCurrentBet() < highestBet);
    }

    int getCurrentHighestBet() {
        return currentHighestBet;
    }

    boolean hasAllPlayersHadInitialTurn() {
        return allPlayersHadInitialTurn;
    }

    Set<String> getActedPlayerIdsSnapshot() {
        return Set.copyOf(actedPlayersInRound);
    }

    int getLastFullRaise() {
        return lastFullRaise;
    }

    Map<String, Integer> getLastActedWagerByPlayerSnapshot() {
        return Map.copyOf(lastActedWagerByPlayer);
    }

    /** Chips {@code player} must add to match the highest bet this street. */
    private int amountOwed(Player player) {
        return Math.max(0, currentHighestBet - player.getCurrentBet());
    }

    /** Size of the smallest full raise this street: the last full raise, or the big blind if none yet. */
    private int fullRaiseSize(int bigBlind) {
        return lastFullRaise > 0 ? lastFullRaise : bigBlind;
    }

    /** Whether {@code player} may raise: false after only a short all-in raise since they last acted. */
    private boolean bettingReopenedFor(Player player, int bigBlind) {
        Integer actedAt = lastActedWagerByPlayer.get(player.getPlayerId());
        return actedAt == null || currentHighestBet - actedAt >= fullRaiseSize(bigBlind);
    }

    private static boolean hasOtherActionableOpponent(List<Player> activePlayers, Player player) {
        return activePlayers.stream()
                .anyMatch(p -> p != player && p.canAct() && p.getChips() > 0);
    }

    private boolean allActionablePlayersActed(List<Player> activePlayers) {
        List<Player> actionable = activePlayers.stream()
                .filter(Player::canAct)
                .toList();
        return !actionable.isEmpty() && actionable.stream().allMatch(p -> actedPlayersInRound.contains(p.getPlayerId()));
    }

    private boolean shouldAutoRunout(List<Player> activePlayers) {
        long actionable = activePlayers.stream()
                .filter(Player::canAct)
                .count();
        return actionable <= 1 && !hasActionablePlayerOwingCall(activePlayers, currentHighestBet);
    }

    private boolean isComplete(List<Player> activePlayers) {
        boolean owed = hasActionablePlayerOwingCall(activePlayers, currentHighestBet);
        long actionable = activePlayers.stream()
                .filter(Player::canAct)
                .count();
        return (actionable <= 1 || allPlayersHadInitialTurn) && !owed;
    }

    /** Outcome of normalizing a requested player decision against stack and bet limits. */
    record DecisionResult(PlayerDecision decision, int amount, String conversionMessage) {
    }

    /** Next hand-state transition required after forced preflop blinds leave some or all players all-in. */
    enum ForcedBlindProgression {
        HAND_OVER,
        AUTO_RUNOUT,
        SELECT_ACTOR
    }
}
