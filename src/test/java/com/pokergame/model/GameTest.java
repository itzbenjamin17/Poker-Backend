package com.pokergame.model;

import com.pokergame.dto.internal.PlayerDecision;
import com.pokergame.enums.*;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.service.HandEvaluatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the Game class.
 */
class GameTest {

    @Mock
    private HandEvaluatorService mockHandEvaluator;

    private List<Player> players;
    private Game game;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        players = new ArrayList<>();
        players.add(new Player("Player1", "p1", 1000));
        players.add(new Player("Player2", "p2", 1000));
        players.add(new Player("Player3", "p3", 1000));

        game = new Game("game123", players, 10, 20, mockHandEvaluator);
    }

    @Test
    void testGameCreation() {
        assertNotNull(game);
        assertEquals("game123", game.getGameId());
        assertEquals(3, game.getPlayers().size());
        assertEquals(3, game.getActivePlayers().size());
        assertEquals(0, game.getPot());
        assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
        assertFalse(game.isGameOver());
    }

    @Test
    void testGameCreationWithNullGameId() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Game(null, players, 10, 20, mockHandEvaluator));
        assertEquals("Game ID cannot be null or empty", exception.getMessage());
    }

    @Test
    void testGameCreationWithEmptyGameId() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Game("   ", players, 10, 20, mockHandEvaluator));
        assertEquals("Game ID cannot be null or empty", exception.getMessage());
    }

    @Test
    void testGameCreationWithInsufficientPlayers() {
        List<Player> onePlayer = List.of(new Player("Solo", "p1", 1000));
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Game("game123", onePlayer, 10, 20, mockHandEvaluator));
        assertEquals("At least 2 players are required to start a game", exception.getMessage());
    }

    @Test
    void testGameCreationWithNullPlayersList() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Game("game123", null, 10, 20, mockHandEvaluator));
        assertEquals("At least 2 players are required to start a game", exception.getMessage());
    }

    @Test
    void testGameCreationWithNullPlayer() {
        List<Player> playersWithNull = new ArrayList<>();
        playersWithNull.add(new Player("Player1", "p1", 1000));
        playersWithNull.add(null);

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Game("game123", playersWithNull, 10, 20, mockHandEvaluator));
        assertEquals("Invalid players list. Please try again.", exception.getMessage());
    }

    @Test
    void testDealHoleCards() {
        game.dealHoleCards();

        for (Player player : game.getActivePlayers()) {
            assertEquals(2, player.getHoleCards().size());
        }
    }

    @Test
    void testPostBlinds() {
        game.postBlinds();

        Player smallBlindPlayer = game.getActivePlayers().get(1);
        Player bigBlindPlayer = game.getActivePlayers().get(2);

        assertEquals(10, smallBlindPlayer.getCurrentBet());
        assertEquals(20, bigBlindPlayer.getCurrentBet());
        assertEquals(990, smallBlindPlayer.getChips());
        assertEquals(980, bigBlindPlayer.getChips());
        assertEquals(30, game.getPot());
        assertEquals(20, game.getCurrentHighestBet());
    }

    @Test
    void testPostBlindsWithShortStack() {
        // Create player with insufficient chips for blind
        List<Player> shortStackPlayers = new ArrayList<>();
        shortStackPlayers.add(new Player("Player1", "p1", 1000));
        shortStackPlayers.add(new Player("ShortStack", "p2", 5)); // Less than small blind
        shortStackPlayers.add(new Player("Player3", "p3", 1000));

        Game shortGame = new Game("game456", shortStackPlayers, 10, 20, mockHandEvaluator);
        shortGame.postBlinds();

        Player shortStackPlayer = shortGame.getActivePlayers().get(1);
        assertTrue(shortStackPlayer.getIsAllIn());
        assertEquals(0, shortStackPlayer.getChips());
    }

    @Test
    void testProcessPlayerDecisionFold() {
        Player player = game.getCurrentPlayer();
        PlayerDecision decision = new PlayerDecision(PlayerAction.FOLD, 0, player.getPlayerId());

        game.processPlayerDecision(player, decision);

        assertTrue(player.getHasFolded());
        assertEquals(0, game.getPot());
    }

    @Test
    void testProcessPlayerDecisionCheck() {
        Player player = game.getCurrentPlayer();
        PlayerDecision decision = new PlayerDecision(PlayerAction.CHECK, 0, player.getPlayerId());

        game.processPlayerDecision(player, decision);

        assertFalse(player.getHasFolded());
        assertEquals(0, game.getPot());
        assertEquals(0, player.getCurrentBet());
    }

    @Test
    void testProcessPlayerDecisionBet() {
        Player player = game.getCurrentPlayer();
        PlayerDecision decision = new PlayerDecision(PlayerAction.BET, 50, player.getPlayerId());

        game.processPlayerDecision(player, decision);

        assertEquals(50, game.getPot());
        assertEquals(50, player.getCurrentBet());
        assertEquals(950, player.getChips());
        assertEquals(50, game.getCurrentHighestBet());
    }

    @Test
    void testProcessPlayerDecisionCall() {
        // Set up: first player bets
        game.postBlinds(); // Sets highest bet to 20
        Player caller = game.getActivePlayers().get(0);
        PlayerDecision decision = new PlayerDecision(PlayerAction.CALL, 0, caller.getPlayerId());

        game.processPlayerDecision(caller, decision);

        assertEquals(50, game.getPot()); // 30 from blinds + 20 from call
        assertEquals(20, caller.getCurrentBet());
        assertEquals(980, caller.getChips());
    }

    @Test
    void testProcessPlayerDecisionRaise() {
        game.postBlinds(); // Sets highest bet to 20
        Player raiser = game.getActivePlayers().get(0);
        PlayerDecision decision = new PlayerDecision(PlayerAction.RAISE, 50, raiser.getPlayerId());

        game.processPlayerDecision(raiser, decision);

        assertEquals(80, game.getPot()); // 30 from blinds + 50 from raise
        assertEquals(50, raiser.getCurrentBet());
        assertEquals(950, raiser.getChips());
        assertEquals(50, game.getCurrentHighestBet());
    }

    @Test
    void testProcessPlayerDecisionAllIn() {
        Player player = game.getCurrentPlayer();
        PlayerDecision decision = new PlayerDecision(PlayerAction.ALL_IN, 0, player.getPlayerId());

        game.processPlayerDecision(player, decision);

        assertEquals(1000, game.getPot());
        assertEquals(1000, player.getCurrentBet());
        assertEquals(0, player.getChips());
        assertTrue(player.getIsAllIn());
    }

    @Test
    void testProcessPlayerDecisionInvalidRaise() {
        game.postBlinds(); // Sets highest bet to 20
        Player player = game.getActivePlayers().get(0);
        player.doAction(PlayerAction.BET, 10, 0); // Player already bet 10

        // Try to raise by only 5 (total 15), which is less than current highest (20)
        PlayerDecision decision = new PlayerDecision(PlayerAction.RAISE, 5, player.getPlayerId());

        assertThrows(UnauthorisedActionException.class,
                () -> game.processPlayerDecision(player, decision));
    }

    @Test
    void testDealFlop() {
        game.dealFlop();

        assertEquals(3, game.getCommunityCards().size());
        assertEquals(GamePhase.FLOP, game.getCurrentPhase());
        assertEquals(0, game.getCurrentHighestBet());
    }

    @Test
    void testDealTurn() {
        game.dealFlop();
        game.dealTurn();

        assertEquals(4, game.getCommunityCards().size());
        assertEquals(GamePhase.TURN, game.getCurrentPhase());
    }

    @Test
    void testDealRiver() {
        game.dealFlop();
        game.dealTurn();
        game.dealRiver();

        assertEquals(5, game.getCommunityCards().size());
        assertEquals(GamePhase.RIVER, game.getCurrentPhase());
    }

    @Test
    void testIsBettingRoundComplete() {
        // Initially not complete (no one has had initial turn)
        assertTrue(game.isBettingRoundComplete());

        // Set everyone has had initial turn
        game.setEveryoneHasHadInitialTurn(true);

        // Still not complete because of different bets
        game.postBlinds();
        assertFalse(game.isBettingRoundComplete());

        // Make everyone match the bet
        for (Player player : game.getActivePlayers()) {
            if (player.getCurrentBet() < game.getCurrentHighestBet()) {
                int callAmount = game.getCurrentHighestBet() - player.getCurrentBet();
                player.payChips(0, callAmount);
            }
        }

        // Now it should be complete
        assertTrue(game.isBettingRoundComplete());
    }

    @Test
    void testSetEveryoneHasHadInitialTurn() {
        assertTrue(game.isBettingRoundComplete());

        game.setEveryoneHasHadInitialTurn(true);

        // All players have 0 bet initially, so with everyone having had a turn, round
        // is complete
        assertTrue(game.isBettingRoundComplete());
    }

    @Test
    void testResetBetsForRound() {
        game.postBlinds();
        assertEquals(20, game.getCurrentHighestBet());

        game.setEveryoneHasHadInitialTurn(true);
        game.resetBetsForRound();

        assertEquals(0, game.getCurrentHighestBet());
        for (Player player : game.getActivePlayers()) {
            assertEquals(0, player.getCurrentBet());
        }
    }

    @Test
    void testIsHandOver() {
        assertFalse(game.isHandOver());

        // Make all but one player fold
        game.getActivePlayers().get(0).doAction(PlayerAction.FOLD, 0, 0);
        game.getActivePlayers().get(1).doAction(PlayerAction.FOLD, 0, 0);

        assertTrue(game.isHandOver());
    }

    @Test
    void testConductShowdownWithOnePlayer() {
        // Make all but one player fold
        game.getActivePlayers().get(0).doAction(PlayerAction.FOLD, 0, 0);
        game.getActivePlayers().get(1).doAction(PlayerAction.FOLD, 0, 0);

        game.processPlayerDecision(game.getActivePlayers().get(2),
                new PlayerDecision(PlayerAction.BET, 100, "p3"));

        List<Player> winners = game.conductShowdown();

        assertEquals(1, winners.size());
        assertEquals("Player3", winners.get(0).getName());
        assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());
    }

    @Test
    void testConductShowdownWithMultiplePlayers() {
        // Mock the hand evaluator
        List<Card> royalFlush = List.of(
                new Card(Rank.ACE, Suit.SPADES),
                new Card(Rank.KING, Suit.SPADES),
                new Card(Rank.QUEEN, Suit.SPADES),
                new Card(Rank.JACK, Suit.SPADES),
                new Card(Rank.TEN, Suit.SPADES));

        List<Card> pair = List.of(
                new Card(Rank.ACE, Suit.HEARTS),
                new Card(Rank.ACE, Suit.DIAMONDS),
                new Card(Rank.KING, Suit.CLUBS),
                new Card(Rank.QUEEN, Suit.HEARTS),
                new Card(Rank.JACK, Suit.HEARTS));

        HandEvaluationResult winnerResult = new HandEvaluationResult(royalFlush, HandRank.ROYAL_FLUSH);
        HandEvaluationResult loserResult = new HandEvaluationResult(pair, HandRank.ONE_PAIR);

        when(mockHandEvaluator.getBestHand(any(), any()))
                .thenReturn(winnerResult)
                .thenReturn(loserResult)
                .thenReturn(loserResult);

        when(mockHandEvaluator.isBetterHandOfSameRank(any(), any(), any()))
                .thenReturn(false);

        // Add chips to pot
        game.processPlayerDecision(game.getActivePlayers().get(0),
                new PlayerDecision(PlayerAction.BET, 100, "p1"));

        List<Player> winners = game.conductShowdown();

        assertEquals(1, winners.size());
        verify(mockHandEvaluator, times(3)).getBestHand(any(), any());
    }

    @Test
    void testAdvancePositions() {
        int initialDealer = game.getDealerPosition();

        game.advancePositions();

        assertEquals((initialDealer + 1) % 3, game.getDealerPosition());
    }

    @Test
    void testNextPlayer() {
        Player firstPlayer = game.getCurrentPlayer();
        game.nextPlayer();
        Player secondPlayer = game.getCurrentPlayer();

        assertNotEquals(firstPlayer, secondPlayer);
    }

    @Test
    void testResetForNewHand() {
        // Set up some game state
        game.dealHoleCards();
        game.postBlinds();
        game.dealFlop();

        game.resetForNewHand();

        assertEquals(0, game.getCommunityCards().size());
        assertEquals(0, game.getPot());
        assertEquals(0, game.getCurrentHighestBet());
        assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());

        for (Player player : game.getActivePlayers()) {
            assertEquals(0, player.getHoleCards().size());
            assertEquals(0, player.getCurrentBet());
            assertFalse(player.getHasFolded());
            assertFalse(player.getIsAllIn());
        }
    }

    @Test
    void testCleanupAfterHand() {
        // Make a player lose all chips
        Player player = game.getActivePlayers().get(0);
        player.doAction(PlayerAction.ALL_IN, 0, 0);

        assertEquals(3, game.getActivePlayers().size());
        assertFalse(game.isGameOver());

        game.cleanupAfterHand();

        assertTrue(player.getIsOut());
        assertEquals(2, game.getActivePlayers().size());
        assertFalse(game.isGameOver()); // Still 2 players
    }

    @Test
    void testCleanupAfterHandEndsGame() {
        // Make all but one player lose all chips
        game.getActivePlayers().get(0).doAction(PlayerAction.ALL_IN, 0, 0);
        game.getActivePlayers().get(1).doAction(PlayerAction.ALL_IN, 0, 0);

        game.cleanupAfterHand();

        assertEquals(1, game.getActivePlayers().size());
        assertTrue(game.isGameOver());
    }

    @Test
    void testGetters() {
        assertEquals("game123", game.getGameId());
        assertEquals(3, game.getPlayers().size());
        assertEquals(3, game.getActivePlayers().size());
        assertNotNull(game.getCurrentPlayer());
        assertNotNull(game.getCommunityCards());
        assertEquals(0, game.getPot());
        assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
        assertFalse(game.isGameOver());
        assertEquals(0, game.getCurrentHighestBet());
        assertEquals(0, game.getDealerPosition());
    }

    @Test
    void testResetForNewHandWithEliminatedPlayers() {
        // Eliminate a player
        Player eliminated = game.getActivePlayers().get(0);
        eliminated.doAction(PlayerAction.ALL_IN, 0, 0);
        eliminated.setIsOut();

        game.resetForNewHand();

        assertEquals(2, game.getActivePlayers().size());
        assertFalse(game.getActivePlayers().contains(eliminated));
    }

    @Test
    void testProcessPlayerDecisionConvertsRaiseToCallWithAllIn() {
        // First player goes all-in
        Player allInPlayer = game.getActivePlayers().get(0);
        game.processPlayerDecision(allInPlayer,
                new PlayerDecision(PlayerAction.ALL_IN, 0, allInPlayer.getPlayerId()));

        // Second player tries to raise, should be converted to call
        Player raiser = game.getActivePlayers().get(1);
        String message = game.processPlayerDecision(raiser,
                new PlayerDecision(PlayerAction.RAISE, 500, raiser.getPlayerId()));

        assertNotNull(message);
        assertTrue(message.contains("converted to a call"));
        assertEquals(1000, raiser.getCurrentBet()); // Should match all-in amount
    }

    @Test
    void testDealingFullHandProgression() {
        // PRE_FLOP
        assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
        assertEquals(0, game.getCommunityCards().size());

        // FLOP
        game.dealFlop();
        assertEquals(GamePhase.FLOP, game.getCurrentPhase());
        assertEquals(3, game.getCommunityCards().size());

        // TURN
        game.dealTurn();
        assertEquals(GamePhase.TURN, game.getCurrentPhase());
        assertEquals(4, game.getCommunityCards().size());

        // RIVER
        game.dealRiver();
        assertEquals(GamePhase.RIVER, game.getCurrentPhase());
        assertEquals(5, game.getCommunityCards().size());

        // SHOWDOWN
        List<Card> hand = List.of(new Card(Rank.ACE, Suit.SPADES));
        when(mockHandEvaluator.getBestHand(any(), any()))
                .thenReturn(new HandEvaluationResult(hand, HandRank.HIGH_CARD));

        game.conductShowdown();
        assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());
    }
}
