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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Integration scenarios exercising Game and its collaborators. */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GameShowdownIntegrationTest extends GameIntegrationTestSupport {

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

                assertTrue(winners.isEmpty(), "A refund-only recipient is not a contested-pot winner");
                assertEquals(1000, game.getPlayers().get(2).getChips());
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
                game.processPlayerDecision(game.getActivePlayers().get(1),
                                new PlayerDecision(PlayerAction.CALL, 100, "p2"));
                game.processPlayerDecision(game.getActivePlayers().get(2),
                                new PlayerDecision(PlayerAction.CALL, 100, "p3"));

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

                headsUpGame.postBlinds();

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

                game.processPlayerDecision(game.getActivePlayers().get(0), new PlayerDecision(PlayerAction.BET, 51, "p1"));
                game.processPlayerDecision(game.getActivePlayers().get(1), new PlayerDecision(PlayerAction.CALL, 51, "p2"));
                game.processPlayerDecision(game.getActivePlayers().get(2), new PlayerDecision(PlayerAction.CALL, 51, "p3"));
                game.getActivePlayers().get(2).doAction(PlayerAction.FOLD, 0, game.getPot());

                game.conductShowdown();

                assertEquals(1, game.getPot());
                assertEquals(List.of(1), game.getPotBreakdown());
                assertEquals(0, game.getUncalledAmount());
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

                game.processPlayerDecision(game.getActivePlayers().get(0), new PlayerDecision(PlayerAction.BET, 51, "p1"));
                game.processPlayerDecision(game.getActivePlayers().get(1), new PlayerDecision(PlayerAction.CALL, 51, "p2"));
                game.processPlayerDecision(game.getActivePlayers().get(2), new PlayerDecision(PlayerAction.CALL, 51, "p3"));
                game.getActivePlayers().get(2).doAction(PlayerAction.FOLD, 0, game.getPot());
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

                game.processPlayerDecision(game.getActivePlayers().get(0), new PlayerDecision(PlayerAction.BET, 51, "p1"));
                game.processPlayerDecision(game.getActivePlayers().get(1), new PlayerDecision(PlayerAction.CALL, 51, "p2"));
                game.processPlayerDecision(game.getActivePlayers().get(2), new PlayerDecision(PlayerAction.CALL, 51, "p3"));
                game.getActivePlayers().get(2).doAction(PlayerAction.FOLD, 0, game.getPot());
                game.conductShowdown();
                assertEquals(1, game.getPot());

                game.resetForNewHand();
                game.processPlayerDecision(game.getActivePlayers().get(0), new PlayerDecision(PlayerAction.BET, 50, "p1"));
                game.processPlayerDecision(game.getActivePlayers().get(1), new PlayerDecision(PlayerAction.CALL, 50, "p2"));
                game.getActivePlayers().get(2).doAction(PlayerAction.FOLD, 0, game.getPot());
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

        @Test
        void deadUpperLayersJoinNearestEligibleLowerAllocation() {
                Player a = Player.restoreBuilder().name("A").playerId("a").chips(0).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(0).hasFolded(false)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Player b = Player.restoreBuilder().name("B").playerId("b").chips(0).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(0).hasFolded(false)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Player c = Player.restoreBuilder().name("C").playerId("c").chips(0).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(0).hasFolded(true)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Player d = Player.restoreBuilder().name("D").playerId("d").chips(0).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(0).hasFolded(true)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Game game = Game.restoreBuilder().gameId("dead-upper-layer").players(List.of(a, b, c, d))
                                .activePlayerIds(List.of("a", "b", "c", "d")).remainingDeck(List.of())
                                .communityCards(List.of()).pot(550).dealerPosition(0).smallBlindPosition(1)
                                .bigBlindPosition(2).currentPlayerPosition(0).currentHighestBet(200)
                                .currentPhase(GamePhase.RIVER).gameOver(false).smallBlind(10).bigBlind(20)
                                .handContributions(Map.of("a", 50, "b", 100, "c", 200, "d", 200))
                                .preHandCarryOver(0).readyCountdownActive(false).everyoneHasHadInitialTurn(true)
                                .actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(mockHandEvaluator).build();
                List<Card> high = List.of(new Card(Rank.ACE, Suit.SPADES));
                List<Card> low = List.of(new Card(Rank.KING, Suit.SPADES));
                when(mockHandEvaluator.getBestHand(any(), any()))
                                .thenReturn(new HandEvaluationResult(high, HandRank.ROYAL_FLUSH))
                                .thenReturn(new HandEvaluationResult(low, HandRank.ONE_PAIR));

                assertEquals(List.of(a, b), game.conductShowdown());
                assertEquals(200, a.getChips());
                assertEquals(350, b.getChips());
                assertEquals(0, game.getPot());
                assertEquals(Map.of("a", 200, "b", 350), game.getLastSettlementResult().contestedAwards());
                assertTrue(game.getLastSettlementResult().refunds().isEmpty());
        }

        @Test
        void departureCapsUncallableTopWagerToRetainedContenderAndRunsOut() {
                Player shortStack = restoredPlayer("Short", "s", 0, 50, true);
                Player retained = restoredPlayer("A", "a", 500, 100, false);
                Player departing = restoredPlayer("B", "b", 300, 200, false);
                Game game = restoredBettingGame("departure-uncallable-top", List.of(shortStack, retained, departing),
                                350, 200, 1, Map.of("s", 50, "a", 100, "b", 200), Map.of("PRE_FLOP", 100), Map.of());

                game.removePlayerFromGame(departing);
                TurnOutcome outcome = game.reconcileAfterPlayerRemoval();

                assertEquals(100, game.getCurrentHighestBet());
                assertEquals(0, game.getCallDifference(retained));
                assertEquals(TurnOutcome.TurnOutcomeType.AUTO_ADVANCING, outcome.type());
                assertEquals(Map.of("s", 50, "a", 100, "b", 200), game.getHandContributionsSnapshot());
        }

        @Test
        void departureCapsGenuineAllInCallToRetainedAllInWager() {
                Player shortStack = restoredPlayer("Short", "s", 0, 200, true);
                Player retained = restoredPlayer("A", "a", 100, 100, false);
                Player departing = restoredPlayer("B", "b", 700, 300, false);
                Game game = restoredBettingGame("departure-genuine-call", List.of(shortStack, retained, departing),
                                600, 300, 1, Map.of("s", 200, "a", 100, "b", 300), Map.of("PRE_FLOP", 100), Map.of());

                game.removePlayerFromGame(departing);
                TurnOutcome outcome = game.reconcileAfterPlayerRemoval();

                assertEquals(200, game.getCurrentHighestBet());
                assertEquals(100, game.getCallDifference(retained));
                assertEquals(TurnOutcome.TurnOutcomeType.NEXT_PLAYER, outcome.type());
                assertEquals(List.of(PlayerAction.FOLD, PlayerAction.CALL, PlayerAction.ALL_IN), game.getLegalActionsFor(retained));
                game.processPlayerDecision(retained, new PlayerDecision(PlayerAction.ALL_IN, 0, retained.getPlayerId()));
                assertEquals(200, retained.getCurrentBet());
                assertEquals(Map.of("s", 200, "a", 200, "b", 300), game.getHandContributionsSnapshot());
        }

        @Test
        void aggressionIsUnavailableWhenNoOpponentCanActButCallAllInRemainsLegal() {
                Player actor = restoredPlayer("Actor", "a", 500, 0, false);
                Player allIn = restoredPlayer("All-in", "b", 0, 100, true);
                Game game = restoredBettingGame("uncallable-aggression", List.of(actor, allIn), 100, 100,
                                0, Map.of("b", 100), Map.of("PRE_FLOP", 100), Map.of());

                assertEquals(List.of(PlayerAction.FOLD, PlayerAction.CALL), game.getLegalActionsFor(actor));
                assertThrows(UnauthorisedActionException.class,
                                () -> game.processPlayerDecision(actor, new PlayerDecision(PlayerAction.RAISE, 100, "a")));
                assertThrows(UnauthorisedActionException.class,
                                () -> game.processPlayerDecision(actor, new PlayerDecision(PlayerAction.ALL_IN, 0, "a")));
                actor.setChips(100);
                assertTrue(game.getLegalActionsFor(actor).contains(PlayerAction.ALL_IN));
                game.processPlayerDecision(actor, new PlayerDecision(PlayerAction.ALL_IN, 0, "a"));
                assertEquals(100, actor.getCurrentBet());
        }

        @Test
        void departedContributionStillDefinesSidePotAndCannotReceiveRefund() {
                Player shortPlayer = new Player("Short", "s", 0);
                Player midPlayer = new Player("Mid", "m", 150);
                Game game = Game.restoreBuilder().gameId("departed-contributor").players(List.of(shortPlayer, midPlayer))
                                .activePlayerIds(List.of("s", "m")).remainingDeck(List.of()).communityCards(List.of())
                                .pot(350).dealerPosition(0).smallBlindPosition(0).bigBlindPosition(1)
                                .currentPlayerPosition(0).currentHighestBet(150).currentPhase(GamePhase.RIVER)
                                .gameOver(false).smallBlind(10).bigBlind(20)
                                .handContributions(Map.of("s", 50, "m", 150, "departed", 150))
                                .preHandCarryOver(0).readyCountdownActive(false).everyoneHasHadInitialTurn(true)
                                .actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(mockHandEvaluator).build();
                List<Card> high = List.of(new Card(Rank.ACE, Suit.SPADES));
                List<Card> low = List.of(new Card(Rank.KING, Suit.SPADES));
                when(mockHandEvaluator.getBestHand(any(), any()))
                                .thenReturn(new HandEvaluationResult(high, HandRank.ROYAL_FLUSH))
                                .thenReturn(new HandEvaluationResult(low, HandRank.ONE_PAIR));

                game.conductShowdown();

                assertEquals(150, shortPlayer.getChips());
                assertEquals(350, midPlayer.getChips());
                assertEquals(0, game.getPot());
                assertEquals(Map.of("s", 150, "m", 200), game.getLastSettlementResult().contestedAwards());
                assertTrue(game.getLastSettlementResult().refunds().isEmpty());
        }

        @Test
        void deadBlindLayersPayRetainedZeroContributionContender() {
                Player contender = new Player("Contender", "a", 100);
                Player foldedSmallBlind = Player.restoreBuilder().name("Folded SB").playerId("b").chips(90)
                                .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(10)
                                .hasFolded(true).allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Player foldedBigBlind = Player.restoreBuilder().name("Folded BB").playerId("c").chips(80)
                                .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(20)
                                .hasFolded(true).allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Game game = Game.restoreBuilder().gameId("zero-contribution-contender")
                                .players(List.of(contender, foldedSmallBlind, foldedBigBlind))
                                .activePlayerIds(List.of("a", "b", "c")).remainingDeck(List.of()).communityCards(List.of())
                                .pot(30).dealerPosition(0).smallBlindPosition(1).bigBlindPosition(2)
                                .currentPlayerPosition(0).currentHighestBet(20).currentPhase(GamePhase.PRE_FLOP)
                                .gameOver(false).smallBlind(10).bigBlind(20)
                                .handContributions(Map.of("b", 10, "c", 20)).preHandCarryOver(0)
                                .readyCountdownActive(false).everyoneHasHadInitialTurn(true)
                                .actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(mockHandEvaluator).build();

                game.conductShowdown();

                assertEquals(130, contender.getChips());
                assertEquals(0, game.getPot());
                assertEquals(Map.of("a", 30), game.getLastSettlementResult().contestedAwards());
        }

        @Test
        void realEvaluatorAwardsAceHighInPublicGameShowdown() {
                List<Card> board = List.of(new Card(Rank.TWO, Suit.SPADES), new Card(Rank.FOUR, Suit.HEARTS),
                                new Card(Rank.SEVEN, Suit.DIAMONDS), new Card(Rank.NINE, Suit.CLUBS),
                                new Card(Rank.JACK, Suit.SPADES));
                Player ace = Player.restoreBuilder().name("Ace").playerId("ace").chips(950)
                                .holeCards(List.of(new Card(Rank.ACE, Suit.HEARTS), new Card(Rank.THREE, Suit.DIAMONDS)))
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(0).hasFolded(false)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Player king = Player.restoreBuilder().name("King").playerId("king").chips(950)
                                .holeCards(List.of(new Card(Rank.KING, Suit.HEARTS), new Card(Rank.EIGHT, Suit.DIAMONDS)))
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(0).hasFolded(false)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Game game = Game.restoreBuilder().gameId("real-high-card-showdown").players(List.of(ace, king))
                                .activePlayerIds(List.of("ace", "king")).remainingDeck(List.of()).communityCards(board)
                                .pot(100).dealerPosition(0).smallBlindPosition(0).bigBlindPosition(1)
                                .currentPlayerPosition(0).currentHighestBet(50).currentPhase(GamePhase.RIVER)
                                .gameOver(false).smallBlind(10).bigBlind(20)
                                .handContributions(Map.of("ace", 50, "king", 50)).preHandCarryOver(0)
                                .readyCountdownActive(false).everyoneHasHadInitialTurn(true)
                                .actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(new HandEvaluatorService()).build();

                assertEquals(List.of(ace), game.conductShowdown());
                assertEquals(1050, ace.getChips());
                assertEquals(950, king.getChips());
                assertEquals(Map.of("ace", 100), game.getLastSettlementResult().contestedAwards());
        }

        @Test
        void finalizedCarryOverRemainderIsProjectedWithoutReopeningLedger() {
                List<Card> evaluated = List.of(new Card(Rank.ACE, Suit.SPADES));
                when(mockHandEvaluator.getBestHand(any(), any()))
                                .thenReturn(new HandEvaluationResult(evaluated, HandRank.HIGH_CARD));
                Player a = Player.restoreBuilder().name("A").playerId("a").chips(50).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(0).hasFolded(false)
                                .allIn(true).out(false).disconnected(false).readyForNextHand(false).build();
                Player b = Player.restoreBuilder().name("B").playerId("b").chips(50).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(0).hasFolded(false)
                                .allIn(true).out(false).disconnected(false).readyForNextHand(false).build();
                Game game = Game.restoreBuilder().gameId("carry-over-remainder").players(List.of(a, b))
                                .activePlayerIds(List.of("a", "b")).remainingDeck(List.of()).communityCards(List.of())
                                .pot(101).dealerPosition(0).smallBlindPosition(0).bigBlindPosition(1)
                                .currentPlayerPosition(0).currentHighestBet(50).currentPhase(GamePhase.RIVER)
                                .gameOver(false).smallBlind(10).bigBlind(20).handContributions(Map.of("a", 50, "b", 50))
                                .preHandCarryOver(1).readyCountdownActive(false).everyoneHasHadInitialTurn(true)
                                .actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(mockHandEvaluator).build();

                game.conductShowdown();

                assertEquals(1, game.getPot());
                assertEquals(List.of(1), game.getPotBreakdown());
                assertEquals(0, game.getUncalledAmount());
                assertEquals(1, game.getLastSettlementResult().remainder());
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

                assertEquals(List.of(510), shoveGame.getPotBreakdown());
                assertEquals(0, shoveGame.getUncalledAmount());
        }

        @Test
        void livePotKeepsEstablishedAllInCapAndPendingBetVisible() {
                Player shortStack = Player.restoreBuilder().name("Short").playerId("short").chips(0).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(50).hasFolded(false)
                                .allIn(true).out(false).disconnected(false).readyForNextHand(false).build();
                Player bettor = Player.restoreBuilder().name("Bettor").playerId("bettor").chips(800).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(250).hasFolded(false)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Player owing = Player.restoreBuilder().name("Owing").playerId("owing").chips(950).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(50).hasFolded(false)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Game live = Game.restoreBuilder().gameId("live-cap-pending-bet").players(List.of(shortStack, bettor, owing))
                                .activePlayerIds(List.of("short", "bettor", "owing")).remainingDeck(List.of())
                                .communityCards(List.of()).pot(350).dealerPosition(0).smallBlindPosition(0).bigBlindPosition(1)
                                .currentPlayerPosition(2).currentHighestBet(250).currentPhase(GamePhase.FLOP)
                                .gameOver(false).smallBlind(10).bigBlind(20)
                                .handContributions(Map.of("short", 50, "bettor", 250, "owing", 50))
                                .preHandCarryOver(0).readyCountdownActive(false).everyoneHasHadInitialTurn(true)
                                .actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(mockHandEvaluator).build();

                assertEquals(List.of(150, 200), live.getPotBreakdown());
                assertEquals(0, live.getUncalledAmount());
        }

        @Test
        void liveCapSlicesIncludeFoldedChipsBelowCapAndPreHandCarryOver() {
                Game live = livePotWithFoldedBlindAndCarryOver(370, 0);
                assertEquals(List.of(170, 200), live.getPotBreakdown());
                assertEquals(0, live.getUncalledAmount());

                Game carried = livePotWithFoldedBlindAndCarryOver(380, 10);
                assertEquals(List.of(180, 200), carried.getPotBreakdown(),
                                "Pre-hand carry-over belongs in the main live allocation");
                assertEquals(0, carried.getUncalledAmount());
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
                var settledResult = testGame.getLastSettlementResult();
                assertEquals(Set.of("p1"), settledResult.winnerIds());
                assertEquals(2000, p1.getChips());
                assertEquals(0, p2.getChips());
                assertEquals(0, testGame.getPot());

                // Second showdown invocation must be idempotent
                List<Player> repeatedWinners = testGame.conductShowdown();
                assertEquals(firstWinners.stream().map(Player::getPlayerId).toList(),
                                repeatedWinners.stream().map(Player::getPlayerId).toList());
                assertEquals(settledResult, testGame.getLastSettlementResult());
                assertEquals(2000, p1.getChips(), "Chips should not increase on second showdown");
                assertEquals(0, p2.getChips());
                assertEquals(0, testGame.getPot());
        }

        @Test
        void zeroContenderVoidRetainsCarryOverAcrossRetryAndNextHand() {
                List<Player> players = new ArrayList<>(List.of(
                                new Player("A", "a", 100), new Player("B", "b", 100)));
                Game voidGame = new Game("void-hand", players, 10, 20, mockHandEvaluator);
                voidGame.processPlayerDecision(players.get(0), new PlayerDecision(PlayerAction.BET, 50, "a"));
                voidGame.processPlayerDecision(players.get(1), new PlayerDecision(PlayerAction.CALL, 50, "b"));
                players.forEach(player -> player.doAction(PlayerAction.FOLD, 0, voidGame.getPot()));

                assertTrue(voidGame.conductShowdown().isEmpty());
                var result = voidGame.getLastSettlementResult();
                assertTrue(result.settled());
                assertTrue(result.winnerIds().isEmpty());
                assertEquals(100, result.remainder());
                assertEquals(100, voidGame.getPot());
                assertTrue(voidGame.conductShowdown().isEmpty());
                assertEquals(result, voidGame.getLastSettlementResult());
                assertEquals(100, voidGame.getPot());

                assertFalse(voidGame.resetForNewHand());
                assertEquals(100, voidGame.getPot());
                assertEquals(100, voidGame.getPreHandCarryOver());
        }

        @Test
        void terminalResetDoesNotReclassifyUnsettledContributionsAsCarryOver() {
                Player a = Player.restoreBuilder().name("A").playerId("a").chips(50).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(50).hasFolded(false)
                                .allIn(true).out(false).disconnected(false).readyForNextHand(false).build();
                Game unsettled = Game.restoreBuilder().gameId("terminal-reset-ledger").players(List.of(a))
                                .activePlayerIds(List.of("a")).remainingDeck(List.of()).communityCards(List.of())
                                .pot(50).dealerPosition(0).smallBlindPosition(0).bigBlindPosition(0)
                                .currentPlayerPosition(0).currentHighestBet(50).currentPhase(GamePhase.RIVER)
                                .gameOver(true).smallBlind(10).bigBlind(20).handContributions(Map.of("a", 50))
                                .preHandCarryOver(0).readyCountdownActive(false).everyoneHasHadInitialTurn(true)
                                .actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(mockHandEvaluator).build();

                assertTrue(unsettled.resetForNewHand());
                assertEquals(0, unsettled.getPreHandCarryOver());
                assertEquals(Map.of("a", 50), unsettled.getHandContributionsSnapshot());
                assertEquals(50, unsettled.getPot());
        }

        @Test
        void terminalSettlementAwardsFinalBlindPotAs1010ExactlyOnce() {
                List<Player> players = new ArrayList<>(List.of(
                                new Player("Small", "small", 1000), new Player("Big", "big", 1000)));
                Game terminalGame = new Game("terminal-blind-pot", players, 10, 20, new HandEvaluatorService());
                terminalGame.postBlinds();
                Player small = players.get(0);
                Player big = players.get(1);
                terminalGame.removePlayerFromGame(small);

                Player winner = terminalGame.resolveGameWinner();
                terminalGame.completeGameSettlement(winner);

                assertSame(big, winner);
                assertEquals(1010, big.getChips());
                assertEquals(0, terminalGame.getPot());
                assertTrue(terminalGame.isTerminalCarryOverSettled());
                terminalGame.completeGameSettlement(winner);
                assertEquals(1010, big.getChips());
                assertEquals(0, terminalGame.getPot());
        }

        @Test
        void terminalSettlementPaysOddCarryOverToResolvedWinnerOnce() {
                Player a = new Player("A", "a", 100);
                Player b = new Player("B", "b", 100);
                Game terminalGame = Game.restoreBuilder().gameId("terminal-odd-carry").players(List.of(a, b))
                                .activePlayerIds(List.of("a", "b")).remainingDeck(List.of()).communityCards(List.of())
                                .pot(1).dealerPosition(0).smallBlindPosition(0).bigBlindPosition(1)
                                .currentPlayerPosition(0).currentHighestBet(0).currentPhase(GamePhase.SHOWDOWN)
                                .gameOver(false).smallBlind(10).bigBlind(20).handContributions(Map.of())
                                .preHandCarryOver(1).lastSettlementResult(new HandSettlementResult(
                                                Map.of(), Map.of(), Set.of(), 1, true))
                                .readyCountdownActive(false).everyoneHasHadInitialTurn(true)
                                .actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(mockHandEvaluator).build();
                Player winner = terminalGame.resolveGameWinner();

                terminalGame.completeGameSettlement(winner);
                terminalGame.completeGameSettlement(winner);

                assertEquals(101, winner.getChips());
                assertEquals(0, terminalGame.getPot());
                assertEquals(1, terminalGame.getLastSettlementResult().remainder(),
                                "Hand payout metadata remains distinct from the terminal game payout");
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
