package com.pokergame.model;

import com.pokergame.enums.GamePhase;
import com.pokergame.enums.ReadyCountdownStartOutcome;
import com.pokergame.enums.ReadyCountdownTimeoutOutcome;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.UnauthorisedActionException;

import java.util.List;
import java.util.Objects;

/**
 * Owns between-hand player readiness state, countdown gating, and timeout evaluation for {@link Game}.
 */
final class ReadyGate {

    private final List<Player> players;
    private ReadyGateState state;
    private Long countdownDeadlineEpochMs;

    /**
     * Creates a closed ready gate bound to the game's player roster.
     *
     * @param players live table player list owned by {@link Game}
     */
    ReadyGate(List<Player> players) {
        this.players = players;
        this.state = ReadyGateState.CLOSED;
        this.countdownDeadlineEpochMs = null;
    }

    /**
     * Reconstructs ready-gate state from saved snapshot fields.
     *
     * @param players         live table player list owned by {@link Game}
     * @param restoredState   saved gate state, or {@code null} to derive from {@code restoredActive}
     * @param restoredActive  legacy countdown-active flag when {@code restoredState} is {@code null}
     * @param deadlineEpochMs saved countdown deadline in epoch milliseconds, or {@code null}
     */
    ReadyGate(List<Player> players, ReadyGateState restoredState, boolean restoredActive, Long deadlineEpochMs) {
        this.players = players;
        this.state = restoredState != null
                ? restoredState
                : (restoredActive ? ReadyGateState.COUNTING_DOWN : ReadyGateState.CLOSED);
        this.countdownDeadlineEpochMs = deadlineEpochMs;
    }

