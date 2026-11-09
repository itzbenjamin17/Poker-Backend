package com.pokergame.service;

import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.dto.response.ApiResponse;
import com.pokergame.dto.internal.PlayerJoinInfo;
import org.mockito.ArgumentCaptor;
import com.pokergame.exception.ConflictException;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.HandRank;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.model.Card;
import com.pokergame.model.Game;
import com.pokergame.model.HandEvaluationResult;
import com.pokergame.model.HandSettlementResult;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.snapshot.AggregateSnapshotMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.*;
import com.pokergame.dto.internal.PlayerDecision;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.EnumMap;
import java.util.stream.Collectors;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for GameParticipantDepartureHandler and participant departure lifecycle flows.
 */
@Tag("unit")
@DisplayName("Game participant departure handler")
@ExtendWith(MockitoExtension.class)
class GameParticipantDepartureHandlerTest {

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

    private GameLifecycleService gameLifecycleService;

    private Room testRoom;
    private static final String ROOM_ID = "test-room-id";

    /**
     * Initializes the test fixtures before each scenario.
     */
    @BeforeEach
    void setUp() {
        gameLifecycleService = new GameLifecycleService(roomService, handEvaluator, gameStateService, messagingTemplate,
                taskScheduler);
        lenient().when(handEvaluator.getBestHand(anyList(), anyList()))
                .thenReturn(new HandEvaluationResult(List.of(), HandRank.HIGH_CARD));

        testRoom = new Room(
                ROOM_ID,
                "Test Room",
                "Host",
                6,
                5,
                10,
                100,
                null);
        testRoom.addPlayer("Host");
        testRoom.addPlayer("Player2");
        stubGameEndCapture();
    }

    private void stubGameEndCapture() {
        Answer<GameEndResponse> buildResponse = invocation -> {
            String id = invocation.getArgument(0);
            Game game = invocation.getArgument(1);
            Player winner = invocation.getArgument(2);
            boolean forfeit = invocation.getArgument(3);
            int maxPlayers = invocation.getArgument(4);
            PublicGameStateResponse finalState = new PublicGameStateResponse(
                    maxPlayers, 0, List.of(), 0, GamePhase.SHOWDOWN, 0, List.of(),
                    game != null && game.getPlayers() != null ? game.getPlayers().stream().map(p -> new PublicPlayerState(
                            p.getPlayerId(), p.getName(), p.getChips(), 0, "status", false, false, false,
                            false, false, null, List.of(), p.equals(winner), null, null, null, false)).toList() : List.of(),
                    null, null, List.of(winner.getName()), null, false, null, false, null, false, null,
                    true, null, List.of(), null, null, 1, ReadyGateState.CLOSED, System.currentTimeMillis(),
                    game != null ? game.getStateRevision() : 1);
            return new GameEndResponse("GAME_END", id, winner.getName(), winner.getChips(), forfeit,
                    winner.getName() + " wins", finalState);
        };
        lenient().when(gameStateService.captureGameEnd(anyString(), any(Game.class), any(Player.class), anyBoolean(), anyInt()))
                .thenAnswer(buildResponse);
        lenient().when(gameStateService.projectGameEnd(anyString(), any(Game.class), any(Player.class), anyBoolean(), anyInt()))
                .thenAnswer(buildResponse);
    }

    // ==================== leaveGame Tests ====================

    /**
     * Repeated leave requests for a missing game are safe after an earlier successful departure.
     */
    @Test
    void leaveGame_WhenGameNotFound_IsIdempotent() {
        assertDoesNotThrow(() -> gameLifecycleService.departParticipants("nonexistent-id", List.of("Player")));
    }

