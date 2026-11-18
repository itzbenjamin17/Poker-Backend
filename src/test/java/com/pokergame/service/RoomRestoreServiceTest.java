package com.pokergame.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.pokergame.config.GameTimingProperties;
import com.pokergame.config.WebSocketEventListener;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.RecoveryDecision;
import com.pokergame.persistence.snapshot.RecoveredAggregate;
import com.pokergame.testutil.MutableClock;
import com.pokergame.testutil.TestTimings;
import com.pokergame.util.LockLease;
import com.pokergame.util.RoomCoordinator;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;

/** Unit tests for {@link RoomRestoreService}. */
@Tag("unit")
@DisplayName("RoomRestoreService unit tests")
@ExtendWith(MockitoExtension.class)
class RoomRestoreServiceTest {

    private static final String ROOM_ID = "restore-room";
    private static final long BASE_EPOCH_MS = 1_900_000_000_000L;
    private static final long DISCONNECT_GRACE_MS = 15_000L;
    private static final long READY_COUNTDOWN_MS = 10_000L;

    @Mock
    private RoomService roomService;

    @Mock
    private GameLifecycleService gameLifecycleService;

    @Mock
    private RoomAggregateStore roomAggregates;

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
    private GameTaskScheduler scheduler;
    private RoomRestoreService roomRestoreService;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(BASE_EPOCH_MS);
        roomCoordinator = new RoomCoordinator();
        activeGames = new ActiveGameRegistry(roomCoordinator);
        timerRegistry = new GameTimerRegistry(taskScheduler, clock);

        @SuppressWarnings("unchecked")
        ObjectProvider<GameLifecycleService> lifecycleProvider = mock(ObjectProvider.class);
        lenient().when(lifecycleProvider.getObject()).thenReturn(gameLifecycleService);
        scheduler = new GameTaskScheduler(activeGames, timerRegistry, roomService, roomCoordinator, clock, TestTimings.DEFAULT_GAME, lifecycleProvider);

        GameTimingProperties timings = TestTimings.game(30_000L, 0L, READY_COUNTDOWN_MS);
        roomRestoreService = new RoomRestoreService(
                roomService,
                gameLifecycleService,
                roomAggregates,
                gameStateService,
                activeGames,
                scheduler,
                webSocketEventListener,
                roomCoordinator,
                clock,
                timings,
                TestTimings.room(DISCONNECT_GRACE_MS));

