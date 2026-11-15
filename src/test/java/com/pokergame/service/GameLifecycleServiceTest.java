package com.pokergame.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.pokergame.config.LifecycleTimingProperties;
import com.pokergame.dto.internal.PlayerJoinInfo;
import com.pokergame.dto.internal.TurnOutcome;
import com.pokergame.dto.internal.TurnOutcome.TurnOutcomeType;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.HandRank;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.Rank;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.enums.Suit;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Card;
import com.pokergame.model.Game;
import com.pokergame.model.HandEvaluationResult;
import com.pokergame.model.HandSettlementResult;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.testutil.MutableClock;
import com.pokergame.util.LockLease;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit tests for {@link GameLifecycleService} lifecycle orchestration, room-lock concurrency,
 * scheduled task dispatch, recovery, and inactivity timer handoffs.
 */
@Tag("unit")
@DisplayName("GameLifecycleService unit tests")
@ExtendWith(MockitoExtension.class)
class GameLifecycleServiceTest {

    private static final String ROOM_ID = "test-room-id";
    private static final long BASE_EPOCH_MS = 1_767_225_600_000L; // 2026-01-01T00:00:00Z
    private static final long DEFAULT_GAME_END_DISPLAY_MS = 30_000L;
    private static final long DEFAULT_ROUND_END_DISPLAY_MS = 0L;
    private static final long DEFAULT_READY_COUNTDOWN_MS = 10_000L;

    @Mock
    private RoomService roomService;

    @Mock
    private HandEvaluatorService handEvaluator;

    @Mock
    private GameStateService gameStateService;

    @Mock
    private TaskScheduler taskScheduler;

    private MutableClock mutableClock;
    private RoomCoordinator roomCoordinator;
    private ActiveGameRegistry activeGames;
    private GameTimerRegistry timerRegistry;
    private GameTaskScheduler scheduler;
    private GameCompletionHandler completionHandler;
    private HandProgressionService handProgression;
    private GameReadinessCoordinator readinessCoordinator;
    private PlayerDepartureHandler departureHandler;
    private GameLifecycleService gameLifecycleService;
    private Room testRoom;

    @BeforeEach
    void setUp() {
        mutableClock = new MutableClock(BASE_EPOCH_MS);
        testRoom = new Room(ROOM_ID, "Test Room", "Host", 6, 5, 10, 100, null);
        testRoom.addPlayer("Host");
        testRoom.addPlayer("Player2");

        lenient().when(handEvaluator.getBestHand(anyList(), anyList()))
                .thenReturn(new HandEvaluationResult(List.of(), HandRank.HIGH_CARD));
        lenient().when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(_ -> mock(ScheduledFuture.class));

        wireLifecycle(DEFAULT_GAME_END_DISPLAY_MS, DEFAULT_ROUND_END_DISPLAY_MS, DEFAULT_READY_COUNTDOWN_MS);
        stubGameEndCapture();
    }

    private void wireLifecycle(long gameEndDisplayMs, long roundEndDisplayMs, long readyCountdownMs) {
        LifecycleTimingProperties timings = new LifecycleTimingProperties(
                gameEndDisplayMs, roundEndDisplayMs, readyCountdownMs);
        roomCoordinator = new RoomCoordinator();
        activeGames = new ActiveGameRegistry(roomCoordinator);
        timerRegistry = new GameTimerRegistry(taskScheduler, mutableClock);
        AtomicReference<GameLifecycleService> lifecycleRef = new AtomicReference<>();
        @SuppressWarnings("unchecked")
        ObjectProvider<GameLifecycleService> lifecycleProvider = mock(ObjectProvider.class);
        lenient().when(lifecycleProvider.getObject()).thenAnswer(_ -> lifecycleRef.get());
        scheduler = new GameTaskScheduler(activeGames, timerRegistry, roomService, mutableClock, lifecycleProvider);
        completionHandler = new GameCompletionHandler(
                activeGames, roomService, gameStateService, timerRegistry, scheduler, mutableClock, timings);
        handProgression = new HandProgressionService(
                activeGames, timerRegistry, scheduler, completionHandler, gameStateService, timings);
        readinessCoordinator = new GameReadinessCoordinator(
                activeGames, roomService, gameStateService, timerRegistry, scheduler,
                completionHandler, handProgression, mutableClock, timings);
        departureHandler = new PlayerDepartureHandler(
                roomService, gameStateService, completionHandler, handProgression,
                readinessCoordinator, timerRegistry, activeGames, mutableClock);
        gameLifecycleService = new GameLifecycleService(
                roomService, handEvaluator, gameStateService, activeGames, timerRegistry, scheduler,
                completionHandler, handProgression, readinessCoordinator, departureHandler,
                roomCoordinator, mutableClock, timings);
        lifecycleRef.set(gameLifecycleService);
    }

    private void stubGameEndCapture() {
        Answer<GameEndResponse> buildResponse = invocation -> {
            Game game = invocation.getArgument(0);
            Player winner = invocation.getArgument(1);
            boolean forfeit = invocation.getArgument(2);
            int maxPlayers = game != null ? game.getMaxPlayers() : 6;
            List<PublicPlayerState> players = game != null && game.getPlayers() != null
                    ? game.getPlayers().stream()
                    .map(p -> new PublicPlayerState(
                            p.getPlayerId(), p.getName(), p.getChips(), 0, "status", false, false, false,
                            false, false, null, List.of(), p.equals(winner), null, null, null, false))
                    .toList()
                    : List.of();
            PublicGameStateResponse finalState = new PublicGameStateResponse(
                    maxPlayers, 0, List.of(), 0, GamePhase.SHOWDOWN, 0, List.of(),
                    players, null, null, List.of(winner.getName()), null, false, null, false, null,
                    false, null, true, null, List.of(), null, null, 1, ReadyGateState.CLOSED,
                    mutableClock.millis(), game != null ? game.getStateRevision() : 1);
            return new GameEndResponse(
                    "GAME_END", game != null ? game.getGameId() : ROOM_ID,
                    winner.getName(), winner.getChips(), forfeit,
                    winner.getName() + " wins", finalState);
        };
        lenient().when(gameStateService.recordGameEnd(any(Game.class), any(Player.class), anyBoolean()))
                .thenAnswer(buildResponse);
        lenient().when(gameStateService.buildGameEndResponse(any(Game.class), any(Player.class), anyBoolean()))
                .thenAnswer(buildResponse);
        lenient().when(gameStateService.recordAbandonment(any(Game.class))).thenAnswer(inv -> {
            Game game = inv.getArgument(0);
            return new GameEndResponse(
                    "GAME_END", game.getGameId(), null, null, false, true, "INACTIVITY",
                    "Abandoned due to inactivity", null);
        });
    }

