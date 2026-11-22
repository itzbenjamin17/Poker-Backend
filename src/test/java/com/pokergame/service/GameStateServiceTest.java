package com.pokergame.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pokergame.dto.response.PlayerFeedbackResponse;
import com.pokergame.dto.response.PrivatePlayerState;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.HandRank;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.Rank;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.RejectedAction;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.enums.Suit;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.model.Card;
import com.pokergame.model.Game;
import com.pokergame.model.HandPayout;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.transaction.PostSaveQueueTestSupport;
import com.pokergame.security.PlayerPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
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

    private Game testGame;
    private Room testRoom;
    private List<Player> testPlayers;
    private static final String GAME_ID = "test-game-id";
    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    /**
     * Initializes the test fixtures before each scenario.
     */
    @BeforeEach
    void setUp() {
        gameStateService = new GameStateService(roomService, messagingTemplate, FIXED_CLOCK);

        testPlayers = new ArrayList<>();
        testPlayers.add(new Player("Player1", UUID.randomUUID().toString(), 100));
        testPlayers.add(new Player("Player2", UUID.randomUUID().toString(), 100));

        testGame = new Game(GAME_ID, testPlayers, 6, 5, 10);

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
        testGame = new Game(GAME_ID, testPlayers, 6, 5, 10);
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

        Game game = new Game(GAME_ID, threePlayers, 6, 5, 10);
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

        ArgumentCaptor<PublicGameStateResponse> captor = ArgumentCaptor.forClass(PublicGameStateResponse.class);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        PublicGameStateResponse response = captor.getValue();
        assertEquals(game.getPlayers().stream().map(Player::getName).toList(),
                response.players().stream().map(PublicGameStateResponse.PlayerState::name).toList());
        assertEquals(game.getCurrentPlayer().getName(), response.currentPlayerName());
        assertEquals(1L, response.stateRevision());
    }

    /**
     * Protects the contract that broadcast game state when player disconnected should expose disconnected status.
     */
    @Test
    void broadcastGameState_WhenPlayerDisconnected_ShouldExposeDisconnectedStatus() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        testPlayers.get(1).setDisconnected(true);
        long disconnectDeadlineEpochMs = FIXED_INSTANT.toEpochMilli() + 120_000;
        testPlayers.get(1).setDisconnectDeadlineEpochMs(disconnectDeadlineEpochMs);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

        gameStateService.broadcastGameState(testGame);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        Object payload = captor.getValue();
        assertInstanceOf(PublicGameStateResponse.class, payload);

        PublicGameStateResponse response = (PublicGameStateResponse) payload;
        PublicGameStateResponse.PlayerState disconnectedPlayer = response.players().stream()
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
     * Protects the contract that the showdown broadcast never consults the room, so it still goes out when no room is registered.
     */
    @Test
    void broadcastShowdownResults_WithNoRoom_ShouldStillBroadcast() {
        Player winner = testPlayers.getFirst();
        Game settled = settledGame(GAME_ID, testPlayers, Map.of(winner.getPlayerId(), 50), Set.of(winner.getPlayerId()));

        ArgumentCaptor<PublicGameStateResponse> captor = ArgumentCaptor.forClass(PublicGameStateResponse.class);
        gameStateService.broadcastShowdownResults(settled);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        PublicGameStateResponse response = captor.getValue();
        assertEquals(List.of(winner.getName()), response.winners());
        assertEquals(50, response.winningsPerPlayer());
        assertTrue(response.handSettled());
        verifyNoInteractions(roomService);
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
        assertTrue(response.players().stream().noneMatch(PublicGameStateResponse.PlayerState::isCurrentPlayer));
    }

    // ==================== broadcastGameStateWithAutoAdvance Tests ====================

    /**
     * Protects the contract that broadcast game state with auto advance rejects a null game.
     */
    @Test
    void broadcastGameStateWithAutoAdvance_WithNullGame_ThrowsNullPointerException() {
        assertThrows(NullPointerException.class,
                () -> gameStateService.broadcastGameStateWithAutoAdvance(null, "All in: dealing the remaining cards..."));

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    /**
     * Protects the contract that broadcast game state with auto advance with valid data should broadcast.
     */
    @Test
    void broadcastGameStateWithAutoAdvance_WithValidData_ShouldBroadcast() {
        ArgumentCaptor<PublicGameStateResponse> captor = ArgumentCaptor.forClass(PublicGameStateResponse.class);

        gameStateService.broadcastGameStateWithAutoAdvance(testGame, "All in: dealing the remaining cards...");

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        PublicGameStateResponse response = captor.getValue();
        assertTrue(response.isAutoAdvancing());
        assertEquals("All in: dealing the remaining cards...", response.autoAdvanceMessage());
        assertNull(response.currentPlayerId());
        assertTrue(response.legalActions().isEmpty());
        assertEquals(1L, response.stateRevision());
    }

    // ==================== broadcastAutoAdvanceNotification Tests ====================

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
        assertTrue(state.players().stream().noneMatch(PublicGameStateResponse.PlayerState::isCurrentPlayer));
    }

    // ==================== Private Notification & Action Error Tests ====================

    /**
     * Protects the contract that private player notifications route to the active membership's user queue.
     */
    @Test
    void sendPrivatePlayerNotification_ShouldSendToPlayerPrivateQueue() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        gameStateService.sendPrivatePlayerNotification(GAME_ID, "Player1", "Test message");

        String expectedUser = "Player1:" + GAME_ID + ":" + testRoom.getMembershipId("Player1");
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(eq(expectedUser), eq("/queue/private"), captor.capture());
        PlayerFeedbackResponse response = assertInstanceOf(PlayerFeedbackResponse.class, captor.getValue());
        assertEquals("Test message", response.message());
        assertEquals(PlayerFeedbackResponse.Type.PLAYER_NOTIFICATION, response.type());
        assertNull(response.code());
        assertNull(response.action());
    }

    /**
     * Protects the contract that principal-targeted private notifications and action errors target the originating session.
     */
    @Test
    void sendPrivatePlayerNotificationAndActionError_WithOriginPrincipal_ShouldTargetOriginSession() {
        PlayerPrincipal origin = new PlayerPrincipal("Player1", GAME_ID, "origin-membership");

        gameStateService.sendPrivatePlayerNotification(origin, "Welcome back");
        gameStateService.sendActionError(origin, "INVALID_ACTION", RejectedAction.BET, "Minimum bet is 10");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, times(2)).convertAndSendToUser(
                eq(origin.getName()), eq("/queue/private"), captor.capture());
        PlayerFeedbackResponse notification = assertInstanceOf(
                PlayerFeedbackResponse.class, captor.getAllValues().get(0));
        assertEquals("Welcome back", notification.message());
        assertEquals(PlayerFeedbackResponse.Type.PLAYER_NOTIFICATION, notification.type());

        PlayerFeedbackResponse actionError = assertInstanceOf(
                PlayerFeedbackResponse.class, captor.getAllValues().get(1));
        assertEquals(PlayerFeedbackResponse.Type.ACTION_ERROR, actionError.type());
        assertEquals("INVALID_ACTION", actionError.code());
        assertEquals(RejectedAction.BET, actionError.action());
        assertEquals("Minimum bet is 10", actionError.message());
    }

    // ==================== advanceRevisionAndBuildGameEnd / sendGameEnd Tests ====================

    /**
     * Protects the contract that advanceRevisionAndBuildGameEnd + sendGameEnd publishes winner info and increments stateRevision.
     */
    @Test
    void advanceRevisionAndBuildGameEnd_AndSendGameEnd_ShouldPublishWinnerInfo() {
        Player winner = testPlayers.getFirst();
        winner.addChips(50);

        PublicGameStateResponse recorded = gameStateService.advanceRevisionAndBuildGameEnd(testGame, winner, false);
        gameStateService.sendGameEnd(GAME_ID, recorded);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());

        PublicGameStateResponse gameEndResponse = assertInstanceOf(PublicGameStateResponse.class, captor.getValue());
        assertNotNull(gameEndResponse.gameEnd());
        assertEquals(winner.getName(), gameEndResponse.gameEnd().winner());
        assertEquals(winner.getChips(), gameEndResponse.gameEnd().winnerChips());
        assertEquals(1L, testGame.getStateRevision());
    }

    /**
     * Protects the contract that game-end messages name the winner.
     */
    @Test
    void buildGameEndResponse_MessageShouldContainWinnerName() {
        Player winner = testPlayers.getFirst();

        PublicGameStateResponse gameEndResponse = gameStateService.buildGameEndResponse(testGame, winner, false);

        assertNotNull(gameEndResponse.gameEnd());
        String message = gameEndResponse.gameEnd().message();
        assertTrue(message.contains(winner.getName()));
        assertTrue(message.contains("wins"));
    }

    /**
     * Protects the contract that advanceRevisionAndBuildGameEnd and buildGameEndResponse reject null game or winner.
     */
    @Test
    void advanceRevisionAndBuildGameEnd_WithNullGameOrWinner_ThrowsNullPointerException() {
        Player winner = testPlayers.getFirst();

        assertThrows(NullPointerException.class, () -> gameStateService.advanceRevisionAndBuildGameEnd(null, winner, false));
        assertThrows(NullPointerException.class, () -> gameStateService.advanceRevisionAndBuildGameEnd(testGame, null, false));
        assertThrows(NullPointerException.class, () -> gameStateService.buildGameEndResponse(null, winner, false));
        assertThrows(NullPointerException.class, () -> gameStateService.buildGameEndResponse(testGame, null, false));
        assertDoesNotThrow(() -> gameStateService.sendGameEnd(GAME_ID, null));
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    /**
     * Protects the contract that a fold-win game end keeps hole cards private.
     */
    @Test
    void advanceRevisionAndBuildGameEnd_FoldWinKeepsHoleCardsPrivate() {
        Player winner = testPlayers.getFirst();
        Player loser = testPlayers.get(1);
        loser.setIsOut();

        PublicGameStateResponse finalState = gameStateService.advanceRevisionAndBuildGameEnd(testGame, winner, false);
        assertNotNull(finalState);
        assertNotNull(finalState.gameEnd());

        PublicGameStateResponse.PlayerState winnerState = finalState.players().stream()
                .filter(p -> p.name().equals(winner.getName()))
                .findFirst()
                .orElseThrow();
        assertNull(winnerState.holeCards());
        assertTrue(winnerState.isWinner());
    }

    @Test
    void advanceRevisionAndBuildGameEnd_ContestedShowdownRevealsWinnerCards() {
        Player winner = testPlayers.getFirst();
        Player other = testPlayers.get(1);
        List<Card> board = List.of(new Card(Rank.ACE, Suit.SPADES),
                new Card(Rank.KING, Suit.SPADES),
                new Card(Rank.QUEEN, Suit.SPADES),
                new Card(Rank.JACK, Suit.SPADES),
                new Card(Rank.TEN, Suit.SPADES));
        winner.setHoleCards(List.of(new Card(Rank.TWO, Suit.CLUBS),
                new Card(Rank.THREE, Suit.CLUBS)));
        other.setHoleCards(List.of(new Card(Rank.FOUR, Suit.CLUBS),
                new Card(Rank.FIVE, Suit.CLUBS)));
        winner.setHandRank(HandRank.ROYAL_FLUSH);
        other.setHandRank(HandRank.ROYAL_FLUSH);
        Game contested = Game.restoreBuilder().gameId(GAME_ID).players(testPlayers)
                .activePlayerIds(testPlayers.stream().map(Player::getPlayerId).toList()).remainingDeck(List.of())
                .communityCards(board).pot(0).dealerPlayerId(testPlayers.get(0).getPlayerId())
                .smallBlindPlayerId(testPlayers.get(0).getPlayerId())
                .bigBlindPlayerId(testPlayers.get(1).getPlayerId())
                .currentPlayerId(testPlayers.get(0).getPlayerId()).currentHighestBet(0).currentPhase(GamePhase.SHOWDOWN).gameOver(false)
                .smallBlind(5).bigBlind(10).handContributions(Map.of())
                .lastHandPayout(new HandPayout(Map.of(winner.getPlayerId(), 100), Map.of(),
                        Set.of(winner.getPlayerId()), 0, true)).scheduledTimes(Map.of())
                .allPlayersHadInitialTurn(true).actedPlayersInRound(Set.of())
                .build();

        PublicGameStateResponse event = gameStateService.advanceRevisionAndBuildGameEnd(contested, winner, false);

        assertNotNull(event.players().stream().filter(player -> player.name().equals(winner.getName()))
                .findFirst().orElseThrow().holeCards());
    }

    @Test
    void advanceRevisionAndBuildGameEnd_ForfeitDuringReadyCountdownAfterContestedShowdown_RevealsShowdownWinnerHoleCards() {
        // Fixed cards and an explicit payout keep the showdown winner unambiguous (a random deal can tie).
        List<Card> winnerCards = List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.ACE, Suit.HEARTS));
        List<Card> loserCards = List.of(new Card(Rank.TWO, Suit.CLUBS), new Card(Rank.SEVEN, Suit.DIAMONDS));
        Player showdownWinner = Player.restoreBuilder().name("Player1").playerId("p1").chips(100)
                .holeCards(winnerCards).bestHand(winnerCards).handRank(HandRank.ONE_PAIR)
                .currentBet(0).hasFolded(false).allIn(false).out(false).disconnected(false)
                .readyForNextHand(false).build();
        Player showdownLoser = Player.restoreBuilder().name("Player2").playerId("p2").chips(100)
                .holeCards(loserCards).bestHand(loserCards).handRank(HandRank.HIGH_CARD)
                .currentBet(0).hasFolded(false).allIn(false).out(false).disconnected(false)
                .readyForNextHand(false).build();
        Game game = Game.restoreBuilder().gameId(GAME_ID).players(List.of(showdownWinner, showdownLoser))
                .activePlayerIds(List.of("p1", "p2")).remainingDeck(List.of())
                .communityCards(List.of(
                        new Card(Rank.NINE, Suit.CLUBS), new Card(Rank.JACK, Suit.DIAMONDS),
                        new Card(Rank.KING, Suit.SPADES), new Card(Rank.FOUR, Suit.HEARTS),
                        new Card(Rank.EIGHT, Suit.CLUBS)))
                .pot(0).dealerPlayerId("p1").smallBlindPlayerId("p1").bigBlindPlayerId("p2")
                .currentPlayerId("p1").currentHighestBet(0).currentPhase(GamePhase.SHOWDOWN).gameOver(false)
                .smallBlind(5).bigBlind(10).handContributions(Map.of()).carryOverPot(0)
                .lastHandPayout(new HandPayout(Map.of("p1", 20), Map.of(), Set.of("p1"), 0, true))
                .readyGateState(ReadyGateState.COUNTING_DOWN)
                .readyCountdownDeadlineEpochMs(FIXED_INSTANT.toEpochMilli() + 10_000L)
                .allPlayersHadInitialTurn(true).actedPlayersInRound(Set.of()).scheduledTimes(Map.of())
                .build();
        assertTrue(game.isReadyCountdownActive());

        Player gameWinner = game.settleGameEnd(showdownWinner, Set.of(showdownLoser.getName()));
        PublicGameStateResponse endResponse = gameStateService.advanceRevisionAndBuildGameEnd(game, gameWinner, true);

        assertTrue(endResponse.gameEnd().isForfeit());
        PublicGameStateResponse.PlayerState winnerState = endResponse.players().stream()
                .filter(p -> p.id().equals(showdownWinner.getPlayerId()))
                .findFirst()
                .orElseThrow();
        PublicGameStateResponse.PlayerState loserState = endResponse.players().stream()
                .filter(p -> p.id().equals(showdownLoser.getPlayerId()))
                .findFirst()
                .orElseThrow();
        assertEquals(winnerCards, winnerState.holeCards());
        assertNull(loserState.holeCards());
    }

    @Test
    void foldWin_NeverRevealsHoleCardsAcrossShowdownLiveOrFinalViews() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        Player p1 = new Player("Player1", "p1", 100);
        Player p2 = new Player("Player2", "p2", 100);
        Game game = new Game(GAME_ID, List.of(p1, p2), 6, 5, 10);
        game.dealHoleCards();
        game.postBlinds();

        Player foldingPlayer = game.getCurrentPlayer();
        Player winner = foldingPlayer.equals(p1) ? p2 : p1;
        game.processPlayerDecision(foldingPlayer, PlayerAction.FOLD, 0);
        assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());
        assertTrue(game.isHandSettled());

        gameStateService.broadcastShowdownResults(game);
        ArgumentCaptor<Object> showdownCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), showdownCaptor.capture());
        PublicGameStateResponse showdownView = assertInstanceOf(PublicGameStateResponse.class, showdownCaptor.getValue());
        assertTrue(showdownView.players().stream().allMatch(p -> p.holeCards() == null),
                "Showdown broadcast must not reveal hole cards on a fold win");

        reset(messagingTemplate);
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        gameStateService.broadcastGameState(game);
        ArgumentCaptor<Object> liveCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), liveCaptor.capture());
        PublicGameStateResponse liveBroadcastView = assertInstanceOf(PublicGameStateResponse.class, liveCaptor.getValue());
        assertTrue(liveBroadcastView.players().stream().allMatch(p -> p.holeCards() == null),
                "Live broadcast must not reveal hole cards on a fold win");

        PublicGameStateResponse liveSnapshotView = gameStateService.getPublicGameStateSnapshot(game);
        assertTrue(liveSnapshotView.players().stream().allMatch(p -> p.holeCards() == null),
                "Live snapshot must not reveal hole cards on a fold win");

        PublicGameStateResponse endResponse = gameStateService.advanceRevisionAndBuildGameEnd(game, winner, false);
        assertTrue(endResponse.players().stream().allMatch(p -> p.holeCards() == null),
                "Final game state must not reveal hole cards on a fold win");
    }

    @Test
    void finalDtoCapturesCarryOverOnceWithWinnerStack1010AndEmptyPot() {
        Player sb = Player.restoreBuilder().name("SB").playerId("sb").chips(0).holeCards(List.of())
                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(0)
                .hasFolded(true).allIn(false).out(true).disconnected(false).readyForNextHand(false).build();
        Player bb = Player.restoreBuilder().name("BB").playerId("bb").chips(1000).holeCards(List.of())
                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(0)
                .hasFolded(false).allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
        HandPayout settled = new HandPayout(Map.of(), Map.of(), Set.of(), 10, false);
        Game finishedGame = Game.restoreBuilder().gameId(GAME_ID).players(List.of(sb, bb)).activePlayerIds(List.of("bb"))
                .remainingDeck(List.of()).communityCards(List.of()).pot(10)
                .dealerPlayerId("sb").smallBlindPlayerId("sb").bigBlindPlayerId("bb")
                .currentPlayerId("bb").currentHighestBet(0)
                .currentPhase(GamePhase.SHOWDOWN).gameOver(true).smallBlind(5).bigBlind(10)
                .handContributions(Map.of()).carryOverPot(10).lastHandPayout(settled)
                .allPlayersHadInitialTurn(true).actedPlayersInRound(Set.of())
                .scheduledTimes(Map.of()).build();
        finishedGame.settleGameEnd(bb, Set.of());

        PublicGameStateResponse result = gameStateService.advanceRevisionAndBuildGameEnd(finishedGame, bb, true);
        gameStateService.sendGameEnd(GAME_ID, result);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());

        PublicGameStateResponse sent = assertInstanceOf(PublicGameStateResponse.class, captor.getValue());
        assertNotNull(sent.gameEnd());
        assertEquals(1010, sent.gameEnd().winnerChips());
        assertEquals(0, sent.pot());
        assertEquals(1010, sent.players().stream().filter(PublicGameStateResponse.PlayerState::isWinner)
                .findFirst().orElseThrow().chips());
        finishedGame.settleGameEnd(bb, Set.of());
        assertEquals(1010, bb.getChips());
    }

    @Test
    void showdownDtoUsesPerPlayerContestedAwardsInsteadOfEqualShareGuess() {
        Player mainWinner = testPlayers.get(0);
        Player sideWinner = testPlayers.get(1);
        mainWinner.setHandRank(HandRank.HIGH_CARD);
        sideWinner.setHandRank(HandRank.ONE_PAIR);
        HandPayout settlement = new HandPayout(
                Map.of(mainWinner.getPlayerId(), 150, sideWinner.getPlayerId(), 200),
                Map.of(), Set.of(mainWinner.getPlayerId(), sideWinner.getPlayerId()), 0, true);
        Game settled = Game.restoreBuilder().gameId(GAME_ID).players(testPlayers)
                .activePlayerIds(testPlayers.stream().map(Player::getPlayerId).toList()).remainingDeck(List.of())
                .communityCards(List.of()).pot(0)
                .dealerPlayerId(testPlayers.get(0).getPlayerId())
                .smallBlindPlayerId(testPlayers.get(0).getPlayerId())
                .bigBlindPlayerId(testPlayers.get(1).getPlayerId())
                .currentPlayerId(testPlayers.get(0).getPlayerId()).currentHighestBet(0).currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false).smallBlind(5).bigBlind(10).handContributions(Map.of())
                .lastHandPayout(settlement).carryOverPot(0)
                .allPlayersHadInitialTurn(true).actedPlayersInRound(Set.of()).scheduledTimes(Map.of())
                .build();
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
        players.get(0).setHoleCards(List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES)));
        players.get(1).setHoleCards(List.of(new Card(Rank.NINE, Suit.HEARTS), new Card(Rank.SEVEN, Suit.CLUBS)));
        players.get(2).setHoleCards(List.of(new Card(Rank.TWO, Suit.HEARTS), new Card(Rank.THREE, Suit.DIAMONDS)));
        players.get(3).setHoleCards(List.of(new Card(Rank.FOUR, Suit.HEARTS), new Card(Rank.FIVE, Suit.DIAMONDS)));
        Game settled = Game.restoreBuilder().gameId(GAME_ID).players(players)
                .activePlayerIds(players.stream().map(Player::getPlayerId).toList()).remainingDeck(List.of())
                .communityCards(List.of(
                        new Card(Rank.TEN, Suit.SPADES), new Card(Rank.JACK, Suit.SPADES),
                        new Card(Rank.QUEEN, Suit.SPADES), new Card(Rank.EIGHT, Suit.DIAMONDS),
                        new Card(Rank.TWO, Suit.CLUBS)))
                .pot(450)
                .dealerPlayerId("main").smallBlindPlayerId("main").bigBlindPlayerId("side1")
                .currentPlayerId("main").currentHighestBet(150).currentPhase(GamePhase.RIVER)
                .gameOver(false).smallBlind(5).bigBlind(10)
                .handContributions(Map.of("main", 50, "side1", 100, "side2", 150, "side3", 150))
                .carryOverPot(0).allPlayersHadInitialTurn(true)
                .actedPlayersInRound(Set.of()).scheduledTimes(Map.of()).build();
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

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
                .filter(PublicGameStateResponse.PlayerState::isWinner)
                .collect(Collectors.toMap(PublicGameStateResponse.PlayerState::id, PublicGameStateResponse.PlayerState::chipsWon)));
        assertTrue(response.players().stream().noneMatch(p -> p.isCurrentPlayer()));
        PublicGameStateResponse.PlayerState initialMain = initial.players().stream().filter(p -> p.id().equals("main")).findFirst().orElseThrow();
        PublicGameStateResponse.PlayerState readyMain = response.players().stream().filter(p -> p.id().equals("main")).findFirst().orElseThrow();
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
                .communityCards(List.of()).pot(0)
                .dealerPlayerId(testPlayers.get(0).getPlayerId())
                .smallBlindPlayerId(testPlayers.get(0).getPlayerId())
                .bigBlindPlayerId(testPlayers.get(1).getPlayerId())
                .currentPlayerId(testPlayers.get(0).getPlayerId()).currentHighestBet(0).currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false).smallBlind(5).bigBlind(10).handContributions(Map.of()).carryOverPot(0)
                .lastHandPayout(new HandPayout(Map.of(connected.getPlayerId(), 50,
                        disconnected.getPlayerId(), 50), Map.of(), Set.of(connected.getPlayerId(), disconnected.getPlayerId()),
                        0, true))
                .readyCountdownDeadlineEpochMs(12345L)
                .allPlayersHadInitialTurn(true).actedPlayersInRound(Set.of()).scheduledTimes(Map.of())
                .build();

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
                .communityCards(List.of()).pot(100)
                .dealerPlayerId(testPlayers.get(0).getPlayerId())
                .smallBlindPlayerId(testPlayers.get(0).getPlayerId())
                .bigBlindPlayerId(testPlayers.get(1).getPlayerId())
                .currentPlayerId(testPlayers.get(0).getPlayerId()).currentHighestBet(0).currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false).smallBlind(5).bigBlind(10).handContributions(Map.of()).carryOverPot(0)
                .lastHandPayout(new HandPayout(Map.of(), Map.of(), Set.of(), 100, false))
                .allPlayersHadInitialTurn(true).actedPlayersInRound(Set.of())
                .scheduledTimes(Map.of()).build();

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
                .pot(0).dealerPlayerId("p1").smallBlindPlayerId("p1").bigBlindPlayerId("p2")
                .currentPlayerId("p1")
                .currentHighestBet(0).currentPhase(GamePhase.SHOWDOWN).gameOver(false)
                .smallBlind(5).bigBlind(10).handContributions(Map.of()).carryOverPot(0)
                .lastHandPayout(new HandPayout(Map.of(winner.getPlayerId(), 150), Map.of(),
                        Set.of(winner.getPlayerId()), 0, false))
                .allPlayersHadInitialTurn(true).actedPlayersInRound(Set.of())
                .scheduledTimes(Map.of()).build();
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
                .dealerPlayerId(testPlayers.get(0).getPlayerId())
                .smallBlindPlayerId(testPlayers.get(0).getPlayerId())
                .bigBlindPlayerId(testPlayers.get(1).getPlayerId())
                .currentPlayerId(testPlayers.get(0).getPlayerId())
                .currentHighestBet(0)
                .currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false)
                .smallBlind(5)
                .bigBlind(10)
                .handContributions(Map.of())
                .carryOverPot(0)
                .lastHandPayout(null)
                .allPlayersHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTimes(Map.of())
                
                .build();

        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(showdownGame);

        assertNull(response.currentPlayerName(), "currentPlayerName must be null during SHOWDOWN phase");
        assertNull(response.currentPlayerId(), "currentPlayerId must be null during SHOWDOWN phase");
        assertTrue(response.legalActions().isEmpty(), "legalActions must be empty during SHOWDOWN phase");
        assertTrue(response.players().stream().noneMatch(PublicGameStateResponse.PlayerState::isCurrentPlayer),
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
                .dealerPlayerId(testPlayers.get(0).getPlayerId())
                .smallBlindPlayerId(testPlayers.get(0).getPlayerId())
                .bigBlindPlayerId(testPlayers.get(1).getPlayerId())
                .currentPlayerId(testPlayers.get(0).getPlayerId())
                .currentHighestBet(0)
                .currentPhase(GamePhase.RIVER)
                .gameOver(false)
                .smallBlind(5)
                .bigBlind(10)
                .handContributions(Map.of())
                .carryOverPot(0)
                .lastHandPayout(null)
                .readyGateState(ReadyGateState.COUNTING_DOWN)
                .readyCountdownDeadlineEpochMs(FIXED_INSTANT.toEpochMilli() + 5000)
                .allPlayersHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTimes(Map.of())
                
                .build();

        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(countdownGame);

        assertNull(response.currentPlayerName(), "currentPlayerName must be null during ready countdown");
        assertNull(response.currentPlayerId(), "currentPlayerId must be null during ready countdown");
        assertTrue(response.legalActions().isEmpty(), "legalActions must be empty during ready countdown");
        assertTrue(response.players().stream().noneMatch(PublicGameStateResponse.PlayerState::isCurrentPlayer),
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
                .dealerPlayerId(testPlayers.get(0).getPlayerId())
                .smallBlindPlayerId(testPlayers.get(0).getPlayerId())
                .bigBlindPlayerId(testPlayers.get(1).getPlayerId())
                .currentPlayerId(testPlayers.get(0).getPlayerId())
                .currentHighestBet(0)
                .currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false)
                .smallBlind(5)
                .bigBlind(10)
                .handContributions(Map.of())
                .carryOverPot(0)
                .lastHandPayout(null)
                .readyGateState(ReadyGateState.COUNTING_DOWN)
                .readyCountdownDeadlineEpochMs(FIXED_INSTANT.toEpochMilli() + 5000)
                .allPlayersHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTimes(Map.of())
                
                .build();

        gameStateService.broadcastGameState(showdownGame);

        ArgumentCaptor<PublicGameStateResponse> captor = ArgumentCaptor.forClass(PublicGameStateResponse.class);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        PublicGameStateResponse response = captor.getValue();

        assertNull(response.currentPlayerName(), "currentPlayerName must be null during showdown ready countdown broadcast");
        assertNull(response.currentPlayerId(), "currentPlayerId must be null during showdown ready countdown broadcast");
        assertTrue(response.legalActions().isEmpty(), "legalActions must be empty during showdown ready countdown broadcast");
        assertTrue(response.players().stream().noneMatch(PublicGameStateResponse.PlayerState::isCurrentPlayer),
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
        winner.setHoleCards(List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.ACE, Suit.HEARTS)));

        loser.setHandRank(HandRank.ONE_PAIR);
        loser.setBestHand(List.of(
                new Card(Rank.JACK, Suit.SPADES), new Card(Rank.JACK, Suit.HEARTS),
                new Card(Rank.TEN, Suit.CLUBS), new Card(Rank.NINE, Suit.DIAMONDS),
                new Card(Rank.EIGHT, Suit.SPADES)));
        loser.setHoleCards(List.of(new Card(Rank.JACK, Suit.SPADES), new Card(Rank.JACK, Suit.HEARTS)));

        HandPayout settlement = new HandPayout(
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
                .dealerPlayerId(testPlayers.get(0).getPlayerId())
                .smallBlindPlayerId(testPlayers.get(0).getPlayerId())
                .bigBlindPlayerId(testPlayers.get(1).getPlayerId())
                .currentPlayerId(testPlayers.get(0).getPlayerId())
                .currentHighestBet(0)
                .currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false)
                .smallBlind(5)
                .bigBlind(10)
                .handContributions(Map.of())
                .carryOverPot(0)
                .lastHandPayout(settlement)
                .readyCountdownDeadlineEpochMs(FIXED_INSTANT.toEpochMilli() + 5000)
                .allPlayersHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTimes(Map.of())
                
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

        PublicGameStateResponse.PlayerState winnerState = response.players().stream()
                .filter(p -> p.id().equals(winner.getPlayerId())).findFirst().orElseThrow();
        PublicGameStateResponse.PlayerState loserState = response.players().stream()
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

        PublicGameStateResponse.PlayerState unreadyWinner = unreadyResponse.players().stream()
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
        winner.setHoleCards(List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.ACE, Suit.HEARTS)));

        loser.setHandRank(HandRank.FLUSH);
        loser.setBestHand(List.of(
                new Card(Rank.KING, Suit.HEARTS), new Card(Rank.QUEEN, Suit.HEARTS),
                new Card(Rank.TEN, Suit.HEARTS), new Card(Rank.EIGHT, Suit.HEARTS),
                new Card(Rank.TWO, Suit.HEARTS)));
        loser.setHoleCards(List.of(new Card(Rank.QUEEN, Suit.HEARTS), new Card(Rank.TWO, Suit.HEARTS)));

        HandPayout settlement = new HandPayout(
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
                .dealerPlayerId(testPlayers.get(0).getPlayerId())
                .smallBlindPlayerId(testPlayers.get(0).getPlayerId())
                .bigBlindPlayerId(testPlayers.get(1).getPlayerId())
                .currentPlayerId(testPlayers.get(0).getPlayerId())
                .currentHighestBet(0)
                .currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false)
                .smallBlind(5)
                .bigBlind(10)
                .handContributions(Map.of())
                .carryOverPot(0)
                .lastHandPayout(settlement)
                .readyCountdownDeadlineEpochMs(FIXED_INSTANT.toEpochMilli() + 6000)
                .allPlayersHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTimes(Map.of())
                
                .build();

        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(countdownGame);

        assertEquals(List.of("Player1"), response.winners());
        assertEquals(300, response.winningsPerPlayer());
        assertTrue(response.handSettled());
        assertNull(response.currentPlayerName());
        assertNull(response.currentPlayerId());
        assertTrue(response.legalActions().isEmpty());

        PublicGameStateResponse.PlayerState p1 = response.players().stream()
                .filter(p -> p.id().equals(winner.getPlayerId())).findFirst().orElseThrow();
        PublicGameStateResponse.PlayerState p2 = response.players().stream()
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
            Map<String, Integer> potWinnings,
            Set<String> winnerIds) {
        return Game.restoreBuilder()
                .gameId(gameId)
                .players(players)
                .activePlayerIds(players.stream().map(Player::getPlayerId).toList())
                .remainingDeck(List.of())
                .communityCards(List.of())
                .pot(0)
                .dealerPlayerId(players.get(0).getPlayerId())
                .smallBlindPlayerId(players.get(0).getPlayerId())
                .bigBlindPlayerId(players.get(Math.min(1, players.size() - 1)).getPlayerId())
                .currentPlayerId(players.get(0).getPlayerId())
                .currentHighestBet(0)
                .currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false)
                .smallBlind(5)
                .bigBlind(10)
                .handContributions(Map.of())
                .carryOverPot(0)
                .lastHandPayout(new HandPayout(potWinnings, Map.of(), winnerIds, 0, true))
                .allPlayersHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTimes(Map.of())
                
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
    void advanceRevisionAndBuildGameEnd_ForfeitPath_ShouldNotRevealHoleCards() {
        Player winner = testPlayers.getFirst();

        PublicGameStateResponse finalState = gameStateService.advanceRevisionAndBuildGameEnd(testGame, winner, true);
        assertNotNull(finalState.gameEnd());
        assertTrue(finalState.gameEnd().isForfeit());

        assertTrue(finalState.players().stream().allMatch(p -> p.holeCards() == null));
    }

    // ==================== Edge Case Tests ====================

    /**
     * Protects the contract that broadcast showdown results with folded player marks them as FOLDED.
     */
    @Test
    void broadcastShowdownResults_WithFoldedPlayer_ShouldMarkAsFolded() {
        testPlayers.get(1).doAction(PlayerAction.FOLD, 0);
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
        testPlayers.getFirst().doAction(PlayerAction.ALL_IN, 0);
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

        ArgumentCaptor<PublicGameStateResponse> captor = ArgumentCaptor.forClass(PublicGameStateResponse.class);

        gameStateService.broadcastGameState(testGame);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        assertEquals(GamePhase.PRE_FLOP, captor.getValue().phase());
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
        long smallBlindCount = response.players().stream().filter(PublicGameStateResponse.PlayerState::isSmallBlind).count();
        long bigBlindCount = response.players().stream().filter(PublicGameStateResponse.PlayerState::isBigBlind).count();

        assertEquals(1, smallBlindCount);
        assertEquals(1, bigBlindCount);
    }

    /**
     * Protects the contract that heads-up the dealer button is on the small blind, and only there.
     */
    @Test
    void broadcastGameState_HeadsUp_ShouldMarkTheSmallBlindAsTheOnlyDealer() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        testGame.dealHoleCards();
        testGame.postBlinds();

        gameStateService.broadcastGameState(testGame);

        PublicGameStateResponse response = capturePublicBroadcast();
        List<PublicGameStateResponse.PlayerState> dealers = response.players().stream()
                .filter(PublicGameStateResponse.PlayerState::isDealer).toList();
        assertEquals(1, dealers.size());
        assertTrue(dealers.getFirst().isSmallBlind());
    }

    /**
     * Protects the contract that at three or more seats the dealer button sits one seat before the small blind.
     */
    @Test
    void broadcastGameState_ThreeHanded_ShouldMarkTheSeatBeforeTheSmallBlindAsDealer() {
        testPlayers.add(new Player("Player3", UUID.randomUUID().toString(), 100));
        testGame = new Game(GAME_ID, testPlayers, 6, 5, 10);
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        testGame.dealHoleCards();
        testGame.postBlinds();

        gameStateService.broadcastGameState(testGame);

        List<PublicGameStateResponse.PlayerState> seats = capturePublicBroadcast().players();
        int smallBlindSeat = IntStream.range(0, seats.size())
                .filter(i -> seats.get(i).isSmallBlind()).findFirst().orElseThrow();
        List<String> dealers = seats.stream().filter(PublicGameStateResponse.PlayerState::isDealer)
                .map(PublicGameStateResponse.PlayerState::name).toList();
        assertEquals(List.of(seats.get((smallBlindSeat + seats.size() - 1) % seats.size()).name()), dealers);
    }

    /**
     * Protects the contract that the broadcast after dealing the flop carries the three community cards and keeps blind flags.
     */
    @Test
    void broadcastGameState_AfterDealingFlop_ShouldCarryCommunityCardsAndKeepBlindFlags() {
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
        long smallBlindCount = response.players().stream().filter(PublicGameStateResponse.PlayerState::isSmallBlind).count();
        long bigBlindCount = response.players().stream().filter(PublicGameStateResponse.PlayerState::isBigBlind).count();

        assertEquals(3, response.communityCards().size());
        assertEquals(1, smallBlindCount);
        assertEquals(1, bigBlindCount);
    }

    // ==================== Multiple Broadcast Tests ====================

    /**
     * Protects the contract that each broadcast increments stateRevision monotonically and sends one private state per player.
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
        // Every broadcast also reaches each of the two players privately.
        verify(messagingTemplate, times(4)).convertAndSendToUser(anyString(), eq("/queue/private"), any(PrivatePlayerState.class));
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
        PublicGameStateResponse.PlayerState p1State = response.players().stream()
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
        PublicGameStateResponse.PlayerState p1State = response.players().stream()
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
        PublicGameStateResponse.PlayerState p1State = response.players().stream()
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
        PublicGameStateResponse.PlayerState p1State = response.players().stream()
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
        PublicGameStateResponse.PlayerState p1State = response.players().stream()
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
        PublicGameStateResponse.PlayerState p1State = response.players().stream()
                .filter(p -> p.id().equals(player.getPlayerId()))
                .findFirst().orElseThrow();

        assertEquals("ACTIVE", p1State.status());
    }

    // ==================== Private Player State Tests ====================

    @Test
    void getPrivatePlayerStateSnapshot_ShouldIncludeHandNumberAndDefensivelyCopyCards() {
        testGame.resetForNewHand();
        Player player = testPlayers.get(0);
        player.setHoleCards(List.of(new Card(Rank.ACE, Suit.HEARTS), new Card(Rank.KING, Suit.SPADES)));

        PrivatePlayerState snapshot = gameStateService.getPrivatePlayerStateSnapshot(testGame, player.getName());

        assertNotNull(snapshot);
        assertEquals(player.getPlayerId(), snapshot.playerId());
        assertEquals(testGame.getHandNumber(), snapshot.handNumber());
        assertEquals(2, snapshot.holeCards().size());
        assertEquals(player.getHoleCards(), snapshot.holeCards());

        // Replacing the player's cards does not mutate the captured snapshot
        player.setHoleCards(List.of(
                new Card(Rank.ACE, Suit.HEARTS),
                new Card(Rank.KING, Suit.SPADES),
                new Card(Rank.QUEEN, Suit.CLUBS)));
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
        Game game = new Game(GAME_ID, players, 6, 5, 10);

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
    @DisplayName("advanceRevisionAndBuildGameEnd preserves game's configured [2, 10] capacity across restored and live games")
    void advanceRevisionAndBuildGameEnd_PreservesMaxPlayersFromGame() {
        Player winner = testPlayers.getFirst();

        for (int validCapacity : new int[]{Room.MIN_PLAYERS, 6, Room.MAX_PLAYERS}) {
            Game restored = Game.restoreBuilder()
                    .gameId(GAME_ID)
                    .maxPlayers(validCapacity)
                    .players(testPlayers)
                    .activePlayerIds(testPlayers.stream().map(Player::getPlayerId).toList())
                    .remainingDeck(List.of())
                    .communityCards(List.of())
                    .pot(0)
                    .dealerPlayerId(testPlayers.get(0).getPlayerId())
                    .smallBlindPlayerId(testPlayers.get(0).getPlayerId())
                    .bigBlindPlayerId(testPlayers.get(1).getPlayerId())
                    .currentPlayerId(testPlayers.get(0).getPlayerId())
                    .currentHighestBet(0)
                    .currentPhase(GamePhase.SHOWDOWN)
                    .gameOver(true)
                    .smallBlind(5)
                    .bigBlind(10)
                    .handContributions(Map.of())
                    .carryOverPot(0)
                    .allPlayersHadInitialTurn(true)
                    .actedPlayersInRound(Set.of())
                    .scheduledTimes(Map.of())
                    .build();

            PublicGameStateResponse response = gameStateService.buildGameEndResponse(restored, winner, false);
            assertEquals(validCapacity, response.maxPlayers());
        }

        Game validCapacityGame = new Game("four-player-game", List.of(
                new Player("P1", "id-1", 100),
                new Player("P2", "id-2", 100),
                new Player("P3", "id-3", 100),
                new Player("P4", "id-4", 100)), 8, 5, 10);
        PublicGameStateResponse response = gameStateService.advanceRevisionAndBuildGameEnd(validCapacityGame, validCapacityGame.getPlayers().getFirst(), false);
        assertEquals(8, response.maxPlayers());
        assertEquals(FIXED_INSTANT.toEpochMilli(), response.serverTimeEpochMs());
    }

    @Test
    @DisplayName("buildGameEndResponse does not increment stateRevision while advanceRevisionAndBuildGameEnd increments it")
    void buildGameEndResponse_DoesNotIncrementStateRevision_WhileRecordGameEndDoes() {
        Player winner = testPlayers.getFirst();
        Game settled = settledGame(GAME_ID, testPlayers, Map.of(winner.getPlayerId(), 100), Set.of(winner.getPlayerId()));

        long revisionBefore = settled.getStateRevision();
        PublicGameStateResponse projected = gameStateService.buildGameEndResponse(settled, winner, false);
        assertNotNull(projected);
        assertEquals(revisionBefore, settled.getStateRevision(), "buildGameEndResponse must not increment stateRevision");
        assertEquals(revisionBefore, projected.stateRevision());

        PublicGameStateResponse recorded = gameStateService.advanceRevisionAndBuildGameEnd(settled, winner, false);
        assertNotNull(recorded);
        assertEquals(revisionBefore + 1, settled.getStateRevision(), "advanceRevisionAndBuildGameEnd must increment stateRevision");
        assertEquals(revisionBefore + 1, recorded.stateRevision());
    }

    @Test
    @DisplayName("advanceRevisionAndBuildAbandonment and buildAbandonmentResponse build INACTIVITY closure and increment revision only on record")
    void advanceRevisionAndBuildAbandonment_AndBuildAbandonmentResponse_IncrementRevisionOnlyOnRecord() {
        long revisionBefore = testGame.getStateRevision();
        PublicGameStateResponse preview = gameStateService.buildAbandonmentResponse(testGame);
        assertEquals(revisionBefore, testGame.getStateRevision());
        assertNotNull(preview.gameEnd());
        assertTrue(preview.gameEnd().abandoned());
        assertEquals("INACTIVITY", preview.gameEnd().reason());
        assertNull(preview.gameEnd().winner());

        PublicGameStateResponse recorded = gameStateService.advanceRevisionAndBuildAbandonment(testGame);
        assertEquals(revisionBefore + 1, testGame.getStateRevision());
        assertEquals(revisionBefore + 1, recorded.stateRevision());
        assertNotNull(recorded.gameEnd());
        assertTrue(recorded.gameEnd().abandoned());
        assertEquals("INACTIVITY", recorded.gameEnd().reason());

        gameStateService.sendGameEnd(GAME_ID, recorded);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), eq(recorded));
    }

    @ParameterizedTest(name = "Alice ready={0}, Bob ready={1}")
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    @DisplayName("Showdown snapshot shows the settled hand whichever players have marked ready")
    void showdownSnapshot_isUnaffectedByReadyFlags(boolean aliceReady, boolean bobReady) {
        SettledShowdown showdown = settledShowdown();
        showdown.alice().setReadyForNextHand(aliceReady);
        showdown.bob().setReadyForNextHand(bobReady);

        PublicGameStateResponse snapshot = gameStateService.getPublicGameStateSnapshot(showdown.game());

        assertNull(snapshot.currentPlayerName(), "currentPlayerName must be null during showdown countdown");
        assertNull(snapshot.currentPlayerId(), "currentPlayerId must be null during showdown countdown");
        assertTrue(snapshot.legalActions().isEmpty(), "legalActions must be empty");

        assertTrue(snapshot.handSettled(), "handSettled must remain true");
        assertEquals(List.of("Alice"), snapshot.winners(), "winners must remain [Alice]");
        assertEquals(450, snapshot.winningsPerPlayer(), "winningsPerPlayer must remain 450");

        PublicGameStateResponse.PlayerState pubP1 = publicPlayer(snapshot, "p-1");
        PublicGameStateResponse.PlayerState pubP2 = publicPlayer(snapshot, "p-2");
        PublicGameStateResponse.PlayerState pubP3 = publicPlayer(snapshot, "p-3");

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

    @Test
    @DisplayName("Showdown snapshot returns immutable winners and best-hand lists")
    void showdownSnapshot_returnsImmutableWinnersAndBestHands() {
        SettledShowdown showdown = settledShowdown();

        PublicGameStateResponse snapshot = gameStateService.getPublicGameStateSnapshot(showdown.game());

        assertEquals(List.of("Alice"), snapshot.winners());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.winners().clear());
        for (String contenderId : List.of("p-1", "p-2")) {
            PublicGameStateResponse.PlayerState contender = publicPlayer(snapshot, contenderId);
            assertNotNull(contender.bestHand(), contender.name() + " reached showdown, so has a best hand list");
            assertThrows(UnsupportedOperationException.class, () -> contender.bestHand().clear());
        }
    }

    private record SettledShowdown(Game game, Player alice, Player bob) {
    }

    /**
     * Alice (full house) won 450 from Bob (flush) with Charlie folded; the hand is settled and the ready
     * countdown is running.
     */
    private SettledShowdown settledShowdown() {
        String roomId = "room-alpha";
        Room room = new Room(roomId, "Room A", "Alice", 6, 5, 10, 100, null);
        room.addPlayer("Alice");
        room.addPlayer("Bob");
        room.addPlayer("Charlie");
        lenient().when(roomService.getRoom(roomId)).thenReturn(room);

        Player alice = createIsolationPlayer("Alice", "p-1", 500, false);
        Player bob = createIsolationPlayer("Bob", "p-2", 300, false);
        Player charlieFolded = createIsolationPlayer("Charlie", "p-3", 200, true);
        alice.setHandRank(HandRank.FULL_HOUSE);
        bob.setHandRank(HandRank.FLUSH);
        charlieFolded.setHandRank(HandRank.NO_HAND);

        Game game = Game.restoreBuilder()
                .gameId(roomId)
                .players(List.of(alice, bob, charlieFolded))
                .activePlayerIds(List.of("p-1", "p-2"))
                .remainingDeck(List.of())
                .communityCards(List.of(
                        new Card(Rank.TEN, Suit.DIAMONDS),
                        new Card(Rank.TEN, Suit.CLUBS),
                        new Card(Rank.TEN, Suit.SPADES)))
                .pot(0)
                .dealerPlayerId("p-1")
                .smallBlindPlayerId("p-1")
                .bigBlindPlayerId("p-2")
                .currentPlayerId("p-2")
                .currentHighestBet(0)
                .currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false)
                .smallBlind(5)
                .bigBlind(10)
                .handContributions(Map.of())
                .carryOverPot(0)
                .lastHandPayout(new HandPayout(Map.of("p-1", 450), Map.of(), Set.of("p-1"), 0, true))
                .readyCountdownDeadlineEpochMs(FIXED_INSTANT.toEpochMilli() + 10000)
                .allPlayersHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTimes(Map.of())
                .build();
        return new SettledShowdown(game, alice, bob);
    }

    private static PublicGameStateResponse.PlayerState publicPlayer(PublicGameStateResponse snapshot, String id) {
        return snapshot.players().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
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
                .dealerPlayerId("p-a1")
                .smallBlindPlayerId("p-a1")
                .bigBlindPlayerId("p-a2")
                .currentPlayerId("p-a1")
                .currentHighestBet(0)
                .currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false)
                .smallBlind(5)
                .bigBlind(10)
                .handContributions(Map.of())
                .carryOverPot(0)
                .lastHandPayout(new HandPayout(Map.of("p-a1", 100), Map.of(), Set.of("p-a1"), 0, true))
                .allPlayersHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTimes(Map.of())
                
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
                .dealerPlayerId("p-b1")
                .smallBlindPlayerId("p-b1")
                .bigBlindPlayerId("p-b2")
                .currentPlayerId("p-b1")
                .currentHighestBet(20)
                .currentPhase(GamePhase.FLOP)
                .gameOver(false)
                .smallBlind(10)
                .bigBlind(20)
                .handContributions(Map.of())
                .carryOverPot(0)
                .lastHandPayout(null)
                .allPlayersHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTimes(Map.of())
                
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
                new Player("Host", "host-id", 100)), 6, 5, 10);
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
                new Player("Host", "host-id", 100)), 6, 5, 10);
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

    // ==================== PostSaveActions deferral Tests ====================

    /** The six live send methods; each queues exactly one kind of message through {@code PostSaveActions}. */
    private enum DeferredSend {
        BROADCAST_GAME_STATE {
            @Override
            void queue(GameStateServiceTest test) {
                test.testGame.dealHoleCards();
                test.gameStateService.broadcastGameState(test.testGame);
            }

            @Override
            void verifyDelivered(SimpMessagingTemplate template) {
                verify(template).convertAndSend(eq("/game/" + GAME_ID), any(PublicGameStateResponse.class));
                verify(template, times(2)).convertAndSendToUser(
                        anyString(), eq("/queue/private"), any(PrivatePlayerState.class));
            }
        },
        BROADCAST_SHOWDOWN_RESULTS {
            @Override
            void queue(GameStateServiceTest test) {
                Player winner = test.testPlayers.getFirst();
                test.gameStateService.broadcastShowdownResults(test.settledGame(
                        GAME_ID, test.testPlayers, Map.of(winner.getPlayerId(), 50), Set.of(winner.getPlayerId())));
            }

            @Override
            void verifyDelivered(SimpMessagingTemplate template) {
                verify(template).convertAndSend(eq("/game/" + GAME_ID), any(PublicGameStateResponse.class));
            }
        },
        BROADCAST_GAME_STATE_WITH_AUTO_ADVANCE {
            @Override
            void queue(GameStateServiceTest test) {
                test.gameStateService.broadcastGameStateWithAutoAdvance(test.testGame, "Banner");
            }

            @Override
            void verifyDelivered(SimpMessagingTemplate template) {
                verify(template).convertAndSend(eq("/game/" + GAME_ID), any(PublicGameStateResponse.class));
            }
        },
        SEND_GAME_END {
            @Override
            void queue(GameStateServiceTest test) {
                test.gameStateService.sendGameEnd(GAME_ID, test.gameStateService.buildGameEndResponse(
                        test.testGame, test.testPlayers.getFirst(), false));
            }

            @Override
            void verifyDelivered(SimpMessagingTemplate template) {
                verify(template).convertAndSend(eq("/game/" + GAME_ID), any(PublicGameStateResponse.class));
            }
        },
        SEND_PRIVATE_PLAYER_NOTIFICATION {
            @Override
            void queue(GameStateServiceTest test) {
                test.gameStateService.sendPrivatePlayerNotification(GAME_ID, "Player1", "Hello");
            }

            @Override
            void verifyDelivered(SimpMessagingTemplate template) {
                verify(template).convertAndSendToUser(
                        anyString(), eq("/queue/private"), any(PlayerFeedbackResponse.class));
            }
        },
        SEND_ACTION_ERROR {
            @Override
            void queue(GameStateServiceTest test) {
                test.gameStateService.sendActionError(
                        new PlayerPrincipal("Player1", GAME_ID, "membership"), "CODE", RejectedAction.BET, "Nope");
            }

            @Override
            void verifyDelivered(SimpMessagingTemplate template) {
                verify(template).convertAndSendToUser(
                        anyString(), eq("/queue/private"), any(PlayerFeedbackResponse.class));
            }
        };

        abstract void queue(GameStateServiceTest test);

        abstract void verifyDelivered(SimpMessagingTemplate template);
    }

    /**
     * Protects the contract that every send method waits for the post-save queue to run before
     * anything reaches the broker, so a failed save never leaks a message.
     */
    @ParameterizedTest
    @EnumSource(DeferredSend.class)
    void sendMethods_AreDeferredUntilThePostSaveQueueRuns(DeferredSend send) {
        lenient().when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        try (PostSaveQueueTestSupport queue = PostSaveQueueTestSupport.open(GAME_ID)) {
            send.queue(this);

            verifyNoInteractions(messagingTemplate);

            queue.executeAll();

            send.verifyDelivered(messagingTemplate);
        }
    }

    /**
     * Protects the contract that a discarded post-save queue (a failed mutation) sends nothing.
     */
    @ParameterizedTest
    @EnumSource(DeferredSend.class)
    void sendMethods_AreDiscardedWhenThePostSaveQueueIsCleared(DeferredSend send) {
        lenient().when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        try (PostSaveQueueTestSupport queue = PostSaveQueueTestSupport.open(GAME_ID)) {
            send.queue(this);
        }

        verifyNoInteractions(messagingTemplate);
    }

    /**
     * Protects the contract that a deferred game-state broadcast delivers the state captured when it
     * was queued, not the game as it looks after later mutations.
     */
    @Test
    void broadcastGameState_QueuedPayloadsAreFrozenBeforeLaterGameMutations() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        testGame.dealHoleCards();
        Player player1 = testPlayers.getFirst();
        List<Card> cardsWhenQueued = List.copyOf(player1.getHoleCards());
        String player1User = "Player1:" + GAME_ID + ":" + testRoom.getMembershipId("Player1");

        try (PostSaveQueueTestSupport queue = PostSaveQueueTestSupport.open(GAME_ID)) {
            gameStateService.broadcastGameState(testGame);
            player1.addChips(50);
            player1.setHoleCards(List.of());
            testGame.incrementStateRevision();

            queue.executeAll();
        }

        ArgumentCaptor<PublicGameStateResponse> publicCaptor = ArgumentCaptor.forClass(PublicGameStateResponse.class);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), publicCaptor.capture());
        assertEquals(1L, publicCaptor.getValue().stateRevision());
        assertEquals(100, publicCaptor.getValue().players().stream()
                .filter(p -> p.name().equals("Player1")).findFirst().orElseThrow().chips());
        ArgumentCaptor<PrivatePlayerState> privateCaptor = ArgumentCaptor.forClass(PrivatePlayerState.class);
        verify(messagingTemplate).convertAndSendToUser(eq(player1User), eq("/queue/private"), privateCaptor.capture());
        assertEquals(cardsWhenQueued, privateCaptor.getValue().holeCards());
    }

    /**
     * Protects the contract that a deferred showdown broadcast delivers the settlement captured when
     * it was queued.
     */
    @Test
    void broadcastShowdownResults_QueuedPayloadIsFrozenBeforeLaterGameMutations() {
        Player winner = testPlayers.getFirst();
        Game settled = settledGame(GAME_ID, testPlayers, Map.of(winner.getPlayerId(), 50), Set.of(winner.getPlayerId()));
        long revisionWhenQueued;

        try (PostSaveQueueTestSupport queue = PostSaveQueueTestSupport.open(GAME_ID)) {
            gameStateService.broadcastShowdownResults(settled);
            revisionWhenQueued = settled.getStateRevision();
            winner.addChips(500);
            settled.incrementStateRevision();

            queue.executeAll();
        }

        ArgumentCaptor<PublicGameStateResponse> captor = ArgumentCaptor.forClass(PublicGameStateResponse.class);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        assertEquals(revisionWhenQueued, captor.getValue().stateRevision());
        assertEquals(100, captor.getValue().players().getFirst().chips());
        assertEquals(List.of("Player1"), captor.getValue().winners());
    }

    /**
     * Protects the contract that a deferred auto-advance broadcast delivers the state captured when
     * it was queued.
     */
    @Test
    void broadcastGameStateWithAutoAdvance_QueuedPayloadIsFrozenBeforeLaterGameMutations() {
        long revisionWhenQueued;

        try (PostSaveQueueTestSupport queue = PostSaveQueueTestSupport.open(GAME_ID)) {
            gameStateService.broadcastGameStateWithAutoAdvance(testGame, "Banner");
            revisionWhenQueued = testGame.getStateRevision();
            testPlayers.getFirst().addChips(500);
            testGame.incrementStateRevision();

            queue.executeAll();
        }

        ArgumentCaptor<PublicGameStateResponse> captor = ArgumentCaptor.forClass(PublicGameStateResponse.class);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        assertEquals(revisionWhenQueued, captor.getValue().stateRevision());
        assertEquals("Banner", captor.getValue().autoAdvanceMessage());
        assertEquals(100, captor.getValue().players().getFirst().chips());
    }

    /**
     * Protects the contract that a deferred game-end send delivers the snapshot that was built
     * before queueing, even if the game changes afterwards.
     */
    @Test
    void sendGameEnd_QueuedResponseIsFrozenBeforeLaterGameMutations() {
        Player winner = testPlayers.getFirst();
        PublicGameStateResponse built = gameStateService.advanceRevisionAndBuildGameEnd(testGame, winner, false);

        try (PostSaveQueueTestSupport queue = PostSaveQueueTestSupport.open(GAME_ID)) {
            gameStateService.sendGameEnd(GAME_ID, built);
            winner.addChips(500);
            testGame.incrementStateRevision();

            queue.executeAll();
        }

        ArgumentCaptor<PublicGameStateResponse> captor = ArgumentCaptor.forClass(PublicGameStateResponse.class);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        assertSame(built, captor.getValue());
        assertEquals(100, captor.getValue().gameEnd().winnerChips());
        assertEquals(1L, captor.getValue().stateRevision());
    }

    /**
     * Protects the contract that a deferred private notification stays addressed to the membership
     * that was active when it was queued, never to a replacement that joined under the same name.
     */
    @Test
    void sendPrivatePlayerNotification_QueuedRecipientIsFrozenBeforeMembershipChanges() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        String originalMembership = testRoom.getMembershipId("Player2");

        try (PostSaveQueueTestSupport queue = PostSaveQueueTestSupport.open(GAME_ID)) {
            gameStateService.sendPrivatePlayerNotification(GAME_ID, "Player2", "Welcome");
            testRoom.removePlayer("Player2");
            String replacementMembership = testRoom.addPlayer("Player2");
            assertNotEquals(originalMembership, replacementMembership);

            queue.executeAll();
        }

        verify(messagingTemplate).convertAndSendToUser(
                eq("Player2:" + GAME_ID + ":" + originalMembership), eq("/queue/private"), any(PlayerFeedbackResponse.class));
        verify(messagingTemplate, never()).convertAndSendToUser(
                eq("Player2:" + GAME_ID + ":" + testRoom.getMembershipId("Player2")), anyString(), any());
    }

    /**
     * Protects the contract that a deferred action error is delivered to the originating session
     * with the code, rejected action and message it was queued with.
     */
    @Test
    void sendActionError_QueuedFeedbackIsDeliveredToTheOriginSessionAfterThePostSaveQueueRuns() {
        PlayerPrincipal origin = new PlayerPrincipal("Player1", GAME_ID, "origin-membership");

        try (PostSaveQueueTestSupport queue = PostSaveQueueTestSupport.open(GAME_ID)) {
            gameStateService.sendActionError(origin, "MIN_BET", RejectedAction.BET, "Minimum bet is 10");
            verifyNoInteractions(messagingTemplate);

            queue.executeAll();
        }

        ArgumentCaptor<PlayerFeedbackResponse> captor = ArgumentCaptor.forClass(PlayerFeedbackResponse.class);
        verify(messagingTemplate).convertAndSendToUser(eq(origin.getName()), eq("/queue/private"), captor.capture());
        assertEquals(PlayerFeedbackResponse.Type.ACTION_ERROR, captor.getValue().type());
        assertEquals("MIN_BET", captor.getValue().code());
        assertEquals(RejectedAction.BET, captor.getValue().action());
        assertEquals("Minimum bet is 10", captor.getValue().message());
    }

    // ==================== Full public view characterization Tests ====================

    private static final Card VIEW_ACE = new Card(Rank.ACE, Suit.SPADES);
    private static final Card VIEW_KING = new Card(Rank.KING, Suit.HEARTS);
    private static final Card VIEW_TWO = new Card(Rank.TWO, Suit.CLUBS);
    private static final Card VIEW_THREE = new Card(Rank.THREE, Suit.CLUBS);
    private static final List<Card> VIEW_BOARD = List.of(
            new Card(Rank.ACE, Suit.HEARTS),
            new Card(Rank.NINE, Suit.DIAMONDS),
            new Card(Rank.SEVEN, Suit.CLUBS));
    private static final long VIEW_NOW_MS = FIXED_INSTANT.toEpochMilli();

    /**
     * Protects the contract that a live betting view names the actor, lists the legal actions and hides hole cards.
     */
    @Test
    void getPublicGameStateSnapshot_LiveBettingHand_NamesActorLegalActionsAndHidesHoleCards() {
        Game game = viewGame(GamePhase.FLOP, null, false, ReadyGateState.CLOSED,
                Map.of(ScheduledGameTask.TURN_TIMEOUT, 5_000L), viewPlayers(false), false);
        testRoom.setInactivityDeadlineEpochMs(9_000L);
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        PublicGameStateResponse actual = gameStateService.getPublicGameStateSnapshot(game);

        Player current = game.getCurrentPlayer();
        assertEquals(new PublicGameStateResponse(
                6, game.getPot(), game.getPotBreakdown(), game.getUncalledAmount(), GamePhase.FLOP,
                game.getCurrentHighestBet(), VIEW_BOARD,
                List.of(viewState("a", "Alice", 90, 10, "ACTIVE", false, current.getName().equals("Alice"), false,
                                true, false, null, null, null, null, null),
                        viewState("b", "Bob", 80, 20, "ACTIVE", false, current.getName().equals("Bob"), false,
                                false, true, null, null, null, null, null)),
                current.getName(), current.getPlayerId(), null, null, false, null, false, null, false, null,
                false, null, game.getLegalActionsFor(current), 10, game.getMinimumRaiseAmount(current),
                3L, ReadyGateState.CLOSED, VIEW_NOW_MS, game.getStateRevision(), 5_000L, 9_000L, null), actual);
    }

    /**
     * Protects the contract that a live view during auto-advance has no actor and carries the banner.
     */
    @Test
    void getPublicGameStateSnapshot_DuringAutoAdvance_HasNoActorAndCarriesBanner() {
        Game game = viewGame(GamePhase.FLOP, null, false, ReadyGateState.CLOSED,
                Map.of(ScheduledGameTask.AUTO_ADVANCE, 7_000L), viewPlayers(true), false);
        testRoom.setInactivityDeadlineEpochMs(9_000L);
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        PublicGameStateResponse actual = gameStateService.getPublicGameStateSnapshot(game);

        assertEquals(new PublicGameStateResponse(
                6, game.getPot(), game.getPotBreakdown(), game.getUncalledAmount(), GamePhase.FLOP,
                game.getCurrentHighestBet(), VIEW_BOARD,
                List.of(viewState("a", "Alice", 90, 10, "ALL_IN", true, false, false, true, false,
                                null, null, null, null, null),
                        viewState("b", "Bob", 80, 20, "ALL_IN", true, false, false, false, true,
                                null, null, null, null, null)),
                null, null, null, null, true, "All in: dealing the remaining cards...", false, null, false, null, false, null,
                List.of(), null, null, 3L, ReadyGateState.CLOSED, VIEW_NOW_MS, game.getStateRevision(), null,
                9_000L, null), actual);
    }

    /**
     * Protects the contract that a settled hand at the ready gate shows results and reveals contested cards.
     */
    @Test
    void getPublicGameStateSnapshot_SettledHandAtReadyGate_ShowsResultsAndRevealsContestedCards() {
        HandPayout settlement = new HandPayout(
                Map.of("a", 30), Map.of(), Set.of("a"), 4, true);
        Game game = viewGame(GamePhase.SHOWDOWN, settlement, true, ReadyGateState.COUNTING_DOWN,
                Map.of(), viewPlayers(false), false);
        testRoom.setInactivityDeadlineEpochMs(9_000L);
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        PublicGameStateResponse actual = gameStateService.getPublicGameStateSnapshot(game);

        assertEquals(new PublicGameStateResponse(
                6, game.getPot(), game.getPotBreakdown(), game.getUncalledAmount(), GamePhase.SHOWDOWN,
                game.getCurrentHighestBet(), VIEW_BOARD,
                List.of(viewState("a", "Alice", 90, 10, "ACTIVE", false, false, false, true, false,
                                HandRank.ONE_PAIR, List.of(VIEW_ACE, VIEW_KING), true, 30, List.of(VIEW_ACE, VIEW_KING)),
                        viewState("b", "Bob", 80, 20, "ACTIVE", false, false, false, false, true,
                                HandRank.HIGH_CARD, List.of(VIEW_TWO, VIEW_THREE), false, 0, List.of(VIEW_TWO, VIEW_THREE))),
                null, null, List.of("Alice"), 30, false, null, true, 12_345L, false, null, true, 4,
                List.of(), null, null, 3L, ReadyGateState.COUNTING_DOWN, VIEW_NOW_MS, game.getStateRevision(),
                null, 9_000L, null), actual);
    }

    /**
     * Protects the contract that a settled hand won without a contest does not reveal hole cards.
     */
    @Test
    void getPublicGameStateSnapshot_UncontestedSettledHand_DoesNotRevealHoleCards() {
        List<Player> players = viewPlayers(false);
        players.get(0).setHandRank(HandRank.NO_HAND);
        HandPayout settlement = new HandPayout(
                Map.of("a", 30), Map.of(), Set.of("a"), 0, false);
        Game game = viewGame(GamePhase.SHOWDOWN, settlement, false, ReadyGateState.CLOSED,
                Map.of(), players, false);
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        PublicGameStateResponse actual = gameStateService.getPublicGameStateSnapshot(game);

        assertEquals(List.of(
                        viewState("a", "Alice", 90, 10, "ACTIVE", false, false, false, true, false,
                                HandRank.NO_HAND, List.of(VIEW_ACE, VIEW_KING), true, 30, null),
                        viewState("b", "Bob", 80, 20, "ACTIVE", false, false, false, false, true,
                                HandRank.HIGH_CARD, List.of(VIEW_TWO, VIEW_THREE), false, 0, null)),
                actual.players());
        assertEquals(List.of("Alice"), actual.winners());
        assertEquals(30, actual.winningsPerPlayer());
        assertTrue(actual.handSettled());
    }

    /**
     * Protects the contract that the showdown broadcast clears the actor, flags settlement and reveals contested cards.
     */
    @Test
    void broadcastShowdownResults_ContestedHandWithFoldedPlayer_ClearsActorFlagsSettlementAndRevealsContestedCards() {
        HandPayout settlement = new HandPayout(
                Map.of("a", 30), Map.of(), Set.of("a"), 4, true);
        List<Player> players = viewPlayers(false);
        players.set(1, Player.restoreBuilder().name("Bob").playerId("b").chips(80).currentBet(20)
                .holeCards(List.of(VIEW_TWO, VIEW_THREE)).bestHand(List.of(VIEW_TWO, VIEW_THREE))
                .handRank(HandRank.HIGH_CARD)
                .hasFolded(true).allIn(false).out(false).disconnected(false).readyForNextHand(false).build());
        Game game = viewGame(GamePhase.SHOWDOWN, settlement, true, ReadyGateState.COUNTING_DOWN,
                Map.of(), players, false);
        long revisionBefore = game.getStateRevision();

        gameStateService.broadcastShowdownResults(game);

        assertEquals(revisionBefore + 1, game.getStateRevision());
        assertEquals(new PublicGameStateResponse(
                6, game.getPot(), game.getPotBreakdown(), game.getUncalledAmount(), GamePhase.SHOWDOWN,
                game.getCurrentHighestBet(), VIEW_BOARD,
                List.of(viewState("a", "Alice", 90, 10, "ACTIVE", false, false, false, true, false,
                                HandRank.ONE_PAIR, List.of(VIEW_ACE, VIEW_KING), true, 30, List.of(VIEW_ACE, VIEW_KING)),
                        viewState("b", "Bob", 80, 20, "FOLDED", false, false, true, false, true,
                                null, List.of(), false, 0, null)),
                null, null, List.of("Alice"), 30, null, null, true, 12_345L, null, null, true, 4,
                null, null, null, 3L, ReadyGateState.COUNTING_DOWN, VIEW_NOW_MS, game.getStateRevision(),
                null, null, null),
                capturePublicBroadcast());
    }

    /**
     * Protects the contract that the auto-advance broadcast has no actor, hides cards and carries the banner.
     */
    @Test
    void broadcastGameStateWithAutoAdvance_ShouldHaveNoActorHideCardsAndCarryBanner() {
        Game game = viewGame(GamePhase.FLOP, null, false, ReadyGateState.CLOSED,
                Map.of(), viewPlayers(true), false);
        long revisionBefore = game.getStateRevision();

        gameStateService.broadcastGameStateWithAutoAdvance(game, "Banner");

        assertEquals(revisionBefore + 1, game.getStateRevision());
        assertEquals(new PublicGameStateResponse(
                6, game.getPot(), game.getPotBreakdown(), game.getUncalledAmount(), GamePhase.FLOP,
                game.getCurrentHighestBet(), VIEW_BOARD,
                List.of(viewState("a", "Alice", 90, 10, "ALL_IN", true, false, false, true, false,
                                HandRank.ONE_PAIR, List.of(), null, null, null),
                        viewState("b", "Bob", 80, 20, "ALL_IN", true, false, false, false, true,
                                HandRank.HIGH_CARD, List.of(), null, null, null)),
                null, null, null, null, true, "Banner", false, null, null, null, null, null, List.of(),
                null, null, 3L, ReadyGateState.CLOSED, VIEW_NOW_MS, game.getStateRevision(),
                null, null, null),
                capturePublicBroadcast());
    }

    /**
     * Protects the contract that a private view holds the player's own cards and rejects an unknown player.
     */
    @Test
    void getPrivatePlayerStateSnapshot_ReturnsOwnCardsAndRejectsUnknownPlayer() {
        List<Player> players = viewPlayers(false);
        players.get(1).setIsOut();
        Game game = viewGame(GamePhase.FLOP, null, false, ReadyGateState.CLOSED,
                Map.of(), players, false);

        assertEquals(new PrivatePlayerState("a", 3L, List.of(VIEW_ACE, VIEW_KING)),
                gameStateService.getPrivatePlayerStateSnapshot(game, "Alice"));
        assertEquals(new PrivatePlayerState("b", 3L, List.of(VIEW_TWO, VIEW_THREE)),
                gameStateService.getPrivatePlayerStateSnapshot(game, "Bob"));
        assertThrows(ResourceNotFoundException.class,
                () -> gameStateService.getPrivatePlayerStateSnapshot(game, "Spectator"));
    }

    /**
     * Protects the contract that a contested win game-end view reveals in-hand cards and closes the ready gate.
     */
    @Test
    void buildGameEndResponse_ContestedWin_RevealsInHandCardsAndClosesReadyGate() {
        HandPayout settlement = new HandPayout(
                Map.of("a", 30), Map.of(), Set.of("a"), 0, true);
        Game game = viewGame(GamePhase.SHOWDOWN, settlement, false, ReadyGateState.COUNTING_DOWN,
                Map.of(), viewPlayers(false), true);
        Player winner = game.findPlayerByName("Alice");

        PublicGameStateResponse response = gameStateService.buildGameEndResponse(game, winner, false);

        assertEquals(new PublicGameStateResponse(
                        6, game.getPot(), game.getPotBreakdown(), game.getUncalledAmount(),
                        GamePhase.SHOWDOWN, game.getCurrentHighestBet(), VIEW_BOARD,
                        List.of(viewState("a", "Alice", 90, 10, "ACTIVE", false, false, false, true, false,
                                        HandRank.ONE_PAIR, List.of(VIEW_ACE, VIEW_KING), true, null,
                                        List.of(VIEW_ACE, VIEW_KING)),
                                viewState("b", "Bob", 80, 20, "ACTIVE", false, false, false, false, true,
                                        HandRank.HIGH_CARD, List.of(VIEW_TWO, VIEW_THREE), false, null,
                                        List.of(VIEW_TWO, VIEW_THREE))),
                        null, null, List.of("Alice"), null, false, null, false, null, false, null, true, null,
                        List.of(), null, null, 3L, ReadyGateState.CLOSED, VIEW_NOW_MS, game.getStateRevision(),
                        null, null, PublicGameStateResponse.GameEnd.won(
                                "Alice", 90, false, "Alice wins the game with 90 chips!")),
                response);
    }

    /**
     * Protects the contract that the abandonment view has no winner, a closed gate and the INACTIVITY reason.
     */
    @Test
    void buildAbandonmentResponse_HasNoWinnerClosedGateAndInactivityReason() {
        Game game = viewGame(GamePhase.FLOP, null, false, ReadyGateState.CLOSED,
                Map.of(), viewPlayers(false), false);

        PublicGameStateResponse response = gameStateService.buildAbandonmentResponse(game);

        assertEquals(new PublicGameStateResponse(
                        6, game.getPot(), game.getPotBreakdown(), game.getUncalledAmount(), GamePhase.FLOP,
                        game.getCurrentHighestBet(), VIEW_BOARD,
                        List.of(viewState("a", "Alice", 90, 10, "ACTIVE", false, false, false, true, false,
                                        null, List.of(), false, null, null),
                                viewState("b", "Bob", 80, 20, "ACTIVE", false, false, false, false, true,
                                        null, List.of(), false, null, null)),
                        null, null, List.of(), null, false, null, false, null, false, null, false, null,
                        List.of(), null, null, 3L, ReadyGateState.CLOSED, VIEW_NOW_MS, game.getStateRevision(),
                        null, null, PublicGameStateResponse.GameEnd.abandoned(
                                "INACTIVITY", "Game abandoned due to inactivity.")),
                response);
    }

    private PublicGameStateResponse capturePublicBroadcast() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        return (PublicGameStateResponse) captor.getValue();
    }

    private static PublicGameStateResponse.PlayerState viewState(String id, String name, int chips, int bet, String status,
            boolean allIn, boolean current, boolean folded, boolean smallBlind, boolean bigBlind,
            HandRank rank, List<Card> best, Boolean winner, Integer won, List<Card> hole) {
        // viewGame gives the dealer button to Alice ("a").
        return new PublicGameStateResponse.PlayerState(id, name, chips, bet, status, allIn, current, folded, smallBlind,
                bigBlind, "a".equals(id), rank, best, winner, won, hole, null, false);
    }

    private static List<Player> viewPlayers(boolean allIn) {
        Player alice = Player.restoreBuilder().name("Alice").playerId("a").chips(90).currentBet(10)
                .holeCards(List.of(VIEW_ACE, VIEW_KING)).bestHand(List.of(VIEW_ACE, VIEW_KING))
                .handRank(HandRank.ONE_PAIR)
                .hasFolded(false).allIn(allIn).out(false).disconnected(false).readyForNextHand(false).build();
        Player bob = Player.restoreBuilder().name("Bob").playerId("b").chips(80).currentBet(20)
                .holeCards(List.of(VIEW_TWO, VIEW_THREE)).bestHand(List.of(VIEW_TWO, VIEW_THREE))
                .handRank(HandRank.HIGH_CARD)
                .hasFolded(false).allIn(allIn).out(false).disconnected(false).readyForNextHand(false).build();
        return new ArrayList<>(List.of(alice, bob));
    }

    private static Game viewGame(GamePhase phase, HandPayout settlement,
            boolean countdown, ReadyGateState gate, Map<ScheduledGameTask, Long> deadlines,
            List<Player> players, boolean gameOver) {
        return Game.restoreBuilder()
                .gameId(GAME_ID)
                .maxPlayers(6)
                .players(players)
                .activePlayerIds(players.stream().filter(Player::isInHand).map(Player::getPlayerId).toList())
                .remainingDeck(List.of())
                .communityCards(VIEW_BOARD)
                .pot(30)
                .dealerPlayerId("a")
                .smallBlindPlayerId("a")
                .bigBlindPlayerId("b")
                .currentPlayerId("a")
                .currentHighestBet(20)
                .currentPhase(phase)
                .gameOver(gameOver)
                .smallBlind(5)
                .bigBlind(10)
                .handContributions(Map.of("a", 10, "b", 20))
                .carryOverPot(0)
                .lastHandPayout(settlement)
                .readyCountdownDeadlineEpochMs(countdown ? 12_345L : null)
                .readyGateState(gate)
                .allPlayersHadInitialTurn(false)
                .actedPlayersInRound(Set.of())
                .scheduledTimes(deadlines)
                .handNumber(3L)
                .stateRevision(7L)
                .build();
    }
}
