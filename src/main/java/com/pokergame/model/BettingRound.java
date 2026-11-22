package com.pokergame.model;

import com.pokergame.enums.GamePhase;
import com.pokergame.enums.PlayerAction;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.UnauthorisedActionException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks the current betting round for a {@link Game}: which actions a player may take,
 * whether a chosen action and bet amount are legal, and when the round is finished.
 */
final class BettingRound {
    private static final Logger logger = LoggerFactory.getLogger(BettingRound.class);

    private int currentHighestBet;
    private boolean allPlayersHadInitialTurn;
    private final Set<String> actedPlayersInRound;
    // Size of the last full raise in this round, or 0 when none has been made yet (the big blind applies).
    private int lastFullRaise;
    private final Map<String, Integer> previousBets;

    /** Creates fresh betting-round state for a new game. */
    BettingRound() {
        this.actedPlayersInRound = new HashSet<>();
        this.previousBets = new HashMap<>();
    }

    /**
     * Reconstructs betting-round state from saved snapshot fields.
     *
     * @param currentHighestBet        highest bet in the current round
     * @param allPlayersHadInitialTurn whether every active player has had at least one turn in this round
     * @param actedPlayersInRound      IDs of players who have acted in the current round
     * @param lastFullRaise            last full-raise increment in this round, or 0 if none has been made yet
     * @param previousBets             each player's total bet in this round after their most recent turn
     */
    BettingRound(
            int currentHighestBet,
            boolean allPlayersHadInitialTurn,
            Set<String> actedPlayersInRound,
            int lastFullRaise,
            Map<String, Integer> previousBets) {
        this.currentHighestBet = currentHighestBet;
        this.allPlayersHadInitialTurn = allPlayersHadInitialTurn;
        this.actedPlayersInRound = actedPlayersInRound != null ? new HashSet<>(actedPlayersInRound) : new HashSet<>();
        this.lastFullRaise = lastFullRaise;
        this.previousBets = previousBets != null ? new HashMap<>(previousBets) : new HashMap<>();
    }

    /**
     * Resets all betting state for the start of a new hand.
     */
    void resetForNewHand() {
        currentHighestBet = 0;
        allPlayersHadInitialTurn = false;
        actedPlayersInRound.clear();
        lastFullRaise = 0;
        previousBets.clear();
    }

    /** Forgets the round's last full raise, so the minimum raise falls back to the big blind (used at showdown). */
    void clearLastFullRaise() {
        lastFullRaise = 0;
    }

    void clearActedPlayers() {
        actedPlayersInRound.clear();
    }

    /**
     * Resets per-player bets and round-tracking state when advancing to a new betting round.
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
        previousBets.clear();
    }

    /**
     * Validates a player's betting decision without changing player or round state,
     * returning the resolved action, chip amount, and any player notification.
     *
     * @param player        acting player
     * @param action        requested poker action
     * @param amount        requested wager amount (0 for non-wagering actions)
     * @param activePlayers active players in the hand
     * @param bigBlind      table big blind amount
     * @return validated action, chip amount to apply, and optional player notice
     * @throws BadRequestException         when {@code action} is {@code null}, {@code amount < 0},
     *                                     or betting rules reject the action
     * @throws UnauthorisedActionException when {@code player} cannot act in the current hand
     */
    ValidatedDecision validateDecision(
            Player player, PlayerAction action, int amount, List<Player> activePlayers, int bigBlind) {
        if (action == null) {
            throw new BadRequestException("No action specified. Please select an action");
        }
        if (amount < 0) {
            throw new BadRequestException("Amount must be positive");
        }
        if (player == null || !player.canAct()) {
            throw new UnauthorisedActionException(
                    "Player cannot act in the current hand", "ACTION_NOT_AVAILABLE", "Player is not eligible to act");
        }
        return switch (action) {
            case FOLD -> new ValidatedDecision(PlayerAction.FOLD, 0, null);
            case CHECK -> validateCheck(player);
            case CALL -> validateCall(player);
            case BET -> validateBet(player, amount, activePlayers, bigBlind);
            case RAISE -> validateRaise(player, amount, activePlayers, bigBlind);
            case ALL_IN -> validateAllIn(player, activePlayers, bigBlind);
        };
    }

    /**
     * Records a player's applied bet, updating the highest bet, full-raise increment, and turn history.
     *
     * @param player                   acting player after chips were committed
     * @param bigBlind                 table big blind amount
     * @param highestBetBeforeDecision highest bet before this decision was applied
     */
    void recordAppliedDecision(Player player, int bigBlind, int highestBetBeforeDecision) {
        currentHighestBet = Math.max(currentHighestBet, player.getCurrentBet());
        if (player.getCurrentBet() > highestBetBeforeDecision) {
            int increase = player.getCurrentBet() - highestBetBeforeDecision;
            if (increase >= fullRaiseSize(bigBlind)) {
                lastFullRaise = increase;
            }
        }
        previousBets.put(player.getPlayerId(), player.getCurrentBet());
        actedPlayersInRound.add(player.getPlayerId());
    }

