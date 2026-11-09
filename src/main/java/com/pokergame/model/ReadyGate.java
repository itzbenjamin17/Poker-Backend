package com.pokergame.model;

import com.pokergame.enums.ReadyGateState;
import com.pokergame.exception.UnauthorisedActionException;

import java.util.List;
import java.util.Objects;

/**
 * Pure state calculation and transition logic for inter-hand player readiness,
 * countdown timers, and hand initiation gating.
 * Package-private collaborator extracted from {@link Game}.
 */
final class ReadyGate {

    private ReadyGate() {
        // Utility collaborator class, prevent instantiation
    }

    static void setPlayerReady(
            List<Player> tablePlayers, boolean readyCountdownActive, String playerName, boolean ready) {
        if (!readyCountdownActive) {
            throw new UnauthorisedActionException("Ready countdown is not active.");
        }
        Player player = Game.findPlayerByName(tablePlayers, playerName);
        if (player == null) {
            throw new UnauthorisedActionException("You are no longer part of this game.");
        }
        if (player.getIsOut()) {
            throw new UnauthorisedActionException("You are out of this game.");
        }
        if (ready) {
            if (player.getIsDisconnected()) {
                throw new UnauthorisedActionException("Reconnect before marking ready.");
            }
            if (player.getChips() <= 0) {
                throw new UnauthorisedActionException("Players without chips cannot mark ready.");
            }
        } else if (player.getIsDisconnected() || player.getChips() <= 0) {
            throw new UnauthorisedActionException("You are not eligible to change readiness.");
        }
        player.setReadyForNextHand(ready);
    }

    /**
     * Container representing the result of a ready-gate state transition.
     *
     * @param active whether the countdown timer is active
     * @param deadlineEpochMs epoch millisecond deadline for the countdown, or null if inactive
     * @param state resulting gate state enum
     */
    record ReadyGateStateTransition(boolean active, Long deadlineEpochMs, ReadyGateState state) {
        ReadyGateStateTransition {
            if (state == null) {
                state = active ? ReadyGateState.COUNTING_DOWN : ReadyGateState.CLOSED;
            }
        }
    }

    /**
     * Opens the ready countdown gate between hands, establishes a deadline, and
     * resets all table players' ready flags to false.
     *
     * @param tablePlayers list of all seated table players
     * @param deadlineEpochMs UTC epoch milliseconds when countdown expires
     * @return resulting gate transition state
     */
    static ReadyGateStateTransition openCountdown(List<Player> tablePlayers, long deadlineEpochMs) {
        if (tablePlayers != null) {
            for (Player player : tablePlayers) {
                if (player != null) {
                    player.setReadyForNextHand(false);
                }
            }
        }
        return new ReadyGateStateTransition(true, deadlineEpochMs, ReadyGateState.COUNTING_DOWN);
    }

    /**
     * Alias for {@link #openCountdown(List, long)}.
     */
    static ReadyGateStateTransition openReadyCountdown(List<Player> tablePlayers, long deadlineEpochMs) {
        return openCountdown(tablePlayers, deadlineEpochMs);
    }

    /**
     * Closes the ready-confirmation gate without modifying player readiness flags.
     *
     * @return resulting gate transition state
     */
    static ReadyGateStateTransition closeCountdown() {
        return new ReadyGateStateTransition(false, null, ReadyGateState.CLOSED);
    }

    /**
     * Alias for {@link #closeCountdown()}.
     */
    static ReadyGateStateTransition closeReadyCountdown() {
        return closeCountdown();
    }

    /**
     * Closes ticking readiness while keeping the completed hand available for review
     * in WAITING_FOR_PLAYERS state, and resets all table players' ready flags to false.
     *
     * @param tablePlayers list of all seated table players
     * @return resulting gate transition state
     */
    static ReadyGateStateTransition waitForPlayers(List<Player> tablePlayers) {
        if (tablePlayers != null) {
            for (Player player : tablePlayers) {
                if (player != null) {
                    player.setReadyForNextHand(false);
                }
            }
        }
        return new ReadyGateStateTransition(false, null, ReadyGateState.WAITING_FOR_PLAYERS);
    }

    /**
     * Alias for {@link #waitForPlayers(List)}.
     */
    static ReadyGateStateTransition waitForReadyPlayers(List<Player> tablePlayers) {
        return waitForPlayers(tablePlayers);
    }

    /**
     * Advances the hand number when beginning a new hand.
     * Starts from 1 when currently 0 or negative, otherwise increments.
     *
     * @param currentHandNumber current hand number (0 if unstarted)
     * @return new hand number
     */
    static long beginHand(long currentHandNumber) {
        if (currentHandNumber <= 0) {
            return 1L;
        }
        return currentHandNumber + 1L;
    }

    /**
     * Filters table players to find those whose readiness is required for starting a new hand:
     * players who are connected, not eliminated (isOut == false), and have chips > 0.
     *
     * @param tablePlayers list of all seated table players
     * @return list of eligible players
     */
    static List<Player> getEligiblePlayers(List<Player> tablePlayers) {
        if (tablePlayers == null) {
            return List.of();
        }
        return tablePlayers.stream()
                .filter(Objects::nonNull)
                .filter(player -> !player.getIsOut())
                .filter(player -> !player.getIsDisconnected())
                .filter(player -> player.getChips() > 0)
                .toList();
    }

    /**
     * Alias for {@link #getEligiblePlayers(List)}.
     */
    static List<Player> getReadyEligiblePlayers(List<Player> tablePlayers) {
        return getEligiblePlayers(tablePlayers);
    }

    /**
     * Returns the count of players currently eligible for ready gate checks.
     *
     * @param tablePlayers list of all seated table players
     * @return number of ready-eligible players
     */
    static int getReadyEligibleCount(List<Player> tablePlayers) {
        return getEligiblePlayers(tablePlayers).size();
    }

    /**
     * Evaluates whether all currently eligible players are marked ready.
     * Requires at least 2 eligible players to return true.
     *
     * @param tablePlayers list of all seated table players
     * @return true if at least 2 eligible players exist and all are marked ready, false otherwise
     */
    static boolean areAllEligiblePlayersReady(List<Player> tablePlayers) {
        List<Player> eligiblePlayers = getEligiblePlayers(tablePlayers);
        if (eligiblePlayers.size() < 2) {
            return false;
        }
        return eligiblePlayers.stream().allMatch(Player::getIsReadyForNextHand);
    }

    /**
     * Alias for {@link #areAllEligiblePlayersReady(List)}.
     */
    static boolean areAllReadyEligiblePlayersReady(List<Player> tablePlayers) {
        return areAllEligiblePlayersReady(tablePlayers);
    }

    /**
     * Marks all currently eligible players as ready for the next hand.
     *
     * @param tablePlayers list of all seated table players
     */
    static void forceReadyForEligiblePlayers(List<Player> tablePlayers) {
        for (Player player : getEligiblePlayers(tablePlayers)) {
            player.setReadyForNextHand(true);
        }
    }

    /**
     * Resolves the initial or fallback ReadyGateState from restore parameters.
     *
     * @param gateState restored state enum, or null if unspecified
     * @param readyCountdownActive whether the countdown was active
     * @return resolved ReadyGateState
     */
    static ReadyGateState resolveInitialState(ReadyGateState gateState, boolean readyCountdownActive) {
        if (gateState != null) {
            return gateState;
        }
        return readyCountdownActive ? ReadyGateState.COUNTING_DOWN : ReadyGateState.CLOSED;
    }
}
