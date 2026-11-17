package com.pokergame.service;

import com.pokergame.config.GameTimingProperties;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ConflictException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.model.GameEligibility;
import java.time.Clock;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handles settling completed games, abandoning inactive games, retrieving post-game review
 * results, and deleting finished games and rooms.
 */
@Component
class GameCompletionHandler {
    private static final Logger logger = LoggerFactory.getLogger(GameCompletionHandler.class);
    private final ActiveGameRegistry activeGames;
    private final RoomService roomService;
    private final GameStateService gameStateService;
    private final GameTimerRegistry runtimeTimers;
    private final GameTaskScheduler taskScheduler;
    private final Clock clock;
    private final GameTimingProperties timings;

    GameCompletionHandler(
            ActiveGameRegistry activeGames,
            RoomService roomService,
            GameStateService gameStateService,
            GameTimerRegistry runtimeTimers,
            GameTaskScheduler taskScheduler,
            Clock clock,
            GameTimingProperties timings) {
        this.activeGames = activeGames;
        this.roomService = roomService;
        this.gameStateService = gameStateService;
        this.runtimeTimers = runtimeTimers;
        this.taskScheduler = taskScheduler;
        this.clock = clock;
        this.timings = timings;
    }

    /**
     * Settles a finished non-forfeit game and broadcasts the final winner result before scheduling
     * cleanup.
     *
     * @param gameId the unique ID of the game
     */
    void handleGameEnd(String gameId) {
        handleGameEndInternal(gameId, false, null, Set.of());
    }

    /**
     * Settles a forfeited game while keeping the full player roster intact in the final result.
     *
     * @param gameId           the unique ID of the game
     * @param preferredWinner  the player declared winner, if determined before settlement
     * @param forfeitedPlayers names of players who forfeited
     */
    void handleGameEndWithForfeits(String gameId, Player preferredWinner, Set<String> forfeitedPlayers) {
        Set<String> forfeits = forfeitedPlayers == null ? Set.of() : Set.copyOf(forfeitedPlayers);
        handleGameEndInternal(gameId, true, preferredWinner, forfeits);
    }

    /**
     * Abandons an active game due to inactivity only if {@code expectedDeadlineEpochMs} still
     * matches the room's current deadline and has expired.
     *
     * @param gameId                  the unique ID of the game
     * @param expectedDeadlineEpochMs the inactivity deadline captured when the timer was scheduled
     */
    void abandonGameIfInactive(String gameId, long expectedDeadlineEpochMs) {
        Room room = roomService.getRoom(gameId);
        if (room == null) {
            return;
        }
        // This method is called asynchronously by a background thread.
        // The inactivity deadline may have changed since the timer was scheduled (because a player acted).
        if (!room.hasInactivityDeadline(expectedDeadlineEpochMs)) {
            logger.debug("Skipping inactivity abandonment: deadline changed for game {}", gameId);
            return;
        }
        if (!room.isInactivityExpired(clock.millis())) {
            logger.debug("Skipping inactivity abandonment: deadline has not elapsed for game {}", gameId);
            return;
        }
        abandonGameDueToInactivity(gameId);
    }

