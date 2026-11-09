package com.pokergame.model;

import com.pokergame.dto.internal.TurnOutcome;
import com.pokergame.enums.*;
import com.pokergame.exception.BadRequestException;
import java.util.ArrayList;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import static org.mockito.Mockito.*;

import com.pokergame.dto.internal.PlayerDecision;
import com.pokergame.dto.internal.TurnOutcome.TurnOutcomeType;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.PlayerAction;
import com.pokergame.exception.UnauthorisedActionException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BettingRoundTest {
    @Test
    void shortRaiseDoesNotReopenUntilFullIncrementIsReached() {
        Player player = new Player("Alice", "a", 100);
        assertFalse(BettingRound.raiseOpenTo(player, Map.of("a", 20), 30, 20));
        assertTrue(BettingRound.raiseOpenTo(player, Map.of("a", 20), 40, 20));
        assertEquals(30, BettingRound.callDifference(player, 30));
    }

    @Test
    void legalActionsReflectCallCheckAndContestableRaise() {
        Player alice = new Player("Alice", "a", 100);
        Player bob = new Player("Bob", "b", 100);
        List<Player> players = List.of(alice, bob);
        assertEquals(List.of(PlayerAction.FOLD, PlayerAction.CHECK, PlayerAction.BET, PlayerAction.ALL_IN),
                BettingRound.legalActions(alice, players, players, alice, GamePhase.FLOP, 0, Map.of(), 20));
        assertEquals(List.of(PlayerAction.FOLD, PlayerAction.CALL, PlayerAction.RAISE, PlayerAction.ALL_IN),
                BettingRound.legalActions(alice, players, players, alice, GamePhase.FLOP, 20, Map.of(), 20));
        assertThrows(UnauthorisedActionException.class, () -> BettingRound.prepareDecision(alice,
                new PlayerDecision(PlayerAction.CHECK, 0, "a"), players, 20, 20, Map.of(), 20));
    }

    @Test
    void shortCallIsPreparedAsAllInWithoutMutatingPlayer() {
        Player alice = new Player("Alice", "a", 10);
        Player bob = new Player("Bob", "b", 100);
        var result = BettingRound.prepareDecision(alice, new PlayerDecision(PlayerAction.CALL, 0, "a"),
                List.of(alice, bob), 20, 20, Map.of(), 20);
        assertEquals(PlayerAction.ALL_IN, result.decision().action());
        assertNotNull(result.conversionMessage());
        assertEquals(10, alice.getChips());
    }

    @Test
    void allInAndFoldedPlayersDoNotBlockRunoutButAnOwedCallDoes() {
        Player alice = new Player("Alice", "a", 100);
        Player bob = new Player("Bob", "b", 20);
        bob.doAction(PlayerAction.ALL_IN, 0, 0);
        Player carol = new Player("Carol", "c", 100);
        carol.doAction(PlayerAction.FOLD, 0, 0);
        List<Player> players = List.of(alice, bob, carol);
        assertFalse(BettingRound.shouldAutoRunout(players, 20));
        assertFalse(BettingRound.isComplete(players, 20, true));
        alice.doAction(PlayerAction.CALL, 20, 20);
        assertTrue(BettingRound.shouldAutoRunout(players, 20));
        assertTrue(BettingRound.isComplete(players, 20, false));
    }

    @Test
    void removalCapsWagerAndProgressionChoosesRunout() {
        Player alice = new Player("Alice", "a", 100);
        Player bob = new Player("Bob", "b", 20);
        bob.doAction(PlayerAction.ALL_IN, 0, 0);
        List<Player> remaining = List.of(alice, bob);
        assertEquals(20, BettingRound.highestBetAfterRemoval(remaining, 50));
        assertFalse(BettingRound.allActionablePlayersActed(remaining, Set.of()));
        assertEquals(TurnOutcomeType.AUTO_ADVANCING,
                BettingRound.progression(false, true, GamePhase.FLOP, true));
    }


    @Test
    void rejectsUnavailableActionsWithStableFeedback() {
        Player actor = new Player("Actor", "a", 100);
        Player opponent = new Player("Opponent", "b", 100);
        List<Player> players = List.of(actor, opponent);

        assertDecisionError("CALL_NOT_AVAILABLE", actor, PlayerAction.CALL, 0, players, 0, Map.of());
        assertDecisionError("RAISE_NOT_AVAILABLE", actor, PlayerAction.RAISE, 40, players, 0, Map.of());
        assertDecisionError("BET_NOT_AVAILABLE", actor, PlayerAction.BET, 20, players, 20, Map.of());
        assertDecisionError("RAISE_NOT_REOPENED", actor, PlayerAction.RAISE, 50, players, 25, Map.of("a", 20));
        assertDecisionError("CHECK_NOT_AVAILABLE", actor, PlayerAction.CHECK, 0, players, 20, Map.of());
        assertDecisionError("BET_NOT_AVAILABLE", actor, PlayerAction.BET, 20, List.of(actor), 0, Map.of());
        assertDecisionError("ACTION_NOT_AVAILABLE", null, PlayerAction.FOLD, 0, players, 0, Map.of());
    }

    @Test
    void rejectsInvalidWagerAmountsWithStableFeedback() {
        Player actor = new Player("Actor", "a", 100);
        List<Player> players = List.of(actor, new Player("Opponent", "b", 100));

        assertDecisionError("BET_AMOUNT_REQUIRED", actor, PlayerAction.BET, 0, players, 0, Map.of());
        assertDecisionError("RAISE_AMOUNT_REQUIRED", actor, PlayerAction.RAISE, 0, players, 20, Map.of());
        assertDecisionError("INSUFFICIENT_CHIPS", actor, PlayerAction.RAISE, 101, players, 20, Map.of());
        assertDecisionError("INSUFFICIENT_CHIPS", actor, PlayerAction.BET, 101, players, 0, Map.of());
        assertDecisionError("MIN_RAISE", actor, PlayerAction.RAISE, 30, players, 20, Map.of());
        assertDecisionError("MIN_BET", actor, PlayerAction.BET, 10, players, 0, Map.of());
    }

    private static void assertDecisionError(String code, Player actor, PlayerAction action, int amount,
                                            List<Player> players, int highest, Map<String, Integer> acted) {
        var error = assertThrows(com.pokergame.exception.ActionFeedbackException.class,
                () -> BettingRound.prepareDecision(actor, new PlayerDecision(action, amount, "a"),
                        players, highest, 20, acted, 20));
        assertEquals(code, error.actionErrorCode());
    }

    @Test
    void fullRaiseHistoryIsScopedToTheStreet() {
        Map<String, Integer> raises = Map.of(GamePhase.FLOP.name(), 50);
        assertEquals(50, BettingRound.lastFullRaise(raises, GamePhase.FLOP, 20));
        assertEquals(20, BettingRound.lastFullRaise(raises, GamePhase.TURN, 20));
    }

    @Test
    void forcedBlindsPreferSettlingOverRunout() {
        assertEquals(BettingRound.ForcedBlindProgression.RECONCILE, BettingRound.afterForcedBlinds(true, true));
        assertEquals(BettingRound.ForcedBlindProgression.AUTO_RUNOUT, BettingRound.afterForcedBlinds(false, true));
        assertEquals(BettingRound.ForcedBlindProgression.SELECT_ACTOR, BettingRound.afterForcedBlinds(false, false));
    }

    @Test
    void roundProgressionDistinguishesRiverCompletionAndOpenAction() {
        assertEquals(TurnOutcomeType.SHOWDOWN, BettingRound.progression(false, true, GamePhase.RIVER, true));
        assertEquals(TurnOutcomeType.PHASE_ADVANCED, BettingRound.progression(false, true, GamePhase.FLOP, false));
        assertEquals(TurnOutcomeType.NEXT_PLAYER, BettingRound.progression(false, false, GamePhase.FLOP, false));
    }

    @Test
    void foldedPlayersDoNotPreventTheRemainingActorsFromCompletingAction() {
        Player actor = new Player("Actor", "a", 100);
        Player folded = new Player("Folded", "f", 100);
        folded.doAction(PlayerAction.FOLD, 0, 0);
        assertTrue(BettingRound.allActionablePlayersActed(List.of(actor, folded), Set.of("a")));
        assertFalse(BettingRound.allActionablePlayersActed(List.of(folded), Set.of("f")));
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class AggregateScenarios extends GameScenarioFixture {

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
    }
}
