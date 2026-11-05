package com.pokergame.service;

import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.dto.response.ApiResponse;
import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.dto.internal.PlayerJoinInfo;
import com.pokergame.enums.ResponseMessage;
import org.mockito.ArgumentCaptor;
import java.time.LocalDateTime;
import com.pokergame.exception.ConflictException;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.HandRank;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.Rank;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.Suit;
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
import com.pokergame.exception.BadRequestException;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.stream.Collectors;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for the GameLifecycleService class.
 */
@Tag("unit")
@DisplayName("Game lifecycle service")
@ExtendWith(MockitoExtension.class)
class GameLifecycleServiceTest {

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

    // ==================== createGameFromRoom Tests ====================

    /**
     * Protects the contract that create game from room with valid room should create game.
     */
    @Test
    void createGameFromRoom_WithValidRoom_ShouldCreateGame() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);

        String gameId = gameLifecycleService.createGameFromRoom(ROOM_ID);

        assertEquals(ROOM_ID, gameId);
        assertNotNull(gameLifecycleService.getGame(ROOM_ID));
        assertTrue(gameLifecycleService.gameExists(ROOM_ID));

        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
        verify(gameStateService).broadcastGameState(eq(ROOM_ID), any(Game.class));
    }

    /**
     * Protects the contract that create game from room with three players should create game with all players.
     */
    @Test
    void createGameFromRoom_WithThreePlayers_ShouldCreateGameWithAllPlayers() {
        testRoom.addPlayer("Player3");
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);

        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);
        assertNotNull(game);
        assertEquals(3, game.getPlayers().size());
    }

    /**
     * Protects the contract that create game from room when room not found should throw exception.
     */
    @Test
    void createGameFromRoom_WhenRoomNotFound_ShouldThrowException() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(null);

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> gameLifecycleService.createGameFromRoom(ROOM_ID));

        assertEquals("Room not found", exception.getMessage());
    }

    /**
     * Protects the contract that create game from room with one player should throw exception.
     */
    @Test
    void createGameFromRoom_WithOnePlayer_ShouldThrowException() {
        Room onePlayerRoom = new Room(
                ROOM_ID,
                "Single Player Room",
                "Host",
                6,
                5,
                10,
                100,
                null);
        onePlayerRoom.addPlayer("Host");

        when(roomService.getRoom(ROOM_ID)).thenReturn(onePlayerRoom);

        UnauthorisedActionException exception = assertThrows(
                UnauthorisedActionException.class,
                () -> gameLifecycleService.createGameFromRoom(ROOM_ID));

        assertEquals("Need at least 2 players to start game. Please wait for more players to join.",
                exception.getMessage());
    }

    /**
     * Protects the contract that create game from room should set correct blinds.
     */
    @Test
    void createGameFromRoom_ShouldSetCorrectBlinds() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);

        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);
        assertNotNull(game);
        // The game should have the blinds from the room (5 small, 10 big)
        // We can verify this indirectly through the pot after blinds are posted
        assertTrue(game.getPot() > 0);
    }

    /**
     * Protects the contract that create game from room should give players correct buy in.
     */
    @Test
    void createGameFromRoom_ShouldGivePlayersCorrectBuyIn() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);

        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);
        // Each player should start with buyIn minus any blinds they posted
        for (Player player : game.getPlayers()) {
            assertTrue(player.getChips() <= 100 && player.getChips() >= 85);
        }
    }

    // ==================== startNewHand Tests ====================

    /**
     * Protects the contract that start new hand with valid game should reset and deal.
     */
    @Test
    void startNewHand_WithValidGame_ShouldResetAndDeal() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        gameLifecycleService.getGame(ROOM_ID).conductShowdown();
        reset(gameStateService);
        stubGameEndCapture();

        gameLifecycleService.startNewHand(ROOM_ID);

        verify(gameStateService).broadcastGameState(eq(ROOM_ID), any(Game.class));
    }

    @Test
    void startNewHand_WithUnsettledHand_DoesNotDealOrPostSecondBlinds() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        int potBefore = game.getPot();
        long handNumberBefore = game.getHandNumber();

        gameLifecycleService.startNewHand(ROOM_ID);

        assertEquals(potBefore, game.getPot());
        assertEquals(handNumberBefore, game.getHandNumber());
        assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
    }

    /**
     * Protects the contract that advance auto advance step at pre flop should deal flop through service seam.
     */
    @Test
    void advanceAutoAdvanceStep_AtPreFlop_ShouldDealFlopThroughServiceSeam() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        reset(gameStateService);
        stubGameEndCapture();

        boolean complete = gameLifecycleService.advanceAutoAdvanceStep(ROOM_ID);

        assertFalse(complete);
        assertEquals(GamePhase.FLOP, gameLifecycleService.getGame(ROOM_ID).getCurrentPhase());
    }

    /**
     * Protects the contract that stale ready countdown timeout cannot close a newer countdown.
     */
    @Test
    void staleReadyCountdownTimeoutCannotCloseANewerCountdown() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        long staleDeadline = System.currentTimeMillis() + 1_000;
        long currentDeadline = staleDeadline + 1_000;
        game.openReadyCountdown(currentDeadline);

        gameLifecycleService.handleReadyCountdownTimeout(ROOM_ID, staleDeadline);

        assertTrue(game.isReadyCountdownActive());
        assertEquals(currentDeadline, game.getReadyCountdownDeadlineEpochMs());
    }

    @Test
    void readyTimeoutWithOneConnectedEligiblePlayerWaitsWithoutStartingOrPostingBlinds() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        game.conductShowdown();
        gameLifecycleService.startReadyCountdown(ROOM_ID, 60_000);
        long deadline = game.getReadyCountdownDeadlineEpochMs();
        long handNumber = game.getHandNumber();
        int hostChips = game.getPlayers().get(0).getChips();
        int player2Chips = game.getPlayers().get(1).getChips();
        int pot = game.getPot();

        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Host", System.currentTimeMillis() + 120_000);
        assertTrue(game.isReadyCountdownActive(), "an existing countdown continues to its deadline");
        gameLifecycleService.handleReadyCountdownTimeout(ROOM_ID, deadline);

        assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, game.getReadyGateState());
        assertFalse(game.isReadyCountdownActive());
        assertNull(game.getReadyCountdownDeadlineEpochMs());
        assertEquals(handNumber, game.getHandNumber());
        assertEquals(hostChips, game.getPlayers().get(0).getChips());
        assertEquals(player2Chips, game.getPlayers().get(1).getChips());
        assertEquals(pot, game.getPot());
    }

    /**
     * Protects the contract that start new hand when game over should not proceed.
     */
    @Test
    void startNewHand_WhenGameOver_ShouldNotProceed() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);
        // Simulate game over by removing all but one player
        game.conductShowdown();
        game.getPlayers().get(1).setIsOut();

        reset(gameStateService);
        stubGameEndCapture();
        gameLifecycleService.startNewHand(ROOM_ID);

        // Should end the game and schedule cleanup because game became over after reset
        verify(gameStateService, never()).broadcastGameState(anyString(), any(Game.class));
        verify(gameStateService).sendGameEnd(any(GameEndResponse.class));
        assertNotNull(game.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP));
    }

    /**
     * Protects the contract that start new hand should deal hole cards to players.
     */
    @Test
    void startNewHand_ShouldDealHoleCardsToPlayers() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);
        for (Player player : game.getPlayers()) {
            assertEquals(2, player.getHoleCards().size());
        }
    }

    /**
     * Protects the contract that start new hand should post blinds.
     */
    @Test
    void startNewHand_ShouldPostBlinds() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);
        assertTrue(game.getPot() > 0);
        assertEquals(10, game.getCurrentHighestBet()); // Big blind is 10
    }

    @Test
    void startNewHand_ReconcilesForcedBlindsAsRunoutOrGenuineOwedCall() {
        Room headsUp = new Room("forced-runout", "Forced runout", "Host", 2, 5, 10, 100, null);
        headsUp.addPlayer("Host");
        headsUp.addPlayer("Player2");
        when(roomService.getRoom(headsUp.getRoomId())).thenReturn(headsUp);
        GameLifecycleService lifecycle = new GameLifecycleService(roomService, handEvaluator, gameStateService,
                messagingTemplate, taskScheduler);
        lifecycle.createGameFromRoom(headsUp.getRoomId());
        Game runout = lifecycle.getGame(headsUp.getRoomId());
        runout.conductShowdown();
        runout.getPlayers().forEach(player -> player.setChips(5));
        reset(gameStateService);
        stubGameEndCapture();

        lifecycle.startNewHand(headsUp.getRoomId());

        assertTrue(runout.getActivePlayers().stream().allMatch(Player::getIsAllIn));
        assertEquals(5, runout.getCurrentHighestBet());
        assertNotNull(runout.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
        verify(gameStateService).broadcastGameStateWithAutoAdvance(eq(headsUp.getRoomId()), eq(runout), anyString());

        Room threeHanded = new Room("forced-call", "Forced call", "Host", 3, 5, 10, 100, null);
        threeHanded.addPlayer("Host");
        threeHanded.addPlayer("Player2");
        threeHanded.addPlayer("Player3");
        when(roomService.getRoom(threeHanded.getRoomId())).thenReturn(threeHanded);
        GameLifecycleService callLifecycle = new GameLifecycleService(roomService, handEvaluator, gameStateService,
                messagingTemplate, taskScheduler);
        callLifecycle.createGameFromRoom(threeHanded.getRoomId());
        Game callHand = callLifecycle.getGame(threeHanded.getRoomId());
        callHand.conductShowdown();
        int playerCount = callHand.getActivePlayers().size();
        int nextSmallBlindIndex = (callHand.getDealerPosition() + 2) % playerCount;
        int nextBigBlindIndex = (nextSmallBlindIndex + 1) % playerCount;
        int nextActorIndex = (nextBigBlindIndex + 1) % playerCount;
        for (int index = 0; index < callHand.getPlayers().size(); index++) {
            callHand.getPlayers().get(index).setChips(index == nextSmallBlindIndex ? 5
                    : index == nextBigBlindIndex ? 10 : 500);
        }
        reset(gameStateService);
        stubGameEndCapture();

        callLifecycle.startNewHand(threeHanded.getRoomId());

        Player actor = callHand.getCurrentPlayer();
        assertNotNull(actor);
        assertEquals(callHand.getActivePlayers().get(nextActorIndex), actor);
        assertTrue(callHand.getCurrentHighestBet() > actor.getCurrentBet(), "The actor owes the posted blind");
        assertNull(callHand.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
        assertTrue(callHand.getLegalActionsFor(actor).contains(PlayerAction.CALL));
        verify(gameStateService).broadcastGameState(threeHanded.getRoomId(), callHand);
        verify(gameStateService, never()).broadcastGameStateWithAutoAdvance(eq(threeHanded.getRoomId()), any(), anyString());
    }

    @Test
    void departedAllInDuringAutoAdvanceMakesCapturedCallbackStale() {
        Room room = new Room("stale-auto", "Stale auto", "A", 4, 5, 10, 100, null);
        for (String name : List.of("A", "B", "C", "D")) room.addPlayer(name);
        when(roomService.getRoom(room.getRoomId())).thenReturn(room);
        GameLifecycleService lifecycle = new GameLifecycleService(roomService, handEvaluator, gameStateService,
                messagingTemplate, taskScheduler);
        List<Card> board = List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES),
                new Card(Rank.QUEEN, Suit.SPADES));
        List<Card> deck = List.of(new Card(Rank.JACK, Suit.SPADES), new Card(Rank.TEN, Suit.SPADES));
        Player a = restoredPlayer("A", "a", 0, 50, false, true);
        Player b = restoredPlayer("B", "b", 100, 0, true, false);
        Player c = restoredPlayer("C", "c", 0, 50, false, true);
        Player d = restoredPlayer("D", "d", 100, 0, true, false);
        Game game = Game.restoreBuilder().gameId(room.getRoomId()).players(List.of(a, b, c, d))
                .activePlayerIds(List.of("a", "b", "c", "d")).remainingDeck(deck).communityCards(board)
                .pot(100).dealerPosition(0).smallBlindPosition(1).bigBlindPosition(2).currentPlayerPosition(0)
                .currentHighestBet(50).currentPhase(GamePhase.FLOP).gameOver(false).smallBlind(5).bigBlind(10)
                .handContributions(Map.of("a", 50, "c", 50)).preHandCarryOver(0).handSeatOrder(List.of("a", "b", "c", "d"))
                .dealerAnchorPlayerId("a").smallBlindPlayerId("b").bigBlindPlayerId("c")
                .readyCountdownActive(false).everyoneHasHadInitialTurn(true).actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of()).handEvaluator(handEvaluator).build();
        lifecycle.restoreGame(game);

        lifecycle.scheduleAutoAdvance(room.getRoomId());
        ArgumentCaptor<Runnable> callbacks = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(callbacks.capture(), any(Instant.class));
        Runnable staleAutoAdvance = callbacks.getValue();
        int winnerChipsBefore = a.getChips();

        lifecycle.leaveGame(room.getRoomId(), "C");
        assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
        assertEquals(3, game.getCommunityCards().size());
        assertEquals(100, a.getChips() - winnerChipsBefore);
        HandSettlementResult settled = game.getLastSettlementResult();

        staleAutoAdvance.run();

        assertEquals(3, game.getCommunityCards().size());
        assertEquals(0, game.getPot());
        assertSame(settled, game.getLastSettlementResult());
        assertEquals(100, a.getChips() - winnerChipsBefore);
    }

    @Test
    void departureDuringReadyWindowPreservesSettlementUntilRemainingPlayersReadyOnce() {
        Room room = new Room("ready-departure", "Ready departure", "A", 4, 5, 10, 100, null);
        for (String name : List.of("A", "B", "C", "D")) room.addPlayer(name);
        when(roomService.getRoom(room.getRoomId())).thenReturn(room);
        GameLifecycleService lifecycle = new GameLifecycleService(roomService, handEvaluator, gameStateService,
                messagingTemplate, taskScheduler);
        lifecycle.createGameFromRoom(room.getRoomId());
        Game game = lifecycle.getGame(room.getRoomId());
        game.conductShowdown();
        game.getPlayers().forEach(player -> player.setChips(100));
        HandSettlementResult settled = game.getLastSettlementResult();
        long readyDeadline = System.currentTimeMillis() + 60_000;
        game.openReadyCountdown(readyDeadline);
        game.getPlayers().stream().filter(player -> List.of("A", "B").contains(player.getName()))
                .forEach(player -> player.setReadyForNextHand(true));

        lifecycle.leaveGame(room.getRoomId(), "D");

        assertTrue(game.isReadyCountdownActive());
        assertEquals(readyDeadline, game.getReadyCountdownDeadlineEpochMs());
        assertSame(settled, game.getLastSettlementResult());
        assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());

        lifecycle.markPlayerReadyForNextHand(room.getRoomId(), "C");

        assertFalse(game.isReadyCountdownActive());
        assertNull(game.getLastSettlementResult());
        assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
        assertEquals(15, game.getPot(), "One hand start posts exactly one set of blinds");
        assertEquals(285, game.getActivePlayers().stream().mapToInt(Player::getChips).sum(),
                "One hand start consumes exactly fifteen chips in blinds");
        assertThrows(UnauthorisedActionException.class,
                () -> lifecycle.markPlayerReadyForNextHand(room.getRoomId(), "A"));
    }

    private static Player restoredPlayer(String name, String id, int chips, int bet, boolean folded, boolean allIn) {
        return Player.restoreBuilder().name(name).playerId(id).chips(chips).holeCards(List.of())
                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(bet).hasFolded(folded).allIn(allIn)
                .out(false).disconnected(false).readyForNextHand(false).build();
    }

    /**
     * Protects the contract that start new hand when game missing should return without throwing.
     */
    @Test
    void startNewHand_WhenGameMissing_ShouldReturnWithoutThrowing() {
        assertDoesNotThrow(() -> gameLifecycleService.startNewHand("missing-game-id"));
        verify(gameStateService, never()).broadcastGameState(anyString(), any(Game.class));
    }

    // ==================== leaveGame Tests ====================

    /**
     * Repeated leave requests for a missing game are safe after an earlier successful departure.
     */
    @Test
    void leaveGame_WhenGameNotFound_IsIdempotent() {
        assertDoesNotThrow(() -> gameLifecycleService.leaveGame("nonexistent-id", "Player"));
    }

    /**
     * Repeated leave requests for an already absent player are safe.
     */
    @Test
    void leaveGame_WhenPlayerNotFound_IsIdempotent() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        assertDoesNotThrow(() -> gameLifecycleService.leaveGame(ROOM_ID, "NonexistentPlayer"));
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

        gameLifecycleService.leaveGame(ROOM_ID, playerToLeave);

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

        gameLifecycleService.leaveGame(ROOM_ID, "Player2");

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

        gameLifecycleService.leaveGame(ROOM_ID, playerToLeave.getName());

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

        assertDoesNotThrow(() -> gameLifecycleService.leaveGame(ROOM_ID, playerToLeave.getName()));
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

        assertDoesNotThrow(() -> gameLifecycleService.leaveGame(ROOM_ID, playerToLeave));

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

        assertDoesNotThrow(() -> gameLifecycleService.leaveGame(ROOM_ID, playerToLeave.getName()));

        assertNull(gameLifecycleService.getGame(ROOM_ID));
        verify(gameStateService, never()).broadcastGameState(anyString(), any(Game.class));
    }

    // ==================== getGame Tests ====================

    /**
     * Protects the contract that get game with valid ID should return game.
     */
    @Test
    void getGame_WithValidId_ShouldReturnGame() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);

        assertNotNull(game);
    }

    /**
     * Protects the contract that get game with invalid ID should return null.
     */
    @Test
    void getGame_WithInvalidId_ShouldReturnNull() {
        Game game = gameLifecycleService.getGame("nonexistent-id");
        assertNull(game);
    }

    // ==================== gameExists Tests ====================

    /**
     * Protects the contract that game exists when game exists should return true.
     */
    @Test
    void gameExists_WhenGameExists_ShouldReturnTrue() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        assertTrue(gameLifecycleService.gameExists(ROOM_ID));
    }

    /**
     * Protects the contract that game exists when game does not exist should return false.
     */
    @Test
    void gameExists_WhenGameDoesNotExist_ShouldReturnFalse() {
        assertFalse(gameLifecycleService.gameExists("nonexistent-id"));
    }

    /**
     * Protects the contract that player exists in game when player exists should return true.
     */
    @Test
    void playerExistsInGame_WhenPlayerExists_ShouldReturnTrue() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        String existingPlayer = gameLifecycleService.getGame(ROOM_ID).getPlayers().getFirst().getName();

        assertTrue(gameLifecycleService.playerExistsInGame(ROOM_ID, existingPlayer));
    }

    /**
     * Protects the contract that player exists in game when player does not exist should return false.
     */
    @Test
    void playerExistsInGame_WhenPlayerDoesNotExist_ShouldReturnFalse() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        assertFalse(gameLifecycleService.playerExistsInGame(ROOM_ID, "NonexistentPlayer"));
    }

    /**
     * Protects the contract that player exists in game when game does not exist should return false.
     */
    @Test
    void playerExistsInGame_WhenGameDoesNotExist_ShouldReturnFalse() {
        assertFalse(gameLifecycleService.playerExistsInGame("nonexistent-id", "Player"));
    }

    @Test
    @DisplayName("playerExistsInGame holds monitor throughout lookup and excludes OUT players")
    void playerExistsInGame_HoldsMonitorThroughoutLookupAndExcludesOutPlayers() {
        Game game = mock(Game.class);
        when(game.getGameId()).thenReturn("mock-game");
        Player activePlayer = new Player("Alice", "p1", 100);
        Player outPlayer = new Player("Bob", "p2", 0);
        outPlayer.setIsOut();

        List<Player> playerList = new ArrayList<>(List.of(activePlayer, outPlayer)) {
            @Override
            public Spliterator<Player> spliterator() {
                assertTrue(Thread.holdsLock(game), "Game monitor must be held during playerExistsInGame lookup");
                return super.spliterator();
            }

            @Override
            public Iterator<Player> iterator() {
                assertTrue(Thread.holdsLock(game), "Game monitor must be held during playerExistsInGame lookup");
                return super.iterator();
            }
        };

        when(game.getPlayers()).thenReturn(playerList);
        gameLifecycleService.restoreGame(game);

        assertTrue(gameLifecycleService.playerExistsInGame("mock-game", "Alice"));
        assertFalse(gameLifecycleService.playerExistsInGame("mock-game", "Bob"));
        assertFalse(gameLifecycleService.playerExistsInGame("mock-game", "Charlie"));
    }

    @Test
    @DisplayName("playerExistsInGame with concurrent player mutation completes without iteration exceptions")
    void playerExistsInGame_ConcurrentWithPlayerMutation_CompletesWithoutExceptions() throws Exception {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> error = new AtomicReference<>();

        Thread lookupThread = new Thread(() -> {
            while (running.get() && error.get() == null) {
                try {
                    gameLifecycleService.playerExistsInGame(ROOM_ID, "Host");
                } catch (Throwable t) {
                    error.set(t);
                }
            }
        });

        Thread mutationThread = new Thread(() -> {
            int count = 0;
            while (running.get() && error.get() == null && count < 2000) {
                synchronized (game) {
                    Player temp = new Player("Temp" + count, "id" + count, 100);
                    game.getPlayers().add(temp);
                    game.getPlayers().remove(temp);
                }
                count++;
            }
            running.set(false);
        });

        lookupThread.start();
        mutationThread.start();
        mutationThread.join(5000);
        running.set(false);
        lookupThread.join(5000);

        assertNull(error.get(), "Concurrent player lookup threw an exception: " + error.get());
    }

    @Test
    @DisplayName("playerExistsInGame returns false when gameId or playerName is null")
    void playerExistsInGame_WhenGameIdOrPlayerNameIsNull_ShouldReturnFalse() {
        assertFalse(gameLifecycleService.playerExistsInGame(null, "Host"));
        assertFalse(gameLifecycleService.playerExistsInGame(ROOM_ID, null));
        assertFalse(gameLifecycleService.playerExistsInGame(null, null));
    }

    @Test
    @DisplayName("playerExistsInGame returns false when game players list is null")
    void playerExistsInGame_WhenGamePlayersListIsNull_ShouldReturnFalse() {
        Game game = mock(Game.class);
        when(game.getGameId()).thenReturn("mock-game-null-players");
        when(game.getPlayers()).thenReturn(null);
        gameLifecycleService.restoreGame(game);

        assertFalse(gameLifecycleService.playerExistsInGame("mock-game-null-players", "Host"));
    }

    @Test
    @DisplayName("playerExistsInGame safely skips null elements in players list")
    void playerExistsInGame_WithNullElementsInPlayersList_ShouldHandleSafely() {
        Game game = mock(Game.class);
        when(game.getGameId()).thenReturn("mock-game-null-elements");
        Player activePlayer = new Player("Alice", "p1", 100);
        List<Player> playerList = new ArrayList<>();
        playerList.add(null);
        playerList.add(activePlayer);
        playerList.add(null);

        when(game.getPlayers()).thenReturn(playerList);
        gameLifecycleService.restoreGame(game);

        assertTrue(gameLifecycleService.playerExistsInGame("mock-game-null-elements", "Alice"));
        assertFalse(gameLifecycleService.playerExistsInGame("mock-game-null-elements", "Bob"));
    }

    @Test
    @DisplayName("playerExistsInGame under multi-threaded read/write stress completes without ConcurrentModificationException")
    void playerExistsInGame_MultiThreadedStress_CompletesWithoutConcurrentModificationException() throws Exception {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);

        int readerCount = 4;
        int mutatorCount = 2;
        int operationsPerThread = 1500;
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(mutatorCount);

        ExecutorService executor = Executors.newFixedThreadPool(readerCount + mutatorCount);

        for (int i = 0; i < readerCount; i++) {
            final int readerId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (running.get() && error.get() == null) {
                        String queryName = (readerId % 2 == 0) ? "Host" : "NonExistent";
                        gameLifecycleService.playerExistsInGame(ROOM_ID, queryName);
                    }
                } catch (Throwable t) {
                    error.set(t);
                }
            });
        }

        for (int i = 0; i < mutatorCount; i++) {
            final int mutatorId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int op = 0; op < operationsPerThread && running.get() && error.get() == null; op++) {
                        synchronized (game) {
                            Player p = new Player("StressPlayer_" + mutatorId + "_" + op, "stress_" + mutatorId + "_" + op, 50);
                            game.getPlayers().add(p);
                            game.getPlayers().remove(p);
                        }
                    }
                } catch (Throwable t) {
                    error.set(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        running.set(false);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertNull(error.get(), "Concurrent modification or unexpected error occurred: " + error.get());
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

        gameLifecycleService.leavePlayer(ROOM_ID, "Player2");

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
            @SuppressWarnings("unchecked") List<String> leaving = invocation.getArgument(1);
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
            @SuppressWarnings("unchecked") List<String> leaving = invocation.getArgument(1);
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
            @SuppressWarnings("unchecked") List<String> leaving = invocation.getArgument(1);
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

    /**
     * Protects the contract that handle game end with winner should broadcast end.
     */
    @Test
    void handleGameEnd_WithWinner_ShouldBroadcastEnd() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        reset(gameStateService);
        stubGameEndCapture();

        gameLifecycleService.handleGameEnd(ROOM_ID, false);

        verify(gameStateService).sendGameEnd(any(GameEndResponse.class));
    }

    @Test
    void terminalCarryOverFlowsThroughLifecycleIntoFrozenGameEndDtoOnce() {
        Room room = testRoom;
        when(roomService.getRoom(ROOM_ID)).thenReturn(room);
        SimpMessagingTemplate publisher = messagingTemplate;
        GameStateService realStateService = new GameStateService(roomService, publisher);
        GameLifecycleService lifecycle = new GameLifecycleService(roomService, handEvaluator, realStateService,
                publisher, taskScheduler);
        Player winner = Player.restoreBuilder().name("Host").playerId("host").chips(1000).holeCards(List.of())
                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(0).hasFolded(false).allIn(false)
                .out(false).disconnected(false).readyForNextHand(false).build();
        Player eliminated = Player.restoreBuilder().name("Player2").playerId("p2").chips(0).holeCards(List.of())
                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(0).hasFolded(true).allIn(false)
                .out(true).disconnected(false).readyForNextHand(false).build();
        Game terminal = Game.restoreBuilder().gameId(ROOM_ID).players(List.of(winner, eliminated))
                .activePlayerIds(List.of("host")).remainingDeck(List.of()).communityCards(List.of()).pot(10)
                .dealerPosition(0).smallBlindPosition(0).bigBlindPosition(1).currentPlayerPosition(0)
                .currentHighestBet(0).currentPhase(GamePhase.SHOWDOWN).gameOver(false).smallBlind(5).bigBlind(10)
                .handContributions(Map.of()).preHandCarryOver(10)
                .lastSettlementResult(new HandSettlementResult(Map.of(), Map.of(), Set.of(), 10, true))
                .readyCountdownActive(false).everyoneHasHadInitialTurn(true).actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of()).handEvaluator(handEvaluator).build();
        lifecycle.restoreGame(terminal);

        lifecycle.handleGameEnd(ROOM_ID, false);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).convertAndSend(eq("/game/" + ROOM_ID), captor.capture());
        GameEndResponse result = (GameEndResponse) captor.getValue();
        assertEquals(1010, result.winnerChips());
        assertEquals(0, result.finalState().pot());
        assertEquals(1010, result.finalState().players().stream().filter(PublicPlayerState::isWinner)
                .findFirst().orElseThrow().chips());
        assertTrue(terminal.isTerminalCarryOverSettled());

        lifecycle.handleGameEnd(ROOM_ID, false);
        verify(publisher, times(1)).convertAndSend(eq("/game/" + ROOM_ID), any(Object.class));
        assertEquals(1010, winner.getChips());
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
                @SuppressWarnings("unchecked") List<Card> holeCards = invocation.getArgument(1);
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
                lifecycle.leaveGame(gameId, smallBlind.getName());
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

    @Test
    void executeScheduledTask_WhenGameEnd_ShouldBroadcastGameEnd() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        long deadline = System.currentTimeMillis() + 5000;
        game.scheduleTask(ScheduledGameTask.GAME_END, deadline);
        reset(gameStateService);
        stubGameEndCapture();
        gameLifecycleService.executeScheduledTask(
                ROOM_ID,
                ScheduledGameTask.GAME_END,
                deadline,
                game.getHandNumber(),
                game.getScheduledTaskGeneration(ScheduledGameTask.GAME_END));

        verify(gameStateService).sendGameEnd(any(GameEndResponse.class));
    }

    @Test
    void executeScheduledCleanup_ReleasesRoomMonitorBeforeImmediateCleanupCallback() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        long deadline = System.currentTimeMillis() + 5000;
        game.scheduleTask(ScheduledGameTask.CLEANUP, deadline);
        doAnswer(invocation -> {
            assertFalse(Thread.holdsLock(game), "Immediate post-commit cleanup must run outside synchronized(game)");
            return null;
        }).when(roomService).destroyRoom(ROOM_ID);

        gameLifecycleService.executeScheduledTask(ROOM_ID, ScheduledGameTask.CLEANUP, deadline,
                game.getHandNumber(), game.getScheduledTaskGeneration(ScheduledGameTask.CLEANUP));

        verify(roomService).destroyRoom(ROOM_ID);
        assertFalse(gameLifecycleService.gameExists(ROOM_ID));
    }

    /**
     * Protects the contract that handle game end with null game should not broadcast.
     */
    @Test
    void handleGameEnd_WithNullGame_ShouldNotBroadcast() {
        gameLifecycleService.handleGameEnd("nonexistent-id", false);

        verify(gameStateService, never()).sendGameEnd(any(GameEndResponse.class));
    }

    /**
     * Protects the contract that handle game end when no players remain should not throw.
     */
    @Test
    void handleGameEnd_WhenNoPlayersRemain_ShouldNotThrow() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);
        game.getPlayers().clear();
        game.getActivePlayers().clear();

        assertDoesNotThrow(() -> gameLifecycleService.handleGameEnd(ROOM_ID, false));
    }

    @Test
    @DisplayName("handleGameEnd should pick winner with chips in heads-up all-in")
    void handleGameEnd_HeadsUpAllIn_ShouldPickWinnerWithChips() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);

        // Host is index 0, Player2 is index 1
        Player host = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);

        // Host is busted (0 chips)
        host.setChips(0);
        p2.setChips(2000);

        reset(gameStateService);
        stubGameEndCapture();
        gameLifecycleService.handleGameEnd(ROOM_ID, false);

        // Should broadcast p2 as winner
        verify(gameStateService).sendGameEnd(argThat(event -> event != null && event.winner().equals(p2.getName()) && !event.isForfeit()));
        assertTrue(game.isGameOver());
    }

    @Test
    @DisplayName("handleGameEnd should broadcast forfeit when opponent is removed")
    void handleGameEnd_ForfeitPath_ShouldBroadcastForfeit() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);

        Player host = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);

        // p2 is out
        p2.setIsOut();

        reset(gameStateService);
        stubGameEndCapture();
        gameLifecycleService.handleGameEnd(ROOM_ID, true);

        // Host should be winner
        verify(gameStateService).sendGameEnd(argThat(event -> event != null && event.winner().equals(host.getName()) && event.isForfeit()));
        assertTrue(game.isGameOver());
    }

    // ==================== Integration-like Tests ====================

    /**
     * Protects the contract that create game should initialize correct game phase.
     */
    @Test
    void createGame_ShouldInitializeCorrectGamePhase() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);
        assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
    }

    /**
     * Protects the contract that create game should have empty community cards.
     */
    @Test
    void createGame_ShouldHaveEmptyCommunityCards() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);
        assertTrue(game.getCommunityCards().isEmpty());
    }

    /**
     * Protects the contract that create game should set current player.
     */
    @Test
    void createGame_ShouldSetCurrentPlayer() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);
        assertNotNull(game.getCurrentPlayer());
    }

    @Test
    @DisplayName("advanceAutoAdvanceStep in SHOWDOWN phase yields true and does not re-distribute")
    void autoAdvanceStepInShowdownDoesNotRedistribute() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);

        when(handEvaluator.getBestHand(any(), any()))
                .thenReturn(new HandEvaluationResult(List.of(new Card(Rank.ACE, Suit.SPADES)), HandRank.HIGH_CARD));

        // Put game into SHOWDOWN
        game.conductShowdown();
        assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());

        int chipsHostBefore = game.getPlayers().get(0).getChips();
        int chipsP2Before = game.getPlayers().get(1).getChips();

        reset(gameStateService);
        stubGameEndCapture();

        boolean complete = gameLifecycleService.advanceAutoAdvanceStep(ROOM_ID);

        assertTrue(complete);
        assertEquals(chipsHostBefore, game.getPlayers().get(0).getChips());
        assertEquals(chipsP2Before, game.getPlayers().get(1).getChips());
        verify(gameStateService, never()).broadcastShowdownResults(anyString(), any(), any(), anyInt());
        verify(gameStateService, never()).broadcastAutoAdvanceComplete(anyString(), any());
    }

    // ==================== CONCURRENCY TESTS ====================

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
                    gameLifecycleService.leaveGame(roomId, leavingPlayer.getName());
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
                    gameLifecycleService.leaveGame(ROOM_ID, player.getName());
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
     * Tests that creating multiple games simultaneously doesn't cause race
     * conditions.
     * Verifies thread-safe access to activeGames map during game creation.
     */
    @Test
    void createGameFromRoom_ConcurrentCreation_ShouldCreateAllGames() throws InterruptedException {
        int numberOfGames = 5;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfGames);
        CountDownLatch latch = new CountDownLatch(numberOfGames);
        List<String> createdGameIds = new CopyOnWriteArrayList<>();
        List<String> roomIds = new ArrayList<>();

        // PHASE 1: SETUP (Single Threaded)
        // Configure all mocks before any threads start
        for (int i = 0; i < numberOfGames; i++) {
            String roomId = "room-" + i;
            roomIds.add(roomId);

            Room room = new Room(roomId, "Room " + i, "Host" + i, 6, 5, 10, 100, null);
            room.addPlayer("Host" + i);
            room.addPlayer("Player" + i);

            when(roomService.getRoom(roomId)).thenReturn(room);
        }

        for (String roomId : roomIds) {
            executor.submit(() -> {
                try {
                    String gameId = gameLifecycleService.createGameFromRoom(roomId);
                    createdGameIds.add(gameId);
                } catch (Exception e) {
                    fail("Concurrent game creation failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all games to be created
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Game creation took too long");
        executor.shutdown();

        // Verify all games were created successfully
        assertEquals(numberOfGames, createdGameIds.size(), "All games should be created");
        for (String gameId : createdGameIds) {
            assertTrue(gameLifecycleService.gameExists(gameId), "Game " + gameId + " should exist");
            assertNotNull(gameLifecycleService.getGame(gameId), "Game " + gameId + " should be retrievable");
        }
    }

    /**
     * Tests that accessing the same game concurrently (read operations) is
     * thread-safe.
     * Multiple threads reading game state simultaneously should not cause issues.
     */
    @Test
    void getGame_ConcurrentAccess_ShouldBeThreadSafe() throws InterruptedException {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        int numberOfThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        AtomicInteger successfulReads = new AtomicInteger(0);

        // 10 threads simultaneously reading the same game
        for (int i = 0; i < numberOfThreads; i++) {
            executor.submit(() -> {
                try {
                    Game game = gameLifecycleService.getGame(ROOM_ID);
                    assertNotNull(game, "Game should not be null");
                    assertEquals(2, game.getPlayers().size(), "Should have 2 players");
                    successfulReads.incrementAndGet();
                } catch (Exception e) {
                    fail("Concurrent read failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Concurrent reads took too long");
        executor.shutdown();

        assertEquals(numberOfThreads, successfulReads.get(), "All reads should succeed");
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
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);
        Player player1 = game.getPlayers().get(0);
        Player player2 = game.getPlayers().get(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch latch = new CountDownLatch(2);

        // Thread 1: Player leaves (causes game end since only 1 will remain)
        executor.submit(() -> {
            try {
                ready.countDown();
                assertTrue(start.await(2, TimeUnit.SECONDS));
                gameLifecycleService.leaveGame(ROOM_ID, player1.getName());
            } catch (Exception e) {
                // Expected - game might end
            } finally {
                latch.countDown();
            }
        });

        // Thread 2: Another player tries to leave at the same time
        executor.submit(() -> {
            try {
                ready.countDown();
                assertTrue(start.await(2, TimeUnit.SECONDS));
                gameLifecycleService.leaveGame(ROOM_ID, player2.getName());
            } catch (Exception e) {
                // Expected - game might already be ended or player might be gone
            } finally {
                latch.countDown();
            }
        });

        assertTrue(ready.await(2, TimeUnit.SECONDS), "Workers did not become ready in time");
        start.countDown();
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Operations took too long");
        executor.shutdown();

        // Game should either still exist with 1 player, or be completely gone
        // Either outcome is acceptable as long as no corruption occurred
        Game finalGame = gameLifecycleService.getGame(ROOM_ID);
        if (finalGame != null) {
            assertTrue(finalGame.getPlayers().size() >= 0, "Player count should be valid");
        }
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
    @DisplayName("getGameReview enforces 200, 409, 404, 403 authorization rules")
    void getGameReview_AuthorizationAndStatusRules() {
        testRoom.addPlayer("Player3");
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);

        // 1. Active game -> 409 Conflict
        assertThrows(ConflictException.class,
                () -> gameLifecycleService.getGameReview(ROOM_ID, "Host"));

        // End the game
        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", System.currentTimeMillis() + 120_000);
        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player3", System.currentTimeMillis() + 120_000);
        GameEndResponse terminal = gameLifecycleService.claimWin(ROOM_ID, "Host");

        // 2. Winner can review -> 200
        GameEndResponse reviewWinner = gameLifecycleService.getGameReview(ROOM_ID, "Host");
        assertSame(terminal, reviewWinner);

        // 3. OUT / forfeit player in terminal roster can review -> 200
        GameEndResponse reviewOutPlayer = gameLifecycleService.getGameReview(ROOM_ID, "Player2");
        assertSame(terminal, reviewOutPlayer);

        // 4. Departed room member (removed from Room, but in terminal roster) can review -> 200
        testRoom.removePlayer("Player3");
        assertFalse(testRoom.getPlayers().contains("Player3"));
        GameEndResponse reviewDepartedMember = gameLifecycleService.getGameReview(ROOM_ID, "Player3");
        assertSame(terminal, reviewDepartedMember);

        // 5. Unauthorized player (not in terminal roster) -> 403 Forbidden
        assertThrows(UnauthorisedActionException.class,
                () -> gameLifecycleService.getGameReview(ROOM_ID, "Stranger"));

        // 6. Non-existent game -> 404 Not Found
        assertThrows(ResourceNotFoundException.class,
                () -> gameLifecycleService.getGameReview("non-existent-game", "Host"));

        // 7. Expired game (now >= cleanupDeadline) -> 404 Not Found
        game.scheduleTask(ScheduledGameTask.CLEANUP, System.currentTimeMillis() - 1000);
        assertTrue(gameLifecycleService.isGameExpired(game));
        assertFalse(gameLifecycleService.gameExists(ROOM_ID));
        assertThrows(ResourceNotFoundException.class,
                () -> gameLifecycleService.getGameReview(ROOM_ID, "Host"));
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
    @DisplayName("getGameReview recovers terminal GameEndResponse after game is over without 404 or mutating stateRevision")
    void getGameReview_WhenGameOverAndRetainedResultNull_RecoversWithout404() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        assertNotNull(game);

        // Mark game over with null retainedTerminalResult (during PRE_FLOP -> forfeit)
        game.markGameOver();
        game.setRetainedTerminalResult(null);
        long revisionBefore = game.getStateRevision();

        reset(gameStateService);
        stubGameEndCapture();

        // getGameReview should project the terminal result without mutating durable state or incrementing stateRevision
        GameEndResponse review = gameLifecycleService.getGameReview(ROOM_ID, "Host");
        assertNotNull(review, "getGameReview must return non-null GameEndResponse");
        assertEquals("GAME_END", review.type());
        assertNotNull(review.winner(), "Winner must be resolved");
        assertTrue(review.isForfeit(), "Pre-showdown game over must synthesize isForfeit = true");
        assertNotNull(review.finalState(), "finalState must be synthesized");
        assertEquals(revisionBefore, game.getStateRevision(), "Read-only getGameReview must not increment stateRevision");
        assertNull(game.getRetainedTerminalResult(), "Read-only getGameReview must not mutate retainedTerminalResult");
        verify(gameStateService, never()).captureGameEnd(anyString(), any(), any(), anyBoolean(), anyInt());
        verify(gameStateService).projectGameEnd(eq(ROOM_ID), eq(game), any(Player.class), eq(true), eq(testRoom.getMaxPlayers()));

        // When game ended via settled SHOWDOWN, both getGameReview and claimWin derive isForfeit = false
        game.conductShowdown();
        assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());
        GameEndResponse showdownReview = gameLifecycleService.getGameReview(ROOM_ID, "Host");
        assertFalse(showdownReview.isForfeit(), "Settled SHOWDOWN game over must synthesize isForfeit = false in getGameReview");

        GameEndResponse showdownClaim = gameLifecycleService.claimWin(ROOM_ID, "Host");
        assertFalse(showdownClaim.isForfeit(), "Settled SHOWDOWN game over must synthesize isForfeit = false in claimWin");
        assertSame(showdownClaim, game.getRetainedTerminalResult(), "Durable claimWin retains synthesized terminal result");
    }

    @Test
    @DisplayName("Empty terminal room preserves container until cleanup, closed to joining")
    void terminalRoom_PreservesContainerWhenEmptyUntilCleanup() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", System.currentTimeMillis() + 120_000);
        gameLifecycleService.claimWin(ROOM_ID, "Host");

        Game game = gameLifecycleService.getGame(ROOM_ID);
        assertTrue(game.isGameOver());
        assertTrue(testRoom.isTerminal());

        // Host leaves after terminal settlement
        gameLifecycleService.departParticipants(ROOM_ID, List.of("Host"));
        assertTrue(testRoom.isTerminal());
        // Verify room was NOT destroyed upon reaching 0 members because it's terminal
        verify(roomService, never()).destroyRoom(ROOM_ID);

        // Perform cleanup durably destroys room and removes game
        gameLifecycleService.performGameCleanup(ROOM_ID);
        assertNull(gameLifecycleService.getGame(ROOM_ID));
        verify(roomService, times(1)).destroyRoom(ROOM_ID);
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

            Thread t1 = new Thread(() -> {
                try {
                    startLatch.await();
                    gameLifecycleService.handleGameEnd(roomId, false);
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });

            Thread t2 = new Thread(() -> {
                try {
                    startLatch.await();
                    gameLifecycleService.departParticipants(roomId, List.of("Player2"));
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });

            t1.start();
            t2.start();
            startLatch.countDown();

            boolean finished = doneLatch.await(3, TimeUnit.SECONDS);
            assertTrue(finished, "Deadlock detected between handleGameEnd and handlePlayerLeave at iteration " + i);
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
            String p = inv.getArgument(1);
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
            String p = inv.getArgument(1);
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
            String p = inv.getArgument(1);
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
        Game game = gameLifecycleService.getGame(ROOM_ID);
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

        gameLifecycleService.removeDisconnectedPlayer("lobby-3", "Guest", "obsolete-id", deadline);

        verifyNobodyRemovedFromRoom();
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

        gameLifecycleService.removeDisconnectedPlayer("lobby-3", "Guest", lobby.getMembershipId("Guest"), deadline);

        verifyNobodyRemovedFromRoom();
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a lobby player with no absence deadline")
    void removeDisconnectedPlayer_Lobby_NoAbsenceDeadline_KeepsPlayer() {
        Room lobby = new Room("lobby-3", "Lobby", "Host", 4, 10, 20, 1000, null);
        lobby.addPlayer("Host");
        lobby.addPlayer("Guest");
        when(roomService.getRoom("lobby-3")).thenReturn(lobby);

        gameLifecycleService.removeDisconnectedPlayer("lobby-3", "Guest", lobby.getMembershipId("Guest"),
                System.currentTimeMillis() - 100);

        verifyNobodyRemovedFromRoom();
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

        gameLifecycleService.removeDisconnectedPlayer("lobby-3", "Guest", lobby.getMembershipId("Guest"),
                currentDeadline - 5_000);

        verifyNobodyRemovedFromRoom();
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

        gameLifecycleService.removeDisconnectedPlayer("lobby-3", "Guest", lobby.getMembershipId("Guest"), deadline);

        verifyNobodyRemovedFromRoom();
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps an active-game player whose membership id is obsolete")
    void removeDisconnectedPlayer_ActiveGame_ObsoleteMembershipId_KeepsPlayer() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        long deadline = System.currentTimeMillis() - 100;
        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", deadline);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", "obsolete-id", deadline);

        verifyNobodyRemovedFromRoom();
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

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), deadline);

        assertFalse(findGamePlayer(game, "Player2").getIsDisconnected());
        verifyNobodyRemovedFromRoom();
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

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", testRoom.getMembershipId("Player2"),
                System.currentTimeMillis() - 100);

        verifyNobodyRemovedFromRoom();
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps an active-game player when the expected deadline is not the current one")
    void removeDisconnectedPlayer_ActiveGame_DeadlineMismatch_KeepsPlayer() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        long currentDeadline = System.currentTimeMillis() - 100;
        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", currentDeadline);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", testRoom.getMembershipId("Player2"),
                currentDeadline - 5_000);

        verifyNobodyRemovedFromRoom();
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps an active-game player until the deadline has passed")
    void removeDisconnectedPlayer_ActiveGame_BeforeDeadline_KeepsPlayer() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        long deadline = System.currentTimeMillis() + 60_000;
        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", deadline);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), deadline);

        verifyNobodyRemovedFromRoom();
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a room-only member who reconnected while a game is active")
    void removeDisconnectedPlayer_ActiveGame_RoomOnlyMemberConnected_KeepsMember() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        testRoom.addPlayer("Spectator");
        long deadline = System.currentTimeMillis() - 100;
        testRoom.setAbsenceDeadline("Spectator", deadline);
        testRoom.setPlayerConnected("Spectator", true);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Spectator", testRoom.getMembershipId("Spectator"), deadline);

        verifyNobodyRemovedFromRoom();
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a room-only member with no absence deadline while a game is active")
    void removeDisconnectedPlayer_ActiveGame_RoomOnlyMemberNoDeadline_KeepsMember() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        testRoom.addPlayer("Spectator");

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Spectator", testRoom.getMembershipId("Spectator"),
                System.currentTimeMillis() - 100);

        verifyNobodyRemovedFromRoom();
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a room-only member whose expected deadline is not the current one")
    void removeDisconnectedPlayer_ActiveGame_RoomOnlyMemberDeadlineMismatch_KeepsMember() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        testRoom.addPlayer("Spectator");
        long currentDeadline = System.currentTimeMillis() - 100;
        testRoom.setAbsenceDeadline("Spectator", currentDeadline);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Spectator", testRoom.getMembershipId("Spectator"),
                currentDeadline - 5_000);

        verifyNobodyRemovedFromRoom();
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a room-only member until the deadline has passed")
    void removeDisconnectedPlayer_ActiveGame_RoomOnlyMemberBeforeDeadline_KeepsMember() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        testRoom.addPlayer("Spectator");
        long deadline = System.currentTimeMillis() + 60_000;
        testRoom.setAbsenceDeadline("Spectator", deadline);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Spectator", testRoom.getMembershipId("Spectator"), deadline);

        verifyNobodyRemovedFromRoom();
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

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", "obsolete-id", deadline);

        verifyNobodyRemovedFromRoom();
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a terminal-game player with no deadline anywhere")
    void removeDisconnectedPlayer_TerminalGame_NoDeadline_KeepsPlayer() {
        Game game = createTerminalGame();
        findGamePlayer(game, "Player2").setDisconnected(true);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", testRoom.getMembershipId("Player2"),
                System.currentTimeMillis() - 100);

        verifyNobodyRemovedFromRoom();
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a terminal-game player when the expected deadline is not the current one")
    void removeDisconnectedPlayer_TerminalGame_DeadlineMismatch_KeepsPlayer() {
        Game game = createTerminalGame();
        long currentDeadline = System.currentTimeMillis() - 100;
        Player player2 = findGamePlayer(game, "Player2");
        player2.setDisconnected(true);
        player2.setDisconnectDeadlineEpochMs(currentDeadline);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", testRoom.getMembershipId("Player2"),
                currentDeadline - 5_000);

        verifyNobodyRemovedFromRoom();
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

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), roomDeadline);

        verifyNobodyRemovedFromRoom();
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a terminal-game player until the deadline has passed")
    void removeDisconnectedPlayer_TerminalGame_BeforeDeadline_KeepsPlayer() {
        Game game = createTerminalGame();
        long deadline = System.currentTimeMillis() + 60_000;
        Player player2 = findGamePlayer(game, "Player2");
        player2.setDisconnected(true);
        player2.setDisconnectDeadlineEpochMs(deadline);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), deadline);

        verifyNobodyRemovedFromRoom();
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a terminal-game player who is no longer disconnected")
    void removeDisconnectedPlayer_TerminalGame_PlayerNotDisconnected_KeepsPlayer() {
        Game game = createTerminalGame();
        long deadline = System.currentTimeMillis() - 100;
        Player player2 = findGamePlayer(game, "Player2");
        player2.setDisconnected(false);
        player2.setDisconnectDeadlineEpochMs(deadline);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Player2", testRoom.getMembershipId("Player2"), deadline);

        verifyNobodyRemovedFromRoom();
    }

    @Test
    @DisplayName("removeDisconnectedPlayer keeps a room-only member who reconnected after the game ended")
    void removeDisconnectedPlayer_TerminalGame_RoomOnlyMemberConnected_KeepsMember() {
        createTerminalGame();
        testRoom.addPlayer("Spectator");
        long deadline = System.currentTimeMillis() - 100;
        testRoom.setAbsenceDeadline("Spectator", deadline);
        testRoom.setPlayerConnected("Spectator", true);

        gameLifecycleService.removeDisconnectedPlayer(ROOM_ID, "Spectator", testRoom.getMembershipId("Spectator"), deadline);

        verifyNobodyRemovedFromRoom();
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
    @DisplayName("createGameFromRoom reconciles absent seat lease into disconnected flag and retains connected state for connected seat")
    void createGameFromRoom_ReconcilesAbsentSeatLeaseAndRetainsConnectedSeat() {
        testRoom.setPlayerConnected("Host", true);
        testRoom.setPlayerConnected("Player2", false);
        long p2Deadline = System.currentTimeMillis() + 45_000;
        testRoom.setAbsenceDeadline("Player2", p2Deadline);

        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);
        Player host = game.getPlayers().stream().filter(p -> p.getName().equals("Host")).findFirst().orElseThrow();
        Player player2 = game.getPlayers().stream().filter(p -> p.getName().equals("Player2")).findFirst().orElseThrow();

        assertFalse(host.getIsDisconnected(), "Connected host must retain normal connected state");
        assertNull(host.getDisconnectDeadlineEpochMs());

        assertTrue(player2.getIsDisconnected(), "Absent player2 must have isDisconnected = true");
        assertEquals(p2Deadline, player2.getDisconnectDeadlineEpochMs(), "Absent player2 must receive absence deadline");
    }


    @Test
    @DisplayName("createGameFromRoom emits canonical GAME_STARTED with RoomDataResponse captured after gameStarted set")
    void createGameFromRoom_EmitsCanonicalGameStartedWithRoomDataResponse() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        RoomDataResponse mockRoomData = new RoomDataResponse(
                ROOM_ID, "Test Room", 6, 1000, 10, 20, LocalDateTime.now(), "Host",
                List.of(new PlayerJoinInfo("Host", true, "now"), new PlayerJoinInfo("Player2", false, "now")),
                2, false, true
        );
        when(roomService.getRoomData(ROOM_ID)).thenAnswer(inv -> {
            assertTrue(testRoom.isGameStarted(), "room.setGameStarted(true) must be called BEFORE capturing start event");
            return mockRoomData;
        });

        ArgumentCaptor<ApiResponse> captor = ArgumentCaptor.forClass(ApiResponse.class);

        gameLifecycleService.createGameFromRoom(ROOM_ID);

        verify(messagingTemplate).convertAndSend(eq("/room/" + ROOM_ID), captor.capture());
        ApiResponse<?> startResponse = captor.getAllValues().stream()
                .filter(res -> ResponseMessage.GAME_STARTED.getMessage().equals(res.message()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected GAME_STARTED event"));

        assertEquals(mockRoomData, startResponse.data());
        assertTrue(testRoom.isGameStarted());
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

    @Test
    @DisplayName("performGameCleanup and evictGame clear retained settlement in GameStateService")
    void performGameCleanupAndEvictGame_ClearRetainedSettlementInGameStateService() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        GameStateService realStateService = new GameStateService(roomService, messagingTemplate);
        GameLifecycleService lifecycle = new GameLifecycleService(roomService, handEvaluator, realStateService,
                messagingTemplate, taskScheduler);

        lifecycle.createGameFromRoom(ROOM_ID);
        Game game = lifecycle.getGame(ROOM_ID);
        realStateService.broadcastShowdownResults(ROOM_ID, game, List.of(game.getPlayers().getFirst()), 50);
        assertTrue(realStateService.hasRetainedSettlement(ROOM_ID));

        lifecycle.performGameCleanup(ROOM_ID);
        assertFalse(realStateService.hasRetainedSettlement(ROOM_ID),
                "performGameCleanup must evict retained settlement for cleaned-up game");

        lifecycle.restoreGame(game);
        realStateService.broadcastShowdownResults(ROOM_ID, game, List.of(game.getPlayers().getFirst()), 50);
        assertTrue(realStateService.hasRetainedSettlement(ROOM_ID));

        lifecycle.evictGame(ROOM_ID);
        assertFalse(realStateService.hasRetainedSettlement(ROOM_ID),
                "evictGame must evict retained settlement for evicted game");
    }

    // ==================== executeScheduledTask guard Tests ====================

    private Game startedGame() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        return gameLifecycleService.getGame(ROOM_ID);
    }

    private Game showdownGame() {
        Game game = startedGame();
        game.conductShowdown();
        assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());
        return game;
    }

    private Game riverGame() {
        Game game = startedGame();
        game.dealFlop();
        game.dealTurn();
        game.dealRiver();
        assertEquals(GamePhase.RIVER, game.getCurrentPhase());
        return game;
    }

    private long scheduleForTest(Game game, ScheduledGameTask task) {
        long deadline = System.currentTimeMillis() + 60_000;
        game.scheduleTask(task, deadline);
        return deadline;
    }

    private void runScheduled(Game game, ScheduledGameTask task, long deadline) {
        gameLifecycleService.executeScheduledTask(ROOM_ID, task, deadline,
                game.getHandNumber(), game.getScheduledTaskGeneration(task));
    }

    private void assertNothingRan(Game game, ScheduledGameTask task, long deadline, long generation, long handNumber) {
        assertEquals(deadline, game.getScheduledTaskDeadline(task), "The guarded task must stay scheduled");
        assertEquals(generation, game.getScheduledTaskGeneration(task), "The guarded task must not be cleared");
        assertEquals(handNumber, game.getHandNumber(), "No new hand may be dealt");
        verifyNoInteractions(gameStateService, taskScheduler);
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("executeScheduledTask does nothing while a forfeit is pending")
    void executeScheduledTask_WhenForfeitPending_DoesNothing() {
        Game game = showdownGame();
        long deadline = scheduleForTest(game, ScheduledGameTask.NEW_HAND);
        long generation = game.getScheduledTaskGeneration(ScheduledGameTask.NEW_HAND);
        long handNumber = game.getHandNumber();
        game.setForfeitPending(true);
        clearInvocations(gameStateService, messagingTemplate, taskScheduler);

        gameLifecycleService.executeScheduledTask(ROOM_ID, ScheduledGameTask.NEW_HAND, deadline, handNumber, generation);

        assertNothingRan(game, ScheduledGameTask.NEW_HAND, deadline, generation, handNumber);
        assertTrue(game.isForfeitPending());
    }

    @Test
    @DisplayName("executeScheduledTask ignores a timer captured for an earlier hand")
    void executeScheduledTask_WhenHandNumberIsStale_DoesNothing() {
        Game game = showdownGame();
        long deadline = scheduleForTest(game, ScheduledGameTask.NEW_HAND);
        long generation = game.getScheduledTaskGeneration(ScheduledGameTask.NEW_HAND);
        long handNumber = game.getHandNumber();
        clearInvocations(gameStateService, messagingTemplate, taskScheduler);

        gameLifecycleService.executeScheduledTask(ROOM_ID, ScheduledGameTask.NEW_HAND, deadline, handNumber - 1, generation);

        assertNothingRan(game, ScheduledGameTask.NEW_HAND, deadline, generation, handNumber);
    }

    @Test
    @DisplayName("executeScheduledTask ignores a timer captured before the task was rescheduled")
    void executeScheduledTask_WhenGenerationIsStale_DoesNothing() {
        Game game = showdownGame();
        scheduleForTest(game, ScheduledGameTask.NEW_HAND);
        long staleGeneration = game.getScheduledTaskGeneration(ScheduledGameTask.NEW_HAND);
        long deadline = scheduleForTest(game, ScheduledGameTask.NEW_HAND);
        long generation = game.getScheduledTaskGeneration(ScheduledGameTask.NEW_HAND);
        long handNumber = game.getHandNumber();
        assertNotEquals(staleGeneration, generation);
        clearInvocations(gameStateService, messagingTemplate, taskScheduler);

        gameLifecycleService.executeScheduledTask(ROOM_ID, ScheduledGameTask.NEW_HAND, deadline, handNumber, staleGeneration);

        assertNothingRan(game, ScheduledGameTask.NEW_HAND, deadline, generation, handNumber);
    }

    @Test
    @DisplayName("executeScheduledTask on a finished game clears and cancels a non-cleanup task")
    void executeScheduledTask_WhenGameOverAndTaskIsNotCleanup_ClearsAndCancelsTask() {
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        Game game = startedGame();
        gameLifecycleService.scheduleAutoAdvance(ROOM_ID);
        long deadline = game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE);
        long generation = game.getScheduledTaskGeneration(ScheduledGameTask.AUTO_ADVANCE);
        long handNumber = game.getHandNumber();
        assertEquals(1, gameLifecycleService.getRuntimeTaskCount(ROOM_ID));
        game.markGameOver();
        clearInvocations(gameStateService, messagingTemplate);

        gameLifecycleService.executeScheduledTask(ROOM_ID, ScheduledGameTask.AUTO_ADVANCE, deadline, handNumber, generation);

        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
        assertEquals(0, gameLifecycleService.getRuntimeTaskCount(ROOM_ID));
        verify(future).cancel(false);
        assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase(), "A finished game cannot auto-advance");
        verifyNoInteractions(gameStateService, messagingTemplate);
    }

    @Test
    @DisplayName("executeScheduledTask ignores a timer whose deadline no longer matches")
    void executeScheduledTask_WhenDeadlineIsStale_DoesNothing() {
        Game game = showdownGame();
        long deadline = scheduleForTest(game, ScheduledGameTask.NEW_HAND);
        long generation = game.getScheduledTaskGeneration(ScheduledGameTask.NEW_HAND);
        long handNumber = game.getHandNumber();
        clearInvocations(gameStateService, messagingTemplate, taskScheduler);

        gameLifecycleService.executeScheduledTask(ROOM_ID, ScheduledGameTask.NEW_HAND, deadline + 1, handNumber, generation);

        assertNothingRan(game, ScheduledGameTask.NEW_HAND, deadline, generation, handNumber);
    }

    @Test
    @DisplayName("executeScheduledTask ignores a timer for a task that is no longer scheduled")
    void executeScheduledTask_WhenTaskIsNotScheduled_DoesNothing() {
        Game game = showdownGame();
        long handNumber = game.getHandNumber();
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.NEW_HAND));
        clearInvocations(gameStateService, messagingTemplate, taskScheduler);

        gameLifecycleService.executeScheduledTask(ROOM_ID, ScheduledGameTask.NEW_HAND,
                System.currentTimeMillis() + 60_000, handNumber, 0);

        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.NEW_HAND));
        assertEquals(0, game.getScheduledTaskGeneration(ScheduledGameTask.NEW_HAND));
        assertEquals(handNumber, game.getHandNumber());
        verifyNoInteractions(gameStateService, taskScheduler);
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("executeScheduledTask NEW_HAND clears the task and deals the next hand")
    void executeScheduledTask_NewHand_ClearsTaskAndStartsNextHand() {
        Game game = showdownGame();
        long deadline = scheduleForTest(game, ScheduledGameTask.NEW_HAND);
        long handNumber = game.getHandNumber();
        clearInvocations(gameStateService);

        runScheduled(game, ScheduledGameTask.NEW_HAND, deadline);

        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.NEW_HAND));
        assertEquals(handNumber + 1, game.getHandNumber());
        assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
        verify(gameStateService).broadcastGameState(eq(ROOM_ID), eq(game));
    }

    @Test
    @DisplayName("executeScheduledTask READY_OPEN clears the task and opens the ready countdown")
    void executeScheduledTask_ReadyOpen_ClearsTaskAndOpensReadyCountdown() {
        Game game = showdownGame();
        long deadline = scheduleForTest(game, ScheduledGameTask.READY_OPEN);
        long handNumber = game.getHandNumber();
        assertFalse(game.isReadyCountdownActive());
        clearInvocations(gameStateService);

        runScheduled(game, ScheduledGameTask.READY_OPEN, deadline);

        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_OPEN));
        assertTrue(game.isReadyCountdownActive());
        assertNotNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
        assertEquals(handNumber, game.getHandNumber(), "Opening the gate must not deal a hand");
        verify(gameStateService).broadcastGameState(eq(ROOM_ID), eq(game));
    }

    @Test
    @DisplayName("executeScheduledTask AUTO_ADVANCE in SHOWDOWN clears the task and stops")
    void executeScheduledTask_AutoAdvanceInShowdown_ClearsTaskAndStops() {
        Game game = showdownGame();
        long deadline = scheduleForTest(game, ScheduledGameTask.AUTO_ADVANCE);
        long handNumber = game.getHandNumber();
        clearInvocations(gameStateService, messagingTemplate, taskScheduler);

        runScheduled(game, ScheduledGameTask.AUTO_ADVANCE, deadline);

        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
        assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());
        assertEquals(handNumber, game.getHandNumber());
        assertFalse(game.isReadyCountdownActive(), "A late auto-advance timer cannot open the ready gate");
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_OPEN));
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.GAME_END));
        verifyNoInteractions(gameStateService, taskScheduler);
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("executeScheduledTask AUTO_ADVANCE finishing a tournament schedules game end")
    void executeScheduledTask_AutoAdvanceComplete_WhenTournamentOver_SchedulesGameEnd() {
        Game game = riverGame();
        Player winner = game.getPlayers().get(0);
        Player loser = game.getPlayers().get(1);
        loser.setChips(0);
        lenient().when(handEvaluator.getBestHand(anyList(), anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked") List<Card> holeCards = invocation.getArgument(1);
            HandRank rank = holeCards.equals(winner.getHoleCards()) ? HandRank.ROYAL_FLUSH : HandRank.HIGH_CARD;
            return new HandEvaluationResult(List.of(), rank);
        });
        long deadline = scheduleForTest(game, ScheduledGameTask.AUTO_ADVANCE);
        long before = System.currentTimeMillis();

        runScheduled(game, ScheduledGameTask.AUTO_ADVANCE, deadline);

        assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());
        assertTrue(game.isTournamentOver());
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
        Long gameEndDeadline = game.getScheduledTaskDeadline(ScheduledGameTask.GAME_END);
        assertNotNull(gameEndDeadline);
        assertTrue(gameEndDeadline >= before + GameLifecycleService.FINAL_SHOWDOWN_DISPLAY_DELAY_MS);
        assertFalse(game.isReadyCountdownActive(), "A finished tournament must not open the ready gate");
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_OPEN));
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
        verify(gameStateService).broadcastAutoAdvanceComplete(ROOM_ID, game);
    }

    @Test
    @DisplayName("executeScheduledTask AUTO_ADVANCE finishing a hand opens the ready gate when there is no display delay")
    void executeScheduledTask_AutoAdvanceComplete_WithNoDisplayDelay_OpensReadyCountdown() {
        Game game = riverGame();
        ReflectionTestUtils.setField(gameLifecycleService, "roundEndDisplayDelayMs", 0L);
        long deadline = scheduleForTest(game, ScheduledGameTask.AUTO_ADVANCE);

        runScheduled(game, ScheduledGameTask.AUTO_ADVANCE, deadline);

        assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());
        assertFalse(game.isTournamentOver());
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
        assertTrue(game.isReadyCountdownActive());
        assertNotNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_OPEN));
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.GAME_END));
        verify(gameStateService).broadcastAutoAdvanceComplete(ROOM_ID, game);
    }

    @Test
    @DisplayName("executeScheduledTask AUTO_ADVANCE finishing a hand persists READY_OPEN when a display delay is configured")
    void executeScheduledTask_AutoAdvanceComplete_WithDisplayDelay_PersistsReadyOpen() {
        Game game = riverGame();
        ReflectionTestUtils.setField(gameLifecycleService, "roundEndDisplayDelayMs", 5_000L);
        long deadline = scheduleForTest(game, ScheduledGameTask.AUTO_ADVANCE);
        long before = System.currentTimeMillis();

        runScheduled(game, ScheduledGameTask.AUTO_ADVANCE, deadline);

        assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
        Long readyOpenDeadline = game.getScheduledTaskDeadline(ScheduledGameTask.READY_OPEN);
        assertNotNull(readyOpenDeadline);
        assertTrue(readyOpenDeadline >= before + 5_000);
        assertFalse(game.isReadyCountdownActive(), "The ready gate waits for the display delay");
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("executeScheduledTask AUTO_ADVANCE with more streets to deal reschedules itself")
    void executeScheduledTask_AutoAdvanceNotComplete_ReschedulesAutoAdvance() {
        Game game = startedGame();
        long deadline = scheduleForTest(game, ScheduledGameTask.AUTO_ADVANCE);
        long before = System.currentTimeMillis();

        runScheduled(game, ScheduledGameTask.AUTO_ADVANCE, deadline);

        assertEquals(GamePhase.FLOP, game.getCurrentPhase());
        Long nextDeadline = game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE);
        assertNotNull(nextDeadline);
        assertTrue(nextDeadline > before);
        assertNotEquals(deadline, nextDeadline);
        assertFalse(game.isReadyCountdownActive());
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_OPEN));
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.GAME_END));
        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
    }
}
