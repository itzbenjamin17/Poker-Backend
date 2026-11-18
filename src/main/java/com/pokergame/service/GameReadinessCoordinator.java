package com.pokergame.service;

import com.pokergame.config.GameTimingProperties;
import com.pokergame.dto.internal.TurnOutcome;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.service.GameTaskScheduler.ScheduledTimer;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Coordinates the between-hand ready gate: opening the ready countdown, tracking player
 * readiness updates, handling countdown expirations, and starting the next hand or ending the game.
 */
@Component
class GameReadinessCoordinator {
    private static final Logger logger = LoggerFactory.getLogger(GameReadinessCoordinator.class);
    private final ActiveGameRegistry activeGames;
    private final GameStateService gameStateService;
    private final GameTimerRegistry timerRegistry;
    private final GameTaskScheduler taskScheduler;
    private final GameCompletionHandler completionHandler;
    private final HandProgressionService handProgression;
    private final Clock clock;
    private final GameTimingProperties timings;

    GameReadinessCoordinator(
            ActiveGameRegistry activeGames,
            GameStateService gameStateService,
            GameTimerRegistry timerRegistry,
            GameTaskScheduler taskScheduler,
            GameCompletionHandler completionHandler,
            HandProgressionService handProgression,
            Clock clock,
            GameTimingProperties timings) {
        this.activeGames = activeGames;
        this.gameStateService = gameStateService;
        this.timerRegistry = timerRegistry;
        this.taskScheduler = taskScheduler;
        this.completionHandler = completionHandler;
        this.handProgression = handProgression;
        this.clock = clock;
        this.timings = timings;
    }

    /**
     * Opens the ready gate using the configured countdown duration and applies any resulting
     * next-hand or game-end progression.
     *
     * @param game the active game instance
     */
    void openReadyGate(Game game) {
        String gameId = game.getGameId();
        long deadline = clock.millis() + timings.readyCountdownMs();
        switch (game.openOrWaitReadyGate(deadline)) {
            case IGNORE -> {
            }
            case GAME_DECIDED -> completionHandler.handleGameEnd(gameId);
            case WAITING_FOR_PLAYERS -> {
                cancelReadyTimeout(gameId);
                gameStateService.broadcastGameState(game);
            }
            case ALL_READY -> {
                gameStateService.broadcastGameState(game);
                completeReadyCountdown(game);
            }
            case OPENED -> {
                ScheduledTimer timer = GameTaskScheduler.createTimer(
                        game, ScheduledGameTask.READY_TIMEOUT, deadline);
                gameStateService.broadcastGameState(game);
                taskScheduler.registerTimerAfterSave(timer);
            }
        }
    }

    /**
     * Deals the next hand and, if that hand ends at once (only possible when the round-end display
     * delay is zero), opens the ready gate in this same call.
     *
     * @param gameId the unique ID of the game
     */
    void startNewHandAndOpenReadyGateIfNeeded(String gameId) {
        if (handProgression.startNewHand(gameId)) {
            openReadyGate(activeGames.get(gameId));
        }
    }

    /**
     * Applies a turn, forced-blind, or departure outcome and, if it ends the hand and the round-end
     * display delay is zero, opens the ready gate in this same call.
     *
     * @param game                       the active game instance
     * @param outcome                    the resulting turn state
     * @param notifyImmediateAutoAdvance {@code true} to send an immediate all-in notice banner
     */
    void applyTurnOutcomeAndOpenReadyGateIfNeeded(
            Game game, TurnOutcome outcome, boolean notifyImmediateAutoAdvance) {
        if (handProgression.applyTurnOutcome(game, outcome, notifyImmediateAutoAdvance)) {
            openReadyGate(game);
        }
    }

    /**
     * Runs an auto-advance timer step and, if it ends the hand and the round-end display delay is
     * zero, opens the ready gate in this same call.
     *
     * @param game the active game instance
     */
    void applyAutoAdvanceTimeoutAndOpenReadyGateIfNeeded(Game game) {
        if (handProgression.applyAutoAdvanceTimeout(game)) {
            openReadyGate(game);
        }
    }

