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
 */
final class BettingRound {
    private static final Logger logger = LoggerFactory.getLogger(BettingRound.class);

    private int currentHighestBet;
    private boolean allPlayersHadInitialTurn;
    private final Set<String> actedPlayersInRound;
    private final Map<String, Integer> lastFullRaiseByStreet;
    private final Map<String, Integer> lastActedWagerByPlayer;

    /** Creates fresh betting-round state for a new game. */
    BettingRound() {
        this.currentHighestBet = 0;
        this.allPlayersHadInitialTurn = false;
        this.actedPlayersInRound = new HashSet<>();
        this.lastFullRaiseByStreet = new HashMap<>();
        this.lastActedWagerByPlayer = new HashMap<>();
    }

    /**
     * Reconstructs betting-round state from saved snapshot fields.
     *
     * @param currentHighestBet       highest street bet facing players
     * @param allPlayersHadInitialTurn whether every active player has had at least one turn this street
     * @param actedPlayersInRound     IDs of players who have acted in the current street
     * @param lastFullRaiseByStreet   minimum full-raise increment recorded per street name
     * @param lastActedWagerByPlayer  last total street wager committed by each player ID
     */
    BettingRound(
            int currentHighestBet,
            boolean allPlayersHadInitialTurn,
            Set<String> actedPlayersInRound,
            Map<String, Integer> lastFullRaiseByStreet,
            Map<String, Integer> lastActedWagerByPlayer) {
        this.currentHighestBet = currentHighestBet;
        this.allPlayersHadInitialTurn = allPlayersHadInitialTurn;
        this.actedPlayersInRound = actedPlayersInRound != null ? new HashSet<>(actedPlayersInRound) : new HashSet<>();
        this.lastFullRaiseByStreet = lastFullRaiseByStreet != null ? new HashMap<>(lastFullRaiseByStreet) : new HashMap<>();
        this.lastActedWagerByPlayer = lastActedWagerByPlayer != null ? new HashMap<>(lastActedWagerByPlayer) : new HashMap<>();
    }