    /**
     * Removes betting-round history for a player who left the game.
     *
     * @param playerId ID of the removed player
     */
    void removePlayer(String playerId) {
        actedPlayersInRound.remove(playerId);
        previousBets.remove(playerId);
    }

    /**
     * Recalculates the highest bet after a player is removed when at most one remaining player can still act.
     *
     * @param activePlayers remaining active players
     */
    void recalculateHighestBetAfterRemoval(List<Player> activePlayers) {
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
     * Marks the betting round as having had its initial turn once every player who can act has acted
     * but the round is not yet complete. Call this before {@link #nextProgression}, which only reports.
     *
     * @param activePlayers active players in the hand
     */
    void markInitialTurnsIfAllActed(List<Player> activePlayers) {
        if (!isComplete(activePlayers) && allPlayersWhoCanActHaveActed(activePlayers)) {
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
        if (shouldAutoAdvance(activePlayers)) {
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
     * @param player        player to check
     * @param players       all table players
     * @param activePlayers active players in the hand
     * @param seating       table seating collaborator used to find the player whose turn it currently is
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
        if (!player.getPlayerId().equals(currentPlayer.getPlayerId())) {
            return List.of();
        }
        List<PlayerAction> actions = new ArrayList<>(List.of(PlayerAction.FOLD));
        int owed = amountOwed(player);
        actions.add(owed == 0 ? PlayerAction.CHECK : PlayerAction.CALL);
        if (hasOpponentWhoCanAct(activePlayers, player)
                && canPlayerRaise(player, bigBlind)
                && player.getChips() > owed) {
            actions.add(currentHighestBet == 0 ? PlayerAction.BET : PlayerAction.RAISE);
            actions.add(PlayerAction.ALL_IN);
        } else if (player.getChips() > 0 && player.getChips() <= owed) {
            actions.add(PlayerAction.ALL_IN);
        }
        return List.copyOf(actions);
    }

    /**
     * Returns whether any player who can still act has bet less than {@code highestBet} in this round.
     *
     * @param activePlayers players active in the hand
     * @param highestBet    current highest bet in the round
     * @return {@code true} if at least one player who can act still needs to call, raise, or fold
     */
    static boolean anyPlayerNeedsToCall(List<Player> activePlayers, int highestBet) {
        return activePlayers.stream()
                .anyMatch(p -> p.canAct() && p.getCurrentBet() < highestBet);
    }

    private ValidatedDecision validateCheck(Player player) {
        int owed = amountOwed(player);
        if (owed > 0) {
            throw new UnauthorisedActionException(
                    "Cannot check when there is an active bet. You must call, raise, or fold.",
                    "CHECK_NOT_AVAILABLE", "To call: " + owed);
        }
        return new ValidatedDecision(PlayerAction.CHECK, 0, null);
    }

    private ValidatedDecision validateCall(Player player) {
        int owed = amountOwed(player);
        if (owed == 0) {
            throw new UnauthorisedActionException("There is no call amount owed", "CALL_NOT_AVAILABLE", "Call amount: 0");
        }
        if (owed > player.getChips()) {
            logger.info("Player {} attempted CALL for {} with only {} chips. Converting to ALL_IN",
                    player.getName(), owed, player.getChips());
            return new ValidatedDecision(PlayerAction.ALL_IN, 0,
                    "Your call was converted to all-in because the call amount exceeded your available chips.");
        }
        return new ValidatedDecision(PlayerAction.CALL, owed, null);
    }

    private ValidatedDecision validateBet(Player player, int amount, List<Player> activePlayers, int bigBlind) {
        requireOpponentWhoCanAct(player, activePlayers, PlayerAction.BET);
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
        return new ValidatedDecision(PlayerAction.BET, amount, null);
    }

    private ValidatedDecision validateRaise(Player player, int amount, List<Player> activePlayers, int bigBlind) {
        if (currentHighestBet == 0) {
            throw new UnauthorisedActionException(
                    "There is no active wager to raise", "RAISE_NOT_AVAILABLE", "Use BET when no wager is active");
        }
        requireOpponentWhoCanAct(player, activePlayers, PlayerAction.RAISE);
        requirePlayerAllowedToRaise(player, bigBlind);
        if (amount == 0) {
            throw new BadRequestException("Raise amount must be greater than 0", "RAISE_AMOUNT_REQUIRED", "Raise must be positive; yours: 0");
        }
        if (amount > player.getChips()) {
            throw new BadRequestException("Raise amount cannot exceed available chips", "INSUFFICIENT_CHIPS",
                    "Available: " + player.getChips() + "; yours: " + amount);
        }
        int totalBetAfterRaise = player.getCurrentBet() + amount;
        int minimumRaise = getMinimumRaiseAmount(player, bigBlind);
        if (totalBetAfterRaise <= currentHighestBet) {
            logger.debug("Invalid raise: player {} tried to raise to {}, current highest bet is {}",
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
        return new ValidatedDecision(PlayerAction.RAISE, amount, null);
    }

    private ValidatedDecision validateAllIn(Player player, List<Player> activePlayers, int bigBlind) {
        int totalBetAfterAllIn = player.getCurrentBet() + player.getChips();
        if (totalBetAfterAllIn > currentHighestBet) {
            requireOpponentWhoCanAct(player, activePlayers, PlayerAction.ALL_IN);
            requirePlayerAllowedToRaise(player, bigBlind);
        }
        return new ValidatedDecision(PlayerAction.ALL_IN, 0, null);
    }

    // A player cannot bet or raise when every remaining opponent is already all-in.
    private void requireOpponentWhoCanAct(Player player, List<Player> activePlayers, PlayerAction action) {
        if (hasOpponentWhoCanAct(activePlayers, player)) {
            return;
        }
        String errorCode = (action == PlayerAction.BET) ? "BET_NOT_AVAILABLE" : "RAISE_NOT_AVAILABLE";
        String allowedActionsHint = (amountOwed(player) > 0) ? "Call or fold" : "Check or fold";
        throw new UnauthorisedActionException(
                "No other player can contest an additional wager", errorCode, allowedActionsHint);
    }

    private void requirePlayerAllowedToRaise(Player player, int bigBlind) {
        if (!canPlayerRaise(player, bigBlind)) {
            throw new UnauthorisedActionException(
                    "Betting has not reopened for this player", "RAISE_NOT_REOPENED", "Call or fold");
        }
    }

    // Chips player must add to match the highest bet in this round.
    private int amountOwed(Player player) {
        return Math.max(0, currentHighestBet - player.getCurrentBet());
    }

    // Size of the smallest full raise in this round: the last full raise, or the big blind if none yet.
    private int fullRaiseSize(int bigBlind) {
        return lastFullRaise > 0 ? lastFullRaise : bigBlind;
    }

    // A player who already acted this round may only raise again if subsequent bets
    // increased the highest bet by at least one full raise since their last turn.
    private boolean canPlayerRaise(Player player, int bigBlind) {
        Integer betAtLastTurn = previousBets.get(player.getPlayerId());
        if (betAtLastTurn == null) {
            return true;
        }
        int increaseSinceLastTurn = currentHighestBet - betAtLastTurn;
        return increaseSinceLastTurn >= fullRaiseSize(bigBlind);
    }

    private static boolean hasOpponentWhoCanAct(List<Player> activePlayers, Player player) {
        return activePlayers.stream()
                .anyMatch(p -> p != player && p.canAct() && p.getChips() > 0);
    }

    private boolean allPlayersWhoCanActHaveActed(List<Player> activePlayers) {
        List<Player> canActPlayers = activePlayers.stream()
                .filter(Player::canAct)
                .toList();
        return !canActPlayers.isEmpty() && canActPlayers.stream().allMatch(p -> actedPlayersInRound.contains(p.getPlayerId()));
    }

    private boolean shouldAutoAdvance(List<Player> activePlayers) {
        long canActCount = activePlayers.stream()
                .filter(Player::canAct)
                .count();
        return canActCount <= 1 && !anyPlayerNeedsToCall(activePlayers, currentHighestBet);
    }

    private boolean isComplete(List<Player> activePlayers) {
        boolean owed = anyPlayerNeedsToCall(activePlayers, currentHighestBet);
        long canActCount = activePlayers.stream()
                .filter(Player::canAct)
                .count();
        return (canActCount <= 1 || allPlayersHadInitialTurn) && !owed;
    }

    int getCurrentHighestBet() {
        return currentHighestBet;
    }

    void setHighestBet(int highestBet) {
        this.currentHighestBet = highestBet;
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

    Map<String, Integer> getPreviousBetsSnapshot() {
        return Map.copyOf(previousBets);
    }

    /** Validated player action with the chip amount to apply and optional notice for the player. */
    record ValidatedDecision(PlayerAction action, int amount, String playerActionNotice) {
    }
}