    private static void setPlayerChips(Player player, int targetChips) {
        int delta = targetChips - player.getChips();
        if (delta > 0) {
            player.addChips(delta);
        } else if (delta < 0) {
            player.payChips(0, -delta);
        }
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

    private Game getGameLocked(String roomId) {
        return withRoomLock(roomId, () -> gameLifecycleService.getGame(roomId));
    }

    private Game createShowdownGame() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        return withRoomLock(ROOM_ID, () -> {
            gameLifecycleService.createGameFromRoom(ROOM_ID);
            Game game = gameLifecycleService.getGame(ROOM_ID);
            game.conductShowdown();
            return game;
        });
    }

    private Game gameWithReadyCountdown() {
        withRoomLock(ROOM_ID, () -> {
            if (gameLifecycleService.getGame(ROOM_ID) != null) {
                gameLifecycleService.evictGame(ROOM_ID);
            }
        });
        Game game = createShowdownGame();
        clearInvocations(gameStateService, taskScheduler);
        withRoomLock(ROOM_ID, () ->
                gameLifecycleService.applyTurnOutcome(ROOM_ID, game, new TurnOutcome(TurnOutcomeType.SHOWDOWN, null), false));
        clearInvocations(gameStateService, taskScheduler);
        return game;
    }

    private void assertReadyRejected(Runnable action, String expectedMessage, Game game) {
        UnauthorisedActionException ex = assertThrows(UnauthorisedActionException.class, action::run);
        assertEquals(expectedMessage, ex.getMessage());
        assertTrue(game.isReadyCountdownActive(), "A rejected ready change must leave the countdown open");
        verify(gameStateService, never()).broadcastGameState(any(Game.class));
    }

    // ==================== Turn Outcome & Creation ====================

    @Nested
    @DisplayName("Turn Outcome & Game Creation")
    class TurnOutcomeAndCreationTests {

