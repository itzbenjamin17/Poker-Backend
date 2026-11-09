package com.pokergame.model;

import com.pokergame.enums.GamePhase;
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

    /**
     * Validates that the ready gate is open and that the named player is eligible to
     * change their readiness flag, then updates the player's readiness state.
     *
     * @param tablePlayers         seated table players
     * @param readyCountdownActive whether the ready countdown gate is currently open
     * @param playerName           player requesting the readiness change
     * @param ready                {@code true} to mark ready, {@code false} to unmark
     * @throws UnauthorisedActionException if the gate is closed or the player is ineligible
     */
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
    public record ReadyGateStateTransition(boolean active, Long deadlineEpochMs, ReadyGateState state) {
        public ReadyGateStateTransition {
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

    /**
     * Container holding the outcome and resulting gate transition of starting or waiting for a ready countdown.
     *
     * @param outcome the start outcome category
     * @param gateTransition the resulting ready gate state transition, or null if ignored
     */
    record CountdownStartTransition(Game.ReadyCountdownStartOutcome outcome, ReadyGateStateTransition gateTransition) {}

    /**
     * Evaluates table readiness gating conditions when a hand completes.
     *
     * @param tablePlayers list of seated table players
     * @param forfeitPending whether a forfeit is pending
     * @param gameOver whether the game is flagged as over
     * @param tournamentOver whether the tournament is over (one or zero players with chips remain)
     * @param currentPhase current phase of the game
     * @param deadlineEpochMs target expiration timestamp for the countdown if opened
     * @return transition result containing the outcome and state changes
     */
    static CountdownStartTransition startOrWait(
            List<Player> tablePlayers,
            boolean forfeitPending,
            boolean gameOver,
            boolean tournamentOver,
            GamePhase currentPhase,
            long deadlineEpochMs) {
        if (forfeitPending || gameOver) {
            return new CountdownStartTransition(Game.ReadyCountdownStartOutcome.IGNORE, null);
        }
        if (tournamentOver) {
            return new CountdownStartTransition(Game.ReadyCountdownStartOutcome.TOURNAMENT_OVER, null);
        }
        if (currentPhase != GamePhase.SHOWDOWN) {
            return new CountdownStartTransition(Game.ReadyCountdownStartOutcome.IGNORE, null);
        }
        if (getReadyEligibleCount(tablePlayers) < 2) {
            return new CountdownStartTransition(Game.ReadyCountdownStartOutcome.WAITING_FOR_PLAYERS, waitForPlayers(tablePlayers));
        }
        ReadyGateStateTransition opened = openCountdown(tablePlayers, deadlineEpochMs);
        Game.ReadyCountdownStartOutcome outcome = areAllEligiblePlayersReady(tablePlayers)
                ? Game.ReadyCountdownStartOutcome.ALL_READY
                : Game.ReadyCountdownStartOutcome.OPENED;
        return new CountdownStartTransition(outcome, opened);
    }

    /**
     * Container holding the outcome and resulting gate transition of expiring a ready countdown.
     *
     * @param outcome the timeout outcome category
     * @param gateTransition the resulting ready gate state transition, or null if not transitioning state
     */
    record CountdownTimeoutTransition(Game.ReadyCountdownTimeoutOutcome outcome, ReadyGateStateTransition gateTransition) {}

    /**
     * Evaluates expiration of an active ready countdown gate.
     *
     * @param tablePlayers list of seated table players
     * @param forfeitPending whether a forfeit is pending
     * @param readyCountdownActive whether the ready countdown is currently active
     * @param currentDeadlineEpochMs currently registered countdown deadline
     * @param expectedDeadlineEpochMs deadline token captured when the callback was scheduled
     * @return transition result containing the timeout outcome and state changes
     */
    static CountdownTimeoutTransition expireCountdown(
            List<Player> tablePlayers,
            boolean forfeitPending,
            boolean readyCountdownActive,
            Long currentDeadlineEpochMs,
            long expectedDeadlineEpochMs) {
        if (forfeitPending || !readyCountdownActive || !Objects.equals(currentDeadlineEpochMs, expectedDeadlineEpochMs)) {
            return new CountdownTimeoutTransition(Game.ReadyCountdownTimeoutOutcome.IGNORE_STALE, null);
        }
        if (getReadyEligibleCount(tablePlayers) < 2) {
            return new CountdownTimeoutTransition(Game.ReadyCountdownTimeoutOutcome.WAITING_FOR_PLAYERS, waitForPlayers(tablePlayers));
        }
        forceReadyForEligiblePlayers(tablePlayers);
        return new CountdownTimeoutTransition(Game.ReadyCountdownTimeoutOutcome.FORCED_READY, null);
    }

    /**
     * Normalizes ready countdown state after crash recovery or restart.
     *
     * @param tablePlayers list of seated table players
     * @param readyCountdownActive whether the ready countdown was active
     * @param currentDeadlineEpochMs restored deadline epoch milliseconds
     * @param freshDeadlineEpochMs fallback deadline if a restored active countdown was missing its deadline
     * @return resulting gate transition to apply, or null if no normalization is needed
     */
    static ReadyGateStateTransition normalizeRecoveredCountdown(
            List<Player> tablePlayers,
            boolean readyCountdownActive,
            Long currentDeadlineEpochMs,
            long freshDeadlineEpochMs) {
        if (!readyCountdownActive || currentDeadlineEpochMs != null) {
            return null;
        }
        return getReadyEligibleCount(tablePlayers) < 2
                ? waitForPlayers(tablePlayers)
                : openCountdown(tablePlayers, freshDeadlineEpochMs);
    }
}
