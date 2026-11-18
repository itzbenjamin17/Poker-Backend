package com.pokergame.service;

import static java.util.Objects.requireNonNull;

import com.pokergame.model.Game;
import com.pokergame.model.Room;
import com.pokergame.persistence.RoomAggregate;
import com.pokergame.persistence.RoomAggregates;
import com.pokergame.util.RoomCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Lets persistence read, swap and remove a room together with its game, keeping the room's
 * game flags and timers consistent with whichever game is live.
 */
@Component
class RoomAggregateStore implements RoomAggregates {
    private static final Logger logger = LoggerFactory.getLogger(RoomAggregateStore.class);
    private final RoomRegistry rooms;
    private final ActiveGameRegistry activeGames;
    private final GameTimerRegistry timerRegistry;
    private final GameTaskScheduler taskScheduler;
    private final RoomService roomService;
    private final RoomCoordinator roomCoordinator;

    RoomAggregateStore(
            RoomRegistry rooms,
            ActiveGameRegistry activeGames,
            GameTimerRegistry timerRegistry,
            GameTaskScheduler taskScheduler,
            RoomService roomService,
            RoomCoordinator roomCoordinator) {
        this.rooms = rooms;
        this.activeGames = activeGames;
        this.timerRegistry = timerRegistry;
        this.taskScheduler = taskScheduler;
        this.roomService = roomService;
        this.roomCoordinator = roomCoordinator;
    }

    @Override
    public RoomAggregate find(String roomId) {
        requireNonNull(roomId, "roomId must not be null");
        Room room = rooms.get(roomId);
        return room == null ? null : new RoomAggregate(room, activeGames.get(roomId));
    }

    @Override
    public void replace(RoomAggregate aggregate) {
        requireNonNull(aggregate, "aggregate must not be null");
        install(aggregate.room(), aggregate.game());
        Game game = aggregate.game();
        if (game == null) {
            return;
        }
        // Start the timers now, not through PostSaveActions. The failed change's queue is already
        // discarded, so a queued timer would never start.
        String roomId = aggregate.room().getRoomId();
        GameTaskScheduler.listActiveTimers(game).forEach(taskScheduler::registerTimer);
        if (aggregate.room().getInactivityDeadlineEpochMs() != null && !game.isGameOver()) {
            taskScheduler.scheduleGameInactivity(roomId, aggregate.room().getInactivityDeadlineEpochMs());
        }
    }

    @Override
    public void remove(String roomId) {
        requireNonNull(roomId, "roomId must not be null");
        roomCoordinator.assertHeldByCurrentThread(roomId);
        timerRegistry.cancelAllForGame(roomId, false);
        activeGames.remove(roomId);
        roomService.cancelLobbyInactivity(roomId);
        Room room = rooms.remove(roomId);
        if (room != null) {
            logger.info("Room evicted from memory: {} (ID: {})", room.getRoomName(), roomId);
        }
    }

    /**
     * Makes {@code room} and {@code game} the live pair without scheduling any timers. Timers of a
     * game that was live before are cancelled. A {@code null} game leaves the room as a lobby.
     *
     * @param room the room to make live
     * @param game the room's game, or {@code null} for a lobby
     */
    void install(Room room, Game game) {
        requireNonNull(room, "room must not be null");
        String roomId = room.getRoomId();
        roomCoordinator.assertHeldByCurrentThread(roomId);
        Game existing = activeGames.get(roomId);
        if (existing != game) {
            timerRegistry.cancelAllForGame(roomId, false);
        }
        rooms.put(room);
        if (game == null) {
            activeGames.remove(roomId);
            room.setGameStarted(false);
            room.setTerminal(false);
        } else {
            activeGames.replace(game);
            room.setTerminal(game.isGameOver());
            room.setGameStarted(true);
        }
    }
}