    /**
     * Validates readiness eligibility and updates {@code playerName}'s ready flag while the countdown is active.
     *
     * @param playerName player requesting the change
     * @param ready      {@code true} to mark ready, {@code false} to unmark
     * @throws UnauthorisedActionException if the gate is not counting down or the player is ineligible
     */
    void setPlayerReady(String playerName, boolean ready) {
        if (!isCountdownActive()) {
            throw new UnauthorisedActionException("Ready countdown is not active.");
        }
        Player player = Game.findPlayerByName(players, playerName);
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
     * Closes the ready gate without altering player ready flags.
     */
    void closeCountdown() {
        this.state = ReadyGateState.CLOSED;
        this.countdownDeadlineEpochMs = null;
    }

    /**
     * Moves the gate to {@link ReadyGateState#WAITING_FOR_PLAYERS} and clears all player ready flags.
     */
    void waitForPlayers() {
        clearPlayerReadyFlags();
        this.state = ReadyGateState.WAITING_FOR_PLAYERS;
        this.countdownDeadlineEpochMs = null;
    }

    /**
     * Evaluates readiness conditions after a hand settles and opens the countdown or waits for players.
     *
     * @param forfeitPending  whether a forfeit is pending
     * @param gameOver        whether the game is already over
     * @param gameDecided     whether one or zero players with chips remain
     * @param currentPhase    current game phase
     * @param deadlineEpochMs countdown expiration timestamp in epoch milliseconds
     * @param scheduling      game scheduler used to record or clear the ready-timeout task
     * @return outcome describing the resulting gate state
     */
    ReadyCountdownStartOutcome openOrWait(
            boolean forfeitPending,
            boolean gameOver,
            boolean gameDecided,
            GamePhase currentPhase,
            long deadlineEpochMs,
            GameScheduling scheduling) {
        if (forfeitPending || gameOver) {
            return ReadyCountdownStartOutcome.IGNORE;
        }
        if (gameDecided) {
            return ReadyCountdownStartOutcome.GAME_DECIDED;
        }
        if (currentPhase != GamePhase.SHOWDOWN) {
            return ReadyCountdownStartOutcome.IGNORE;
        }
        if (getReadyEligibleCount() < 2) {
            waitForPlayers();
            scheduling.clearTask(ScheduledGameTask.READY_TIMEOUT);
            return ReadyCountdownStartOutcome.WAITING_FOR_PLAYERS;
        }
        openCountdown(deadlineEpochMs);
        scheduling.scheduleTask(ScheduledGameTask.READY_TIMEOUT, deadlineEpochMs);
        return areAllEligiblePlayersReady() ? ReadyCountdownStartOutcome.ALL_READY : ReadyCountdownStartOutcome.OPENED;
    }

    /**
     * Evaluates expiration of a scheduled ready countdown timer.
     *
     * @param forfeitPending          whether a forfeit is pending
     * @param expectedDeadlineEpochMs deadline captured when the timer was scheduled
     * @param scheduling              game scheduler used to clear the ready-timeout task if waiting
     * @return timeout evaluation outcome
     */
    ReadyCountdownTimeoutOutcome expireCountdown(
            boolean forfeitPending, long expectedDeadlineEpochMs, GameScheduling scheduling) {
        if (forfeitPending || !isCountdownActive()
                || !Objects.equals(countdownDeadlineEpochMs, expectedDeadlineEpochMs)) {
            return ReadyCountdownTimeoutOutcome.IGNORE_STALE;
        }
        if (getReadyEligibleCount() < 2) {
            waitForPlayers();
            scheduling.clearTask(ScheduledGameTask.READY_TIMEOUT);
            return ReadyCountdownTimeoutOutcome.WAITING_FOR_PLAYERS;
        }
        forceReadyForEligiblePlayers();
        return ReadyCountdownTimeoutOutcome.FORCED_READY;
    }

    /**
     * Repairs a recovered ready countdown that is missing a deadline or matching scheduled task.
     *
     * @param fallbackDeadlineEpochMs fallback deadline to apply if the recovered countdown had no deadline
     * @param scheduling              game scheduler holding persisted task deadlines
     * @return {@code true} if the gate state was repaired and requires a broadcast
     */
    boolean repairRecoveredGate(long fallbackDeadlineEpochMs, GameScheduling scheduling) {
        boolean repaired = false;
        if (isCountdownActive() && countdownDeadlineEpochMs == null) {
            repaired = true;
            if (getReadyEligibleCount() < 2) {
                waitForPlayers();
                scheduling.clearTask(ScheduledGameTask.READY_TIMEOUT);
            } else {
                openCountdown(fallbackDeadlineEpochMs);
                scheduling.scheduleTask(ScheduledGameTask.READY_TIMEOUT, fallbackDeadlineEpochMs);
            }
        }
        if (isCountdownActive() && countdownDeadlineEpochMs != null
                && !Objects.equals(scheduling.getDeadline(ScheduledGameTask.READY_TIMEOUT), countdownDeadlineEpochMs)) {
            scheduling.scheduleTask(ScheduledGameTask.READY_TIMEOUT, countdownDeadlineEpochMs);
        }
        return repaired;
    }

    /**
     * Returns the count of connected, non-eliminated players with chips who can ready up.
     *
     * @return number of eligible players
     */
    int getReadyEligibleCount() {
        return getEligiblePlayers().size();
    }

    /**
     * Returns whether at least two eligible players exist and every eligible player is marked ready.
     *
     * @return {@code true} if the table is ready to start the next hand
     */
    boolean areAllEligiblePlayersReady() {
        List<Player> eligiblePlayers = getEligiblePlayers();
        return eligiblePlayers.size() >= 2 && eligiblePlayers.stream().allMatch(Player::getIsReadyForNextHand);
    }

    ReadyGateState getState() {
        return state;
    }

    boolean isCountdownActive() {
        return state == ReadyGateState.COUNTING_DOWN;
    }

    Long getCountdownDeadlineEpochMs() {
        return countdownDeadlineEpochMs;
    }

    private void openCountdown(long deadlineEpochMs) {
        clearPlayerReadyFlags();
        this.state = ReadyGateState.COUNTING_DOWN;
        this.countdownDeadlineEpochMs = deadlineEpochMs;
    }

    private void clearPlayerReadyFlags() {
        if (players == null) {
            return;
        }
        for (Player player : players) {
            if (player != null) {
                player.setReadyForNextHand(false);
            }
        }
    }

    private List<Player> getEligiblePlayers() {
        if (players == null) {
            return List.of();
        }
        return players.stream()
                .filter(Objects::nonNull)
                .filter(player -> !player.getIsOut())
                .filter(player -> !player.getIsDisconnected())
                .filter(player -> player.getChips() > 0)
                .toList();
    }

    private void forceReadyForEligiblePlayers() {
        for (Player player : getEligiblePlayers()) {
            player.setReadyForNextHand(true);
        }
    }
}
