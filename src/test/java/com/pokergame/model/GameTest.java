package com.pokergame.model;

import com.pokergame.dto.internal.PlayerDecision;
import com.pokergame.dto.internal.TurnOutcome;
import com.pokergame.enums.*;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.service.HandEvaluatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the Game class.
 */
@Tag("unit")
@DisplayName("Game model")
class GameTest {

        @Mock
        private HandEvaluatorService mockHandEvaluator;

        private List<Player> players;
        private Game game;

        /**
         * Initializes the test fixtures before each scenario.
         */
        @BeforeEach
        void setUp() {
                MockitoAnnotations.openMocks(this);

                players = new ArrayList<>();
                players.add(new Player("Player1", "p1", 1000));
                players.add(new Player("Player2", "p2", 1000));
                players.add(new Player("Player3", "p3", 1000));

                game = new Game("game123", players, 10, 20, mockHandEvaluator);
        }

        /**
         * Protects the expected behavior for game creation.
         */
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

        /**
         * Protects the expected behavior for game creation with null game ID.
         */
        @Test
        void testGameCreationWithNullGameId() {
                BadRequestException exception = assertThrows(
                                BadRequestException.class,
                                () -> new Game(null, players, 10, 20, mockHandEvaluator));
                assertEquals("Game ID cannot be null or empty", exception.getMessage());
        }

        /**
         * Protects the expected behavior for game creation with empty game ID.
         */
        @Test
        void testGameCreationWithEmptyGameId() {
                BadRequestException exception = assertThrows(
                                BadRequestException.class,
                                () -> new Game("   ", players, 10, 20, mockHandEvaluator));
                assertEquals("Game ID cannot be null or empty", exception.getMessage());
        }

        /**
         * Protects the expected behavior for game creation with insufficient players.
         */
        @Test
        void testGameCreationWithInsufficientPlayers() {
                List<Player> onePlayer = List.of(new Player("Solo", "p1", 1000));
                BadRequestException exception = assertThrows(
                                BadRequestException.class,
                                () -> new Game("game123", onePlayer, 10, 20, mockHandEvaluator));
                assertEquals("At least 2 players are required to start a game", exception.getMessage());
        }

        /**
         * Protects the expected behavior for game creation with null players list.
         */
        @Test
        void testGameCreationWithNullPlayersList() {
                BadRequestException exception = assertThrows(
                                BadRequestException.class,
                                () -> new Game("game123", null, 10, 20, mockHandEvaluator));
                assertEquals("At least 2 players are required to start a game", exception.getMessage());
        }

        /**
         * Protects the expected behavior for game creation with null player.
         */
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

        /**
         * Protects the expected behavior for deal hole cards.
         */
        @Test
        void testDealHoleCards() {
                game.dealHoleCards();

                for (Player player : game.getActivePlayers()) {
                        assertEquals(2, player.getHoleCards().size());
                }
        }

        /**
         * Protects the expected behavior for post blinds.
         */
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

        /**
         * Protects the expected behavior for post blinds with short stack.
         */
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

        /**
         * Protects the expected behavior for post blinds small blind short big blind still posts.
         */
        @Test
        void testPostBlindsSmallBlindShortBigBlindStillPosts() {
                List<Player> playersWithShortSb = new ArrayList<>();
                playersWithShortSb.add(new Player("Player1", "p1", 1000));
                playersWithShortSb.add(new Player("ShortSB", "p2", 5));
                playersWithShortSb.add(new Player("BigBlind", "p3", 1000));

                Game shortSbGame = new Game("short-sb-game", playersWithShortSb, 10, 20, mockHandEvaluator);
                shortSbGame.postBlinds();

                Player smallBlindPlayer = shortSbGame.getActivePlayers().get(1);
                Player bigBlindPlayer = shortSbGame.getActivePlayers().get(2);

                assertTrue(smallBlindPlayer.getIsAllIn());
                assertEquals(0, smallBlindPlayer.getChips());
                assertEquals(5, smallBlindPlayer.getCurrentBet());
                assertEquals(20, bigBlindPlayer.getCurrentBet());
                assertEquals(25, shortSbGame.getPot());
        }

        /**
         * Protects the expected behavior for post blinds big blind short small blind still posts.
         */
        @Test
        void testPostBlindsBigBlindShortSmallBlindStillPosts() {
                List<Player> playersWithShortBb = new ArrayList<>();
                playersWithShortBb.add(new Player("Player1", "p1", 1000));
                playersWithShortBb.add(new Player("SmallBlind", "p2", 1000));
                playersWithShortBb.add(new Player("ShortBB", "p3", 15));

                Game shortBbGame = new Game("short-bb-game", playersWithShortBb, 10, 20, mockHandEvaluator);
                shortBbGame.postBlinds();

                Player smallBlindPlayer = shortBbGame.getActivePlayers().get(1);
                Player bigBlindPlayer = shortBbGame.getActivePlayers().get(2);

                assertEquals(10, smallBlindPlayer.getCurrentBet());
                assertEquals(990, smallBlindPlayer.getChips());
                assertTrue(bigBlindPlayer.getIsAllIn());
                assertEquals(15, bigBlindPlayer.getCurrentBet());
                assertEquals(0, bigBlindPlayer.getChips());
                assertEquals(25, shortBbGame.getPot());
        }

        /**
         * Protects the expected behavior for post blinds both short includes both contributions edge case.
         */
        @Test
        void testPostBlindsBothShortIncludesBothContributionsEdgeCase() {
                List<Player> playersWithBothShort = new ArrayList<>();
                playersWithBothShort.add(new Player("Player1", "p1", 1000));
                playersWithBothShort.add(new Player("ShortSB", "p2", 7));
                playersWithBothShort.add(new Player("ShortBB", "p3", 15));

                Game bothShortGame = new Game("both-short-game", playersWithBothShort, 10, 20, mockHandEvaluator);
                bothShortGame.postBlinds();

                Player smallBlindPlayer = bothShortGame.getActivePlayers().get(1);
                Player bigBlindPlayer = bothShortGame.getActivePlayers().get(2);

                assertTrue(smallBlindPlayer.getIsAllIn());
                assertTrue(bigBlindPlayer.getIsAllIn());
                assertEquals(7, smallBlindPlayer.getCurrentBet());
                assertEquals(15, bigBlindPlayer.getCurrentBet());
                assertEquals(22, bothShortGame.getPot());
        }

        /**
         * Protects the expected behavior for post blinds current highest bet uses actual big blind when covered.
         */
        @Test
        void testPostBlindsCurrentHighestBetUsesActualBigBlindWhenCovered() {
                game.postBlinds();

                assertEquals(20, game.getCurrentHighestBet());
        }

        /**
         * Protects the expected behavior for post blinds current highest bet uses actual all in when big blind short.
         */
        @Test
        void testPostBlindsCurrentHighestBetUsesActualAllInWhenBigBlindShort() {
                List<Player> playersWithShortBb = new ArrayList<>();
                playersWithShortBb.add(new Player("Player1", "p1", 1000));
                playersWithShortBb.add(new Player("SmallBlind", "p2", 1000));
                playersWithShortBb.add(new Player("ShortBB", "p3", 15));

                Game shortBbGame = new Game("short-bb-highest-bet", playersWithShortBb, 10, 20, mockHandEvaluator);
                shortBbGame.postBlinds();

                assertEquals(15, shortBbGame.getCurrentHighestBet());
        }

