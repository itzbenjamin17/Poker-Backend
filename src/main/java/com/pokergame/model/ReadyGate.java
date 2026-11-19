package com.pokergame.model;

import com.pokergame.enums.ReadyCountdownStartOutcome;
import com.pokergame.enums.ReadyCountdownTimeoutOutcome;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.UnauthorisedActionException;

import java.util.List;
import java.util.Objects;

/**
 * Manages the between-hand pause before the next hand is dealt for {@link Game}:
 * <ul>
 *   <li>{@link ReadyGateState#CLOSED}: a hand is in progress or the match has ended.</li>
 *   <li>{@link ReadyGateState#COUNTING_DOWN}: at least two eligible players (connected, not out,
 *       with chips) can mark ready before {@code READY_TIMEOUT} forces the next hand to start.</li>
 *   <li>{@link ReadyGateState#WAITING_FOR_PLAYERS}: fewer than two eligible players are present,
 *       pausing the countdown until enough players reconnect.</li>
 * </ul>
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
    }

    /**
     * Reconstructs ready-gate state from saved snapshot fields.
     *
     * @param players         live table player list owned by {@link Game}
     * @param restoredState   saved gate state (must not be {@code null})
     * @param deadlineEpochMs saved countdown deadline in epoch milliseconds, or {@code null}
     */
    ReadyGate(List<Player> players, ReadyGateState restoredState, Long deadlineEpochMs) {
        this.players = players;
        this.state = Objects.requireNonNull(restoredState, "readyGateState must not be null");
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
        validateReadinessEligibility(player, ready);
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
     * Evaluates readiness eligibility after a hand settles and opens the countdown or waits for players.
     *
     * @param deadlineEpochMs countdown expiration timestamp in epoch milliseconds
     * @param scheduling      game scheduler used to record or clear the ready-timeout task
     * @return outcome describing the resulting gate state
     */
    ReadyCountdownStartOutcome openOrWait(long deadlineEpochMs, GameScheduling scheduling) {
        if (getReadyEligibleCount() < 2) {
            moveToWaitingForPlayers(scheduling);
            return ReadyCountdownStartOutcome.WAITING_FOR_PLAYERS;
        }
        openCountdown(deadlineEpochMs);
        scheduling.scheduleTask(ScheduledGameTask.READY_TIMEOUT, deadlineEpochMs);
        return areAllEligiblePlayersReady() ? ReadyCountdownStartOutcome.ALL_READY : ReadyCountdownStartOutcome.OPENED;
    }

    /**
     * Evaluates expiration of a scheduled ready countdown timer.
     *
     * @param pausedForDisconnectedSurvivor whether the game is paused awaiting a disconnected survivor
     * @param expectedDeadlineEpochMs       deadline captured when the timer was scheduled
     * @param scheduling                    game scheduler used to clear the ready-timeout task if waiting
     * @return timeout evaluation outcome
     */
    ReadyCountdownTimeoutOutcome expireCountdown(
            boolean pausedForDisconnectedSurvivor, long expectedDeadlineEpochMs, GameScheduling scheduling) {
        if (pausedForDisconnectedSurvivor || !isCountdownActive()
                || !Objects.equals(countdownDeadlineEpochMs, expectedDeadlineEpochMs)) {
            return ReadyCountdownTimeoutOutcome.IGNORE_STALE;
        }
        if (getReadyEligibleCount() < 2) {
            moveToWaitingForPlayers(scheduling);
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
                moveToWaitingForPlayers(scheduling);
            } else {
                openCountdown(fallbackDeadlineEpochMs);
                scheduling.scheduleTask(ScheduledGameTask.READY_TIMEOUT, fallbackDeadlineEpochMs);
            }
        }
        if (isCountdownActive() && countdownDeadlineEpochMs != null
                && !Objects.equals(scheduling.getScheduledTime(ScheduledGameTask.READY_TIMEOUT), countdownDeadlineEpochMs)) {
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
        return eligiblePlayers.size() >= 2 && eligiblePlayers.stream().allMatch(Player::isReadyForNextHand);
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

    private void moveToWaitingForPlayers(GameScheduling scheduling) {
        waitForPlayers();
        scheduling.clearTask(ScheduledGameTask.READY_TIMEOUT);
    }

    private void openCountdown(long deadlineEpochMs) {
        clearPlayerReadyFlags();
        this.state = ReadyGateState.COUNTING_DOWN;
        this.countdownDeadlineEpochMs = deadlineEpochMs;
    }

    private void clearPlayerReadyFlags() {
        for (Player player : players) {
            player.setReadyForNextHand(false);
        }
    }

    private List<Player> getEligiblePlayers() {
        return players.stream()
                .filter(ReadyGate::isPlayerEligible)
                .toList();
    }

    private void forceReadyForEligiblePlayers() {
        for (Player player : getEligiblePlayers()) {
            player.setReadyForNextHand(true);
        }
    }

    private static boolean isPlayerEligible(Player player) {
        return !player.isOut() && !player.isDisconnected() && player.getChips() > 0;
    }

    private static void validateReadinessEligibility(Player player, boolean ready) {
        if (isPlayerEligible(player)) {
            return;
        }
        if (player.isOut()) {
            throw new UnauthorisedActionException("You are out of this game.");
        }
        if (!ready) {
            throw new UnauthorisedActionException("You are not eligible to change readiness.");
        }
        if (player.isDisconnected()) {
            throw new UnauthorisedActionException("Reconnect before marking ready.");
        }
        throw new UnauthorisedActionException("Players without chips cannot mark ready.");
    }
}