        @Test
        void applyTurnOutcome_ShowdownWithoutDelay_OpensReadyCountdown() {
            Game game = createShowdownGame();
            clearInvocations(gameStateService);

            withRoomLock(ROOM_ID, () -> gameLifecycleService.applyTurnOutcome(
                    ROOM_ID, game, new TurnOutcome(TurnOutcomeType.SHOWDOWN, null), true));

            assertTrue(game.isReadyCountdownActive());
            assertEquals(game.getReadyCountdownDeadlineEpochMs(),
                    game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
            assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_OPEN));
            assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.GAME_END));
            var broadcasts = inOrder(gameStateService);
            broadcasts.verify(gameStateService).broadcastShowdownResults(game);
            broadcasts.verify(gameStateService).broadcastGameState(game);
        }

        @Test
        void applyTurnOutcome_ShowdownWithDelay_SchedulesReadyCountdownOpen() {
            wireLifecycle(DEFAULT_GAME_END_DISPLAY_MS, 2_500L, DEFAULT_READY_COUNTDOWN_MS);
            stubGameEndCapture();
            Game game = createShowdownGame();

            withRoomLock(ROOM_ID, () -> gameLifecycleService.applyTurnOutcome(
                    ROOM_ID, game, new TurnOutcome(TurnOutcomeType.SHOWDOWN, null), true));

            assertFalse(game.isReadyCountdownActive());
            assertEquals(BASE_EPOCH_MS + 2_500L,
                    game.getScheduledTaskDeadline(ScheduledGameTask.READY_OPEN));
            assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
            assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.GAME_END));
            verify(gameStateService).broadcastShowdownResults(game);
        }

        @Test
        void applyTurnOutcome_FinalShowdown_SchedulesGameEnd() {
            Game game = createShowdownGame();
            setPlayerChips(game.getPlayers().getLast(), 0);
            assertTrue(game.isGameDecided());

            withRoomLock(ROOM_ID, () -> gameLifecycleService.applyTurnOutcome(
                    ROOM_ID, game, new TurnOutcome(TurnOutcomeType.SHOWDOWN, null), true));

            assertFalse(game.isReadyCountdownActive());
            assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_OPEN));
            assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
            assertEquals(BASE_EPOCH_MS + HandProgressionService.FINAL_SHOWDOWN_DISPLAY_DELAY_MS,
                    game.getScheduledTaskDeadline(ScheduledGameTask.GAME_END));
            verify(gameStateService).broadcastShowdownResults(game);
        }

        @Test
        void createGameFromRoom_ValidRoom_StartsHandAndEmitsEvents() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);

            String gameId = withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));

            assertEquals(ROOM_ID, gameId);
            Game game = getGameLocked(ROOM_ID);
            assertNotNull(game);
            assertTrue(withRoomLock(ROOM_ID, () -> gameLifecycleService.gameExists(ROOM_ID)));
            assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
            assertTrue(game.getCommunityCards().isEmpty());
            assertNotNull(game.getCurrentPlayer());
            assertTrue(game.getPot() > 0);
            assertEquals(10, game.getCurrentHighestBet());
            for (Player player : game.getPlayers()) {
                assertEquals(2, player.getHoleCards().size());
                assertTrue(player.getChips() <= 100 && player.getChips() >= 85);
            }
            verify(roomService).cancelLobbyInactivity(ROOM_ID);
            verify(roomService).broadcastGameStarted(ROOM_ID);
            verify(gameStateService).broadcastGameState(any(Game.class));
        }

        @Test
        void createGameFromRoom_ValidationFailures() {
            when(roomService.getRoom("missing")).thenReturn(null);
            assertThrows(ResourceNotFoundException.class,
                    () -> withRoomLock("missing", () -> gameLifecycleService.createGameFromRoom("missing")));

            Room expiredLobby = new Room("expired", "Expired", "Host", 6, 5, 10, 100, null);
            expiredLobby.addPlayer("Host");
            expiredLobby.addPlayer("Player2");
            when(roomService.getRoom("expired")).thenReturn(expiredLobby);
            when(roomService.isLobbyExpired(expiredLobby)).thenReturn(true);
            assertThrows(BadRequestException.class,
                    () -> withRoomLock("expired", () -> gameLifecycleService.createGameFromRoom("expired")));
            verify(roomService, never()).checkAndCloseIfExpired("expired");

            Room onePlayerRoom = new Room("one", "One", "Host", 6, 5, 10, 100, null);
            onePlayerRoom.addPlayer("Host");
            when(roomService.getRoom("one")).thenReturn(onePlayerRoom);
            UnauthorisedActionException fewEx = assertThrows(UnauthorisedActionException.class,
                    () -> withRoomLock("one", () -> gameLifecycleService.createGameFromRoom("one")));
            assertEquals("Need at least 2 players to start game. Please wait for more players to join.",
                    fewEx.getMessage());

            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));
            BadRequestException startedEx = assertThrows(BadRequestException.class,
                    () -> withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID)));
            assertEquals("Game has already started for this room.", startedEx.getMessage());
        }

        @Test
        @DisplayName("createGameFromRoom reconciles absent seat lease and marks room started before broadcast")
        void createGameFromRoom_ReconcilesAbsentSeatLeaseAndMarksStartedBeforeBroadcast() {
            testRoom.setPlayerConnected("Host", true);
            testRoom.setPlayerConnected("Player2", false);
            long p2Deadline = BASE_EPOCH_MS + 45_000L;
            testRoom.setAbsenceDeadline("Player2", p2Deadline);
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            doAnswer(_ -> {
                assertTrue(testRoom.isGameStarted(),
                        "room.setGameStarted(true) must precede broadcastGameStarted");
                return null;
            }).when(roomService).broadcastGameStarted(ROOM_ID);

            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));

            Game game = getGameLocked(ROOM_ID);
            Player host = game.findPlayerByName("Host");
            Player player2 = game.findPlayerByName("Player2");
            assertFalse(host.getIsDisconnected());
            assertNull(host.getDisconnectDeadlineEpochMs());
            assertTrue(player2.getIsDisconnected());
            assertEquals(p2Deadline, player2.getDisconnectDeadlineEpochMs());
            verify(roomService).broadcastGameStarted(ROOM_ID);
        }
    }

    // ==================== Forced Blinds & Ready Progression ====================

    @Nested
    @DisplayName("Forced Blinds & Ready Progression")
    class HandStartAndReadyTests {

        @Test
        void startNewHand_ReconcilesForcedBlindsAsRunoutOrGenuineOwedCall() {
            Room headsUp = new Room("forced-runout", "Forced runout", "Host", 2, 5, 10, 100, null);
            headsUp.addPlayer("Host");
            headsUp.addPlayer("Player2");
            when(roomService.getRoom(headsUp.getRoomId())).thenReturn(headsUp);
            withRoomLock(headsUp.getRoomId(), () ->
                    gameLifecycleService.createGameFromRoom(headsUp.getRoomId()));
            Game runout = getGameLocked(headsUp.getRoomId());
            runout.conductShowdown();
            runout.getPlayers().forEach(player -> setPlayerChips(player, 5));
            long nextHandDeadline = BASE_EPOCH_MS + 1_000L;
            runout.scheduleTask(ScheduledGameTask.NEW_HAND, nextHandDeadline);
            long nextHandGen = runout.getScheduledTaskGeneration(ScheduledGameTask.NEW_HAND);
            reset(gameStateService);
            stubGameEndCapture();

            withRoomLock(headsUp.getRoomId(), () -> gameLifecycleService.executeScheduledTask(
                    headsUp.getRoomId(), ScheduledGameTask.NEW_HAND, nextHandDeadline, runout.getHandNumber(), nextHandGen));

            assertTrue(runout.getActivePlayers().stream().allMatch(Player::getIsAllIn));
            assertEquals(5, runout.getCurrentHighestBet());
            assertNotNull(runout.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
            verify(gameStateService).broadcastGameStateWithAutoAdvance(eq(runout), anyString());

            Room threeHanded = new Room("forced-call", "Forced call", "Host", 3, 5, 10, 100, null);
            threeHanded.addPlayer("Host");
            threeHanded.addPlayer("Player2");
            threeHanded.addPlayer("Player3");
            when(roomService.getRoom(threeHanded.getRoomId())).thenReturn(threeHanded);
            withRoomLock(threeHanded.getRoomId(), () ->
                    gameLifecycleService.createGameFromRoom(threeHanded.getRoomId()));
            Game callHand = getGameLocked(threeHanded.getRoomId());
            callHand.conductShowdown();
            int playerCount = callHand.getActivePlayers().size();
            int currentDealerIndex = -1;
            for (int i = 0; i < playerCount; i++) {
                if (callHand.getActivePlayers().get(i).getPlayerId().equals(callHand.getDealerPlayerId())) {
                    currentDealerIndex = i;
                    break;
                }
            }
            int nextSmallBlindIndex = (currentDealerIndex + 2) % playerCount;
            int nextBigBlindIndex = (nextSmallBlindIndex + 1) % playerCount;
            int nextActorIndex = (nextBigBlindIndex + 1) % playerCount;
            for (int index = 0; index < callHand.getPlayers().size(); index++) {
                setPlayerChips(callHand.getPlayers().get(index),
                        index == nextSmallBlindIndex ? 5 : index == nextBigBlindIndex ? 10 : 500);
            }
            callHand.scheduleTask(ScheduledGameTask.NEW_HAND, nextHandDeadline);
            long callGen = callHand.getScheduledTaskGeneration(ScheduledGameTask.NEW_HAND);
            reset(gameStateService);
            stubGameEndCapture();

            withRoomLock(threeHanded.getRoomId(), () -> gameLifecycleService.executeScheduledTask(
                    threeHanded.getRoomId(), ScheduledGameTask.NEW_HAND, nextHandDeadline, callHand.getHandNumber(), callGen));

            Player actor = callHand.getCurrentPlayer();
            assertNotNull(actor);
            assertEquals(callHand.getActivePlayers().get(nextActorIndex), actor);
            assertTrue(callHand.getCurrentHighestBet() > actor.getCurrentBet(), "Actor owes the posted blind");
            assertNull(callHand.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
            assertTrue(callHand.getLegalActionsFor(actor).contains(PlayerAction.CALL));
            verify(gameStateService).broadcastGameState(callHand);
            verify(gameStateService, never()).broadcastGameStateWithAutoAdvance(eq(callHand), anyString());
        }

        @Test
        void departureDuringReadyWindowPreservesSettlementUntilRemainingPlayersReadyOnce() {
            Room room = new Room("ready-departure", "Ready departure", "A", 4, 5, 10, 100, null);
            for (String name : List.of("A", "B", "C", "D")) {
                room.addPlayer(name);
            }
            String roomId = room.getRoomId();
            when(roomService.getRoom(roomId)).thenReturn(room);
            withRoomLock(roomId, () -> gameLifecycleService.createGameFromRoom(roomId));
            Game game = getGameLocked(roomId);
            game.conductShowdown();
            game.getPlayers().forEach(player -> setPlayerChips(player, 100));
            HandSettlementResult settled = game.getLastSettlementResult();
            withRoomLock(roomId, () -> {
                gameLifecycleService.applyTurnOutcome(
                        roomId, game, new TurnOutcome(TurnOutcomeType.SHOWDOWN, null), false);
            });
            long readyDeadline = game.getReadyCountdownDeadlineEpochMs();
            withRoomLock(roomId, () -> {
                gameLifecycleService.markPlayerReadyForNextHand(roomId, "A");
                gameLifecycleService.markPlayerReadyForNextHand(roomId, "B");
                gameLifecycleService.removePlayers(roomId, List.of("D"));
            });

            assertTrue(game.isReadyCountdownActive());
            assertEquals(readyDeadline, game.getReadyCountdownDeadlineEpochMs());
            assertSame(settled, game.getLastSettlementResult());
            assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());

            withRoomLock(roomId, () -> gameLifecycleService.markPlayerReadyForNextHand(roomId, "C"));

            assertFalse(game.isReadyCountdownActive());
            assertNull(game.getLastSettlementResult());
            assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
            assertEquals(15, game.getPot(), "One hand start posts one set of blinds");
            assertEquals(285, game.getActivePlayers().stream().mapToInt(Player::getChips).sum());
            assertThrows(UnauthorisedActionException.class,
                    () -> withRoomLock(roomId, () -> gameLifecycleService.markPlayerReadyForNextHand(roomId, "A")));
        }

        @Test
        void readyTimeout_StaleDeadlineAndWaitingTransitionAndExpiry() {
            wireLifecycle(DEFAULT_GAME_END_DISPLAY_MS, DEFAULT_ROUND_END_DISPLAY_MS, 60_000L);
            stubGameEndCapture();
            Game game = createShowdownGame();
            withRoomLock(ROOM_ID, () -> gameLifecycleService.applyTurnOutcome(
                    ROOM_ID, game, new TurnOutcome(TurnOutcomeType.SHOWDOWN, null), false));
            long currentDeadline = game.getReadyCountdownDeadlineEpochMs();
            long hand = game.getHandNumber();
            long gen = game.getScheduledTaskGeneration(ScheduledGameTask.READY_TIMEOUT);

            // Stale deadline ignored
            withRoomLock(ROOM_ID, () -> gameLifecycleService.executeScheduledTask(
                    ROOM_ID, ScheduledGameTask.READY_TIMEOUT, currentDeadline - 1_000L, hand, gen));
            assertTrue(game.isReadyCountdownActive());
            assertEquals(currentDeadline, game.getReadyCountdownDeadlineEpochMs());

            // One connected eligible player left -> moves to WAITING_FOR_PLAYERS instead of starting
            int hostChipsBefore = game.findPlayerByName("Host").getChips();
            int p2ChipsBefore = game.findPlayerByName("Player2").getChips();
            int potBefore = game.getPot();
            withRoomLock(ROOM_ID, () -> {
                gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Host", BASE_EPOCH_MS + 120_000L);
                assertTrue(game.isReadyCountdownActive());
                gameLifecycleService.executeScheduledTask(
                        ROOM_ID, ScheduledGameTask.READY_TIMEOUT, currentDeadline, hand, gen);
            });

            assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, game.getReadyGateState());
            assertFalse(game.isReadyCountdownActive());
            assertNull(game.getReadyCountdownDeadlineEpochMs());
            assertEquals(hand, game.getHandNumber());
            assertEquals(hostChipsBefore, game.findPlayerByName("Host").getChips());
            assertEquals(p2ChipsBefore, game.findPlayerByName("Player2").getChips());
            assertEquals(potBefore, game.getPot());

            // Reconnect opens gate; matching READY_TIMEOUT starts next hand
            withRoomLock(ROOM_ID, () -> gameLifecycleService.markPlayerReconnected(ROOM_ID, "Host"));
            assertTrue(game.isReadyCountdownActive());
            long reopenedDeadline = game.getReadyCountdownDeadlineEpochMs();
            long reopenedGen = game.getScheduledTaskGeneration(ScheduledGameTask.READY_TIMEOUT);

            withRoomLock(ROOM_ID, () -> gameLifecycleService.executeScheduledTask(
                    ROOM_ID, ScheduledGameTask.READY_TIMEOUT, reopenedDeadline, hand, reopenedGen));

            assertEquals(hand + 1, game.getHandNumber());
            assertNull(game.getReadyCountdownDeadlineEpochMs());
            assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
        }

        @Test
        void readyEligibilityAndRejectionContracts() {
            assertThrows(ResourceNotFoundException.class,
                    () -> withRoomLock("nonexistent-id", () -> gameLifecycleService.markPlayerReadyForNextHand("nonexistent-id", "Host")));
            assertThrows(ResourceNotFoundException.class,
                    () -> withRoomLock("nonexistent-id", () -> gameLifecycleService.unmarkPlayerReadyForNextHand("nonexistent-id", "Host")));

            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));
            Game liveGame = getGameLocked(ROOM_ID);
            liveGame.findPlayerByName("Host").setReadyForNextHand(true);
            clearInvocations(gameStateService);

            UnauthorisedActionException unmarkInactive = assertThrows(UnauthorisedActionException.class,
                    () -> withRoomLock(ROOM_ID, () -> gameLifecycleService.unmarkPlayerReadyForNextHand(ROOM_ID, "Host")));
            assertEquals("Ready countdown is not active.", unmarkInactive.getMessage());
            assertTrue(liveGame.findPlayerByName("Host").getIsReadyForNextHand());

            liveGame.findPlayerByName("Host").setReadyForNextHand(false);
            UnauthorisedActionException markInactive = assertThrows(UnauthorisedActionException.class,
                    () -> withRoomLock(ROOM_ID, () -> gameLifecycleService.markPlayerReadyForNextHand(ROOM_ID, "Host")));
            assertEquals("Ready countdown is not active.", markInactive.getMessage());

            Game readyGame = gameWithReadyCountdown();
            assertReadyRejected(() -> withRoomLock(ROOM_ID, () -> gameLifecycleService.markPlayerReadyForNextHand(ROOM_ID, "Stranger")),
                    "You are no longer part of this game.", readyGame);
            assertReadyRejected(() -> withRoomLock(ROOM_ID, () -> gameLifecycleService.unmarkPlayerReadyForNextHand(ROOM_ID, "Stranger")),
                    "You are no longer part of this game.", readyGame);

            Player host = readyGame.findPlayerByName("Host");
            host.setIsOut();
            assertReadyRejected(() -> withRoomLock(ROOM_ID, () -> gameLifecycleService.markPlayerReadyForNextHand(ROOM_ID, "Host")),
                    "You are out of this game.", readyGame);
            host.setReadyForNextHand(true);
            assertReadyRejected(() -> withRoomLock(ROOM_ID, () -> gameLifecycleService.unmarkPlayerReadyForNextHand(ROOM_ID, "Host")),
                    "You are out of this game.", readyGame);

            Game disconnectedGame = gameWithReadyCountdown();
            Player dcHost = disconnectedGame.findPlayerByName("Host");
            dcHost.setDisconnected(true);
            assertReadyRejected(() -> withRoomLock(ROOM_ID, () -> gameLifecycleService.markPlayerReadyForNextHand(ROOM_ID, "Host")),
                    "Reconnect before marking ready.", disconnectedGame);
            dcHost.setReadyForNextHand(true);
            assertReadyRejected(() -> withRoomLock(ROOM_ID, () -> gameLifecycleService.unmarkPlayerReadyForNextHand(ROOM_ID, "Host")),
                    "You are not eligible to change readiness.", disconnectedGame);

            Game bustedGame = gameWithReadyCountdown();
            Player bustedHost = bustedGame.findPlayerByName("Host");
            setPlayerChips(bustedHost, 0);
            assertReadyRejected(() -> withRoomLock(ROOM_ID, () -> gameLifecycleService.markPlayerReadyForNextHand(ROOM_ID, "Host")),
                    "Players without chips cannot mark ready.", bustedGame);
            bustedHost.setReadyForNextHand(true);
            assertReadyRejected(() -> withRoomLock(ROOM_ID, () -> gameLifecycleService.unmarkPlayerReadyForNextHand(ROOM_ID, "Host")),
                    "You are not eligible to change readiness.", bustedGame);
        }
    }

    // ==================== Queries, Restore, Eviction & Recovery ====================

    @Nested
    @DisplayName("Queries, Recovery & Membership")
    class QueriesAndRecoveryTests {

        @Test
        void gameAndPlayerQueriesRespectMissingAndOutPlayers() {
            assertNull(getGameLocked("missing"));
            assertThrows(IllegalStateException.class, () -> gameLifecycleService.getGame("missing"));
            assertThrows(IllegalStateException.class, () -> gameLifecycleService.gameExists("missing"));
            assertThrows(IllegalStateException.class, () -> gameLifecycleService.playerExistsInGame("missing", "Host"));
            assertThrows(IllegalStateException.class, () -> gameLifecycleService.isGameInactivityExpired("missing"));
            assertThrows(IllegalStateException.class, () -> gameLifecycleService.getGameReview("missing", "Host"));
            assertThrows(IllegalStateException.class,
                    () -> gameLifecycleService.validatePlayerMembership("missing", "Host", "mem-1"));
            assertFalse(withRoomLock("missing", () -> gameLifecycleService.gameExists("missing")));
            assertFalse(gameLifecycleService.playerExistsInGame(null, "Host"));
            assertFalse(gameLifecycleService.playerExistsInGame(ROOM_ID, null));

            Game nullPlayersGame = mock(Game.class);
            when(nullPlayersGame.getGameId()).thenReturn("mock-null-players");
            when(nullPlayersGame.getPlayers()).thenReturn(null);
            withRoomLock("mock-null-players", () -> {
                gameLifecycleService.restoreGame(nullPlayersGame);
                assertFalse(gameLifecycleService.playerExistsInGame("mock-null-players", "Host"));
            });

            Player active = new Player("Alice", "p1", 100);
            Player out = new Player("Bob", "p2", 0);
            out.setIsOut();
            List<Player> verifiedList = new ArrayList<>(List.of(active, out)) {
                @Override
                public Spliterator<Player> spliterator() {
                    assertTrue(roomCoordinator.isHeldByCurrentThread("mock-locked"));
                    return super.spliterator();
                }

                @Override
                public Iterator<Player> iterator() {
                    assertTrue(roomCoordinator.isHeldByCurrentThread("mock-locked"));
                    return super.iterator();
                }
            };
            Game lockedGame = mock(Game.class);
            when(lockedGame.getGameId()).thenReturn("mock-locked");
            when(lockedGame.getPlayers()).thenReturn(verifiedList);
            withRoomLock("mock-locked", () -> {
                gameLifecycleService.restoreGame(lockedGame);
                assertTrue(gameLifecycleService.playerExistsInGame("mock-locked", "Alice"));
                assertFalse(gameLifecycleService.playerExistsInGame("mock-locked", "Bob"));
                assertFalse(gameLifecycleService.playerExistsInGame("mock-locked", "Charlie"));
            });
        }

        @Test
        void evictGame_RemovesGameAndTimersWhilePreservingRoomContainer() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));
            @SuppressWarnings("unchecked")
            ScheduledFuture<Object> turnFuture = mock(ScheduledFuture.class);
            doReturn(turnFuture).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
            timerRegistry.schedule(ROOM_ID, ScheduledGameTask.TURN_TIMEOUT, BASE_EPOCH_MS + 5_000L, () -> {});
            testRoom.setTerminal(true);

            gameLifecycleService.evictGame(null);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.evictGame(ROOM_ID));

            assertNull(getGameLocked(ROOM_ID));
            assertFalse(withRoomLock(ROOM_ID, () -> gameLifecycleService.gameExists(ROOM_ID)));
            assertFalse(testRoom.isGameStarted());
            assertFalse(testRoom.isTerminal());
            verify(turnFuture).cancel(false);
            verify(roomService, never()).destroyRoom(ROOM_ID);
        }

        @Test
        void validatePlayerMembership_EnforcesActiveMembershipAndBlankClaims() {
            testRoom.setMembershipId("Host", "mem-1");
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);

            withRoomLock(ROOM_ID, () -> {
                assertDoesNotThrow(() -> gameLifecycleService.validatePlayerMembership(ROOM_ID, "Host", "mem-1"));
                assertThrows(AccessDeniedException.class,
                        () -> gameLifecycleService.validatePlayerMembership(ROOM_ID, "Host", "wrong"));
                assertThrows(AccessDeniedException.class,
                        () -> gameLifecycleService.validatePlayerMembership(ROOM_ID, "Host", " "));
                assertThrows(AccessDeniedException.class,
                        () -> gameLifecycleService.validatePlayerMembership(ROOM_ID, "", "mem-1"));
            });
            withRoomLock("missing-room", () -> {
                ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                        () -> gameLifecycleService.validatePlayerMembership("missing-room", "Host", "mem-1"));
                assertEquals("Room not found", ex.getMessage());
            });
            assertThrows(AccessDeniedException.class,
                    () -> gameLifecycleService.validatePlayerMembership(null, "Host", "mem-1"));
        }

        @Test
        void restoreAndResumeRecoveredAndRollbackTimers() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            @SuppressWarnings("unchecked")
            ScheduledFuture<Object> recoveredReadyFuture = mock(ScheduledFuture.class);
            @SuppressWarnings("unchecked")
            ScheduledFuture<Object> rollbackReadyFuture = mock(ScheduledFuture.class);
            @SuppressWarnings("unchecked")
            ScheduledFuture<Object> rollbackInactivityFuture = mock(ScheduledFuture.class);
            doReturn(recoveredReadyFuture, rollbackReadyFuture, rollbackInactivityFuture)
                    .when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

            Game game = new Game(ROOM_ID,
                    List.of(new Player("Host", "h", 100), new Player("Player2", "p2", 100)),
                    6, 5, 10, handEvaluator);
            game.conductShowdown();
            game.openOrWaitReadyGate(BASE_EPOCH_MS + 10_000L);
            // Force recovered ready countdown missing deadline and verify resume repairs and schedules it
            game.clearScheduledTask(ScheduledGameTask.READY_TIMEOUT);
            withRoomLock(ROOM_ID, () -> {
                gameLifecycleService.restoreGame(game);
                testRoom.setInactivityDeadlineEpochMs(BASE_EPOCH_MS + 50_000L);

                gameLifecycleService.resumeRecoveredTimers(ROOM_ID);
                assertEquals(BASE_EPOCH_MS + 10_000L,
                        game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
                verify(taskScheduler).schedule(any(Runnable.class), eq(Instant.ofEpochMilli(BASE_EPOCH_MS + 10_000L)));

                gameLifecycleService.cancelTimers(ROOM_ID, false);
                verify(recoveredReadyFuture).cancel(false);

                clearInvocations(taskScheduler);
                gameLifecycleService.resumeRollbackTimers(ROOM_ID);
                verify(taskScheduler).schedule(any(Runnable.class), eq(Instant.ofEpochMilli(BASE_EPOCH_MS + 10_000L)));
                verify(taskScheduler).schedule(any(Runnable.class), eq(Instant.ofEpochMilli(BASE_EPOCH_MS + 50_000L)));

                gameLifecycleService.cancelTimers(ROOM_ID, false);
                verify(rollbackReadyFuture).cancel(false);
                verify(rollbackInactivityFuture).cancel(false);
            });
        }
    }

    // ==================== Scheduled Execution & Inactivity Handoffs ====================

    @Nested
    @DisplayName("Scheduled Execution & Inactivity Handoffs")
    class ScheduledExecutionAndInactivityTests {

        @Test
        void executeScheduledTask_AutoAdvanceInShowdown_DoesNotRedistribute() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));
            Game game = getGameLocked(ROOM_ID);
            when(handEvaluator.getBestHand(any(), any()))
                    .thenReturn(new HandEvaluationResult(List.of(new Card(Rank.ACE, Suit.SPADES)), HandRank.HIGH_CARD));
            game.conductShowdown();
            long deadline = BASE_EPOCH_MS + 4_000L;
            game.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, deadline);
            long gen = game.getScheduledTaskGeneration(ScheduledGameTask.AUTO_ADVANCE);
            int chipsHostBefore = game.getPlayers().get(0).getChips();
            int chipsP2Before = game.getPlayers().get(1).getChips();
            reset(gameStateService);
            stubGameEndCapture();

            withRoomLock(ROOM_ID, () -> gameLifecycleService.executeScheduledTask(
                    ROOM_ID, ScheduledGameTask.AUTO_ADVANCE, deadline, game.getHandNumber(), gen));

            assertEquals(chipsHostBefore, game.getPlayers().get(0).getChips());
            assertEquals(chipsP2Before, game.getPlayers().get(1).getChips());
            verify(gameStateService, never()).broadcastShowdownResults(any());
            verify(gameStateService, never()).broadcastAutoAdvanceComplete(any());
        }

        @Test
        void firstHandKeepsOriginalInactivityCallbackExecutable() {
            ArgumentCaptor<Runnable> callbackCaptor = ArgumentCaptor.forClass(Runnable.class);
            when(taskScheduler.schedule(callbackCaptor.capture(), any(Instant.class)))
                    .thenAnswer(_ -> mock(ScheduledFuture.class));
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);

            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));
            Game game = getGameLocked(ROOM_ID);
            long deadline = testRoom.getInactivityDeadlineEpochMs();
            assertNotNull(deadline);
            assertEquals(deadline, BASE_EPOCH_MS + Room.GAME_INACTIVITY_ALLOWANCE_MS);

            // First scheduled timer is the game inactivity callback; early trigger must not abandon
            Runnable inactivityCallback = callbackCaptor.getAllValues().getFirst();
            withRoomLock(ROOM_ID, () -> gameLifecycleService.abandonGameIfInactive(ROOM_ID, deadline));
            assertFalse(game.isGameOver(), "Early check before deadline must not abandon");

            mutableClock.setMillis(deadline);
            assertTrue(withRoomLock(ROOM_ID, () -> gameLifecycleService.isGameInactivityExpired(ROOM_ID)));
            withRoomLock(ROOM_ID, inactivityCallback);

            assertTrue(game.isGameOver());
            assertTrue(game.getGameEndResult().abandoned());
            assertEquals("INACTIVITY", game.getGameEndResult().reason());
        }

        @Test
        void readinessActivitySupersedesOldInactivityCallbackAndNextHandKeepsReplacement() {
            List<Runnable> callbacks = new ArrayList<>();
            when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(inv -> {
                callbacks.add(inv.getArgument(0));
                return mock(ScheduledFuture.class);
            });
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);

            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));
            long initialDeadline = testRoom.getInactivityDeadlineEpochMs();
            Runnable initialCallback = callbacks.getFirst();

            Game game = getGameLocked(ROOM_ID);
            game.conductShowdown();
            withRoomLock(ROOM_ID, () -> gameLifecycleService.applyTurnOutcome(
                    ROOM_ID, game, new TurnOutcome(TurnOutcomeType.SHOWDOWN, null), false));

            // Advance clock 5s and mark first player ready -> extends inactivity deadline & schedules replacement
            mutableClock.setMillis(BASE_EPOCH_MS + 5_000L);
            callbacks.clear();
            withRoomLock(ROOM_ID, () -> gameLifecycleService.markPlayerReadyForNextHand(ROOM_ID, "Host"));
            long replacedDeadline = testRoom.getInactivityDeadlineEpochMs();
            assertEquals(BASE_EPOCH_MS + 5_000L + Room.GAME_INACTIVITY_ALLOWANCE_MS, replacedDeadline);
            assertEquals(1, callbacks.size());
            Runnable replacementCallback = callbacks.getFirst();

            // Mark second player ready -> next hand starts and keeps the extended inactivity deadline
            mutableClock.setMillis(BASE_EPOCH_MS + 6_000L);
            callbacks.clear();
            withRoomLock(ROOM_ID, () -> gameLifecycleService.markPlayerReadyForNextHand(ROOM_ID, "Player2"));
            assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
            assertEquals(2L, game.getHandNumber());
            long hand2Deadline = testRoom.getInactivityDeadlineEpochMs();
            Runnable hand2Callback = callbacks.stream().findFirst().orElse(replacementCallback);

            // Firing initial callback at initialDeadline is ignored because deadline changed
            mutableClock.setMillis(initialDeadline);
            withRoomLock(ROOM_ID, initialCallback);
            assertFalse(game.isGameOver(), "Superseded inactivity callback must not abandon active hand 2");

            // Firing the replacement callback at hand 2 deadline abandons the game
            mutableClock.setMillis(hand2Deadline);
            withRoomLock(ROOM_ID, hand2Callback);
            assertTrue(game.isGameOver());
            assertTrue(game.getGameEndResult().abandoned());
        }

        @Test
        void startedReadyCallbackCannotExpireReplacementWithSameDeadline() {
            wireLifecycle(DEFAULT_GAME_END_DISPLAY_MS, DEFAULT_ROUND_END_DISPLAY_MS, 5_000L);
            stubGameEndCapture();
            Game game = createShowdownGame();
            withRoomLock(ROOM_ID, () -> gameLifecycleService.applyTurnOutcome(
                    ROOM_ID, game, new TurnOutcome(TurnOutcomeType.SHOWDOWN, null), false));
            long deadline = game.getReadyCountdownDeadlineEpochMs();
            long oldGen = game.getScheduledTaskGeneration(ScheduledGameTask.READY_TIMEOUT);

            // Re-schedule READY_TIMEOUT at the same clock & deadline -> generation increments
            game.closeReadyCountdown();
            game.openOrWaitReadyGate(deadline);
            assertEquals(oldGen + 1, game.getScheduledTaskGeneration(ScheduledGameTask.READY_TIMEOUT));

            withRoomLock(ROOM_ID, () -> gameLifecycleService.executeScheduledTask(
                    ROOM_ID, ScheduledGameTask.READY_TIMEOUT, deadline, game.getHandNumber(), oldGen));

            assertTrue(game.isReadyCountdownActive());
            assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());
            assertEquals(1L, game.getHandNumber());
        }

        @Test
        void departedAllInDuringAutoAdvanceMakesCapturedCallbackStale() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            testRoom.addPlayer("Player3");
            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));
            Game game = getGameLocked(ROOM_ID);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.applyTurnOutcome(
                    ROOM_ID, game, new TurnOutcome(TurnOutcomeType.AUTO_ADVANCING, null), true));
            long capturedDeadline = game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE);
            long capturedHand = game.getHandNumber();
            long capturedGen = game.getScheduledTaskGeneration(ScheduledGameTask.AUTO_ADVANCE);

            Player nonCurrent = game.getPlayers().stream()
                    .filter(p -> !p.equals(game.getCurrentPlayer()))
                    .findFirst()
                    .orElseThrow();
            withRoomLock(ROOM_ID, () -> gameLifecycleService.removePlayers(ROOM_ID, List.of(nonCurrent.getName())));
            // Subsequent outcome or progression advances/invalidates generation
            game.invalidateProgressionTasks();
            clearInvocations(gameStateService);

            withRoomLock(ROOM_ID, () -> gameLifecycleService.executeScheduledTask(
                    ROOM_ID, ScheduledGameTask.AUTO_ADVANCE, capturedDeadline, capturedHand, capturedGen));

            verifyNoInteractions(gameStateService);
        }

        @Test
        void claimWin_AndCleanupExecution_WorkThroughServiceBoundary() {
            testRoom.addPlayer("Player3");
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> {
                gameLifecycleService.createGameFromRoom(ROOM_ID);
                gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", BASE_EPOCH_MS + 60_000L);
                gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player3", BASE_EPOCH_MS + 60_000L);
            });

            GameEndResponse claimed = withRoomLock(ROOM_ID, () -> gameLifecycleService.claimWin(ROOM_ID, "Host"));
            assertEquals("Host", claimed.winner());
            assertTrue(claimed.isForfeit());

            Game game = getGameLocked(ROOM_ID);
            Long cleanupDeadline = game.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP);
            long cleanupGen = game.getScheduledTaskGeneration(ScheduledGameTask.CLEANUP);
            assertNotNull(cleanupDeadline);

            withRoomLock(ROOM_ID, () -> gameLifecycleService.executeScheduledTask(
                    ROOM_ID, ScheduledGameTask.CLEANUP, cleanupDeadline, game.getHandNumber(), cleanupGen));

            assertNull(getGameLocked(ROOM_ID));
            verify(roomService).destroyRoom(ROOM_ID);
        }
    }

    // ==================== Room Lock Concurrency Tests ====================

    @Nested
    @DisplayName("Room Lock Concurrency")
    class ConcurrencyTests {

        @Test
        void playerLookupWaitsForRosterMutationOnSameGame() throws Exception {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));
            Game game = getGameLocked(ROOM_ID);
            Player departing = game.getPlayers().getLast();
            assertTrue(withRoomLock(ROOM_ID, () -> gameLifecycleService.playerExistsInGame(ROOM_ID, departing.getName())));
            assertThrows(IllegalStateException.class,
                    () -> gameLifecycleService.playerExistsInGame(ROOM_ID, departing.getName()));

            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<Boolean> lookup;
                try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
                    lookup = executor.submit(() -> withRoomLock(ROOM_ID,
                            () -> gameLifecycleService.playerExistsInGame(ROOM_ID, departing.getName())));
                    Awaitility.await().atMost(Duration.ofSeconds(5))
                            .until(() -> roomCoordinator.lockReservationCount(ROOM_ID) >= 2);
                    game.removePlayer(departing);
                }
                assertFalse(lookup.get(5, TimeUnit.SECONDS));
            }
        }

        @Test
        void concurrentRoomStartsRegisterAllGames() throws Exception {
            List<String> roomIds = new ArrayList<>();
            for (int index = 0; index < 5; index++) {
                String roomId = "room-" + index;
                roomIds.add(roomId);
                Room room = new Room(roomId, "Room " + index, "Host" + index, 6, 5, 10, 100, null);
                room.addPlayer("Host" + index);
                room.addPlayer("Player" + index);
                when(roomService.getRoom(roomId)).thenReturn(room);
            }

            try (ExecutorService executor = Executors.newFixedThreadPool(roomIds.size())) {
                List<Future<String>> creations = roomIds.stream()
                        .map(roomId -> executor.submit(() -> withRoomLock(roomId,
                                () -> gameLifecycleService.createGameFromRoom(roomId))))
                        .toList();
                for (int index = 0; index < roomIds.size(); index++) {
                    String gameId = creations.get(index).get(10, TimeUnit.SECONDS);
                    assertEquals(roomIds.get(index), gameId);
                    assertTrue(withRoomLock(gameId, () -> gameLifecycleService.gameExists(gameId)));
                    assertNotNull(getGameLocked(gameId));
                }
            }
        }

        @Test
        void concurrentRegistryReadsReturnRegisteredGame() throws Exception {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));
            Game expected = getGameLocked(ROOM_ID);
            assertNotNull(expected);

            try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
                List<Future<Game>> reads = new ArrayList<>();
                for (int index = 0; index < 4; index++) {
                    reads.add(executor.submit(() -> getGameLocked(ROOM_ID)));
                }
                for (Future<Game> read : reads) {
                    assertSame(expected, read.get(5, TimeUnit.SECONDS));
                }
            }
        }

        @Test
        void concurrentAutoAdvanceSchedulingSerializesUpdatesOnRoomLease() throws Exception {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));
            Game game = getGameLocked(ROOM_ID);
            ScheduledGameTask task = ScheduledGameTask.AUTO_ADVANCE;
            long generationBefore = game.getScheduledTaskGeneration(task);
            clearInvocations(taskScheduler);

            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<?> firstSchedule;
                Future<?> secondSchedule;
                try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
                    TurnOutcome autoAdvance = new TurnOutcome(TurnOutcomeType.AUTO_ADVANCING, null);
                    firstSchedule = executor.submit(() -> withRoomLock(ROOM_ID,
                            () -> gameLifecycleService.applyTurnOutcome(ROOM_ID, game, autoAdvance, false)));
                    secondSchedule = executor.submit(() -> withRoomLock(ROOM_ID,
                            () -> gameLifecycleService.applyTurnOutcome(ROOM_ID, game, autoAdvance, false)));
                    Awaitility.await().atMost(Duration.ofSeconds(5))
                            .until(() -> roomCoordinator.lockReservationCount(ROOM_ID) == 3);
                    assertEquals(generationBefore, game.getScheduledTaskGeneration(task));
                    assertNull(game.getScheduledTaskDeadline(task));
                }
                firstSchedule.get(5, TimeUnit.SECONDS);
                secondSchedule.get(5, TimeUnit.SECONDS);
                assertEquals(generationBefore + 1, game.getScheduledTaskGeneration(task));
                assertNotNull(game.getScheduledTaskDeadline(task));
                verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
            }
        }
    }
}