        /**
         * Protects the expected behavior for post blinds current highest bet both short uses max posted contribution edge case.
         */
        @Test
        void testPostBlindsCurrentHighestBetBothShortUsesMaxPostedContributionEdgeCase() {
                List<Player> playersWithBothShort = new ArrayList<>();
                playersWithBothShort.add(new Player("Player1", "p1", 1000));
                playersWithBothShort.add(new Player("ShortSB", "p2", 7));
                playersWithBothShort.add(new Player("ShortBB", "p3", 15));

                Game bothShortGame = new Game("both-short-highest-bet", playersWithBothShort, 10, 20,
                                mockHandEvaluator);
                bothShortGame.postBlinds();

                assertEquals(15, bothShortGame.getCurrentHighestBet());
        }

        /**
         * Protects the expected behavior for process player decision fold.
         */
        @Test
        void testProcessPlayerDecisionFold() {
                Player player = game.getCurrentPlayer();
                PlayerDecision decision = new PlayerDecision(PlayerAction.FOLD, 0, player.getPlayerId());

                game.processPlayerDecision(player, decision);

                assertTrue(player.getHasFolded());
                assertEquals(0, game.getPot());
        }

        /**
         * Protects the expected behavior for process player decision check.
         */
        @Test
        void testProcessPlayerDecisionCheck() {
                Player player = game.getCurrentPlayer();
                PlayerDecision decision = new PlayerDecision(PlayerAction.CHECK, 0, player.getPlayerId());

                game.processPlayerDecision(player, decision);

                assertFalse(player.getHasFolded());
                assertEquals(0, game.getPot());
                assertEquals(0, player.getCurrentBet());
        }

        /**
         * Protects the expected behavior for process player decision check with active bet.
         */
        @Test
        void testProcessPlayerDecisionCheckWithActiveBet() {
                game.postBlinds(); // Sets highest bet to 20
                Player player = game.getCurrentPlayer();
                PlayerDecision decision = new PlayerDecision(PlayerAction.CHECK, 0, player.getPlayerId());

                UnauthorisedActionException exception = assertThrows(
                                UnauthorisedActionException.class,
                                () -> game.processPlayerDecision(player, decision));

                assertTrue(exception.getMessage().contains("Cannot check when there is an active bet"));
        }

        /**
         * Protects the expected behavior for process player decision bet.
         */
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

        /**
         * Protects the expected behavior for process player decision bet with active bet.
         */
        @Test
        void testProcessPlayerDecisionBetWithActiveBet() {
                game.postBlinds(); // Sets highest bet to 20
                Player player = game.getCurrentPlayer();
                PlayerDecision decision = new PlayerDecision(PlayerAction.BET, 50, player.getPlayerId());

                UnauthorisedActionException exception = assertThrows(
                                UnauthorisedActionException.class,
                                () -> game.processPlayerDecision(player, decision));

                assertTrue(exception.getMessage().contains("Cannot bet when there is an active bet"));
        }

        /**
         * Protects the expected behavior for process player decision call.
         */
        @Test
        void testProcessPlayerDecisionCall() {
                // Set up: first player bets
                game.postBlinds(); // Sets highest bet to 20
                Player caller = game.getActivePlayers().getFirst();
                PlayerDecision decision = new PlayerDecision(PlayerAction.CALL, 0, caller.getPlayerId());

                game.processPlayerDecision(caller, decision);

                assertEquals(50, game.getPot()); // 30 from blinds + 20 from call
                assertEquals(20, caller.getCurrentBet());
                assertEquals(980, caller.getChips());
        }

        /**
         * Protects the expected behavior for process player decision raise.
         */
        @Test
        void testProcessPlayerDecisionRaise() {
                game.postBlinds(); // Sets highest bet to 20
                Player raiser = game.getActivePlayers().getFirst();
                PlayerDecision decision = new PlayerDecision(PlayerAction.RAISE, 50, raiser.getPlayerId());

                game.processPlayerDecision(raiser, decision);

                assertEquals(80, game.getPot()); // 30 from blinds + 50 from raise
                assertEquals(50, raiser.getCurrentBet());
                assertEquals(950, raiser.getChips());
                assertEquals(50, game.getCurrentHighestBet());
        }

        /**
         * Protects the expected behavior for process player decision all in.
         */
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

        /**
         * Protects the expected behavior for process player decision invalid raise.
         */
        @Test
        void testProcessPlayerDecisionInvalidRaise() {
                game.postBlinds(); // Sets highest bet to 20
                Player player = game.getActivePlayers().getFirst();
                player.doAction(PlayerAction.BET, 10, 0); // Player already bet 10

                // Try to raise by only 5 (total 15), which is less than current highest (20)
                PlayerDecision decision = new PlayerDecision(PlayerAction.RAISE, 5, player.getPlayerId());

                assertThrows(UnauthorisedActionException.class,
                                () -> game.processPlayerDecision(player, decision));
        }

        @Test
        void testProcessPlayerDecisionBetLessThanBigBlind() {
                Player player = game.getCurrentPlayer();
                // Big blind is 20. Try to bet 10.
                PlayerDecision decision = new PlayerDecision(PlayerAction.BET, 10, player.getPlayerId());

                BadRequestException exception = assertThrows(
                                BadRequestException.class,
                                () -> game.processPlayerDecision(player, decision));

                assertTrue(exception.getMessage().contains("Bet amount must be at least the big blind"));
        }

        @Test
        void testProcessPlayerDecisionRaiseLessThanBigBlind() {
                game.postBlinds(); // Sets highest bet to 20
                Player raiser = game.getActivePlayers().getFirst(); // Player1
                // Current highest bet is 20. Try to raise to 30 (increment is 10, which is less than big blind of 20).
                // Raiser's current bet is 0, so raise amount is 30.
                PlayerDecision decision = new PlayerDecision(PlayerAction.RAISE, 30, raiser.getPlayerId());

                BadRequestException exception = assertThrows(
                                BadRequestException.class,
                                () -> game.processPlayerDecision(raiser, decision));

                assertTrue(exception.getMessage().contains("Raise amount must be at least the big blind"));
        }

        @Test
        void testProcessPlayerDecisionAllowsBetLessThanBigBlindIfAllIn() {
                Player player = game.getCurrentPlayer();
                player.setChips(15); // Stack is less than big blind of 20
                // Bet all chips (15) which is less than big blind but all-in.
                PlayerDecision decision = new PlayerDecision(PlayerAction.BET, 15, player.getPlayerId());

                assertDoesNotThrow(() -> game.processPlayerDecision(player, decision));
                assertEquals(15, player.getCurrentBet());
                assertTrue(player.getIsAllIn());
        }

        @Test
        void testProcessPlayerDecisionAllowsRaiseLessThanBigBlindIfAllIn() {
                game.postBlinds(); // Sets highest bet to 20
                Player raiser = game.getActivePlayers().getFirst(); // Player1
                raiser.setChips(25); // Stack is less than big blind of 20 above the current highest bet
                // Current highest bet is 20. Raise all-in by putting in all 25 chips (increment is 5, less than 20 big blind).
                PlayerDecision decision = new PlayerDecision(PlayerAction.RAISE, 25, raiser.getPlayerId());

                assertDoesNotThrow(() -> game.processPlayerDecision(raiser, decision));
                assertEquals(25, raiser.getCurrentBet());
                assertTrue(raiser.getIsAllIn());
        }

        /**
         * Protects the expected behavior for deal flop.
         */
        @Test
        void testDealFlop() {
                game.dealFlop();

                assertEquals(3, game.getCommunityCards().size());
                assertEquals(GamePhase.FLOP, game.getCurrentPhase());
                assertEquals(0, game.getCurrentHighestBet());
        }

        /**
         * Protects the expected behavior for deal turn.
         */
        @Test
        void testDealTurn() {
                game.dealFlop();
                game.dealTurn();

                assertEquals(4, game.getCommunityCards().size());
                assertEquals(GamePhase.TURN, game.getCurrentPhase());
        }

