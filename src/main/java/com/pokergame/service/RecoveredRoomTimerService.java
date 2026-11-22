package com.pokergame.service;

import static java.util.Objects.requireNonNull;

import com.pokergame.config.GameTimingProperties;
import com.pokergame.config.RoomTimingProperties;
import com.pokergame.config.WebSocketEventListener;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.transaction.DurableMutation;
import com.pokergame.persistence.transaction.GameId;
import com.pokergame.persistence.transaction.PostSaveActions;
import com.pokergame.service.GameTaskScheduler.ScheduledTimer;
import com.pokergame.util.MdcUtils;
import com.pokergame.util.MdcUtils.MdcScope;
import com.pokergame.util.RoomCoordinator;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Restores runtime inactivity, disconnect grace, and {@code ScheduledGameTask} timers for
 * recovered rooms and games after server startup.
 */
@Service
public class RecoveredRoomTimerService {
    private static final Logger logger = LoggerFactory.getLogger(RecoveredRoomTimerService.class);
    private final RoomService roomService;
    private final ActiveGameRegistry activeGames;
    private final GameStateService gameStateService;
    private final GameTaskScheduler taskScheduler;
    private final WebSocketEventListener webSocketEventListener;
    private final RoomCoordinator roomCoordinator;
    private final Clock clock;
    private final GameTimingProperties timings;
    private final RoomTimingProperties roomTimings;

    RecoveredRoomTimerService(
            RoomService roomService,
            ActiveGameRegistry activeGames,
            GameStateService gameStateService,
            GameTaskScheduler taskScheduler,
            WebSocketEventListener webSocketEventListener,
            RoomCoordinator roomCoordinator,
            Clock clock,
            GameTimingProperties timings,
            RoomTimingProperties roomTimings) {
        this.roomService = roomService;
        this.activeGames = activeGames;
        this.gameStateService = gameStateService;
        this.taskScheduler = taskScheduler;
        this.webSocketEventListener = webSocketEventListener;
        this.roomCoordinator = roomCoordinator;
        this.clock = clock;
        this.timings = timings;
        this.roomTimings = roomTimings;
    }

    /**
     * Repairs missing {@code ReadyGate} deadlines after startup recovery, schedules active
     * {@code ScheduledGameTask} timers once the updated {@code RoomSnapshot} is saved, and
     * broadcasts the updated game state if the gate changed.
     *
     * @param gameId the unique ID of the recovered game
     */
    @DurableMutation
    public void resumeRecoveredTimers(@GameId String gameId) {
        Game game = activeGames.get(gameId);
        if (game == null) {
            return;
        }
        try (MdcScope _ = MdcUtils.withRoomId(gameId)) {
            boolean normalized = game.repairRecoveredReadyGate(clock.millis() + timings.readyCountdownMs());
            List<ScheduledTimer> timers = GameTaskScheduler.listActiveTimers(game);
            PostSaveActions.runAfterSave(() -> timers.forEach(taskScheduler::registerTimer));
            if (normalized) {
                gameStateService.broadcastGameState(game);
            }
            logger.info("Resumed {} recovered timers for game {}", timers.size(), gameId);
        }
    }

    /**
     * Schedules the lobby or active-game inactivity timer from the recovered room deadline while
     * the caller holds the room lock.
     *
     * @param room the recovered room
     * @param game the recovered game, or {@code null} if the room is still a lobby
     */
    void scheduleInactivityTimer(Room room, Game game) {
        requireNonNull(room, "room must not be null");
        String roomId = room.getRoomId();
        roomCoordinator.assertHeldByCurrentThread(roomId);
        Long deadline = room.getInactivityDeadlineEpochMs();
        if (deadline == null) {
            return;
        }
        if (game != null) {
            if (!game.isGameOver()) {
                taskScheduler.scheduleGameInactivity(roomId, deadline);
            }
        } else {
            roomService.scheduleLobbyInactivity(roomId, deadline);
        }
    }

    /**
     * Marks each recovered player disconnected, clears next-hand readiness on game players, and
     * schedules each player's reconnect grace deadline while the caller holds the room lock.
     *
     * @param room the recovered room
     * @param game the recovered game, or {@code null} if the room is still a lobby
     * @param now  the current server time in epoch milliseconds
     */
    void scheduleDisconnectGrace(Room room, Game game, long now) {
        requireNonNull(room, "room must not be null");
        String roomId = room.getRoomId();
        roomCoordinator.assertHeldByCurrentThread(roomId);
        long freshDeadline = now + roomTimings.disconnectGracePeriodMs();
        for (String playerName : room.getPlayers()) {
            Player gamePlayer = game == null ? null : game.findPlayerByName(playerName);
            long storedDeadline = gamePlayer == null || gamePlayer.getDisconnectDeadlineEpochMs() == null
                    ? 0L
                    : gamePlayer.getDisconnectDeadlineEpochMs();
            long deadline = Math.max(freshDeadline, storedDeadline);
            if (gamePlayer != null) {
                gamePlayer.setDisconnected(true);
                gamePlayer.setReadyForNextHand(false);
                gamePlayer.setDisconnectDeadlineEpochMs(deadline);
            }
            webSocketEventListener.scheduleRecoveredDisconnect(roomId, playerName, deadline);
        }
    }

    /**
     * Cancels any pending recovered disconnect timers queued for {@code roomId} during a partial
     * restore rollback while the caller holds the room lock.
     *
     * @param roomId the unique ID of the room being evicted
     */
    void cancelPendingDisconnects(String roomId) {
        requireNonNull(roomId, "roomId must not be null");
        roomCoordinator.assertHeldByCurrentThread(roomId);
        webSocketEventListener.cancelRoomPendingDisconnects(roomId);
    }
}
