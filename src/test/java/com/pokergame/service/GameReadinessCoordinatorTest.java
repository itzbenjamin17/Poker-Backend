package com.pokergame.service;

import com.pokergame.util.RoomCoordinator;

import com.pokergame.config.LifecycleTimingProperties;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.testutil.MutableClock;
import com.pokergame.util.LockLease;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GameReadinessCoordinator} ready-gate transitions, readiness validation,
 * inactivity resets, and timeout expiration.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("GameReadinessCoordinator unit tests")
class GameReadinessCoordinatorTest {

    private static final String GAME_ID = "readiness-room";
    private static final long BASE_EPOCH_MS = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    private static final long READY_COUNTDOWN_MS = 10_000L;

    @Mock
    private TaskScheduler springScheduler;

    @Mock
    private RoomService roomService;

    @Mock
    private GameStateService gameStateService;

    @Mock
    private ObjectProvider<GameLifecycleService> lifecycleProvider;

    private MutableClock mutableClock;
    private RoomCoordinator roomCoordinator;
    private ActiveGameRegistry activeGames;
    private GameTimerRegistry timerRegistry;
    private GameTaskScheduler taskScheduler;
    private GameCompletionHandler completionHandler;
    private HandProgressionService handProgression;
    private GameReadinessCoordinator readinessCoordinator;
    private Room room;

    @BeforeEach
    void setUp() {
        mutableClock = new MutableClock(BASE_EPOCH_MS);

        LifecycleTimingProperties timings = new LifecycleTimingProperties(30_000L, 4_000L, READY_COUNTDOWN_MS);
        roomCoordinator = new RoomCoordinator();
        activeGames = new ActiveGameRegistry(roomCoordinator);
        timerRegistry = new GameTimerRegistry(springScheduler, mutableClock);
        taskScheduler = new GameTaskScheduler(activeGames, timerRegistry, roomService, mutableClock, lifecycleProvider);
        completionHandler = new GameCompletionHandler(
                activeGames, roomService, gameStateService, timerRegistry, taskScheduler, mutableClock, timings);
        handProgression = new HandProgressionService(
                activeGames, timerRegistry, taskScheduler, completionHandler, gameStateService, timings);
        readinessCoordinator = new GameReadinessCoordinator(
                activeGames, roomService, gameStateService, timerRegistry, taskScheduler,
                completionHandler, handProgression, mutableClock, timings);

        lenient().when(springScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(_ -> mock(ScheduledFuture.class));

        room = new Room(GAME_ID, "Room", "Host", 6, 5, 10, 100, null);
        room.addPlayer("Host");
        room.addPlayer("Player2");
        room.setInactivityDeadlineEpochMs(BASE_EPOCH_MS + Room.GAME_INACTIVITY_ALLOWANCE_MS);
        lenient().when(roomService.getRoom(GAME_ID)).thenReturn(room);
    }

    private Game createShowdownGame(List<Player> players) {
        Game game = new Game(GAME_ID, players, 6, 5, 10);
        try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
            activeGames.replace(game);
            handProgression.startNewHand(GAME_ID);
            game.dealFlop();
            game.dealTurn();
            game.dealRiver();
            game.conductShowdown();
        }
        return game;
    }

    @Nested
    @DisplayName("openReadyGate")
    class OpenReadyGateTests {

        @Test
        @DisplayName("opens countdown and schedules READY_TIMEOUT when >= 2 connected eligible players remain")
        void openReadyGate_OpensCountdownAndSchedulesTimeout() {
            Game game = createShowdownGame(List.of(
                    new Player("Host", "host", 1000),
                    new Player("Player2", "p2", 1000)));
            clearInvocations(gameStateService, springScheduler);

            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                readinessCoordinator.openReadyGate(GAME_ID);
            }

            assertTrue(game.isReadyCountdownActive());
            assertEquals(ReadyGateState.COUNTING_DOWN, game.getReadyGateState());
            assertEquals(BASE_EPOCH_MS + READY_COUNTDOWN_MS,
                    game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
            verify(gameStateService).broadcastGameState(game);
        }

        @Test
        @DisplayName("enters WAITING_FOR_PLAYERS without starting next hand when fewer than 2 connected players remain")
        void openReadyGate_OnlyOneConnectedEligiblePlayer_Waits() {
            Player host = new Player("Host", "host", 1000);
            Player p2 = new Player("Player2", "p2", 1000);
            Game game = createShowdownGame(List.of(host, p2));
            p2.setDisconnected(true);
            long handBefore = game.getHandNumber();
            clearInvocations(gameStateService);

            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                readinessCoordinator.openReadyGate(GAME_ID);
            }

