package com.pokergame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pokergame.model.Room;
import com.pokergame.testutil.MutableClock;
import com.pokergame.util.LockLease;
import com.pokergame.util.RoomCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/** Unit tests for {@link LobbyInactivityScheduler}'s timer callback. */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("Lobby inactivity scheduler")
class LobbyInactivitySchedulerTest {

    private static final String ROOM_ID = "lobby-room";
    private static final long DEADLINE_MS = 1_900_000_000_000L;

    @Mock
    private GameTimerRegistry timerRegistry;

    @Mock
    private ObjectProvider<RoomService> roomServiceProvider;

    @Mock
    private RoomService roomService;

    private MutableClock clock;
    private RoomCoordinator roomCoordinator;
    private RoomRegistry rooms;
    private LobbyInactivityScheduler scheduler;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(DEADLINE_MS - 60_000L);
        roomCoordinator = new RoomCoordinator();
        rooms = new RoomRegistry(roomCoordinator);
        scheduler = new LobbyInactivityScheduler(timerRegistry, rooms, roomCoordinator, clock, roomServiceProvider);
        lenient().when(roomServiceProvider.getObject()).thenReturn(roomService);
    }

    private void putLobby() {
        Room room = new Room(ROOM_ID, "Lobby", "Host", 6, 5, 10, 100, null);
        room.setInactivityDeadlineEpochMs(DEADLINE_MS);
        try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
            rooms.put(room);
        }
    }

    private Runnable scheduledCallback() {
        scheduler.schedule(ROOM_ID, DEADLINE_MS);
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(timerRegistry).scheduleLobbyInactivity(eq(ROOM_ID), eq(DEADLINE_MS), callback.capture());
        return callback.getValue();
    }

    @Test
    @DisplayName("the timer callback takes the room lock itself and closes an expired lobby through the proxy")
    void givenExpiredLobbyAndNoRoomLock_whenCallbackRuns_thenLocksAndClosesLobby() {
        putLobby();
        clock.setMillis(DEADLINE_MS + 1L);
        Runnable callback = scheduledCallback();
        assertThat(roomCoordinator.isHeldByCurrentThread(ROOM_ID)).isFalse();
        doAnswer(invocation -> {
            assertThat(roomCoordinator.isHeldByCurrentThread(ROOM_ID)).isTrue();
            return null;
        }).when(roomService).closeLobbyDueToInactivity(ROOM_ID);

        callback.run();

        verify(roomService).closeLobbyDueToInactivity(ROOM_ID);
        assertThat(roomCoordinator.isHeldByCurrentThread(ROOM_ID)).isFalse();
    }

    @Test
    @DisplayName("firing before the deadline leaves the lobby open and closes nothing")
    void givenActiveLobby_whenCallbackRunsEarly_thenNothingIsClosed() {
        putLobby();
        clock.setMillis(DEADLINE_MS - 1L);
        Runnable callback = scheduledCallback();

        callback.run();

        try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
            assertThat(rooms.get(ROOM_ID)).isNotNull();
        }
        verify(roomService, never()).closeLobbyDueToInactivity(anyString());
        assertThat(roomCoordinator.isHeldByCurrentThread(ROOM_ID)).isFalse();
    }

    @Test
    @DisplayName("firing after the room was already destroyed does nothing")
    void givenDestroyedRoom_whenCallbackRuns_thenNoCloseAndNoError() {
        Runnable callback = scheduledCallback();
        clock.setMillis(DEADLINE_MS + 1L);

        assertThatCode(callback::run).doesNotThrowAnyException();

        verify(roomService, never()).closeLobbyDueToInactivity(any());
    }
}
