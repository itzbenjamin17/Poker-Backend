package com.pokergame.model;

import com.pokergame.dto.internal.PlayerDecision;
import com.pokergame.dto.internal.TurnOutcome;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.UnauthorisedActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.PlayerAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Stateless betting rules; the game retains ownership of all mutable state. */
final class BettingRound {
    private static final Logger logger = LoggerFactory.getLogger(BettingRound.class);
    private BettingRound() {}

    record DecisionResult(PlayerDecision decision, int amount, String conversionMessage) {}

    enum ForcedBlindProgression { RECONCILE, AUTO_RUNOUT, SELECT_ACTOR }

    /** Determines how to continue after the forced wagers have been posted. */
    static ForcedBlindProgression afterForcedBlinds(boolean handOver, boolean autoRunout) {
        if (handOver) return ForcedBlindProgression.RECONCILE;
        return autoRunout ? ForcedBlindProgression.AUTO_RUNOUT : ForcedBlindProgression.SELECT_ACTOR;
    }

    static boolean needsNextActor(Player player) {
        return player.getHasFolded() || player.getIsOut() || player.getIsAllIn();
    }

    /** Selects the next progression without performing the associated mutation. */
    static TurnOutcome.TurnOutcomeType progression(boolean handOver, boolean roundComplete,
                                                   GamePhase phase, boolean autoRunout) {
        if (handOver || (roundComplete && phase == GamePhase.RIVER)) return TurnOutcome.TurnOutcomeType.SHOWDOWN;
        if (autoRunout) return TurnOutcome.TurnOutcomeType.AUTO_ADVANCING;
        if (roundComplete) return TurnOutcome.TurnOutcomeType.PHASE_ADVANCED;
        return TurnOutcome.TurnOutcomeType.NEXT_PLAYER;
    }

    static boolean allActionablePlayersActed(List<Player> activePlayers, java.util.Set<String> actedPlayers) {
        List<Player> actionable = activePlayers.stream()
                .filter(p -> !p.getHasFolded() && !p.getIsOut() && !p.getIsAllIn()).toList();
        return !actionable.isEmpty() && actionable.stream().allMatch(p -> actedPlayers.contains(p.getPlayerId()));
    }

    static int highestBetAfterRemoval(List<Player> activePlayers, int currentHighestBet) {
        long actionable = activePlayers.stream().filter(p -> !p.getHasFolded() && !p.getIsOut()
                && !p.getIsAllIn() && p.getChips() > 0).count();
        return actionable <= 1 ? activePlayers.stream().filter(p -> !p.getHasFolded() && !p.getIsOut())
                .mapToInt(Player::getCurrentBet).max().orElse(0) : currentHighestBet;
    }

    /** Validates and prepares a decision without changing the player or game. */
    static DecisionResult prepareDecision(Player player, PlayerDecision decision, List<Player> activePlayers,
                                          int currentHighestBet, int bigBlind, Map<String, Integer> actedWagers,
                                          int lastFullRaise) {
        PlayerDecision actualDecision = decision;
        String conversionMessage = null;

        if (player == null || player.getHasFolded() || player.getIsOut() || player.getIsAllIn()) {
            throw new UnauthorisedActionException("Player cannot act in the current hand", "ACTION_NOT_AVAILABLE", "Player is not eligible to act");
        }

        int callDifference = Math.max(0, currentHighestBet - player.getCurrentBet());
        boolean aggressionContestable = hasOtherActionableOpponent(activePlayers, player);
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
                && !raiseOpenTo(player, actedWagers, currentHighestBet, lastFullRaise)
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
                int minimumRaise = callDifference + lastFullRaise;
                logger.warn("[Game] Invalid raise: player {} tried to raise to {}, current highest bet is {}",
                        player.getName(), totalBetAfterRaise, currentHighestBet);
                throw new UnauthorisedActionException(
                        "Raise amount must result in a bet higher than current highest bet of " + currentHighestBet +
                                ". Your current bet is " + player.getCurrentBet() +
                                ", so the minimum incremental raise is " + minimumRaise,
                        "MIN_RAISE", "Min raise: " + minimumRaise + "; yours: " + actualDecision.amount());
            }