    /**
     * Resets all betting state for the start of a new hand.
     */
    void resetForNewHand() {
        currentHighestBet = 0;
        allPlayersHadInitialTurn = false;
        actedPlayersInRound.clear();
        lastFullRaiseByStreet.clear();
        lastActedWagerByPlayer.clear();
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
     * @param phase         current game phase
     * @param bigBlind      table big blind amount
     * @return validated decision, chip amount to apply, and optional conversion notice
     */
    DecisionResult prepareDecision(
            Player player,
            PlayerDecision decision,
            List<Player> activePlayers,
            GamePhase phase,
            int bigBlind) {
        PlayerDecision actualDecision = decision;
        String conversionMessage = null;

        if (player == null || !player.canAct()) {
            throw new UnauthorisedActionException(
                    "Player cannot act in the current hand", "ACTION_NOT_AVAILABLE", "Player is not eligible to act");
        }

        int lastFullRaise = getLastFullRaise(phase, bigBlind);
        int callDifference = callDifference(player, currentHighestBet);
        boolean aggressionContestable = hasOtherActionableOpponent(activePlayers, player);
        if (actualDecision.action() == PlayerAction.CALL && callDifference == 0) {
            throw new UnauthorisedActionException("There is no call amount owed", "CALL_NOT_AVAILABLE", "Call amount: 0");
        }
        if (actualDecision.action() == PlayerAction.RAISE && currentHighestBet == 0) {
            throw new UnauthorisedActionException(
                    "There is no active wager to raise", "RAISE_NOT_AVAILABLE", "Use BET when no wager is active");
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
                && !raiseOpenTo(player, lastActedWagerByPlayer, currentHighestBet, lastFullRaise)
                && player.getCurrentBet() + (actualDecision.action() == PlayerAction.ALL_IN
                        ? player.getChips()
                        : actualDecision.amount()) > currentHighestBet) {
            throw new UnauthorisedActionException("Betting has not reopened for this player", "RAISE_NOT_REOPENED", "Call or fold");
        }

        if (actualDecision.action() == PlayerAction.RAISE) {
            if (actualDecision.amount() == 0) {
                throw new BadRequestException("Raise amount must be greater than 0", "RAISE_AMOUNT_REQUIRED", "Raise must be positive; yours: 0");
            }
            if (actualDecision.amount() > player.getChips()) {
                throw new BadRequestException("Raise amount cannot exceed available chips", "INSUFFICIENT_CHIPS",
                        "Available: " + player.getChips() + "; yours: " + actualDecision.amount());
            }
            int totalBetAfterRaise = player.getCurrentBet() + actualDecision.amount();
            int minimumRaise = callDifference + lastFullRaise;
            if (totalBetAfterRaise <= currentHighestBet) {
                logger.warn("[Game] Invalid raise: player {} tried to raise to {}, current highest bet is {}",
                        player.getName(), totalBetAfterRaise, currentHighestBet);
                throw new BadRequestException(
                        "Raise amount must result in a bet higher than current highest bet of " + currentHighestBet
                                + ". Your current bet is " + player.getCurrentBet()
                                + ", so the minimum incremental raise is " + minimumRaise,
                        "MIN_RAISE", "Min raise: " + minimumRaise + "; yours: " + actualDecision.amount());
            }
            int raiseIncrement = totalBetAfterRaise - currentHighestBet;
            if (raiseIncrement < lastFullRaise && actualDecision.amount() < player.getChips()) {
                throw new BadRequestException(
                        "Raise increment must be at least " + lastFullRaise + ". You tried to raise by " + raiseIncrement,
                        "MIN_RAISE", "Min raise: " + minimumRaise + "; yours: " + actualDecision.amount());
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
                throw new BadRequestException("Bet amount cannot exceed available chips", "INSUFFICIENT_CHIPS",
                        "Available: " + player.getChips() + "; yours: " + actualDecision.amount());
            }
            if (actualDecision.amount() < bigBlind && actualDecision.amount() < player.getChips()) {
                throw new BadRequestException("Bet amount must be at least the big blind of " + bigBlind,
                        "MIN_BET", "Min bet: " + bigBlind + "; yours: " + actualDecision.amount());
            }
        }

        if (actualDecision.action() == PlayerAction.CHECK && currentHighestBet > player.getCurrentBet()) {
            throw new UnauthorisedActionException(
                    "Cannot check when there is an active bet. You must call, raise, or fold.",
                    "CHECK_NOT_AVAILABLE", "To call: " + (currentHighestBet - player.getCurrentBet()));
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

        int amount = switch (actualDecision.action()) {
            case CALL -> currentHighestBet - player.getCurrentBet();
            case BET, RAISE -> actualDecision.amount();
            default -> 0;
        };
        return new DecisionResult(actualDecision, amount, conversionMessage);
    }

    /**
     * Records a player's applied wager, updating highest bet, full-raise increment, and acted-player history.
     *
     * @param player              acting player after chips were committed
     * @param phase               current game phase
     * @param bigBlind            table big blind amount
     * @param wagerBeforeDecision highest bet before this decision was applied
     */
    void recordAppliedDecision(Player player, GamePhase phase, int bigBlind, int wagerBeforeDecision) {
        if (player.getCurrentBet() > currentHighestBet) {
            currentHighestBet = player.getCurrentBet();
        }
        if (player.getCurrentBet() > wagerBeforeDecision) {
            int increase = player.getCurrentBet() - wagerBeforeDecision;
            if (increase >= getLastFullRaise(phase, bigBlind)) {
                lastFullRaiseByStreet.put(phase.name(), increase);
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
            return ForcedBlindProgression.RECONCILE;
        }
        return shouldAutoRunout(activePlayers) ? ForcedBlindProgression.AUTO_RUNOUT : ForcedBlindProgression.SELECT_ACTOR;
    }

    /**
     * Evaluates whether the betting round is complete and selects the next hand progression step.
     *
     * @param activePlayers active players in the hand
     * @param handOver      whether at most one non-folded player remains
     * @param phase         current game phase
     * @return next progression outcome
     */
    TurnOutcome.TurnOutcomeType nextProgression(List<Player> activePlayers, boolean handOver, GamePhase phase) {
        boolean roundComplete = isComplete(activePlayers);
        if (allActionablePlayersActed(activePlayers) && !roundComplete) {
            allPlayersHadInitialTurn = true;
            roundComplete = isComplete(activePlayers);
        }
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
     * @param phase    current game phase
     * @param bigBlind table big blind amount
     * @return minimum incremental raise amount
     */
    int getMinimumRaiseAmount(Player player, GamePhase phase, int bigBlind) {
        return callDifference(player, currentHighestBet) + getLastFullRaise(phase, bigBlind);
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
        int call = callDifference(player, currentHighestBet);
        actions.add(call == 0 ? PlayerAction.CHECK : PlayerAction.CALL);
        int lastFullRaise = getLastFullRaise(phase, bigBlind);
        if (hasOtherActionableOpponent(activePlayers, player)
                && raiseOpenTo(player, lastActedWagerByPlayer, currentHighestBet, lastFullRaise)
                && player.getChips() > call) {
            actions.add(currentHighestBet == 0 ? PlayerAction.BET : PlayerAction.RAISE);
            actions.add(PlayerAction.ALL_IN);
        } else if (player.getChips() > 0 && player.getChips() <= call) {
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
        if (activePlayers == null) {
            return false;
        }
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

    Map<String, Integer> getLastFullRaiseByStreetSnapshot() {
        return Map.copyOf(lastFullRaiseByStreet);
    }

    Map<String, Integer> getLastActedWagerByPlayerSnapshot() {
        return Map.copyOf(lastActedWagerByPlayer);
    }

    private int getLastFullRaise(GamePhase phase, int bigBlind) {
        return lastFullRaiseByStreet.getOrDefault(phase.name(), bigBlind);
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

    private static int callDifference(Player player, int highestBet) {
        return Math.max(0, highestBet - player.getCurrentBet());
    }

    private static boolean raiseOpenTo(Player player, Map<String, Integer> actedWagers, int highestBet, int lastRaise) {
        Integer actedAt = actedWagers.get(player.getPlayerId());
        return actedAt == null || highestBet - actedAt >= lastRaise;
    }

    private static boolean hasOtherActionableOpponent(List<Player> activePlayers, Player player) {
        return activePlayers.stream()
                .anyMatch(p -> p != player && p.canAct() && p.getChips() > 0);
    }

    /** Outcome of normalizing a requested player decision against stack and bet limits. */
    record DecisionResult(PlayerDecision decision, int amount, String conversionMessage) {
    }

    /** Next hand-state transition required after forced preflop blinds leave some or all players all-in. */
    enum ForcedBlindProgression {
        RECONCILE,
        AUTO_RUNOUT,
        SELECT_ACTOR
    }
}
