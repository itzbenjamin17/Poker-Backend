package com.pokergame.service;

import com.pokergame.util.RoomCoordinator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.pokergame.config.LifecycleTimingProperties;
import com.pokergame.dto.internal.PlayerDecision;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.HandRank;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Card;
import com.pokergame.model.Game;
import com.pokergame.model.HandEvaluationResult;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.snapshot.AggregateSnapshotMapper;
import com.pokergame.testutil.MutableClock;
import com.pokergame.util.LockLease;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link PlayerDepartureHandler} and participant departure, host succession,
 * disconnect-claim, and reconnect-grace expiration flows.
 */
@Tag("unit")
@DisplayName("PlayerDepartureHandler unit tests")
@ExtendWith(MockitoExtension.class)
class PlayerDepartureHandlerTest {

    private static final String ROOM_ID = "test-room-id";
    private static final long BASE_EPOCH_MS = 1_767_225_600_000L; // 2026-01-01T00:00:00Z
    private static final LifecycleTimingProperties DEFAULT_TIMINGS =
            new LifecycleTimingProperties(30_000L, 0L, 10_000L);

    @Mock
    private RoomService roomService;

    @Mock
    private HandEvaluatorService handEvaluator;

    @Mock
    private GameStateService gameStateService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

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

        wireLifecycle(gameStateService);
        stubGameEndCapture();
    }

    private void wireLifecycle(GameStateService stateService) {
        roomCoordinator = new RoomCoordinator();
        activeGames = new ActiveGameRegistry(roomCoordinator);
        timerRegistry = new GameTimerRegistry(taskScheduler, mutableClock);
        AtomicReference<GameLifecycleService> lifecycleRef = new AtomicReference<>();
        @SuppressWarnings("unchecked")
        ObjectProvider<GameLifecycleService> lifecycleProvider = mock(ObjectProvider.class);
        lenient().when(lifecycleProvider.getObject()).thenAnswer(_ -> lifecycleRef.get());
        scheduler = new GameTaskScheduler(activeGames, timerRegistry, roomService, mutableClock, lifecycleProvider);
        completionHandler = new GameCompletionHandler(
                activeGames, roomService, stateService, timerRegistry, scheduler, mutableClock, DEFAULT_TIMINGS);
        handProgression = new HandProgressionService(
                activeGames, timerRegistry, scheduler, completionHandler, stateService, DEFAULT_TIMINGS);
        readinessCoordinator = new GameReadinessCoordinator(
                activeGames, roomService, stateService, timerRegistry, scheduler,
                completionHandler, handProgression, mutableClock, DEFAULT_TIMINGS);
        departureHandler = new PlayerDepartureHandler(
                roomService, stateService, completionHandler, handProgression,
                readinessCoordinator, timerRegistry, activeGames, mutableClock);
        gameLifecycleService = new GameLifecycleService(
                roomService, handEvaluator, stateService, activeGames, timerRegistry, scheduler,
                completionHandler, handProgression, readinessCoordinator, departureHandler,
                roomCoordinator, mutableClock, DEFAULT_TIMINGS);
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

    private static void setPlayerChips(Player player, int targetChips) {
        int delta = targetChips - player.getChips();
        if (delta > 0) {
            player.addChips(delta);
        } else if (delta < 0) {
            player.payChips(0, -delta);
        }
    }

    private Player findGamePlayer(Game game, String name) {
        return game.getPlayers().stream().filter(p -> p.getName().equals(name)).findFirst().orElseThrow();
    }

    private Game createTerminalGame() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        return withRoomLock(ROOM_ID, () -> {
            gameLifecycleService.createGameFromRoom(ROOM_ID);
            Game game = gameLifecycleService.getGame(ROOM_ID);
            game.markGameOver();
            return game;
        });
    }

    private void verifyNobodyRemovedFromRoom() {
        verify(roomService, never()).leavePlayersBatch(anyString(), anyList(), any());
        verify(roomService, never()).leaveRoom(anyString(), anyString());
    }

    private record RoomGameSnapshot(
            List<String> roster,
            Map<String, String> membershipIds,
            Map<String, Boolean> connected,
            Map<String, Long> absenceDeadlines,
            Map<String, Boolean> gamePlayerDisconnected,
            Map<String, Long> gamePlayerDisconnectDeadlines,
            Boolean gameOver,
            Map<ScheduledGameTask, Long> scheduledDeadlines,
            Map<ScheduledGameTask, Long> scheduledGenerations) {
    }

    private RoomGameSnapshot snapshotRoomAndGame(Room room, Game game) {
        List<String> roster = List.copyOf(room.getPlayers());
        Map<String, String> membershipIds = new LinkedHashMap<>();
        Map<String, Boolean> connected = new LinkedHashMap<>();
        Map<String, Long> absenceDeadlines = new LinkedHashMap<>();
        for (String name : roster) {
            membershipIds.put(name, room.getMembershipId(name));
            connected.put(name, room.isPlayerConnected(name));
            absenceDeadlines.put(name, room.getAbsenceDeadline(name));
        }
        Map<String, Boolean> gameDisconnected = new LinkedHashMap<>();
        Map<String, Long> gameDeadlines = new LinkedHashMap<>();
        Boolean gameOver = null;
        Map<ScheduledGameTask, Long> scheduledDeadlines = Map.of();
        Map<ScheduledGameTask, Long> scheduledGenerations = Map.of();
        if (game != null) {
            for (Player player : game.getPlayers()) {
                gameDisconnected.put(player.getName(), player.getIsDisconnected());
                gameDeadlines.put(player.getName(), player.getDisconnectDeadlineEpochMs());
            }
            gameOver = game.isGameOver();
            scheduledDeadlines = game.getScheduledTaskDeadlinesSnapshot();
            scheduledGenerations = game.getScheduledTaskGenerationsSnapshot();
        }
        return new RoomGameSnapshot(roster, membershipIds, connected, absenceDeadlines,
                gameDisconnected, gameDeadlines, gameOver, scheduledDeadlines, scheduledGenerations);
    }

    private void assertNoOpDisconnect(RoomGameSnapshot before, Room room, Game game) {
        assertEquals(before, snapshotRoomAndGame(room, game),
                "A no-op disconnect guard must leave roster/membership/connection/deadline/game-over/scheduled state unchanged");
        verifyNobodyRemovedFromRoom();
        verifyNoInteractions(gameStateService);
    }

    // ==================== Departure & Host Transfer Tests ====================

    @Nested
    @DisplayName("Departure & Host Transfer")
    class DepartureAndHostTransferTests {

        @Test
        void removePlayers_IdempotentOnMissingOrNullArguments() {
            assertDoesNotThrow(() -> withRoomLock("nonexistent-id",
                    () -> gameLifecycleService.removePlayers("nonexistent-id", List.of("Player"))));

            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));
            reset(roomService);
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);

            assertDoesNotThrow(() -> withRoomLock(ROOM_ID,
                    () -> gameLifecycleService.removePlayers(ROOM_ID, List.of("NonExistentPlayer"))));
            assertDoesNotThrow(() -> withRoomLock(ROOM_ID,
                    () -> gameLifecycleService.removePlayers(ROOM_ID, null)));
            assertDoesNotThrow(() -> withRoomLock(ROOM_ID,
                    () -> gameLifecycleService.removePlayers(ROOM_ID, List.of())));
            assertDoesNotThrow(() -> withRoomLock("missing-room",
                    () -> gameLifecycleService.removePlayers("missing-room", List.of("Player2"))));

            verifyNobodyRemovedFromRoom();
        }

        @Test
        void removePlayers_OneContenderRemains_EndsGame() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));
            Game game = getGameLocked(ROOM_ID);
            String playerToLeave = game.getPlayers().getFirst().getName();

            withRoomLock(ROOM_ID, () -> gameLifecycleService.removePlayers(ROOM_ID, List.of(playerToLeave)));

            verify(gameStateService).sendGameEnd(any(GameEndResponse.class));
        }

        @Test
        void removePlayers_WhenLastConnectedPlayerLeaves_PreservesDisconnectedPlayerGrace() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> {
                gameLifecycleService.createGameFromRoom(ROOM_ID);
                gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Host", BASE_EPOCH_MS + 120_000L);
            });
            reset(gameStateService);
            stubGameEndCapture();

            withRoomLock(ROOM_ID, () -> gameLifecycleService.removePlayers(ROOM_ID, List.of("Player2")));

            assertNotNull(getGameLocked(ROOM_ID));
            assertTrue(withRoomLock(ROOM_ID, () -> gameLifecycleService.playerExistsInGame(ROOM_ID, "Host")));
            verify(roomService, never()).destroyRoom(ROOM_ID);
            verify(gameStateService, never()).sendGameEnd(any(GameEndResponse.class));
        }

        @Test
        void removePlayers_WithMultiplePlayers_ShouldContinueGameAndKeepTurnIndexSafe() {
            testRoom.addPlayer("Player3");
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));
            reset(gameStateService);
            stubGameEndCapture();

            Game game = getGameLocked(ROOM_ID);
            for (int i = 0; i < 2; i++) {
                Player actor = game.getCurrentPlayer();
                PlayerAction action = actor.getCurrentBet() < game.getCurrentHighestBet()
                        ? PlayerAction.CALL : PlayerAction.CHECK;
                game.processPlayerDecision(actor, new PlayerDecision(action, 0, actor.getPlayerId()));
            }
            Player currentPlayer = game.getCurrentPlayer();
            Player playerToLeave = game.getPlayers().stream()
                    .filter(p -> !p.equals(currentPlayer))
                    .findFirst()
                    .orElseThrow();

            assertDoesNotThrow(() -> withRoomLock(ROOM_ID,
                    () -> gameLifecycleService.removePlayers(ROOM_ID, List.of(playerToLeave.getName()))));

            Game remaining = getGameLocked(ROOM_ID);
            assertEquals(2, remaining.getPlayers().size());
            assertNotNull(remaining.getCurrentPlayer());
            verify(gameStateService).broadcastGameState(any(Game.class));
        }

        @Test
        void removePlayers_WhenRoomMissingOrDestroyedMidFlow_CleansUpWithoutBroadcast() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));
            reset(gameStateService);
            stubGameEndCapture();
            Game game = getGameLocked(ROOM_ID);
            String playerToLeave = game.getPlayers().getFirst().getName();
            when(roomService.getRoom(ROOM_ID)).thenReturn(null);

            assertDoesNotThrow(() -> withRoomLock(ROOM_ID,
                    () -> gameLifecycleService.removePlayers(ROOM_ID, List.of(playerToLeave))));
            assertNull(getGameLocked(ROOM_ID));
            verify(gameStateService, never()).broadcastGameState(any(Game.class));

            // Recreate with 3 players, then simulate room disappearing mid-flow
            testRoom.setGameStarted(false);
            testRoom.addPlayer("Player3");
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));
            reset(gameStateService);
            stubGameEndCapture();
            Game recreated = getGameLocked(ROOM_ID);
            Player current = recreated.getCurrentPlayer();
            String nonCurrent = recreated.getPlayers().stream()
                    .filter(p -> !p.equals(current))
                    .findFirst()
                    .orElseThrow()
                    .getName();
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom, testRoom, null);

            assertDoesNotThrow(() -> withRoomLock(ROOM_ID,
                    () -> gameLifecycleService.removePlayers(ROOM_ID, List.of(nonCurrent))));
            assertNull(getGameLocked(ROOM_ID));
            verify(gameStateService, never()).broadcastGameState(any(Game.class));
        }

        @Test
        void removePlayers_ConnectedZeroChipUnsettledContenderCanRemainTournamentWinner() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));
            Game game = getGameLocked(ROOM_ID);
            Player zeroChipSurvivor = findGamePlayer(game, "Host");
            setPlayerChips(zeroChipSurvivor, 0);
            reset(gameStateService);
            stubGameEndCapture();

            withRoomLock(ROOM_ID, () -> gameLifecycleService.removePlayers(ROOM_ID, List.of("Player2")));

            verify(gameStateService).sendGameEnd(argThat(event -> event != null && event.winner().equals("Host")));
            assertTrue(game.isGameOver());
            assertTrue(game.getPlayers().stream().anyMatch(player -> player.getName().equals("Player2")));
        }

        @Test
        void removePlayers_LobbyAndActiveHostTransferAndBatchAndTerminalFreeze() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            when(roomService.getCurrentHost(ROOM_ID)).thenReturn("Host");
            withRoomLock(ROOM_ID, () -> gameLifecycleService.removePlayers(ROOM_ID, List.of("Host")));
            verify(roomService).leaveRoom(ROOM_ID, "Host");
            verify(roomService, never()).leavePlayersBatch(anyString(), anyList(), any());

            reset(roomService);
            testRoom.addPlayer("Player3");
            testRoom.addPlayer("Player4");
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            when(roomService.getCurrentHost(ROOM_ID)).thenReturn("Host");
            withRoomLock(ROOM_ID, () -> {
                gameLifecycleService.createGameFromRoom(ROOM_ID);
                gameLifecycleService.removePlayers(ROOM_ID, List.of("Player3", "Player4"));
            });
            verify(roomService, times(1)).leavePlayersBatch(ROOM_ID, List.of("Player3", "Player4"), "Host");
            assertEquals(2, getGameLocked(ROOM_ID).getPlayers().size());
            assertFalse(getGameLocked(ROOM_ID).isGameOver());

            testRoom.removePlayer("Player3");
            testRoom.removePlayer("Player4");
            reset(roomService);
            testRoom.setGameStarted(false);
            testRoom.addPlayer("Player3");
            withRoomLock(ROOM_ID, () -> gameLifecycleService.evictGame(ROOM_ID));
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> {
                gameLifecycleService.createGameFromRoom(ROOM_ID);
                gameLifecycleService.removePlayers(ROOM_ID, List.of("Host"));
            });
            verify(roomService).leavePlayersBatch(ROOM_ID, List.of("Host"), "Player2");
            assertFalse(getGameLocked(ROOM_ID).getPlayers().stream().anyMatch(p -> p.getName().equals("Host")));

            // Terminal state frozen: roster stays intact
            Game active = getGameLocked(ROOM_ID);
            active.markGameOver();
            reset(roomService);
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.removePlayers(ROOM_ID, List.of("Player2")));
            verify(roomService).leavePlayersBatch(eq(ROOM_ID), eq(List.of("Player2")), any());
            assertTrue(active.getPlayers().stream().anyMatch(p -> p.getName().equals("Player2")));
        }

        @Test
        void removePlayers_PreFlopAbandonedDuringCleanupWindow_DetachesWithoutSettling() {
            HandEvaluatorService realEvaluator = new HandEvaluatorService();
            lenient().when(handEvaluator.getBestHand(anyList(), anyList()))
                    .thenAnswer(inv -> realEvaluator.getBestHand(inv.getArgument(0), inv.getArgument(1)));
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);

            withRoomLock(ROOM_ID, () -> {
                gameLifecycleService.createGameFromRoom(ROOM_ID);
                Game game = gameLifecycleService.getGame(ROOM_ID);
                assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
                assertTrue(game.getCommunityCards().isEmpty());
                gameLifecycleService.abandonGameDueToInactivity(ROOM_ID);
                assertTrue(game.isGameOver());
                assertTrue(game.getGameEndResult().abandoned());
                assertFalse(game.isFinalPotSettled());
            });

            reset(gameStateService, roomService);
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);

            withRoomLock(ROOM_ID, () -> gameLifecycleService.removePlayers(ROOM_ID, List.of("Player2")));

            Game game = getGameLocked(ROOM_ID);
            verify(roomService).leavePlayersBatch(eq(ROOM_ID), eq(List.of("Player2")), any());
            verify(handEvaluator, never()).getBestHand(anyList(), anyList());
            verifyNoInteractions(gameStateService);
            assertFalse(game.isFinalPotSettled());
            assertTrue(game.getGameEndResult().abandoned());
        }

        @Test
        void pendingForfeitSurvivorExpiryCleansSettledShowdownRoomGameAndTasks() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));
            Game game = getGameLocked(ROOM_ID);
            Player host = game.getPlayers().get(0);
            Player survivor = game.getPlayers().get(1);
            host.setIsOut();
            game.conductShowdown();
            assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());
            assertTrue(game.getLastSettlementResult().settled());
            survivor.setDisconnected(true);
            game.setForfeitPending(true);
            game.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, 10L);
            game.scheduleTask(ScheduledGameTask.GAME_END, 10L);
            game.scheduleTask(ScheduledGameTask.CLEANUP, 10L);
            testRoom.removePlayer(host.getName());

            AtomicInteger roomPresent = new AtomicInteger(1);
            reset(roomService);
            when(roomService.getRoom(ROOM_ID)).thenAnswer(_ -> roomPresent.get() == 1 ? testRoom : null);
            doAnswer(invocation -> {
                List<String> leaving = invocation.getArgument(1);
                leaving.forEach(testRoom::removePlayer);
                if (testRoom.getPlayers().isEmpty()) {
                    roomPresent.set(0);
                }
                return null;
            }).when(roomService).leavePlayersBatch(eq(ROOM_ID), anyList(), any());

            long p2Deadline = BASE_EPOCH_MS - 100;
            survivor.setDisconnectDeadlineEpochMs(p2Deadline);
            withRoomLock(ROOM_ID, () -> {
                gameLifecycleService.removeDisconnectedPlayer(
                        ROOM_ID, survivor.getName(), testRoom.getMembershipId(survivor.getName()), p2Deadline);
                assertFalse(gameLifecycleService.gameExists(ROOM_ID));
            });

            assertEquals(0, roomPresent.get());
            assertTrue(testRoom.getPlayers().isEmpty());
            assertTrue(game.getScheduledTaskDeadlinesSnapshot().isEmpty());
        }

        @Test
        void pendingForfeitRetriesPreserveOutRowsAndSpectatorDepartureDoesNotAdvanceGame() {
            testRoom.addPlayer("Spectator");
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));
            Game game = getGameLocked(ROOM_ID);
            Player spectator = findGamePlayer(game, "Spectator");
            setPlayerChips(spectator, 0);
            spectator.setIsOut();
            withRoomLock(ROOM_ID, () ->
                    gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", BASE_EPOCH_MS + 60_000L));

            AtomicInteger roomPresent = new AtomicInteger(1);
            reset(roomService);
            when(roomService.getRoom(ROOM_ID)).thenAnswer(_ -> roomPresent.get() == 1 ? testRoom : null);
            doAnswer(invocation -> {
                List<String> leaving = invocation.getArgument(1);
                leaving.forEach(testRoom::removePlayer);
                if (testRoom.getPlayers().isEmpty()) {
                    roomPresent.set(0);
                }
                return null;
            }).when(roomService).leavePlayersBatch(eq(ROOM_ID), anyList(), any());

            withRoomLock(ROOM_ID, () -> {
                gameLifecycleService.removePlayers(ROOM_ID, List.of("Host"));
                assertTrue(game.isForfeitPending());
                gameLifecycleService.removePlayers(ROOM_ID, List.of("Host"));
                gameLifecycleService.removePlayers(ROOM_ID, List.of("Spectator"));
                assertTrue(game.getPlayers().stream().anyMatch(p -> p.getName().equals("Host") && p.getIsOut()));
                assertTrue(game.getPlayers().stream().anyMatch(p -> p.getName().equals("Spectator") && p.getIsOut()));
                assertTrue(game.isForfeitPending());
                assertTrue(gameLifecycleService.gameExists(ROOM_ID));

                long p2ForfeitDeadline = BASE_EPOCH_MS - 100;
                findGamePlayer(game, "Player2").setDisconnectDeadlineEpochMs(p2ForfeitDeadline);
                gameLifecycleService.removeDisconnectedPlayer(
                        ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), p2ForfeitDeadline);
                assertFalse(gameLifecycleService.gameExists(ROOM_ID));
            });
            assertEquals(0, roomPresent.get());
            assertTrue(game.getScheduledTaskDeadlinesSnapshot().isEmpty());
        }

        @Test
        void pendingForfeitInvalidatesCapturedProgressionCallbacksAndReconnectSettlesOnce() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));
            Game game = getGameLocked(ROOM_ID);
            Player departing = findGamePlayer(game, "Host");
            Player survivor = findGamePlayer(game, "Player2");
            withRoomLock(ROOM_ID, () ->
                    gameLifecycleService.markPlayerDisconnected(ROOM_ID, survivor.getName(), BASE_EPOCH_MS + 60_000L));

            Map<ScheduledGameTask, Long> deadlines = new EnumMap<>(ScheduledGameTask.class);
            Map<ScheduledGameTask, Long> generations = new EnumMap<>(ScheduledGameTask.class);
            long deadline = BASE_EPOCH_MS + 30_000L;
            for (ScheduledGameTask task : List.of(ScheduledGameTask.READY_OPEN, ScheduledGameTask.READY_TIMEOUT,
                    ScheduledGameTask.AUTO_ADVANCE, ScheduledGameTask.NEW_HAND, ScheduledGameTask.GAME_END)) {
                game.scheduleTask(task, deadline++);
                deadlines.put(task, game.getScheduledTaskDeadline(task));
                generations.put(task, game.getScheduledTaskGeneration(task));
            }

            doAnswer(invocation -> {
                List<String> leaving = invocation.getArgument(1);
                leaving.forEach(testRoom::removePlayer);
                return null;
            }).when(roomService).leavePlayersBatch(eq(ROOM_ID), anyList(), any());
            long handBefore = game.getHandNumber();
            int potBefore = game.getPot();
            int departingStackBefore = departing.getChips();
            int expectedSurvivorEndChips = survivor.getChips() + potBefore;

            withRoomLock(ROOM_ID, () ->
                    gameLifecycleService.removePlayers(ROOM_ID, List.of(departing.getName())));
            assertTrue(game.isForfeitPending());
            assertTrue(departing.getIsOut());
            assertFalse(testRoom.hasPlayer(departing.getName()));

            reset(gameStateService);
            stubGameEndCapture();
            withRoomLock(ROOM_ID, () -> {
                for (ScheduledGameTask task : deadlines.keySet()) {
                    gameLifecycleService.executeScheduledTask(
                            ROOM_ID, task, deadlines.get(task), handBefore, generations.get(task));
                }
            });
            assertEquals(handBefore, game.getHandNumber());
            assertEquals(potBefore, game.getPot());
            assertFalse(game.isGameOver());
            assertTrue(game.isForfeitPending());
            verify(gameStateService, never()).sendGameEnd(any(GameEndResponse.class));
            verify(gameStateService, never()).broadcastGameState(any(Game.class));

            AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                    JsonMapper.builder().findAndAddModules().build(), handEvaluator);
            Game restored = mapper.deserialize(mapper.serialize(testRoom, survivor.getName(), game)).game();
            assertTrue(restored.isForfeitPending());
            assertTrue(restored.getPlayers().stream()
                    .anyMatch(player -> player.getName().equals(departing.getName()) && player.getIsOut()));
            assertFalse(mapper.deserialize(mapper.serialize(testRoom, survivor.getName(), game))
                    .room().hasPlayer(departing.getName()));
            withRoomLock(ROOM_ID, () -> gameLifecycleService.restoreGame(restored));
            Player restoredSurvivor = findGamePlayer(restored, survivor.getName());

            withRoomLock(ROOM_ID, () -> gameLifecycleService.markPlayerReconnected(ROOM_ID, restoredSurvivor.getName()));
            assertTrue(restored.isGameOver());
            assertFalse(restored.isForfeitPending());
            assertEquals(departingStackBefore, findGamePlayer(restored, departing.getName()).getChips());
            assertEquals(expectedSurvivorEndChips, restoredSurvivor.getChips());
            assertEquals(BASE_EPOCH_MS + DEFAULT_TIMINGS.gameEndDisplayDelayMs(),
                    restored.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP));
            verify(taskScheduler, atLeastOnce()).schedule(any(Runnable.class), any(Instant.class));
            verify(gameStateService, times(1)).sendGameEnd(any(GameEndResponse.class));

            withRoomLock(ROOM_ID, () -> gameLifecycleService.markPlayerReconnected(ROOM_ID, restoredSurvivor.getName()));
            verify(gameStateService, times(1)).sendGameEnd(any(GameEndResponse.class));
            assertEquals(expectedSurvivorEndChips, restoredSurvivor.getChips());
        }

        @Test
        void removePlayers_HostSuccessionPriorityOrder() {
            // 1. Chooses later connected contender over earlier busted member
            testRoom.addPlayer("Player3");
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            when(roomService.getCurrentHost(ROOM_ID)).thenReturn("Host");
            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));
            Game g1 = getGameLocked(ROOM_ID);
            Player p2 = findGamePlayer(g1, "Player2");
            setPlayerChips(p2, 0);
            p2.setIsOut();
            Player p3 = findGamePlayer(g1, "Player3");
            setPlayerChips(p3, 1000);
            p3.setDisconnected(false);
            reset(roomService);
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            when(roomService.getCurrentHost(ROOM_ID)).thenReturn("Host");
            withRoomLock(ROOM_ID, () -> gameLifecycleService.removePlayers(ROOM_ID, List.of("Host")));
            verify(roomService).leavePlayersBatch(ROOM_ID, List.of("Host"), "Player3");

            // 2. Chooses unsettled zero-stack all-in contender over later candidate
            String r2Id = "succ-allin";
            Room r2 = new Room(r2Id, "R2", "Host", 6, 5, 10, 100, null);
            r2.addPlayer("Host");
            r2.addPlayer("Player2");
            r2.addPlayer("Player3");
            when(roomService.getRoom(r2Id)).thenReturn(r2);
            when(roomService.getCurrentHost(r2Id)).thenReturn("Host");
            withRoomLock(r2Id, () -> gameLifecycleService.createGameFromRoom(r2Id));
            Game g2 = getGameLocked(r2Id);
            Player g2p2 = findGamePlayer(g2, "Player2");
            setPlayerChips(g2p2, 0);
            g2p2.setDisconnected(false);
            reset(roomService);
            when(roomService.getRoom(r2Id)).thenReturn(r2);
            when(roomService.getCurrentHost(r2Id)).thenReturn("Host");
            withRoomLock(r2Id, () -> gameLifecycleService.removePlayers(r2Id, List.of("Host")));
            verify(roomService).leavePlayersBatch(r2Id, List.of("Host"), "Player2");

            // 3. Chooses disconnected contender over room-only spectator
            String r3Id = "succ-dc";
            Room r3 = new Room(r3Id, "R3", "Host", 6, 5, 10, 100, null);
            r3.addPlayer("Host");
            r3.addPlayer("Player2");
            r3.addPlayer("Player3");
            when(roomService.getRoom(r3Id)).thenReturn(r3);
            when(roomService.getCurrentHost(r3Id)).thenReturn("Host");
            withRoomLock(r3Id, () -> gameLifecycleService.createGameFromRoom(r3Id));
            Game g3 = getGameLocked(r3Id);
            g3.removePlayer(findGamePlayer(g3, "Player3"));
            Player g3p2 = findGamePlayer(g3, "Player2");
            setPlayerChips(g3p2, 1000);
            g3p2.setDisconnected(true);
            reset(roomService);
            when(roomService.getRoom(r3Id)).thenReturn(r3);
            when(roomService.getCurrentHost(r3Id)).thenReturn("Host");
            withRoomLock(r3Id, () -> gameLifecycleService.removePlayers(r3Id, List.of("Host")));
            verify(roomService).leavePlayersBatch(r3Id, List.of("Host"), "Player2");

            // 4. Chooses join-order fallback when no contenders remain + batch + null when empty
            String r4Id = "succ-fallback";
            Room r4 = new Room(r4Id, "R4", "Host", 6, 5, 10, 100, null);
            r4.addPlayer("Host");
            r4.addPlayer("Player2");
            r4.addPlayer("Spectator1");
            r4.addPlayer("Spectator2");
            when(roomService.getRoom(r4Id)).thenReturn(r4);
            when(roomService.getCurrentHost(r4Id)).thenReturn("Host");
            withRoomLock(r4Id, () -> gameLifecycleService.createGameFromRoom(r4Id));
            Game g4 = getGameLocked(r4Id);
            Player g4p2 = findGamePlayer(g4, "Player2");
            setPlayerChips(g4p2, 0);
            g4p2.setIsOut();
            g4.removePlayer(findGamePlayer(g4, "Spectator1"));
            g4.removePlayer(findGamePlayer(g4, "Spectator2"));
            reset(roomService);
            when(roomService.getRoom(r4Id)).thenReturn(r4);
            when(roomService.getCurrentHost(r4Id)).thenReturn("Host");
            withRoomLock(r4Id, () -> gameLifecycleService.removePlayers(r4Id, List.of("Host")));
            verify(roomService).leavePlayersBatch(r4Id, List.of("Host"), "Player2");

            String r5Id = "succ-batch";
            Room r5 = new Room(r5Id, "R5", "Host", 6, 5, 10, 100, null);
            r5.addPlayer("Host");
            r5.addPlayer("Player2");
            r5.addPlayer("Player3");
            r5.addPlayer("Player4");
            when(roomService.getRoom(r5Id)).thenReturn(r5);
            when(roomService.getCurrentHost(r5Id)).thenReturn("Host");
            withRoomLock(r5Id, () -> gameLifecycleService.createGameFromRoom(r5Id));
            reset(roomService);
            when(roomService.getRoom(r5Id)).thenReturn(r5);
            when(roomService.getCurrentHost(r5Id)).thenReturn("Host");
            withRoomLock(r5Id, () -> gameLifecycleService.removePlayers(r5Id, List.of("Host", "Player2", "Player3")));
            verify(roomService).leavePlayersBatch(r5Id, List.of("Host", "Player2", "Player3"), "Player4");

            String r6Id = "succ-empty";
            Room r6 = new Room(r6Id, "R6", "Host", 6, 5, 10, 100, null);
            r6.addPlayer("Host");
            r6.addPlayer("Player2");
            when(roomService.getRoom(r6Id)).thenReturn(r6);
            when(roomService.getCurrentHost(r6Id)).thenReturn("Host");
            withRoomLock(r6Id, () -> gameLifecycleService.createGameFromRoom(r6Id));
            getGameLocked(r6Id).markGameOver();
            reset(roomService);
            when(roomService.getRoom(r6Id)).thenReturn(r6);
            when(roomService.getCurrentHost(r6Id)).thenReturn("Host");
            withRoomLock(r6Id, () -> gameLifecycleService.removePlayers(r6Id, List.of("Host", "Player2")));
            verify(roomService).leavePlayersBatch(r6Id, List.of("Host", "Player2"), null);
        }
    }

    // ==================== Disconnect, Reconnect & Turn Timeout ====================

    @Nested
    @DisplayName("Disconnect, Reconnect & Turn Timeout")
    class DisconnectReconnectAndTurnTimeoutTests {

        @Test
        void markPlayerDisconnected_AndReconnected_UpdateFlagsAndBroadcast() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));
            long deadline = BASE_EPOCH_MS + 120_000L;
            reset(gameStateService);
            stubGameEndCapture();

            withRoomLock(ROOM_ID, () -> gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", deadline));
            Game game = getGameLocked(ROOM_ID);
            Player player2 = findGamePlayer(game, "Player2");
            assertTrue(player2.getIsDisconnected());
            assertEquals(deadline, player2.getDisconnectDeadlineEpochMs());
            verify(gameStateService).broadcastGameState(game);

            reset(gameStateService);
            stubGameEndCapture();
            withRoomLock(ROOM_ID, () -> gameLifecycleService.markPlayerReconnected(ROOM_ID, "Player2"));
            assertFalse(player2.getIsDisconnected());
            assertNull(player2.getDisconnectDeadlineEpochMs());
            verify(gameStateService).broadcastGameState(game);

            // Reconnect when game over does not broadcast
            withRoomLock(ROOM_ID, () -> gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Host", deadline));
            game.markGameOver();
            reset(gameStateService);
            stubGameEndCapture();
            withRoomLock(ROOM_ID, () -> gameLifecycleService.markPlayerReconnected(ROOM_ID, "Host"));
            verify(gameStateService, never()).broadcastGameState(any(Game.class));
        }

        @Test
        void handleTurnTimeout_RemovesCurrentPlayerOrAbandonsIfInactivityExpired() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));
            Game game = getGameLocked(ROOM_ID);
            String actor = game.getCurrentPlayer().getName();

            try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
                departureHandler.handleTurnTimeout(ROOM_ID, game);
            }
            verify(roomService).leavePlayersBatch(eq(ROOM_ID), eq(List.of(actor)), any());

            // Inactivity expired takes precedence over actor removal
            String inactiveRoomId = "turn-inactive";
            Room inactiveRoom = new Room(inactiveRoomId, "Inactive", "Host", 6, 5, 10, 100, null);
            inactiveRoom.addPlayer("Host");
            inactiveRoom.addPlayer("Player2");
            when(roomService.getRoom(inactiveRoomId)).thenReturn(inactiveRoom);
            withRoomLock(inactiveRoomId, () -> gameLifecycleService.createGameFromRoom(inactiveRoomId));
            Game inactiveGame = getGameLocked(inactiveRoomId);
            mutableClock.setMillis(inactiveRoom.getInactivityDeadlineEpochMs());
            reset(roomService);
            when(roomService.getRoom(inactiveRoomId)).thenReturn(inactiveRoom);

            try (LockLease _ = roomCoordinator.acquire(inactiveRoomId)) {
                departureHandler.handleTurnTimeout(inactiveRoomId, inactiveGame);
            }
            assertTrue(inactiveGame.isGameOver());
            assertTrue(inactiveGame.getGameEndResult().abandoned());
            verify(roomService, never()).leavePlayersBatch(anyString(), anyList(), any());
        }
    }

    // ==================== Claim Win & Settlement Equivalence ====================

    @Nested
    @DisplayName("Claim Win & Settlement Equivalence")
    class ClaimWinTests {

        @Test
        void claimWin_DisconnectedOpponents_EndsGameAndIsIdempotent() {
            testRoom.addPlayer("Player3");
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> {
                gameLifecycleService.createGameFromRoom(ROOM_ID);
                gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", BASE_EPOCH_MS + 120_000L);
                gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player3", BASE_EPOCH_MS + 120_000L);
            });

            reset(gameStateService);
            stubGameEndCapture();

            GameEndResponse result = withRoomLock(ROOM_ID, () -> gameLifecycleService.claimWin(ROOM_ID, "Host"));
            assertEquals("GAME_END", result.type());
            assertEquals("Host", result.winner());
            assertTrue(result.isForfeit());
            assertNotNull(result.finalState());
            assertTrue(result.finalState().players().stream()
                    .anyMatch(p -> p.name().equals("Host") && Boolean.TRUE.equals(p.isWinner())));
            verify(gameStateService).sendGameEnd(
                    argThat(event -> event != null && "Host".equals(event.winner()) && event.isForfeit()));
            verify(roomService, times(1)).leavePlayersBatch(eq(ROOM_ID), eq(List.of("Player2", "Player3")), any());
            verify(roomService, never()).leaveRoom(anyString(), anyString());
            verify(gameStateService).recordGameEnd(
                    argThat(game -> game.getPlayers().stream().map(Player::getName).collect(Collectors.toSet())
                            .equals(Set.of("Host", "Player2", "Player3"))
                            && game.getPlayers().stream().filter(p -> p.getName().startsWith("Player"))
                            .allMatch(Player::getIsOut)),
                    any(Player.class),
                    eq(true));

            Game game = getGameLocked(ROOM_ID);
            assertTrue(game.isGameOver());
            assertSame(result, game.getGameEndResult());
            Long initialCleanup = game.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP);
            assertNotNull(initialCleanup);

            reset(gameStateService);
            GameEndResponse repeated = withRoomLock(ROOM_ID, () -> gameLifecycleService.claimWin(ROOM_ID, "Host"));
            assertSame(result, repeated);
            verify(gameStateService, never()).sendGameEnd(any());
            assertEquals(initialCleanup, game.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP));

            assertThrows(UnauthorisedActionException.class,
                    () -> withRoomLock(ROOM_ID, () -> gameLifecycleService.claimWin(ROOM_ID, "Stranger")));
        }

        @Test
        void claimWin_ConnectedOpponentOrAbandonedGame_ThrowsExpectedException() {
            testRoom.addPlayer("Player3");
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> {
                gameLifecycleService.createGameFromRoom(ROOM_ID);
                gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", BASE_EPOCH_MS + 120_000L);
            });

            UnauthorisedActionException connectedEx = assertThrows(UnauthorisedActionException.class,
                    () -> withRoomLock(ROOM_ID, () -> gameLifecycleService.claimWin(ROOM_ID, "Host")));
            assertTrue(connectedEx.getMessage().toLowerCase().contains("claim"));

            withRoomLock(ROOM_ID, () -> gameLifecycleService.abandonGameDueToInactivity(ROOM_ID));
            BadRequestException abandonedEx = assertThrows(BadRequestException.class,
                    () -> withRoomLock(ROOM_ID, () -> gameLifecycleService.claimWin(ROOM_ID, "Host")));
            assertEquals("GAME_ABANDONED", abandonedEx.actionErrorCode());
        }

        @Test
        void leaveClaimAndGraceExpirySettleCommittedLargePotOnceThroughFinalDto() {
            for (String ending : List.of("leave", "claim", "grace")) {
                String gameId = ROOM_ID + "-" + ending;
                Room room = new Room(gameId, "Terminal " + ending, "Host", 2, 10, 20, 1000, null);
                room.addPlayer("Host");
                room.addPlayer("Player2");
                when(roomService.getRoom(gameId)).thenReturn(room);
                reset(messagingTemplate);
                GameStateService realState = new GameStateService(roomService, messagingTemplate, mutableClock);
                wireLifecycle(realState);

                withRoomLock(gameId, () -> gameLifecycleService.createGameFromRoom(gameId));
                Game game = getGameLocked(gameId);
                Player smallBlind = game.getPlayers().stream()
                        .filter(player -> player.getPlayerId().equals(game.getSmallBlindPlayerId()))
                        .findFirst().orElseThrow();
                Player bigBlind = game.getPlayers().stream()
                        .filter(player -> player.getPlayerId().equals(game.getBigBlindPlayerId()))
                        .findFirst().orElseThrow();
                lenient().when(handEvaluator.getBestHand(anyList(), anyList())).thenAnswer(invocation -> {
                    List<Card> holeCards = invocation.getArgument(1);
                    HandRank rank = holeCards.equals(smallBlind.getHoleCards())
                            ? HandRank.ROYAL_FLUSH : HandRank.HIGH_CARD;
                    return new HandEvaluationResult(List.of(), rank);
                });

                Player actor = game.getCurrentPlayer();
                if (!actor.equals(smallBlind)) {
                    game.processPlayerDecision(actor, new PlayerDecision(
                            actor.getCurrentBet() < game.getCurrentHighestBet()
                                    ? PlayerAction.CALL : PlayerAction.CHECK,
                            0, actor.getPlayerId()));
                }
                PlayerAction aggressiveAction = game.getCurrentPhase() == GamePhase.PRE_FLOP
                        ? PlayerAction.RAISE : PlayerAction.BET;
                game.processPlayerDecision(smallBlind, new PlayerDecision(
                        aggressiveAction, 500, smallBlind.getPlayerId()));
                game.processPlayerDecision(bigBlind, new PlayerDecision(
                        PlayerAction.CALL, 0, bigBlind.getPlayerId()));
                assertEquals(1020, game.getPot());

                withRoomLock(gameId, () -> {
                    if (!ending.equals("leave")) {
                        gameLifecycleService.markPlayerDisconnected(
                                gameId, smallBlind.getName(), BASE_EPOCH_MS + 60_000L);
                    }
                    if (ending.equals("leave")) {
                        gameLifecycleService.removePlayers(gameId, List.of(smallBlind.getName()));
                    } else if (ending.equals("claim")) {
                        gameLifecycleService.claimWin(gameId, bigBlind.getName());
                    } else {
                        long sbDeadline = BASE_EPOCH_MS - 100;
                        smallBlind.setDisconnectDeadlineEpochMs(sbDeadline);
                        gameLifecycleService.removeDisconnectedPlayer(
                                gameId, smallBlind.getName(), room.getMembershipId(smallBlind.getName()), sbDeadline);
                    }
                });

                ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
                verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/game/" + gameId), captor.capture());
                GameEndResponse result = captor.getAllValues().stream()
                        .filter(GameEndResponse.class::isInstance)
                        .map(GameEndResponse.class::cast)
                        .findFirst()
                        .orElseThrow();
                assertEquals(1510, result.winnerChips(), "Unexpected payout for " + ending);
                assertEquals(0, result.finalState().pot());
                assertEquals(1510, result.finalState().players().stream()
                        .filter(PublicPlayerState::isWinner).findFirst().orElseThrow().chips());
                assertNotNull(game.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP),
                        "Cleanup deadline should be scheduled for " + ending);

                try (LockLease _ = roomCoordinator.acquire(gameId)) {
                    completionHandler.handleGameEnd(gameId);
                }
                assertEquals(1510, bigBlind.getChips());
            }
        }
    }

    // ==================== Disconnected Grace Period Guards ====================

    @Nested
    @DisplayName("Disconnected Grace Guards")
    class RemoveDisconnectedPlayerGuardTests {

        @Test
        void removeDisconnectedPlayer_ValidExpiryPaths_DepartAsExpected() {
            // Active game expired player departs
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            long deadline = BASE_EPOCH_MS - 100;
            withRoomLock(ROOM_ID, () -> {
                gameLifecycleService.createGameFromRoom(ROOM_ID);
                gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", deadline);
                String p2MembershipId = testRoom.getMembershipId("Player2");
                gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", p2MembershipId, deadline);
            });
            verify(roomService).leavePlayersBatch(eq(ROOM_ID), eq(List.of("Player2")), any());

            // Lobby host expired destroys room
            Room lobbyHost = new Room("lobby-1", "Lobby", "Host", 4, 10, 20, 1000, null);
            lobbyHost.addPlayer("Host");
            when(roomService.getRoom("lobby-1")).thenReturn(lobbyHost);
            when(roomService.getCurrentHost("lobby-1")).thenReturn("Host");
            lobbyHost.setAbsenceDeadline("Host", deadline);
            withRoomLock("lobby-1", () -> gameLifecycleService.removeDisconnectedPlayer(
                    "lobby-1", "Host", lobbyHost.getMembershipId("Host"), deadline));
            verify(roomService).leaveRoom("lobby-1", "Host");

            // Lobby guest expired releases seat
            Room lobbyGuest = new Room("lobby-2", "Lobby", "Host", 4, 10, 20, 1000, null);
            lobbyGuest.addPlayer("Host");
            lobbyGuest.addPlayer("Guest");
            when(roomService.getRoom("lobby-2")).thenReturn(lobbyGuest);
            when(roomService.getCurrentHost("lobby-2")).thenReturn("Host");
            lobbyGuest.setAbsenceDeadline("Guest", deadline);
            withRoomLock("lobby-2", () -> gameLifecycleService.removeDisconnectedPlayer(
                    "lobby-2", "Guest", lobbyGuest.getMembershipId("Guest"), deadline));
            verify(roomService).leavePlayersBatch("lobby-2", List.of("Guest"), "Host");

            // Busted room member expires even though playerExistsInGame is false
            String bustedRoomId = "busted-room";
            Room bustedRoom = new Room(bustedRoomId, "Busted", "Host", 6, 5, 10, 100, null);
            bustedRoom.addPlayer("Host");
            bustedRoom.addPlayer("Player2");
            bustedRoom.addPlayer("Player3");
            when(roomService.getRoom(bustedRoomId)).thenReturn(bustedRoom);
            withRoomLock(bustedRoomId, () -> {
                gameLifecycleService.createGameFromRoom(bustedRoomId);
                Game bustedGame = gameLifecycleService.getGame(bustedRoomId);
                Player p3 = findGamePlayer(bustedGame, "Player3");
                setPlayerChips(p3, 0);
                p3.setIsOut();
                assertFalse(gameLifecycleService.playerExistsInGame(bustedRoomId, "Player3"));
                p3.setDisconnected(true);
                p3.setDisconnectDeadlineEpochMs(deadline);
                gameLifecycleService.removeDisconnectedPlayer(
                        bustedRoomId, "Player3", bustedRoom.getMembershipId("Player3"), deadline);
            });
            verify(roomService).leavePlayersBatch(eq(bustedRoomId), eq(List.of("Player3")), any());

            // Room-only spectator expired while active game runs leaves room only
            String specRoomId = "spec-active";
            Room specRoom = new Room(specRoomId, "Spec", "Host", 6, 5, 10, 100, null);
            specRoom.addPlayer("Host");
            specRoom.addPlayer("Player2");
            when(roomService.getRoom(specRoomId)).thenReturn(specRoom);
            withRoomLock(specRoomId, () -> {
                gameLifecycleService.createGameFromRoom(specRoomId);
                specRoom.addPlayer("Spectator");
                specRoom.setAbsenceDeadline("Spectator", deadline);
                gameLifecycleService.removeDisconnectedPlayer(
                        specRoomId, "Spectator", specRoom.getMembershipId("Spectator"), deadline);
            });
            verify(roomService).leavePlayersBatch(eq(specRoomId), eq(List.of("Spectator")), any());
            assertEquals(2, getGameLocked(specRoomId).getPlayers().size());
        }

        @Test
        void removeDisconnectedPlayer_NullAndLobbyNoOpGuards() {
            withRoomLock(ROOM_ID, () -> {
                gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, null, "membership", 1L);
                gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", null, 1L);
            });
            verifyNoInteractions(roomService);

            when(roomService.getRoom(ROOM_ID)).thenReturn(null);
            withRoomLock(ROOM_ID, () ->
                    gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", "membership", BASE_EPOCH_MS - 100));
            verifyNobodyRemovedFromRoom();

            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () ->
                    gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Stranger", "membership", BASE_EPOCH_MS - 100));
            verifyNobodyRemovedFromRoom();

            Room lobby = new Room("lobby-3", "Lobby", "Host", 4, 10, 20, 1000, null);
            lobby.addPlayer("Host");
            lobby.addPlayer("Guest");
            when(roomService.getRoom("lobby-3")).thenReturn(lobby);
            long pastDeadline = BASE_EPOCH_MS - 100;

            // Obsolete membership
            lobby.setAbsenceDeadline("Guest", pastDeadline);
            RoomGameSnapshot beforeObsolete = snapshotRoomAndGame(lobby, null);
            clearInvocations(gameStateService);
            withRoomLock("lobby-3", () ->
                    gameLifecycleService.removeDisconnectedPlayer("lobby-3", "Guest", "obsolete-id", pastDeadline));
            assertNoOpDisconnect(beforeObsolete, lobby, null);

            // Reconnected
            lobby.setPlayerConnected("Guest", true);
            RoomGameSnapshot beforeReconn = snapshotRoomAndGame(lobby, null);
            withRoomLock("lobby-3", () -> gameLifecycleService.removeDisconnectedPlayer(
                    "lobby-3", "Guest", lobby.getMembershipId("Guest"), pastDeadline));
            assertNoOpDisconnect(beforeReconn, lobby, null);

            // No deadline
            lobby.setPlayerConnected("Guest", false);
            lobby.setAbsenceDeadline("Guest", null);
            RoomGameSnapshot beforeNull = snapshotRoomAndGame(lobby, null);
            withRoomLock("lobby-3", () -> gameLifecycleService.removeDisconnectedPlayer(
                    "lobby-3", "Guest", lobby.getMembershipId("Guest"), pastDeadline));
            assertNoOpDisconnect(beforeNull, lobby, null);

            // Deadline mismatch
            lobby.setAbsenceDeadline("Guest", pastDeadline);
            RoomGameSnapshot beforeMismatch = snapshotRoomAndGame(lobby, null);
            withRoomLock("lobby-3", () -> gameLifecycleService.removeDisconnectedPlayer(
                    "lobby-3", "Guest", lobby.getMembershipId("Guest"), pastDeadline - 5_000L));
            assertNoOpDisconnect(beforeMismatch, lobby, null);

            // Before deadline
            long futureDeadline = BASE_EPOCH_MS + 60_000L;
            lobby.setAbsenceDeadline("Guest", futureDeadline);
            RoomGameSnapshot beforeFuture = snapshotRoomAndGame(lobby, null);
            withRoomLock("lobby-3", () -> gameLifecycleService.removeDisconnectedPlayer(
                    "lobby-3", "Guest", lobby.getMembershipId("Guest"), futureDeadline));
            assertNoOpDisconnect(beforeFuture, lobby, null);
        }

        @Test
        void removeDisconnectedPlayer_ActiveGameNoOpGuardsPreserveFullSnapshot() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));
            Game game = getGameLocked(ROOM_ID);
            long pastDeadline = BASE_EPOCH_MS - 100;
            long futureDeadline = BASE_EPOCH_MS + 60_000L;

            // Obsolete membership id
            withRoomLock(ROOM_ID, () ->
                    gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", pastDeadline));
            RoomGameSnapshot s1 = snapshotRoomAndGame(testRoom, game);
            clearInvocations(gameStateService);
            withRoomLock(ROOM_ID, () ->
                    gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", "obsolete-id", pastDeadline));
            assertNoOpDisconnect(s1, testRoom, game);

            // Deadline mismatch
            withRoomLock(ROOM_ID, () -> gameLifecycleService.removeDisconnectedPlayer(
                    ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), pastDeadline - 5_000L));
            assertNoOpDisconnect(s1, testRoom, game);

            // Reconnected before timer fired
            withRoomLock(ROOM_ID, () -> gameLifecycleService.markPlayerReconnected(ROOM_ID, "Player2"));
            RoomGameSnapshot s2 = snapshotRoomAndGame(testRoom, game);
            clearInvocations(gameStateService);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.removeDisconnectedPlayer(
                    ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), pastDeadline));
            assertFalse(findGamePlayer(game, "Player2").getIsDisconnected());
            assertNoOpDisconnect(s2, testRoom, game);

            // Disconnected with null deadline
            Player p2 = findGamePlayer(game, "Player2");
            p2.setDisconnected(true);
            p2.setDisconnectDeadlineEpochMs(null);
            RoomGameSnapshot s3 = snapshotRoomAndGame(testRoom, game);
            clearInvocations(gameStateService);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.removeDisconnectedPlayer(
                    ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), pastDeadline));
            assertNoOpDisconnect(s3, testRoom, game);

            // Future deadline
            withRoomLock(ROOM_ID, () ->
                    gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", futureDeadline));
            RoomGameSnapshot s4 = snapshotRoomAndGame(testRoom, game);
            clearInvocations(gameStateService);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.removeDisconnectedPlayer(
                    ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), futureDeadline));
            assertNoOpDisconnect(s4, testRoom, game);

            // Room-only spectator guards: no deadline, mismatch, future, connected
            testRoom.addPlayer("Spectator");
            RoomGameSnapshot specNoDeadline = snapshotRoomAndGame(testRoom, game);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.removeDisconnectedPlayer(
                    ROOM_ID, "Spectator", testRoom.getMembershipId("Spectator"), pastDeadline));
            assertNoOpDisconnect(specNoDeadline, testRoom, game);

            testRoom.setAbsenceDeadline("Spectator", pastDeadline);
            RoomGameSnapshot specMismatch = snapshotRoomAndGame(testRoom, game);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.removeDisconnectedPlayer(
                    ROOM_ID, "Spectator", testRoom.getMembershipId("Spectator"), pastDeadline - 5_000L));
            assertNoOpDisconnect(specMismatch, testRoom, game);

            testRoom.setAbsenceDeadline("Spectator", futureDeadline);
            RoomGameSnapshot specFuture = snapshotRoomAndGame(testRoom, game);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.removeDisconnectedPlayer(
                    ROOM_ID, "Spectator", testRoom.getMembershipId("Spectator"), futureDeadline));
            assertNoOpDisconnect(specFuture, testRoom, game);

            testRoom.setPlayerConnected("Spectator", true);
            testRoom.setAbsenceDeadline("Spectator", pastDeadline);
            RoomGameSnapshot specConnected = snapshotRoomAndGame(testRoom, game);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.removeDisconnectedPlayer(
                    ROOM_ID, "Spectator", testRoom.getMembershipId("Spectator"), pastDeadline));
            assertNoOpDisconnect(specConnected, testRoom, game);
        }

        @Test
        void removeDisconnectedPlayer_TerminalGameNoOpsAndExpirations() {
            Game game = createTerminalGame();
            long pastDeadline = BASE_EPOCH_MS - 100;
            long futureDeadline = BASE_EPOCH_MS + 60_000L;
            Player p2 = findGamePlayer(game, "Player2");

            // Null deadline -> no-op
            p2.setDisconnected(true);
            p2.setDisconnectDeadlineEpochMs(null);
            RoomGameSnapshot sNoDeadline = snapshotRoomAndGame(testRoom, game);
            clearInvocations(gameStateService);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.removeDisconnectedPlayer(
                    ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), pastDeadline));
            assertNoOpDisconnect(sNoDeadline, testRoom, game);

            // Obsolete membership
            p2.setDisconnectDeadlineEpochMs(pastDeadline);
            RoomGameSnapshot sObsolete = snapshotRoomAndGame(testRoom, game);
            clearInvocations(gameStateService);
            withRoomLock(ROOM_ID, () ->
                    gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", "obsolete-id", pastDeadline));
            assertNoOpDisconnect(sObsolete, testRoom, game);

            // Deadline mismatch
            withRoomLock(ROOM_ID, () -> gameLifecycleService.removeDisconnectedPlayer(
                    ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), pastDeadline - 5_000L));
            assertNoOpDisconnect(sObsolete, testRoom, game);

            // Game player deadline wins over room deadline
            long roomDeadline = pastDeadline - 5_000L;
            testRoom.setAbsenceDeadline("Player2", roomDeadline);
            RoomGameSnapshot sGameWins = snapshotRoomAndGame(testRoom, game);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.removeDisconnectedPlayer(
                    ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), roomDeadline));
            assertNoOpDisconnect(sGameWins, testRoom, game);

            // Before deadline -> no-op
            testRoom.setAbsenceDeadline("Player2", null);
            p2.setDisconnectDeadlineEpochMs(futureDeadline);
            RoomGameSnapshot sFuture = snapshotRoomAndGame(testRoom, game);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.removeDisconnectedPlayer(
                    ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), futureDeadline));
            assertNoOpDisconnect(sFuture, testRoom, game);

            // Not disconnected -> no-op
            p2.setDisconnected(false);
            p2.setDisconnectDeadlineEpochMs(pastDeadline);
            RoomGameSnapshot sConnected = snapshotRoomAndGame(testRoom, game);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.removeDisconnectedPlayer(
                    ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), pastDeadline));
            assertNoOpDisconnect(sConnected, testRoom, game);

            // Room-only spectator connected -> no-op even if a deadline is present
            testRoom.addPlayer("Spectator");
            testRoom.setPlayerConnected("Spectator", true);
            testRoom.setAbsenceDeadline("Spectator", pastDeadline);
            RoomGameSnapshot sSpecConn = snapshotRoomAndGame(testRoom, game);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.removeDisconnectedPlayer(
                    ROOM_ID, "Spectator", testRoom.getMembershipId("Spectator"), pastDeadline));
            assertNoOpDisconnect(sSpecConn, testRoom, game);

            // Room-only spectator expired -> no-op after game over, stays in room until cleanup
            testRoom.setPlayerConnected("Spectator", false);
            testRoom.setAbsenceDeadline("Spectator", pastDeadline);
            RoomGameSnapshot sSpecExpired = snapshotRoomAndGame(testRoom, game);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.removeDisconnectedPlayer(
                    ROOM_ID, "Spectator", testRoom.getMembershipId("Spectator"), pastDeadline));
            assertNoOpDisconnect(sSpecExpired, testRoom, game);
            assertTrue(testRoom.hasPlayer("Spectator"));

            // Expired game-player deadline -> no-op after game over, retains room member and frozen roster
            p2.setDisconnected(true);
            p2.setDisconnectDeadlineEpochMs(pastDeadline);
            RoomGameSnapshot sP2Expired = snapshotRoomAndGame(testRoom, game);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.removeDisconnectedPlayer(
                    ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), pastDeadline));
            assertNoOpDisconnect(sP2Expired, testRoom, game);
            assertTrue(testRoom.hasPlayer("Player2"));
            assertTrue(game.getPlayers().stream().anyMatch(p -> p.getName().equals("Player2")));

            // Room absence deadline fallback when game player deadline is null -> no-op after game over
            p2.setDisconnectDeadlineEpochMs(null);
            testRoom.setAbsenceDeadline("Player2", pastDeadline);
            RoomGameSnapshot sFallback = snapshotRoomAndGame(testRoom, game);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.removeDisconnectedPlayer(
                    ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), pastDeadline));
            assertNoOpDisconnect(sFallback, testRoom, game);
            assertTrue(testRoom.hasPlayer("Player2"));
        }

        @Test
        void removeDisconnectedPlayer_SettledGameDecided_FinalizesGame() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));
            Game game = getGameLocked(ROOM_ID);
            Player busted = game.getPlayers().get(0);
            busted.setIsOut();
            game.conductShowdown();
            assertTrue(game.getLastSettlementResult().settled());
            assertTrue(game.isGameDecided());
            assertFalse(game.isGameOver());
            long deadline = BASE_EPOCH_MS - 100;
            busted.setDisconnected(true);
            busted.setDisconnectDeadlineEpochMs(deadline);

            withRoomLock(ROOM_ID, () -> gameLifecycleService.removeDisconnectedPlayer(
                    ROOM_ID, busted.getName(), testRoom.getMembershipId(busted.getName()), deadline));

            verify(roomService).leavePlayersBatch(eq(ROOM_ID), eq(List.of(busted.getName())), any());
            assertTrue(game.isGameOver());
        }

        @Test
        void removeDisconnectedPlayer_PreFlopAbandonedDuringCleanupWindow_NoOpsAndKeepsRoomMembers() {
            HandEvaluatorService realEvaluator = new HandEvaluatorService();
            lenient().when(handEvaluator.getBestHand(anyList(), anyList()))
                    .thenAnswer(inv -> realEvaluator.getBestHand(inv.getArgument(0), inv.getArgument(1)));
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            long disconnectDeadline = BASE_EPOCH_MS + 5_000L;

            withRoomLock(ROOM_ID, () -> {
                gameLifecycleService.createGameFromRoom(ROOM_ID);
                gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", disconnectDeadline);
            });

            long inactivityDeadline = testRoom.getInactivityDeadlineEpochMs();
            mutableClock.setMillis(inactivityDeadline);
            withRoomLock(ROOM_ID, () -> completionHandler.abandonGameIfInactive(ROOM_ID, inactivityDeadline));

            Game game = getGameLocked(ROOM_ID);
            assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
            assertTrue(game.getCommunityCards().isEmpty());
            assertTrue(game.isGameOver());
            assertTrue(game.getGameEndResult().abandoned());
            assertFalse(game.isFinalPotSettled());

            reset(gameStateService, roomService);
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);

            withRoomLock(ROOM_ID, () -> gameLifecycleService.removeDisconnectedPlayer(
                    ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), disconnectDeadline));

            verifyNobodyRemovedFromRoom();
            assertTrue(testRoom.hasPlayer("Player2"));
            verify(handEvaluator, never()).getBestHand(anyList(), anyList());
            verifyNoInteractions(gameStateService);
            assertFalse(game.isFinalPotSettled());
            assertTrue(game.getGameEndResult().abandoned());
        }
    }

    // ==================== Concurrency Tests ====================

    @Nested
    @DisplayName("Concurrency")
    class ConcurrencyTests {

        @Test
        void removePlayers_ConcurrentWithPlayerAction_NonCurrentDisconnect_PreservesTurnIndex()
                throws Exception {
            PlayerActionService playerActionService = new PlayerActionService(
                    gameLifecycleService, gameStateService, mutableClock);
            ConcurrentHashMap<String, Room> roomsById = new ConcurrentHashMap<>();
            when(roomService.getRoom(anyString())).thenAnswer(inv -> roomsById.get(inv.<String>getArgument(0)));

            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                for (int i = 0; i < 20; i++) {
                    String roomId = "race-room-" + i;
                    String host = "Host-" + i;
                    String p2 = "P2-" + i;
                    String p3 = "P3-" + i;

                    Room room = new Room(roomId, "Race Room " + i, host, 6, 5, 10, 100, null);
                    room.addPlayer(host);
                    room.addPlayer(p2);
                    room.addPlayer(p3);
                    roomsById.put(roomId, room);

                    withRoomLock(roomId, () -> gameLifecycleService.createGameFromRoom(roomId));
                    Game game = getGameLocked(roomId);
                    Player currentPlayer = game.getCurrentPlayer();
                    Player leavingPlayer = game.getPlayers().stream()
                            .filter(player -> !player.equals(currentPlayer))
                            .findFirst()
                            .orElseThrow();

                    CyclicBarrier barrier = new CyclicBarrier(2);
                    Future<?> fold = executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        withRoomLock(roomId, () -> playerActionService.processPlayerAction(
                                roomId, new PlayerActionRequest(PlayerAction.FOLD, null), currentPlayer.getName()));
                        return null;
                    });
                    Future<?> leave = executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        withRoomLock(roomId, () ->
                                gameLifecycleService.removePlayers(roomId, List.of(leavingPlayer.getName())));
                        return null;
                    });
                    fold.get(5, TimeUnit.SECONDS);
                    leave.get(5, TimeUnit.SECONDS);

                    Game finalGame = getGameLocked(roomId);
                    assertNotNull(finalGame);
                    assertEquals(2, finalGame.getPlayers().size());
                    assertDoesNotThrow(finalGame::getCurrentPlayer);
                }
            }
        }

        @Test
        void concurrentDeparturesRemoveBothNonActingPlayers() throws Exception {
            testRoom.addPlayer("Player3");
            testRoom.addPlayer("Player4");
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));

            Game game = getGameLocked(ROOM_ID);
            List<Player> departing = game.getPlayers().stream()
                    .filter(player -> player != game.getCurrentPlayer())
                    .limit(2)
                    .toList();
            List<Player> survivors = game.getPlayers().stream()
                    .filter(player -> !departing.contains(player))
                    .toList();

            CyclicBarrier barrier = new CyclicBarrier(departing.size());
            try (ExecutorService executor = Executors.newFixedThreadPool(departing.size())) {
                List<Future<?>> futures = new ArrayList<>();
                for (Player player : departing) {
                    futures.add(executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        withRoomLock(ROOM_ID, () ->
                                gameLifecycleService.removePlayers(ROOM_ID, List.of(player.getName())));
                        return null;
                    }));
                }
                for (Future<?> future : futures) {
                    future.get(5, TimeUnit.SECONDS);
                }
                assertEquals(survivors, getGameLocked(ROOM_ID).getPlayers());
            }
        }

        @Test
        void removePlayers_DuringGameEnd_LinearizesSingleSurvivorAndPublishesOnce() throws Exception {
            testRoom.addPlayer("Player3");
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            reset(gameStateService);
            stubGameEndCapture();
            withRoomLock(ROOM_ID, () -> gameLifecycleService.createGameFromRoom(ROOM_ID));

            Game game = getGameLocked(ROOM_ID);
            Player p1 = game.getPlayers().get(0);
            Player p2 = game.getPlayers().get(1);
            Player survivor = game.getPlayers().stream()
                    .filter(p -> !p.equals(p1) && !p.equals(p2))
                    .findFirst()
                    .orElseThrow();

            CyclicBarrier barrier = new CyclicBarrier(2);
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                List<Future<?>> futures = new ArrayList<>();
                for (Player leaver : List.of(p1, p2)) {
                    futures.add(executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        withRoomLock(ROOM_ID, () ->
                                gameLifecycleService.removePlayers(ROOM_ID, List.of(leaver.getName())));
                        return null;
                    }));
                }
                for (Future<?> future : futures) {
                    future.get(5, TimeUnit.SECONDS);
                }
            }

            Game finalGame = getGameLocked(ROOM_ID);
            assertNotNull(finalGame);
            assertTrue(finalGame.isGameOver());
            List<Player> stillActive = finalGame.getPlayers().stream().filter(p -> !p.getIsOut()).toList();
            assertEquals(1, stillActive.size());
            assertEquals(survivor.getName(), stillActive.getFirst().getName());
            assertNotNull(finalGame.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP));
            verify(gameStateService, times(1)).sendGameEnd(any(GameEndResponse.class));
        }

        @Test
        void gameCompletion_ConcurrentWithPlayerLeave_DoesNotDeadlock() throws Exception {
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                for (int i = 0; i < 20; i++) {
                    String roomId = "deadlock-test-" + i;
                    Room room = new Room(roomId, "Deadlock Room", "Host", 6, 10, 20, 1000, null);
                    room.addPlayer("Host");
                    room.addPlayer("Player2");
                    room.setGameStarted(true);

                    Game game = new Game(roomId, List.of(
                            new Player("Host", "p1", 1000),
                            new Player("Player2", "p2", 1000)), 6, 10, 20, handEvaluator);
                    game.dealHoleCards();
                    game.postBlinds();

                    when(roomService.getRoom(roomId)).thenReturn(room);
                    withRoomLock(roomId, () -> gameLifecycleService.restoreGame(game));

                    CyclicBarrier barrier = new CyclicBarrier(2);
                    Future<?> endFuture = executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        try (LockLease _ = roomCoordinator.acquire(roomId)) {
                            completionHandler.handleGameEnd(roomId);
                        }
                        return null;
                    });
                    Future<?> leaveFuture = executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        withRoomLock(roomId, () -> gameLifecycleService.removePlayers(roomId, List.of("Player2")));
                        return null;
                    });

                    endFuture.get(5, TimeUnit.SECONDS);
                    leaveFuture.get(5, TimeUnit.SECONDS);

                    Game endedGame = getGameLocked(roomId);
                    assertNotNull(endedGame);
                    assertTrue(endedGame.isGameOver());
                    assertTrue(room.isTerminal());
                }
            }
        }

        @Test
        void raceStartWithGuestOrHostLeave_AndConcurrentHostSuccessorDepartures() throws Exception {
            Map<String, Room> activeRooms = new ConcurrentHashMap<>();
            lenient().when(roomService.getRoom(anyString())).thenAnswer(inv -> activeRooms.get(inv.<String>getArgument(0)));
            lenient().when(roomService.getCurrentHost(anyString())).thenAnswer(inv -> {
                Room r = activeRooms.get(inv.<String>getArgument(0));
                return (r == null || r.getPlayers().isEmpty()) ? null : r.getPlayers().getFirst();
            });
            lenient().doAnswer(inv -> {
                String rid = inv.getArgument(0);
                List<String> toLeave = inv.getArgument(1);
                Room r = activeRooms.get(rid);
                if (r != null && toLeave != null) {
                    toLeave.forEach(p -> {
                        if (r.hasPlayer(p)) {
                            r.removePlayer(p);
                        }
                    });
                }
                return null;
            }).when(roomService).leavePlayersBatch(anyString(), any(), any());
            lenient().doAnswer(inv -> {
                String rid = inv.getArgument(0);
                activeRooms.remove(rid);
                return null;
            }).when(roomService).leaveRoom(anyString(), anyString());

            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                // Race start vs guest leave
                for (int i = 0; i < 30; i++) {
                    String roomId = "race-guest-" + i;
                    Room room = new Room(roomId, "Race Room " + i, "Host", 6, 10, 20, 1000, null);
                    room.addPlayer("Host");
                    room.addPlayer("Guest");
                    activeRooms.put(roomId, room);

                    CyclicBarrier barrier = new CyclicBarrier(2);
                    Future<Throwable> startResult = executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        try {
                            withRoomLock(roomId, () -> gameLifecycleService.createGameFromRoom(roomId));
                            return null;
                        } catch (Throwable t) {
                            return t;
                        }
                    });
                    Future<?> leaveResult = executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        withRoomLock(roomId, () -> gameLifecycleService.removePlayers(roomId, List.of("Guest")));
                        return null;
                    });
                    Throwable startError = startResult.get(5, TimeUnit.SECONDS);
                    leaveResult.get(5, TimeUnit.SECONDS);

                    Game game = getGameLocked(roomId);
                    if (game != null) {
                        assertFalse(withRoomLock(roomId, () -> gameLifecycleService.playerExistsInGame(roomId, "Guest")));
                        assertFalse(room.hasPlayer("Guest"));
                    } else {
                        assertNotNull(startError);
                        assertInstanceOf(UnauthorisedActionException.class, startError);
                    }
                }

                // Race start vs host leave
                for (int i = 0; i < 30; i++) {
                    String roomId = "race-host-" + i;
                    Room room = new Room(roomId, "Race Room " + i, "Host", 6, 10, 20, 1000, null);
                    room.addPlayer("Host");
                    room.addPlayer("Guest");
                    activeRooms.put(roomId, room);

                    CyclicBarrier barrier = new CyclicBarrier(2);
                    Future<Throwable> startResult = executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        try {
                            withRoomLock(roomId, () -> gameLifecycleService.createGameFromRoom(roomId));
                            return null;
                        } catch (Throwable t) {
                            return t;
                        }
                    });
                    Future<?> leaveResult = executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        withRoomLock(roomId, () -> gameLifecycleService.removePlayers(roomId, List.of("Host")));
                        return null;
                    });
                    Throwable startError = startResult.get(5, TimeUnit.SECONDS);
                    leaveResult.get(5, TimeUnit.SECONDS);

                    Game game = getGameLocked(roomId);
                    if (game == null) {
                        assertNotNull(startError);
                    }
                }

                // Concurrent host + successor departure
                for (int i = 0; i < 30; i++) {
                    String roomId = "depart-race-" + i;
                    Room room = new Room(roomId, "Depart Room " + i, "Host", 6, 10, 20, 1000, null);
                    room.addPlayer("Host");
                    room.addPlayer("Successor");
                    room.addPlayer("Player3");
                    room.setGameStarted(true);
                    activeRooms.put(roomId, room);

                    Game game = new Game(roomId, List.of(
                            new Player("Host", "p1", 1000),
                            new Player("Successor", "p2", 1000),
                            new Player("Player3", "p3", 1000)), 6, 10, 20, handEvaluator);
                    game.dealHoleCards();
                    game.postBlinds();
                    withRoomLock(roomId, () -> gameLifecycleService.restoreGame(game));

                    CyclicBarrier barrier = new CyclicBarrier(2);
                    Future<?> hostLeave = executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        withRoomLock(roomId, () -> gameLifecycleService.removePlayers(roomId, List.of("Host")));
                        return null;
                    });
                    Future<?> succLeave = executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        withRoomLock(roomId, () -> gameLifecycleService.removePlayers(roomId, List.of("Successor")));
                        return null;
                    });
                    hostLeave.get(5, TimeUnit.SECONDS);
                    succLeave.get(5, TimeUnit.SECONDS);
                }
            }
        }
    }
}
