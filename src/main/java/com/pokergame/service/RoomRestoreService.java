package com.pokergame.service;

import static java.util.Objects.requireNonNull;

import com.pokergame.config.GameTimingProperties;
import com.pokergame.config.WebSocketEventListener;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.RecoveryDecision;
import com.pokergame.persistence.RoomRecoveryHandler;
import com.pokergame.persistence.snapshot.RecoveredAggregate;
import com.pokergame.persistence.transaction.DurableMutation;
import com.pokergame.persistence.transaction.PostSaveActions;
import com.pokergame.service.GameTaskScheduler.ScheduledTimer;
import com.pokergame.util.MdcUtils;
import com.pokergame.util.MdcUtils.MdcScope;
import com.pokergame.util.RoomCoordinator;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Applies domain restart rules to recovered rooms and games, including expiry checks,
 * inactivity abandonment, disconnect grace scheduling, and timer resumption.
 */
@Service
public class RoomRestoreService implements RoomRecoveryHandler {
    private static final Logger logger = LoggerFactory.getLogger(RoomRestoreService.class);
    private final RoomService roomService;
    private final GameLifecycleService gameLifecycleService;
    private final RoomAggregateStore roomAggregates;
    private final GameStateService gameStateService;
    private final ActiveGameRegistry activeGames;
    private final GameTaskScheduler taskScheduler;
    private final WebSocketEventListener webSocketEventListener;
    private final RoomCoordinator roomCoordinator;
    private final Clock clock;
    private final GameTimingProperties timings;
    private final long disconnectGracePeriodMs;

    public RoomRestoreService(
            RoomService roomService,
            GameLifecycleService gameLifecycleService,
            RoomAggregateStore roomAggregates,
            GameStateService gameStateService,
            ActiveGameRegistry activeGames,
            GameTaskScheduler taskScheduler,
            WebSocketEventListener webSocketEventListener,
            RoomCoordinator roomCoordinator,
            Clock clock,
            GameTimingProperties timings,
            @Value("${poker.room.disconnect-grace-period-ms:120000}") long disconnectGracePeriodMs) {
        this.roomService = roomService;
        this.gameLifecycleService = gameLifecycleService;
        this.roomAggregates = roomAggregates;
        this.gameStateService = gameStateService;
        this.activeGames = activeGames;
        this.taskScheduler = taskScheduler;
        this.webSocketEventListener = webSocketEventListener;
        this.roomCoordinator = roomCoordinator;
        this.clock = clock;
        this.timings = timings;
        this.disconnectGracePeriodMs = disconnectGracePeriodMs;
    }

    /**
     * Applies domain restart rules to a single recovered room while the caller holds its room lock.
     *
     * @param recovered the deserialized room, host, and optional game snapshot
     * @return {@link RecoveryDecision#KEEP} when the room remains registered, or
     *         {@link RecoveryDecision#DISCARD} when its WAL should be deleted
     */
    @Override
    public RecoveryDecision restore(RecoveredAggregate recovered) {
        requireNonNull(recovered, "recovered must not be null");
        Room room = requireNonNull(recovered.room(), "recovered.room() must not be null");
        String roomId = room.getRoomId();
        roomCoordinator.assertHeldByCurrentThread(roomId);

        long now = clock.millis();
        Game game = recovered.game();
        if (game == null) {
            if (roomService.isLobbyExpired(room)) {
                logger.info("Lobby {} expired during restart downtime (deadline: {}, now: {}); deleting",
                        roomId, room.getInactivityDeadlineEpochMs(), now);
                return RecoveryDecision.DISCARD;
            }
        } else {
            if (game.isGameOver() && game.isCleanupExpired(now)) {
                logger.info("Game {} cleanup deadline expired during restart downtime (now: {}); deleting",
                        roomId, now);
                return RecoveryDecision.DISCARD;
            }
            if (room.isInactivityExpired(now) && !game.isGameOver()) {
                logger.info("Game {} expired due to room inactivity during restart downtime (deadline: {}, now: {}); abandoning",
                        roomId, room.getInactivityDeadlineEpochMs(), now);
                try {
                    roomAggregates.install(room, game);
                    gameLifecycleService.abandonGameDueToInactivity(roomId);
                } catch (RuntimeException e) {
                    evictPartialRestore(roomId);
                    throw e;
                }
                return RecoveryDecision.KEEP;
            }
        }

        long freshDeadline = now + disconnectGracePeriodMs;
        try {
            roomAggregates.install(room, game);
            if (game != null) {
                if (!game.isGameOver() && room.getInactivityDeadlineEpochMs() != null) {
                    taskScheduler.scheduleGameInactivity(roomId, room.getInactivityDeadlineEpochMs());
                }
            } else if (room.getInactivityDeadlineEpochMs() != null) {
                roomService.scheduleLobbyInactivity(roomId, room.getInactivityDeadlineEpochMs());
            }

            for (String playerName : room.getPlayers()) {
                Player gamePlayer = findGamePlayer(game, playerName);
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

            if (game != null) {
                self().resumeRecoveredTimers(roomId);
            }
        } catch (RuntimeException e) {
            evictPartialRestore(roomId);
            throw e;
        }
        return RecoveryDecision.KEEP;
    }

    /**
     * Repairs ready-gate timers after startup recovery and schedules all pending timers after save.
     *
     * @param gameId the unique ID of the recovered game
     */
    @DurableMutation(roomId = "#gameId")
    public void resumeRecoveredTimers(String gameId) {
        Game game = activeGames.get(gameId);
        if (game == null) {
            return;
        }
        try (MdcScope _ = MdcUtils.withRoomId(gameId)) {
            boolean normalized = game.repairRecoveredReadyGate(clock.millis() + timings.readyCountdownMs());
            List<ScheduledTimer> timers = GameTaskScheduler.listActiveTimers(gameId, game);
            PostSaveActions.runAfterSave(() -> timers.forEach(taskScheduler::registerTimer));
            if (normalized) {
                gameStateService.broadcastGameState(game);
            }
            logger.info("Resumed {} recovered timers for game {}", timers.size(), gameId);
        }
    }

    private RoomRestoreService self() {
        try {
            return (RoomRestoreService) AopContext.currentProxy();
        } catch (IllegalStateException e) {
            return this;
        }
    }

    private void evictPartialRestore(String roomId) {
        webSocketEventListener.cancelRoomPendingDisconnects(roomId);
        roomAggregates.remove(roomId);
    }

    private static Player findGamePlayer(Game game, String playerName) {
        if (game == null || game.getPlayers() == null) {
            return null;
        }
        return game.getPlayers().stream()
                .filter(player -> player != null && playerName.equals(player.getName()))
                .findFirst()
                .orElse(null);
    }
}
