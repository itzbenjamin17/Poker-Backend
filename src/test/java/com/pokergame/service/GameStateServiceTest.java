package com.pokergame.service;

import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.PrivatePlayerState;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;
import com.pokergame.model.*;
import com.pokergame.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pokergame.enums.HandRank;
import java.util.stream.Collectors;

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

    /**
     * Protects the contract that broadcast game state with null game should not broadcast.
     */
    @Test
    void broadcastGameState_WithNullGame_ShouldNotBroadcast() {
        assertThrows(BadRequestException.class, () -> gameStateService.broadcastGameState(GAME_ID, null));

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

        gameStateService.broadcastGameState(GAME_ID, testGame);

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

        gameStateService.broadcastGameState(GAME_ID, testGame);

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
        testGame = new Game(GAME_ID, testPlayers, 5, 10, handEvaluator);
        testRoom.addPlayer("Player3");

        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        gameStateService.broadcastGameState(GAME_ID, testGame);

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

        gameStateService.broadcastGameState(GAME_ID, testGame);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        Object payload = captor.getValue();
        assertInstanceOf(PublicGameStateResponse.class, payload);

        PublicGameStateResponse response = (PublicGameStateResponse) payload;
        assertEquals(Boolean.TRUE, response.claimWinAvailable());
        assertEquals(current.getName(), response.claimWinPlayerName());
    }

    // ==================== broadcastShowdownResults Tests ====================

    /**
     * Protects the contract that broadcast showdown results with null game should not broadcast.
     */
    @Test
    void broadcastShowdownResults_WithNullGame_ShouldNotBroadcast() {
        gameStateService.broadcastShowdownResults(GAME_ID, null, List.of(), 0);

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    /**
     * Protects the contract that broadcast showdown results with valid data should broadcast.
     */
    @Test
    void broadcastShowdownResults_WithValidData_ShouldBroadcast() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        List<Player> winners = List.of(testPlayers.getFirst());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

        gameStateService.broadcastShowdownResults(GAME_ID, testGame, winners, 50);

        // The showdown broadcast must be a PublicGameStateResponse naming the winner and carrying
        // the game's state revision, not merely some object on the right destination.
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        Object payload = captor.getValue();
        assertInstanceOf(PublicGameStateResponse.class, payload);
        PublicGameStateResponse response = (PublicGameStateResponse) payload;
        assertEquals(List.of(testPlayers.getFirst().getName()), response.winners(),
                "The showdown projection must report the single winner");
        assertEquals(testGame.getStateRevision(), response.stateRevision(),
                "The showdown broadcast must carry the game's current state revision");
    }

    /**
     * Protects the contract that broadcast showdown results with no room should still broadcast.
     */
    @Test
    void broadcastShowdownResults_WithNoRoom_ShouldStillBroadcast() {
        when(roomService.getRoom(GAME_ID)).thenReturn(null);
        List<Player> winners = List.of(testPlayers.getFirst());

        gameStateService.broadcastShowdownResults(GAME_ID, testGame, winners, 50);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    /**
     * Protects the contract that broadcast showdown results with multiple winners should include all winners.
     */
    @Test
    void broadcastShowdownResults_WithMultipleWinners_ShouldIncludeAllWinners() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        List<Player> winners = List.of(testPlayers.get(0), testPlayers.get(1));

        gameStateService.broadcastShowdownResults(GAME_ID, testGame, winners, 25);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    /**
     * Protects the contract that no player is reported as having the turn during a
     * showdown broadcast. Previously the stale currentPlayerId let the client
     * briefly render Fold/Check controls for the losing player between the
     * showdown broadcast and the later GAME_END broadcast.
     */
    @Test
    void broadcastShowdownResults_ShouldNotReportACurrentPlayer() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        List<Player> winners = List.of(testPlayers.getFirst());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        gameStateService.broadcastShowdownResults(GAME_ID, testGame, winners, 50);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        PublicGameStateResponse response = (PublicGameStateResponse) captor.getValue();

        assertNull(response.currentPlayerId());
        assertNull(response.currentPlayerName());
        assertTrue(response.players().stream().noneMatch(PublicPlayerState::isCurrentPlayer));
    }

    // ==================== broadcastGameStateWithAutoAdvance Tests
    // ====================

    /**
     * Protects the contract that broadcast game state with auto advance with null game should not broadcast.
     */
    @Test
    void broadcastGameStateWithAutoAdvance_WithNullGame_ShouldNotBroadcast() {
        gameStateService.broadcastGameStateWithAutoAdvance(GAME_ID, null, "Auto-advancing...");

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    /**
     * Protects the contract that broadcast game state with auto advance with valid data should broadcast.
     */
    @Test
    void broadcastGameStateWithAutoAdvance_WithValidData_ShouldBroadcast() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        gameStateService.broadcastGameStateWithAutoAdvance(GAME_ID, testGame, "Auto-advancing...");

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    // ==================== broadcastAutoAdvanceNotification Tests
    // ====================

    /**
     * Protects the contract that broadcast auto advance notification with null game should not broadcast.
     */
    @Test
    void broadcastAutoAdvanceNotification_WithNullGame_ShouldNotBroadcast() {
        gameStateService.broadcastAutoAdvanceNotification(GAME_ID, null);

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    /**
     * Protects the contract that broadcast auto advance notification with valid game should broadcast.
     */
    @Test
    void broadcastAutoAdvanceNotification_WithValidGame_ShouldBroadcast() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        gameStateService.broadcastAutoAdvanceNotification(GAME_ID, testGame);

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
     * Protects the contract that broadcast auto advance complete with null game should not broadcast.
     */
    @Test
    void broadcastAutoAdvanceComplete_WithNullGame_ShouldNotBroadcast() {
        gameStateService.broadcastAutoAdvanceComplete(GAME_ID, null);

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    /**
     * Protects the contract that broadcast auto advance complete with valid game should broadcast.
     */
    @Test
    void broadcastAutoAdvanceComplete_WithValidGame_ShouldBroadcast() {
        gameStateService.broadcastAutoAdvanceComplete(GAME_ID, testGame);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    // ==================== sendPlayerNotification Tests ====================

    /**
     * Protects the contract that send player notification should broadcast to game channel.
     */
    @Test
    void sendPlayerNotification_ShouldBroadcastToGameChannel() {
        gameStateService.sendPlayerNotification(GAME_ID, "Player1", "Test message");

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    /**
     * Protects the contract that send player notification with different players should broadcast.
     */
    @Test
    void sendPlayerNotification_WithDifferentPlayers_ShouldBroadcast() {
        gameStateService.sendPlayerNotification(GAME_ID, "Player1", "Message 1");
        gameStateService.sendPlayerNotification(GAME_ID, "Player2", "Message 2");

        verify(messagingTemplate, times(2)).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    // ==================== broadcastGameEnd Tests ====================

    /**
     * Protects the contract that broadcast game end should broadcast winner info.
     */
    @Test
    void broadcastGameEnd_ShouldBroadcastWinnerInfo() {
        Player winner = testPlayers.getFirst();
        winner.addChips(50); // Winner has more chips
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

        gameStateService.broadcastGameEnd(GAME_ID, testGame, winner, false);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());

        Object captured = captor.getValue();
        assertNotNull(captured);
        assertTrue(captured instanceof GameEndResponse);

        GameEndResponse gameEndResponse = (GameEndResponse) captured;
        assertEquals("GAME_END", gameEndResponse.type());
        assertEquals(winner.getName(), gameEndResponse.winner());
        assertEquals(winner.getChips(), gameEndResponse.winnerChips());
        assertEquals(GAME_ID, gameEndResponse.gameId());
    }

    /**
     * Protects the contract that broadcast game end message should contain winner name.
     */
    @Test
    void broadcastGameEnd_MessageShouldContainWinnerName() {
        Player winner = testPlayers.getFirst();
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        gameStateService.broadcastGameEnd(GAME_ID, testGame, winner, false);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());

        GameEndResponse gameEndResponse = (GameEndResponse) captor.getValue();
        String message = gameEndResponse.message();
        assertTrue(message.contains(winner.getName()));
        assertTrue(message.contains("wins"));
    }

    /**
     * Protects the contract that broadcast game end with null winner should not throw.
     */
    @Test
    void broadcastGameEnd_WithNullWinner_ShouldNotThrow() {
        assertDoesNotThrow(() -> gameStateService.broadcastGameEnd(GAME_ID, testGame, null, false));
    }

    /**
     * Protects the contract that a showdown game end reveals both players' hole cards
     * and the full five-card community board in the final-state snapshot.
     */
    @Test
    void broadcastGameEnd_FoldWinKeepsHoleCardsPrivate() {
        Player winner = testPlayers.getFirst();
        Player loser = testPlayers.get(1);
        loser.setIsOut();
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        gameStateService.broadcastGameEnd(GAME_ID, testGame, winner, false);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());

        GameEndResponse gameEndResponse = (GameEndResponse) captor.getValue();
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
    void broadcastGameEnd_ContestedShowdownRevealsWinnerCards() {
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
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

        gameStateService.broadcastGameEnd(GAME_ID, contested, winner, false);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        GameEndResponse event = (GameEndResponse) captor.getValue();
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
        terminal.completeGameSettlement(bb);
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

        gameStateService.broadcastGameEnd(GAME_ID, terminal, bb, true);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());

        GameEndResponse result = (GameEndResponse) captor.getValue();
        assertEquals(1010, result.winnerChips());
        assertEquals(0, result.finalState().pot());
        assertEquals(1010, result.finalState().players().stream().filter(PublicPlayerState::isWinner)
                .findFirst().orElseThrow().chips());
        terminal.completeGameSettlement(bb);
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
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

        gameStateService.broadcastShowdownResults(GAME_ID, settled, List.of(), 0);
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

        List<Player> winners = settled.conductShowdown();
        ArgumentCaptor<Object> initialCaptor = ArgumentCaptor.forClass(Object.class);
        gameStateService.broadcastShowdownResults(GAME_ID, settled, winners, 0);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), initialCaptor.capture());
        PublicGameStateResponse initial = (PublicGameStateResponse) initialCaptor.getValue();
        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(GAME_ID, settled);

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

        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(GAME_ID, settled);

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

        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(GAME_ID, voidGame);

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

        gameStateService.broadcastShowdownResults(GAME_ID, settled, List.of(winner), 150);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        PublicGameStateResponse initial = (PublicGameStateResponse) captor.getValue();
        PublicGameStateResponse ready = gameStateService.getPublicGameStateSnapshot(GAME_ID, settled);

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

        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(GAME_ID, showdownGame);

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
                .readyCountdownActive(true)
                .readyCountdownDeadlineEpochMs(System.currentTimeMillis() + 5000)
                .everyoneHasHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of())
                .handEvaluator(handEvaluator)
                .build();

        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(GAME_ID, countdownGame);

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
                .readyCountdownActive(true)
                .readyCountdownDeadlineEpochMs(System.currentTimeMillis() + 5000)
                .everyoneHasHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of())
                .handEvaluator(handEvaluator)
                .build();

        gameStateService.broadcastGameState(GAME_ID, showdownGame);

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
        gameStateService.broadcastGameState(GAME_ID, showdownGame);

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
        gameStateService.broadcastGameState(GAME_ID, showdownGame);

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

        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(GAME_ID, countdownGame);

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

    @Test
    void broadcastGameState_AfterBroadcastShowdownResultsWithoutGameLevelSettlement_PreservesSettlementAcrossReadyToggle() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        Player winner = testPlayers.getFirst();
        winner.setHandRank(HandRank.STRAIGHT);
        winner.getHoleCards().clear();
        winner.getHoleCards().addAll(List.of(new Card(Rank.TEN, Suit.SPADES), new Card(Rank.NINE, Suit.HEARTS)));

        Game gameWithoutSettlement = Game.restoreBuilder().gameId(GAME_ID).players(testPlayers)
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
                .readyCountdownActive(true)
                .readyCountdownDeadlineEpochMs(System.currentTimeMillis() + 5000)
                .everyoneHasHadInitialTurn(true)
                .actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of())
                .handEvaluator(handEvaluator)
                .build();

        // Broadcast initial showdown results (which populates retained settlement for the game)
        gameStateService.broadcastShowdownResults(GAME_ID, gameWithoutSettlement, List.of(winner), 100);

        // Player marks ready and triggers broadcastGameState
        winner.setReadyForNextHand(true);
        reset(messagingTemplate);
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        gameStateService.broadcastGameState(GAME_ID, gameWithoutSettlement);

        ArgumentCaptor<PublicGameStateResponse> captor = ArgumentCaptor.forClass(PublicGameStateResponse.class);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        PublicGameStateResponse response = captor.getValue();

        assertEquals(List.of("Player1"), response.winners());
        assertEquals(100, response.winningsPerPlayer());
        assertTrue(response.handSettled());
        PublicPlayerState p1 = response.players().stream()
                .filter(p -> p.id().equals(winner.getPlayerId())).findFirst().orElseThrow();
        assertTrue(p1.isWinner());
        assertEquals(100, p1.chipsWon());
        assertEquals(winner.getHoleCards(), p1.holeCards());
        assertTrue(p1.isReadyForNextHand());
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
    void broadcastGameEnd_ForfeitPath_ShouldNotRevealHoleCards() {
        Player winner = testPlayers.getFirst();
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        gameStateService.broadcastGameEnd(GAME_ID, testGame, winner, true);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());

        GameEndResponse gameEndResponse = (GameEndResponse) captor.getValue();
        assertTrue(gameEndResponse.isForfeit());
        PublicGameStateResponse finalState = gameEndResponse.finalState();
        assertNotNull(finalState);

        assertTrue(finalState.players().stream().allMatch(p -> p.holeCards() == null));
    }

    // ==================== Edge Case Tests ====================

    /**
     * Protects the contract that broadcast showdown results with folded player should mark as folded.
     */
    @Test
    void broadcastShowdownResults_WithFoldedPlayer_ShouldMarkAsFolded() {
        testPlayers.get(1).doAction(PlayerAction.FOLD, 0, 0);
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        List<Player> winners = List.of(testPlayers.get(0));
        gameStateService.broadcastShowdownResults(GAME_ID, testGame, winners, 100);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    /**
     * Protects the contract that broadcast showdown results with all in player should mark as all in.
     */
    @Test
    void broadcastShowdownResults_WithAllInPlayer_ShouldMarkAsAllIn() {
        testPlayers.getFirst().doAction(PlayerAction.ALL_IN, 0, 0);
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        List<Player> winners = List.of(testPlayers.getFirst());
        gameStateService.broadcastShowdownResults(GAME_ID, testGame, winners, 100);

        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), any(Object.class));
    }

    // ==================== GamePhase Tests ====================

    /**
     * Protects the contract that broadcast game state during preflop should broadcast.
     */
    @Test
    void broadcastGameState_DuringPreflop_ShouldBroadcast() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        gameStateService.broadcastGameState(GAME_ID, testGame);

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

    /**
     * Protects the contract that broadcast game state after dealing flop should broadcast.
     */
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

    /**
     * Protects the contract that multiple broadcasts should all succeed.
     */
    @Test
    void multipleBroadcasts_ShouldAllSucceed() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);

        gameStateService.broadcastGameState(GAME_ID, testGame);
        gameStateService.broadcastGameState(GAME_ID, testGame);
        gameStateService.broadcastGameState(GAME_ID, testGame);

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

        gameStateService.broadcastGameState(GAME_ID, testGame);
        gameStateService.broadcastGameState(GAME_ID, testGame);

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

        assertDoesNotThrow(() -> gameStateService.broadcastGameState(GAME_ID, testGame));
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

        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(GAME_ID, testGame);
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

        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(GAME_ID, testGame);
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

        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(GAME_ID, testGame);
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

        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(GAME_ID, testGame);
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

        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(GAME_ID, testGame);
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

        PublicGameStateResponse response = gameStateService.getPublicGameStateSnapshot(GAME_ID, testGame);
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
        Game game = new Game(GAME_ID, players, 5, 10, handEvaluator);

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

        gameStateService.broadcastGameState(GAME_ID, testGame);

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
    @DisplayName("captureGameEnd clamps maxPlayers to [2, 10] and never emits 0 when room is null")
    void captureGameEnd_WhenRoomIsNull_ClampsMaxPlayersToValidRange() {
        when(roomService.getRoom(GAME_ID)).thenReturn(null);
        Player winner = testPlayers.get(0);

        GameEndResponse response = gameStateService.captureGameEnd(GAME_ID, testGame, winner, false, 0);

        assertNotNull(response);
        assertNotNull(response.finalState());
        int maxPlayers = response.finalState().maxPlayers();
        assertTrue(maxPlayers >= 2 && maxPlayers <= 10, "maxPlayers must be within [2, 10], got: " + maxPlayers);
        assertEquals(2, maxPlayers, "Should resolve to retained player count (2) when room is null");
        assertNotEquals(0, maxPlayers, "finalState.maxPlayers must never be 0");
    }

    @Test
    @DisplayName("captureGameEnd clamps maxPlayers to retained count clamped at 10 when room is null")
    void captureGameEnd_WhenRoomIsNullWithManyPlayers_ClampsMaxPlayersToRetainedCount() {
        List<Player> fourPlayers = new ArrayList<>();
        fourPlayers.add(new Player("P1", UUID.randomUUID().toString(), 100));
        fourPlayers.add(new Player("P2", UUID.randomUUID().toString(), 100));
        fourPlayers.add(new Player("P3", UUID.randomUUID().toString(), 100));
        fourPlayers.add(new Player("P4", UUID.randomUUID().toString(), 100));
        Game fourPlayerGame = new Game("four-player-game", fourPlayers, 5, 10, handEvaluator);
        when(roomService.getRoom("four-player-game")).thenReturn(null);

        GameEndResponse response = gameStateService.captureGameEnd("four-player-game", fourPlayerGame, fourPlayers.get(0), false, 0);

        assertNotNull(response);
        assertNotNull(response.finalState());
        assertEquals(4, response.finalState().maxPlayers());
    }

    @Test
    @DisplayName("captureGameEnd clamps maxPlayers out of bounds to [2, 10]")
    void captureGameEnd_WhenConfiguredMaxPlayersOutOfBounds_ClampsToValidRange() {
        Player winner = testPlayers.get(0);

        // Negative maxPlayers
        GameEndResponse responseNegative = gameStateService.captureGameEnd(GAME_ID, testGame, winner, false, -1);
        assertNotNull(responseNegative.finalState());
        assertTrue(responseNegative.finalState().maxPlayers() >= 2 && responseNegative.finalState().maxPlayers() <= 10);

        // 1 player (below minimum 2)
        GameEndResponse responseOne = gameStateService.captureGameEnd(GAME_ID, testGame, winner, false, 1);
        assertNotNull(responseOne.finalState());
        assertTrue(responseOne.finalState().maxPlayers() >= 2 && responseOne.finalState().maxPlayers() <= 10);

        // Valid configured maxPlayers (6)
        GameEndResponse responseSix = gameStateService.captureGameEnd(GAME_ID, testGame, winner, false, 6);
        assertNotNull(responseSix.finalState());
        assertEquals(6, responseSix.finalState().maxPlayers());
    }

    @Test
    @DisplayName("broadcastGameEnd emits clamped maxPlayers in finalState when room is null")
    void broadcastGameEnd_WhenRoomIsNull_EmitsClampedMaxPlayersInFinalState() {
        when(roomService.getRoom(GAME_ID)).thenReturn(null);
        Player winner = testPlayers.get(0);

        gameStateService.broadcastGameEnd(GAME_ID, testGame, winner, false);

        GameEndResponse retained = testGame.getRetainedTerminalResult();
        assertNotNull(retained);
        assertNotNull(retained.finalState());
        int maxPlayers = retained.finalState().maxPlayers();
        assertTrue(maxPlayers >= 2 && maxPlayers <= 10, "maxPlayers must be within [2, 10], got: " + maxPlayers);
        assertNotEquals(0, maxPlayers, "finalState.maxPlayers must never be 0 when room is null");

        ArgumentCaptor<GameEndResponse> captor = ArgumentCaptor.forClass(GameEndResponse.class);
        verify(messagingTemplate).convertAndSend(eq("/game/" + GAME_ID), captor.capture());
        assertEquals(maxPlayers, captor.getValue().finalState().maxPlayers());
    }

    @Test
    @DisplayName("clearRetainedSettlement evicts cached settlement entry for gameId")
    void clearRetainedSettlement_EvictsCachedSettlementEntry() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        gameStateService.broadcastShowdownResults(GAME_ID, testGame, List.of(testPlayers.getFirst()), 100);
        assertTrue(gameStateService.hasRetainedSettlement(GAME_ID));

        gameStateService.clearRetainedSettlement(GAME_ID);

        assertFalse(gameStateService.hasRetainedSettlement(GAME_ID));
    }

    @Test
    @DisplayName("projectGameEnd does not increment stateRevision while captureGameEnd increments and clears retainedSettlements")
    void projectGameEnd_DoesNotIncrementStateRevision_AndCaptureClearsRetainedSettlements() {
        when(roomService.getRoom(GAME_ID)).thenReturn(testRoom);
        Player winner = testPlayers.getFirst();
        gameStateService.broadcastShowdownResults(GAME_ID, testGame, List.of(winner), 100);
        assertTrue(gameStateService.hasRetainedSettlement(GAME_ID));

        long revisionBefore = testGame.getStateRevision();
        GameEndResponse projected = gameStateService.projectGameEnd(GAME_ID, testGame, winner, false, 6);
        assertNotNull(projected);
        assertEquals(revisionBefore, testGame.getStateRevision(), "projectGameEnd must not increment stateRevision");
        assertEquals(revisionBefore, projected.finalState().stateRevision());

        GameEndResponse captured = gameStateService.captureGameEnd(GAME_ID, testGame, winner, false, 6);
        assertNotNull(captured);
        assertEquals(revisionBefore + 1, testGame.getStateRevision(), "captureGameEnd must increment stateRevision");
        assertFalse(gameStateService.hasRetainedSettlement(GAME_ID), "captureGameEnd must clear retainedSettlements");
    }
}
