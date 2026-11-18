package com.pokergame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.RoomAggregate;
import com.pokergame.testutil.MutableClock;
import com.pokergame.testutil.TestTimings;
import com.pokergame.util.LockLease;
import com.pokergame.util.RoomCoordinator;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;

/** Unit tests for {@link RoomAggregateStore}. */
@Tag("unit")
@DisplayName("RoomAggregateStore unit tests")
@ExtendWith(MockitoExtension.class)
class RoomAggregateStoreTest {

    private static final String ROOM_ID = "aggregate-room";
    private static final long NOW = 1_900_000_000_000L;

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private RoomService roomService;

    @Mock
    private GameLifecycleService gameLifecycleService;

    private RoomCoordinator coordinator;
    private RoomRegistry rooms;
    private ActiveGameRegistry activeGames;
    private RoomAggregateStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MutableClock clock = new MutableClock(NOW);
        coordinator = new RoomCoordinator();
        rooms = new RoomRegistry(coordinator);
        activeGames = new ActiveGameRegistry(coordinator);
        GameTimerRegistry timers = new GameTimerRegistry(taskScheduler, clock);
        ObjectProvider<GameLifecycleService> lifecycleProvider = mock(ObjectProvider.class);
        lenient().when(lifecycleProvider.getObject()).thenReturn(gameLifecycleService);
        GameTaskScheduler scheduler = new GameTaskScheduler(activeGames, timers, roomService, coordinator, clock, TestTimings.DEFAULT_GAME, lifecycleProvider);
        store = new RoomAggregateStore(rooms, activeGames, timers, scheduler, roomService, coordinator);
        lenient().when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(_ -> mock(ScheduledFuture.class));
    }

    private Room lobbyRoom() {
        Room room = new Room(ROOM_ID, "Aggregate Room", "Alice", 6, 5, 10, 1000, null);
        room.addPlayer("Alice");
        room.addPlayer("Bob");
        return room;
    }

    private Game game() {
        return new Game(ROOM_ID, List.of(new Player("Alice", "a", 1000), new Player("Bob", "b", 1000)), 6, 5, 10);
    }

    @Test
    @DisplayName("find returns null for a missing room and the room with its game otherwise")
    void findReturnsLiveRoomAndGame() {
        Room room = lobbyRoom();
        Game game = game();
        try (LockLease _ = coordinator.acquire(ROOM_ID)) {
            assertThat(store.find(ROOM_ID)).isNull();
            store.install(room, game);
            assertThat(store.find(ROOM_ID)).isEqualTo(new RoomAggregate(room, game));
        }
    }

    @Test
    @DisplayName("replace with a game marks the room started and schedules the game inactivity timer")
    void replaceWithGameSchedulesInactivityTimer() {
        Room room = lobbyRoom();
        room.setInactivityDeadlineEpochMs(NOW + 60_000L);
        Game game = game();
        try (LockLease _ = coordinator.acquire(ROOM_ID)) {
            store.replace(new RoomAggregate(room, game));
            assertThat(rooms.get(ROOM_ID)).isSameAs(room);
            assertThat(activeGames.get(ROOM_ID)).isSameAs(game);
        }
        assertThat(room.isGameStarted()).isTrue();
        verify(taskScheduler).schedule(any(Runnable.class), org.mockito.ArgumentMatchers.eq(Instant.ofEpochMilli(NOW + 60_000L)));
    }

    @Test
    @DisplayName("replace with no game drops a live game and leaves the room as an unstarted lobby")
    void replaceWithoutGameRemovesLiveGame() {
        Room room = lobbyRoom();
        try (LockLease _ = coordinator.acquire(ROOM_ID)) {
            store.install(room, game());
            store.replace(new RoomAggregate(room, null));
            assertThat(activeGames.get(ROOM_ID)).isNull();
        }
        assertThat(room.isGameStarted()).isFalse();
        assertThat(room.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("remove drops the room and game and cancels the lobby timer")
    void removeDropsRoomAndGame() {
        try (LockLease _ = coordinator.acquire(ROOM_ID)) {
            store.install(lobbyRoom(), game());
            store.remove(ROOM_ID);
            assertThat(rooms.get(ROOM_ID)).isNull();
            assertThat(activeGames.get(ROOM_ID)).isNull();
        }
        verify(roomService).cancelLobbyInactivity(ROOM_ID);
    }

    @Test
    @DisplayName("every method requires the room lock")
    void methodsRequireRoomLock() {
        Room room = lobbyRoom();
        assertThatThrownBy(() -> store.find(ROOM_ID)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.replace(new RoomAggregate(room, null))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.remove(ROOM_ID)).isInstanceOf(IllegalStateException.class);
    }
}
