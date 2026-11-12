package com.pokergame.service;

import com.pokergame.model.Game;
import com.pokergame.model.Room;
import com.pokergame.persistence.transaction.DurableTransactionContext;
import com.pokergame.service.GameTaskScheduler.TaskIntent;
import com.pokergame.util.MdcUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Installs and evicts restored games and resumes their accepted timer intent. */
final class GameRecoveryService {
    private static final Logger logger = LoggerFactory.getLogger(GameRecoveryService.class);

    private final ActiveGameRegistry activeGames;
    private final RoomService roomService;
    private final GameStateService gameStateService;
    private final GameTimerRegistry timerRegistry;
    private final RoomCoordinator roomCoordinator;
    private final GameTaskScheduler scheduling;

    GameRecoveryService(ActiveGameRegistry activeGames, RoomService roomService, GameStateService gameStateService,
            GameTimerRegistry timerRegistry, RoomCoordinator roomCoordinator,
            GameTaskScheduler scheduling) {
        this.activeGames = activeGames;
        this.roomService = roomService;
        this.gameStateService = gameStateService;
        this.timerRegistry = timerRegistry;
        this.roomCoordinator = roomCoordinator;
        this.scheduling = scheduling;
    }

    /** Replaces a game with a detached, already rehydrated instance under its room lease. */
    void restoreGame(Game game) {
        String gameId = game.getGameId();
        roomCoordinator.assertHeldByCurrentThread(gameId);
        Room room = roomService.getRoom(gameId);
        applyRestoreGame(game, room, activeGames.get(gameId));
    }

    private void applyRestoreGame(Game game, Room room, Game existing) {
        String gameId = game.getGameId();
        roomCoordinator.assertHeldByCurrentThread(gameId);
        if (existing != null && existing != game) {
            timerRegistry.cancelAllForGame(gameId, false);
        }
        if (gameStateService != null) {
            gameStateService.clearRetainedSettlement(gameId);
        }
        activeGames.replace(game);
        if (room != null) {
            room.setTerminal(game.isGameOver());
            room.setGameStarted(true);
        }
    }

    /** Removes the game and its registrations while retaining the room container. */
    void evictGame(String gameId) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        Room room = roomService.getRoom(gameId);
        timerRegistry.cancelAllForGame(gameId, false);
        activeGames.remove(gameId);
        if (gameStateService != null) {
            gameStateService.clearRetainedSettlement(gameId);
        }
        if (room != null) {
            room.setGameStarted(false);
            room.setTerminal(false);
        }
    }

    /** Repairs ready state at startup and schedules work after the recovery mutation. */
    void resumeRecoveredTimers(String gameId, long configuredReadyCountdownMs) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        Game game = activeGames.get(gameId);
        if (game == null) return;
        MdcUtils.runWithRoomId(gameId, () -> {
            boolean normalized = game.normalizeRecoveredReadyCountdown(System.currentTimeMillis() + configuredReadyCountdownMs);
            List<TaskIntent> intents = GameTaskScheduler.captureAllIntents(gameId, game);
            DurableTransactionContext.afterCommit(() -> intents.forEach(scheduling::scheduleRuntimeTask));
            if (normalized) gameStateService.broadcastGameState(gameId, game);
            logger.info("Resumed {} recovered timers for game {}", intents.size(), gameId);
        });
    }

    /** Restores exact accepted timers without joining the failed mutation's effect queue. */
    void resumeRollbackTimers(String gameId) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        Game game = activeGames.get(gameId);
        if (game == null) return;
        GameTaskScheduler.captureAllIntents(gameId, game).forEach(scheduling::scheduleRuntimeTask);
        Room room = roomService != null ? roomService.getRoom(gameId) : null;
        if (room != null && room.getInactivityDeadlineEpochMs() != null && !game.isGameOver()) {
            scheduling.scheduleGameInactivity(gameId, room.getInactivityDeadlineEpochMs());
        }
    }
}
