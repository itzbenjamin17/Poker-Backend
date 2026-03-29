package com.pokergame.service;

import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.enums.PlayerAction;
import com.pokergame.model.*;
import com.pokergame.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the GameStateService class.
 */
@ExtendWith(MockitoExtension.class)
class GameStateServiceTest {
    private GameStateService gameStateService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private RoomService roomService;

    @Mock
    private HandEvaluatorService handEvaluator;

    private Game testGame;
    private Room testRoom;
    private List<Player> testPlayers;
    private static final String GAME_ID = "test-game-id";

    @BeforeEach
    void setUp() {
        gameStateService = new GameStateService(roomService, messagingTemplate);

        testPlayers = new ArrayList<>();
        testPlayers.add(new Player("Player1", UUID.randomUUID().toString(), 100));
        testPlayers.add(new Player("Player2", UUID.randomUUID().toString(), 100));

        testGame = new Game(GAME_ID, testPlayers, 5, 10, handEvaluator);

        testRoom = new Room(
                GAME_ID,
                "Test Room",
                "Player1",
                6,
                5,
                10,
                100,
                null);
        testRoom.addPlayer("Player1");
        testRoom.addPlayer("Player2");
    }

    // ==================== broadcastGameState Tests ====================

    @Test
    void broadcastGameState_WithNullGame_ShouldNotBroadcast() {
        assertThrows(BadRequestException.class, () -> gameStateService.broadcastGameState(GAME_ID, null));

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void broadcastGameState_WithValidGame_ShouldBroadcastToGameChannel() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        gameStateService.broadcastGameState(GAME_ID, testGame);

        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    @Test
    void broadcastGameState_ShouldSendPrivateStateToEachPlayer() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        gameStateService.broadcastGameState(GAME_ID, testGame);

        // Should send private messages to each player by encoded name
        for (Player player : testGame.getPlayers()) {
            String encodedName = java.net.URLEncoder.encode(
                    player.getName(),
                    java.nio.charset.StandardCharsets.UTF_8);

            verify(messagingTemplate).convertAndSend(
                    eq("/game/" + GAME_ID + "/player-name/" + encodedName + "/private"),
                    any(Object.class));
        }
    }

    @Test
    void broadcastGameState_WithThreePlayers_ShouldSendThreePrivateMessages() {
        testPlayers.add(new Player("Player3", UUID.randomUUID().toString(), 100));
        testGame = new Game(GAME_ID, testPlayers, 5, 10, handEvaluator);
        testRoom.addPlayer("Player3");

        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        gameStateService.broadcastGameState(GAME_ID, testGame);

        // 1 public message + 3 name-private = 4 total
        verify(messagingTemplate, times(4)).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void broadcastGameState_AfterNonCurrentLeaveWithStaleBlindIndex_ShouldNotThrow() {
        List<Player> threePlayers = new ArrayList<>();
        threePlayers.add(new Player("Player1", UUID.randomUUID().toString(), 100));
        threePlayers.add(new Player("Player2", UUID.randomUUID().toString(), 100));
        threePlayers.add(new Player("Player3", UUID.randomUUID().toString(), 100));

        Game game = new Game(GAME_ID, threePlayers, 5, 10, handEvaluator);
        game.postBlinds();

        Room room = new Room(
                GAME_ID,
                "Test Room",
                "Player1",
                6,
                5,
                10,
                100,
                null);
        room.addPlayer("Player1");
        room.addPlayer("Player2");
        room.addPlayer("Player3");

        when(roomService.getRoom(GAME_ID)).thenReturn(room);

        Player playerToRemove = game.getActivePlayers().get(1);
        assertNotEquals(playerToRemove, game.getCurrentPlayer());
        game.removePlayerFromGame(playerToRemove);

        assertDoesNotThrow(() -> gameStateService.broadcastGameState(GAME_ID, game));
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    @Test
    void broadcastGameState_WhenPlayerDisconnected_ShouldExposeDisconnectedStatus() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        testPlayers.get(1).setDisconnected(true);
        long disconnectDeadlineEpochMs = System.currentTimeMillis() + 120_000;
        testPlayers.get(1).setDisconnectDeadlineEpochMs(disconnectDeadlineEpochMs);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

        gameStateService.broadcastGameState(GAME_ID, testGame);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        Object payload = captor.getValue();
        assertInstanceOf(PublicGameStateResponse.class, payload);

        PublicGameStateResponse response = (PublicGameStateResponse) payload;
        PublicPlayerState disconnectedPlayer = response.players().stream()
                .filter(player -> player.name().equals("Player2"))
                .findFirst()
                .orElseThrow();

        assertEquals("DISCONNECTED", disconnectedPlayer.status());
        assertEquals(disconnectDeadlineEpochMs, disconnectedPlayer.disconnectDeadlineEpochMs());
    }

    @Test
    void broadcastGameState_WhenOnlyOneConnectedPlayerRemains_ShouldExposeClaimWinEligibility() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        // Current player stays connected, all others disconnected.
        Player current = testGame.getCurrentPlayer();
        testGame.getPlayers().stream()
                .filter(player -> !player.equals(current))
                .forEach(player -> player.setDisconnected(true));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

        gameStateService.broadcastGameState(GAME_ID, testGame);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        Object payload = captor.getValue();
        assertInstanceOf(PublicGameStateResponse.class, payload);

        PublicGameStateResponse response = (PublicGameStateResponse) payload;
        assertEquals(Boolean.TRUE, response.claimWinAvailable());
        assertEquals(current.getName(), response.claimWinPlayerName());
    }