        /**
         * Protects the expected behavior for deal river.
         */
        @Test
        void testDealRiver() {
                game.dealFlop();
                game.dealTurn();
                game.dealRiver();

                assertEquals(5, game.getCommunityCards().size());
                assertEquals(GamePhase.RIVER, game.getCurrentPhase());
        }

        /**
         * Protects the expected behavior for is betting round complete.
         */
        @Test
        void testIsBettingRoundComplete() {
                // Initially not complete because no one has had an initial turn yet.
                assertFalse(game.isBettingRoundComplete());

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

        /**
         * Protects the expected behavior for set everyone has had initial turn.
         */
        @Test
        void testSetEveryoneHasHadInitialTurn() {
                assertFalse(game.isBettingRoundComplete());

                game.setEveryoneHasHadInitialTurn(true);

                // All players have 0 bet initially, so with everyone having had a turn, round
                // is complete
                assertTrue(game.isBettingRoundComplete());
        }

        /**
         * Protects the expected behavior for reset bets for round.
         */
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

        /**
         * Protects the expected behavior for is hand over.
         */
        @Test
        void testIsHandOver() {
                assertFalse(game.isHandOver());

                // Make all but one player fold
                game.getActivePlayers().get(0).doAction(PlayerAction.FOLD, 0, 0);
                game.getActivePlayers().get(1).doAction(PlayerAction.FOLD, 0, 0);

                assertTrue(game.isHandOver());
        }

        /**
         * Protects the expected behavior for conduct showdown with one player.
         */
        @Test
        void testConductShowdownWithOnePlayer() {
                game.processPlayerDecision(game.getActivePlayers().get(2),
                                new PlayerDecision(PlayerAction.BET, 100, "p3"));

                // Make all but one player fold
                game.getActivePlayers().get(0).doAction(PlayerAction.FOLD, 0, 0);
                game.getActivePlayers().get(1).doAction(PlayerAction.FOLD, 0, 0);

                List<Player> winners = game.conductShowdown();

                assertEquals(1, winners.size());
                assertEquals("Player3", winners.getFirst().getName());
                assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());
        }

        /**
         * Protects the expected behavior for conduct showdown with multiple players.
         */
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
                game.processPlayerDecision(game.getActivePlayers().getFirst(),
                                new PlayerDecision(PlayerAction.BET, 100, "p1"));

                List<Player> winners = game.conductShowdown();