    /**
     * Marks a player as ready for the next hand after checking game inactivity expiration, starting
     * the next hand if all eligible players are now ready.
     *
     * @param gameId     the unique ID of the game
     * @param playerName the player marking ready
     */
    void markPlayerReadyForNextHand(String gameId, String playerName) {
        setPlayerReadyForNextHand(gameId, playerName, true);
    }

    /**
     * Marks a player as not ready for the next hand after checking game inactivity expiration.
     *
     * @param gameId     the unique ID of the game
     * @param playerName the player unmarking ready
     */
    void unmarkPlayerReadyForNextHand(String gameId, String playerName) {
        setPlayerReadyForNextHand(gameId, playerName, false);
    }

    /**
     * Processes expiration of the ready countdown for {@code expectedDeadline} and starts the next
     * hand if the gate forces remaining players ready.
     *
     * @param game             the active game instance
     * @param expectedDeadline the deadline captured when the ready timeout was scheduled
     */
    void handleReadyTimeout(Game game, long expectedDeadline) {
        switch (game.expireReadyCountdown(expectedDeadline)) {
            case IGNORE_STALE -> {
            }
            case WAITING_FOR_PLAYERS -> {
                cancelReadyTimeout(game.getGameId());
                gameStateService.broadcastGameState(game);
            }
            case FORCED_READY -> completeReadyCountdown(game);
        }
    }

    /**
     * Completes the ready countdown if one is open and every eligible player is now ready; does
     * nothing otherwise. Call after a player leaves or disconnects, since that can leave only
     * ready players.
     *
     * @param game the active game instance
     * @return {@code true} if the countdown was open, everyone was ready, and it completed;
     *         {@code false} otherwise
     */
    boolean completeReadyCountdownIfEveryoneReady(Game game) {
        if (game.isReadyCountdownActive() && game.areAllEligiblePlayersReady()) {
            completeReadyCountdown(game);
            return true;
        }
        return false;
    }

    /**
     * Closes the ready gate for {@code game} and immediately deals the next hand.
     *
     * @param game the active game instance
     */
    void completeReadyCountdown(Game game) {
        closeReadyGate(game);
        startNewHandAndOpenReadyGateIfNeeded(game.getGameId());
    }

    /**
     * Cancels ready and turn timers and closes the ready gate on {@code game}.
     *
     * @param game the active game instance
     */
    void closeReadyGate(Game game) {
        game.closeReadyCountdown();
        taskScheduler.cancelTask(game, ScheduledGameTask.READY_TIMEOUT);
        taskScheduler.cancelTask(game, ScheduledGameTask.TURN_TIMEOUT);
    }

    /**
     * Cancels any scheduled runtime ready-timeout future without modifying {@code Game} state.
     *
     * @param gameId the unique ID of the game
     */
    void cancelReadyTimeout(String gameId) {
        timerRegistry.cancelTask(gameId, ScheduledGameTask.READY_TIMEOUT);
    }

    private void setPlayerReadyForNextHand(String gameId, String playerName, boolean ready) {
        Game game = activeGames.get(gameId);
        if (game == null) {
            throw new ResourceNotFoundException("Game not found");
        }
        completionHandler.rejectExpiredActivity(gameId);

        Player player = game.findPlayerByName(playerName);
        boolean previouslyReady = player != null && player.isReadyForNextHand();
        game.setPlayerReadyForNextHand(playerName, ready);
        if (previouslyReady != ready) {
            taskScheduler.resetGameInactivity(game);
        }
        logger.debug("Player {} {} ready for next hand in game {}",
                playerName, ready ? "marked" : "unmarked", gameId);
        if (ready && completeReadyCountdownIfEveryoneReady(game)) {
            return;
        }
        gameStateService.broadcastGameState(game);
    }
}
