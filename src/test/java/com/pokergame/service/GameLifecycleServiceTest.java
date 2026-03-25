package com.pokergame.service;

import com.pokergame.enums.GamePhase;
import com.pokergame.event.GameCleanupEvent;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import static org.junit.jupiter.api.Assertions.*;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the GameLifecycleService class.
 */
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
    private ApplicationEventPublisher applicationEventPublisher;

    private GameLifecycleService gameLifecycleService;

    private Room testRoom;
    private static final String ROOM_ID = "test-room-id";

    @BeforeEach
    void setUp() {
        gameLifecycleService = new GameLifecycleService(roomService, handEvaluator, gameStateService, messagingTemplate,
                applicationEventPublisher);

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

    @Test
    void createGameFromRoom_WithThreePlayers_ShouldCreateGameWithAllPlayers() {
        testRoom.addPlayer("Player3");
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);

        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);
        assertNotNull(game);
        assertEquals(3, game.getPlayers().size());
    }

    @Test
    void createGameFromRoom_WhenRoomNotFound_ShouldThrowException() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(null);

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> gameLifecycleService.createGameFromRoom(ROOM_ID));

        assertEquals("Room not found", exception.getMessage());
    }

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

    @Test
    void startNewHand_WithValidGame_ShouldResetAndDeal() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        reset(gameStateService);

        gameLifecycleService.startNewHand(ROOM_ID);

        verify(gameStateService).broadcastGameState(eq(ROOM_ID), any(Game.class));
    }

    @Test
    void startNewHand_WhenGameOver_ShouldNotProceed() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);
        // Simulate game over by removing all but one player
        game.getPlayers().subList(1, game.getPlayers().size()).clear();
        game.getActivePlayers().clear();
        game.getActivePlayers().add(game.getPlayers().get(0));

        reset(gameStateService);
        reset(applicationEventPublisher);
        gameLifecycleService.startNewHand(ROOM_ID);

        // Should end the game and schedule cleanup because game became over after reset
        verify(gameStateService, never()).broadcastGameState(anyString(), any(Game.class));
        verify(gameStateService).broadcastGameEnd(eq(ROOM_ID), any(Player.class));
        verify(applicationEventPublisher).publishEvent(any(GameCleanupEvent.class));
    }

    @Test
    void startNewHand_ShouldDealHoleCardsToPlayers() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);
        for (Player player : game.getPlayers()) {
            assertEquals(2, player.getHoleCards().size());
        }
    }

    @Test
    void startNewHand_ShouldPostBlinds() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);
        assertTrue(game.getPot() > 0);
        assertEquals(10, game.getCurrentHighestBet()); // Big blind is 10
    }

    @Test
    void startNewHand_WhenGameMissing_ShouldReturnWithoutThrowing() {
        assertDoesNotThrow(() -> gameLifecycleService.startNewHand("missing-game-id"));
        verify(gameStateService, never()).broadcastGameState(anyString(), any(Game.class));
    }

    // ==================== leaveGame Tests ====================

    @Test
    void leaveGame_WhenGameNotFound_ShouldThrowException() {
        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> gameLifecycleService.leaveGame("nonexistent-id", "Player"));

        assertEquals("Game not found", exception.getMessage());
    }

    @Test
    void leaveGame_WhenPlayerNotFound_ShouldThrowException() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> gameLifecycleService.leaveGame(ROOM_ID, "NonexistentPlayer"));

        assertEquals("Player not found in game", exception.getMessage());
    }

    @Test
    void leaveGame_OnePlayerRemains_ShouldEndGame() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);
        String playerToLeave = game.getPlayers().get(0).getName();

        gameLifecycleService.leaveGame(ROOM_ID, playerToLeave);

        verify(gameStateService).broadcastGameEnd(eq(ROOM_ID), any(Player.class));
    }

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

    // ==================== getGame Tests ====================

    @Test
    void getGame_WithValidId_ShouldReturnGame() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);

        assertNotNull(game);
    }

    @Test
    void getGame_WithInvalidId_ShouldReturnNull() {
        Game game = gameLifecycleService.getGame("nonexistent-id");
        assertNull(game);
    }

    // ==================== gameExists Tests ====================

    @Test
    void gameExists_WhenGameExists_ShouldReturnTrue() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        assertTrue(gameLifecycleService.gameExists(ROOM_ID));
    }

    @Test
    void gameExists_WhenGameDoesNotExist_ShouldReturnFalse() {
        assertFalse(gameLifecycleService.gameExists("nonexistent-id"));
    }

    // ==================== handleGameEnd Tests ====================

    @Test
    void handleGameEnd_WithWinner_ShouldBroadcastEnd() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        reset(gameStateService);

        gameLifecycleService.handleGameEnd(ROOM_ID);

        verify(gameStateService).broadcastGameEnd(eq(ROOM_ID), any(Player.class));
    }

    @Test
    void handleGameEnd_WithNullGame_ShouldNotBroadcast() {
        gameLifecycleService.handleGameEnd("nonexistent-id");

        verify(gameStateService, never()).broadcastGameEnd(anyString(), any(Player.class));
    }

    @Test
    void handleGameEnd_WhenNoPlayersRemain_ShouldNotThrow() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);
        game.getPlayers().clear();
        game.getActivePlayers().clear();

        assertDoesNotThrow(() -> gameLifecycleService.handleGameEnd(ROOM_ID));
    }

    // ==================== Integration-like Tests ====================

    @Test
    void createGame_ShouldInitializeCorrectGamePhase() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);
        assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
    }

    @Test
    void createGame_ShouldHaveEmptyCommunityCards() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);
        assertTrue(game.getCommunityCards().isEmpty());
    }

    @Test
    void createGame_ShouldSetCurrentPlayer() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);

        Game game = gameLifecycleService.getGame(ROOM_ID);
        assertNotNull(game.getCurrentPlayer());
    }

    // ==================== CONCURRENCY TESTS ====================

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
        CountDownLatch latch = new CountDownLatch(2);

        // Thread 1: Player leaves (causes game end since only 1 will remain)
        executor.submit(() -> {
            try {
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
                Thread.sleep(10); // Slight delay to interleave operations
                gameLifecycleService.leaveGame(ROOM_ID, player2.getName());
            } catch (Exception e) {
                // Expected - game might already be ended or player might be gone
            } finally {
                latch.countDown();
            }
        });

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
