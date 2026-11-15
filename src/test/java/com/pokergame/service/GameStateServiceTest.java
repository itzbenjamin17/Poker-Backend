package com.pokergame.service;

import com.pokergame.dto.response.ActionErrorResponse;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.PlayerNotificationResponse;
import com.pokergame.dto.response.PrivatePlayerState;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.HandRank;
import com.pokergame.enums.Rank;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.RejectedAction;
import com.pokergame.enums.ResponseMessage;
import com.pokergame.enums.Suit;
import com.pokergame.model.*;
import com.pokergame.security.PlayerPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.DefaultUserDestinationResolver;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.MessageBuilder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the GameStateService class.
 */
@Tag("unit")
@DisplayName("Game state service")
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

    /**
     * Initializes the test fixtures before each scenario.
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        gameStateService = new GameStateService(roomService, messagingTemplate, FIXED_CLOCK);

        testPlayers = new ArrayList<>();
        testPlayers.add(new Player("Player1", UUID.randomUUID().toString(), 100));
        testPlayers.add(new Player("Player2", UUID.randomUUID().toString(), 100));

        testGame = new Game(GAME_ID, testPlayers, 6, 5, 10, handEvaluator);

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

    /**
     * Protects the contract that broadcast game state rejects a null game.
     */
    @Test
    void broadcastGameState_WithNullGame_ThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () -> gameStateService.broadcastGameState(null));

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    /**
     * Protects the contract that broadcast game state with valid game should broadcast to game channel.
     */
    @Test
    void broadcastGameState_WithValidGame_ShouldBroadcastToGameChannel() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        // Deal hole cards so the public projection has something private to mask.
        testGame.dealHoleCards();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

        gameStateService.broadcastGameState(testGame);

        // The public channel receives a PublicGameStateResponse (not a bare Object) carrying the
        // game's state revision, and every player's hole cards are masked away before the flop.
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        Object payload = captor.getValue();
        assertInstanceOf(PublicGameStateResponse.class, payload);
        PublicGameStateResponse response = (PublicGameStateResponse) payload;
        assertEquals(testGame.getStateRevision(), response.stateRevision(),
                "The public broadcast must carry the game's current state revision");
        assertTrue(response.players().stream()
                        .allMatch(p -> p.holeCards() == null || p.holeCards().isEmpty()),
                "The public projection must not expose any player's hole cards outside showdown");
    }

    /**
     * Protects the contract that broadcast game state should send private state to each player.
     */
    @Test
    void broadcastGameState_ShouldSendPrivateStateToEachPlayer() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        testGame.dealHoleCards();

        gameStateService.broadcastGameState(testGame);

        // Each private message must carry only its own owner's hole cards and identity, so one
        // player can never receive another's cards.
        for (Player player : testGame.getPlayers()) {
            String compositeName = player.getName() + ":" + GAME_ID + ":" + testRoom.getMembershipId(player.getName());
            ArgumentCaptor<Object> privateCaptor = ArgumentCaptor.forClass(Object.class);

            verify(messagingTemplate).convertAndSendToUser(
                    eq(compositeName),
                    eq("/queue/private"),
                    privateCaptor.capture());

            Object privatePayload = privateCaptor.getValue();
            assertInstanceOf(PrivatePlayerState.class, privatePayload);
            PrivatePlayerState privateState = (PrivatePlayerState) privatePayload;
            assertEquals(player.getPlayerId(), privateState.playerId(),
                    "A private projection must be addressed to its own owner");
            assertEquals(player.getHoleCards(), privateState.holeCards(),
                    "A private projection must carry only the owner's hole cards");
        }
    }

    /**
     * Protects the contract that broadcast game state with three players should send three private messages.
     */
    @Test
    void broadcastGameState_WithThreePlayers_ShouldSendThreePrivateMessages() {
        testPlayers.add(new Player("Player3", UUID.randomUUID().toString(), 100));
        testGame = new Game(GAME_ID, testPlayers, 6, 5, 10, handEvaluator);
        testRoom.addPlayer("Player3");

        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        gameStateService.broadcastGameState(testGame);

        // 1 public message + 3 name-private via convertAndSendToUser
        verify(messagingTemplate, times(1)).convertAndSend(anyString(), any(Object.class));
        verify(messagingTemplate, times(3)).convertAndSendToUser(anyString(), anyString(), any(Object.class));
    }

    /**
     * Protects the contract that broadcast game state after non current leave with stale blind index should not throw.
     */
    @Test
    void broadcastGameState_AfterNonCurrentLeaveWithStaleBlindIndex_ShouldNotThrow() {
        List<Player> threePlayers = new ArrayList<>();
        threePlayers.add(new Player("Player1", UUID.randomUUID().toString(), 100));
        threePlayers.add(new Player("Player2", UUID.randomUUID().toString(), 100));
        threePlayers.add(new Player("Player3", UUID.randomUUID().toString(), 100));

        Game game = new Game(GAME_ID, threePlayers, 6, 5, 10, handEvaluator);
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
        game.removePlayer(playerToRemove);

        assertDoesNotThrow(() -> gameStateService.broadcastGameState(game));
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    /**
     * Protects the contract that broadcast game state when player disconnected should expose disconnected status.
     */
    @Test
    void broadcastGameState_WhenPlayerDisconnected_ShouldExposeDisconnectedStatus() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        testPlayers.get(1).setDisconnected(true);
        long disconnectDeadlineEpochMs = System.currentTimeMillis() + 120_000;
        testPlayers.get(1).setDisconnectDeadlineEpochMs(disconnectDeadlineEpochMs);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

        gameStateService.broadcastGameState(testGame);

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

    /**
     * Protects the contract that broadcast game state when only one connected player remains should expose claim win eligibility.
     */
    @Test
    void broadcastGameState_WhenOnlyOneConnectedPlayerRemains_ShouldExposeClaimWinEligibility() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        // Current player stays connected, all others disconnected.
        Player current = testGame.getCurrentPlayer();
        testGame.getPlayers().stream()
                .filter(player -> !player.equals(current))
                .forEach(player -> player.setDisconnected(true));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

        gameStateService.broadcastGameState(testGame);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        Object payload = captor.getValue();
        assertInstanceOf(PublicGameStateResponse.class, payload);

        PublicGameStateResponse response = (PublicGameStateResponse) payload;
        assertEquals(Boolean.TRUE, response.claimWinAvailable());
        assertEquals(current.getName(), response.claimWinPlayerName());
    }

    // ==================== broadcastShowdownResults Tests ====================

    /**
     * Protects the contract that broadcast showdown results rejects a null game.
     */
    @Test
    void broadcastShowdownResults_WithNullGame_ThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () -> gameStateService.broadcastShowdownResults(null));

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    /**
     * Protects the contract that broadcast showdown results rejects an unsettled hand.
     */
    @Test
    void broadcastShowdownResults_WhenUnsettled_ThrowsIllegalStateException() {
        assertThrows(IllegalStateException.class, () -> gameStateService.broadcastShowdownResults(testGame));

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    /**
     * Protects the contract that broadcast showdown results with valid settled data broadcasts to game channel.
     */
    @Test
    void broadcastShowdownResults_WithValidData_ShouldBroadcast() {
        Player winner = testPlayers.getFirst();
        Game settled = settledGame(GAME_ID, testPlayers, Map.of(winner.getPlayerId(), 50), Set.of(winner.getPlayerId()));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

        gameStateService.broadcastShowdownResults(settled);

        // The showdown broadcast must be a PublicGameStateResponse naming the winner and carrying
        // the game's state revision, not merely some object on the right destination.
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        Object payload = captor.getValue();
        assertInstanceOf(PublicGameStateResponse.class, payload);
        PublicGameStateResponse response = (PublicGameStateResponse) payload;
        assertEquals(List.of(winner.getName()), response.winners(),
                "The showdown projection must report the single winner");
        assertEquals(settled.getStateRevision(), response.stateRevision(),
                "The showdown broadcast must carry the game's current state revision");
    }

    /**
     * Protects the contract that broadcast showdown results with no room lookup still broadcasts.
     */
    @Test
    void broadcastShowdownResults_WithNoRoom_ShouldStillBroadcast() {
        Player winner = testPlayers.getFirst();
        Game settled = settledGame(GAME_ID, testPlayers, Map.of(winner.getPlayerId(), 50), Set.of(winner.getPlayerId()));

        gameStateService.broadcastShowdownResults(settled);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    /**
     * Protects the contract that broadcast showdown results with multiple winners includes all winners.
     */
    @Test
    void broadcastShowdownResults_WithMultipleWinners_ShouldIncludeAllWinners() {
        Player p1 = testPlayers.get(0);
        Player p2 = testPlayers.get(1);
        Game settled = settledGame(
                GAME_ID,
                testPlayers,
                Map.of(p1.getPlayerId(), 25, p2.getPlayerId(), 25),
                Set.of(p1.getPlayerId(), p2.getPlayerId()));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        gameStateService.broadcastShowdownResults(settled);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        PublicGameStateResponse response = (PublicGameStateResponse) captor.getValue();
        assertEquals(List.of("Player1", "Player2"), response.winners());
        assertEquals(25, response.winningsPerPlayer());
    }

    /**
     * Protects the contract that no player is reported as having the turn during a
     * showdown broadcast. Previously the stale currentPlayerId let the client
     * briefly render Fold/Check controls for the losing player between the
     * showdown broadcast and the later GAME_END broadcast.
     */
    @Test
    void broadcastShowdownResults_ShouldNotReportACurrentPlayer() {
        Player winner = testPlayers.getFirst();
        Game settled = settledGame(GAME_ID, testPlayers, Map.of(winner.getPlayerId(), 50), Set.of(winner.getPlayerId()));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        gameStateService.broadcastShowdownResults(settled);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        PublicGameStateResponse response = (PublicGameStateResponse) captor.getValue();

        assertNull(response.currentPlayerId());
        assertNull(response.currentPlayerName());
        assertTrue(response.players().stream().noneMatch(PublicPlayerState::isCurrentPlayer));
    }

    // ==================== broadcastGameStateWithAutoAdvance Tests
    // ====================

    /**
     * Protects the contract that broadcast game state with auto advance rejects a null game.
     */
    @Test
    void broadcastGameStateWithAutoAdvance_WithNullGame_ThrowsNullPointerException() {
        assertThrows(NullPointerException.class,
                () -> gameStateService.broadcastGameStateWithAutoAdvance(null, "Auto-advancing..."));

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    /**
     * Protects the contract that broadcast game state with auto advance with valid data should broadcast.
     */
    @Test
    void broadcastGameStateWithAutoAdvance_WithValidData_ShouldBroadcast() {
        gameStateService.broadcastGameStateWithAutoAdvance(testGame, "Auto-advancing...");

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    // ==================== broadcastAutoAdvanceNotification Tests
    // ====================

    /**
     * Protects the contract that broadcast auto advance notification rejects a null game.
     */
    @Test
    void broadcastAutoAdvanceNotification_WithNullGame_ThrowsNullPointerException() {
        assertThrows(NullPointerException.class,
                () -> gameStateService.broadcastAutoAdvanceNotification(null));

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    /**
     * Protects the contract that broadcast auto advance notification with valid game should broadcast.
     */
    @Test
    void broadcastAutoAdvanceNotification_WithValidGame_ShouldBroadcast() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        gameStateService.broadcastAutoAdvanceNotification(testGame);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        assertInstanceOf(PublicGameStateResponse.class, captor.getValue());
        PublicGameStateResponse state = (PublicGameStateResponse) captor.getValue();
        assertEquals(testGame.getPot(), state.pot());
        assertTrue(state.isAutoAdvancing());
        assertNull(state.currentPlayerId());
        assertTrue(state.legalActions().isEmpty());
        assertTrue(state.players().stream().noneMatch(PublicPlayerState::isCurrentPlayer));
    }

    // ==================== broadcastAutoAdvanceComplete Tests ====================

    /**
     * Protects the contract that broadcast auto advance complete rejects a null game.
     */
    @Test
    void broadcastAutoAdvanceComplete_WithNullGame_ThrowsNullPointerException() {
        assertThrows(NullPointerException.class,
                () -> gameStateService.broadcastAutoAdvanceComplete(null));

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    /**
     * Protects the contract that broadcast auto advance complete with valid game should broadcast.
     */
    @Test
    void broadcastAutoAdvanceComplete_WithValidGame_ShouldBroadcast() {
        gameStateService.broadcastAutoAdvanceComplete(testGame);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    // ==================== Private Notification & Action Error Tests ====================

    /**
     * Protects the contract that private player notifications route to the active membership's user queue.
     */
    @Test
    void sendPrivatePlayerNotification_ShouldSendToPlayerPrivateQueue() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        gameStateService.sendPrivatePlayerNotification(
                GAME_ID, "Player1", "Test message", ResponseMessage.PLAYER_NOTIFICATION);

        String expectedUser = "Player1:" + GAME_ID + ":" + testRoom.getMembershipId("Player1");
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(eq(expectedUser), eq("/queue/private"), captor.capture());
        PlayerNotificationResponse response = assertInstanceOf(PlayerNotificationResponse.class, captor.getValue());
        assertEquals("Test message", response.message());
        assertEquals(ResponseMessage.PLAYER_NOTIFICATION, response.type());
        assertEquals("Player1", response.playerName());
    }

    /**
     * Protects the contract that principal-targeted private notifications and action errors target the originating session.
     */
    @Test
    void sendPrivatePlayerNotificationAndActionError_WithOriginPrincipal_ShouldTargetOriginSession() {
        PlayerPrincipal origin = new PlayerPrincipal("Player1", GAME_ID, "origin-membership");

        gameStateService.sendPrivatePlayerNotification(origin, "Welcome back", ResponseMessage.PLAYER_NOTIFICATION);
        gameStateService.sendActionError(origin, "INVALID_ACTION", RejectedAction.BET, "Minimum bet is 10");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, times(2)).convertAndSendToUser(
                eq(origin.getName()), eq("/queue/private"), captor.capture());
        PlayerNotificationResponse notification = assertInstanceOf(
                PlayerNotificationResponse.class, captor.getAllValues().get(0));
        assertEquals("Welcome back", notification.message());
        assertEquals(ResponseMessage.PLAYER_NOTIFICATION, notification.type());

        ActionErrorResponse actionError = assertInstanceOf(
                ActionErrorResponse.class, captor.getAllValues().get(1));
        assertEquals("INVALID_ACTION", actionError.code());
        assertEquals(RejectedAction.BET, actionError.action());
        assertEquals("Minimum bet is 10", actionError.message());
    }

    // ==================== recordGameEnd / sendGameEnd Tests ====================

    /**
     * Protects the contract that recordGameEnd + sendGameEnd publishes winner info and increments stateRevision.
     */
    @Test
    void recordGameEnd_AndSendGameEnd_ShouldPublishWinnerInfo() {
        Player winner = testPlayers.getFirst();
        winner.addChips(50);

        GameEndResponse recorded = gameStateService.recordGameEnd(testGame, winner, false);
        gameStateService.sendGameEnd(recorded);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());

        GameEndResponse gameEndResponse = assertInstanceOf(GameEndResponse.class, captor.getValue());
        assertEquals("GAME_END", gameEndResponse.type());
        assertEquals(winner.getName(), gameEndResponse.winner());
        assertEquals(winner.getChips(), gameEndResponse.winnerChips());
        assertEquals(GAME_ID, gameEndResponse.gameId());
        assertEquals(1L, testGame.getStateRevision());
    }

    /**
     * Protects the contract that game-end messages name the winner.
     */
    @Test
    void buildGameEndResponse_MessageShouldContainWinnerName() {
        Player winner = testPlayers.getFirst();

        GameEndResponse gameEndResponse = gameStateService.buildGameEndResponse(testGame, winner, false);

        String message = gameEndResponse.message();
        assertTrue(message.contains(winner.getName()));
        assertTrue(message.contains("wins"));
    }

    /**
     * Protects the contract that recordGameEnd and buildGameEndResponse reject null game or winner.
     */
    @Test
    void recordGameEnd_WithNullGameOrWinner_ThrowsNullPointerException() {
        Player winner = testPlayers.getFirst();

        assertThrows(NullPointerException.class, () -> gameStateService.recordGameEnd(null, winner, false));
        assertThrows(NullPointerException.class, () -> gameStateService.recordGameEnd(testGame, null, false));
        assertThrows(NullPointerException.class, () -> gameStateService.buildGameEndResponse(null, winner, false));
        assertThrows(NullPointerException.class, () -> gameStateService.buildGameEndResponse(testGame, null, false));
        assertDoesNotThrow(() -> gameStateService.sendGameEnd(null));
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    /**
     * Protects the contract that a fold-win game end keeps hole cards private.
     */
    @Test
    void recordGameEnd_FoldWinKeepsHoleCardsPrivate() {
        Player winner = testPlayers.getFirst();
        Player loser = testPlayers.get(1);
        loser.setIsOut();

        GameEndResponse gameEndResponse = gameStateService.recordGameEnd(testGame, winner, false);
        PublicGameStateResponse finalState = gameEndResponse.finalState();
        assertNotNull(finalState);

        PublicPlayerState winnerState = finalState.players().stream()
                .filter(p -> p.name().equals(winner.getName()))
                .findFirst()
                .orElseThrow();
        assertNull(winnerState.holeCards());
        assertTrue(winnerState.isWinner());
    }

    @Test
    void recordGameEnd_ContestedShowdownRevealsWinnerCards() {
        Player winner = testPlayers.getFirst();
        Player other = testPlayers.get(1);
        List<Card> board = List.of(new Card(Rank.ACE, Suit.SPADES),
                new Card(Rank.KING, Suit.SPADES),
                new Card(Rank.QUEEN, Suit.SPADES),
                new Card(Rank.JACK, Suit.SPADES),
                new Card(Rank.TEN, Suit.SPADES));
        winner.getHoleCards().addAll(List.of(new Card(Rank.TWO, Suit.CLUBS),
                new Card(Rank.THREE, Suit.CLUBS)));
        other.getHoleCards().addAll(List.of(new Card(Rank.FOUR, Suit.CLUBS),
                new Card(Rank.FIVE, Suit.CLUBS)));
        Game contested = Game.restoreBuilder().gameId(GAME_ID).players(testPlayers)
                .activePlayerIds(testPlayers.stream().map(Player::getPlayerId).toList()).remainingDeck(List.of())
                .communityCards(board).pot(0).dealerPosition(0).smallBlindPosition(0).bigBlindPosition(1)
                .currentPlayerPosition(0).currentHighestBet(0).currentPhase(GamePhase.SHOWDOWN).gameOver(false)
                .smallBlind(5).bigBlind(10).handContributions(Map.of()).readyCountdownActive(false)
                .lastSettlementResult(new HandSettlementResult(Map.of(winner.getPlayerId(), 100), Map.of(),
                        Set.of(winner.getPlayerId()), 0, true)).scheduledTaskDeadlines(Map.of())
                .everyoneHasHadInitialTurn(true).actedPlayersInRound(Set.of())
                .handEvaluator(handEvaluator).build();

        GameEndResponse event = gameStateService.recordGameEnd(contested, winner, false);

        assertNotNull(event.finalState().players().stream().filter(player -> player.name().equals(winner.getName()))
                .findFirst().orElseThrow().holeCards());
    }

    @Test
    void finalDtoCapturesTerminalCarryOverOnceWithWinnerStack1010AndEmptyPot() {
        Player sb = Player.restoreBuilder().name("SB").playerId("sb").chips(0).holeCards(List.of())
                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(0)
                .hasFolded(true).allIn(false).out(true).disconnected(false).readyForNextHand(false).build();
        Player bb = Player.restoreBuilder().name("BB").playerId("bb").chips(1000).holeCards(List.of())
                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(0)
                .hasFolded(false).allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
        HandSettlementResult settled = new HandSettlementResult(Map.of(), Map.of(), Set.of(), 10, true);
        Game terminal = Game.restoreBuilder().gameId(GAME_ID).players(List.of(sb, bb)).activePlayerIds(List.of("bb"))
                .remainingDeck(List.of()).communityCards(List.of()).pot(10).dealerPosition(0).smallBlindPosition(0)
                .bigBlindPosition(1).currentPlayerPosition(0).currentHighestBet(0)
                .currentPhase(GamePhase.SHOWDOWN).gameOver(true).smallBlind(5).bigBlind(10)
                .handContributions(Map.of()).preHandCarryOver(10).lastSettlementResult(settled)
                .readyCountdownActive(false).everyoneHasHadInitialTurn(true).actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of()).handEvaluator(handEvaluator).build();
        terminal.settleGameEnd(bb, Set.of());

        GameEndResponse result = gameStateService.recordGameEnd(terminal, bb, true);
        gameStateService.sendGameEnd(result);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());

        GameEndResponse sent = assertInstanceOf(GameEndResponse.class, captor.getValue());
        assertEquals(1010, sent.winnerChips());
        assertEquals(0, sent.finalState().pot());
        assertEquals(1010, sent.finalState().players().stream().filter(PublicPlayerState::isWinner)
                .findFirst().orElseThrow().chips());
        terminal.settleGameEnd(bb, Set.of());
        assertEquals(1010, bb.getChips());
    }

    @Test
    void showdownDtoUsesPerPlayerContestedAwardsInsteadOfEqualShareGuess() {
        Player mainWinner = testPlayers.get(0);
        Player sideWinner = testPlayers.get(1);
        mainWinner.setHandRank(HandRank.HIGH_CARD);
        sideWinner.setHandRank(HandRank.ONE_PAIR);
        HandSettlementResult settlement = new HandSettlementResult(
                Map.of(mainWinner.getPlayerId(), 150, sideWinner.getPlayerId(), 200),
                Map.of(), Set.of(mainWinner.getPlayerId(), sideWinner.getPlayerId()), 0, true);
        Game settled = Game.restoreBuilder().gameId(GAME_ID).players(testPlayers)
                .activePlayerIds(testPlayers.stream().map(Player::getPlayerId).toList()).remainingDeck(List.of())
                .communityCards(List.of()).pot(0).dealerPosition(0).smallBlindPosition(0).bigBlindPosition(1)
                .currentPlayerPosition(0).currentHighestBet(0).currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false).smallBlind(5).bigBlind(10).handContributions(Map.of())
                .lastSettlementResult(settlement).preHandCarryOver(0).readyCountdownActive(false)
                .everyoneHasHadInitialTurn(true).actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                .handEvaluator(handEvaluator).build();
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

        gameStateService.broadcastShowdownResults(settled);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());

        PublicGameStateResponse response = (PublicGameStateResponse) captor.getValue();
        assertEquals(Set.of("Player1", "Player2"), Set.copyOf(response.winners()));
        assertNull(response.winningsPerPlayer());
        assertEquals(150, response.players().stream().filter(p -> p.id().equals(mainWinner.getPlayerId()))
                .findFirst().orElseThrow().chipsWon());
        assertEquals(200, response.players().stream().filter(p -> p.id().equals(sideWinner.getPlayerId()))
                .findFirst().orElseThrow().chipsWon());
    }

    @Test
    void genericRestSnapshotRetainsActualUnequalSettlementAndSuppressesActor() {
        List<Player> players = List.of(
                settledPlayer("Main", "main", 50), settledPlayer("Side1", "side1", 100),
                settledPlayer("Side2", "side2", 150), settledPlayer("Side3", "side3", 150));
        Game settled = Game.restoreBuilder().gameId(GAME_ID).players(players)
                .activePlayerIds(players.stream().map(Player::getPlayerId).toList()).remainingDeck(List.of())
                .communityCards(List.of()).pot(450).dealerPosition(0).smallBlindPosition(0).bigBlindPosition(1)
                .currentPlayerPosition(0).currentHighestBet(150).currentPhase(GamePhase.RIVER)
                .gameOver(false).smallBlind(5).bigBlind(10)
                .handContributions(Map.of("main", 50, "side1", 100, "side2", 150, "side3", 150))
                .preHandCarryOver(0).readyCountdownActive(true).everyoneHasHadInitialTurn(true)
                .actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of()).handEvaluator(handEvaluator).build();
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        when(handEvaluator.getBestHand(any(), any()))
                .thenReturn(new HandEvaluationResult(List.of(), HandRank.ROYAL_FLUSH))
                .thenReturn(new HandEvaluationResult(List.of(), HandRank.STRAIGHT))
                .thenReturn(new HandEvaluationResult(List.of(), HandRank.ONE_PAIR))
                .thenReturn(new HandEvaluationResult(List.of(), HandRank.HIGH_CARD));

        settled.conductShowdown();
        ArgumentCaptor<Object> initialCaptor = ArgumentCaptor.forClass(Object.class);
        gameStateService.broadcastShowdownResults(settled);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), initialCaptor.capture());
        PublicGameStateResponse initial = (PublicGameStateResponse) initialCaptor.getValue();
        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(settled);

        assertEquals(List.of("Main", "Side1", "Side2"), response.winners());
        assertNull(response.winningsPerPlayer(), "Unequal side-pot awards have no equal-share scalar");
        assertTrue(response.handSettled());
        assertEquals(0, response.carryOverAmount());
        assertNull(response.currentPlayerId());
        assertNull(response.currentPlayerName());
        assertEquals(List.of(), response.legalActions());
        assertEquals(Map.of("main", 200, "side1", 150, "side2", 100), response.players().stream()
                .filter(PublicPlayerState::isWinner)
                .collect(Collectors.toMap(PublicPlayerState::id, PublicPlayerState::chipsWon)));
        assertTrue(response.players().stream().noneMatch(p -> p.isCurrentPlayer()));
        PublicPlayerState initialMain = initial.players().stream().filter(p -> p.id().equals("main")).findFirst().orElseThrow();
        PublicPlayerState readyMain = response.players().stream().filter(p -> p.id().equals("main")).findFirst().orElseThrow();
        assertEquals(initialMain.handRank(), readyMain.handRank());
        assertEquals(initialMain.bestHand(), readyMain.bestHand());
        assertEquals(initialMain.holeCards(), readyMain.holeCards());
    }

    @Test
    void settledReadySnapshotStillExposesGameLevelClaimWinWhenPeerDisconnects() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        Player connected = testPlayers.getFirst();
        Player disconnected = testPlayers.get(1);
        disconnected.setDisconnected(true);
        Game settled = Game.restoreBuilder().gameId(GAME_ID).players(testPlayers)
                .activePlayerIds(testPlayers.stream().map(Player::getPlayerId).toList()).remainingDeck(List.of())
                .communityCards(List.of()).pot(0).dealerPosition(0).smallBlindPosition(0).bigBlindPosition(1)
                .currentPlayerPosition(0).currentHighestBet(0).currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false).smallBlind(5).bigBlind(10).handContributions(Map.of()).preHandCarryOver(0)
                .lastSettlementResult(new HandSettlementResult(Map.of(connected.getPlayerId(), 50,
                        disconnected.getPlayerId(), 50), Map.of(), Set.of(connected.getPlayerId(), disconnected.getPlayerId()),
                        0, true))
                .readyCountdownActive(true).readyCountdownDeadlineEpochMs(12345L)
                .everyoneHasHadInitialTurn(true).actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                .handEvaluator(handEvaluator).build();

        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(settled);

        assertEquals(Boolean.TRUE, response.claimWinAvailable());
        assertEquals(connected.getName(), response.claimWinPlayerName());
        assertEquals(12345L, response.readyCountdownDeadlineEpochMs());
        assertTrue(response.handSettled());
    }

    @Test
    void genericSettledVoidSnapshotCarriesRemainderWithoutRevealingStaleCards() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        testPlayers.getFirst().setHandRank(HandRank.ROYAL_FLUSH);
        testPlayers.getFirst().setBestHand(List.of(new Card(Rank.ACE,
                Suit.SPADES)));
        Game voidGame = Game.restoreBuilder().gameId(GAME_ID).players(testPlayers)
                .activePlayerIds(testPlayers.stream().map(Player::getPlayerId).toList()).remainingDeck(List.of())
                .communityCards(List.of()).pot(100).dealerPosition(0).smallBlindPosition(0).bigBlindPosition(1)
                .currentPlayerPosition(0).currentHighestBet(0).currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false).smallBlind(5).bigBlind(10).handContributions(Map.of()).preHandCarryOver(0)
                .lastSettlementResult(new HandSettlementResult(Map.of(), Map.of(), Set.of(), 100, true))
                .readyCountdownActive(true).everyoneHasHadInitialTurn(true).actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of()).handEvaluator(handEvaluator).build();

        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(voidGame);

        assertTrue(response.handSettled());
        assertEquals(List.of(), response.winners());
        assertEquals(100, response.carryOverAmount());
        assertNull(response.currentPlayerId());
        assertTrue(response.players().stream().allMatch(player -> player.holeCards() == null
                && player.handRank() == null && player.bestHand() == null));
    }

    @Test
    void uncontestedReadyProjectionDoesNotRevealPriorOutSpectatorsCards() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        Card winnerCard = new Card(Rank.ACE, Suit.SPADES);
        Card oldCard = new Card(Rank.KING, Suit.HEARTS);
        Player winner = Player.restoreBuilder().name("Player1").playerId("p1").chips(100)
                .holeCards(List.of(winnerCard)).bestHand(List.of()).handRank(HandRank.NO_HAND)
                .currentBet(0).hasFolded(false).allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
        Player oldOut = Player.restoreBuilder().name("Player2").playerId("p2").chips(100)
                .holeCards(List.of(oldCard)).bestHand(List.of(new Card(Rank.ACE,
                        Suit.DIAMONDS))).handRank(HandRank.ROYAL_FLUSH)
                .currentBet(0).hasFolded(false).allIn(false).out(true).disconnected(false).readyForNextHand(false).build();
        List<Player> players = List.of(winner, oldOut);
        Game settled = Game.restoreBuilder().gameId(GAME_ID).players(players)
                .activePlayerIds(List.of(winner.getPlayerId())).remainingDeck(List.of()).communityCards(List.of())
                .pot(0).dealerPosition(0).smallBlindPosition(0).bigBlindPosition(1).currentPlayerPosition(0)
                .currentHighestBet(0).currentPhase(GamePhase.SHOWDOWN).gameOver(false)
                .smallBlind(5).bigBlind(10).handContributions(Map.of()).preHandCarryOver(0)
                .lastSettlementResult(new HandSettlementResult(Map.of(winner.getPlayerId(), 150), Map.of(),
                        Set.of(winner.getPlayerId()), 0, true))
                .readyCountdownActive(true).everyoneHasHadInitialTurn(true).actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of()).handEvaluator(handEvaluator).build();
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

        gameStateService.broadcastShowdownResults(settled);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        PublicGameStateResponse initial = (PublicGameStateResponse) captor.getValue();
        PublicGameStateResponse ready = gameStateService.getPublicGameStateSnapshot(settled);

        assertNull(initial.players().stream().filter(p -> p.id().equals(winner.getPlayerId())).findFirst().orElseThrow().holeCards());
        assertNull(ready.players().stream().filter(p -> p.id().equals(winner.getPlayerId())).findFirst().orElseThrow().holeCards());
        assertNull(ready.players().stream().filter(p -> p.id().equals(oldOut.getPlayerId())).findFirst().orElseThrow().holeCards());
    }

    @Test
    void getPublicGameStateSnapshot_DuringShowdownPhase_NullifiesCurrentPlayerAndTurnPointers() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        Game showdownGame = Game.restoreBuilder().gameId(GAME_ID).players(testPlayers)
                .activePlayerIds(testPlayers.stream().map(Player::getPlayerId).toList())
                .remainingDeck(List.of())
                .communityCards(List.of())
                .pot(100)
                .dealerPosition(0)
                .smallBlindPosition(0)
                .bigBlindPosition(1)
                .currentPlayerPosition(0)
                .currentHighestBet(0)
                .currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false)
                .smallBlind(5)
                .bigBlind(10)
                .handContributions(Map.of())
                .preHandCarryOver(0)
                .lastSettlementResult(null)
                .readyCountdownActive(false)
                .everyoneHasHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of())
                .handEvaluator(handEvaluator)
                .build();

        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(showdownGame);

        assertNull(response.currentPlayerName(), "currentPlayerName must be null during SHOWDOWN phase");
        assertNull(response.currentPlayerId(), "currentPlayerId must be null during SHOWDOWN phase");
        assertTrue(response.legalActions().isEmpty(), "legalActions must be empty during SHOWDOWN phase");
        assertTrue(response.players().stream().noneMatch(PublicPlayerState::isCurrentPlayer),
                "No player must be marked isCurrentPlayer == true during SHOWDOWN phase");
    }

    @Test
    void getPublicGameStateSnapshot_DuringReadyCountdownActive_NullifiesCurrentPlayerAndTurnPointers() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        Game countdownGame = Game.restoreBuilder().gameId(GAME_ID).players(testPlayers)
                .activePlayerIds(testPlayers.stream().map(Player::getPlayerId).toList())
                .remainingDeck(List.of())
                .communityCards(List.of())
                .pot(100)
                .dealerPosition(0)
                .smallBlindPosition(0)
                .bigBlindPosition(1)
                .currentPlayerPosition(0)
                .currentHighestBet(0)
                .currentPhase(GamePhase.RIVER)
                .gameOver(false)
                .smallBlind(5)
                .bigBlind(10)
                .handContributions(Map.of())
                .preHandCarryOver(0)
                .lastSettlementResult(null)
                .readyGateState(ReadyGateState.COUNTING_DOWN)
                .readyCountdownActive(true)
                .readyCountdownDeadlineEpochMs(System.currentTimeMillis() + 5000)
                .everyoneHasHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of())
                .handEvaluator(handEvaluator)
                .build();

        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(countdownGame);

        assertNull(response.currentPlayerName(), "currentPlayerName must be null during ready countdown");
        assertNull(response.currentPlayerId(), "currentPlayerId must be null during ready countdown");
        assertTrue(response.legalActions().isEmpty(), "legalActions must be empty during ready countdown");
        assertTrue(response.players().stream().noneMatch(PublicPlayerState::isCurrentPlayer),
                "No player must be marked isCurrentPlayer == true during ready countdown");
    }

    @Test
    void broadcastGameState_DuringShowdownWithReadyCountdown_NullifiesCurrentPlayerAndTurnPointers() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        Game showdownGame = Game.restoreBuilder().gameId(GAME_ID).players(testPlayers)
                .activePlayerIds(testPlayers.stream().map(Player::getPlayerId).toList())
                .remainingDeck(List.of())
                .communityCards(List.of())
                .pot(100)
                .dealerPosition(0)
                .smallBlindPosition(0)
                .bigBlindPosition(1)
                .currentPlayerPosition(0)
                .currentHighestBet(0)
                .currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false)
                .smallBlind(5)
                .bigBlind(10)
                .handContributions(Map.of())
                .preHandCarryOver(0)
                .lastSettlementResult(null)
                .readyGateState(ReadyGateState.COUNTING_DOWN)
                .readyCountdownActive(true)
                .readyCountdownDeadlineEpochMs(System.currentTimeMillis() + 5000)
                .everyoneHasHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of())
                .handEvaluator(handEvaluator)
                .build();

        gameStateService.broadcastGameState(showdownGame);

        ArgumentCaptor<PublicGameStateResponse> captor = ArgumentCaptor.forClass(PublicGameStateResponse.class);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        PublicGameStateResponse response = captor.getValue();

        assertNull(response.currentPlayerName(), "currentPlayerName must be null during showdown ready countdown broadcast");
        assertNull(response.currentPlayerId(), "currentPlayerId must be null during showdown ready countdown broadcast");
        assertTrue(response.legalActions().isEmpty(), "legalActions must be empty during showdown ready countdown broadcast");
        assertTrue(response.players().stream().noneMatch(PublicPlayerState::isCurrentPlayer),
                "No player must be marked isCurrentPlayer == true during showdown ready countdown broadcast");
    }

    @Test
    void broadcastGameState_DuringShowdownReadyCountdown_PreservesWinnersChipsWonAndCardsOnReadyToggle() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        Player winner = testPlayers.get(0);
        Player loser = testPlayers.get(1);
        winner.setHandRank(HandRank.TWO_PAIR);
        winner.setBestHand(List.of(
                new Card(Rank.ACE, Suit.SPADES), new Card(Rank.ACE, Suit.HEARTS),
                new Card(Rank.KING, Suit.CLUBS), new Card(Rank.KING, Suit.DIAMONDS),
                new Card(Rank.QUEEN, Suit.SPADES)));
        winner.getHoleCards().clear();
        winner.getHoleCards().addAll(List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.ACE, Suit.HEARTS)));

        loser.setHandRank(HandRank.ONE_PAIR);
        loser.setBestHand(List.of(
                new Card(Rank.JACK, Suit.SPADES), new Card(Rank.JACK, Suit.HEARTS),
                new Card(Rank.TEN, Suit.CLUBS), new Card(Rank.NINE, Suit.DIAMONDS),
                new Card(Rank.EIGHT, Suit.SPADES)));
        loser.getHoleCards().clear();
        loser.getHoleCards().addAll(List.of(new Card(Rank.JACK, Suit.SPADES), new Card(Rank.JACK, Suit.HEARTS)));

        HandSettlementResult settlement = new HandSettlementResult(
                Map.of(winner.getPlayerId(), 200),
                Map.of(),
                Set.of(winner.getPlayerId()),
                0,
                true);

        Game showdownGame = Game.restoreBuilder().gameId(GAME_ID).players(testPlayers)
                .activePlayerIds(testPlayers.stream().map(Player::getPlayerId).toList())
                .remainingDeck(List.of())
                .communityCards(List.of(
                        new Card(Rank.KING, Suit.CLUBS), new Card(Rank.KING, Suit.DIAMONDS),
                        new Card(Rank.QUEEN, Suit.SPADES), new Card(Rank.TEN, Suit.CLUBS),
                        new Card(Rank.NINE, Suit.DIAMONDS)))
                .pot(0)
                .dealerPosition(0)
                .smallBlindPosition(0)
                .bigBlindPosition(1)
                .currentPlayerPosition(0)
                .currentHighestBet(0)
                .currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false)
                .smallBlind(5)
                .bigBlind(10)
                .handContributions(Map.of())
                .preHandCarryOver(0)
                .lastSettlementResult(settlement)
                .readyCountdownActive(true)
                .readyCountdownDeadlineEpochMs(System.currentTimeMillis() + 5000)
                .everyoneHasHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of())
                .handEvaluator(handEvaluator)
                .build();

        // 1. Initial ready toggle (Player 1 marks ready)
        winner.setReadyForNextHand(true);
        gameStateService.broadcastGameState(showdownGame);

        ArgumentCaptor<PublicGameStateResponse> captor = ArgumentCaptor.forClass(PublicGameStateResponse.class);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        PublicGameStateResponse response = captor.getValue();

        assertEquals(List.of("Player1"), response.winners());
        assertEquals(200, response.winningsPerPlayer());
        assertTrue(response.handSettled());
        assertNull(response.currentPlayerName());
        assertNull(response.currentPlayerId());

        PublicPlayerState winnerState = response.players().stream()
                .filter(p -> p.id().equals(winner.getPlayerId())).findFirst().orElseThrow();
        PublicPlayerState loserState = response.players().stream()
                .filter(p -> p.id().equals(loser.getPlayerId())).findFirst().orElseThrow();

        assertTrue(winnerState.isWinner());
        assertEquals(200, winnerState.chipsWon());
        assertEquals(winner.getHoleCards(), winnerState.holeCards());
        assertEquals(HandRank.TWO_PAIR, winnerState.handRank());
        assertTrue(winnerState.isReadyForNextHand());
        assertFalse(winnerState.isCurrentPlayer());

        assertFalse(loserState.isWinner());
        assertEquals(0, loserState.chipsWon());
        assertEquals(loser.getHoleCards(), loserState.holeCards());
        assertEquals(HandRank.ONE_PAIR, loserState.handRank());
        assertFalse(loserState.isReadyForNextHand());
        assertFalse(loserState.isCurrentPlayer());

        // 2. Intermediate ready toggle (Player 1 unmarks ready)
        winner.setReadyForNextHand(false);
        reset(messagingTemplate);
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        gameStateService.broadcastGameState(showdownGame);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        PublicGameStateResponse unreadyResponse = captor.getValue();

        assertEquals(List.of("Player1"), unreadyResponse.winners());
        assertEquals(200, unreadyResponse.winningsPerPlayer());
        assertTrue(unreadyResponse.handSettled());

        PublicPlayerState unreadyWinner = unreadyResponse.players().stream()
                .filter(p -> p.id().equals(winner.getPlayerId())).findFirst().orElseThrow();
        assertFalse(unreadyWinner.isReadyForNextHand());
        assertTrue(unreadyWinner.isWinner());
        assertEquals(200, unreadyWinner.chipsWon());
        assertEquals(winner.getHoleCards(), unreadyWinner.holeCards());
    }

    @Test
    void getPublicGameStateSnapshot_DuringShowdownReadyCountdown_RetainsShowdownSettlementResults() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        Player winner = testPlayers.get(0);
        Player loser = testPlayers.get(1);
        winner.setHandRank(HandRank.FULL_HOUSE);
        winner.setBestHand(List.of(
                new Card(Rank.ACE, Suit.SPADES), new Card(Rank.ACE, Suit.HEARTS),
                new Card(Rank.ACE, Suit.CLUBS), new Card(Rank.KING, Suit.DIAMONDS),
                new Card(Rank.KING, Suit.SPADES)));
        winner.getHoleCards().clear();
        winner.getHoleCards().addAll(List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.ACE, Suit.HEARTS)));

        loser.setHandRank(HandRank.FLUSH);
        loser.setBestHand(List.of(
                new Card(Rank.KING, Suit.HEARTS), new Card(Rank.QUEEN, Suit.HEARTS),
                new Card(Rank.TEN, Suit.HEARTS), new Card(Rank.EIGHT, Suit.HEARTS),
                new Card(Rank.TWO, Suit.HEARTS)));
        loser.getHoleCards().clear();
        loser.getHoleCards().addAll(List.of(new Card(Rank.QUEEN, Suit.HEARTS), new Card(Rank.TWO, Suit.HEARTS)));

        HandSettlementResult settlement = new HandSettlementResult(
                Map.of(winner.getPlayerId(), 300),
                Map.of(),
                Set.of(winner.getPlayerId()),
                0,
                true);

        Game countdownGame = Game.restoreBuilder().gameId(GAME_ID).players(testPlayers)
                .activePlayerIds(testPlayers.stream().map(Player::getPlayerId).toList())
                .remainingDeck(List.of())
                .communityCards(List.of())
                .pot(0)
                .dealerPosition(0)
                .smallBlindPosition(0)
                .bigBlindPosition(1)
                .currentPlayerPosition(0)
                .currentHighestBet(0)
                .currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false)
                .smallBlind(5)
                .bigBlind(10)
                .handContributions(Map.of())
                .preHandCarryOver(0)
                .lastSettlementResult(settlement)
                .readyCountdownActive(true)
                .readyCountdownDeadlineEpochMs(System.currentTimeMillis() + 6000)
                .everyoneHasHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of())
                .handEvaluator(handEvaluator)
                .build();

        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(countdownGame);

        assertEquals(List.of("Player1"), response.winners());
        assertEquals(300, response.winningsPerPlayer());
        assertTrue(response.handSettled());
        assertNull(response.currentPlayerName());
        assertNull(response.currentPlayerId());
        assertTrue(response.legalActions().isEmpty());

        PublicPlayerState p1 = response.players().stream()
                .filter(p -> p.id().equals(winner.getPlayerId())).findFirst().orElseThrow();
        PublicPlayerState p2 = response.players().stream()
                .filter(p -> p.id().equals(loser.getPlayerId())).findFirst().orElseThrow();

        assertTrue(p1.isWinner());
        assertEquals(300, p1.chipsWon());
        assertEquals(winner.getHoleCards(), p1.holeCards());
        assertEquals(HandRank.FULL_HOUSE, p1.handRank());
        assertFalse(p1.isCurrentPlayer());

        assertFalse(p2.isWinner());
        assertEquals(0, p2.chipsWon());
        assertEquals(loser.getHoleCards(), p2.holeCards());
        assertEquals(HandRank.FLUSH, p2.handRank());
        assertFalse(p2.isCurrentPlayer());
    }

    private Game settledGame(
            String gameId,
            List<Player> players,
            Map<String, Integer> contestedAwards,
            Set<String> winnerIds) {
        return Game.restoreBuilder()
                .gameId(gameId)
                .players(players)
                .activePlayerIds(players.stream().map(Player::getPlayerId).toList())
                .remainingDeck(List.of())
                .communityCards(List.of())
                .pot(0)
                .dealerPosition(0)
                .smallBlindPosition(0)
                .bigBlindPosition(Math.min(1, players.size() - 1))
                .currentPlayerPosition(0)
                .currentHighestBet(0)
                .currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false)
                .smallBlind(5)
                .bigBlind(10)
                .handContributions(Map.of())
                .preHandCarryOver(0)
                .lastSettlementResult(new HandSettlementResult(contestedAwards, Map.of(), winnerIds, 0, true))
                .readyCountdownActive(false)
                .everyoneHasHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of())
                .handEvaluator(handEvaluator)
                .build();
    }

    private Player settledPlayer(String name, String id, int contribution) {
        return Player.restoreBuilder().name(name).playerId(id).chips(0).holeCards(List.of(
                        new Card(Rank.ACE, Suit.SPADES),
                        new Card(Rank.KING, Suit.HEARTS)))
                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(contribution)
                .hasFolded(false).allIn(true).out(false).disconnected(false).readyForNextHand(false).build();
    }

    /**
     * Protects the contract that a forfeit game end does not reveal any hole cards.
     */
    @Test
    void recordGameEnd_ForfeitPath_ShouldNotRevealHoleCards() {
        Player winner = testPlayers.getFirst();

        GameEndResponse gameEndResponse = gameStateService.recordGameEnd(testGame, winner, true);
        assertTrue(gameEndResponse.isForfeit());
        PublicGameStateResponse finalState = gameEndResponse.finalState();
        assertNotNull(finalState);

        assertTrue(finalState.players().stream().allMatch(p -> p.holeCards() == null));
    }

    // ==================== Edge Case Tests ====================

    /**
     * Protects the contract that broadcast showdown results with folded player marks them as FOLDED.
     */
    @Test
    void broadcastShowdownResults_WithFoldedPlayer_ShouldMarkAsFolded() {
        testPlayers.get(1).doAction(PlayerAction.FOLD, 0, 0);
        Player winner = testPlayers.getFirst();
        Game settled = settledGame(GAME_ID, testPlayers, Map.of(winner.getPlayerId(), 100), Set.of(winner.getPlayerId()));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        gameStateService.broadcastShowdownResults(settled);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        PublicGameStateResponse response = assertInstanceOf(PublicGameStateResponse.class, captor.getValue());
        assertEquals("FOLDED", response.players().get(1).status());
    }

    /**
     * Protects the contract that broadcast showdown results with all-in player marks them as ALL_IN.
     */
    @Test
    void broadcastShowdownResults_WithAllInPlayer_ShouldMarkAsAllIn() {
        testPlayers.getFirst().doAction(PlayerAction.ALL_IN, 0, 0);
        Player winner = testPlayers.getFirst();
        Game settled = settledGame(GAME_ID, testPlayers, Map.of(winner.getPlayerId(), 100), Set.of(winner.getPlayerId()));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        gameStateService.broadcastShowdownResults(settled);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        PublicGameStateResponse response = assertInstanceOf(PublicGameStateResponse.class, captor.getValue());
        assertEquals("ALL_IN", response.players().getFirst().status());
    }

    // ==================== GamePhase Tests ====================

    /**
     * Protects the contract that broadcast game state during preflop should broadcast.
     */
    @Test
    void broadcastGameState_DuringPreflop_ShouldBroadcast() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        gameStateService.broadcastGameState(testGame);

        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    /**
     * Protects the contract that broadcast game state should include single small blind and big blind flags.
     */
    @Test
    void broadcastGameState_ShouldIncludeSingleSmallBlindAndBigBlindFlags() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        testGame.dealHoleCards();
        testGame.postBlinds();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

        gameStateService.broadcastGameState(testGame);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        Object payload = captor.getValue();
        assertInstanceOf(PublicGameStateResponse.class, payload);

        PublicGameStateResponse response = (PublicGameStateResponse) payload;
        long smallBlindCount = response.players().stream().filter(PublicPlayerState::isSmallBlind).count();
        long bigBlindCount = response.players().stream().filter(PublicPlayerState::isBigBlind).count();

        assertEquals(1, smallBlindCount);
        assertEquals(1, bigBlindCount);
    }

    /**
     * Protects the contract that broadcast game state after dealing flop should broadcast.
     */
    @Test
    void broadcastGameState_AfterDealingFlop_ShouldBroadcast() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        testGame.dealHoleCards();
        testGame.postBlinds();
        testGame.dealFlop();

        gameStateService.broadcastGameState(testGame);

        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
        assertEquals(3, testGame.getCommunityCards().size());
    }

    /**
     * Protects the contract that broadcast game state after dealing flop should keep blind flags.
     */
    @Test
    void broadcastGameState_AfterDealingFlop_ShouldKeepBlindFlags() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        testGame.dealHoleCards();
        testGame.postBlinds();
        testGame.dealFlop();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

        gameStateService.broadcastGameState(testGame);

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

    /**
     * Protects the contract that multiple broadcasts should all succeed.
     */
    @Test
    void multipleBroadcasts_ShouldAllSucceed() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        gameStateService.broadcastGameState(testGame);
        gameStateService.broadcastGameState(testGame);
        gameStateService.broadcastGameState(testGame);

        // 3 public broadcasts via convertAndSend + 3*(2 name-private) via convertAndSendToUser
        verify(messagingTemplate, times(3)).convertAndSend(anyString(), any(Object.class));
        verify(messagingTemplate, times(6)).convertAndSendToUser(anyString(), anyString(), any(Object.class));
    }

    /**
     * Protects the contract that broadcast game state increments stateRevision monotonically.
     */
    @Test
    void broadcastGameState_IncrementsStateRevisionMonotonically() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        ArgumentCaptor<PublicGameStateResponse> captor = ArgumentCaptor.forClass(PublicGameStateResponse.class);

        gameStateService.broadcastGameState(testGame);
        gameStateService.broadcastGameState(testGame);

        verify(messagingTemplate, times(2)).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        List<PublicGameStateResponse> responses = captor.getAllValues();
        assertEquals(1, responses.get(0).stateRevision());
        assertEquals(2, responses.get(1).stateRevision());
        assertEquals(2, testGame.getStateRevision());
    }

    /**
     * Protects the contract that broadcast game state when current player missing should not throw.
     */
    @Test
    void broadcastGameState_WhenCurrentPlayerMissing_ShouldNotThrow() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        testGame.getActivePlayers().clear();

        assertDoesNotThrow(() -> gameStateService.broadcastGameState(testGame));
    }

    // ==================== Status Precedence Tests ====================

    @Test
    void resolvePlayerStatus_FoldedAndDisconnected_ReturnsFolded() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        Player player = Player.restoreBuilder()
                .name("Player1")
                .playerId(testPlayers.get(0).getPlayerId())
                .chips(100)
                .holeCards(List.of())
                .bestHand(List.of())
                .hasFolded(true)
                .disconnected(true)
                .build();
        testGame.getPlayers().set(0, player);

        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(testGame);
        PublicPlayerState p1State = response.players().stream()
                .filter(p -> p.id().equals(player.getPlayerId()))
                .findFirst().orElseThrow();

        assertEquals("FOLDED", p1State.status());
    }

    @Test
    void resolvePlayerStatus_OutAndDisconnected_ReturnsOut() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        Player player = Player.restoreBuilder()
                .name("Player1")
                .playerId(testPlayers.get(0).getPlayerId())
                .chips(0)
                .holeCards(List.of())
                .bestHand(List.of())
                .out(true)
                .disconnected(true)
                .build();
        testGame.getPlayers().set(0, player);

        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(testGame);
        PublicPlayerState p1State = response.players().stream()
                .filter(p -> p.id().equals(player.getPlayerId()))
                .findFirst().orElseThrow();

        assertEquals("OUT", p1State.status());
    }

    @Test
    void resolvePlayerStatus_OutAndFolded_ReturnsOut() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        Player player = Player.restoreBuilder()
                .name("Player1")
                .playerId(testPlayers.get(0).getPlayerId())
                .chips(0)
                .holeCards(List.of())
                .bestHand(List.of())
                .out(true)
                .hasFolded(true)
                .build();
        testGame.getPlayers().set(0, player);

        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(testGame);
        PublicPlayerState p1State = response.players().stream()
                .filter(p -> p.id().equals(player.getPlayerId()))
                .findFirst().orElseThrow();

        assertEquals("OUT", p1State.status());
    }

    @Test
    void resolvePlayerStatus_DisconnectedAndAllIn_ReturnsDisconnected() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        Player player = Player.restoreBuilder()
                .name("Player1")
                .playerId(testPlayers.get(0).getPlayerId())
                .chips(0)
                .holeCards(List.of())
                .bestHand(List.of())
                .disconnected(true)
                .allIn(true)
                .build();
        testGame.getPlayers().set(0, player);

        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(testGame);
        PublicPlayerState p1State = response.players().stream()
                .filter(p -> p.id().equals(player.getPlayerId()))
                .findFirst().orElseThrow();

        assertEquals("DISCONNECTED", p1State.status());
    }

    @Test
    void resolvePlayerStatus_AllIn_ReturnsAllIn() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        Player player = Player.restoreBuilder()
                .name("Player1")
                .playerId(testPlayers.get(0).getPlayerId())
                .chips(0)
                .holeCards(List.of())
                .bestHand(List.of())
                .allIn(true)
                .build();
        testGame.getPlayers().set(0, player);

        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(testGame);
        PublicPlayerState p1State = response.players().stream()
                .filter(p -> p.id().equals(player.getPlayerId()))
                .findFirst().orElseThrow();

        assertEquals("ALL_IN", p1State.status());
    }

    @Test
    void resolvePlayerStatus_Active_ReturnsActive() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        Player player = Player.restoreBuilder()
                .name("Player1")
                .playerId(testPlayers.get(0).getPlayerId())
                .chips(100)
                .holeCards(List.of())
                .bestHand(List.of())
                .build();
        testGame.getPlayers().set(0, player);

        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(testGame);
        PublicPlayerState p1State = response.players().stream()
                .filter(p -> p.id().equals(player.getPlayerId()))
                .findFirst().orElseThrow();

        assertEquals("ACTIVE", p1State.status());
    }

    // ==================== Private Player State Tests ====================

    @Test
    void getPrivatePlayerStateSnapshot_ShouldIncludeHandNumberAndDefensivelyCopyCards() {
        testGame.resetForNewHand();
        Player player = testPlayers.get(0);
        player.getHoleCards().add(new Card(Rank.ACE, Suit.HEARTS));
        player.getHoleCards().add(new Card(Rank.KING, Suit.SPADES));

        PrivatePlayerState snapshot = gameStateService.getPrivatePlayerStateSnapshot(testGame, player.getName());

        assertNotNull(snapshot);
        assertEquals(player.getPlayerId(), snapshot.playerId());
        assertEquals(testGame.getHandNumber(), snapshot.handNumber());
        assertEquals(2, snapshot.holeCards().size());
        assertEquals(player.getHoleCards(), snapshot.holeCards());

        // Mutating the original player cards list does not mutate the snapshot
        player.getHoleCards().add(new Card(Rank.QUEEN, Suit.CLUBS));
        assertEquals(2, snapshot.holeCards().size());
    }

    @Test
    void getPrivatePlayerStateSnapshot_WithNullCards_ReturnsEmptyList() {
        Player player1 = mock(Player.class);
        when(player1.getName()).thenReturn("Player1");
        when(player1.getPlayerId()).thenReturn("p1-id");
        when(player1.getHoleCards()).thenReturn(null);

        Player player2 = new Player("Player2", UUID.randomUUID().toString(), 100);

        List<Player> players = List.of(player1, player2);
        Game game = new Game(GAME_ID, players, 6, 5, 10, handEvaluator);

        PrivatePlayerState snapshot = gameStateService.getPrivatePlayerStateSnapshot(game, "Player1");

        assertNotNull(snapshot);
        assertEquals("p1-id", snapshot.playerId());
        assertEquals(game.getHandNumber(), snapshot.handNumber());
        assertNotNull(snapshot.holeCards());
        assertTrue(snapshot.holeCards().isEmpty());
    }

    @Test
    void broadcastGameState_ShouldSendPrivateStateWithHandNumber() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        testGame.resetForNewHand();

        gameStateService.broadcastGameState(testGame);

        ArgumentCaptor<PrivatePlayerState> captor = ArgumentCaptor.forClass(PrivatePlayerState.class);
        for (Player player : testGame.getPlayers()) {
            String compositeName = player.getName() + ":" + GAME_ID + ":" + testRoom.getMembershipId(player.getName());
            verify(messagingTemplate).convertAndSendToUser(
                    eq(compositeName),
                    eq("/queue/private"),
                    captor.capture());

            PrivatePlayerState sentState = captor.getValue();
            assertEquals(player.getPlayerId(), sentState.playerId());
            assertEquals(testGame.getHandNumber(), sentState.handNumber());
        }
    }

    @Test
    @DisplayName("recordGameEnd preserves game's clamped [2, 10] capacity across restored out-of-range settings")
    void recordGameEnd_PreservesClampedMaxPlayersFromGame() {
        Player winner = testPlayers.getFirst();
        int[] outOfRangeValues = {
                Integer.MIN_VALUE, -999999, -1, 0, 1, 11, 12, 100, 999999, Integer.MAX_VALUE
        };

        for (int val : outOfRangeValues) {
            Game restored = Game.restoreBuilder()
                    .gameId(GAME_ID)
                    .maxPlayers(val)
                    .players(testPlayers)
                    .activePlayerIds(testPlayers.stream().map(Player::getPlayerId).toList())
                    .remainingDeck(List.of())
                    .communityCards(List.of())
                    .pot(0)
                    .dealerPosition(0)
                    .smallBlindPosition(0)
                    .bigBlindPosition(1)
                    .currentPlayerPosition(0)
                    .currentHighestBet(0)
                    .currentPhase(GamePhase.SHOWDOWN)
                    .gameOver(true)
                    .smallBlind(5)
                    .bigBlind(10)
                    .handContributions(Map.of())
                    .preHandCarryOver(0)
                    .readyCountdownActive(false)
                    .everyoneHasHadInitialTurn(true)
                    .actedPlayersInRound(Set.of())
                    .scheduledTaskDeadlines(Map.of())
                    .handEvaluator(handEvaluator)
                    .build();

            GameEndResponse response = gameStateService.buildGameEndResponse(restored, winner, false);
            int maxPlayers = response.finalState().maxPlayers();
            assertEquals(2, maxPlayers,
                    "val=" + val + " should clamp to retained player count (2), got: " + maxPlayers);
        }

        Game validCapacityGame = new Game("four-player-game", List.of(
                new Player("P1", "id-1", 100),
                new Player("P2", "id-2", 100),
                new Player("P3", "id-3", 100),
                new Player("P4", "id-4", 100)), 8, 5, 10, handEvaluator);
        GameEndResponse response = gameStateService.recordGameEnd(validCapacityGame, validCapacityGame.getPlayers().getFirst(), false);
        assertEquals(8, response.finalState().maxPlayers());
        assertEquals(FIXED_INSTANT.toEpochMilli(), response.finalState().serverTimeEpochMs());
    }

    @Test
    @DisplayName("buildGameEndResponse does not increment stateRevision while recordGameEnd increments it")
    void buildGameEndResponse_DoesNotIncrementStateRevision_WhileRecordGameEndDoes() {
        Player winner = testPlayers.getFirst();
        Game settled = settledGame(GAME_ID, testPlayers, Map.of(winner.getPlayerId(), 100), Set.of(winner.getPlayerId()));

        long revisionBefore = settled.getStateRevision();
        GameEndResponse projected = gameStateService.buildGameEndResponse(settled, winner, false);
        assertNotNull(projected);
        assertEquals(revisionBefore, settled.getStateRevision(), "buildGameEndResponse must not increment stateRevision");
        assertEquals(revisionBefore, projected.finalState().stateRevision());

        GameEndResponse recorded = gameStateService.recordGameEnd(settled, winner, false);
        assertNotNull(recorded);
        assertEquals(revisionBefore + 1, settled.getStateRevision(), "recordGameEnd must increment stateRevision");
        assertEquals(revisionBefore + 1, recorded.finalState().stateRevision());
    }

    @Test
    @DisplayName("recordAbandonment and buildAbandonmentResponse build INACTIVITY closure and increment revision only on record")
    void recordAbandonment_AndBuildAbandonmentResponse_IncrementRevisionOnlyOnRecord() {
        long revisionBefore = testGame.getStateRevision();
        GameEndResponse preview = gameStateService.buildAbandonmentResponse(testGame);
        assertEquals(revisionBefore, testGame.getStateRevision());
        assertTrue(preview.abandoned());
        assertEquals("INACTIVITY", preview.reason());
        assertNull(preview.winner());

        GameEndResponse recorded = gameStateService.recordAbandonment(testGame);
        assertEquals(revisionBefore + 1, testGame.getStateRevision());
        assertEquals(revisionBefore + 1, recorded.finalState().stateRevision());
        assertTrue(recorded.abandoned());
        assertEquals("INACTIVITY", recorded.reason());

        gameStateService.sendGameEnd(recorded);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), eq(recorded));
    }

    @Test
    @DisplayName("Repeated ready/unready toggling during showdown retains settlement and suppresses currentPlayer")
    void readyFlapping_preservesSettlementIntegrity() {
        String roomAId = "room-alpha";
        Room roomA = new Room(roomAId, "Room A", "Alice", 6, 5, 10, 100, null);
        roomA.addPlayer("Alice");
        roomA.addPlayer("Bob");
        roomA.addPlayer("Charlie");
        when(roomService.getRoom(roomAId)).thenReturn(roomA);

        Player p1 = createIsolationPlayer("Alice", "p-1", 500, false);
        Player p2 = createIsolationPlayer("Bob", "p-2", 300, false);
        Player p3Folded = createIsolationPlayer("Charlie", "p-3", 200, true);
        p1.setHandRank(HandRank.FULL_HOUSE);
        p2.setHandRank(HandRank.FLUSH);
        p3Folded.setHandRank(HandRank.NO_HAND);

        HandSettlementResult settlement = new HandSettlementResult(
                Map.of("p-1", 450), Map.of(), Set.of("p-1"), 0, true);

        Game game = Game.restoreBuilder()
                .gameId(roomAId)
                .players(List.of(p1, p2, p3Folded))
                .activePlayerIds(List.of("p-1", "p-2"))
                .remainingDeck(List.of())
                .communityCards(List.of(
                        new Card(Rank.TEN, Suit.DIAMONDS),
                        new Card(Rank.TEN, Suit.CLUBS),
                        new Card(Rank.TEN, Suit.SPADES)))
                .pot(0)
                .dealerPosition(0)
                .smallBlindPosition(0)
                .bigBlindPosition(1)
                .currentPlayerPosition(1)
                .currentHighestBet(0)
                .currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false)
                .smallBlind(5)
                .bigBlind(10)
                .handContributions(Map.of())
                .preHandCarryOver(0)
                .lastSettlementResult(settlement)
                .readyCountdownActive(true)
                .readyCountdownDeadlineEpochMs(FIXED_INSTANT.toEpochMilli() + 10000)
                .everyoneHasHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of())
                .handEvaluator(handEvaluator)
                .build();

        gameStateService.broadcastShowdownResults(game);

        Random rng = new Random(42);
        for (int i = 0; i < 100; i++) {
            p1.setReadyForNextHand(rng.nextBoolean());
            p2.setReadyForNextHand(rng.nextBoolean());

            PublicGameStateResponse snapshot = gameStateService.getPublicGameStateSnapshot(game);

            assertNull(snapshot.currentPlayerName(), "Iteration " + i + ": currentPlayerName must be null during showdown countdown");
            assertNull(snapshot.currentPlayerId(), "Iteration " + i + ": currentPlayerId must be null during showdown countdown");
            assertTrue(snapshot.legalActions().isEmpty(), "Iteration " + i + ": legalActions must be empty");

            assertTrue(snapshot.handSettled(), "Iteration " + i + ": handSettled must remain true");
            assertEquals(List.of("Alice"), snapshot.winners(), "Iteration " + i + ": winners must remain [Alice]");
            assertEquals(450, snapshot.winningsPerPlayer(), "Iteration " + i + ": winningsPerPlayer must remain 450");

            PublicPlayerState pubP1 = snapshot.players().stream().filter(p -> p.id().equals("p-1")).findFirst().orElseThrow();
            PublicPlayerState pubP2 = snapshot.players().stream().filter(p -> p.id().equals("p-2")).findFirst().orElseThrow();
            PublicPlayerState pubP3 = snapshot.players().stream().filter(p -> p.id().equals("p-3")).findFirst().orElseThrow();

            assertFalse(pubP1.isCurrentPlayer());
            assertFalse(pubP2.isCurrentPlayer());
            assertFalse(pubP3.isCurrentPlayer());

            assertTrue(pubP1.isWinner());
            assertEquals(450, pubP1.chipsWon());
            assertNotNull(pubP1.holeCards(), "Winner holeCards must be revealed");

            assertFalse(pubP2.isWinner());
            assertEquals(0, pubP2.chipsWon());
            assertNotNull(pubP2.holeCards(), "Contender holeCards must be revealed");

            assertFalse(pubP3.isWinner());
            assertNull(pubP3.holeCards(), "Folded player holeCards must NEVER be revealed");
        }
    }

    @Test
    @DisplayName("Settled showdown in one room does not leak into an active hand in another room")
    void multiRoomIsolation_noSettlementLeak() {
        String roomBId = "room-beta";
        Room roomB = new Room(roomBId, "Room B", "HostB", 4, 10, 20, 200, null);
        roomB.addPlayer("Xavier");
        roomB.addPlayer("Yvonne");
        when(roomService.getRoom(roomBId)).thenReturn(roomB);

        Player pA1 = createIsolationPlayer("Alice", "p-a1", 500, false);
        Player pA2 = createIsolationPlayer("Bob", "p-a2", 300, false);
        Game gameA = Game.restoreBuilder()
                .gameId("room-alpha")
                .players(List.of(pA1, pA2))
                .activePlayerIds(List.of("p-a1", "p-a2"))
                .remainingDeck(List.of())
                .communityCards(List.of())
                .pot(100)
                .dealerPosition(0)
                .smallBlindPosition(0)
                .bigBlindPosition(1)
                .currentPlayerPosition(0)
                .currentHighestBet(0)
                .currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false)
                .smallBlind(5)
                .bigBlind(10)
                .handContributions(Map.of())
                .preHandCarryOver(0)
                .lastSettlementResult(new HandSettlementResult(Map.of("p-a1", 100), Map.of(), Set.of("p-a1"), 0, true))
                .readyCountdownActive(true)
                .everyoneHasHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of())
                .handEvaluator(handEvaluator)
                .build();

        gameStateService.broadcastShowdownResults(gameA);

        Player pB1 = createIsolationPlayer("Xavier", "p-b1", 400, false);
        Player pB2 = createIsolationPlayer("Yvonne", "p-b2", 400, false);
        Game gameB = Game.restoreBuilder()
                .gameId(roomBId)
                .players(List.of(pB1, pB2))
                .activePlayerIds(List.of("p-b1", "p-b2"))
                .remainingDeck(List.of())
                .communityCards(List.of(
                        new Card(Rank.TWO, Suit.CLUBS),
                        new Card(Rank.THREE, Suit.DIAMONDS),
                        new Card(Rank.FOUR, Suit.HEARTS)))
                .pot(40)
                .dealerPosition(0)
                .smallBlindPosition(0)
                .bigBlindPosition(1)
                .currentPlayerPosition(0)
                .currentHighestBet(20)
                .currentPhase(GamePhase.FLOP)
                .gameOver(false)
                .smallBlind(10)
                .bigBlind(20)
                .handContributions(Map.of())
                .preHandCarryOver(0)
                .lastSettlementResult(null)
                .readyCountdownActive(false)
                .everyoneHasHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of())
                .handEvaluator(handEvaluator)
                .build();

        PublicGameStateResponse snapshotB = gameStateService.getPublicGameStateSnapshot(gameB);

        assertFalse(snapshotB.handSettled(), "Room B must NOT be marked settled");
        assertNull(snapshotB.winners(), "Room B must have no winners");
        assertNotNull(snapshotB.currentPlayerName(), "Room B must have an active currentPlayer");
        assertEquals("Xavier", snapshotB.currentPlayerName());
    }

    @Test
    void broadcastGameStatePublishesRoomInactivityDeadlineAndActiveMemberPrivateState() {
        Room room = new Room("room", "Room", "Host", 6, 5, 10, 100, null);
        String hostMembership = room.addPlayer("Host");
        String aliceMembership = room.addPlayer("Alice");
        room.setInactivityDeadlineEpochMs(1_750_000_000_000L);

        RoomService rooms = mock(RoomService.class);
        when(rooms.getRoom("room")).thenReturn(room);
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        GameStateService projections = new GameStateService(rooms, template, FIXED_CLOCK);
        Game game = new Game("room", List.of(
                new Player("Alice", "alice-id", 100),
                new Player("Host", "host-id", 100)), 6, 5, 10, handEvaluator);
        game.dealHoleCards();

        projections.broadcastGameState(game);

        ArgumentCaptor<PublicGameStateResponse> publicCaptor = ArgumentCaptor.forClass(PublicGameStateResponse.class);
        verify(template).convertAndSend(eq("/game/room"), publicCaptor.capture());
        PublicGameStateResponse publicResponse = publicCaptor.getValue();
        assertEquals(1_750_000_000_000L, publicResponse.inactivityDeadlineEpochMs());
        assertEquals(FIXED_CLOCK.millis(), publicResponse.serverTimeEpochMs());

        for (Player player : game.getPlayers()) {
            String membership = player.getName().equals("Alice") ? aliceMembership : hostMembership;
            ArgumentCaptor<PrivatePlayerState> privateCaptor = ArgumentCaptor.forClass(PrivatePlayerState.class);
            verify(template).convertAndSendToUser(
                    eq(new PlayerPrincipal(player.getName(), "room", membership).getName()),
                    eq("/queue/private"),
                    privateCaptor.capture());
            assertEquals(player.getHoleCards(), privateCaptor.getValue().holeCards());
        }
    }

    @Test
    void retiredSocketCannotReceivePrivateMessagesForReplacementMembership() {
        PlayerPrincipal retired = new PlayerPrincipal("Alice", "room", "retired");
        Room room = new Room("room", "Room", "Host", 6, 5, 10, 100, null);
        room.addPlayer("Alice");
        PlayerPrincipal replacement = new PlayerPrincipal("Alice", "room", room.getMembershipId("Alice"));
        RoomService rooms = mock(RoomService.class);
        when(rooms.getRoom("room")).thenReturn(room);
        SimpMessagingTemplate publisher = mock(SimpMessagingTemplate.class);
        GameStateService projections = new GameStateService(rooms, publisher, FIXED_CLOCK);

        SimpUserRegistry registry = mock(SimpUserRegistry.class);
        SimpUser retiredUser = simpUser("retired-socket");
        SimpUser replacementUser = simpUser("replacement-socket", "replacement-second-tab");
        when(registry.getUser(anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            if (name.equals(retired.getName()) && name.equals(replacement.getName())) {
                Set<SimpSession> combined = new HashSet<>(retiredUser.getSessions());
                combined.addAll(replacementUser.getSessions());
                SimpUser both = mock(SimpUser.class);
                when(both.getSessions()).thenReturn(combined);
                return both;
            }
            return name.equals(replacement.getName()) ? replacementUser : retiredUser;
        });

        Game game = new Game("room", List.of(
                new Player("Alice", "alice-id", 100),
                new Player("Host", "host-id", 100)), 6, 5, 10, handEvaluator);
        projections.broadcastGameState(game);
        ArgumentCaptor<String> recipient = ArgumentCaptor.forClass(String.class);
        verify(publisher).convertAndSendToUser(recipient.capture(), eq("/queue/private"), any(PrivatePlayerState.class));

        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination("/user/" + recipient.getValue() + "/queue/private");
        var resolved = new DefaultUserDestinationResolver(registry).resolveDestination(
                MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()));
        assertNotNull(resolved);
        assertEquals(Set.of("/queue/private-userreplacement-socket", "/queue/private-userreplacement-second-tab"),
                resolved.getTargetDestinations());
        assertNotEquals(retired.getName(), replacement.getName(),
                "A replacement must have a distinct broker identity");
    }

    private Player createIsolationPlayer(String name, String id, int chips, boolean folded) {
        return Player.restoreBuilder()
                .name(name)
                .playerId(id)
                .chips(chips)
                .holeCards(new ArrayList<>(List.of(
                        new Card(Rank.ACE, Suit.SPADES),
                        new Card(Rank.KING, Suit.HEARTS))))
                .bestHand(new ArrayList<>())
                .currentBet(0)
                .hasFolded(folded)
                .allIn(false)
                .out(false)
                .disconnected(false)
                .readyForNextHand(false)
                .build();
    }

    private static SimpUser simpUser(String... sessionIds) {
        SimpUser user = mock(SimpUser.class);
        Set<SimpSession> sessions = new HashSet<>();
        for (String id : sessionIds) {
            SimpSession session = mock(SimpSession.class);
            lenient().when(session.getId()).thenReturn(id);
            sessions.add(session);
        }
        lenient().when(user.getSessions()).thenReturn(sessions);
        return user;
    }
}