    /**
     * Repeated leave requests for an already absent player are safe.
     */
    @Test
    void leaveGame_WhenPlayerNotFound_IsIdempotent() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        assertDoesNotThrow(() -> gameLifecycleService.departParticipants(ROOM_ID, List.of("NonexistentPlayer")));
    }

    /**
     * Protects the contract that leave game one player remains should end game.
     */
    @Test
    void leaveGame_OnePlayerRemains_ShouldEndGame() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);
        String playerToLeave = game.getPlayers().getFirst().getName();

        gameLifecycleService.departParticipants(ROOM_ID, List.of(playerToLeave));

        verify(gameStateService).sendGameEnd(any(GameEndResponse.class));
    }

    /**
     * Protects the contract that when all remaining players are disconnected, leaving destroys the game and room immediately.
     */
    @Test
    void leaveGame_WhenLastConnectedPlayerLeaves_PreservesDisconnectedPlayerGrace() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Host", System.currentTimeMillis() + 120_000);
        reset(gameStateService);
        stubGameEndCapture();

        gameLifecycleService.departParticipants(ROOM_ID, List.of("Player2"));

        assertNotNull(gameLifecycleService.getGame(ROOM_ID));
        assertTrue(gameLifecycleService.playerExistsInGame(ROOM_ID, "Host"));
        verify(roomService, never()).destroyRoom(ROOM_ID);
        verify(gameStateService, never()).sendGameEnd(any(GameEndResponse.class));
    }

    /**
     * Protects the contract that markPlayerReconnected does not broadcast live state if the game is already over.
     */
    @Test
    void markPlayerReconnected_WhenGameIsOver_ShouldNotBroadcastGameState() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);
        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Host", System.currentTimeMillis() + 120_000);
        game.markGameOver();
        reset(gameStateService);
        stubGameEndCapture();

        gameLifecycleService.markPlayerReconnected(ROOM_ID, "Host");

        verify(gameStateService, never()).broadcastGameState(anyString(), any(Game.class));
    }

    /**
     * Protects the contract that leave game with multiple players should continue game.
     */
    @Test
    void leaveGame_WithMultiplePlayers_ShouldContinueGame() {
        testRoom.addPlayer("Player3");
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        reset(gameStateService);
        stubGameEndCapture();

        Game game = gameLifecycleService.getGame(ROOM_ID);
        // Find a player who is not the current player
        Player currentPlayer = game.getCurrentPlayer();
        Player playerToLeave = game.getPlayers().stream()
                .filter(p -> !p.equals(currentPlayer))
                .findFirst()
                .orElseThrow();

        gameLifecycleService.departParticipants(ROOM_ID, List.of(playerToLeave.getName()));

        assertEquals(2, gameLifecycleService.getGame(ROOM_ID).getPlayers().size());
        verify(gameStateService).broadcastGameState(eq(ROOM_ID), any(Game.class));
    }

    /**
     * Protects the contract that leave game non current player leaves with current at tail should not throw or corrupt turn index.
     */
    @Test
    void leaveGame_NonCurrentPlayerLeavesWithCurrentAtTail_ShouldNotThrowOrCorruptTurnIndex() {
        testRoom.addPlayer("Player3");
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        reset(gameStateService);
        stubGameEndCapture();

        Game game = gameLifecycleService.getGame(ROOM_ID);

        // Move turn to tail seat so removing an earlier seat used to leave a stale
        // index.
        game.nextPlayer();
        game.nextPlayer();
        Player currentPlayer = game.getCurrentPlayer();

        Player playerToLeave = game.getPlayers().stream()
                .filter(p -> !p.equals(currentPlayer))
                .findFirst()
                .orElseThrow();

        assertDoesNotThrow(() -> gameLifecycleService.departParticipants(ROOM_ID, List.of(playerToLeave.getName())));
        assertNotNull(game.getCurrentPlayer());
        verify(gameStateService).broadcastGameState(eq(ROOM_ID), any(Game.class));
    }

    /**
     * Protects the contract that leave game when room missing should cleanup game without broadcast.
     */
    @Test
    void leaveGame_WhenRoomMissing_ShouldCleanupGameWithoutBroadcast() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        reset(gameStateService);
        stubGameEndCapture();

        Game game = gameLifecycleService.getGame(ROOM_ID);
        String playerToLeave = game.getPlayers().getFirst().getName();

        when(roomService.getRoom(ROOM_ID)).thenReturn(null);

        assertDoesNotThrow(() -> gameLifecycleService.departParticipants(ROOM_ID, List.of(playerToLeave)));

        assertNull(gameLifecycleService.getGame(ROOM_ID));
        verify(gameStateService, never()).broadcastGameState(anyString(), any(Game.class));
    }

    /**
     * Protects the contract that leave game when room destroyed mid flow should cleanup game without broadcast.
     */
    @Test
    void leaveGame_WhenRoomDestroyedMidFlow_ShouldCleanupGameWithoutBroadcast() {
        testRoom.addPlayer("Player3");
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom, testRoom, null);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        reset(gameStateService);
        stubGameEndCapture();

        Game game = gameLifecycleService.getGame(ROOM_ID);
        Player currentPlayer = game.getCurrentPlayer();
        Player playerToLeave = game.getPlayers().stream()
                .filter(p -> !p.equals(currentPlayer))
                .findFirst()
                .orElseThrow();

        assertDoesNotThrow(() -> gameLifecycleService.departParticipants(ROOM_ID, List.of(playerToLeave.getName())));

        assertNull(gameLifecycleService.getGame(ROOM_ID));
        verify(gameStateService, never()).broadcastGameState(anyString(), any(Game.class));
    }


    /**
     * Protects the contract that mark player disconnected when player exists should set disconnected and broadcast.
     */
    @Test
    void markPlayerDisconnected_WhenPlayerExists_ShouldSetDisconnectedAndBroadcast() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        long disconnectDeadlineEpochMs = System.currentTimeMillis() + 120_000;

        reset(gameStateService);
        stubGameEndCapture();

        assertDoesNotThrow(
                () -> gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", disconnectDeadlineEpochMs));

        Game game = gameLifecycleService.getGame(ROOM_ID);
        Player player2 = game.getPlayers().stream().filter(p -> p.getName().equals("Player2")).findFirst()
                .orElseThrow();
        assertTrue(player2.getIsDisconnected());
        assertEquals(disconnectDeadlineEpochMs, player2.getDisconnectDeadlineEpochMs());
        verify(gameStateService).broadcastGameState(eq(ROOM_ID), any(Game.class));
    }

    /**
     * Protects the contract that mark player reconnected when player exists should clear disconnected and broadcast.
     */
    @Test
    void markPlayerReconnected_WhenPlayerExists_ShouldClearDisconnectedAndBroadcast() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", System.currentTimeMillis() + 120_000);
        reset(gameStateService);
        stubGameEndCapture();

        assertDoesNotThrow(() -> gameLifecycleService.markPlayerReconnected(ROOM_ID, "Player2"));

        Game game = gameLifecycleService.getGame(ROOM_ID);
        Player player2 = game.getPlayers().stream().filter(p -> p.getName().equals("Player2")).findFirst()
                .orElseThrow();
        assertFalse(player2.getIsDisconnected());
        assertNull(player2.getDisconnectDeadlineEpochMs());
        verify(gameStateService).broadcastGameState(eq(ROOM_ID), any(Game.class));
    }

    /**
     * Protects the contract that claim win when all other non out players disconnected should end game for claimant.
     */
    @Test
    void claimWin_WhenAllOtherNonOutPlayersDisconnected_ShouldEndGameForClaimant() {
        testRoom.addPlayer("Player3");
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", System.currentTimeMillis() + 120_000);
        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player3", System.currentTimeMillis() + 120_000);

        reset(gameStateService);
        stubGameEndCapture();

        assertDoesNotThrow(() -> gameLifecycleService.claimWin(ROOM_ID, "Host"));

        verify(gameStateService).sendGameEnd(argThat(event -> event != null && event.winner().equals("Host") && event.isForfeit()));
        verify(roomService, times(1)).leavePlayersBatch(eq(ROOM_ID), eq(List.of("Player2", "Player3")), any());
        verify(roomService, never()).leaveRoom(anyString(), anyString());
        verify(gameStateService).captureGameEnd(eq(ROOM_ID), argThat(game ->
                game.getPlayers().stream().map(Player::getName).collect(Collectors.toSet())
                        .equals(Set.of("Host", "Player2", "Player3"))
                        && game.getPlayers().stream().filter(player -> player.getName().startsWith("Player"))
                        .allMatch(Player::getIsOut)), any(Player.class), eq(true), anyInt());
        assertNotNull(gameLifecycleService.getGame(ROOM_ID).getScheduledTaskDeadline(ScheduledGameTask.CLEANUP));
    }

    @Test
    void leavePlayer_ConnectedZeroChipUnsettledContenderCanRemainTournamentWinner() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        Player zeroChipSurvivor = game.getPlayers().stream().filter(player -> player.getName().equals("Host"))
                .findFirst().orElseThrow();
        zeroChipSurvivor.setChips(0);
        reset(gameStateService);
        stubGameEndCapture();

        gameLifecycleService.departParticipants(ROOM_ID, List.of("Player2"));

        verify(gameStateService).sendGameEnd(argThat(event -> event != null && event.winner().equals("Host")));
        assertTrue(game.isGameOver());
        assertTrue(game.getPlayers().stream().anyMatch(player -> player.getName().equals("Player2")),
                "Terminal final standings retain the departed participant");
    }

    @Test
    void departParticipants_LobbyHostLeaves_DestroysRoom() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        when(roomService.getCurrentHost(ROOM_ID)).thenReturn("Host");

        gameLifecycleService.departParticipants(ROOM_ID, List.of("Host"));

        verify(roomService).leaveRoom(ROOM_ID, "Host");
        verify(roomService, never()).leavePlayersBatch(anyString(), anyList(), any());
    }

    @Test
    void departParticipants_ActiveGameHostLeaves_TransfersHostToRemainingPlayer() {
        testRoom.addPlayer("Player3");
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        gameLifecycleService.departParticipants(ROOM_ID, List.of("Host"));

        verify(roomService).leavePlayersBatch(ROOM_ID, List.of("Host"), "Player2");
        Game game = gameLifecycleService.getGame(ROOM_ID);
        assertNotNull(game);
        assertFalse(game.getPlayers().stream().anyMatch(p -> p.getName().equals("Host")));
        assertEquals(2, game.getPlayers().size());
    }

    @Test
    void departParticipants_BatchDeparture_ContendersRemaining_SingleRoomUpdate() {
        testRoom.addPlayer("Player3");
        testRoom.addPlayer("Player4");
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        reset(roomService);
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);

        gameLifecycleService.departParticipants(ROOM_ID, List.of("Player3", "Player4"));

        verify(roomService, times(1)).leavePlayersBatch(ROOM_ID, List.of("Player3", "Player4"), "Host");
        Game game = gameLifecycleService.getGame(ROOM_ID);
        assertNotNull(game);
        assertEquals(2, game.getPlayers().size());
        assertFalse(game.isGameOver());
    }

    @Test
    void departParticipants_TerminalStateFrozen_RosterAndStandingsImmutable() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        game.markGameOver();
        reset(roomService);
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);

        gameLifecycleService.departParticipants(ROOM_ID, List.of("Player2"));

        verify(roomService).leavePlayersBatch(ROOM_ID, List.of("Player2"), "Host");
        // Game roster remains intact and frozen
        assertTrue(game.getPlayers().stream().anyMatch(p -> p.getName().equals("Player2")));
    }

    @Test
    void pendingForfeitSurvivorExpiryCleansSettledShowdownRoomGameAndTasks() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
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
        when(roomService.getRoom(ROOM_ID)).thenAnswer(invocation -> roomPresent.get() == 1 ? testRoom : null);
        doAnswer(invocation -> {
            List<String> leaving = invocation.getArgument(1);
            leaving.forEach(testRoom::removePlayer);
            if (testRoom.getPlayers().isEmpty()) roomPresent.set(0);
            return null;
        }).when(roomService).leavePlayersBatch(eq(ROOM_ID), anyList(), any());

        long p2Deadline = System.currentTimeMillis() - 100;
        survivor.setDisconnectDeadlineEpochMs(p2Deadline);
        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, survivor.getName(), testRoom.getMembershipId(survivor.getName()), p2Deadline);

        assertEquals(0, roomPresent.get(), "The emptied Room is gone");
        assertTrue(testRoom.getPlayers().isEmpty());
        assertFalse(gameLifecycleService.gameExists(ROOM_ID), "Retained OUT roster rows do not keep an empty Game alive");
        assertTrue(game.getScheduledTaskDeadlinesSnapshot().isEmpty(), "Abandonment removes all durable timer intent");
    }

    @Test
    void pendingForfeitRetriesPreserveOutRowsAndSpectatorDepartureDoesNotAdvanceGame() {
        testRoom.addPlayer("Spectator");
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        Player spectator = game.getPlayers().stream().filter(p -> p.getName().equals("Spectator")).findFirst().orElseThrow();
        spectator.setChips(0);
        spectator.setIsOut();
        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", System.currentTimeMillis() + 60_000);

        AtomicInteger roomPresent = new AtomicInteger(1);
        reset(roomService);
        when(roomService.getRoom(ROOM_ID)).thenAnswer(invocation -> roomPresent.get() == 1 ? testRoom : null);
        doAnswer(invocation -> {
            List<String> leaving = invocation.getArgument(1);
            leaving.forEach(testRoom::removePlayer);
            if (testRoom.getPlayers().isEmpty()) roomPresent.set(0);
            return null;
        }).when(roomService).leavePlayersBatch(eq(ROOM_ID), anyList(), any());

        gameLifecycleService.departParticipants(ROOM_ID, List.of("Host"));
        assertTrue(game.isForfeitPending());
        gameLifecycleService.departParticipants(ROOM_ID, List.of("Host"));
        gameLifecycleService.departParticipants(ROOM_ID, List.of("Spectator"));
        assertTrue(game.getPlayers().stream().anyMatch(p -> p.getName().equals("Host") && p.getIsOut()),
                "A duplicate leave cannot remove retained OUT standings rows");
        assertTrue(game.getPlayers().stream().anyMatch(p -> p.getName().equals("Spectator") && p.getIsOut()),
                "A busted spectator remains in the final roster after leaving Room membership");
        assertTrue(game.isForfeitPending(), "A spectator departure cannot resolve or progress the pending forfeit");
        assertTrue(gameLifecycleService.gameExists(ROOM_ID));

        long p2ForfeitDeadline = System.currentTimeMillis() - 100;
        game.getPlayers().stream().filter(p -> p.getName().equals("Player2")).findFirst().orElseThrow().setDisconnectDeadlineEpochMs(p2ForfeitDeadline);
        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), p2ForfeitDeadline);
        assertEquals(0, roomPresent.get(), "No-contender abandonment closes the whole leftover Room");
        assertFalse(gameLifecycleService.gameExists(ROOM_ID));
        assertTrue(game.getScheduledTaskDeadlinesSnapshot().isEmpty());
    }

    @Test
    void pendingForfeitInvalidatesCapturedProgressionCallbacksAndReconnectSettlesOnce() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        Player departing = game.getPlayers().stream().filter(player -> player.getName().equals("Host")).findFirst().orElseThrow();
        Player survivor = game.getPlayers().stream().filter(player -> player.getName().equals("Player2")).findFirst().orElseThrow();
        gameLifecycleService.markPlayerDisconnected(ROOM_ID, survivor.getName(), System.currentTimeMillis() + 60_000);

        Map<ScheduledGameTask, Long> deadlines = new EnumMap<>(ScheduledGameTask.class);
        Map<ScheduledGameTask, Long> generations = new EnumMap<>(ScheduledGameTask.class);
        long deadline = System.currentTimeMillis() + 30_000;
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
        gameLifecycleService.departParticipants(ROOM_ID, List.of(departing.getName()));
        assertTrue(game.isForfeitPending());
        assertTrue(departing.getIsOut());
        assertFalse(testRoom.hasPlayer(departing.getName()));

        reset(gameStateService);
        stubGameEndCapture();
        for (ScheduledGameTask task : deadlines.keySet()) {
            gameLifecycleService.executeScheduledTask(ROOM_ID, task, deadlines.get(task), handBefore, generations.get(task));
        }
        assertEquals(handBefore, game.getHandNumber(), "Captured callbacks cannot deal a new hand");
        assertEquals(potBefore, game.getPot(), "Captured callbacks cannot advance or settle the pending hand");
        assertFalse(game.isGameOver(), "Captured GAME_END cannot finalize the pending forfeit");
        assertTrue(game.isForfeitPending());
        verify(gameStateService, never()).sendGameEnd(any(GameEndResponse.class));
        verify(gameStateService, never()).broadcastGameState(eq(ROOM_ID), any(Game.class));

        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build(), handEvaluator);
        Game restored = mapper.deserialize(mapper.serialize(testRoom, survivor.getName(), game)).game();
        assertTrue(restored.isForfeitPending());
        assertTrue(restored.getPlayers().stream().anyMatch(player -> player.getName().equals(departing.getName()) && player.getIsOut()));
        assertFalse(mapper.deserialize(mapper.serialize(testRoom, survivor.getName(), game)).room().hasPlayer(departing.getName()));
        gameLifecycleService.restoreGame(restored);
        Player restoredSurvivor = restored.getPlayers().stream()
                .filter(player -> player.getName().equals(survivor.getName())).findFirst().orElseThrow();

        gameLifecycleService.markPlayerReconnected(ROOM_ID, restoredSurvivor.getName());
        assertTrue(restored.isGameOver());
        assertFalse(restored.isForfeitPending());
        assertEquals(departingStackBefore, restored.getPlayers().stream()
                .filter(player -> player.getName().equals(departing.getName())).findFirst().orElseThrow().getChips(),
                "The forfeiting player's uncommitted stack is retained");
        assertEquals(110, restoredSurvivor.getChips(), "The recovered survivor receives the committed pot once");
        verify(gameStateService, times(1)).sendGameEnd(any(GameEndResponse.class));

        gameLifecycleService.markPlayerReconnected(ROOM_ID, restoredSurvivor.getName());
        verify(gameStateService, times(1)).sendGameEnd(any(GameEndResponse.class));
        assertEquals(110, restoredSurvivor.getChips(), "A repeated reconnect cannot pay out a second time");
    }

    @Test
    void departParticipants_IdempotentOnAbsentOrNullPlayers() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        reset(roomService);
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);

        assertDoesNotThrow(() -> gameLifecycleService.departParticipants(ROOM_ID, List.of("NonExistentPlayer")));
        assertDoesNotThrow(() -> gameLifecycleService.departParticipants(ROOM_ID, null));
        assertDoesNotThrow(() -> gameLifecycleService.departParticipants(ROOM_ID, List.of()));
        assertDoesNotThrow(() -> gameLifecycleService.departParticipants("missing-room", List.of("Player2")));

        verify(roomService, never()).leavePlayersBatch(anyString(), anyList(), any());
        verify(roomService, never()).leaveRoom(anyString(), anyString());
    }

    /**
     * Protects the contract that claim win when any other non out player connected should reject claim.
     */
    @Test
    void claimWin_WhenAnyOtherNonOutPlayerConnected_ShouldRejectClaim() {
        testRoom.addPlayer("Player3");
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", System.currentTimeMillis() + 120_000);

        UnauthorisedActionException exception = assertThrows(
                UnauthorisedActionException.class,
                () -> gameLifecycleService.claimWin(ROOM_ID, "Host"));

        assertTrue(exception.getMessage().toLowerCase().contains("claim"));
    }

    // ==================== handleGameEnd Tests ====================


    @Test
    void leaveClaimAndGraceExpirySettleCommittedLargePotOnceThroughFinalDto() {
        for (String ending : List.of("leave", "claim", "grace")) {
            String gameId = ROOM_ID + "-" + ending;
            Room room = new Room(gameId, "Terminal " + ending, "Host", 2, 10, 20, 1000, null);
            room.addPlayer("Host");
            room.addPlayer("Player2");
            when(roomService.getRoom(gameId)).thenReturn(room);
            reset(messagingTemplate);
            GameStateService realState = new GameStateService(roomService, messagingTemplate);
            GameLifecycleService lifecycle = new GameLifecycleService(roomService, handEvaluator, realState,
                    messagingTemplate, taskScheduler);
            lifecycle.createGameFromRoom(gameId);
            Game game = lifecycle.getGame(gameId);
            Player smallBlind = game.getPlayers().stream()
                    .filter(player -> player.getPlayerId().equals(game.getSmallBlindPlayerId())).findFirst().orElseThrow();
            Player bigBlind = game.getPlayers().stream()
                    .filter(player -> player.getPlayerId().equals(game.getBigBlindPlayerId())).findFirst().orElseThrow();
            lenient().when(handEvaluator.getBestHand(anyList(), anyList())).thenAnswer(invocation -> {
                List<Card> holeCards = invocation.getArgument(1);
                HandRank rank = holeCards.equals(smallBlind.getHoleCards()) ? HandRank.ROYAL_FLUSH : HandRank.HIGH_CARD;
                return new HandEvaluationResult(List.of(), rank);
            });

            Player actor = game.getCurrentPlayer();
            if (!actor.equals(smallBlind)) {
                game.processPlayerDecision(actor, new PlayerDecision(
                        actor.getCurrentBet() < game.getCurrentHighestBet() ? PlayerAction.CALL : PlayerAction.CHECK,
                        0, actor.getPlayerId()));
            }
            PlayerAction aggressiveAction = game.getCurrentPhase() == GamePhase.PRE_FLOP
                    ? PlayerAction.RAISE : PlayerAction.BET;
            game.processPlayerDecision(smallBlind, new PlayerDecision(
                    aggressiveAction, 500, smallBlind.getPlayerId()));
            game.processPlayerDecision(bigBlind, new PlayerDecision(
                    PlayerAction.CALL, 0, bigBlind.getPlayerId()));
            assertEquals(1020, game.getPot(), "The actual calls and raise build the contested pot");

            if (!ending.equals("leave")) {
                lifecycle.markPlayerDisconnected(gameId, smallBlind.getName(), System.currentTimeMillis() + 60_000);
            }
            if (ending.equals("leave")) {
                lifecycle.departParticipants(gameId, List.of(smallBlind.getName()));
            } else if (ending.equals("claim")) {
                lifecycle.claimWin(gameId, bigBlind.getName());
            } else {
                long sbDeadline = System.currentTimeMillis() - 100;
                smallBlind.setDisconnectDeadlineEpochMs(sbDeadline);
                lifecycle.removeDisconnectedPlayer(gameId, smallBlind.getName(), room.getMembershipId(smallBlind.getName()), sbDeadline);
            }

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/game/" + gameId), captor.capture());
            GameEndResponse result = captor.getAllValues().stream().filter(GameEndResponse.class::isInstance)
                    .map(GameEndResponse.class::cast).findFirst().orElseThrow();
            assertEquals(1510, result.winnerChips(), "The remaining BB receives committed pot chips even though the leaver held the stronger hand: " + ending);
            assertEquals(0, result.finalState().pot());
            assertEquals(1510, result.finalState().players().stream().filter(PublicPlayerState::isWinner)
                    .findFirst().orElseThrow().chips());
            lifecycle.handleGameEnd(gameId, false);
            assertEquals(1510, bigBlind.getChips(), "Delayed terminal handling cannot pay again: " + ending);
        }
    }


    /**
     * Protects the contract that leave game concurrent with player action non current disconnect should not throw or corrupt turn index.
     */
    @Test
    void leaveGame_ConcurrentWithPlayerAction_NonCurrentDisconnect_ShouldNotThrowOrCorruptTurnIndex()
            throws InterruptedException {
        final int iterations = 20;
        PlayerActionService playerActionService = new PlayerActionService(
                gameLifecycleService,
                gameStateService);

        ConcurrentHashMap<String, Room> roomsById = new ConcurrentHashMap<>();
        when(roomService.getRoom(anyString())).thenAnswer(invocation -> roomsById.get(invocation.getArgument(0)));

        for (int i = 0; i < iterations; i++) {
            String roomId = "race-room-" + i;
            String host = "Host-" + i;
            String p2 = "P2-" + i;
            String p3 = "P3-" + i;

            Room room = new Room(roomId, "Race Room " + i, host, 6, 5, 10, 100, null);
            room.addPlayer(host);
            room.addPlayer(p2);
            room.addPlayer(p3);
            roomsById.put(roomId, room);

            gameLifecycleService.createGameFromRoom(roomId);
            Game game = gameLifecycleService.getGame(roomId);

            Player currentPlayer = game.getCurrentPlayer();
            Player leavingPlayer = game.getPlayers().stream()
                    .filter(player -> !player.equals(currentPlayer))
                    .findFirst()
                    .orElseThrow();

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
            ExecutorService executor = Executors.newFixedThreadPool(2);

            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await(2, TimeUnit.SECONDS);
                    playerActionService.processPlayerAction(
                            roomId,
                            new PlayerActionRequest(PlayerAction.FOLD, null),
                            currentPlayer.getName());
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    done.countDown();
                }
            });

            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await(2, TimeUnit.SECONDS);
                    gameLifecycleService.departParticipants(roomId, List.of(leavingPlayer.getName()));
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    done.countDown();
                }
            });

            assertTrue(ready.await(2, TimeUnit.SECONDS), "Workers did not become ready in time");
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS), "Concurrent operations took too long");
            executor.shutdownNow();

            assertTrue(failures.isEmpty(), "Concurrent leave/action produced failures: " + failures);

            Game finalGame = gameLifecycleService.getGame(roomId);
            assertNotNull(finalGame, "Game should still exist after non-current leave");
            assertEquals(2, finalGame.getPlayers().size(), "Exactly one player should be removed from the game");
            assertDoesNotThrow(finalGame::getCurrentPlayer, "Current player lookup should remain index-safe");
        }
    }

    /**
     * Protects the contract that leave game multiple players concurrently should not corrupt game state.
     */
    @Test
    void leaveGame_MultiplePlayersConcurrently_ShouldNotCorruptGameState() throws InterruptedException {
        // Setup game with 4 players
        testRoom.addPlayer("Player3");
        testRoom.addPlayer("Player4");
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);
        assertEquals(4, game.getPlayers().size());

        // Get two players who are NOT the current player
        Player currentPlayer = game.getCurrentPlayer();
        List<Player> nonCurrentPlayers = game.getPlayers().stream()
                .filter(p -> !p.equals(currentPlayer))
                .limit(2)
                .toList();

        // Simulate 2 players leaving at the exact same time
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger successfulLeaves = new AtomicInteger(0);

        for (Player player : nonCurrentPlayers) {
            executor.submit(() -> {
                try {
                    gameLifecycleService.departParticipants(ROOM_ID, List.of(player.getName()));
                    successfulLeaves.incrementAndGet();
                } catch (Exception e) {
                    // May fail if player already removed by concurrent thread
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for both threads to complete
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Concurrent operations took too long");
        executor.shutdown();

        // Verify game state is still valid (not corrupted)
        Game finalGame = gameLifecycleService.getGame(ROOM_ID);
        assertNotNull(finalGame, "Game should still exist");
        assertTrue(finalGame.getPlayers().size() >= 2, "At least 2 players should remain");
        assertFalse(finalGame.getPlayers().isEmpty(), "Game should have players");
    }


    /**
     * Tests the edge case where game cleanup happens while players are leaving.
     * This simulates the scenario where async cleanup runs concurrently with player
     * actions.
     */
    @Test
    void leaveGame_DuringGameEnd_ShouldHandleGracefully() throws InterruptedException {
        testRoom.addPlayer("Player3");
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        reset(gameStateService);
        stubGameEndCapture();
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);
        Player player1 = game.getPlayers().get(0);
        Player player2 = game.getPlayers().get(1);
        // The sole survivor (the only member who does not leave) must own the terminal result.
        Player survivor = game.getPlayers().stream()
                .filter(p -> !p.equals(player1) && !p.equals(player2))
                .findFirst()
                .orElseThrow();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch latch = new CountDownLatch(2);
        ConcurrentLinkedQueue<Throwable> unexpectedFailures = new ConcurrentLinkedQueue<>();

        try {
            // Two of the three members leave at the same time, driving the game to its single-survivor
            // terminal state through whichever interleaving the scheduler picks.
            for (Player leaver : List.of(player1, player2)) {
                final String leaverName = leaver.getName();
                executor.submit(() -> {
                    try {
                        ready.countDown();
                        assertTrue(start.await(2, TimeUnit.SECONDS));
                        gameLifecycleService.departParticipants(ROOM_ID, List.of(leaverName));
                    } catch (Throwable t) {
                        // leaveGame is specified idempotent, so no interleaving may throw: capture any
                        // failure instead of swallowing it so the assertions below can surface it.
                        unexpectedFailures.add(t);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(ready.await(2, TimeUnit.SECONDS), "Workers did not become ready in time");
            start.countDown();
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Operations took too long");
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor must terminate promptly");
        }

        assertTrue(unexpectedFailures.isEmpty(), "Concurrent leaves produced failures: " + unexpectedFailures);

        // Regardless of interleaving, exactly one valid linearized terminal state must result.
        Game finalGame = gameLifecycleService.getGame(ROOM_ID);
        assertNotNull(finalGame, "The terminal aggregate must be retained after the game ends");
        assertTrue(finalGame.isGameOver(), "Two of three members leaving must terminate the game");
        // The terminal roster freezes the survivor as the only member still active (not out).
        List<Player> stillActive = finalGame.getPlayers().stream()
                .filter(p -> !p.getIsOut())
                .toList();
        assertEquals(1, stillActive.size(), "Exactly one survivor stays active in the terminal roster");
        assertEquals(survivor.getName(), stillActive.getFirst().getName(),
                "The member who never left must be the surviving winner");
        assertNotNull(finalGame.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP),
                "A terminal game schedules its cleanup task");
        // Game-end is published exactly once on the terminal path, no matter which leave closed it.
        verify(gameStateService, times(1)).sendGameEnd(any(GameEndResponse.class));
    }

    @Test
    @DisplayName("claimWin returns frozen terminal result and is idempotent without settlement re-execution or timer extension")
    void claimWin_IdempotenceAndNoExtension() {
        testRoom.addPlayer("Player3");
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", System.currentTimeMillis() + 120_000);
        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player3", System.currentTimeMillis() + 120_000);

        reset(gameStateService);
        stubGameEndCapture();

        GameEndResponse initialResult = gameLifecycleService.claimWin(ROOM_ID, "Host");
        assertNotNull(initialResult);
        assertEquals("Host", initialResult.winner());
        assertTrue(initialResult.isForfeit());

        Game game = gameLifecycleService.getGame(ROOM_ID);
        assertNotNull(game);
        assertTrue(game.isGameOver());
        assertSame(initialResult, game.getRetainedTerminalResult());
        Long initialCleanupDeadline = game.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP);
        assertNotNull(initialCleanupDeadline);

        reset(gameStateService);

        // Repeating claimWin
        GameEndResponse repeatedResult = gameLifecycleService.claimWin(ROOM_ID, "Host");
        assertSame(initialResult, repeatedResult);
        verify(gameStateService, never()).sendGameEnd(any());
        assertEquals(initialCleanupDeadline, game.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP));

        // Unauthorized player (not in terminal roster) cannot claimWin on finished game
        assertThrows(UnauthorisedActionException.class,
                () -> gameLifecycleService.claimWin(ROOM_ID, "Stranger"));
    }



    @Test
    @DisplayName("claimWin returns ApiResponse-compatible GameEndResponse with valid terminal state and winner")
    void claimWin_ReturnsGameEndResponseWithValidTerminalStateAndWinner() {
        testRoom.addPlayer("Player3");
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", System.currentTimeMillis() + 120_000);
        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player3", System.currentTimeMillis() + 120_000);

        reset(gameStateService);
        stubGameEndCapture();

        GameEndResponse result = gameLifecycleService.claimWin(ROOM_ID, "Host");
        assertNotNull(result, "claimWin must return a non-null GameEndResponse");
        assertEquals("GAME_END", result.type());
        assertEquals("Host", result.winner(), "Claimant must be recorded as terminal winner");
        assertTrue(result.isForfeit(), "Disconnect claim must be marked as forfeit");
        assertNotNull(result.finalState(), "finalState in GameEndResponse must not be null");
        assertTrue(result.finalState().maxPlayers() >= 2 && result.finalState().maxPlayers() <= 10,
                "finalState.maxPlayers must be within [2, 10]");
        assertNotNull(result.finalState().players(), "finalState.players must not be null");
        assertTrue(result.finalState().players().stream().anyMatch(p -> p.name().equals("Host") && Boolean.TRUE.equals(p.isWinner())),
                "Claimant Host must be flagged as winner in finalState");

        Game game = gameLifecycleService.getGame(ROOM_ID);
        assertNotNull(game);
        assertTrue(game.isGameOver(), "Game must be marked game over after claimWin");
        assertSame(result, game.getRetainedTerminalResult(), "Retained terminal result must match returned result");
    }



    @Test
    @DisplayName("Game completion concurrent with leave does not deadlock")
    void gameCompletion_ConcurrentWithPlayerLeave_DoesNotDeadlock() throws Exception {
        stubGameEndCapture();
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        int iterations = 20;
        for (int i = 0; i < iterations; i++) {
            String roomId = "deadlock-test-" + i;
            Room room = new Room(roomId, "Deadlock Room", "Host", 6, 10, 20, 1000, null);
            room.addPlayer("Host");
            room.addPlayer("Player2");
            room.setGameStarted(true);

            Game game = new Game(roomId, List.of(
                    new Player("Host", "p1", 1000),
                    new Player("Player2", "p2", 1000)
            ), 10, 20, handEvaluator);
            game.dealHoleCards();
            game.postBlinds();

            when(roomService.getRoom(roomId)).thenReturn(room);
            gameLifecycleService.restoreGame(game);

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);
            ConcurrentLinkedQueue<Throwable> unexpectedFailures = new ConcurrentLinkedQueue<>();

            Thread t1 = new Thread(() -> {
                try {
                    startLatch.await();
                    gameLifecycleService.handleGameEnd(roomId, false);
                } catch (Throwable t) {
                    // Neither operation is specified to fail; a thrown exception is a real defect, so
                    // record it rather than hiding it behind a bare countdown.
                    unexpectedFailures.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });

            Thread t2 = new Thread(() -> {
                try {
                    startLatch.await();
                    gameLifecycleService.departParticipants(roomId, List.of("Player2"));
                } catch (Throwable t) {
                    unexpectedFailures.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });

            t1.start();
            t2.start();
            startLatch.countDown();

            boolean finished = doneLatch.await(3, TimeUnit.SECONDS);
            assertTrue(finished, "Deadlock detected between handleGameEnd and handlePlayerLeave at iteration " + i);
            // Bounded completion alone is not enough: the Room-before-Game lock order (ADR-0009)
            // must also keep both operations failure-free under the race.
            assertTrue(unexpectedFailures.isEmpty(),
                    "Concurrent handleGameEnd/departParticipants failed at iteration " + i + ": " + unexpectedFailures);

            // Both operations have linearized: the game is terminal and the room agrees it is done,
            // so no partial end was left behind by either interleaving.
            Game endedGame = gameLifecycleService.getGame(roomId);
            assertNotNull(endedGame, "The terminal game aggregate must be retained after handleGameEnd at iteration " + i);
            assertTrue(endedGame.isGameOver(), "handleGameEnd must leave the game terminal at iteration " + i);
            assertTrue(room.isTerminal(), "The room must be marked terminal once its game has ended at iteration " + i);
        }
    }

    @Test
    @DisplayName("Race start with guest leave: legal results follow winning operation without departed guest in seat")
    void raceStartWithGuestLeave_LegalResultsFollowWinningOperation() throws Exception {
        Map<String, Room> activeRooms = new ConcurrentHashMap<>();
        Mockito.lenient().when(roomService.getRoom(anyString())).thenAnswer(inv -> activeRooms.get(inv.getArgument(0)));
        Mockito.lenient().when(roomService.getCurrentHost(anyString())).thenAnswer(inv -> {
            Room r = activeRooms.get(inv.getArgument(0));
            return (r == null || r.getPlayers().isEmpty()) ? null : r.getPlayers().getFirst();
        });
        Mockito.lenient().doAnswer(inv -> {
            String rid = inv.getArgument(0);
            List<String> toLeave = inv.getArgument(1);
            Room r = activeRooms.get(rid);
            if (r != null && toLeave != null) {
                toLeave.forEach(p -> { if (r.hasPlayer(p)) r.removePlayer(p); });
            }
            return null;
        }).when(roomService).leavePlayersBatch(anyString(), any(), any());
        Mockito.lenient().doAnswer(inv -> {
            String rid = inv.getArgument(0);
            Room r = activeRooms.get(rid);
            if (r != null) {
                activeRooms.remove(rid);
            }
            return null;
        }).when(roomService).leaveRoom(anyString(), anyString());

        for (int i = 0; i < 30; i++) {
            String roomId = "race-guest-" + i;
            Room room = new Room(roomId, "Race Room " + i, "Host", 6, 10, 20, 1000, null);
            room.addPlayer("Host");
            room.addPlayer("Guest");
            activeRooms.put(roomId, room);

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);
            AtomicReference<Throwable> startError = new AtomicReference<>();
            AtomicReference<Throwable> leaveError = new AtomicReference<>();

            Thread startThread = new Thread(() -> {
                try {
                    startLatch.await();
                    gameLifecycleService.createGameFromRoom(roomId);
                } catch (Throwable t) {
                    startError.set(t);
                } finally {
                    doneLatch.countDown();
                }
            });

            Thread leaveThread = new Thread(() -> {
                try {
                    startLatch.await();
                    gameLifecycleService.departParticipants(roomId, List.of("Guest"));
                } catch (Throwable t) {
                    leaveError.set(t);
                } finally {
                    doneLatch.countDown();
                }
            });

            startThread.start();
            leaveThread.start();
            startLatch.countDown();

            boolean done = doneLatch.await(3, TimeUnit.SECONDS);
            assertTrue(done, "Race timed out at iteration " + i);

            Game game = gameLifecycleService.getGame(roomId);
            if (game != null) {
                // Start won: guest departed afterwards, so guest must NOT remain in an active seat
                assertFalse(gameLifecycleService.playerExistsInGame(roomId, "Guest"),
                        "Departed guest must not remain in an active seat");
                assertFalse(room.hasPlayer("Guest"), "Departed guest must not remain in room");
            } else {
                // Leave won: start must have failed due to < 2 players
                assertNotNull(startError.get(), "Start must fail when guest leave won");
                assertInstanceOf(UnauthorisedActionException.class, startError.get());
            }
        }
    }

    @Test
    @DisplayName("Race start with host leave: legal results follow winning operation")
    void raceStartWithHostLeave_LegalResultsFollowWinningOperation() throws Exception {
        Map<String, Room> activeRooms = new ConcurrentHashMap<>();
        Mockito.lenient().when(roomService.getRoom(anyString())).thenAnswer(inv -> activeRooms.get(inv.getArgument(0)));
        Mockito.lenient().when(roomService.getCurrentHost(anyString())).thenAnswer(inv -> {
            Room r = activeRooms.get(inv.getArgument(0));
            return (r == null || r.getPlayers().isEmpty()) ? null : r.getPlayers().getFirst();
        });
        Mockito.lenient().doAnswer(inv -> {
            String rid = inv.getArgument(0);
            List<String> toLeave = inv.getArgument(1);
            Room r = activeRooms.get(rid);
            if (r != null && toLeave != null) {
                toLeave.forEach(p -> { if (r.hasPlayer(p)) r.removePlayer(p); });
            }
            return null;
        }).when(roomService).leavePlayersBatch(anyString(), any(), any());
        Mockito.lenient().doAnswer(inv -> {
            String rid = inv.getArgument(0);
            Room r = activeRooms.get(rid);
            if (r != null) {
                activeRooms.remove(rid);
            }
            return null;
        }).when(roomService).leaveRoom(anyString(), anyString());

        for (int i = 0; i < 30; i++) {
            String roomId = "race-host-" + i;
            Room room = new Room(roomId, "Race Room " + i, "Host", 6, 10, 20, 1000, null);
            room.addPlayer("Host");
            room.addPlayer("Guest");
            activeRooms.put(roomId, room);

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);
            AtomicReference<Throwable> startError = new AtomicReference<>();
            AtomicReference<Throwable> leaveError = new AtomicReference<>();

            Thread startThread = new Thread(() -> {
                try {
                    startLatch.await();
                    gameLifecycleService.createGameFromRoom(roomId);
                } catch (Throwable t) {
                    startError.set(t);
                } finally {
                    doneLatch.countDown();
                }
            });

            Thread leaveThread = new Thread(() -> {
                try {
                    startLatch.await();
                    gameLifecycleService.departParticipants(roomId, List.of("Host"));
                } catch (Throwable t) {
                    leaveError.set(t);
                } finally {
                    doneLatch.countDown();
                }
            });

            startThread.start();
            leaveThread.start();
            startLatch.countDown();

            boolean done = doneLatch.await(3, TimeUnit.SECONDS);
            assertTrue(done, "Race timed out at iteration " + i);

            Game game = gameLifecycleService.getGame(roomId);
            if (game != null) {
                // Start won: host left active game, room and game preserved
                assertNotNull(game);
            } else {
                // Host left lobby first: room was destroyed, start was prevented
                assertNotNull(startError.get(), "Start must fail when host lobby leave won");
            }
        }
    }

    @Test
    @DisplayName("departParticipants concurrent active-host and successor departures never select absent host")
    void departParticipants_ConcurrentHostAndSuccessorLeave_NeverSelectsAbsentHost() throws Exception {
        Map<String, Room> activeRooms = new ConcurrentHashMap<>();
        Mockito.lenient().when(roomService.getRoom(anyString())).thenAnswer(inv -> activeRooms.get(inv.getArgument(0)));
        Mockito.lenient().when(roomService.getCurrentHost(anyString())).thenAnswer(inv -> {
            Room r = activeRooms.get(inv.getArgument(0));
            return (r == null || r.getPlayers().isEmpty()) ? null : r.getPlayers().getFirst();
        });
        Mockito.lenient().doAnswer(inv -> {
            String rid = inv.getArgument(0);
            List<String> toLeave = inv.getArgument(1);
            Room r = activeRooms.get(rid);
            if (r != null && toLeave != null) {
                toLeave.forEach(p -> { if (r.hasPlayer(p)) r.removePlayer(p); });
            }
            return null;
        }).when(roomService).leavePlayersBatch(anyString(), any(), any());
        Mockito.lenient().doAnswer(inv -> {
            String rid = inv.getArgument(0);
            Room r = activeRooms.get(rid);
            if (r != null) {
                activeRooms.remove(rid);
            }
            return null;
        }).when(roomService).leaveRoom(anyString(), anyString());

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
                    new Player("Player3", "p3", 1000)
            ), 10, 20, handEvaluator);
            game.dealHoleCards();
            game.postBlinds();

            gameLifecycleService.restoreGame(game);

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);
            AtomicReference<Throwable> error = new AtomicReference<>();

            Thread t1 = new Thread(() -> {
                try {
                    startLatch.await();
                    gameLifecycleService.departParticipants(roomId, List.of("Host"));
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                } finally {
                    doneLatch.countDown();
                }
            });

            Thread t2 = new Thread(() -> {
                try {
                    startLatch.await();
                    gameLifecycleService.departParticipants(roomId, List.of("Successor"));
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                } finally {
                    doneLatch.countDown();
                }
            });

            t1.start();
            t2.start();
            startLatch.countDown();

            boolean done = doneLatch.await(3, TimeUnit.SECONDS);
            assertTrue(done, "Depart timed out at iteration " + i);
            assertNull(error.get(), "Unexpected error during concurrent departures: " + error.get());
        }
    }

    @Test
    @DisplayName("Guarded removeDisconnectedPlayer departs disconnected player when all guards match")
    void removeDisconnectedPlayer_ActiveGame_AllGuardsMatch_DepartsParticipant() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        long deadline = System.currentTimeMillis() - 100;

        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", deadline);
        String p2MembershipId = testRoom.getMembershipId("Player2");

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", p2MembershipId, deadline);

        verify(roomService).leavePlayersBatch(eq(ROOM_ID), eq(List.of("Player2")), any());
    }

    @Test
    @DisplayName("Guarded removeDisconnectedPlayer is no-op if player is connected")
    void removeDisconnectedPlayer_ActiveGame_ConnectedPlayer_IsNoOp() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        long deadline = System.currentTimeMillis() - 100;
        String p2MembershipId = testRoom.getMembershipId("Player2");

        // Player2 is connected (not marked disconnected)
        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", p2MembershipId, deadline);

        assertTrue(testRoom.hasPlayer("Player2"), "Connected Player2 must not be removed");
        assertTrue(game.getPlayers().stream().anyMatch(p -> p.getName().equals("Player2") && !p.getIsOut()));
    }

    @Test
    @DisplayName("Guarded removeDisconnectedPlayer is no-op if deadline is mismatched or in future")
    void removeDisconnectedPlayer_ActiveGame_MismatchedOrFutureDeadline_IsNoOp() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        long deadline = System.currentTimeMillis() + 60_000;
        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", deadline);
        String p2MembershipId = testRoom.getMembershipId("Player2");

        // Calling with different deadline:
        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", p2MembershipId, deadline - 5000);
        assertTrue(testRoom.hasPlayer("Player2"), "Mismatched deadline must be a no-op");

        // Calling with matching deadline but now < deadline:
        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", p2MembershipId, deadline);
        assertTrue(testRoom.hasPlayer("Player2"), "Future deadline (not yet reached) must be a no-op");
    }

    @Test
    @DisplayName("Guarded removeDisconnectedPlayer is no-op if membership ID is obsolete")
    void removeDisconnectedPlayer_ObsoleteMembershipId_IsNoOp() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        long deadline = System.currentTimeMillis() - 100;
        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", deadline);

        // Calling with an obsolete membership ID:
        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", "obsolete-id-12345", deadline);

        assertTrue(testRoom.hasPlayer("Player2"), "Obsolete membership ID must be a no-op");
    }

    @Test
    @DisplayName("Guarded removeDisconnectedPlayer closes room if lobby host expires")
    void removeDisconnectedPlayer_LobbyHostExpired_DestroysRoom() {
        Room lobby = new Room("lobby-1", "Lobby", "Host", 4, 10, 20, 1000, null);
        lobby.addPlayer("Host");
        when(roomService.getRoom("lobby-1")).thenReturn(lobby);
        when(roomService.getCurrentHost("lobby-1")).thenReturn("Host");
        long deadline = System.currentTimeMillis() - 100;
        lobby.setAbsenceDeadline("Host", deadline);
        String hostMembershipId = lobby.getMembershipId("Host");

        gameLifecycleService.removeDisconnectedPlayer("lobby-1", "Host", hostMembershipId, deadline);

        verify(roomService).leaveRoom("lobby-1", "Host");
    }

    @Test
    @DisplayName("Guarded removeDisconnectedPlayer releases seat if lobby guest expires")
    void removeDisconnectedPlayer_LobbyGuestExpired_ReleasesSeat() {
        Room lobby = new Room("lobby-2", "Lobby", "Host", 4, 10, 20, 1000, null);
        lobby.addPlayer("Host");
        lobby.addPlayer("Guest");
        when(roomService.getRoom("lobby-2")).thenReturn(lobby);
        when(roomService.getCurrentHost("lobby-2")).thenReturn("Host");
        long deadline = System.currentTimeMillis() - 100;
        lobby.setAbsenceDeadline("Guest", deadline);
        String guestMembershipId = lobby.getMembershipId("Guest");

        gameLifecycleService.removeDisconnectedPlayer("lobby-2", "Guest", guestMembershipId, deadline);

        verify(roomService).leavePlayersBatch("lobby-2", List.of("Guest"), "Host");
    }

    @Test
    @DisplayName("Guarded removeDisconnectedPlayer expires busted room member even if playerExistsInGame is false")
    void removeDisconnectedPlayer_BustedRoomMember_ExpiresAndLeavesRoom() {
        testRoom.addPlayer("Player3");
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        Player p3 = game.getPlayers().stream().filter(p -> p.getName().equals("Player3")).findFirst().orElseThrow();
        p3.setChips(0);
        p3.setIsOut(); // OUT player! playerExistsInGame(ROOM_ID, "Player3") will return false!
        assertFalse(gameLifecycleService.playerExistsInGame(ROOM_ID, "Player3"));

        long deadline = System.currentTimeMillis() - 100;
        p3.setDisconnected(true);
        p3.setDisconnectDeadlineEpochMs(deadline);
        String p3MembershipId = testRoom.getMembershipId("Player3");

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player3", p3MembershipId, deadline);

        verify(roomService).leavePlayersBatch(eq(ROOM_ID), eq(List.of("Player3")), any());
    }

    private void verifyNobodyRemovedFromRoom() {
        verify(roomService, never()).leavePlayersBatch(anyString(), anyList(), any());
        verify(roomService, never()).leaveRoom(anyString(), anyString());
    }

    /**
     * Immutable before/after view of the aggregate state a disconnect guard path must leave
     * untouched: the room roster, each member's membership id, connection flag and absence
     * deadline, plus (when a game exists) each game player's disconnect state/deadline, the
     * game-over flag and the scheduled-task deadline/generation maps.
     *
     * <p>Finding 7: a no-op disconnect guard previously asserted only that neither
     * {@code leavePlayersBatch} nor {@code leaveRoom} ran. That leaves a guard free to clear a
     * deadline, flip a connection flag, finalize the game, schedule work or publish without being
     * caught. Comparing a full snapshot and asserting no publisher interaction closes that gap.
     */
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
        Map<String, String> membershipIds = new java.util.LinkedHashMap<>();
        Map<String, Boolean> connected = new java.util.LinkedHashMap<>();
        Map<String, Long> absenceDeadlines = new java.util.LinkedHashMap<>();
        for (String name : roster) {
            membershipIds.put(name, room.getMembershipId(name));
            connected.put(name, room.isPlayerConnected(name));
            absenceDeadlines.put(name, room.getAbsenceDeadline(name));
        }
        Map<String, Boolean> gameDisconnected = new java.util.LinkedHashMap<>();
        Map<String, Long> gameDeadlines = new java.util.LinkedHashMap<>();
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

    /**
     * Asserts the aggregate is byte-for-byte unchanged versus {@code before} and that no member
     * was removed and no client state was published. Use for disconnect guard paths that must be
     * no-ops. The caller clears {@code gameStateService} invocations after any setup broadcast so
     * this check sees only interactions from the guarded call.
     */
    private void assertNoOpDisconnect(RoomGameSnapshot before, Room room, Game game) {
        assertEquals(before, snapshotRoomAndGame(room, game),
                "A no-op disconnect guard must leave roster/membership/connection/deadline/game-over/scheduled state unchanged");
        verifyNobodyRemovedFromRoom();
        verifyNoInteractions(gameStateService);
    }

    private Player findGamePlayer(Game game, String name) {
        return game.getPlayers().stream().filter(p -> p.getName().equals(name)).findFirst().orElseThrow();
    }

    private Game createTerminalGame() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        game.markGameOver();
        return game;
    }

    @Test
    @DisplayName("removeDisconnectedPlayer ignores null game id, player name or membership id")
    void removeDisconnectedPlayer_NullArguments_AreIgnored() {
        gameLifecycleService.removeDisconnectedPlayer(null, "Player2", "membership", 1L);
        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, null, "membership", 1L);
        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", null, 1L);

        verifyNoInteractions(roomService);
    }

    @Test
    @DisplayName("removeDisconnectedPlayer does nothing when the room no longer exists")
    void removeDisconnectedPlayer_RoomMissing_IsNoOp() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(null);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", "membership", System.currentTimeMillis() - 100);

        verifyNobodyRemovedFromRoom();
    }

    @Test
    @DisplayName("removeDisconnectedPlayer does nothing when the player is not in the room")
    void removeDisconnectedPlayer_PlayerNotInRoom_IsNoOp() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Stranger", "membership", System.currentTimeMillis() - 100);

        verifyNobodyRemovedFromRoom();
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a lobby player whose membership id is obsolete")
    void removeDisconnectedPlayer_Lobby_ObsoleteMembershipId_KeepsPlayer() {
        Room lobby = new Room("lobby-3", "Lobby", "Host", 4, 10, 20, 1000, null);
        lobby.addPlayer("Host");
        lobby.addPlayer("Guest");
        long deadline = System.currentTimeMillis() - 100;
        lobby.setAbsenceDeadline("Guest", deadline);
        when(roomService.getRoom("lobby-3")).thenReturn(lobby);
        RoomGameSnapshot before = snapshotRoomAndGame(lobby, null);
        clearInvocations(gameStateService);

        gameLifecycleService.removeDisconnectedPlayer("lobby-3", "Guest", "obsolete-id", deadline);

        assertNoOpDisconnect(before, lobby, null);
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a lobby player who has reconnected")
    void removeDisconnectedPlayer_Lobby_ReconnectedPlayer_KeepsPlayer() {
        Room lobby = new Room("lobby-3", "Lobby", "Host", 4, 10, 20, 1000, null);
        lobby.addPlayer("Host");
        lobby.addPlayer("Guest");
        long deadline = System.currentTimeMillis() - 100;
        lobby.setAbsenceDeadline("Guest", deadline);
        lobby.setPlayerConnected("Guest", true);
        when(roomService.getRoom("lobby-3")).thenReturn(lobby);
        RoomGameSnapshot before = snapshotRoomAndGame(lobby, null);
        clearInvocations(gameStateService);

        gameLifecycleService.removeDisconnectedPlayer("lobby-3", "Guest", lobby.getMembershipId("Guest"), deadline);

        assertNoOpDisconnect(before, lobby, null);
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a lobby player with no absence deadline")
    void removeDisconnectedPlayer_Lobby_NoAbsenceDeadline_KeepsPlayer() {
        Room lobby = new Room("lobby-3", "Lobby", "Host", 4, 10, 20, 1000, null);
        lobby.addPlayer("Host");
        lobby.addPlayer("Guest");
        when(roomService.getRoom("lobby-3")).thenReturn(lobby);
        RoomGameSnapshot before = snapshotRoomAndGame(lobby, null);
        clearInvocations(gameStateService);

        gameLifecycleService.removeDisconnectedPlayer("lobby-3", "Guest", lobby.getMembershipId("Guest"),
                System.currentTimeMillis() - 100);

        assertNoOpDisconnect(before, lobby, null);
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a lobby player when the expected deadline is not the current one")
    void removeDisconnectedPlayer_Lobby_DeadlineMismatch_KeepsPlayer() {
        Room lobby = new Room("lobby-3", "Lobby", "Host", 4, 10, 20, 1000, null);
        lobby.addPlayer("Host");
        lobby.addPlayer("Guest");
        long currentDeadline = System.currentTimeMillis() - 100;
        lobby.setAbsenceDeadline("Guest", currentDeadline);
        when(roomService.getRoom("lobby-3")).thenReturn(lobby);
        RoomGameSnapshot before = snapshotRoomAndGame(lobby, null);
        clearInvocations(gameStateService);

        gameLifecycleService.removeDisconnectedPlayer("lobby-3", "Guest", lobby.getMembershipId("Guest"),
                currentDeadline - 5_000);

        assertNoOpDisconnect(before, lobby, null);
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a lobby player until the deadline has passed")
    void removeDisconnectedPlayer_Lobby_BeforeDeadline_KeepsPlayer() {
        Room lobby = new Room("lobby-3", "Lobby", "Host", 4, 10, 20, 1000, null);
        lobby.addPlayer("Host");
        lobby.addPlayer("Guest");
        long deadline = System.currentTimeMillis() + 60_000;
        lobby.setAbsenceDeadline("Guest", deadline);
        when(roomService.getRoom("lobby-3")).thenReturn(lobby);
        RoomGameSnapshot before = snapshotRoomAndGame(lobby, null);
        clearInvocations(gameStateService);

        gameLifecycleService.removeDisconnectedPlayer("lobby-3", "Guest", lobby.getMembershipId("Guest"), deadline);

        assertNoOpDisconnect(before, lobby, null);
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps an active-game player whose membership id is obsolete")
    void removeDisconnectedPlayer_ActiveGame_ObsoleteMembershipId_KeepsPlayer() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        long deadline = System.currentTimeMillis() - 100;
        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", deadline);
        RoomGameSnapshot before = snapshotRoomAndGame(testRoom, game);
        clearInvocations(gameStateService);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", "obsolete-id", deadline);

        assertNoOpDisconnect(before, testRoom, game);
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps an active-game player who reconnected before the timer fired")
    void removeDisconnectedPlayer_ActiveGame_ReconnectedPlayer_KeepsPlayer() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        long deadline = System.currentTimeMillis() - 100;
        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", deadline);
        gameLifecycleService.markPlayerReconnected(ROOM_ID, "Player2");
        RoomGameSnapshot before = snapshotRoomAndGame(testRoom, game);
        clearInvocations(gameStateService);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), deadline);

        assertFalse(findGamePlayer(game, "Player2").getIsDisconnected());
        assertNoOpDisconnect(before, testRoom, game);
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a disconnected active-game player that has no deadline")
    void removeDisconnectedPlayer_ActiveGame_NoDeadline_KeepsPlayer() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        Player player2 = findGamePlayer(game, "Player2");
        player2.setDisconnected(true);
        player2.setDisconnectDeadlineEpochMs(null);
        RoomGameSnapshot before = snapshotRoomAndGame(testRoom, game);
        clearInvocations(gameStateService);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", testRoom.getMembershipId("Player2"),
                System.currentTimeMillis() - 100);

        assertNoOpDisconnect(before, testRoom, game);
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps an active-game player when the expected deadline is not the current one")
    void removeDisconnectedPlayer_ActiveGame_DeadlineMismatch_KeepsPlayer() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        long currentDeadline = System.currentTimeMillis() - 100;
        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", currentDeadline);
        RoomGameSnapshot before = snapshotRoomAndGame(testRoom, game);
        clearInvocations(gameStateService);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", testRoom.getMembershipId("Player2"),
                currentDeadline - 5_000);

        assertNoOpDisconnect(before, testRoom, game);
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps an active-game player until the deadline has passed")
    void removeDisconnectedPlayer_ActiveGame_BeforeDeadline_KeepsPlayer() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        long deadline = System.currentTimeMillis() + 60_000;
        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", deadline);
        RoomGameSnapshot before = snapshotRoomAndGame(testRoom, game);
        clearInvocations(gameStateService);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), deadline);

        assertNoOpDisconnect(before, testRoom, game);
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a room-only member who reconnected while a game is active")
    void removeDisconnectedPlayer_ActiveGame_RoomOnlyMemberConnected_KeepsMember() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        testRoom.addPlayer("Spectator");
        long deadline = System.currentTimeMillis() - 100;
        testRoom.setAbsenceDeadline("Spectator", deadline);
        testRoom.setPlayerConnected("Spectator", true);
        RoomGameSnapshot before = snapshotRoomAndGame(testRoom, game);
        clearInvocations(gameStateService);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Spectator", testRoom.getMembershipId("Spectator"), deadline);

        assertNoOpDisconnect(before, testRoom, game);
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a room-only member with no absence deadline while a game is active")
    void removeDisconnectedPlayer_ActiveGame_RoomOnlyMemberNoDeadline_KeepsMember() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        testRoom.addPlayer("Spectator");
        RoomGameSnapshot before = snapshotRoomAndGame(testRoom, game);
        clearInvocations(gameStateService);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Spectator", testRoom.getMembershipId("Spectator"),
                System.currentTimeMillis() - 100);

        assertNoOpDisconnect(before, testRoom, game);
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a room-only member whose expected deadline is not the current one")
    void removeDisconnectedPlayer_ActiveGame_RoomOnlyMemberDeadlineMismatch_KeepsMember() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        testRoom.addPlayer("Spectator");
        long currentDeadline = System.currentTimeMillis() - 100;
        testRoom.setAbsenceDeadline("Spectator", currentDeadline);
        RoomGameSnapshot before = snapshotRoomAndGame(testRoom, game);
        clearInvocations(gameStateService);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Spectator", testRoom.getMembershipId("Spectator"),
                currentDeadline - 5_000);

        assertNoOpDisconnect(before, testRoom, game);
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a room-only member until the deadline has passed")
    void removeDisconnectedPlayer_ActiveGame_RoomOnlyMemberBeforeDeadline_KeepsMember() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        testRoom.addPlayer("Spectator");
        long deadline = System.currentTimeMillis() + 60_000;
        testRoom.setAbsenceDeadline("Spectator", deadline);
        RoomGameSnapshot before = snapshotRoomAndGame(testRoom, game);
        clearInvocations(gameStateService);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Spectator", testRoom.getMembershipId("Spectator"), deadline);

        assertNoOpDisconnect(before, testRoom, game);
    }

    @Test
    @DisplayName("removeDisconnectedPlayer removes a room-only member whose grace expired while a game is active")
    void removeDisconnectedPlayer_ActiveGame_RoomOnlyMemberExpired_LeavesRoom() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        testRoom.addPlayer("Spectator");
        long deadline = System.currentTimeMillis() - 100;
        testRoom.setAbsenceDeadline("Spectator", deadline);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Spectator", testRoom.getMembershipId("Spectator"), deadline);

        verify(roomService).leavePlayersBatch(eq(ROOM_ID), eq(List.of("Spectator")), any());
        assertEquals(2, game.getPlayers().size(), "A room-only member is not a game participant");
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a terminal-game player whose membership id is obsolete")
    void removeDisconnectedPlayer_TerminalGame_ObsoleteMembershipId_KeepsPlayer() {
        Game game = createTerminalGame();
        long deadline = System.currentTimeMillis() - 100;
        Player player2 = findGamePlayer(game, "Player2");
        player2.setDisconnected(true);
        player2.setDisconnectDeadlineEpochMs(deadline);
        RoomGameSnapshot before = snapshotRoomAndGame(testRoom, game);
        clearInvocations(gameStateService);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", "obsolete-id", deadline);

        assertNoOpDisconnect(before, testRoom, game);
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a terminal-game player with no deadline anywhere")
    void removeDisconnectedPlayer_TerminalGame_NoDeadline_KeepsPlayer() {
        Game game = createTerminalGame();
        findGamePlayer(game, "Player2").setDisconnected(true);
        RoomGameSnapshot before = snapshotRoomAndGame(testRoom, game);
        clearInvocations(gameStateService);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", testRoom.getMembershipId("Player2"),
                System.currentTimeMillis() - 100);

        assertNoOpDisconnect(before, testRoom, game);
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a terminal-game player when the expected deadline is not the current one")
    void removeDisconnectedPlayer_TerminalGame_DeadlineMismatch_KeepsPlayer() {
        Game game = createTerminalGame();
        long currentDeadline = System.currentTimeMillis() - 100;
        Player player2 = findGamePlayer(game, "Player2");
        player2.setDisconnected(true);
        player2.setDisconnectDeadlineEpochMs(currentDeadline);
        RoomGameSnapshot before = snapshotRoomAndGame(testRoom, game);
        clearInvocations(gameStateService);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", testRoom.getMembershipId("Player2"),
                currentDeadline - 5_000);

        assertNoOpDisconnect(before, testRoom, game);
    }

    @Test
    @DisplayName("removeDisconnectedPlayer checks the game player's deadline before the room's in a terminal game")
    void removeDisconnectedPlayer_TerminalGame_GamePlayerDeadlineWinsOverRoomDeadline() {
        Game game = createTerminalGame();
        long playerDeadline = System.currentTimeMillis() - 100;
        long roomDeadline = playerDeadline - 5_000;
        Player player2 = findGamePlayer(game, "Player2");
        player2.setDisconnected(true);
        player2.setDisconnectDeadlineEpochMs(playerDeadline);
        testRoom.setAbsenceDeadline("Player2", roomDeadline);
        RoomGameSnapshot before = snapshotRoomAndGame(testRoom, game);
        clearInvocations(gameStateService);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), roomDeadline);

        assertNoOpDisconnect(before, testRoom, game);
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a terminal-game player until the deadline has passed")
    void removeDisconnectedPlayer_TerminalGame_BeforeDeadline_KeepsPlayer() {
        Game game = createTerminalGame();
        long deadline = System.currentTimeMillis() + 60_000;
        Player player2 = findGamePlayer(game, "Player2");
        player2.setDisconnected(true);
        player2.setDisconnectDeadlineEpochMs(deadline);
        RoomGameSnapshot before = snapshotRoomAndGame(testRoom, game);
        clearInvocations(gameStateService);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), deadline);

        assertNoOpDisconnect(before, testRoom, game);
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a terminal-game player who is no longer disconnected")
    void removeDisconnectedPlayer_TerminalGame_PlayerNotDisconnected_KeepsPlayer() {
        Game game = createTerminalGame();
        long deadline = System.currentTimeMillis() - 100;
        Player player2 = findGamePlayer(game, "Player2");
        player2.setDisconnected(false);
        player2.setDisconnectDeadlineEpochMs(deadline);
        RoomGameSnapshot before = snapshotRoomAndGame(testRoom, game);
        clearInvocations(gameStateService);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), deadline);

        assertNoOpDisconnect(before, testRoom, game);
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a room-only member who reconnected after the game ended")
    void removeDisconnectedPlayer_TerminalGame_RoomOnlyMemberConnected_KeepsMember() {
        Game game = createTerminalGame();
        testRoom.addPlayer("Spectator");
        long deadline = System.currentTimeMillis() - 100;
        testRoom.setAbsenceDeadline("Spectator", deadline);
        testRoom.setPlayerConnected("Spectator", true);
        RoomGameSnapshot before = snapshotRoomAndGame(testRoom, game);
        clearInvocations(gameStateService);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Spectator", testRoom.getMembershipId("Spectator"), deadline);

        assertNoOpDisconnect(before, testRoom, game);
    }

    @Test
    @DisplayName("removeDisconnectedPlayer removes a terminal-game player using the game player's deadline")
    void removeDisconnectedPlayer_TerminalGame_GamePlayerDeadlineExpired_LeavesRoomAndKeepsRoster() {
        Game game = createTerminalGame();
        long deadline = System.currentTimeMillis() - 100;
        Player player2 = findGamePlayer(game, "Player2");
        player2.setDisconnected(true);
        player2.setDisconnectDeadlineEpochMs(deadline);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), deadline);

        verify(roomService).leavePlayersBatch(eq(ROOM_ID), eq(List.of("Player2")), any());
        assertTrue(game.getPlayers().stream().anyMatch(p -> p.getName().equals("Player2")),
                "The terminal roster stays frozen");
    }

    @Test
    @DisplayName("removeDisconnectedPlayer falls back to the room's absence deadline in a terminal game")
    void removeDisconnectedPlayer_TerminalGame_FallsBackToRoomAbsenceDeadline_LeavesRoom() {
        Game game = createTerminalGame();
        long deadline = System.currentTimeMillis() - 100;
        Player player2 = findGamePlayer(game, "Player2");
        player2.setDisconnected(true);
        player2.setDisconnectDeadlineEpochMs(null);
        testRoom.setAbsenceDeadline("Player2", deadline);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), deadline);

        verify(roomService).leavePlayersBatch(eq(ROOM_ID), eq(List.of("Player2")), any());
    }

    @Test
    @DisplayName("removeDisconnectedPlayer removes a room-only member whose grace expired after the game ended")
    void removeDisconnectedPlayer_TerminalGame_RoomOnlyMemberExpired_LeavesRoom() {
        createTerminalGame();
        testRoom.addPlayer("Spectator");
        long deadline = System.currentTimeMillis() - 100;
        testRoom.setAbsenceDeadline("Spectator", deadline);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Spectator", testRoom.getMembershipId("Spectator"), deadline);

        verify(roomService).leavePlayersBatch(eq(ROOM_ID), eq(List.of("Spectator")), any());
    }

    @Test
    @DisplayName("removeDisconnectedPlayer treats a settled tournament-over game as terminal and finalizes it")
    void removeDisconnectedPlayer_SettledTournamentOver_LeavesRoomAndFinalizesGame() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        Player busted = game.getPlayers().get(0);
        busted.setIsOut();
        game.conductShowdown();
        assertTrue(game.getLastSettlementResult().settled());
        assertTrue(game.isTournamentOver());
        assertFalse(game.isGameOver());
        long deadline = System.currentTimeMillis() - 100;
        busted.setDisconnected(true);
        busted.setDisconnectDeadlineEpochMs(deadline);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, busted.getName(), testRoom.getMembershipId(busted.getName()),
                deadline);

        verify(roomService).leavePlayersBatch(eq(ROOM_ID), eq(List.of(busted.getName())), any());
        assertTrue(game.isGameOver(), "A settled tournament-over game is finalized by the terminal path");
    }


    @Test
    @DisplayName("departParticipants chooses later connected contender over earlier-joined busted member")
    void departParticipants_HostLeaves_ChoosesConnectedContenderOverEarlierBustedMember() {
        testRoom.addPlayer("Player3"); // Room join order: Host, Player2, Player3
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        when(roomService.getCurrentHost(ROOM_ID)).thenReturn("Host");
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);

        // Player2 is busted (out)
        Player p2 = game.getPlayers().stream().filter(p -> p.getName().equals("Player2")).findFirst().orElseThrow();
        p2.setChips(0);
        p2.setIsOut();

        // Player3 is connected contender
        Player p3 = game.getPlayers().stream().filter(p -> p.getName().equals("Player3")).findFirst().orElseThrow();
        p3.setChips(1000);
        p3.setDisconnected(false);

        reset(roomService);
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        when(roomService.getCurrentHost(ROOM_ID)).thenReturn("Host");

        gameLifecycleService.departParticipants(ROOM_ID, List.of("Host"));

        verify(roomService).leavePlayersBatch(ROOM_ID, List.of("Host"), "Player3");
    }

    @Test
    @DisplayName("departParticipants chooses unsettled zero-stack all-in candidate as contender")
    void departParticipants_HostLeaves_ChoosesUnsettledAllInCandidateAsContender() {
        testRoom.addPlayer("Player3"); // Room join order: Host, Player2, Player3
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        when(roomService.getCurrentHost(ROOM_ID)).thenReturn("Host");
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);

        // Player2 is all-in with 0 chips during unsettled hand (PRE_FLOP / FLOP)
        Player p2 = game.getPlayers().stream().filter(p -> p.getName().equals("Player2")).findFirst().orElseThrow();
        p2.setChips(0);
        assertFalse(p2.getIsOut());
        p2.setDisconnected(false);

        reset(roomService);
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        when(roomService.getCurrentHost(ROOM_ID)).thenReturn("Host");

        gameLifecycleService.departParticipants(ROOM_ID, List.of("Host"));

        // Player2 is contender (unsettled) and joined earlier than Player3
        verify(roomService).leavePlayersBatch(ROOM_ID, List.of("Host"), "Player2");
    }

    @Test
    @DisplayName("departParticipants chooses disconnected contender when no connected contender remains")
    void departParticipants_HostLeaves_ChoosesDisconnectedContenderOverSpectator() {
        testRoom.addPlayer("Player3"); // Player3 in room but not in game (spectator)
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        when(roomService.getCurrentHost(ROOM_ID)).thenReturn("Host");
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        // Remove Player3 from game so Player3 is just a room spectator
        Player p3Game = game.getPlayers().stream().filter(p -> p.getName().equals("Player3")).findFirst().orElse(null);
        if (p3Game != null) game.removePlayerFromGame(p3Game);

        // Player2 is disconnected contender
        Player p2 = game.getPlayers().stream().filter(p -> p.getName().equals("Player2")).findFirst().orElseThrow();
        p2.setChips(1000);
        p2.setDisconnected(true);

        reset(roomService);
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        when(roomService.getCurrentHost(ROOM_ID)).thenReturn("Host");

        gameLifecycleService.departParticipants(ROOM_ID, List.of("Host"));

        // Player2 chosen because Player2 is disconnected contender, Player3 is spectator
        verify(roomService).leavePlayersBatch(ROOM_ID, List.of("Host"), "Player2");
    }

    @Test
    @DisplayName("departParticipants chooses spectator fallback in room join order when no contenders remain")
    void departParticipants_HostLeaves_ChoosesSpectatorFallbackInRoomJoinOrder() {
        testRoom.addPlayer("Spectator1");
        testRoom.addPlayer("Spectator2");
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        when(roomService.getCurrentHost(ROOM_ID)).thenReturn("Host");
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);

        // All non-host game players are busted
        Player p2 = game.getPlayers().stream().filter(p -> p.getName().equals("Player2")).findFirst().orElseThrow();
        p2.setChips(0);
        p2.setIsOut();

        // Spectator1 and Spectator2 are not in game
        Player s1Game = game.getPlayers().stream().filter(p -> p.getName().equals("Spectator1")).findFirst().orElse(null);
        if (s1Game != null) game.removePlayerFromGame(s1Game);
        Player s2Game = game.getPlayers().stream().filter(p -> p.getName().equals("Spectator2")).findFirst().orElse(null);
        if (s2Game != null) game.removePlayerFromGame(s2Game);

        reset(roomService);
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        when(roomService.getCurrentHost(ROOM_ID)).thenReturn("Host");

        gameLifecycleService.departParticipants(ROOM_ID, List.of("Host"));

        // Fallback in room join order: Player2 (busted) was first, Spectator1 was next...
        verify(roomService).leavePlayersBatch(ROOM_ID, List.of("Host"), "Player2");
    }

    @Test
    @DisplayName("departParticipants handles batch departure of multiple potential successors")
    void departParticipants_HostLeavesInBatchWithSuccessors_ChoosesFirstRemainingContender() {
        testRoom.addPlayer("Player3");
        testRoom.addPlayer("Player4");
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        when(roomService.getCurrentHost(ROOM_ID)).thenReturn("Host");
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        reset(roomService);
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        when(roomService.getCurrentHost(ROOM_ID)).thenReturn("Host");

        // Host, Player2, and Player3 leave together in one batch
        gameLifecycleService.departParticipants(ROOM_ID, List.of("Host", "Player2", "Player3"));

        verify(roomService).leavePlayersBatch(ROOM_ID, List.of("Host", "Player2", "Player3"), "Player4");
    }

    @Test
    @DisplayName("departParticipants passes null host when room becomes empty")
    void departParticipants_EmptyTerminalRoom_PassesNullHost() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        when(roomService.getCurrentHost(ROOM_ID)).thenReturn("Host");
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        game.markGameOver();

        reset(roomService);
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        when(roomService.getCurrentHost(ROOM_ID)).thenReturn("Host");

        // Both Host and Player2 leave
        gameLifecycleService.departParticipants(ROOM_ID, List.of("Host", "Player2"));

        verify(roomService).leavePlayersBatch(ROOM_ID, List.of("Host", "Player2"), null);
    }

}