            int raiseIncrement = totalBetAfterRaise - currentHighestBet;
            int minimumRaise = callDifference + lastFullRaise;
            if (raiseIncrement < lastFullRaise && actualDecision.amount() < player.getChips()) {
                throw new BadRequestException(
                        "Raise increment must be at least " + lastFullRaise +
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

        int amount = switch (actualDecision.action()) {
            case CALL -> currentHighestBet - player.getCurrentBet();
            case BET, RAISE -> actualDecision.amount();
            default -> 0;
        };
        return new DecisionResult(actualDecision, amount, conversionMessage);
    }

    static int lastFullRaise(Map<String, Integer> raises, GamePhase phase, int bigBlind) {
        return raises.getOrDefault(phase.name(), bigBlind);
    }

    static int callDifference(Player player, int highestBet) {
        return Math.max(0, highestBet - player.getCurrentBet());
    }

    static boolean raiseOpenTo(Player player, Map<String, Integer> actedWagers, int highestBet, int lastRaise) {
        Integer actedAt = actedWagers.get(player.getPlayerId());
        return actedAt == null || highestBet - actedAt >= lastRaise;
    }

    static boolean hasOtherActionableOpponent(List<Player> activePlayers, Player player) {
        return activePlayers.stream().anyMatch(p -> p != player && !p.getHasFolded() && !p.getIsOut()
                && !p.getIsAllIn() && p.getChips() > 0);
    }

    static boolean hasActionablePlayerOwingCall(List<Player> activePlayers, int highestBet) {
        return activePlayers.stream().anyMatch(p -> !p.getHasFolded() && !p.getIsOut() && !p.getIsAllIn()
                && p.getCurrentBet() < highestBet);
    }

    static boolean shouldAutoRunout(List<Player> activePlayers, int highestBet) {
        long actionable = activePlayers.stream().filter(p -> !p.getHasFolded() && !p.getIsOut() && !p.getIsAllIn()).count();
        return actionable <= 1 && !hasActionablePlayerOwingCall(activePlayers, highestBet);
    }

    static boolean isComplete(List<Player> activePlayers, int highestBet, boolean everyoneHasHadInitialTurn) {
        boolean owed = hasActionablePlayerOwingCall(activePlayers, highestBet);
        long actionable = activePlayers.stream().filter(p -> !p.getHasFolded() && !p.getIsOut() && !p.getIsAllIn()).count();
        return (actionable <= 1 || everyoneHasHadInitialTurn) && !owed;
    }

    static List<PlayerAction> legalActions(Player player, List<Player> players, List<Player> activePlayers,
                                           Player currentPlayer, GamePhase phase, int highestBet,
                                           Map<String, Integer> actedWagers, int lastRaise) {
        if (player == null || !players.contains(player) || player.getHasFolded() || player.getIsOut() || player.getIsAllIn()
                || phase == GamePhase.SHOWDOWN || currentPlayer == null
                || !player.getPlayerId().equals(currentPlayer.getPlayerId())) return List.of();
        List<PlayerAction> actions = new ArrayList<>(List.of(PlayerAction.FOLD));
        int call = callDifference(player, highestBet);
        actions.add(call == 0 ? PlayerAction.CHECK : PlayerAction.CALL);
        if (hasOtherActionableOpponent(activePlayers, player) && raiseOpenTo(player, actedWagers, highestBet, lastRaise)
                && player.getChips() > call) {
            actions.add(highestBet == 0 ? PlayerAction.BET : PlayerAction.RAISE);
            actions.add(PlayerAction.ALL_IN);
        } else if (player.getChips() > 0 && player.getChips() <= call) {
            actions.add(PlayerAction.ALL_IN);
        }
        return List.copyOf(actions);
    }
}