            assertFalse(game.isReadyCountdownActive());
            assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, game.getReadyGateState());
            assertEquals(handBefore, game.getHandNumber());
            verify(gameStateService).broadcastGameState(game);
        }

        @Test
        @DisplayName("ends game via completion handler when game is already decided at ready gate")
        void openReadyGate_WhenGameDecided_EndsGame() {
            Player host = new Player("Host", "host", 1000);
            Player p2 = new Player("Player2", "p2", 0);
            Game game = createShowdownGame(List.of(host, p2));

            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                readinessCoordinator.openReadyGate(GAME_ID);
            }

            assertTrue(game.isGameOver());
            verify(gameStateService).recordGameEnd(game, host, false);
            verify(gameStateService).sendGameEnd(any());
        }
    }

    @Nested
    @DisplayName("markPlayerReadyForNextHand & unmarkPlayerReadyForNextHand")
    class PlayerReadinessTests {

        @Test
        @DisplayName("throws ResourceNotFoundException when game is missing")
        void missingGame_ThrowsResourceNotFound() {
            try (LockLease _ = roomCoordinator.acquire("missing")) {
                assertThrows(ResourceNotFoundException.class,
                        () -> readinessCoordinator.markPlayerReadyForNextHand("missing", "Host"));
                assertThrows(ResourceNotFoundException.class,
                        () -> readinessCoordinator.unmarkPlayerReadyForNextHand("missing", "Host"));
            }
        }

        @Test
        @DisplayName("rejects mark/unmark when countdown closed, player unknown, out, disconnected, or zero chips")
        void validationRejectsIneligibleRequestsWithoutMutatingState() {
            Player host = new Player("Host", "host", 1000);
            Player p2 = new Player("Player2", "p2", 1000);
            Player p3 = new Player("Player3", "p3", 0);
            Game game = createShowdownGame(List.of(host, p2, p3));

            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                // Gate closed
                assertThrows(UnauthorisedActionException.class,
                        () -> readinessCoordinator.markPlayerReadyForNextHand(GAME_ID, "Host"));
                assertThrows(UnauthorisedActionException.class,
                        () -> readinessCoordinator.unmarkPlayerReadyForNextHand(GAME_ID, "Host"));

                readinessCoordinator.openReadyGate(GAME_ID);

                // Unknown player
                assertThrows(UnauthorisedActionException.class,
                        () -> readinessCoordinator.markPlayerReadyForNextHand(GAME_ID, "Stranger"));
                assertThrows(UnauthorisedActionException.class,
                        () -> readinessCoordinator.unmarkPlayerReadyForNextHand(GAME_ID, "Stranger"));

                // Zero chips
                assertThrows(UnauthorisedActionException.class,
                        () -> readinessCoordinator.markPlayerReadyForNextHand(GAME_ID, "Player3"));
                assertThrows(UnauthorisedActionException.class,
                        () -> readinessCoordinator.unmarkPlayerReadyForNextHand(GAME_ID, "Player3"));

                // Out player
                p3.setIsOut();
                assertThrows(UnauthorisedActionException.class,
                        () -> readinessCoordinator.markPlayerReadyForNextHand(GAME_ID, "Player3"));
                assertThrows(UnauthorisedActionException.class,
                        () -> readinessCoordinator.unmarkPlayerReadyForNextHand(GAME_ID, "Player3"));

                // Disconnected player
                p2.setDisconnected(true);
                assertThrows(UnauthorisedActionException.class,
                        () -> readinessCoordinator.markPlayerReadyForNextHand(GAME_ID, "Player2"));
                assertThrows(UnauthorisedActionException.class,
                        () -> readinessCoordinator.unmarkPlayerReadyForNextHand(GAME_ID, "Player2"));
            }
        }

        @Test
        @DisplayName("toggling ready resets game inactivity only on state change and deals next hand when all ready")
        void markAndUnmarkReady_ResetsInactivityOnChangeAndStartsWhenAllReady() {
            Game game = createShowdownGame(List.of(
                    new Player("Host", "host", 1000),
                    new Player("Player2", "p2", 1000)));
            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                readinessCoordinator.openReadyGate(GAME_ID);
            }
            long handBefore = game.getHandNumber();
            long initialInactivity = room.getInactivityDeadlineEpochMs();

            // Host marks ready -> resets inactivity and broadcasts without starting
            mutableClock.setMillis(BASE_EPOCH_MS + 1_000L);
            clearInvocations(gameStateService);
            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                readinessCoordinator.markPlayerReadyForNextHand(GAME_ID, "Host");
            }
            assertEquals(BASE_EPOCH_MS + 1_000L + Room.GAME_INACTIVITY_ALLOWANCE_MS,
                    room.getInactivityDeadlineEpochMs());
            assertEquals(handBefore, game.getHandNumber());
            verify(gameStateService).broadcastGameState(game);

            // Idempotent re-mark does NOT extend inactivity again
            long afterFirstReady = room.getInactivityDeadlineEpochMs();
            mutableClock.setMillis(BASE_EPOCH_MS + 2_000L);
            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                readinessCoordinator.markPlayerReadyForNextHand(GAME_ID, "Host");
            }
            assertEquals(afterFirstReady, room.getInactivityDeadlineEpochMs());

            // Unmark extends inactivity since ready state changed back to false
            mutableClock.setMillis(BASE_EPOCH_MS + 3_000L);
            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                readinessCoordinator.unmarkPlayerReadyForNextHand(GAME_ID, "Host");
            }
            assertEquals(BASE_EPOCH_MS + 3_000L + Room.GAME_INACTIVITY_ALLOWANCE_MS,
                    room.getInactivityDeadlineEpochMs());
            assertNotEquals(initialInactivity, room.getInactivityDeadlineEpochMs());

            // Both mark ready -> completes countdown and starts next hand
            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                readinessCoordinator.markPlayerReadyForNextHand(GAME_ID, "Host");
                readinessCoordinator.markPlayerReadyForNextHand(GAME_ID, "Player2");
            }
            assertEquals(handBefore + 1, game.getHandNumber());
            assertFalse(game.isReadyCountdownActive());
            assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
        }

        @Test
        @DisplayName("rejects mark ready with GAME_EXPIRED without abandoning when inactivity deadline elapsed")
        void markPlayerReady_ExpiredInactivity_ThrowsWithoutAbandoning() {
            Game game = createShowdownGame(List.of(
                    new Player("Host", "host", 1000),
                    new Player("Player2", "p2", 1000)));
            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                readinessCoordinator.openReadyGate(GAME_ID);
            }
            mutableClock.setMillis(room.getInactivityDeadlineEpochMs());

            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                BadRequestException ex = assertThrows(BadRequestException.class,
                        () -> readinessCoordinator.markPlayerReadyForNextHand(GAME_ID, "Host"));
                assertEquals("GAME_EXPIRED", ex.actionErrorCode());
            }
            assertFalse(game.isGameOver());
        }
    }

    @Nested
    @DisplayName("handleReadyTimeout")
    class HandleReadyTimeoutTests {

        @Test
        @DisplayName("ignores stale deadline and cancels timer when game is null")
        void handleReadyTimeout_StaleDeadlineAndNullGame() {
            @SuppressWarnings("unchecked")
            ScheduledFuture<Object> readyFuture = mock(ScheduledFuture.class);
            doReturn(readyFuture).when(springScheduler).schedule(any(Runnable.class), any(Instant.class));
            timerRegistry.schedule(GAME_ID, ScheduledGameTask.READY_TIMEOUT, BASE_EPOCH_MS + 1_000L, () -> {});

            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                readinessCoordinator.handleReadyTimeout(GAME_ID, null, BASE_EPOCH_MS);
            }
            verify(readyFuture).cancel(false);

            Game game = createShowdownGame(List.of(
                    new Player("Host", "host", 1000),
                    new Player("Player2", "p2", 1000)));
            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                readinessCoordinator.openReadyGate(GAME_ID);
            }
            long expectedDeadline = game.getReadyCountdownDeadlineEpochMs();
            long handBefore = game.getHandNumber();

            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                readinessCoordinator.handleReadyTimeout(GAME_ID, game, expectedDeadline - 100L);
            }
            assertTrue(game.isReadyCountdownActive());
            assertEquals(handBefore, game.getHandNumber());
        }

        @Test
        @DisplayName("forces next hand on matching deadline or transitions to WAITING_FOR_PLAYERS if player disconnected")
        void handleReadyTimeout_ForcedReadyVsWaitingForPlayers() {
            Player host = new Player("Host", "host", 1000);
            Player p2 = new Player("Player2", "p2", 1000);
            Game game = createShowdownGame(List.of(host, p2));
            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                readinessCoordinator.openReadyGate(GAME_ID);
            }
            long deadline = game.getReadyCountdownDeadlineEpochMs();
            long hand1 = game.getHandNumber();

            // Player2 disconnects before timeout fires -> transitions to WAITING_FOR_PLAYERS
            p2.setDisconnected(true);
            clearInvocations(gameStateService);
            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                readinessCoordinator.handleReadyTimeout(GAME_ID, game, deadline);
            }
            assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, game.getReadyGateState());
            assertEquals(hand1, game.getHandNumber());
            verify(gameStateService).broadcastGameState(game);

            // Reconnect, showdown hand 1 again, open gate, let timeout force hand 2
            p2.setDisconnected(false);
            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                readinessCoordinator.openReadyGate(GAME_ID);
                long secondDeadline = game.getReadyCountdownDeadlineEpochMs();
                readinessCoordinator.handleReadyTimeout(GAME_ID, game, secondDeadline);
            }
            assertEquals(hand1 + 1, game.getHandNumber());
            assertFalse(game.isReadyCountdownActive());
        }
    }
}
