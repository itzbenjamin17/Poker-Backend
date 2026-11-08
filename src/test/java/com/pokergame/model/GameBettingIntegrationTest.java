package com.pokergame.model;

import com.pokergame.dto.internal.PlayerDecision;
import com.pokergame.dto.internal.TurnOutcome;
import com.pokergame.enums.*;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.UnauthorisedActionException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class GameBettingIntegrationTest extends GameIntegrationTestSupport {

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
        void nonIncreasingRaiseReportsFullIncrementalMinimum() {
                game.postBlinds();
                Player player = game.getActivePlayers().getFirst();
                UnauthorisedActionException exception = assertThrows(UnauthorisedActionException.class,
                                () -> game.processPlayerDecision(player, new PlayerDecision(PlayerAction.RAISE, 1, player.getPlayerId())));
                assertEquals("MIN_RAISE", exception.actionErrorCode());
                assertEquals("Min raise: 40; yours: 1", exception.actionErrorMessage());
        }

        @Test
        void fullRaiseIncrementSetsNextMinimumAtTenToFiftyToNinety() {
                Player a = restoredPlayer("A", "a", 900, 10, false);
                Player b = restoredPlayer("B", "b", 900, 10, false);
                Player c = restoredPlayer("C", "c", 900, 10, false);
                Game hand = restoredBettingGame("raise-increment", List.of(a, b, c), 30, 10, 0,
                                Map.of("a", 10, "b", 10, "c", 10), Map.of(), Map.of());

                hand.processPlayerDecision(a, new PlayerDecision(PlayerAction.RAISE, 40, "a"));
                assertEquals(50, a.getCurrentBet(), "A raises the existing 10 wager to 50");
                assertEquals(40, hand.getLastFullRaise());
                assertEquals(80, hand.getMinimumRaiseAmount(b));

                int bChipsBefore = b.getChips();
                UnauthorisedActionException tooSmall = assertThrows(UnauthorisedActionException.class,
                                () -> hand.processPlayerDecision(b, new PlayerDecision(PlayerAction.RAISE, 10, "b")));
                assertEquals("MIN_RAISE", tooSmall.actionErrorCode());
                assertEquals(bChipsBefore, b.getChips());
                assertEquals(50, hand.getCurrentHighestBet());

                hand.processPlayerDecision(b, new PlayerDecision(PlayerAction.RAISE, 80, "b"));
                assertEquals(90, b.getCurrentBet(), "The next full raise reaches 90");
                assertEquals(90, hand.getCurrentHighestBet());
        }

        @Test
        void shortAllInDoesNotReopenAlreadyActedPlayerButCumulativeShortRaisesDo() {
                Player a = restoredPlayer("A", "a", 900, 100, false);
                Player b = restoredPlayer("B", "b", 900, 100, false);
                Player shortOne = restoredPlayer("Short1", "s1", 0, 150, true);
                Player shortTwo = restoredPlayer("Short2", "s2", 0, 200, true);
                Game hand = restoredBettingGame("short-raise-reopening", List.of(a, b, shortOne, shortTwo), 550, 200, 0,
                                Map.of("a", 100, "b", 100, "s1", 150, "s2", 200), Map.of("PRE_FLOP", 100), Map.of("a", 100));

                assertTrue(hand.isRaiseOpenTo(a), "Two short all-ins cumulatively move the wager by the full raise size");
                assertTrue(hand.getLegalActionsFor(a).contains(PlayerAction.RAISE));
                assertTrue(hand.getLegalActionsFor(a).contains(PlayerAction.ALL_IN));
        }

        @Test
        void oneShortAllInLeavesRaiseClosedButAllowsCallOrCallingAllIn() {
                Player a = restoredPlayer("A", "a", 900, 100, false);
                Player shortOne = restoredPlayer("Short", "s", 0, 150, true);
                Player b = restoredPlayer("B", "b", 900, 100, false);
                Game hand = restoredBettingGame("short-raise-closed", List.of(a, shortOne, b), 350, 150, 0,
                                Map.of("a", 100, "s", 150, "b", 100), Map.of("PRE_FLOP", 100), Map.of("a", 100));

                assertFalse(hand.isRaiseOpenTo(a));
                assertFalse(hand.getLegalActionsFor(a).contains(PlayerAction.RAISE));
                assertFalse(hand.getLegalActionsFor(a).contains(PlayerAction.ALL_IN));
                assertTrue(hand.getLegalActionsFor(a).contains(PlayerAction.CALL));

                UnauthorisedActionException closed = assertThrows(UnauthorisedActionException.class,
                                () -> hand.processPlayerDecision(a, new PlayerDecision(PlayerAction.ALL_IN, 0, "a")));
                assertEquals("RAISE_NOT_REOPENED", closed.actionErrorCode());
                assertEquals(100, a.getCurrentBet(), "A rejected aggressive all-in does not consume chips");
                assertEquals(900, a.getChips());

                a.setChips(50);
                assertTrue(hand.getLegalActionsFor(a).contains(PlayerAction.ALL_IN), "A stack-limited call remains legal");
                hand.processPlayerDecision(a, new PlayerDecision(PlayerAction.ALL_IN, 0, "a"));
                assertEquals(150, a.getCurrentBet(), "Short all-in matches the wager without aggression");
                assertTrue(a.getIsAllIn());
        }

        @Test
        void legalActionsOmitRaiseWhenOnlyCallFitsButAllowShortWholeStackRaise() {
                Player exactCaller = restoredPlayer("Exact", "exact", 80, 20, false);
                Player wagerLeader = restoredPlayer("Leader", "leader", 900, 100, false);
                Player other = restoredPlayer("Other", "other", 900, 100, false);
                Game exactCall = restoredBettingGame("exact-call-actions", List.of(exactCaller, wagerLeader, other), 220, 100, 0,
                                Map.of("exact", 20, "leader", 100, "other", 100), Map.of("PRE_FLOP", 100), Map.of());

                List<PlayerAction> exactActions = exactCall.getLegalActionsFor(exactCaller);
                assertTrue(exactActions.contains(PlayerAction.CALL));
                assertTrue(exactActions.contains(PlayerAction.ALL_IN));
                assertFalse(exactActions.contains(PlayerAction.RAISE), "An exact-call stack has no aggressive wager available");
                exactCall.processPlayerDecision(exactCaller, new PlayerDecision(PlayerAction.ALL_IN, 0, "exact"));
                assertEquals(100, exactCaller.getCurrentBet());

                Player shortRaiser = restoredPlayer("ShortRaise", "shortRaise", 90, 20, false);
                Player leader2 = restoredPlayer("Leader2", "leader2", 900, 100, false);
                Player other2 = restoredPlayer("Other2", "other2", 900, 100, false);
                Game shortRaise = restoredBettingGame("short-all-in-raise-actions", List.of(shortRaiser, leader2, other2), 220, 100, 0,
                                Map.of("shortRaise", 20, "leader2", 100, "other2", 100), Map.of("PRE_FLOP", 100), Map.of());

                assertTrue(shortRaise.getLegalActionsFor(shortRaiser).contains(PlayerAction.RAISE));
                assertTrue(shortRaise.getLegalActionsFor(shortRaiser).contains(PlayerAction.ALL_IN));
                shortRaise.processPlayerDecision(shortRaiser, new PlayerDecision(PlayerAction.ALL_IN, 0, "shortRaise"));
                assertEquals(110, shortRaiser.getCurrentBet(), "A whole-stack raise may be below the full increment");
                assertTrue(shortRaiser.getIsAllIn());
        }

        @Test
        void callWithoutOwedChipsAndRaiseWithoutWagerRejectBeforeMutation() {
                Player actor = game.getCurrentPlayer();
                int startingChips = actor.getChips();
                assertThrows(UnauthorisedActionException.class, () -> game.processPlayerDecision(actor,
                                new PlayerDecision(PlayerAction.CALL, 0, actor.getPlayerId())));
                assertThrows(UnauthorisedActionException.class, () -> game.processPlayerDecision(actor,
                                new PlayerDecision(PlayerAction.RAISE, 10, actor.getPlayerId())));
                assertEquals(startingChips, actor.getChips());
                assertEquals(0, game.getPot());
                assertEquals(0, game.getCurrentHighestBet());
        }

        @Test
        void forcedAllInRunoutDistinguishesMatchedStateFromGenuineOwedCall() {
                Player allIn = Player.restoreBuilder().name("AllIn").playerId("ai").chips(0).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(10).hasFolded(false)
                                .allIn(true).out(false).disconnected(false).readyForNextHand(false).build();
                Player matched = Player.restoreBuilder().name("Matched").playerId("matched").chips(100).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(10).hasFolded(false)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Game matchedGame = Game.restoreBuilder().gameId("runout-matched").players(List.of(allIn, matched))
                                .activePlayerIds(List.of("ai", "matched")).remainingDeck(List.of()).communityCards(List.of())
                                .pot(20).dealerPosition(0).smallBlindPosition(0).bigBlindPosition(1).currentPlayerPosition(1)
                                .currentHighestBet(10).currentPhase(GamePhase.PRE_FLOP).gameOver(false).smallBlind(5).bigBlind(10)
                                .handContributions(Map.of("ai", 10, "matched", 10)).preHandCarryOver(0)
                                .readyCountdownActive(false).everyoneHasHadInitialTurn(false).actedPlayersInRound(Set.of())
                                .scheduledTaskDeadlines(Map.of()).handEvaluator(mockHandEvaluator).build();
                assertTrue(matchedGame.shouldAutoRunout(), "No active contender owes chips, so forced runout starts immediately");

                Player owing = Player.restoreBuilder().name("Owing").playerId("owing").chips(100).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(0).hasFolded(false)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Game owingGame = Game.restoreBuilder().gameId("runout-owed-call").players(List.of(allIn, owing))
                                .activePlayerIds(List.of("ai", "owing")).remainingDeck(List.of()).communityCards(List.of())
                                .pot(10).dealerPosition(0).smallBlindPosition(0).bigBlindPosition(1).currentPlayerPosition(1)
                                .currentHighestBet(10).currentPhase(GamePhase.PRE_FLOP).gameOver(false).smallBlind(5).bigBlind(10)
                                .handContributions(Map.of("ai", 10)).preHandCarryOver(0).readyCountdownActive(false)
                                .everyoneHasHadInitialTurn(false).actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(mockHandEvaluator).build();
                assertFalse(owingGame.shouldAutoRunout(), "A genuine call remains available even when only one player can act");
                assertFalse(owingGame.isBettingRoundComplete(), "An owed call keeps the street open before the actor responds");
        }

        @Test
        void departedBlindIdentityStaysAnchoredAndNextDealerWalksOriginalSeatOrder() {
                Player dealer = new Player("D", "d", 100);
                Player smallBlind = new Player("A", "a", 100);
                Player bigBlind = new Player("B", "b", 100);
                Player c = new Player("C", "c", 100);
                Game anchored = Game.restoreBuilder().gameId("position-anchor")
                                .players(List.of(dealer, smallBlind, bigBlind, c)).activePlayerIds(List.of("d", "a", "b", "c"))
                                .remainingDeck(List.of()).communityCards(List.of()).pot(0).dealerPosition(0)
                                .smallBlindPosition(1).bigBlindPosition(2).currentPlayerPosition(3).currentHighestBet(0)
                                .currentPhase(GamePhase.FLOP).gameOver(false).smallBlind(5).bigBlind(10)
                                .handContributions(Map.of()).preHandCarryOver(0).handSeatOrder(List.of("d", "a", "b", "c"))
                                .dealerAnchorPlayerId("d").smallBlindPlayerId("a").bigBlindPlayerId("b")
                                .readyCountdownActive(false).everyoneHasHadInitialTurn(true).actedPlayersInRound(Set.of())
                                .scheduledTaskDeadlines(Map.of()).handEvaluator(mockHandEvaluator).build();

                anchored.removePlayerFromGame(dealer);
                anchored.removePlayerFromGame(bigBlind);

                assertEquals("a", anchored.getSmallBlindPlayerId());
                assertEquals("b", anchored.getBigBlindPlayerId(), "The departed payer identity is retained, not reassigned");
                assertFalse(anchored.getActivePlayers().stream().anyMatch(p -> p.getPlayerId().equals("b")));
                anchored.advancePositions();
                assertEquals("a", anchored.getDealerAnchorPlayerId());
                assertEquals("a", anchored.getSmallBlindPlayerId());
                assertEquals("c", anchored.getBigBlindPlayerId());
        }

        @Test
        void postFlopActorUsesDealerAnchorAfterSmallBlindLeavesDuringPreFlop() {
                Player a = restoredPlayer("A", "a", 990, 10, false);
                Player b = restoredPlayer("B", "b", 980, 20, false);
                Player c = restoredPlayer("C", "c", 1000, 0, false);
                Player d = restoredPlayer("D", "d", 1000, 0, false);
                Game hand = Game.restoreBuilder().gameId("post-flop-seat-anchor")
                                .players(List.of(a, b, c, d)).activePlayerIds(List.of("a", "b", "c", "d"))
                                .remainingDeck(List.of(new Card(Rank.TWO, Suit.SPADES), new Card(Rank.THREE, Suit.SPADES),
                                                new Card(Rank.FOUR, Suit.SPADES), new Card(Rank.FIVE, Suit.SPADES),
                                                new Card(Rank.SIX, Suit.SPADES))).communityCards(List.of()).pot(30).dealerPosition(3)
                                .smallBlindPosition(0).bigBlindPosition(1).currentPlayerPosition(2)
                                .currentHighestBet(20).currentPhase(GamePhase.PRE_FLOP).gameOver(false)
                                .smallBlind(10).bigBlind(20).handContributions(Map.of("a", 10, "b", 20))
                                .preHandCarryOver(0).handSeatOrder(List.of("a", "b", "c", "d"))
                                .dealerAnchorPlayerId("d").smallBlindPlayerId("a").bigBlindPlayerId("b")
                                .readyCountdownActive(false).everyoneHasHadInitialTurn(false)
                                .actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(mockHandEvaluator).build();

                hand.removePlayerFromGame(a);
                assertEquals("c", hand.getCurrentPlayer().getPlayerId());
                hand.processPlayerDecision(c, new PlayerDecision(PlayerAction.CALL, 0, "c"));
                hand.processPlayerDecision(d, new PlayerDecision(PlayerAction.CALL, 0, "d"));
                hand.processPlayerDecision(b, new PlayerDecision(PlayerAction.CHECK, 0, "b"));

                assertEquals(GamePhase.FLOP, hand.getCurrentPhase());
                assertEquals("b", hand.getCurrentPlayer().getPlayerId(),
                                "First post-flop action starts after departed dealer D's stable hand anchor");
                assertEquals("d", hand.getDealerAnchorPlayerId());
                assertEquals("a", hand.getSmallBlindPlayerId(), "The departed blind payer identity remains recorded");

                Player a2 = restoredPlayer("A2", "a2", 1000, 0, false);
                Player b2 = restoredPlayer("B2", "b2", 1000, 0, false);
                Player c2 = restoredPlayer("C2", "c2", 1000, 0, false);
                Player d2 = restoredPlayer("D2", "d2", 1000, 0, false);
                Game dealerLeft = Game.restoreBuilder().gameId("departed-dealer-post-flop")
                                .players(List.of(a2, b2, c2, d2)).activePlayerIds(List.of("a2", "b2", "c2", "d2"))
                                .remainingDeck(List.of()).communityCards(List.of()).pot(0).dealerPosition(3)
                                .smallBlindPosition(0).bigBlindPosition(1).currentPlayerPosition(2)
                                .currentHighestBet(0).currentPhase(GamePhase.FLOP).gameOver(false)
                                .smallBlind(10).bigBlind(20).handContributions(Map.of()).preHandCarryOver(0)
                                .handSeatOrder(List.of("a2", "b2", "c2", "d2")).dealerAnchorPlayerId("d2")
                                .readyCountdownActive(false).everyoneHasHadInitialTurn(false)
                                .actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(mockHandEvaluator).build();
                dealerLeft.removePlayerFromGame(d2);
                dealerLeft.resetBetsForRound();
                assertEquals("a2", dealerLeft.getCurrentPlayer().getPlayerId(),
                                "A departed dealer anchor still determines the clockwise first seat");
        }

        @Test
        void removingCurrentPlayerKeepsImmediateEligibleSuccessorAsActor() {
                Player a = restoredPlayer("A", "a", 1000, 0, false);
                Player b = restoredPlayer("B", "b", 1000, 0, false);
                Player c = restoredPlayer("C", "c", 1000, 0, false);
                Player d = restoredPlayer("D", "d", 1000, 0, false);
                Game hand = Game.restoreBuilder().gameId("leave-successor")
                                .players(List.of(a, b, c, d)).activePlayerIds(List.of("a", "b", "c", "d"))
                                .remainingDeck(List.of()).communityCards(List.of()).pot(0).dealerPosition(0)
                                .smallBlindPosition(1).bigBlindPosition(2).currentPlayerPosition(1)
                                .currentHighestBet(0).currentPhase(GamePhase.FLOP).gameOver(false).smallBlind(5).bigBlind(10)
                                .handContributions(Map.of()).preHandCarryOver(0).handSeatOrder(List.of("a", "b", "c", "d"))
                                .dealerAnchorPlayerId("a").readyCountdownActive(false).everyoneHasHadInitialTurn(false)
                                .actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(mockHandEvaluator).build();

                hand.removePlayerFromGame(b);
                TurnOutcome outcome = hand.reconcileAfterPlayerRemoval();

                assertEquals(TurnOutcome.TurnOutcomeType.NEXT_PLAYER, outcome.type());
                assertEquals("c", hand.getCurrentPlayer().getPlayerId(), "The successor is selected exactly once");
        }

        @Test
        void departureThatCompletesMatchedFlopTurnsAdvancesToTurnOnce() {
                Player a = restoredPlayer("A", "a", 1000, 0, false);
                Player b = restoredPlayer("B", "b", 1000, 0, false);
                Player c = restoredPlayer("C", "c", 1000, 0, false);
                Game hand = Game.restoreBuilder().gameId("leave-completes-flop")
                                .players(List.of(a, b, c)).activePlayerIds(List.of("a", "b", "c"))
                                .remainingDeck(List.of(new Card(Rank.SIX, Suit.CLUBS))).communityCards(List.of(
                                                new Card(Rank.TWO, Suit.CLUBS), new Card(Rank.THREE, Suit.DIAMONDS),
                                                new Card(Rank.FOUR, Suit.HEARTS)))
                                .pot(0).dealerPosition(0).smallBlindPosition(1).bigBlindPosition(2)
                                .currentPlayerPosition(2).currentHighestBet(0).currentPhase(GamePhase.FLOP)
                                .gameOver(false).smallBlind(5).bigBlind(10).handContributions(Map.of()).preHandCarryOver(0)
                                .handSeatOrder(List.of("a", "b", "c")).dealerAnchorPlayerId("a")
                                .readyCountdownActive(false).everyoneHasHadInitialTurn(false)
                                .actedPlayersInRound(Set.of("a", "b")).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(mockHandEvaluator).build();

                hand.removePlayerFromGame(c);
                TurnOutcome outcome = hand.reconcileAfterPlayerRemoval();

                assertEquals(TurnOutcome.TurnOutcomeType.PHASE_ADVANCED, outcome.type());
                assertEquals(GamePhase.TURN, hand.getCurrentPhase());
                assertEquals(4, hand.getCommunityCards().size(), "The turn card is dealt once after the three-card flop");
        }

        @Test
        void departureLeavingOneContenderSettlesHandWithoutAnotherAction() {
                Player a = restoredPlayer("A", "a", 950, 50, false);
                Player folded = restoredPlayer("Folded", "folded", 950, 50, false);
                folded.doAction(PlayerAction.FOLD, 0, 0);
                Player leaving = restoredPlayer("Leaving", "leaving", 1000, 0, false);
                Game hand = Game.restoreBuilder().gameId("leave-finishes-hand")
                                .players(List.of(a, folded, leaving)).activePlayerIds(List.of("a", "folded", "leaving"))
                                .remainingDeck(List.of()).communityCards(List.of()).pot(100).dealerPosition(0)
                                .smallBlindPosition(1).bigBlindPosition(2).currentPlayerPosition(2)
                                .currentHighestBet(0).currentPhase(GamePhase.FLOP).gameOver(false).smallBlind(5).bigBlind(10)
                                .handContributions(Map.of("a", 50, "folded", 50)).preHandCarryOver(0)
                                .handSeatOrder(List.of("a", "folded", "leaving")).dealerAnchorPlayerId("a")
                                .readyCountdownActive(false).everyoneHasHadInitialTurn(false)
                                .actedPlayersInRound(Set.of("a", "folded")).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(mockHandEvaluator).build();

                hand.removePlayerFromGame(leaving);
                TurnOutcome outcome = hand.reconcileAfterPlayerRemoval();

                assertEquals(TurnOutcome.TurnOutcomeType.SHOWDOWN, outcome.type());
                assertEquals(List.of(a), outcome.winners());
                assertEquals(1050, a.getChips());
                assertEquals(950, folded.getChips(), "The folded contributor receives no award");
                assertEquals(0, hand.getPot());
                assertTrue(hand.getLastSettlementResult().settled());
        }

        @Test
        void riverCallThatCompletesBettingReturnsShowdownImmediately() {
                Player allIn = restoredPlayer("AllIn", "allIn", 0, 100, true);
                Player caller = restoredPlayer("Caller", "caller", 100, 0, false);
                Game hand = Game.restoreBuilder().gameId("river-call-showdown")
                                .players(List.of(allIn, caller)).activePlayerIds(List.of("allIn", "caller"))
                                .remainingDeck(List.of()).communityCards(List.of(
                                                new Card(Rank.TWO, Suit.CLUBS), new Card(Rank.THREE, Suit.DIAMONDS),
                                                new Card(Rank.FOUR, Suit.HEARTS), new Card(Rank.FIVE, Suit.SPADES),
                                                new Card(Rank.SIX, Suit.CLUBS)))
                                .pot(100).dealerPosition(0).smallBlindPosition(0).bigBlindPosition(1)
                                .currentPlayerPosition(1).currentHighestBet(100).currentPhase(GamePhase.RIVER)
                                .gameOver(false).smallBlind(5).bigBlind(10).handContributions(Map.of("allIn", 100))
                                .preHandCarryOver(0).readyCountdownActive(false).everyoneHasHadInitialTurn(false)
                                .actedPlayersInRound(Set.of("allIn")).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(mockHandEvaluator).build();
                when(mockHandEvaluator.getBestHand(any(), any()))
                                .thenReturn(new HandEvaluationResult(List.of(new Card(Rank.ACE, Suit.SPADES)), HandRank.HIGH_CARD));
                when(mockHandEvaluator.isBetterHandOfSameRank(any(), any(), any())).thenReturn(false);

                TurnOutcome outcome = hand.processPlayerDecision(caller,
                                new PlayerDecision(PlayerAction.CALL, 0, "caller"));

                assertEquals(TurnOutcome.TurnOutcomeType.SHOWDOWN, outcome.type());
                assertEquals(GamePhase.SHOWDOWN, hand.getCurrentPhase());
                assertTrue(hand.getLastSettlementResult().settled());
        }

        @Test
        void foldedContributionBoundariesDoNotCreateOddChipsAcrossSameEligiblePot() {
                List<Card> highCard = List.of(new Card(Rank.ACE, Suit.SPADES));
                when(mockHandEvaluator.getBestHand(any(), any()))
                                .thenReturn(new HandEvaluationResult(highCard, HandRank.HIGH_CARD));
                when(mockHandEvaluator.isBetterHandOfSameRank(any(), any(), any())).thenReturn(false);
                Player a = Player.restoreBuilder().name("A").playerId("a").chips(900).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(100).hasFolded(false)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Player b = Player.restoreBuilder().name("B").playerId("b").chips(900).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(100).hasFolded(false)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Player f1 = Player.restoreBuilder().name("F1").playerId("f1").chips(995).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(5).hasFolded(true)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Player f2 = Player.restoreBuilder().name("F2").playerId("f2").chips(990).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(10).hasFolded(true)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Player f3 = Player.restoreBuilder().name("F3").playerId("f3").chips(985).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(15).hasFolded(true)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Game ordinary = Game.restoreBuilder().gameId("coalesce-ordinary-pots")
                                .players(List.of(a, b, f1, f2, f3)).activePlayerIds(List.of("a", "b", "f1", "f2", "f3"))
                                .remainingDeck(List.of()).communityCards(List.of()).pot(230).dealerPosition(0)
                                .smallBlindPosition(0).bigBlindPosition(1).currentPlayerPosition(0).currentHighestBet(100)
                                .currentPhase(GamePhase.RIVER).gameOver(false).smallBlind(10).bigBlind(20)
                                .handContributions(Map.of("a", 100, "b", 100, "f1", 5, "f2", 10, "f3", 15))
                                .preHandCarryOver(0).readyCountdownActive(false).everyoneHasHadInitialTurn(true)
                                .actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(mockHandEvaluator).build();

                ordinary.conductShowdown();

                assertEquals(1015, a.getChips());
                assertEquals(1015, b.getChips());
                assertEquals(0, ordinary.getPot());
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

                assertTrue(exception.getMessage().contains("Raise increment must be at least 20"));
                assertEquals("MIN_RAISE", exception.actionErrorCode());
                assertEquals("Min raise: 40; yours: 30", exception.actionErrorMessage());
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

}
