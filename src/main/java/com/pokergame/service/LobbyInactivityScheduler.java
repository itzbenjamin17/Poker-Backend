package com.pokergame.service;

import static java.util.Objects.requireNonNull;

import com.pokergame.model.Room;
import com.pokergame.util.LockLease;
import com.pokergame.util.RoomCoordinator;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Closes unstarted lobby rooms that stay inactive past their deadline. Runtime timers live in
 * {@link GameTimerRegistry}, next to the game timers.
 * <p>
 * The timer callback is an entry point: it takes the room lock, checks the room's current
 * deadline, and only when the lobby has really expired calls
 * {@link RoomService#closeLobbyDueToInactivity(String)} through the Spring proxy. A check
 * that finds the lobby still active therefore writes nothing to the save log.
 * </p>
 */
@Component
class LobbyInactivityScheduler {
    private static final Logger logger = LoggerFactory.getLogger(LobbyInactivityScheduler.class);

    private final GameTimerRegistry timerRegistry;
    private final RoomRegistry rooms;
    private final RoomCoordinator roomCoordinator;
    private final Clock clock;
    private final ObjectProvider<RoomService> roomServiceProvider;

    LobbyInactivityScheduler(
            GameTimerRegistry timerRegistry,
            RoomRegistry rooms,
            RoomCoordinator roomCoordinator,
            Clock clock,
            ObjectProvider<RoomService> roomServiceProvider) {
        this.timerRegistry = timerRegistry;
        this.rooms = rooms;
        this.roomCoordinator = roomCoordinator;
        this.clock = clock;
        this.roomServiceProvider = roomServiceProvider;
    }

    /**
     * Schedules (or replaces) the timer that closes the lobby if it is still inactive at
     * {@code deadlineEpochMs}. A scheduler that rejects the task is logged and otherwise ignored.
     *
     * @param roomId          the unique ID of the room
     * @param deadlineEpochMs expiration epoch time in milliseconds
     */
    void schedule(String roomId, long deadlineEpochMs) {
        requireNonNull(roomId, "roomId must not be null");
        try {
            timerRegistry.scheduleLobbyInactivity(roomId, deadlineEpochMs, () -> closeWhenTimerFires(roomId));
        } catch (RuntimeException e) {
            logger.warn("Scheduler rejected lobby inactivity for room {}", roomId, e);
        }
    }

    /**
     * Cancels the pending lobby inactivity timer for {@code roomId}, if any.
     *
     * @param roomId the unique ID of the room
     */
    void cancel(String roomId) {
        requireNonNull(roomId, "roomId must not be null");
        timerRegistry.cancelLobbyInactivity(roomId);
    }

    /**
     * Closes the lobby for {@code roomId} if it is unstarted and its inactivity deadline has passed.
     * The caller must hold the room lock.
     *
     * @param roomId the unique ID of the room
     * @return {@code true} if the lobby had expired and was closed
     */
    boolean closeIfExpired(String roomId) {
        roomCoordinator.assertHeldByCurrentThread(roomId);
        Room room = rooms.get(roomId);
        if (room == null || room.isGameStarted() || !room.isInactivityExpired(clock.millis())) {
            return false;
        }
        roomServiceProvider.getObject().closeLobbyDueToInactivity(roomId);
        return true;
    }

    private void closeWhenTimerFires(String roomId) {
        try (LockLease _ = roomCoordinator.acquire(roomId)) {
            closeIfExpired(roomId);
        }
    }
}
