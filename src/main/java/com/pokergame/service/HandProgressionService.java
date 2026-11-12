package com.pokergame.service;

import com.pokergame.dto.internal.TurnOutcome;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.LongSupplier;

/** Deals and advances hands, recording scheduled progression and publishing hand effects under the room lease. */
final class HandProgressionService {
    private static final Logger logger = LoggerFactory.getLogger(HandProgressionService.class);
    static final long FINAL_SHOWDOWN_DISPLAY_DELAY_MS = 6000;

    /**
     * Opens readiness immediately. A normal showdown publishes its result after that opening;
     * auto-advance has already published its result. This continuation stays within the current lease.
     */
    record OpenReadiness(Game game, TurnOutcome pendingShowdown) { }

    private final ActiveGameRegistry activeGames;
    private final RoomCoordinator roomCoordinator;
    private final GameTimerRegistry runtimeTimers;
    private final GameTaskScheduler scheduling;
    private final GameCompletionHandler completion;
    private final GameStateService gameStateService;
    private final LongSupplier roundEndDisplayDelayMs;

    HandProgressionService(ActiveGameRegistry activeGames, RoomCoordinator roomCoordinator,
            GameTimerRegistry runtimeTimers, GameTaskScheduler scheduling, GameCompletionHandler completion,
            GameStateService gameStateService, LongSupplier roundEndDisplayDelayMs) {
        this.activeGames = activeGames;
        this.roomCoordinator = roomCoordinator;
        this.runtimeTimers = runtimeTimers;
        this.scheduling = scheduling;
        this.completion = completion;
        this.gameStateService = gameStateService;
        this.roundEndDisplayDelayMs = roundEndDisplayDelayMs;
    }

    /** Starts the next hand and returns any immediate readiness opening from the forced blinds. */
    OpenReadiness startNewHand(String gameId) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        Game game = activeGames.get(gameId);
        if (game == null) {
            logger.warn("Cannot start new hand - game {} not found", gameId);
            return null;
        }
        logger.info("Starting new hand for game: {}", gameId);
        if (game.isForfeitPending()) return null;
        if (game.isGameOver()) {
            if (!game.isTerminalCarryOverSettled()) completion.handleGameEnd(gameId, false);
            return null;
        }
        if (game.getHandNumber() > 0 && (game.getCurrentPhase() != GamePhase.SHOWDOWN
                || !game.isHandSettled())) {
            logger.warn("Ignoring new-hand request for unsettled game {} hand {}", gameId, game.getHandNumber());
            return null;
        }
        runtimeTimers.cancelProgressionForGame(gameId);
        // Invalidate obsolete durable intent independently of the runtime cancellation scope.
        game.invalidateProgressionTasks();
        game.closeReadyCountdown();
        game.clearScheduledTask(ScheduledGameTask.CLEANUP);

        // Reset determines game completion before cards are dealt so the final
        // winner state remains visible during the configured display window.
        boolean gameEnded = game.resetForNewHand();
        if (gameEnded) {
            logger.warn("Game {} became over after reset", gameId);
            completion.handleGameEnd(gameId, false);
            return null;
        }
        if (gameStateService != null) {
            gameStateService.clearRetainedSettlement(gameId);
        }

        game.beginHand();

        game.dealHoleCards();
        game.postBlinds();
        OpenReadiness continuation = applyTurnOutcome(gameId, game, game.reconcileAfterForcedBlinds());