        lenient().when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(_ -> mock(ScheduledFuture.class));
    }

    private <T> T withRoomLock(String roomId, Supplier<T> action) {
        try (LockLease _ = roomCoordinator.acquire(roomId)) {
            return action.get();
        }
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
        List<Player> players = java.util.Arrays.stream(playerNames)
                .map(name -> new Player(name, name + "-id", 1000))
                .toList();
        return new Game(roomId, players, 6, 10, 20);
    }

    @Test
    void restoreRequiresRoomLockToBeHeld() {
        Room room = createRoom(ROOM_ID, "Alice");
        RecoveredAggregate aggregate = new RecoveredAggregate(room, null, false);

        assertThrows(IllegalStateException.class, () -> roomRestoreService.restore(aggregate));
        verifyNoInteractions(roomService, gameLifecycleService, webSocketEventListener);
    }

    @Test
    void expiredLobbyReturnsDiscardWithoutRestoring() {
        Room room = createRoom(ROOM_ID, "Alice");
        room.setInactivityDeadlineEpochMs(BASE_EPOCH_MS - 1_000L);
        when(roomService.isLobbyExpired(room)).thenReturn(true);

        RecoveryDecision decision = withRoomLock(ROOM_ID,
                () -> roomRestoreService.restore(new RecoveredAggregate(room, null, false)));

        assertEquals(RecoveryDecision.DISCARD, decision);
        verify(roomService).isLobbyExpired(room);
        verify(roomAggregates, never()).install(any(), any());
        verifyNoInteractions(gameLifecycleService, webSocketEventListener);
    }

    @Test
    void lobbyWithFutureDeadlineRestoresSchedulesInactivityAndDisconnectGrace() {
        long futureDeadline = BASE_EPOCH_MS + 1_800_000L;
        Room room = createRoom(ROOM_ID, "Alice");
        room.setInactivityDeadlineEpochMs(futureDeadline);
        when(roomService.isLobbyExpired(room)).thenReturn(false);

        RecoveryDecision decision = withRoomLock(ROOM_ID,
                () -> roomRestoreService.restore(new RecoveredAggregate(room, null, false)));

        assertEquals(RecoveryDecision.KEEP, decision);
        verify(roomAggregates).install(room, null);
        verify(roomService).scheduleLobbyInactivity(ROOM_ID, futureDeadline);
        verify(webSocketEventListener).scheduleRecoveredDisconnect(
                ROOM_ID, "Alice", BASE_EPOCH_MS + DISCONNECT_GRACE_MS);
        verifyNoInteractions(gameLifecycleService);
    }

    @Test
    void expiredCleanupGameReturnsDiscardWithoutRestoring() {
        Room room = createRoom(ROOM_ID, "Alice", "Bob");
        Game game = createGame(ROOM_ID, "Alice", "Bob");
        game.markGameOver();
        game.scheduleTask(ScheduledGameTask.CLEANUP, BASE_EPOCH_MS - 500L);

        RecoveryDecision decision = withRoomLock(ROOM_ID,
                () -> roomRestoreService.restore(new RecoveredAggregate(room, game, false)));

        assertEquals(RecoveryDecision.DISCARD, decision);
        verifyNoInteractions(roomService, gameLifecycleService, webSocketEventListener);
    }

    @Test
    void inactiveActiveGameIsAbandonedOnRestoreWithoutResumingNormalTimers() {
        Room room = createRoom(ROOM_ID, "Alice", "Bob");
        room.setInactivityDeadlineEpochMs(BASE_EPOCH_MS - 1_000L);
        Game game = createGame(ROOM_ID, "Alice", "Bob");

        RecoveryDecision decision = withRoomLock(ROOM_ID,
                () -> roomRestoreService.restore(new RecoveredAggregate(room, game, false)));

        assertEquals(RecoveryDecision.KEEP, decision);
        verify(roomAggregates).install(room, game);
        verify(gameLifecycleService).abandonGameDueToInactivity(ROOM_ID);
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        verifyNoInteractions(webSocketEventListener);
    }

    @Test
    void activeGameRestoresDeadlinesSchedulesInactivityAndResumesTimers() {
        long gameInactivityDeadline = BASE_EPOCH_MS + 600_000L;
        long longerStoredDeadline = BASE_EPOCH_MS + 60_000L;
        long shorterStoredDeadline = BASE_EPOCH_MS + 1_000L;

        Room room = createRoom(ROOM_ID, "Alice", "Bob", "Charlie");
        room.setInactivityDeadlineEpochMs(gameInactivityDeadline);
        Game game = createGame(ROOM_ID, "Alice", "Bob", "Charlie");
        Player alice = game.findPlayerByName("Alice");
        Player bob = game.findPlayerByName("Bob");
        Player charlie = game.findPlayerByName("Charlie");
        alice.setReadyForNextHand(true);
        alice.setDisconnectDeadlineEpochMs(longerStoredDeadline);
        bob.setReadyForNextHand(true);
        bob.setDisconnectDeadlineEpochMs(shorterStoredDeadline);
        charlie.setDisconnectDeadlineEpochMs(null);
        long turnDeadline = BASE_EPOCH_MS + 45_000L;
        game.scheduleTask(ScheduledGameTask.TURN_TIMEOUT, turnDeadline);
        doAnswer(inv -> {
            activeGames.replace(inv.getArgument(1));
            return null;
        }).when(roomAggregates).install(room, game);

        RecoveryDecision decision = withRoomLock(ROOM_ID,
                () -> roomRestoreService.restore(new RecoveredAggregate(room, game, false)));

        assertEquals(RecoveryDecision.KEEP, decision);
        verify(roomAggregates).install(room, game);

        long expectedFreshDeadline = BASE_EPOCH_MS + DISCONNECT_GRACE_MS;
        assertTrue(alice.getIsDisconnected());
        assertFalse(alice.getIsReadyForNextHand());
        assertEquals(longerStoredDeadline, alice.getDisconnectDeadlineEpochMs());
        verify(webSocketEventListener).scheduleRecoveredDisconnect(ROOM_ID, "Alice", longerStoredDeadline);

        assertTrue(bob.getIsDisconnected());
        assertFalse(bob.getIsReadyForNextHand());
        assertEquals(expectedFreshDeadline, bob.getDisconnectDeadlineEpochMs());
        verify(webSocketEventListener).scheduleRecoveredDisconnect(ROOM_ID, "Bob", expectedFreshDeadline);

        assertTrue(charlie.getIsDisconnected());
        assertFalse(charlie.getIsReadyForNextHand());
        assertEquals(expectedFreshDeadline, charlie.getDisconnectDeadlineEpochMs());
        verify(webSocketEventListener).scheduleRecoveredDisconnect(ROOM_ID, "Charlie", expectedFreshDeadline);

        verify(taskScheduler).schedule(any(Runnable.class), eq(Instant.ofEpochMilli(gameInactivityDeadline)));
        verify(taskScheduler).schedule(any(Runnable.class), eq(Instant.ofEpochMilli(turnDeadline)));
    }

    @Test
    void unexpiredTerminalGameSkipsGameInactivityAndResumesCleanupTimer() {
        long cleanupDeadline = BASE_EPOCH_MS + 20_000L;
        Room room = createRoom(ROOM_ID, "Alice", "Bob");
        room.setInactivityDeadlineEpochMs(BASE_EPOCH_MS + 60_000L);
        Game game = createGame(ROOM_ID, "Alice", "Bob");
        game.markGameOver();
        game.scheduleTask(ScheduledGameTask.CLEANUP, cleanupDeadline);
        doAnswer(inv -> {
            activeGames.replace(inv.getArgument(1));
            return null;
        }).when(roomAggregates).install(room, game);

        RecoveryDecision decision = withRoomLock(ROOM_ID,
                () -> roomRestoreService.restore(new RecoveredAggregate(room, game, false)));

        assertEquals(RecoveryDecision.KEEP, decision);
        verify(taskScheduler).schedule(any(Runnable.class), eq(Instant.ofEpochMilli(cleanupDeadline)));
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void restoreEvictsPartiallyRegisteredAggregatesWhenResumeTimersThrows() {
        Room room = createRoom(ROOM_ID, "Alice", "Bob");
        Game game = createGame(ROOM_ID, "Alice", "Bob");
        RoomRestoreService spyService = spy(roomRestoreService);
        doThrow(new IllegalStateException("Timer resume failure"))
                .when(spyService).resumeRecoveredTimers(ROOM_ID);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                withRoomLock(ROOM_ID,
                        () -> spyService.restore(new RecoveredAggregate(room, game, false))));

        assertEquals("Timer resume failure", ex.getMessage());
        verify(roomAggregates).install(room, game);
        verify(webSocketEventListener).cancelRoomPendingDisconnects(ROOM_ID);
        verify(roomAggregates).remove(ROOM_ID);
    }

    @Test
    void restoreEvictsPartiallyRegisteredAggregatesWhenAbandonGameThrows() {
        Room room = createRoom(ROOM_ID, "Alice", "Bob");
        room.setInactivityDeadlineEpochMs(BASE_EPOCH_MS - 1_000L);
        Game game = createGame(ROOM_ID, "Alice", "Bob");
        doThrow(new IllegalStateException("Abandon failure"))
                .when(gameLifecycleService).abandonGameDueToInactivity(ROOM_ID);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                withRoomLock(ROOM_ID,
                        () -> roomRestoreService.restore(new RecoveredAggregate(room, game, false))));

        assertEquals("Abandon failure", ex.getMessage());
        verify(webSocketEventListener).cancelRoomPendingDisconnects(ROOM_ID);
        verify(roomAggregates).remove(ROOM_ID);
    }

    @Test
    void restoreEvictsPartiallyRegisteredLobbyWhenDisconnectSchedulingThrows() {
        long futureDeadline = BASE_EPOCH_MS + 1_800_000L;
        Room room = createRoom(ROOM_ID, "Alice");
        room.setInactivityDeadlineEpochMs(futureDeadline);
        when(roomService.isLobbyExpired(room)).thenReturn(false);
        doThrow(new IllegalStateException("Disconnect schedule failure"))
                .when(webSocketEventListener).scheduleRecoveredDisconnect(eq(ROOM_ID), eq("Alice"), anyLong());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                withRoomLock(ROOM_ID,
                        () -> roomRestoreService.restore(new RecoveredAggregate(room, null, false))));

        assertEquals("Disconnect schedule failure", ex.getMessage());
        verify(roomAggregates).install(room, null);
        verify(roomService).scheduleLobbyInactivity(ROOM_ID, futureDeadline);
        verify(webSocketEventListener).cancelRoomPendingDisconnects(ROOM_ID);
        verify(roomAggregates).remove(ROOM_ID);
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
            roomRestoreService.resumeRecoveredTimers(ROOM_ID);

            assertEquals(BASE_EPOCH_MS + READY_COUNTDOWN_MS,
                    game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
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
            roomRestoreService.resumeRecoveredTimers(ROOM_ID);

            long expectedDeadline = BASE_EPOCH_MS + READY_COUNTDOWN_MS;
            assertEquals(expectedDeadline, game.getReadyCountdownDeadlineEpochMs());
            assertEquals(expectedDeadline,
                    game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
            verify(gameStateService).broadcastGameState(game);
            verify(taskScheduler).schedule(
                    any(Runnable.class), eq(Instant.ofEpochMilli(expectedDeadline)));

            timerRegistry.cancelAllForGame(ROOM_ID, false);
            verify(readyFuture).cancel(false);
        });
    }
}