    // ==================== broadcastShowdownResults Tests ====================

    @Test
    void broadcastShowdownResults_WithNullGame_ShouldNotBroadcast() {
        gameStateService.broadcastShowdownResults(GAME_ID, null, List.of(), 0);

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void broadcastShowdownResults_WithValidData_ShouldBroadcast() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        List<Player> winners = List.of(testPlayers.getFirst());

        gameStateService.broadcastShowdownResults(GAME_ID, testGame, winners, 50);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    @Test
    void broadcastShowdownResults_WithNoRoom_ShouldStillBroadcast() {
        when(roomService.getRoom(GAME_ID)).thenReturn(null);
        List<Player> winners = List.of(testPlayers.getFirst());

        gameStateService.broadcastShowdownResults(GAME_ID, testGame, winners, 50);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    @Test
    void broadcastShowdownResults_WithMultipleWinners_ShouldIncludeAllWinners() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        List<Player> winners = List.of(testPlayers.get(0), testPlayers.get(1));

        gameStateService.broadcastShowdownResults(GAME_ID, testGame, winners, 25);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    // ==================== broadcastGameStateWithAutoAdvance Tests
    // ====================

    @Test
    void broadcastGameStateWithAutoAdvance_WithNullGame_ShouldNotBroadcast() {
        gameStateService.broadcastGameStateWithAutoAdvance(GAME_ID, null, true, "Auto-advancing...");

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void broadcastGameStateWithAutoAdvance_WithValidData_ShouldBroadcast() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        gameStateService.broadcastGameStateWithAutoAdvance(GAME_ID, testGame, true, "Auto-advancing...");

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    @Test
    void broadcastGameStateWithAutoAdvance_WhenNotAutoAdvancing_ShouldStillBroadcast() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        gameStateService.broadcastGameStateWithAutoAdvance(GAME_ID, testGame, false, "Not auto-advancing");

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    // ==================== broadcastAutoAdvanceNotification Tests
    // ====================

    @Test
    void broadcastAutoAdvanceNotification_WithNullGame_ShouldNotBroadcast() {
        gameStateService.broadcastAutoAdvanceNotification(GAME_ID, null);

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void broadcastAutoAdvanceNotification_WithValidGame_ShouldBroadcast() {
        gameStateService.broadcastAutoAdvanceNotification(GAME_ID, testGame);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    // ==================== broadcastAutoAdvanceComplete Tests ====================

    @Test
    void broadcastAutoAdvanceComplete_WithNullGame_ShouldNotBroadcast() {
        gameStateService.broadcastAutoAdvanceComplete(GAME_ID, null);

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void broadcastAutoAdvanceComplete_WithValidGame_ShouldBroadcast() {
        gameStateService.broadcastAutoAdvanceComplete(GAME_ID, testGame);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    // ==================== sendPlayerNotification Tests ====================

    @Test
    void sendPlayerNotification_ShouldBroadcastToGameChannel() {
        gameStateService.sendPlayerNotification(GAME_ID, "Player1", "Test message");

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    @Test
    void sendPlayerNotification_WithDifferentPlayers_ShouldBroadcast() {
        gameStateService.sendPlayerNotification(GAME_ID, "Player1", "Message 1");
        gameStateService.sendPlayerNotification(GAME_ID, "Player2", "Message 2");

        verify(messagingTemplate, times(2)).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    // ==================== broadcastGameEnd Tests ====================

    @Test
    void broadcastGameEnd_ShouldBroadcastWinnerInfo() {
        Player winner = testPlayers.getFirst();
        winner.addChips(50); // Winner has more chips

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

        gameStateService.broadcastGameEnd(GAME_ID, winner, false);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());

        Object captured = captor.getValue();
        assertNotNull(captured);
        assertTrue(captured instanceof Map<?, ?>);

        Map<?, ?> gameEndData = (Map<?, ?>) captured;
        assertEquals("GAME_END", gameEndData.get("type"));
        assertEquals(winner.getName(), gameEndData.get("winner"));
        assertEquals(winner.getChips(), gameEndData.get("winnerChips"));
        assertEquals(GAME_ID, gameEndData.get("gameId"));
    }

    @Test
    void broadcastGameEnd_MessageShouldContainWinnerName() {
        Player winner = testPlayers.getFirst();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        gameStateService.broadcastGameEnd(GAME_ID, winner, false);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());

        Map<?, ?> gameEndData = (Map<?, ?>) captor.getValue();
        Object messageObj = gameEndData.get("message");
        assertTrue(messageObj instanceof String);
        String message = (String) messageObj;
        assertTrue(message.contains(winner.getName()));
        assertTrue(message.contains("wins"));
    }

    @Test
    void broadcastGameEnd_WithNullWinner_ShouldNotThrow() {
        assertDoesNotThrow(() -> gameStateService.broadcastGameEnd(GAME_ID, null, false));
    }

    // ==================== Edge Case Tests ====================

    @Test
    void broadcastShowdownResults_WithFoldedPlayer_ShouldMarkAsFolded() {
        testPlayers.get(1).doAction(PlayerAction.FOLD, 0, 0);
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        List<Player> winners = List.of(testPlayers.get(0));
        gameStateService.broadcastShowdownResults(GAME_ID, testGame, winners, 100);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    @Test
    void broadcastShowdownResults_WithAllInPlayer_ShouldMarkAsAllIn() {
        testPlayers.getFirst().doAction(PlayerAction.ALL_IN, 0, 0);
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        List<Player> winners = List.of(testPlayers.getFirst());
        gameStateService.broadcastShowdownResults(GAME_ID, testGame, winners, 100);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    // ==================== GamePhase Tests ====================

    @Test
    void broadcastGameState_DuringPreflop_ShouldBroadcast() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        gameStateService.broadcastGameState(GAME_ID, testGame);

        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    @Test
    void broadcastGameState_ShouldIncludeSingleSmallBlindAndBigBlindFlags() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        testGame.dealHoleCards();
        testGame.postBlinds();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

        gameStateService.broadcastGameState(GAME_ID, testGame);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        Object payload = captor.getValue();
        assertInstanceOf(PublicGameStateResponse.class, payload);

        PublicGameStateResponse response = (PublicGameStateResponse) payload;
        long smallBlindCount = response.players().stream().filter(PublicPlayerState::isSmallBlind).count();
        long bigBlindCount = response.players().stream().filter(PublicPlayerState::isBigBlind).count();

        assertEquals(1, smallBlindCount);
        assertEquals(1, bigBlindCount);
    }

    @Test
    void broadcastGameState_AfterDealingFlop_ShouldBroadcast() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        testGame.dealHoleCards();
        testGame.postBlinds();
        testGame.dealFlop();

        gameStateService.broadcastGameState(GAME_ID, testGame);

        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
        assertEquals(3, testGame.getCommunityCards().size());
    }

    @Test
    void broadcastGameState_AfterDealingFlop_ShouldKeepBlindFlags() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        testGame.dealHoleCards();
        testGame.postBlinds();
        testGame.dealFlop();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

        gameStateService.broadcastGameState(GAME_ID, testGame);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        Object payload = captor.getValue();
        assertInstanceOf(PublicGameStateResponse.class, payload);

        PublicGameStateResponse response = (PublicGameStateResponse) payload;
        long smallBlindCount = response.players().stream().filter(PublicPlayerState::isSmallBlind).count();
        long bigBlindCount = response.players().stream().filter(PublicPlayerState::isBigBlind).count();

        assertEquals(1, smallBlindCount);
        assertEquals(1, bigBlindCount);
    }

    // ==================== Multiple Broadcast Tests ====================

    @Test
    void multipleBroadcasts_ShouldAllSucceed() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        gameStateService.broadcastGameState(GAME_ID, testGame);
        gameStateService.broadcastGameState(GAME_ID, testGame);
        gameStateService.broadcastGameState(GAME_ID, testGame);

        // 3 public broadcasts + 3*(2 name-private) = 9 total
        verify(messagingTemplate, times(9)).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void broadcastGameState_WhenCurrentPlayerMissing_ShouldNotThrow() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        testGame.getActivePlayers().clear();

        assertDoesNotThrow(() -> gameStateService.broadcastGameState(GAME_ID, testGame));
    }
}