                assertEquals(1, winners.size());
                verify(mockHandEvaluator, times(3)).getBestHand(any(), any());
        }

        /**
         * Protects the expected behavior for conduct showdown regression josh vs benjamin with real evaluation.
         */
        @Test
        void testConductShowdownRegressionJoshVsBenjaminWithRealEvaluation() {
                List<Player> headsUpPlayers = new ArrayList<>();
                headsUpPlayers.add(new Player("josh", "j1", 1000));
                headsUpPlayers.add(new Player("Benjamin", "b1", 1000));

                Game headsUpGame = new Game(
                                "regression-josh-benjamin",
                                headsUpPlayers,
                                10,
                                20,
                                new HandEvaluatorService());

                Player josh = headsUpGame.getActivePlayers().getFirst();
                Player benjamin = headsUpGame.getActivePlayers().get(1);

                josh.getHoleCards().clear();
                josh.getHoleCards().addAll(List.of(
                                new Card(Rank.THREE, Suit.SPADES),
                                new Card(Rank.KING, Suit.DIAMONDS)));

                benjamin.getHoleCards().clear();
                benjamin.getHoleCards().addAll(List.of(
                                new Card(Rank.ACE, Suit.HEARTS),
                                new Card(Rank.TEN, Suit.CLUBS)));

                headsUpGame.getCommunityCards().clear();
                headsUpGame.getCommunityCards().addAll(List.of(
                                new Card(Rank.THREE, Suit.DIAMONDS),
                                new Card(Rank.JACK, Suit.CLUBS),
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.EIGHT, Suit.HEARTS),
                                new Card(Rank.TWO, Suit.CLUBS)));

                List<Player> winners = headsUpGame.conductShowdown();

                assertEquals(HandRank.ONE_PAIR, josh.getHandRank());
                assertEquals(HandRank.ONE_PAIR, benjamin.getHandRank());
                assertEquals(List.of(
                                new Card(Rank.THREE, Suit.SPADES),
                                new Card(Rank.THREE, Suit.DIAMONDS),
                                new Card(Rank.JACK, Suit.CLUBS),
                                new Card(Rank.KING, Suit.DIAMONDS),
                                new Card(Rank.ACE, Suit.SPADES)),
                                josh.getBestHand());
                assertEquals(List.of(
                                new Card(Rank.EIGHT, Suit.HEARTS),
                                new Card(Rank.TEN, Suit.CLUBS),
                                new Card(Rank.JACK, Suit.CLUBS),
                                new Card(Rank.ACE, Suit.HEARTS),
                                new Card(Rank.ACE, Suit.SPADES)),
                                benjamin.getBestHand());
                assertEquals(1, winners.size());
                assertEquals("Benjamin", winners.getFirst().getName(),
                                "Regression: Benjamin should win with pair of aces over josh's pair of threes");
        }

        /**
         * Protects the expected behavior for distribute pot remainder preserved after showdown split.
         */
        @Test
        void testDistributePotRemainderPreservedAfterShowdownSplit() {
                List<Card> highCard = List.of(
                                new Card(Rank.TWO, Suit.SPADES),
                                new Card(Rank.FIVE, Suit.HEARTS),
                                new Card(Rank.SEVEN, Suit.DIAMONDS),
                                new Card(Rank.NINE, Suit.CLUBS),
                                new Card(Rank.JACK, Suit.SPADES));

                when(mockHandEvaluator.getBestHand(any(), any()))
                                .thenReturn(new HandEvaluationResult(highCard, HandRank.HIGH_CARD));
                when(mockHandEvaluator.isBetterHandOfSameRank(any(), any(), any()))
                                .thenReturn(false);

                game.processPlayerDecision(game.getActivePlayers().getFirst(),
                                new PlayerDecision(PlayerAction.BET, 100, "p1"));

                game.conductShowdown();

                assertEquals(1, game.getPot());
        }

        /**
         * Protects the expected behavior for reset for new hand preserves pot remainder for next hand.
         */
        @Test
        void testResetForNewHandPreservesPotRemainderForNextHand() {
                List<Card> highCard = List.of(
                                new Card(Rank.TWO, Suit.SPADES),
                                new Card(Rank.FIVE, Suit.HEARTS),
                                new Card(Rank.SEVEN, Suit.DIAMONDS),
                                new Card(Rank.NINE, Suit.CLUBS),
                                new Card(Rank.JACK, Suit.SPADES));

                when(mockHandEvaluator.getBestHand(any(), any()))
                                .thenReturn(new HandEvaluationResult(highCard, HandRank.HIGH_CARD));
                when(mockHandEvaluator.isBetterHandOfSameRank(any(), any(), any()))
                                .thenReturn(false);

                game.processPlayerDecision(game.getActivePlayers().getFirst(),
                                new PlayerDecision(PlayerAction.BET, 100, "p1"));
                game.conductShowdown();

                assertEquals(1, game.getPot());
                game.resetForNewHand();
                assertEquals(1, game.getPot());
        }

        /**
         * Protects the expected behavior for reset for new hand preserves remainder across multiple hands edge case.
         */
        @Test
        void testResetForNewHandPreservesRemainderAcrossMultipleHandsEdgeCase() {
                List<Card> highCard = List.of(
                                new Card(Rank.TWO, Suit.SPADES),
                                new Card(Rank.FIVE, Suit.HEARTS),
                                new Card(Rank.SEVEN, Suit.DIAMONDS),
                                new Card(Rank.NINE, Suit.CLUBS),
                                new Card(Rank.JACK, Suit.SPADES));

                when(mockHandEvaluator.getBestHand(any(), any()))
                                .thenReturn(new HandEvaluationResult(highCard, HandRank.HIGH_CARD));
                when(mockHandEvaluator.isBetterHandOfSameRank(any(), any(), any()))
                                .thenReturn(false);

                game.processPlayerDecision(game.getActivePlayers().getFirst(),
                                new PlayerDecision(PlayerAction.BET, 100, "p1"));
                game.conductShowdown();
                assertEquals(1, game.getPot());

                game.resetForNewHand();
                game.processPlayerDecision(game.getCurrentPlayer(),
                                new PlayerDecision(PlayerAction.BET, 99, game.getCurrentPlayer().getPlayerId()));
                game.conductShowdown();

                assertEquals(1, game.getPot());
        }

        /**
         * Protects the expected behavior for conduct showdown distributes main and side pot to different winners.
         */
        @Test
        void testConductShowdown_DistributesMainAndSidePotToDifferentWinners() {
                List<Player> sidePotPlayers = new ArrayList<>();
                sidePotPlayers.add(new Player("Short", "s1", 50));
                sidePotPlayers.add(new Player("Mid", "m1", 300));
                sidePotPlayers.add(new Player("Deep", "d1", 300));

                Game sidePotGame = new Game("side-pot-basic", sidePotPlayers, 10, 20, mockHandEvaluator);

                Player shortStack = sidePotPlayers.get(0);
                Player midStack = sidePotPlayers.get(1);
                Player deepStack = sidePotPlayers.get(2);

                sidePotGame.processPlayerDecision(midStack,
                                new PlayerDecision(PlayerAction.BET, 150, midStack.getPlayerId()));
                sidePotGame.processPlayerDecision(shortStack,
                                new PlayerDecision(PlayerAction.CALL, 0, shortStack.getPlayerId()));
                sidePotGame.processPlayerDecision(deepStack,
                                new PlayerDecision(PlayerAction.CALL, 0, deepStack.getPlayerId()));

                List<Card> shortBest = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.KING, Suit.SPADES),
                                new Card(Rank.QUEEN, Suit.SPADES),
                                new Card(Rank.JACK, Suit.SPADES),
                                new Card(Rank.NINE, Suit.SPADES));
                List<Card> midBest = List.of(
                                new Card(Rank.ACE, Suit.HEARTS),
                                new Card(Rank.KING, Suit.HEARTS),
                                new Card(Rank.QUEEN, Suit.HEARTS),
                                new Card(Rank.JACK, Suit.HEARTS),
                                new Card(Rank.EIGHT, Suit.HEARTS));
                List<Card> deepBest = List.of(
                                new Card(Rank.TEN, Suit.CLUBS),
                                new Card(Rank.TEN, Suit.HEARTS),
                                new Card(Rank.FIVE, Suit.CLUBS),
                                new Card(Rank.FOUR, Suit.DIAMONDS),
                                new Card(Rank.TWO, Suit.SPADES));

                when(mockHandEvaluator.getBestHand(any(), any()))
                                .thenReturn(new HandEvaluationResult(shortBest, HandRank.FLUSH))
                                .thenReturn(new HandEvaluationResult(midBest, HandRank.STRAIGHT))
                                .thenReturn(new HandEvaluationResult(deepBest, HandRank.ONE_PAIR));

                sidePotGame.conductShowdown();

                assertEquals(150, shortStack.getChips());
                assertEquals(350, midStack.getChips());
                assertEquals(150, deepStack.getChips());
                assertEquals(0, sidePotGame.getPot());
        }

        /**
         * Protects the expected behavior for conduct showdown splits side pot between tied winners.
         */
        @Test
        void testConductShowdown_SplitsSidePotBetweenTiedWinners() {
                List<Player> sidePotPlayers = new ArrayList<>();
                sidePotPlayers.add(new Player("Short", "s1", 50));
                sidePotPlayers.add(new Player("Mid", "m1", 300));
                sidePotPlayers.add(new Player("Deep", "d1", 300));

                Game sidePotGame = new Game("side-pot-split", sidePotPlayers, 10, 20, mockHandEvaluator);

                Player shortStack = sidePotPlayers.get(0);
                Player midStack = sidePotPlayers.get(1);
                Player deepStack = sidePotPlayers.get(2);

                sidePotGame.processPlayerDecision(midStack,
                                new PlayerDecision(PlayerAction.BET, 150, midStack.getPlayerId()));
                sidePotGame.processPlayerDecision(shortStack,
                                new PlayerDecision(PlayerAction.CALL, 0, shortStack.getPlayerId()));
                sidePotGame.processPlayerDecision(deepStack,
                                new PlayerDecision(PlayerAction.CALL, 0, deepStack.getPlayerId()));

                List<Card> shortBest = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.KING, Suit.SPADES),
                                new Card(Rank.QUEEN, Suit.SPADES),
                                new Card(Rank.JACK, Suit.SPADES),
                                new Card(Rank.NINE, Suit.SPADES));
                List<Card> tiedSideWinner = List.of(
                                new Card(Rank.ACE, Suit.HEARTS),
                                new Card(Rank.KING, Suit.HEARTS),
                                new Card(Rank.QUEEN, Suit.HEARTS),
                                new Card(Rank.JACK, Suit.HEARTS),
                                new Card(Rank.EIGHT, Suit.HEARTS));

                when(mockHandEvaluator.getBestHand(any(), any()))
                                .thenReturn(new HandEvaluationResult(shortBest, HandRank.FLUSH))
                                .thenReturn(new HandEvaluationResult(tiedSideWinner, HandRank.STRAIGHT))
                                .thenReturn(new HandEvaluationResult(tiedSideWinner, HandRank.STRAIGHT));
                when(mockHandEvaluator.isBetterHandOfSameRank(any(), any(), any())).thenReturn(false);

                sidePotGame.conductShowdown();

                assertEquals(150, shortStack.getChips());
                assertEquals(250, midStack.getChips());
                assertEquals(250, deepStack.getChips());
                assertEquals(0, sidePotGame.getPot());
        }

        /**
         * Protects the expected behavior for conduct showdown distributes multiple side pot layers.
         */
        @Test
        void testConductShowdown_DistributesMultipleSidePotLayers() {
                List<Player> sidePotPlayers = new ArrayList<>();
                sidePotPlayers.add(new Player("A", "a1", 50));
                sidePotPlayers.add(new Player("B", "b1", 100));
                sidePotPlayers.add(new Player("C", "c1", 300));
                sidePotPlayers.add(new Player("D", "d1", 300));

                Game sidePotGame = new Game("side-pot-multi", sidePotPlayers, 10, 20, mockHandEvaluator);

                Player a = sidePotPlayers.get(0);
                Player b = sidePotPlayers.get(1);
                Player c = sidePotPlayers.get(2);
                Player d = sidePotPlayers.get(3);

                sidePotGame.processPlayerDecision(c, new PlayerDecision(PlayerAction.BET, 200, c.getPlayerId()));
                sidePotGame.processPlayerDecision(d, new PlayerDecision(PlayerAction.CALL, 0, d.getPlayerId()));
                sidePotGame.processPlayerDecision(b, new PlayerDecision(PlayerAction.CALL, 0, b.getPlayerId()));
                sidePotGame.processPlayerDecision(a, new PlayerDecision(PlayerAction.CALL, 0, a.getPlayerId()));

                List<Card> aBest = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.KING, Suit.SPADES),
                                new Card(Rank.QUEEN, Suit.SPADES),
                                new Card(Rank.JACK, Suit.SPADES),
                                new Card(Rank.NINE, Suit.SPADES));
                List<Card> bBest = List.of(
                                new Card(Rank.ACE, Suit.HEARTS),
                                new Card(Rank.KING, Suit.HEARTS),
                                new Card(Rank.QUEEN, Suit.HEARTS),
                                new Card(Rank.JACK, Suit.HEARTS),
                                new Card(Rank.EIGHT, Suit.HEARTS));
                List<Card> cBest = List.of(
                                new Card(Rank.KING, Suit.CLUBS),
                                new Card(Rank.QUEEN, Suit.CLUBS),
                                new Card(Rank.JACK, Suit.CLUBS),
                                new Card(Rank.TEN, Suit.CLUBS),
                                new Card(Rank.NINE, Suit.CLUBS));
                List<Card> dBest = List.of(
                                new Card(Rank.TEN, Suit.CLUBS),
                                new Card(Rank.TEN, Suit.HEARTS),
                                new Card(Rank.FIVE, Suit.CLUBS),
                                new Card(Rank.FOUR, Suit.DIAMONDS),
                                new Card(Rank.TWO, Suit.SPADES));

                when(mockHandEvaluator.getBestHand(any(), any()))
                                .thenReturn(new HandEvaluationResult(aBest, HandRank.FLUSH))
                                .thenReturn(new HandEvaluationResult(bBest, HandRank.STRAIGHT))
                                .thenReturn(new HandEvaluationResult(cBest, HandRank.THREE_OF_A_KIND))
                                .thenReturn(new HandEvaluationResult(dBest, HandRank.ONE_PAIR));

                sidePotGame.conductShowdown();

                assertEquals(200, a.getChips());
                assertEquals(150, b.getChips());
                assertEquals(300, c.getChips());
                assertEquals(100, d.getChips());
                assertEquals(0, sidePotGame.getPot());
        }

        /**
         * Protects the expected behavior for conduct showdown side pot ignores folded contributors edge case.
         */
        @Test
        void testConductShowdown_SidePotIgnoresFoldedContributorsEdgeCase() {
                List<Player> sidePotPlayers = new ArrayList<>();
                sidePotPlayers.add(new Player("Short", "s1", 50));
                sidePotPlayers.add(new Player("Mid", "m1", 300));
                sidePotPlayers.add(new Player("Folder", "f1", 300));

                Game sidePotGame = new Game("side-pot-folded-edge", sidePotPlayers, 10, 20, mockHandEvaluator);

                Player shortStack = sidePotPlayers.get(0);
                Player midStack = sidePotPlayers.get(1);
                Player folder = sidePotPlayers.get(2);

                sidePotGame.processPlayerDecision(midStack,
                                new PlayerDecision(PlayerAction.BET, 150, midStack.getPlayerId()));
                sidePotGame.processPlayerDecision(shortStack,
                                new PlayerDecision(PlayerAction.CALL, 0, shortStack.getPlayerId()));
                sidePotGame.processPlayerDecision(folder,
                                new PlayerDecision(PlayerAction.CALL, 0, folder.getPlayerId()));
                folder.doAction(PlayerAction.FOLD, 0, 0);

                List<Card> shortBest = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.KING, Suit.SPADES),
                                new Card(Rank.QUEEN, Suit.SPADES),
                                new Card(Rank.JACK, Suit.SPADES),
                                new Card(Rank.NINE, Suit.SPADES));
                List<Card> midBest = List.of(
                                new Card(Rank.TEN, Suit.CLUBS),
                                new Card(Rank.TEN, Suit.HEARTS),
                                new Card(Rank.FIVE, Suit.CLUBS),
                                new Card(Rank.FOUR, Suit.DIAMONDS),
                                new Card(Rank.TWO, Suit.SPADES));

                when(mockHandEvaluator.getBestHand(any(), any()))
                                .thenReturn(new HandEvaluationResult(shortBest, HandRank.FLUSH))
                                .thenReturn(new HandEvaluationResult(midBest, HandRank.ONE_PAIR));

                sidePotGame.conductShowdown();

                assertEquals(150, shortStack.getChips());
                assertEquals(350, midStack.getChips());
                assertEquals(150, folder.getChips());
                assertEquals(0, sidePotGame.getPot());
        }

        /**
         * Protects the expected behavior for conduct showdown short stack cannot win beyond main pot edge case.
         */
        @Test
        void testConductShowdown_ShortStackCannotWinBeyondMainPotEdgeCase() {
                List<Player> sidePotPlayers = new ArrayList<>();
                sidePotPlayers.add(new Player("Short", "s1", 50));
                sidePotPlayers.add(new Player("Mid", "m1", 300));
                sidePotPlayers.add(new Player("Deep", "d1", 300));

                Game sidePotGame = new Game("side-pot-cap-edge", sidePotPlayers, 10, 20, mockHandEvaluator);

                Player shortStack = sidePotPlayers.get(0);
                Player midStack = sidePotPlayers.get(1);
                Player deepStack = sidePotPlayers.get(2);

                sidePotGame.processPlayerDecision(midStack,
                                new PlayerDecision(PlayerAction.BET, 150, midStack.getPlayerId()));
                sidePotGame.processPlayerDecision(shortStack,
                                new PlayerDecision(PlayerAction.CALL, 0, shortStack.getPlayerId()));
                sidePotGame.processPlayerDecision(deepStack,
                                new PlayerDecision(PlayerAction.CALL, 0, deepStack.getPlayerId()));

                List<Card> shortBest = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.KING, Suit.SPADES),
                                new Card(Rank.QUEEN, Suit.SPADES),
                                new Card(Rank.JACK, Suit.SPADES),
                                new Card(Rank.NINE, Suit.SPADES));
                List<Card> midBest = List.of(
                                new Card(Rank.ACE, Suit.HEARTS),
                                new Card(Rank.KING, Suit.HEARTS),
                                new Card(Rank.QUEEN, Suit.HEARTS),
                                new Card(Rank.JACK, Suit.HEARTS),
                                new Card(Rank.EIGHT, Suit.HEARTS));
                List<Card> deepBest = List.of(
                                new Card(Rank.TEN, Suit.CLUBS),
                                new Card(Rank.TEN, Suit.HEARTS),
                                new Card(Rank.FIVE, Suit.CLUBS),
                                new Card(Rank.FOUR, Suit.DIAMONDS),
                                new Card(Rank.TWO, Suit.SPADES));

                when(mockHandEvaluator.getBestHand(any(), any()))
                                .thenReturn(new HandEvaluationResult(shortBest, HandRank.FLUSH))
                                .thenReturn(new HandEvaluationResult(midBest, HandRank.STRAIGHT))
                                .thenReturn(new HandEvaluationResult(deepBest, HandRank.ONE_PAIR));

                sidePotGame.conductShowdown();

                assertEquals(150, shortStack.getChips(),
                                "Short stack should only receive the main pot they were eligible for");
                assertEquals(350, midStack.getChips());
                assertEquals(150, deepStack.getChips());
        }

        /**
         * Protects the expected behavior for conduct showdown side pot split remainder stays in pot edge case.
         */
        @Test
        void testConductShowdown_SidePotSplitRemainderStaysInPotEdgeCase() {
                List<Player> sidePotPlayers = new ArrayList<>();
                sidePotPlayers.add(new Player("A", "a1", 50));
                sidePotPlayers.add(new Player("B", "b1", 101));
                sidePotPlayers.add(new Player("C", "c1", 101));
                sidePotPlayers.add(new Player("D", "d1", 101));

                Game sidePotGame = new Game("side-pot-remainder-edge", sidePotPlayers, 10, 20, mockHandEvaluator);

                Player a = sidePotPlayers.get(0);
                Player b = sidePotPlayers.get(1);
                Player c = sidePotPlayers.get(2);
                Player d = sidePotPlayers.get(3);

                sidePotGame.processPlayerDecision(b, new PlayerDecision(PlayerAction.BET, 101, b.getPlayerId()));
                sidePotGame.processPlayerDecision(c, new PlayerDecision(PlayerAction.CALL, 0, c.getPlayerId()));
                sidePotGame.processPlayerDecision(d, new PlayerDecision(PlayerAction.CALL, 0, d.getPlayerId()));
                sidePotGame.processPlayerDecision(a, new PlayerDecision(PlayerAction.CALL, 0, a.getPlayerId()));

                List<Card> aBest = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.KING, Suit.SPADES),
                                new Card(Rank.QUEEN, Suit.SPADES),
                                new Card(Rank.JACK, Suit.SPADES),
                                new Card(Rank.NINE, Suit.SPADES));
                List<Card> tiedSideBest = List.of(
                                new Card(Rank.ACE, Suit.HEARTS),
                                new Card(Rank.KING, Suit.HEARTS),
                                new Card(Rank.QUEEN, Suit.HEARTS),
                                new Card(Rank.JACK, Suit.HEARTS),
                                new Card(Rank.EIGHT, Suit.HEARTS));
                List<Card> lowerSideBest = List.of(
                                new Card(Rank.TEN, Suit.CLUBS),
                                new Card(Rank.TEN, Suit.HEARTS),
                                new Card(Rank.FIVE, Suit.CLUBS),
                                new Card(Rank.FOUR, Suit.DIAMONDS),
                                new Card(Rank.TWO, Suit.SPADES));

                when(mockHandEvaluator.getBestHand(any(), any()))
                                .thenReturn(new HandEvaluationResult(aBest, HandRank.FLUSH))
                                .thenReturn(new HandEvaluationResult(tiedSideBest, HandRank.STRAIGHT))
                                .thenReturn(new HandEvaluationResult(tiedSideBest, HandRank.STRAIGHT))
                                .thenReturn(new HandEvaluationResult(lowerSideBest, HandRank.ONE_PAIR));
                when(mockHandEvaluator.isBetterHandOfSameRank(any(), any(), any())).thenReturn(false);

                sidePotGame.conductShowdown();

                assertEquals(200, a.getChips());
                assertEquals(76, b.getChips());
                assertEquals(76, c.getChips());
                assertEquals(0, d.getChips());
                assertEquals(1, sidePotGame.getPot(),
                                "Odd side-pot split remainder should stay in the pot");
        }

        /**
         * Protects the expected behavior for get pot breakdown treats single player layer as uncalled.
         */
        @Test
        void testGetPotBreakdown_TreatsSinglePlayerLayerAsUncalled() {
                List<Player> playersWithShove = new ArrayList<>();
                playersWithShove.add(new Player("AllIn", "a1", 480));
                playersWithShove.add(new Player("SmallBlind", "s1", 1000));
                playersWithShove.add(new Player("BigBlind", "b1", 1000));

                Game shoveGame = new Game("uncalled-breakdown", playersWithShove, 10, 20, mockHandEvaluator);
                shoveGame.postBlinds();

                Player allInPlayer = shoveGame.getCurrentPlayer();
                shoveGame.processPlayerDecision(allInPlayer,
                                new PlayerDecision(PlayerAction.ALL_IN, 0, allInPlayer.getPlayerId()));

                assertEquals(List.of(30, 20), shoveGame.getPotBreakdown());
                assertEquals(460, shoveGame.getUncalledAmount());
        }

        /**
         * Protects the expected behavior for conduct showdown refunds uncalled single player layer.
         */
        @Test
        void testConductShowdown_RefundsUncalledSinglePlayerLayer() {
                List<Player> playersWithShove = new ArrayList<>();
                playersWithShove.add(new Player("AllIn", "a1", 480));
                playersWithShove.add(new Player("SmallBlind", "s1", 1000));
                playersWithShove.add(new Player("BigBlind", "b1", 1000));

                Game shoveGame = new Game("uncalled-refund", playersWithShove, 10, 20, mockHandEvaluator);
                shoveGame.postBlinds();

                Player allInPlayer = shoveGame.getCurrentPlayer();
                Player smallBlind = shoveGame.getActivePlayers().get(1);
                Player bigBlind = shoveGame.getActivePlayers().get(2);

                shoveGame.processPlayerDecision(allInPlayer,
                                new PlayerDecision(PlayerAction.ALL_IN, 0, allInPlayer.getPlayerId()));

                List<Card> allInBest = List.of(
                                new Card(Rank.TWO, Suit.SPADES),
                                new Card(Rank.THREE, Suit.HEARTS),
                                new Card(Rank.FOUR, Suit.DIAMONDS),
                                new Card(Rank.FIVE, Suit.CLUBS),
                                new Card(Rank.SEVEN, Suit.SPADES));
                List<Card> smallBlindBest = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.KING, Suit.SPADES),
                                new Card(Rank.QUEEN, Suit.SPADES),
                                new Card(Rank.JACK, Suit.SPADES),
                                new Card(Rank.TEN, Suit.SPADES));
                List<Card> bigBlindBest = List.of(
                                new Card(Rank.ACE, Suit.HEARTS),
                                new Card(Rank.ACE, Suit.DIAMONDS),
                                new Card(Rank.KING, Suit.CLUBS),
                                new Card(Rank.QUEEN, Suit.HEARTS),
                                new Card(Rank.JACK, Suit.HEARTS));

                when(mockHandEvaluator.getBestHand(any(), any()))
                                .thenReturn(new HandEvaluationResult(allInBest, HandRank.HIGH_CARD))
                                .thenReturn(new HandEvaluationResult(smallBlindBest, HandRank.ROYAL_FLUSH))
                                .thenReturn(new HandEvaluationResult(bigBlindBest, HandRank.ONE_PAIR));

                shoveGame.conductShowdown();

                assertEquals(460, allInPlayer.getChips(), "All-in player should get uncalled portion refunded");
                assertEquals(1020, smallBlind.getChips(), "Small blind should win contested main pot");
                assertEquals(1000, bigBlind.getChips(), "Big blind should win contested side pot");
                assertEquals(0, shoveGame.getPot());
        }

        /**
         * Protects the expected behavior for heads up post blinds dealer posts small blind.
         */
        @Test
        void testHeadsUpPostBlindsDealerPostsSmallBlind() {
                List<Player> headsUpPlayers = new ArrayList<>();
                headsUpPlayers.add(new Player("Dealer", "d1", 1000));
                headsUpPlayers.add(new Player("Opponent", "o1", 1000));

                Game headsUpGame = new Game("heads-up-sb", headsUpPlayers, 10, 20, mockHandEvaluator);
                headsUpGame.postBlinds();

                assertEquals(0, headsUpGame.getDealerPosition());
                assertEquals(10, headsUpGame.getActivePlayers().getFirst().getCurrentBet());
        }

        /**
         * Protects the expected behavior for heads up post blinds non dealer posts big blind.
         */
        @Test
        void testHeadsUpPostBlindsNonDealerPostsBigBlind() {
                List<Player> headsUpPlayers = new ArrayList<>();
                headsUpPlayers.add(new Player("Dealer", "d1", 1000));
                headsUpPlayers.add(new Player("Opponent", "o1", 1000));

                Game headsUpGame = new Game("heads-up-bb", headsUpPlayers, 10, 20, mockHandEvaluator);
                headsUpGame.postBlinds();

                assertEquals(20, headsUpGame.getActivePlayers().get(1).getCurrentBet());
        }

        /**
         * Protects the expected behavior for heads up advance positions blinds rotate correctly between hands edge case.
         */
        @Test
        void testHeadsUpAdvancePositionsBlindsRotateCorrectlyBetweenHandsEdgeCase() {
                List<Player> headsUpPlayers = new ArrayList<>();
                headsUpPlayers.add(new Player("Dealer", "d1", 1000));
                headsUpPlayers.add(new Player("Opponent", "o1", 1000));

                Game headsUpGame = new Game("heads-up-rotate", headsUpPlayers, 10, 20, mockHandEvaluator);

                headsUpGame.postBlinds();
                assertEquals(10, headsUpGame.getActivePlayers().get(0).getCurrentBet());
                assertEquals(20, headsUpGame.getActivePlayers().get(1).getCurrentBet());

                headsUpGame.resetForNewHand();
                headsUpGame.postBlinds();

                assertEquals(1, headsUpGame.getDealerPosition());
                assertEquals(10, headsUpGame.getActivePlayers().get(1).getCurrentBet());
                assertEquals(20, headsUpGame.getActivePlayers().get(0).getCurrentBet());
        }

        /**
         * Protects the expected behavior for advance positions.
         */
        @Test
        void testAdvancePositions() {
                int initialDealer = game.getDealerPosition();

                game.advancePositions();

                assertEquals((initialDealer + 1) % 3, game.getDealerPosition());
        }

        /**
         * Protects the expected behavior for next player.
         */
        @Test
        void testNextPlayer() {
                Player firstPlayer = game.getCurrentPlayer();
                game.nextPlayer();
                Player secondPlayer = game.getCurrentPlayer();

                assertNotEquals(firstPlayer, secondPlayer);
        }

        /**
         * Protects the contract that remove player from game when non current player leaves before current should preserve current player.
         */
        @Test
        void removePlayerFromGame_WhenNonCurrentPlayerLeavesBeforeCurrent_ShouldPreserveCurrentPlayer() {
                game.nextPlayer();
                game.nextPlayer();
                Player currentBeforeRemoval = game.getCurrentPlayer();

                Player playerToRemove = game.getActivePlayers().stream()
                                .filter(p -> !p.equals(currentBeforeRemoval))
                                .findFirst()
                                .orElseThrow();

                game.removePlayerFromGame(playerToRemove);

                assertEquals(currentBeforeRemoval, game.getCurrentPlayer());
                assertEquals(2, game.getActivePlayers().size());
        }

        /**
         * Protects the contract that get big blind player ID when non current player leaves and blind index stale should not throw.
         */
        @Test
        void getBigBlindPlayerId_WhenNonCurrentPlayerLeavesAndBlindIndexStale_ShouldNotThrow() {
                game.postBlinds();

                Player playerToRemove = game.getActivePlayers().get(1);
                assertNotEquals(playerToRemove, game.getCurrentPlayer());

                game.removePlayerFromGame(playerToRemove);

                String bigBlindPlayerId = assertDoesNotThrow(() -> game.getBigBlindPlayerId());

                assertNotNull(bigBlindPlayerId);
                assertTrue(game.getActivePlayers().stream().anyMatch(p -> p.getPlayerId().equals(bigBlindPlayerId)));
        }

        /**
         * Protects the expected behavior for reset for new hand clears hand state and advances dealer.
         */
        @Test
        void testResetForNewHandClearsHandStateAndAdvancesDealer() {
                game.dealHoleCards();
                game.postBlinds();
                game.dealFlop();

                Player playerOne = game.getPlayers().get(0);
                Player playerTwo = game.getPlayers().get(1);
                Player playerThree = game.getPlayers().get(2);

                playerOne.doAction(PlayerAction.FOLD, 0, game.getPot());
                playerTwo.setBestHand(List.of(new Card(Rank.ACE, Suit.SPADES)));
                playerTwo.setHandRank(HandRank.ONE_PAIR);
                playerThree.doAction(PlayerAction.BET, 100, 0);

                int dealerPositionBeforeReset = game.getDealerPosition();
                int potBeforeReset = game.getPot();
                List<Integer> chipCountsBeforeReset = game.getPlayers().stream()
                                .map(Player::getChips)
                                .toList();

                boolean gameEnded = game.resetForNewHand();

                assertFalse(gameEnded);
                assertEquals(0, game.getCommunityCards().size());
                assertEquals(potBeforeReset, game.getPot(),
                                "Hand reset should preserve any configured carry-over pot");
                assertEquals(0, game.getCurrentHighestBet());
                assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
                assertEquals(players.size(), game.getActivePlayers().size());
                assertEquals((dealerPositionBeforeReset + 1) % players.size(), game.getDealerPosition());
                assertEquals(chipCountsBeforeReset,
                                game.getPlayers().stream().map(Player::getChips).toList(),
                                "Hand reset should not change chip stacks");

                for (Player player : game.getPlayers()) {
                        assertEquals(0, player.getHoleCards().size());
                        assertEquals(0, player.getBestHand().size());
                        assertEquals(HandRank.NO_HAND, player.getHandRank());
                        assertEquals(0, player.getCurrentBet());
                        assertFalse(player.getHasFolded());
                        assertFalse(player.getIsAllIn());
                }
        }

        /**
         * Protects the expected behavior for cleanup after hand.
         */
        @Test
        void testCleanupAfterHand() {
                // Make a player lose all chips
                Player player = game.getActivePlayers().getFirst();
                player.doAction(PlayerAction.ALL_IN, 0, 0);

                assertEquals(3, game.getActivePlayers().size());
                assertFalse(game.isGameOver());

                game.cleanupAfterHand();

                assertTrue(player.getIsOut());
                assertEquals(2, game.getActivePlayers().size());
                assertFalse(game.isGameOver()); // Still 2 players
        }

        /**
         * Protects the expected behavior for cleanup after hand ends game.
         */
        @Test
        void testCleanupAfterHandEndsGame() {
                // Make all but one player lose all chips
                game.getActivePlayers().get(0).doAction(PlayerAction.ALL_IN, 0, 0);
                game.getActivePlayers().get(1).doAction(PlayerAction.ALL_IN, 0, 0);

                game.cleanupAfterHand();

                assertEquals(1, game.getActivePlayers().size());
                assertTrue(game.isGameOver());
        }

        /**
         * Protects the expected behavior for getters.
         */
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

        /**
         * Protects the expected behavior for reset for new hand with eliminated players.
         */
        @Test
        void testResetForNewHandWithEliminatedPlayers() {
                // Eliminate a player
                Player eliminated = game.getActivePlayers().getFirst();
                eliminated.doAction(PlayerAction.ALL_IN, 0, 0);
                eliminated.setIsOut();

                game.resetForNewHand();

                assertEquals(2, game.getActivePlayers().size());
                assertFalse(game.getActivePlayers().contains(eliminated));
        }

        @Test
        void testIsTournamentOverWhenOnePlayerHasChips() {
                assertFalse(game.isTournamentOver());

                // Set 2 players out of 3 to 0 chips
                game.getPlayers().get(1).doAction(PlayerAction.ALL_IN, 0, 0); // chips = 0
                game.getPlayers().get(2).doAction(PlayerAction.ALL_IN, 0, 0); // chips = 0

                assertTrue(game.isTournamentOver());
        }

        @Test
        void testAreAllReadyEligiblePlayersReadyExcludesZeroChipPlayers() {
                game.openReadyCountdown(System.currentTimeMillis() + 10_000);

                // Set player 1 to 0 chips
                game.getPlayers().get(1).doAction(PlayerAction.ALL_IN, 0, 0);

                // Only player 0 and player 2 have chips > 0
                game.getPlayers().get(0).setReadyForNextHand(true);
                assertFalse(game.areAllReadyEligiblePlayersReady());

                game.getPlayers().get(2).setReadyForNextHand(true);
                // Player 1 has 0 chips, so with player 0 and 2 ready, all eligible should be ready
                assertTrue(game.areAllReadyEligiblePlayersReady());
        }

        /**
         * Protects the expected behavior for process player decision allows raise with all in.
         */
        @Test
        void testProcessPlayerDecision_AllowsRaiseWithAllIn() {
                List<Player> mixedStacks = new ArrayList<>();
                mixedStacks.add(new Player("Deep1", "deep1", 1000));
                mixedStacks.add(new Player("Short", "short", 50));
                mixedStacks.add(new Player("Deep2", "deep2", 1000));

                Game sidePotGame = new Game("all-in-raise-game", mixedStacks, 5, 10, mockHandEvaluator);
                sidePotGame.resetForNewHand();
                sidePotGame.dealHoleCards();
                sidePotGame.postBlinds();

                Player allInPlayer = sidePotGame.getCurrentPlayer();
                assertEquals("Short", allInPlayer.getName());
                sidePotGame.processPlayerDecision(allInPlayer,
                                new PlayerDecision(PlayerAction.ALL_IN, 0, allInPlayer.getPlayerId()));

                Player raiser = mixedStacks.getFirst();
                int previousBet = raiser.getCurrentBet();
                TurnOutcome outcome = sidePotGame.processPlayerDecision(raiser,
                                new PlayerDecision(PlayerAction.RAISE, 100, raiser.getPlayerId()));

                assertNull(outcome.conversionMessage());
                assertTrue(raiser.getCurrentBet() > previousBet);
                assertEquals(raiser.getCurrentBet(), sidePotGame.getCurrentHighestBet());
        }

        /**
         * Protects the expected behavior for process player decision converts call to all in when call exceeds stack.
         */
        @Test
        void testProcessPlayerDecisionConvertsCallToAllInWhenCallExceedsStack() {
                List<Player> shortPlayers = new ArrayList<>();
                shortPlayers.add(new Player("P1", "p1", 1000));
                shortPlayers.add(new Player("Short", "p2", 5));
                shortPlayers.add(new Player("P3", "p3", 1000));

                Game shortStackGame = new Game("short-call-game", shortPlayers, 5, 10, mockHandEvaluator);
                shortStackGame.resetForNewHand();
                shortStackGame.dealHoleCards();
                shortStackGame.postBlinds();

                Player shortCaller = shortStackGame.getCurrentPlayer();
                TurnOutcome outcome = shortStackGame.processPlayerDecision(
                                shortCaller,
                                new PlayerDecision(PlayerAction.CALL, 0, shortCaller.getPlayerId()));

                assertNotNull(outcome.conversionMessage());
                assertTrue(outcome.conversionMessage().contains("converted to all-in"));
                assertEquals(0, shortCaller.getChips());
                assertTrue(shortCaller.getIsAllIn());
        }

        /**
         * Protects the expected behavior for dealing full hand progression.
         */
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

        @Test
        @DisplayName("should mark game as over")
        void testMarkGameOver() {
                assertFalse(game.isGameOver());
                game.markGameOver();
                assertTrue(game.isGameOver());
        }

        @Test
        @DisplayName("should resolve game winner by chip count and index tiebreak")
        void testResolveGameWinner() {
                // Setup: Player 1 (index 0) has 0 chips and is out
                // Player 2 (index 1) has 1000 chips
                // Player 3 (index 2) has 1000 chips
                players.get(0).setChips(0);
                players.get(0).setIsOut();
                players.get(1).setChips(1000);
                players.get(2).setChips(1000);

                Player winner = game.resolveGameWinner();

                // Should pick Player 2 (index 1) over Player 3 (index 2) due to lower index tiebreak
                assertNotNull(winner);
                assertEquals("Player2", winner.getName());

                // Now give Player 3 more chips
                players.get(2).setChips(2000);
                winner = game.resolveGameWinner();
                assertEquals("Player3", winner.getName());
        }

        @Test
        @DisplayName("conductShowdown is idempotent and does not duplicate chip payouts on second call")
        void conductShowdownIsIdempotent() {
                List<Player> testPlayers = new ArrayList<>();
                Player p1 = new Player("Pieter", "p1", 1000);
                Player p2 = new Player("BoshMan", "p2", 1000);
                testPlayers.add(p1);
                testPlayers.add(p2);

                Game testGame = new Game("double-showdown-game", testPlayers, 10, 20, mockHandEvaluator);
                testGame.dealHoleCards();
                testGame.postBlinds();

                testGame.processPlayerDecision(p1, new PlayerDecision(PlayerAction.ALL_IN, 0, p1.getPlayerId()));
                testGame.processPlayerDecision(p2, new PlayerDecision(PlayerAction.CALL, 0, p2.getPlayerId()));

                List<Card> p1Best = List.of(new Card(Rank.ACE, Suit.SPADES));
                List<Card> p2Best = List.of(new Card(Rank.KING, Suit.SPADES));
                when(mockHandEvaluator.getBestHand(any(), any()))
                                .thenReturn(new HandEvaluationResult(p1Best, HandRank.ONE_PAIR))
                                .thenReturn(new HandEvaluationResult(p2Best, HandRank.HIGH_CARD));

                List<Player> firstWinners = testGame.conductShowdown();
                assertEquals(1, firstWinners.size());
                assertEquals(2000, p1.getChips());
                assertEquals(0, p2.getChips());
                assertEquals(0, testGame.getPot());

                // Second showdown invocation must be idempotent
                testGame.conductShowdown();
                assertEquals(2000, p1.getChips(), "Chips should not increase on second showdown");
                assertEquals(0, p2.getChips());
                assertEquals(0, testGame.getPot());
        }

        @Test
        @DisplayName("chip total is strictly conserved across showdown and repeated showdowns")
        void chipTotalIsConservedAcrossShowdown() {
                List<Player> testPlayers = new ArrayList<>();
                Player p1 = new Player("P1", "p1", 1000);
                Player p2 = new Player("P2", "p2", 1000);
                Player p3 = new Player("P3", "p3", 1000);
                testPlayers.add(p1);
                testPlayers.add(p2);
                testPlayers.add(p3);

                int totalBuyIn = 3000;
                Game testGame = new Game("conservation-game", testPlayers, 10, 20, mockHandEvaluator);
                testGame.dealHoleCards();
                testGame.postBlinds();

                testGame.processPlayerDecision(p1, new PlayerDecision(PlayerAction.ALL_IN, 0, p1.getPlayerId()));
                testGame.processPlayerDecision(p2, new PlayerDecision(PlayerAction.ALL_IN, 0, p2.getPlayerId()));
                testGame.processPlayerDecision(p3, new PlayerDecision(PlayerAction.FOLD, 0, p3.getPlayerId()));

                List<Card> p1Best = List.of(new Card(Rank.ACE, Suit.SPADES));
                List<Card> p2Best = List.of(new Card(Rank.KING, Suit.SPADES));
                when(mockHandEvaluator.getBestHand(any(), any()))
                                .thenReturn(new HandEvaluationResult(p1Best, HandRank.ONE_PAIR))
                                .thenReturn(new HandEvaluationResult(p2Best, HandRank.HIGH_CARD));

                testGame.conductShowdown();

                int sumChipsAfterFirst = testPlayers.stream().mapToInt(Player::getChips).sum() + testGame.getPot();
                assertEquals(totalBuyIn, sumChipsAfterFirst);

                testGame.conductShowdown();

                int sumChipsAfterSecond = testPlayers.stream().mapToInt(Player::getChips).sum() + testGame.getPot();
                assertEquals(totalBuyIn, sumChipsAfterSecond);
        }
}
