package com.pokergame.service;

import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.dto.response.ApiResponse;
import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.dto.internal.PlayerJoinInfo;
import com.pokergame.dto.internal.TurnOutcome;
import com.pokergame.enums.ResponseMessage;
import org.mockito.ArgumentCaptor;
import java.time.LocalDateTime;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import static com.pokergame.util.ConcurrentTestSupport.awaitBlockedOn;
import static com.pokergame.util.ConcurrentTestSupport.stopExecutor;
import static org.junit.jupiter.api.Assertions.*;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.mockito.Mockito.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Iterator;
import java.util.Spliterator;
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

    @Test
    void applyTurnOutcome_ShowdownWithoutDelay_OpensReadyCountdown() {
        Game game = createShowdownGame();
        TurnOutcome outcome = showdownOutcome(game);

        gameLifecycleService.applyTurnOutcome(ROOM_ID, game, outcome, true);

        assertTrue(game.isReadyCountdownActive());
        assertEquals(game.getReadyCountdownDeadlineEpochMs(),
                game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_OPEN));
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.GAME_END));
        verify(gameStateService).broadcastShowdownResults(ROOM_ID, game, outcome.winners(), outcome.winningsPerPlayer());
    }

    @Test
    void applyTurnOutcome_ShowdownWithDelay_SchedulesReadyCountdownOpen() {
        Game game = createShowdownGame();
        TurnOutcome outcome = showdownOutcome(game);
        ReflectionTestUtils.setField(gameLifecycleService, "roundEndDisplayDelayMs", 2_500L);
        long before = System.currentTimeMillis();

        gameLifecycleService.applyTurnOutcome(ROOM_ID, game, outcome, true);

        assertFalse(game.isReadyCountdownActive());
        Long deadline = game.getScheduledTaskDeadline(ScheduledGameTask.READY_OPEN);
        assertNotNull(deadline);
        assertTrue(deadline >= before + 2_500);
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.GAME_END));
        verify(gameStateService).broadcastShowdownResults(ROOM_ID, game, outcome.winners(), outcome.winningsPerPlayer());
    }

    @Test
    void applyTurnOutcome_FinalShowdown_SchedulesGameEnd() {
        Game game = createShowdownGame();
        TurnOutcome outcome = showdownOutcome(game);
        game.getPlayers().getLast().setChips(0);
        assertTrue(game.isTournamentOver());
        long before = System.currentTimeMillis();

        gameLifecycleService.applyTurnOutcome(ROOM_ID, game, outcome, true);

        assertFalse(game.isReadyCountdownActive());
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_OPEN));
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
        Long deadline = game.getScheduledTaskDeadline(ScheduledGameTask.GAME_END);
        assertNotNull(deadline);
        assertTrue(deadline >= before + GameLifecycleService.FINAL_SHOWDOWN_DISPLAY_DELAY_MS);
        verify(gameStateService).broadcastShowdownResults(ROOM_ID, game, outcome.winners(), outcome.winningsPerPlayer());
    }

    private Game createShowdownGame() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        game.conductShowdown();
        return game;
    }

    private TurnOutcome showdownOutcome(Game game) {
        return new TurnOutcome(TurnOutcome.TurnOutcomeType.SHOWDOWN, null, List.of(game.getPlayers().getFirst()), 10);
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

        lifecycle.departParticipants(room.getRoomId(), List.of("D"));

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


    /**
     * Protects the contract that start new hand when game missing should return without throwing.
     */
    @Test
    void startNewHand_WhenGameMissing_ShouldReturnWithoutThrowing() {
        assertDoesNotThrow(() -> gameLifecycleService.startNewHand("missing-game-id"));
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
                assertTrue(gameLifecycleService.getRoomCoordinator().isHeldByCurrentThread("mock-game"),
                        "Room coordinator lease must be held during playerExistsInGame lookup");
                return super.spliterator();
            }

            @Override
            public Iterator<Player> iterator() {
                assertTrue(gameLifecycleService.getRoomCoordinator().isHeldByCurrentThread("mock-game"),
                        "Room coordinator lease must be held during playerExistsInGame lookup");
                return super.iterator();
            }
        };

        when(game.getPlayers()).thenReturn(playerList);
        gameLifecycleService.restoreGame(game);

        assertTrue(gameLifecycleService.playerExistsInGame("mock-game", "Alice"));
        assertFalse(gameLifecycleService.playerExistsInGame("mock-game", "Bob"));
        assertFalse(gameLifecycleService.playerExistsInGame("mock-game", "Charlie"));
    }

    /** A lookup queued during a roster change must see the roster after the mutation. */
    @Test
    void playerLookupWaitsForRosterMutationOnTheSameGame() throws Exception {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        Player departing = game.getPlayers().getLast();
        assertTrue(gameLifecycleService.playerExistsInGame(ROOM_ID, departing.getName()));

        AtomicReference<Thread> reader = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> lookup;
            try (var lease = gameLifecycleService.getRoomCoordinator().acquire(ROOM_ID)) {
                lookup = executor.submit(() -> {
                    reader.set(Thread.currentThread());
                    return gameLifecycleService.playerExistsInGame(ROOM_ID, departing.getName());
                });
                awaitBlockedOn(reader, game);
                game.removePlayerFromGame(departing);
            }
            assertFalse(lookup.get(5, TimeUnit.SECONDS));
        } finally {
            stopExecutor(executor);
        }
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

        ExecutorService executor = Executors.newFixedThreadPool(roomIds.size());
        try {
            List<Future<String>> creations = roomIds.stream()
                    .map(roomId -> executor.submit(() -> gameLifecycleService.createGameFromRoom(roomId)))
                    .toList();
            for (int index = 0; index < roomIds.size(); index++) {
                String gameId = creations.get(index).get(10, TimeUnit.SECONDS);
                assertEquals(roomIds.get(index), gameId);
                assertTrue(gameLifecycleService.gameExists(gameId));
                assertNotNull(gameLifecycleService.getGame(gameId));
            }
        } finally {
            stopExecutor(executor);
        }
    }

    @Test
    void concurrentRegistryReadsReturnTheRegisteredGame() throws Exception {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game expected = gameLifecycleService.getGame(ROOM_ID);
        assertNotNull(expected);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<Game>> reads = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                reads.add(executor.submit(() -> gameLifecycleService.getGame(ROOM_ID)));
            }
            for (Future<Game> read : reads) {
                assertSame(expected, read.get(5, TimeUnit.SECONDS));
            }
        } finally {
            stopExecutor(executor);
        }
    }

    /** Verifies that competing service calls serialize updates to the game's task generation. */
    @Test
    void concurrentAutoAdvanceSchedulingUsesTheGameMonitor() throws Exception {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        ScheduledGameTask task = ScheduledGameTask.AUTO_ADVANCE;
        long generationBefore = game.getScheduledTaskGeneration(task);
        AtomicReference<Thread> first = new AtomicReference<>();
        AtomicReference<Thread> second = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstSchedule;
            Future<?> secondSchedule;
            try (var lease = gameLifecycleService.getRoomCoordinator().acquire(ROOM_ID)) {
                firstSchedule = executor.submit(() -> {
                    first.set(Thread.currentThread());
                    gameLifecycleService.scheduleAutoAdvance(ROOM_ID);
                });
                secondSchedule = executor.submit(() -> {
                    second.set(Thread.currentThread());
                    gameLifecycleService.scheduleAutoAdvance(ROOM_ID);
                });
                awaitBlockedOn(first, game);
                awaitBlockedOn(second, game);
                assertEquals(generationBefore, game.getScheduledTaskGeneration(task));
                assertNull(game.getScheduledTaskDeadline(task));
            }
            firstSchedule.get(5, TimeUnit.SECONDS);
            secondSchedule.get(5, TimeUnit.SECONDS);
            assertEquals(generationBefore + 2, game.getScheduledTaskGeneration(task));
            assertNotNull(game.getScheduledTaskDeadline(task));
        } finally {
            stopExecutor(executor);
        }
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

        ArgumentCaptor<ApiResponse<?>> captor = ArgumentCaptor.forClass(ApiResponse.class);

        gameLifecycleService.createGameFromRoom(ROOM_ID);

        verify(messagingTemplate).convertAndSend(eq("/room/" + ROOM_ID), captor.capture());
        ApiResponse<?> startResponse = captor.getAllValues().stream()
                .filter(res -> ResponseMessage.GAME_STARTED.getMessage().equals(res.message()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected GAME_STARTED event"));

        assertEquals(mockRoomData, startResponse.data());
        assertTrue(testRoom.isGameStarted());
    }

    // ==================== Ready Gate Tests ====================

    private Game gameWithReadyCountdown() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        game.openReadyCountdown(System.currentTimeMillis() + 60_000);
        clearInvocations(gameStateService, taskScheduler);
        return game;
    }

    private void assertReadyRejected(Runnable action, String expectedMessage, Game game) {
        UnauthorisedActionException ex = assertThrows(UnauthorisedActionException.class, action::run);
        assertEquals(expectedMessage, ex.getMessage());
        assertTrue(game.isReadyCountdownActive(), "A rejected ready change must leave the countdown open");
        verify(gameStateService, never()).broadcastGameState(anyString(), any(Game.class));
    }

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
    void deadlineOnlyDispatchCanExpireAGateWithoutRecordedTaskIntent() {
        Game game = gameWithReadyCountdown();
        long deadline = game.getReadyCountdownDeadlineEpochMs();
        long hand = game.getHandNumber();
        game.conductShowdown();
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));

        gameLifecycleService.executeScheduledTask(ROOM_ID, ScheduledGameTask.READY_TIMEOUT, deadline);

        assertEquals(hand + 1, game.getHandNumber());
        assertNull(game.getReadyCountdownDeadlineEpochMs());
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

    @Test
    @DisplayName("markPlayerReadyForNextHand for a missing game throws ResourceNotFoundException")
    void markPlayerReadyForNextHand_MissingGame_ThrowsResourceNotFound() {
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> gameLifecycleService.markPlayerReadyForNextHand("nonexistent-id", "Host"));
        assertEquals("Game not found", ex.getMessage());
    }

    @Test
    @DisplayName("unmarkPlayerReadyForNextHand for a missing game throws ResourceNotFoundException")
    void unmarkPlayerReadyForNextHand_MissingGame_ThrowsResourceNotFound() {
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> gameLifecycleService.unmarkPlayerReadyForNextHand("nonexistent-id", "Host"));
        assertEquals("Game not found", ex.getMessage());
    }

    @Test
    @DisplayName("markPlayerReadyForNextHand is rejected when the ready countdown is not active")
    void markPlayerReadyForNextHand_CountdownNotActive_IsRejected() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        assertFalse(game.isReadyCountdownActive());
        clearInvocations(gameStateService);

        UnauthorisedActionException ex = assertThrows(UnauthorisedActionException.class,
                () -> gameLifecycleService.markPlayerReadyForNextHand(ROOM_ID, "Host"));

        assertEquals("Ready countdown is not active.", ex.getMessage());
        assertFalse(game.getPlayers().stream().anyMatch(Player::getIsReadyForNextHand));
        verify(gameStateService, never()).broadcastGameState(anyString(), any(Game.class));
    }

    @Test
    @DisplayName("unmarkPlayerReadyForNextHand is rejected when the ready countdown is not active")
    void unmarkPlayerReadyForNextHand_CountdownNotActive_IsRejected() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        game.getPlayers().getFirst().setReadyForNextHand(true);
        clearInvocations(gameStateService);

        UnauthorisedActionException ex = assertThrows(UnauthorisedActionException.class,
                () -> gameLifecycleService.unmarkPlayerReadyForNextHand(ROOM_ID, "Host"));

        assertEquals("Ready countdown is not active.", ex.getMessage());
        assertTrue(game.getPlayers().getFirst().getIsReadyForNextHand(), "A rejected unmark must not clear readiness");
        verify(gameStateService, never()).broadcastGameState(anyString(), any(Game.class));
    }

    @Test
    @DisplayName("markPlayerReadyForNextHand is rejected for a player who is not in the game")
    void markPlayerReadyForNextHand_PlayerNotInGame_IsRejected() {
        Game game = gameWithReadyCountdown();

        assertReadyRejected(() -> gameLifecycleService.markPlayerReadyForNextHand(ROOM_ID, "Stranger"),
                "You are no longer part of this game.", game);
    }

    @Test
    @DisplayName("unmarkPlayerReadyForNextHand is rejected for a player who is not in the game")
    void unmarkPlayerReadyForNextHand_PlayerNotInGame_IsRejected() {
        Game game = gameWithReadyCountdown();

        assertReadyRejected(() -> gameLifecycleService.unmarkPlayerReadyForNextHand(ROOM_ID, "Stranger"),
                "You are no longer part of this game.", game);
    }

    @Test
    @DisplayName("markPlayerReadyForNextHand is rejected for a player who is out")
    void markPlayerReadyForNextHand_PlayerOut_IsRejected() {
        Game game = gameWithReadyCountdown();
        Player host = game.getPlayers().getFirst();
        host.setIsOut();

        assertReadyRejected(() -> gameLifecycleService.markPlayerReadyForNextHand(ROOM_ID, "Host"),
                "You are out of this game.", game);
        assertFalse(host.getIsReadyForNextHand());
    }

    @Test
    @DisplayName("unmarkPlayerReadyForNextHand is rejected for a player who is out")
    void unmarkPlayerReadyForNextHand_PlayerOut_IsRejected() {
        Game game = gameWithReadyCountdown();
        Player host = game.getPlayers().getFirst();
        host.setReadyForNextHand(true);
        host.setIsOut();

        assertReadyRejected(() -> gameLifecycleService.unmarkPlayerReadyForNextHand(ROOM_ID, "Host"),
                "You are out of this game.", game);
        assertTrue(host.getIsReadyForNextHand());
    }

    @Test
    @DisplayName("markPlayerReadyForNextHand is rejected for a disconnected player")
    void markPlayerReadyForNextHand_PlayerDisconnected_IsRejected() {
        Game game = gameWithReadyCountdown();
        Player host = game.getPlayers().getFirst();
        host.setDisconnected(true);

        assertReadyRejected(() -> gameLifecycleService.markPlayerReadyForNextHand(ROOM_ID, "Host"),
                "Reconnect before marking ready.", game);
        assertFalse(host.getIsReadyForNextHand());
    }

    @Test
    @DisplayName("markPlayerReadyForNextHand is rejected for a player with no chips")
    void markPlayerReadyForNextHand_PlayerWithoutChips_IsRejected() {
        Game game = gameWithReadyCountdown();
        Player host = game.getPlayers().getFirst();
        host.setChips(0);

        assertReadyRejected(() -> gameLifecycleService.markPlayerReadyForNextHand(ROOM_ID, "Host"),
                "Players without chips cannot mark ready.", game);
        assertFalse(host.getIsReadyForNextHand());
    }

    @Test
    @DisplayName("unmarkPlayerReadyForNextHand is rejected for a disconnected player")
    void unmarkPlayerReadyForNextHand_PlayerDisconnected_IsRejected() {
        Game game = gameWithReadyCountdown();
        Player host = game.getPlayers().getFirst();
        host.setReadyForNextHand(true);
        host.setDisconnected(true);

        assertReadyRejected(() -> gameLifecycleService.unmarkPlayerReadyForNextHand(ROOM_ID, "Host"),
                "You are not eligible to change readiness.", game);
        assertTrue(host.getIsReadyForNextHand());
    }

    @Test
    @DisplayName("unmarkPlayerReadyForNextHand is rejected for a player with no chips")
    void unmarkPlayerReadyForNextHand_PlayerWithoutChips_IsRejected() {
        Game game = gameWithReadyCountdown();
        Player host = game.getPlayers().getFirst();
        host.setReadyForNextHand(true);
        host.setChips(0);

        assertReadyRejected(() -> gameLifecycleService.unmarkPlayerReadyForNextHand(ROOM_ID, "Host"),
                "You are not eligible to change readiness.", game);
        assertTrue(host.getIsReadyForNextHand());
    }
}
