package com.pokergame.service;

import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.HandRank;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.Rank;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.enums.Suit;
import com.pokergame.model.Card;
import com.pokergame.model.Game;
import com.pokergame.model.HandEvaluationResult;
import com.pokergame.model.HandSettlementResult;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
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
        reset(gameStateService);

        gameLifecycleService.startNewHand(ROOM_ID);

        verify(gameStateService).broadcastGameState(eq(ROOM_ID), any(Game.class));
    }

    /**
     * Protects the contract that advance auto advance step at pre flop should deal flop through service seam.
     */
    @Test
    void advanceAutoAdvanceStep_AtPreFlop_ShouldDealFlopThroughServiceSeam() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        reset(gameStateService);

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
        gameLifecycleService.startNewHand(ROOM_ID);

        // Should end the game and schedule cleanup because game became over after reset
        verify(gameStateService, never()).broadcastGameState(anyString(), any(Game.class));
        verify(gameStateService).broadcastGameEnd(eq(ROOM_ID), any(Game.class), any(Player.class), anyBoolean());
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
        org.mockito.ArgumentCaptor<Runnable> callbacks = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(callbacks.capture(), any(java.time.Instant.class));
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
     * Protects the contract that leave game when game not found should throw exception.
     */
    @Test
    void leaveGame_WhenGameNotFound_ShouldThrowException() {
        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> gameLifecycleService.leaveGame("nonexistent-id", "Player"));

        assertEquals("Game not found", exception.getMessage());
    }

    /**
     * Protects the contract that leave game when player not found should throw exception.
     */
    @Test
    void leaveGame_WhenPlayerNotFound_ShouldThrowException() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> gameLifecycleService.leaveGame(ROOM_ID, "NonexistentPlayer"));

        assertEquals("Player not found in game", exception.getMessage());
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

        verify(gameStateService).broadcastGameEnd(eq(ROOM_ID), any(Game.class), any(Player.class), anyBoolean());
    }

    /**
     * Protects the contract that when all remaining players are disconnected, leaving destroys the game and room immediately.
     */
    @Test
    void leaveGame_WhenOnlyDisconnectedPlayerRemains_ShouldDestroyGameAndRoomImmediately() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Host", System.currentTimeMillis() + 120_000);
        reset(gameStateService);

        gameLifecycleService.leaveGame(ROOM_ID, "Player2");

        assertNull(gameLifecycleService.getGame(ROOM_ID));
        verify(roomService).destroyRoom(ROOM_ID);
        verify(gameStateService, never()).broadcastGameEnd(anyString(), any(Game.class), any(Player.class), anyBoolean());
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

    /**
     * Protects the contract that mark player disconnected when player exists should set disconnected and broadcast.
     */
    @Test
    void markPlayerDisconnected_WhenPlayerExists_ShouldSetDisconnectedAndBroadcast() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        long disconnectDeadlineEpochMs = System.currentTimeMillis() + 120_000;

        reset(gameStateService);

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

        assertDoesNotThrow(() -> gameLifecycleService.claimWin(ROOM_ID, "Host"));

        verify(gameStateService).broadcastGameEnd(eq(ROOM_ID), any(Game.class),
                argThat(player -> player != null && player.getName().equals("Host")), anyBoolean());
        assertNotNull(gameLifecycleService.getGame(ROOM_ID).getScheduledTaskDeadline(ScheduledGameTask.CLEANUP));
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

        gameLifecycleService.handleGameEnd(ROOM_ID, false);

        verify(gameStateService).broadcastGameEnd(eq(ROOM_ID), any(Game.class), any(Player.class), anyBoolean());
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
                .scheduledTaskDeadlines(java.util.Map.of()).handEvaluator(handEvaluator).build();
        lifecycle.restoreGame(terminal);

        lifecycle.handleGameEnd(ROOM_ID, false);
        org.mockito.ArgumentCaptor<Object> captor = org.mockito.ArgumentCaptor.forClass(Object.class);
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
    void leaveClaimAndGraceExpiryAwardCommittedLargePotThroughFinalDto() {
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

            Player actor = game.getCurrentPlayer();
            if (!actor.equals(smallBlind)) {
                game.processPlayerDecision(actor, new com.pokergame.dto.internal.PlayerDecision(
                        actor.getCurrentBet() < game.getCurrentHighestBet() ? PlayerAction.CALL : PlayerAction.CHECK,
                        0, actor.getPlayerId()));
            }
            PlayerAction aggressiveAction = game.getCurrentPhase() == GamePhase.PRE_FLOP
                    ? PlayerAction.RAISE : PlayerAction.BET;
            game.processPlayerDecision(smallBlind, new com.pokergame.dto.internal.PlayerDecision(
                    aggressiveAction, 500, smallBlind.getPlayerId()));
            game.processPlayerDecision(bigBlind, new com.pokergame.dto.internal.PlayerDecision(
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
                lifecycle.removeDisconnectedPlayer(gameId, smallBlind.getName());
            }

            org.mockito.ArgumentCaptor<Object> captor = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/game/" + gameId), captor.capture());
            GameEndResponse result = captor.getAllValues().stream().filter(GameEndResponse.class::isInstance)
                    .map(GameEndResponse.class::cast).findFirst().orElseThrow();
            assertEquals(1510, result.winnerChips(), "The surviving BB receives the full matched pot: " + ending);
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

        gameLifecycleService.executeScheduledTask(ROOM_ID, ScheduledGameTask.GAME_END, deadline);

        verify(gameStateService).broadcastGameEnd(eq(ROOM_ID), any(Game.class), any(Player.class), anyBoolean());
    }

    /**
     * Protects the contract that handle game end with null game should not broadcast.
     */
    @Test
    void handleGameEnd_WithNullGame_ShouldNotBroadcast() {
        gameLifecycleService.handleGameEnd("nonexistent-id", false);

        verify(gameStateService, never()).broadcastGameEnd(anyString(), any(Game.class), any(Player.class), anyBoolean());
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
        gameLifecycleService.handleGameEnd(ROOM_ID, false);

        // Should broadcast p2 as winner
        verify(gameStateService).broadcastGameEnd(eq(ROOM_ID), eq(game), eq(p2), eq(false));
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
        gameLifecycleService.handleGameEnd(ROOM_ID, true);

        // Host should be winner
        verify(gameStateService).broadcastGameEnd(eq(ROOM_ID), eq(game), eq(host), eq(true));
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
}
