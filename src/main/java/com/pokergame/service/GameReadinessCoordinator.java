package com.pokergame.service;

import com.pokergame.config.LifecycleTimingProperties;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.service.GameTaskScheduler.ScheduledTimer;
import com.pokergame.service.HandProgressionService.PostHandTransition;
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
    private final GameTaskScheduler scheduling;
    private final GameCompletionHandler completion;
    private final HandProgressionService handProgression;
    private final Clock clock;
    private final LifecycleTimingProperties timings;

    GameReadinessCoordinator(
            ActiveGameRegistry activeGames,
            RoomService roomService,
            GameStateService gameStateService,
            GameTimerRegistry runtimeTimers,
            GameTaskScheduler scheduling,
            GameCompletionHandler completion,
            HandProgressionService handProgression,
            Clock clock,
            LifecycleTimingProperties timings) {
        this.activeGames = activeGames;
        this.roomService = roomService;
        this.gameStateService = gameStateService;
        this.runtimeTimers = runtimeTimers;
        this.scheduling = scheduling;
        this.completion = completion;
        this.handProgression = handProgression;
        this.clock = clock;
        this.timings = timings;
    }

    /**
     * Opens the ready gate with {@code countdownMs} duration and schedules its timeout timer.
     *
     * @param gameId      the unique ID of the game
     * @param countdownMs ready countdown duration in milliseconds
     * @return the next lifecycle action triggered immediately, if any
     */
    Progression startReadyCountdown(String gameId, long countdownMs) {
        Game game = activeGames.get(gameId);
        if (game == null) {
            return Progression.NONE;
        }
        ReadyGateOutcome outcome = openReadyGate(gameId, game, clock.millis() + countdownMs);
        if (outcome.timeout() != null) {
            scheduling.registerTimerAfterSave(outcome.timeout());
        }
        return outcome.progression();
    }

    /**
     * Opens the ready gate using the default configured countdown duration and applies any
     * resulting next-hand or game-end progression.
     *
     * @param gameId the unique ID of the game
     */
    void startDefaultReadyCountdown(String gameId) {
        applyProgression(gameId, startReadyCountdown(gameId, timings.readyCountdownMs()));
    }

    /**
     * Marks a player as ready for the next hand after checking game inactivity expiration.
     *
     * @param gameId     the unique ID of the game
     * @param playerName the player marking ready
     * @return the resulting lifecycle progression
     */
    Progression markPlayerReadyForNextHand(String gameId, String playerName) {
        return setPlayerReadyForNextHand(gameId, playerName, true);
    }

    /**
     * Marks a player as not ready for the next hand after checking game inactivity expiration.
     *
     * @param gameId     the unique ID of the game
     * @param playerName the player unmarking ready
     * @return the resulting lifecycle progression
     */
    Progression unmarkPlayerReadyForNextHand(String gameId, String playerName) {
        return setPlayerReadyForNextHand(gameId, playerName, false);
    }

    /**
     * Applies a readiness {@link Progression} step to either start the next hand or settle game end.
     *
     * @param gameId      the unique ID of the game
     * @param progression the progression decision to apply
     */
    void applyProgression(String gameId, Progression progression) {
        switch (progression) {
            case START_HAND -> handlePostHandTransition(gameId, handProgression.startNewHand(gameId));
            case END_GAME -> completion.handleGameEnd(gameId, false);
            case NONE -> {
            }
        }
    }

    /**
     * Opens the default ready gate immediately when a hand transition requires it, then broadcasts
     * any deferred showdown result.
     *
     * @param gameId     the unique ID of the game
     * @param transition the follow-up transition returned from hand progression, or {@code null}
     */
    void handlePostHandTransition(String gameId, PostHandTransition transition) {
        if (transition == null) {
            return;
        }
        startDefaultReadyCountdown(gameId);
        handProgression.broadcastPendingShowdown(gameId, transition);
    }

    /**
     * Transitions the game's ready gate toward {@code deadline}, broadcasting updates or returning a
     * progression decision when all players are already ready or the tournament has ended.
     *
     * @param gameId   the unique ID of the game
     * @param game     the active game instance
     * @param deadline target expiration epoch time in milliseconds
     * @return the opening outcome containing any progression decision and timeout descriptor
     */
    ReadyGateOutcome openReadyGate(String gameId, Game game, long deadline) {
        return switch (game.startOrWaitReadyCountdown(deadline)) {
            case IGNORE -> new ReadyGateOutcome(Progression.NONE, null);
            case TOURNAMENT_OVER -> new ReadyGateOutcome(Progression.END_GAME, null);
            case WAITING_FOR_PLAYERS -> {
                cancelTimeout(gameId);
                gameStateService.broadcastGameState(gameId, game);
                yield new ReadyGateOutcome(Progression.NONE, null);
            }
            case ALL_READY -> {
                gameStateService.broadcastGameState(gameId, game);
                closeReadyGate(gameId, game);
                yield new ReadyGateOutcome(Progression.START_HAND, null);
            }
            case OPENED -> {
                ScheduledTimer timer = GameTaskScheduler.createTimer(
                        gameId, game, ScheduledGameTask.READY_TIMEOUT, deadline);
                gameStateService.broadcastGameState(gameId, game);
                yield new ReadyGateOutcome(Progression.NONE, timer);
            }
        };
    }

    /**
     * Updates a player's ready state, resets inactivity when the state changed, and signals when
     * all eligible players are ready.
     *
     * @param gameId     the unique ID of the game
     * @param game       the active game instance
     * @param playerName the player whose readiness is changing
     * @param ready      {@code true} to mark ready, {@code false} to unmark
     * @return whether the next hand should start immediately
     */
    Progression setReady(String gameId, Game game, String playerName, boolean ready) {
        Player player = game.findPlayerByName(playerName);
        boolean previouslyReady = player != null && player.getIsReadyForNextHand();
        game.setPlayerReadyForNextHand(playerName, ready);
        if (previouslyReady != ready) {
            scheduling.resetGameInactivity(gameId, game);
        }
        logger.debug("Player {} {} ready for next hand in game {}",
                playerName, ready ? "marked" : "unmarked", gameId);
        if (ready && game.areAllReadyEligiblePlayersReady()) {
            closeReadyGate(gameId, game);
            return Progression.START_HAND;
        }
        gameStateService.broadcastGameState(gameId, game);
        return Progression.NONE;
    }

    /**
     * Processes expiration of the ready countdown for {@code expectedDeadline}.
     *
     * @param gameId           the unique ID of the game
     * @param game             the active game instance, or {@code null} if removed
     * @param expectedDeadline the deadline captured when the ready timeout was scheduled
     * @return whether the next hand should start after forcing remaining players ready
     */
    Progression expire(String gameId, Game game, long expectedDeadline) {
        if (game == null) {
            cancelTimeout(gameId);
            return Progression.NONE;
        }
        return switch (game.expireReadyCountdown(expectedDeadline)) {
            case IGNORE_STALE -> Progression.NONE;
            case WAITING_FOR_PLAYERS -> {
                cancelTimeout(gameId);
                gameStateService.broadcastGameState(gameId, game);
                yield Progression.NONE;
            }
            case FORCED_READY -> {
                closeReadyGate(gameId, game);
                yield Progression.START_HAND;
            }
        };
    }

    /**
     * Cancels ready and turn timers and closes the ready gate on {@code game}.
     *
     * @param gameId the unique ID of the game
     * @param game   the active game instance
     */
    void closeReadyGate(String gameId, Game game) {
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

    private Progression setPlayerReadyForNextHand(String gameId, String playerName, boolean ready) {
        Game game = activeGames.get(gameId);
        if (game == null) {
            throw new ResourceNotFoundException("Game not found");
        }
        Room room = roomService.getRoom(gameId);
        completion.rejectExpiredActivity(gameId, room);
        return setReady(gameId, game, playerName, ready);
    }

    enum Progression {
        NONE,
        START_HAND,
        END_GAME
    }

    /**
     * Result of opening the ready gate, pairing an immediate progression step with an optional
     * timeout timer to register after saving.
     *
     * @param progression next lifecycle step to run immediately
     * @param timeout     ready-timeout timer descriptor to register, or {@code null}
     */
    record ReadyGateOutcome(Progression progression, ScheduledTimer timeout) {
    }
}