    /**
     * Ends an active game due to inactivity, cancels active hand timers, broadcasts an
     * abandonment result, and schedules room cleanup.
     *
     * @param gameId the unique ID of the game
     */
    void abandonGameDueToInactivity(String gameId) {
        runtimeTimers.cancelInactivity(gameId);
        Game game = activeGames.get(gameId);
        if (game == null) {
            return;
        }
        Room room = roomService.getRoom(gameId);
        if (game.isGameOver() && game.getGameEndResult() != null) {
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
        GameEndResponse event = gameStateService.advanceRevisionAndBuildAbandonment(game);
        game.setGameEndResult(event);

        if (room != null) {
            room.setTerminal(true);
        }
        gameStateService.sendGameEnd(event);
        long displayDelayMs = timings.gameEndDisplayDelayMs();
        taskScheduler.scheduleGameCleanup(gameId, displayDelayMs);
        logger.info("Game {} abandoned due to inactivity; scheduled {}ms cleanup", gameId, displayDelayMs);
    }

    /**
     * Cancels all timers for {@code gameId}, removes the game from memory, and destroys its room.
     *
     * @param gameId the unique ID of the game and room to delete
     */
    void deleteGameAndRoom(String gameId) {
        runtimeTimers.cancelAllForGame(gameId, false);
        activeGames.remove(gameId);
        roomService.destroyRoom(gameId);
        logger.info("Game {} and associated room deleted", gameId);
    }

    /**
     * Returns the saved final game summary for {@code playerName} after verifying that the game
     * is finished and the player participated in it.
     *
     * @param gameId     the unique ID of the game
     * @param playerName the requesting player's name
     * @return the final {@link GameEndResponse}
     * @throws ResourceNotFoundException   if the game does not exist or has expired
     * @throws ConflictException           if the game has not finished yet
     * @throws UnauthorisedActionException if {@code playerName} was not in the finished game
     * @throws IllegalStateException       if the finished game has no stored game end result
     */
    GameEndResponse getGameReview(String gameId, String playerName) {
        Game game = activeGames.get(gameId);
        if (game == null || isGameExpired(game)) {
            throw new ResourceNotFoundException("Game not found");
        }
        if (!game.isGameOver()) {
            throw new ConflictException("Game has not finished");
        }

        GameEndResponse result = game.getGameEndResult();
        if (result == null) {
            throw new IllegalStateException("Finished game " + gameId + " has no stored game end result");
        }
        if (!wasPlayerInGame(game, result, playerName)) {
            throw new UnauthorisedActionException("You are not part of this game's terminal result.");
        }
        return result;
    }

    /**
     * Returns whether {@code game} is missing or has passed its cleanup deadline.
     *
     * @param game the game instance to check, or {@code null}
     * @return {@code true} if {@code game} is {@code null} or its cleanup timer has expired
     */
    boolean isGameExpired(Game game) {
        return game == null || game.isCleanupExpired(clock.millis());
    }

    /**
     * Rejects late player activity when the room's game inactivity deadline has already expired
     * without mutating room or game state.
     *
     * @param gameId the unique ID of the game
     * @param room   the room holding the inactivity deadline, or {@code null}
     * @throws BadRequestException if the room's inactivity deadline has elapsed
     */
    void rejectExpiredActivity(String gameId, Room room) {
        if (room != null && room.isInactivityExpired(clock.millis())) {
            logger.warn("Rejecting activity for expired game {} (now={})", gameId, clock.millis());
            throw new BadRequestException(
                    "Game has expired due to inactivity.",
                    "GAME_EXPIRED",
                    "Game has expired due to inactivity");
        }
    }
    /**
     * Clears and removes an abandoned game from memory once no non-eliminated players or room members remain.
     *
     * @param gameId the unique ID of the game
     * @param room   the backing room
     * @param game   the game instance
     */
    void cleanupAbandonedGameIfRoomEmpty(String gameId, Room room, Game game) {
        if (GameEligibility.countContenders(game) != 0 || !room.getPlayers().isEmpty()) {
            return;
        }
        game.closeReadyCountdown();
        for (ScheduledGameTask task : ScheduledGameTask.values()) {
            game.clearScheduledTask(task);
        }
        runtimeTimers.cancelAllForGame(gameId, false);
        activeGames.remove(gameId);
    }

    private void handleGameEndInternal(
            String gameId,
            boolean isForfeit,
            Player preferredWinner,
            Set<String> forfeitedPlayers) {
        Game game = activeGames.get(gameId);
        if (game == null) {
            return;
        }
        if (game.isForfeitPending() && preferredWinner == null) {
            return;
        }
        if (game.isGameOver() && (game.isFinalPotSettled() || game.isAbandoned())) {
            return;
        }

        Player winner = game.settleGameEnd(preferredWinner, forfeitedPlayers);
        runtimeTimers.cancelAllForGame(gameId, true);

        Room room = roomService.getRoom(gameId);
        if (room != null) {
            room.setTerminal(true);
        }

        GameEndResponse event = game.getGameEndResult();
        if (event == null) {
            event = gameStateService.advanceRevisionAndBuildGameEnd(game, winner, isForfeit);
            game.setGameEndResult(event);
        }
        gameStateService.sendGameEnd(event);
        taskScheduler.scheduleGameCleanup(gameId, timings.gameEndDisplayDelayMs());
    }

    private static boolean wasPlayerInGame(Game game, GameEndResponse result, String playerName) {
        boolean inFinalState = result != null
                && result.finalState() != null
                && result.finalState().players() != null
                && result.finalState().players().stream().anyMatch(p -> p.name().equals(playerName));
        boolean inGameRoster = game.getPlayers() != null
                && game.getPlayers().stream().anyMatch(p -> p.getName().equals(playerName));
        return inFinalState || inGameRoster;
    }
}
