package com.pokergame.service;

import static java.util.Objects.requireNonNull;

import com.pokergame.model.Game;
import com.pokergame.model.Room;
import com.pokergame.persistence.RecoveryDecision;
import com.pokergame.persistence.RoomRecoveryHandler;
import com.pokergame.persistence.snapshot.RecoveredRoom;
import com.pokergame.util.RoomCoordinator;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Applies domain restart rules to recovered rooms and games, including expiry checks,
 * inactivity abandonment, and post-restart timer setup delegation.
 */
@Service
public class RoomRestoreService implements RoomRecoveryHandler {
    private static final Logger logger = LoggerFactory.getLogger(RoomRestoreService.class);
    private final RoomService roomService;
    private final GameLifecycleService gameLifecycleService;
    private final RoomAggregateStore roomAggregates;
    private final RecoveredRoomTimerService timerService;
    private final RoomCoordinator roomCoordinator;
    private final Clock clock;

    public RoomRestoreService(
            RoomService roomService,
            GameLifecycleService gameLifecycleService,
            RoomAggregateStore roomAggregates,
            RecoveredRoomTimerService timerService,
            RoomCoordinator roomCoordinator,
            Clock clock) {
        this.roomService = roomService;
        this.gameLifecycleService = gameLifecycleService;
        this.roomAggregates = roomAggregates;
        this.timerService = timerService;
        this.roomCoordinator = roomCoordinator;
        this.clock = clock;
    }

    /**
     * Applies domain restart rules to a single recovered room while the caller holds its room lock.
     *
     * @param recovered the deserialized room and optional game snapshot
     * @return {@link RecoveryDecision#KEEP} when the room remains registered, or
     *         {@link RecoveryDecision#DISCARD} when its WAL should be deleted
     */
    @Override
    public RecoveryDecision restore(RecoveredRoom recovered) {
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

        try {
            roomAggregates.install(room, game);
            timerService.scheduleInactivityTimer(room, game);
            timerService.scheduleDisconnectGrace(room, game, now);
            if (game != null) {
                timerService.resumeRecoveredTimers(roomId);
            }
        } catch (RuntimeException e) {
            evictPartialRestore(roomId);
            throw e;
        }
        return RecoveryDecision.KEEP;
    }

    private void evictPartialRestore(String roomId) {
        timerService.cancelPendingDisconnects(roomId);
        roomAggregates.remove(roomId);
    }
}
