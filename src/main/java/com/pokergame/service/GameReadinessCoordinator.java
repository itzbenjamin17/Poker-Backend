package com.pokergame.service;

import com.pokergame.config.LifecycleTimingProperties;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
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
    private final RoomService roomService;
    private final GameStateService gameStateService;
    private final GameTimerRegistry runtimeTimers;
    private final GameTaskScheduler taskScheduler;
    private final GameCompletionHandler completionHandler;
    private final HandProgressionService handProgression;
    private final Clock clock;
    private final LifecycleTimingProperties timings;

    GameReadinessCoordinator(
            ActiveGameRegistry activeGames,
            RoomService roomService,
            GameStateService gameStateService,
            GameTimerRegistry runtimeTimers,
            GameTaskScheduler taskScheduler,
            GameCompletionHandler completionHandler,
            HandProgressionService handProgression,
            Clock clock,
            LifecycleTimingProperties timings) {
        this.activeGames = activeGames;
        this.roomService = roomService;
        this.gameStateService = gameStateService;
        this.runtimeTimers = runtimeTimers;
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
     * @param gameId the unique ID of the game
     */
    void openReadyGate(String gameId) {
        Game game = activeGames.get(gameId);
        if (game == null) {
            return;
        }
        long deadline = clock.millis() + timings.readyCountdownMs();
        switch (game.startOrWaitReadyCountdown(deadline)) {
            case IGNORE -> {
            }
            case TOURNAMENT_OVER -> completionHandler.handleGameEnd(gameId);
            case WAITING_FOR_PLAYERS -> {
                cancelTimeout(gameId);
                gameStateService.broadcastGameState(game);
            }
            case ALL_READY -> {
                gameStateService.broadcastGameState(game);
                completeReadyCountdown(game);
            }
            case OPENED -> {
                ScheduledTimer timer = GameTaskScheduler.createTimer(
                        gameId, game, ScheduledGameTask.READY_TIMEOUT, deadline);
                gameStateService.broadcastGameState(game);
                taskScheduler.registerTimerAfterSave(timer);
            }
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
     * @param gameId           the unique ID of the game
     * @param game             the active game instance, or {@code null} if removed
     * @param expectedDeadline the deadline captured when the ready timeout was scheduled
     */
    void handleReadyTimeout(String gameId, Game game, long expectedDeadline) {
        if (game == null) {
            cancelTimeout(gameId);
            return;
        }
        switch (game.expireReadyCountdown(expectedDeadline)) {
            case IGNORE_STALE -> {
            }
            case WAITING_FOR_PLAYERS -> {
                cancelTimeout(gameId);
                gameStateService.broadcastGameState(game);
            }
            case FORCED_READY -> completeReadyCountdown(game);
        }
    }

    /**
     * Closes the ready gate for {@code game} and immediately deals the next hand.
     *
     * @param game the active game instance
     */
    void completeReadyCountdown(Game game) {
        closeReadyGate(game);
        String gameId = game.getGameId();
        if (handProgression.startNewHand(gameId)) {
            openReadyGate(gameId);
        }
    }

    /**
     * Cancels ready and turn timers and closes the ready gate on {@code game}.
     *
     * @param game the active game instance
     */
    void closeReadyGate(Game game) {
        String gameId = game.getGameId();
        cancelTimeout(gameId);
        runtimeTimers.cancelTask(gameId, ScheduledGameTask.TURN_TIMEOUT);
        game.closeReadyCountdown();
        game.clearScheduledTask(ScheduledGameTask.READY_TIMEOUT);
        game.clearScheduledTask(ScheduledGameTask.TURN_TIMEOUT);
    }

    /**
     * Cancels any scheduled runtime ready-timeout future without modifying {@code Game} state.
     *
     * @param gameId the unique ID of the game
     */
    void cancelTimeout(String gameId) {
        runtimeTimers.cancelTask(gameId, ScheduledGameTask.READY_TIMEOUT);
    }

    private void setPlayerReadyForNextHand(String gameId, String playerName, boolean ready) {
        Game game = activeGames.get(gameId);
        if (game == null) {
            throw new ResourceNotFoundException("Game not found");
        }
        Room room = roomService.getRoom(gameId);
        completionHandler.rejectExpiredActivity(gameId, room);

        Player player = game.findPlayerByName(playerName);
        boolean previouslyReady = player != null && player.getIsReadyForNextHand();
        game.setPlayerReadyForNextHand(playerName, ready);
        if (previouslyReady != ready) {
            taskScheduler.resetGameInactivity(gameId, game);
        }
        logger.debug("Player {} {} ready for next hand in game {}",
                playerName, ready ? "marked" : "unmarked", gameId);
        if (ready && game.areAllReadyEligiblePlayersReady()) {
            completeReadyCountdown(game);
            return;
        }
        gameStateService.broadcastGameState(game);
    }
}
