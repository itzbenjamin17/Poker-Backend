package com.pokergame.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.pokergame.config.GameTimingProperties;
import com.pokergame.config.WebSocketEventListener;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.testutil.MutableClock;
import com.pokergame.testutil.TestTimings;
import com.pokergame.util.LockLease;
import com.pokergame.util.RoomCoordinator;
import java.time.Instant;
import java.util.Arrays;
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

/** Unit tests for {@link RecoveredRoomTimerService}. */
@Tag("unit")
@DisplayName("RecoveredRoomTimerService unit tests")
@ExtendWith(MockitoExtension.class)
class RecoveredRoomTimerServiceTest {

    private static final String ROOM_ID = "timer-restore-room";
    private static final long BASE_EPOCH_MS = 1_900_000_000_000L;
    private static final long DISCONNECT_GRACE_MS = 15_000L;
    private static final long READY_COUNTDOWN_MS = 10_000L;

    @Mock
    private RoomService roomService;

    @Mock
    private GameStateService gameStateService;

    @Mock
    private WebSocketEventListener webSocketEventListener;

    @Mock
    private TaskScheduler taskScheduler;

    private MutableClock clock;
    private RoomCoordinator roomCoordinator;
    private ActiveGameRegistry activeGames;
    private GameTimerRegistry timerRegistry;
    private RecoveredRoomTimerService timerService;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(BASE_EPOCH_MS);
        roomCoordinator = new RoomCoordinator();
        activeGames = new ActiveGameRegistry(roomCoordinator);
        timerRegistry = new GameTimerRegistry(taskScheduler, clock);

        @SuppressWarnings("unchecked")
        ObjectProvider<GameLifecycleService> lifecycleProvider = mock(ObjectProvider.class);
        GameTaskScheduler scheduler = new GameTaskScheduler(
                activeGames, timerRegistry, roomService, roomCoordinator, clock,
                TestTimings.DEFAULT_GAME, lifecycleProvider);

        GameTimingProperties timings = TestTimings.game(30_000L, 0L, READY_COUNTDOWN_MS);
        timerService = new RecoveredRoomTimerService(
                roomService,
                activeGames,
                gameStateService,
                scheduler,
                webSocketEventListener,
                roomCoordinator,
                clock,
                timings,
                TestTimings.room(DISCONNECT_GRACE_MS));

