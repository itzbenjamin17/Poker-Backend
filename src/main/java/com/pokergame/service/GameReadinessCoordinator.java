package com.pokergame.service;

import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.transaction.DurableTransactionContext;
import com.pokergame.service.GameTaskScheduler.TaskIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Orchestrates ready-gate transitions and their effects under the caller's room lease. */
final class GameReadinessCoordinator {
    private static final Logger logger = LoggerFactory.getLogger(GameReadinessCoordinator.class);

    enum Progression { NONE, START_HAND, END_GAME }

    /** Returns the lifecycle decision and any timeout intent for an opened gate. */
    record Opening(Progression progression, TaskIntent timeout) { }

    private final ActiveGameRegistry activeGames;
    private final RoomService roomService;
    private final GameStateService gameStateService;
    private final GameTimerRegistry runtimeTimers;

    private final GameTaskScheduler scheduling;
    private final GameCompletionHandler completion;
    private final HandProgressionService handProgression;
    private final RoomCoordinator roomCoordinator;
    private final Supplier<Clock> clock;
    private final LongSupplier configuredCountdownMs;
    private final Consumer<String> endGameMutation;

    GameReadinessCoordinator(ActiveGameRegistry activeGames, RoomService roomService, GameStateService gameStateService,
            GameTimerRegistry runtimeTimers, GameTaskScheduler scheduling, GameCompletionHandler completion,
            HandProgressionService handProgression, RoomCoordinator roomCoordinator, Supplier<Clock> clock,
            LongSupplier configuredCountdownMs, Consumer<String> endGameMutation) {
        this.activeGames = activeGames;
        this.roomService = roomService;
        this.gameStateService = gameStateService;
        this.runtimeTimers = runtimeTimers;
        this.scheduling = scheduling;
        this.completion = completion;
        this.handProgression = handProgression;
        this.roomCoordinator = roomCoordinator;
        this.clock = clock;
        this.configuredCountdownMs = configuredCountdownMs;
        this.endGameMutation = endGameMutation;
    }

    /** Opens readiness and records its timeout, returning any immediate progression. */
    Progression startReadyCountdown(String gameId, long countdownMs) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        Game game = activeGames.get(gameId);
        if (game == null) return Progression.NONE;
        Opening opening = open(gameId, game, clock.get().millis() + countdownMs);
        if (opening.timeout() != null) {
            scheduling.submitAfterCommit(opening.timeout());
        }
        return opening.progression();
    }

    /** Opens readiness using the configured countdown in the current mutation. */
    void openConfiguredCountdown(String gameId) {
        applyProgression(gameId, startReadyCountdown(gameId, configuredCountdownMs.getAsLong()));
    }

    Progression markPlayerReadyForNextHand(String gameId, String playerName) {
        return setPlayerReadyForNextHand(gameId, playerName, true);
    }

    Progression unmarkPlayerReadyForNextHand(String gameId, String playerName) {
        return setPlayerReadyForNextHand(gameId, playerName, false);
    }

    /** Validates accepted activity before applying readiness and any inactivity reset. */
    private Progression setPlayerReadyForNextHand(String gameId, String playerName, boolean ready) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        Game game = activeGames.get(gameId);
        if (game == null) throw new ResourceNotFoundException("Game not found");

        Room room = roomService != null ? roomService.getRoom(gameId) : null;
        completion.rejectExpiredActivity(gameId, room);
        return setReady(gameId, game, playerName, ready);
    }

    /** Applies a compatibility timeout using the supplied deadline. */
    Progression expireReadyCountdown(String gameId, long expectedDeadline) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        return expire(gameId, activeGames.get(gameId), expectedDeadline);
    }

    /** Applies readiness progression while keeping terminal effects in their separate mutation. */
    void applyProgression(String gameId, Progression progression) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        switch (progression) {
            case START_HAND -> applyHandContinuation(gameId, handProgression.startNewHand(gameId));
            case END_GAME -> DurableTransactionContext.afterCommit(() -> endGameMutation.accept(gameId));
            case NONE -> { }
        }
    }

    /** Applies a hand's immediate ready opening before publishing any pending showdown. */
    void applyHandContinuation(String gameId, HandProgressionService.OpenReadiness continuation) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        if (continuation == null) return;
        openConfiguredCountdown(gameId);
        handProgression.publishPendingShowdown(gameId, continuation);
    }

    /** Applies the opening transition and publishes readiness before any hand progression. */
    Opening open(String gameId, Game game, long deadline) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        return switch (game.startOrWaitReadyCountdown(deadline)) {
            case IGNORE -> new Opening(Progression.NONE, null);
            case TOURNAMENT_OVER -> new Opening(Progression.END_GAME, null);
            case WAITING_FOR_PLAYERS -> {
                cancelTimeout(gameId);
                gameStateService.broadcastGameState(gameId, game);
                yield new Opening(Progression.NONE, null);
            }
            case ALL_READY -> {
                gameStateService.broadcastGameState(gameId, game);
                complete(gameId, game);
                yield new Opening(Progression.START_HAND, null);
            }
            case OPENED -> {
                TaskIntent intent = GameTaskScheduler.captureIntent(gameId, game, ScheduledGameTask.READY_TIMEOUT, deadline);
                gameStateService.broadcastGameState(gameId, game);
                yield new Opening(Progression.NONE, intent);
            }
        };
    }

    /** Applies an eligible player's readiness and reports whether the next hand should start. */
    Progression setReady(String gameId, Game game, String playerName, boolean ready) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        Player player = game.findPlayerByName(playerName);
        boolean previouslyReady = player != null && player.getIsReadyForNextHand();
        game.setPlayerReadyForNextHand(playerName, ready);
        if (GameInactivityPolicy.isReadinessActivity(previouslyReady, ready)) {
            scheduling.resetGameInactivityUnderLock(gameId, game);
        }
        logger.debug("Player {} {} ready for next hand in game {}", playerName, ready ? "marked" : "unmarked", gameId);
        if (ready && game.areAllReadyEligiblePlayersReady()) {
            complete(gameId, game);
            return Progression.START_HAND;
        }
        gameStateService.broadcastGameState(gameId, game);
        return Progression.NONE;
    }

    /** Expires the matching gate and reports whether the next hand should start. */
    Progression expire(String gameId, Game game, long expectedDeadline) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
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
                complete(gameId, game);
                yield Progression.START_HAND;
            }
        };
    }

    /** Cancels the obsolete timers and closes the ready gate before the next deal. */
    void complete(String gameId, Game game) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        cancelTimeout(gameId);
        runtimeTimers.cancelTask(gameId, ScheduledGameTask.TURN_TIMEOUT);
        game.closeReadyCountdown();
        game.clearScheduledTask(ScheduledGameTask.READY_TIMEOUT);
        game.clearScheduledTask(ScheduledGameTask.TURN_TIMEOUT);
    }

    /** Cancels the runtime ready timeout without changing the gate. */
    void cancelTimeout(String gameId) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        runtimeTimers.cancelTask(gameId, ScheduledGameTask.READY_TIMEOUT);
    }
}
