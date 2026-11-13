package com.pokergame.service;

import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ConflictException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.model.TournamentEligibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Owns game completion, terminal review, and removal under the caller's room lease. */
final class GameCompletionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GameCompletionHandler.class);

    private final ActiveGameRegistry activeGames;
    private final RoomService roomService;
    private final GameStateService gameStateService;
    private final GameTimerRegistry runtimeTimers;
    private final GameTaskScheduler scheduling;
    private final RoomCoordinator roomCoordinator;
    private final Supplier<Clock> clock;
    private final LongSupplier cleanupDelayMs;

    GameCompletionHandler(ActiveGameRegistry activeGames, RoomService roomService, GameStateService gameStateService,
            GameTimerRegistry runtimeTimers, GameTaskScheduler scheduling, RoomCoordinator roomCoordinator,
            Supplier<Clock> clock, LongSupplier cleanupDelayMs) {
        this.activeGames = activeGames;
        this.roomService = roomService;
        this.gameStateService = gameStateService;
        this.runtimeTimers = runtimeTimers;
        this.scheduling = scheduling;
        this.roomCoordinator = roomCoordinator;
        this.clock = clock;
        this.cleanupDelayMs = cleanupDelayMs;
    }

    /** Settles and publishes a completed game in the current durable mutation. */
    void handleGameEnd(String gameId, boolean isForfeit) {
        handleGameEndInternal(gameId, isForfeit, null, Set.of());
    }

    /** Settles a forfeit with the original participant roster intact. */
    void handleGameEndWithForfeits(String gameId, Player preferredWinner, Set<String> forfeitedPlayers) {
        handleGameEndInternal(gameId, true, preferredWinner, forfeitedPlayers == null ? Set.of() : Set.copyOf(forfeitedPlayers));
    }

    /** Freezes the terminal result before broadcasting it and recording cleanup. */
    private void handleGameEndInternal(String gameId, boolean isForfeit, Player preferredWinner, Set<String> forfeitedPlayers) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        Game game = activeGames.get(gameId);
        if (game == null) return;

        Room room = roomService.getRoom(gameId);
        SettlementOutcome outcome = settleUnderLock(gameId, game, room, isForfeit, preferredWinner, forfeitedPlayers);
        if (!outcome.settledNow()) return;

        if (room != null) {
            room.setTerminal(true);
        }
        if (outcome.winner() == null) {
            logger.warn("Cannot broadcast game end for {} - no winner could be determined", gameId);
            return;
        }
        if (outcome.event() != null) {
            gameStateService.sendGameEnd(outcome.event());
        }
        scheduling.scheduleGameCleanup(gameId, cleanupDelayMs.getAsLong());
    }

    /** Abandons the game only if the captured inactivity deadline is still due. */
    void executeInactivityCleanup(String gameId, long expectedDeadlineEpochMs) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        Room room = roomService != null ? roomService.getRoom(gameId) : null;
        if (room == null) {
            return;
        }
        Game game = activeGames.get(gameId);
        if (game == null) {
            return;
        }
        if (game.isGameOver() && game.getRetainedTerminalResult() != null) {
            return;
        }
        if (!GameInactivityPolicy.matchesDeadline(room, expectedDeadlineEpochMs)) {
            logger.debug("Skipping inactivity cleanup: deadline changed for game {}", gameId);
            return;
        }
        if (!GameInactivityPolicy.hasElapsed(expectedDeadlineEpochMs, clock.get().millis())) {
            logger.debug("Skipping inactivity cleanup: deadline has not elapsed for game {}", gameId);
            return;
        }
        abandonGameDueToInactivity(gameId);
    }

    /** Freezes an abandonment result and schedules its retention window. */
    void abandonGameDueToInactivity(String gameId) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        runtimeTimers.cancelInactivity(gameId);
        Game game = activeGames.get(gameId);
        if (game == null) return;
        Room room = roomService != null ? roomService.getRoom(gameId) : null;
        if (game.isGameOver() && game.getRetainedTerminalResult() != null) {
            return;
        }
        game.markGameOver();
        game.closeReadyCountdown();
        game.invalidateProgressionTasks();
        runtimeTimers.cancelAllForGame(gameId, true);
        game.clearScheduledTask(ScheduledGameTask.AUTO_ADVANCE);
        game.clearScheduledTask(ScheduledGameTask.TURN_TIMEOUT);
        game.clearScheduledTask(ScheduledGameTask.READY_TIMEOUT);
        game.clearScheduledTask(ScheduledGameTask.READY_OPEN);
        game.clearScheduledTask(ScheduledGameTask.NEW_HAND);
        GameEndResponse event = captureAbandonmentUnderLock(gameId, game, room);

        if (room != null) {
            room.setTerminal(true);
        }
        if (event != null) {
            gameStateService.sendGameEnd(event);
        }
        long displayDelayMs = cleanupDelayMs.getAsLong();
        scheduling.scheduleGameCleanup(gameId, displayDelayMs);
        logger.info("Game {} abandoned due to inactivity; scheduled {}ms cleanup", gameId, displayDelayMs);
    }

    /** Deletes a terminal room or a game with accepted cleanup intent. */
    void executeTerminalCleanup(String gameId) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        Game game = activeGames.get(gameId);
        Room room = roomService != null ? roomService.getRoom(gameId) : null;
        if (game == null && room == null) {
            return;
        }
        if (game != null) {
            if (!game.isGameOver() && game.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP) == null) {
                logger.debug("Skipping terminal cleanup for uncompleted game {}", gameId);
                return;
            }
        }
        performGameCleanup(gameId);
    }

    /** Removes the game, all timers, and its room in the current mutation. */
    void performGameCleanup(String gameId) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        runtimeTimers.cancelAllForGame(gameId, false);
        activeGames.remove(gameId);
        if (gameStateService != null) {
            gameStateService.clearRetainedSettlement(gameId);
        }
        roomService.destroyRoom(gameId);
        logger.info("Game {} and associated room cleaned up", gameId);
    }

    /** Resolves an authorized terminal review under the room lease. */
    GameEndResponse getGameReview(String gameId, String playerName) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        Game game = activeGames.get(gameId);
        if (game == null || isGameExpired(game)) {
            throw new ResourceNotFoundException("Game not found");
        }

        if (!game.isGameOver()) {
            throw new ConflictException("Game has not finished");
        }

        Room room = roomService != null ? roomService.getRoom(gameId) : null;
        return resolveTerminalResultForParticipant(gameId, game, room, playerName, false);
    }

    /** Reports whether a supplied game has passed its cleanup deadline. */
    boolean isGameExpired(Game game) {
        return game == null || game.isCleanupExpired(clock.get().millis());
    }

    /** Rejects late activity after abandonment in the same durable mutation. */
    void rejectExpiredActivity(String gameId, Room room) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        if (GameInactivityPolicy.isExpired(GameInactivityPolicy.deadline(room), clock.get().millis())) {
            abandonGameDueToInactivity(gameId);
            throw new BadRequestException("Game has expired due to inactivity.", "GAME_EXPIRED", "Game has expired due to inactivity");
        }
    }

    /**
     * Cleans up an abandoned game if the room is empty and has no remaining contenders.
     *
     * @param gameId identifier of the game
     * @param room   current room aggregate
     * @param game   active game aggregate
     */
    void cleanupAbandonedGameIfRoomEmpty(String gameId, Room room, Game game) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        if (TournamentEligibility.countContenders(game) != 0 || !room.getPlayers().isEmpty()) return;
        game.closeReadyCountdown();
        for (ScheduledGameTask task : ScheduledGameTask.values()) game.clearScheduledTask(task);
        runtimeTimers.cancelAllForGame(gameId, false);
        activeGames.remove(gameId);
        if (gameStateService != null) {
            gameStateService.clearRetainedSettlement(gameId);
        }
    }

    /** Result of attempting to freeze and settle a terminal game under the game lock. */
    record SettlementOutcome(boolean settledNow, Player winner, GameEndResponse event) { }

    /**
     * Settles an ending game under the caller's room coordination lease, cancels
     * non-cleanup runtime timers, clears retained hand settlement, and freezes the
     * terminal {@link GameEndResponse}.
     */
    SettlementOutcome settleUnderLock(
            String gameId,
            Game game,
            Room room,
            boolean isForfeit,
            Player preferredWinner,
            Set<String> forfeitedPlayers) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        if (game.isForfeitPending() && preferredWinner == null) {
            return new SettlementOutcome(false, null, null);
        }
        if (game.isGameOver() && game.isTerminalCarryOverSettled()) {
            return new SettlementOutcome(false, null, null);
        }
        Player winner = game.settleTerminalState(preferredWinner, forfeitedPlayers);
        runtimeTimers.cancelAllForGame(gameId, true);
        if (gameStateService != null) {
            gameStateService.clearRetainedSettlement(gameId);
        }
        int maxPlayers = room == null ? 0 : room.getMaxPlayers();
        GameEndResponse event = game.getRetainedTerminalResult();
        if (event == null && gameStateService != null) {
            event = gameStateService.captureGameEnd(gameId, game, winner, isForfeit, maxPlayers);
            game.setRetainedTerminalResult(event);
        }
        return new SettlementOutcome(true, winner, event);
    }

    /** Freezes an abandonment result without paying out the unfinished pot. */
    GameEndResponse captureAbandonmentUnderLock(String gameId, Game game, Room room) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        GameEndResponse event = null;
        if (gameStateService != null) {
            gameStateService.clearRetainedSettlement(gameId);
            int maxPlayers = room != null ? room.getMaxPlayers() : game.getPlayers().size();
            event = gameStateService.captureAbandonment(gameId, game, maxPlayers);
        }
        game.setRetainedTerminalResult(event);
        return event;
    }

    /**
     * Resolves or synthesizes the terminal {@link GameEndResponse} for an already
     * finished game and verifies that {@code playerName} is part of its terminal roster.
     * Read-only callers ({@code persistIfSynthesized == false}) project the fallback
     * response without incrementing {@code stateRevision} or mutating durable state.
     */
    GameEndResponse resolveTerminalResultForParticipant(
            String gameId,
            Game game,
            Room room,
            String playerName,
            boolean persistIfSynthesized) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        GameEndResponse retained = game.getRetainedTerminalResult();
        if (retained == null) {
            Player winner = game.resolveWinnerOrFallback(null);
            if (winner != null && gameStateService != null) {
                int maxPlayers = room != null ? room.getMaxPlayers() : game.getPlayers().size();
                boolean isForfeit = game.isFallbackForfeit();
                if (persistIfSynthesized) {
                    retained = gameStateService.captureGameEnd(gameId, game, winner, isForfeit, maxPlayers);
                    game.setRetainedTerminalResult(retained);
                } else {
                    retained = gameStateService.projectGameEnd(gameId, game, winner, isForfeit, maxPlayers);
                }
            } else if (gameStateService != null) {
                int maxPlayers = room != null ? room.getMaxPlayers() : game.getPlayers().size();
                if (persistIfSynthesized) {
                    retained = gameStateService.captureAbandonment(gameId, game, maxPlayers);
                    game.setRetainedTerminalResult(retained);
                } else {
                    retained = gameStateService.projectAbandonment(gameId, game, maxPlayers);
                }
            }
        }
        if (retained == null) {
            throw new ResourceNotFoundException("Game not found");
        }
        if (!game.isParticipantInTerminalRoster(playerName, retained)) {
            throw new UnauthorisedActionException("You are not part of this game's terminal result.");
        }
        return retained;
    }

    /**
     * Ensures a retained terminal result is captured for a disconnect-claim winner if not already present.
     */
    GameEndResponse ensureRetainedClaimResult(String gameId, Game game, Room room, String playerName) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        GameEndResponse retained = game.getRetainedTerminalResult();
        if (retained == null && game.isGameOver()) {
            Player claimant = game.findPlayerByName(playerName);
            if (claimant != null && gameStateService != null) {
                retained = gameStateService.captureGameEnd(gameId, game, claimant, true, room.getMaxPlayers());
                game.setRetainedTerminalResult(retained);
            }
        }
        return retained;
    }
}