        lenient().when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(_ -> mock(ScheduledFuture.class));
    }

    private void withRoomLock(String roomId, Runnable action) {
        try (LockLease _ = roomCoordinator.acquire(roomId)) {
            action.run();
        }
    }

    private static Room createRoom(String roomId, String host, String... guests) {
        Room room = new Room(roomId, "Room " + roomId, host, 6, 10, 20, 1000, null);
        room.addPlayer(host);
        for (String guest : guests) {
            room.addPlayer(guest);
        }
        return room;
    }

    private static Game createGame(String roomId, String... playerNames) {
        List<Player> players = Arrays.stream(playerNames)
                .map(name -> new Player(name, name + "-id", 1000))
                .toList();
        return new Game(roomId, players, 6, 10, 20);
    }

    @Test
    void resumeRecoveredTimersNoOpsWhenGameNotActive() {
        withRoomLock(ROOM_ID, () -> timerService.resumeRecoveredTimers(ROOM_ID));

        verifyNoInteractions(gameStateService, taskScheduler);
    }

    @Test
    void resumeRecoveredTimersReschedulesMissingReadyTimeoutWithoutBroadcast() {
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> readyFuture = mock(ScheduledFuture.class);
        doReturn(readyFuture).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        Game game = createGame(ROOM_ID, "Alice", "Bob");
        game.dealFlop();
        game.dealTurn();
        game.dealRiver();
        game.conductShowdown();
        game.openOrWaitReadyGate(BASE_EPOCH_MS + READY_COUNTDOWN_MS);
        game.clearScheduledTask(ScheduledGameTask.READY_TIMEOUT);

        withRoomLock(ROOM_ID, () -> {
            activeGames.replace(game);
            timerService.resumeRecoveredTimers(ROOM_ID);

            assertEquals(BASE_EPOCH_MS + READY_COUNTDOWN_MS,
                    game.getScheduledTaskTime(ScheduledGameTask.READY_TIMEOUT));
            verify(gameStateService, never()).broadcastGameState(any());
            verify(taskScheduler).schedule(
                    any(Runnable.class), eq(Instant.ofEpochMilli(BASE_EPOCH_MS + READY_COUNTDOWN_MS)));

            timerRegistry.cancelAllForGame(ROOM_ID, false);
            verify(readyFuture).cancel(false);
        });
    }

    @Test
    void resumeRecoveredTimersRepairsMissingCountdownDeadlineBroadcastsAndSchedulesTimer() {
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> readyFuture = mock(ScheduledFuture.class);
        doReturn(readyFuture).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        Player alice = new Player("Alice", "p1", 1000);
        Player bob = new Player("Bob", "p2", 1000);
        Game game = Game.restoreBuilder()
                .gameId(ROOM_ID)
                .players(List.of(alice, bob))
                .activePlayerIds(List.of("p1", "p2"))
                .remainingDeck(List.of())
                .smallBlind(10)
                .bigBlind(20)
                .currentPhase(GamePhase.SHOWDOWN)
                .readyGateState(ReadyGateState.COUNTING_DOWN)
                .readyCountdownDeadlineEpochMs(null)
                .build();

        withRoomLock(ROOM_ID, () -> {
            activeGames.replace(game);
            timerService.resumeRecoveredTimers(ROOM_ID);

            long expectedDeadline = BASE_EPOCH_MS + READY_COUNTDOWN_MS;
            assertEquals(expectedDeadline, game.getReadyCountdownDeadlineEpochMs());
            assertEquals(expectedDeadline,
                    game.getScheduledTaskTime(ScheduledGameTask.READY_TIMEOUT));
            verify(gameStateService).broadcastGameState(game);
            verify(taskScheduler).schedule(
                    any(Runnable.class), eq(Instant.ofEpochMilli(expectedDeadline)));

            timerRegistry.cancelAllForGame(ROOM_ID, false);
            verify(readyFuture).cancel(false);
        });
    }

    @Test
    void scheduleInactivityTimerSchedulesGameInactivityForActiveGame() {
        long deadline = BASE_EPOCH_MS + 600_000L;
        Room room = createRoom(ROOM_ID, "Alice", "Bob");
        room.setInactivityDeadlineEpochMs(deadline);
        Game game = createGame(ROOM_ID, "Alice", "Bob");

        withRoomLock(ROOM_ID, () -> {
            activeGames.replace(game);
            timerService.scheduleInactivityTimer(room, game);
        });

        verify(taskScheduler).schedule(any(Runnable.class), eq(Instant.ofEpochMilli(deadline)));
        verifyNoInteractions(roomService);
    }

    @Test
    void scheduleInactivityTimerSkipsFinishedGame() {
        Room room = createRoom(ROOM_ID, "Alice", "Bob");
        room.setInactivityDeadlineEpochMs(BASE_EPOCH_MS + 600_000L);
        Game game = createGame(ROOM_ID, "Alice", "Bob");
        game.markGameOver();

        withRoomLock(ROOM_ID, () -> timerService.scheduleInactivityTimer(room, game));

        verifyNoInteractions(taskScheduler, roomService);
    }

    @Test
    void scheduleInactivityTimerSkipsNullDeadline() {
        Room room = createRoom(ROOM_ID, "Alice");
        room.setInactivityDeadlineEpochMs(null);

        withRoomLock(ROOM_ID, () -> timerService.scheduleInactivityTimer(room, null));

        verifyNoInteractions(taskScheduler, roomService);
    }

    @Test
    void scheduleInactivityTimerSchedulesLobbyInactivity() {
        long deadline = BASE_EPOCH_MS + 900_000L;
        Room room = createRoom(ROOM_ID, "Alice");
        room.setInactivityDeadlineEpochMs(deadline);

        withRoomLock(ROOM_ID, () -> timerService.scheduleInactivityTimer(room, null));

        verify(roomService).scheduleLobbyInactivity(ROOM_ID, deadline);
        verifyNoInteractions(taskScheduler);
    }

    @Test
    void scheduleDisconnectGraceRequiresRoomLockToBeHeld() {
        Room room = createRoom(ROOM_ID, "Alice");

        assertThrows(IllegalStateException.class,
                () -> timerService.scheduleDisconnectGrace(room, null, BASE_EPOCH_MS));

        verifyNoInteractions(webSocketEventListener);
    }

    @Test
    void scheduleDisconnectGraceAppliesFreshGraceForLobbyPlayers() {
        Room room = createRoom(ROOM_ID, "Alice", "Bob");
        long expectedDeadline = BASE_EPOCH_MS + DISCONNECT_GRACE_MS;

        withRoomLock(ROOM_ID, () -> timerService.scheduleDisconnectGrace(room, null, BASE_EPOCH_MS));

        verify(webSocketEventListener).scheduleRecoveredDisconnect(ROOM_ID, "Alice", expectedDeadline);
        verify(webSocketEventListener).scheduleRecoveredDisconnect(ROOM_ID, "Bob", expectedDeadline);
    }
}