        logger.info("New hand started successfully for game: {} | Current player: {} | Phase: {} | Pot: {}",
                gameId, game.getCurrentPlayer() == null ? "none" : game.getCurrentPlayer().getName(),
                game.getCurrentPhase(), game.getPot());
        return continuation;
    }

    /** Applies the outcome of blinds or a participant departure. */
    OpenReadiness applyTurnOutcome(String gameId, Game game, TurnOutcome outcome) {
        return applyTurnOutcomeUnderLock(gameId, game, outcome, false);
    }

    /** Applies the outcome of a player action, optionally publishing the all-in notification. */
    OpenReadiness applyTurnOutcome(String gameId, Game game, TurnOutcome outcome, boolean notifyImmediateAutoAdvance) {
        return applyTurnOutcomeUnderLock(gameId, game, outcome, notifyImmediateAutoAdvance);
    }

    private OpenReadiness applyTurnOutcomeUnderLock(String gameId, Game game, TurnOutcome outcome,
            boolean notifyImmediateAutoAdvance) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        switch (outcome.type()) {
            case NEXT_PLAYER, PHASE_ADVANCED -> {
                game.clearScheduledTask(ScheduledGameTask.AUTO_ADVANCE);
                runtimeTimers.cancelTask(gameId, ScheduledGameTask.AUTO_ADVANCE);
                if (game.getCurrentPlayer() != null) {
                    scheduling.scheduleTurnTimeout(gameId);
                } else {
                    game.clearScheduledTask(ScheduledGameTask.TURN_TIMEOUT);
                    runtimeTimers.cancelTask(gameId, ScheduledGameTask.TURN_TIMEOUT);
                }
                gameStateService.broadcastGameState(gameId, game);
            }
            case AUTO_ADVANCING -> {
                game.clearScheduledTask(ScheduledGameTask.TURN_TIMEOUT);
                runtimeTimers.cancelTask(gameId, ScheduledGameTask.TURN_TIMEOUT);
                if (game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE) == null) {
                    scheduling.scheduleAutoAdvance(gameId);
                }
                if (notifyImmediateAutoAdvance) {
                    gameStateService.broadcastAutoAdvanceNotification(gameId, game);
                } else {
                    gameStateService.broadcastGameStateWithAutoAdvance(gameId, game, "Auto-advancing...");
                }
            }
            case SHOWDOWN -> {
                game.clearScheduledTask(ScheduledGameTask.AUTO_ADVANCE);
                game.clearScheduledTask(ScheduledGameTask.TURN_TIMEOUT);
                runtimeTimers.cancelTask(gameId, ScheduledGameTask.TURN_TIMEOUT);
                if (schedulePostShowdownProgression(gameId, game)) {
                    return new OpenReadiness(game, outcome);
                }
                publishShowdown(gameId, game, outcome);
            }
        }
        return null;
    }

    /** Publishes a deferred showdown after the immediate readiness transition. */
    void publishPendingShowdown(String gameId, OpenReadiness continuation) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        if (continuation.pendingShowdown() != null) {
            publishShowdown(gameId, continuation.game(), continuation.pendingShowdown());
        }
    }

    private void publishShowdown(String gameId, Game game, TurnOutcome outcome) {
        gameStateService.broadcastShowdownResults(gameId, game, outcome.winners(), outcome.winningsPerPlayer());
    }

    /** Records the terminal or ready-opening intent, returning true for an immediate ready opening. */
    private boolean schedulePostShowdownProgression(String gameId, Game game) {
        if (game.isTournamentOver()) {
            scheduling.scheduleGameEnd(gameId, FINAL_SHOWDOWN_DISPLAY_DELAY_MS);
            return false;
        }
        return scheduling.scheduleReadyCountdownOpen(gameId, roundEndDisplayDelayMs.getAsLong())
                == GameTaskScheduler.ReadyOpening.OPEN_IMMEDIATELY;
    }

    /** Deals one street or settles the final street of an all-in hand. */
    boolean advanceAutoAdvanceStep(String gameId) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        Game game = activeGames.get(gameId);
        if (game == null) {
            return true;
        }

        if (game.isForfeitPending()) return true;
        if (game.isGameOver()) return true;
        if (game.isHandSettled()
                || game.isTerminalCarryOverSettled()) {
            logger.info("Skipping stale auto-advance callback for settled game {}", gameId);
            return true;
        }
        return switch (game.getCurrentPhase()) {
            case PRE_FLOP -> {
                game.dealFlop();
                gameStateService.broadcastGameStateWithAutoAdvance(gameId, game, "Dealing flop...");
                yield false;
            }
            case FLOP -> {
                game.dealTurn();
                gameStateService.broadcastGameStateWithAutoAdvance(gameId, game, "Dealing turn...");
                yield false;
            }
            case TURN -> {
                game.dealRiver();
                gameStateService.broadcastGameStateWithAutoAdvance(gameId, game, "Dealing river...");
                yield false;
            }
            case RIVER -> {
                int potBeforeDistribution = game.getPot();
                List<Player> winners = game.conductShowdown();
                int winningsPerPlayer = winners.isEmpty() ? 0 : potBeforeDistribution / winners.size();
                gameStateService.broadcastShowdownResults(gameId, game, winners, winningsPerPlayer);
                gameStateService.broadcastAutoAdvanceComplete(gameId, game);
                yield true;
            }
            case SHOWDOWN -> {
                logger.warn("Auto-advance step invoked for game {} already in SHOWDOWN - skipping duplicate distribution", gameId);
                yield true;
            }
        };
    }

    /** Advances a claimed auto-advance callback and records any subsequent work. */
    OpenReadiness applyAutoAdvanceTimeout(String gameId, Game game) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        if (game.getCurrentPhase() == GamePhase.SHOWDOWN) {
            game.clearScheduledTask(ScheduledGameTask.AUTO_ADVANCE);
            return null;
        }
        boolean complete = advanceAutoAdvanceStep(gameId);
        if (complete) {
            return schedulePostShowdownProgression(gameId, game) ? new OpenReadiness(game, null) : null;
        } else {
            scheduling.scheduleAutoAdvance(gameId);
        }
        return null;
    }
}
