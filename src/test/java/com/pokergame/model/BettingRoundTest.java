package com.pokergame.model;

import com.pokergame.dto.internal.PlayerDecision;
import com.pokergame.dto.internal.TurnOutcome.TurnOutcomeType;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.PlayerAction;
import com.pokergame.exception.ActionFeedbackException;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.UnauthorisedActionException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class BettingRoundTest {

    @Test
    void shortRaiseDoesNotReopenUntilFullIncrementIsReached() {
        Player alice = new Player("Alice", "a", 100);
        Player bob = new Player("Bob", "b", 100);
        List<Player> players = List.of(alice, bob);
        TableSeating seating = new TableSeating(players, players);

        BettingRound shortRaiseRound = new BettingRound(
                30, false, Set.of("a"), Map.of(), Map.of("a", 20));
        assertFalse(shortRaiseRound.legalActionsFor(alice, players, players, seating, GamePhase.FLOP, 20)
                .contains(PlayerAction.RAISE));
        assertEquals(50, shortRaiseRound.getMinimumRaiseAmount(alice, GamePhase.FLOP, 20));
        ActionFeedbackException error = assertThrows(ActionFeedbackException.class, () ->
                shortRaiseRound.prepareDecision(alice, new PlayerDecision(PlayerAction.RAISE, 60, "a"),
                        players, GamePhase.FLOP, 20));
        assertEquals("RAISE_NOT_REOPENED", error.actionErrorCode());

        BettingRound fullRaiseRound = new BettingRound(
                40, false, Set.of("a"), Map.of(), Map.of("a", 20));
        assertTrue(fullRaiseRound.legalActionsFor(alice, players, players, seating, GamePhase.FLOP, 20)
                .contains(PlayerAction.RAISE));
        var prepared = fullRaiseRound.prepareDecision(alice, new PlayerDecision(PlayerAction.RAISE, 60, "a"),
                players, GamePhase.FLOP, 20);
        assertEquals(PlayerAction.RAISE, prepared.decision().action());
    }

    @Test
    void legalActionsReflectCallCheckAndContestableRaise() {
        Player alice = new Player("Alice", "a", 100);
        Player bob = new Player("Bob", "b", 100);
        List<Player> players = List.of(alice, bob);
        TableSeating seating = new TableSeating(players, players);

        BettingRound checkedPot = new BettingRound();
        assertEquals(List.of(PlayerAction.FOLD, PlayerAction.CHECK, PlayerAction.BET, PlayerAction.ALL_IN),
                checkedPot.legalActionsFor(alice, players, players, seating, GamePhase.FLOP, 20));

        checkedPot.setHighestBet(20);
        assertEquals(List.of(PlayerAction.FOLD, PlayerAction.CALL, PlayerAction.RAISE, PlayerAction.ALL_IN),
                checkedPot.legalActionsFor(alice, players, players, seating, GamePhase.FLOP, 20));
        assertEquals(List.of(),
                checkedPot.legalActionsFor(bob, players, players, seating, GamePhase.FLOP, 20));

        ActionFeedbackException error = assertThrows(ActionFeedbackException.class, () ->
                checkedPot.prepareDecision(alice, new PlayerDecision(PlayerAction.CHECK, 0, "a"),
                        players, GamePhase.FLOP, 20));
        assertEquals("CHECK_NOT_AVAILABLE", error.actionErrorCode());
    }

    @Test
    void shortCallIsPreparedAsAllInWithoutMutatingPlayer() {
        Player alice = new Player("Alice", "a", 10);
        Player bob = new Player("Bob", "b", 100);
        BettingRound round = new BettingRound(20, false, Set.of(), Map.of(), Map.of());

        var result = round.prepareDecision(alice, new PlayerDecision(PlayerAction.CALL, 0, "a"),
                List.of(alice, bob), GamePhase.FLOP, 20);
        assertEquals(PlayerAction.ALL_IN, result.decision().action());
        assertEquals(0, result.amount());
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
        BettingRound round = new BettingRound(20, true, Set.of(), Map.of(), Map.of());

        assertTrue(BettingRound.hasActionablePlayerOwingCall(players, 20));
        assertEquals(BettingRound.ForcedBlindProgression.SELECT_ACTOR, round.afterForcedBlinds(players, false));
        assertEquals(TurnOutcomeType.NEXT_PLAYER, round.nextProgression(players, false, GamePhase.FLOP));

        alice.doAction(PlayerAction.CALL, 20, 20);
        round.recordAppliedDecision(alice, GamePhase.FLOP, 20, 0);

        assertFalse(BettingRound.hasActionablePlayerOwingCall(players, 20));
        assertEquals(BettingRound.ForcedBlindProgression.AUTO_RUNOUT, round.afterForcedBlinds(players, false));
        assertEquals(TurnOutcomeType.AUTO_ADVANCING, round.nextProgression(players, false, GamePhase.FLOP));
    }

    @Test
    void removalCapsWagerAndProgressionChoosesRunout() {
        Player alice = new Player("Alice", "a", 100);
        Player bob = new Player("Bob", "b", 20);
        bob.doAction(PlayerAction.ALL_IN, 0, 0);
        List<Player> remaining = List.of(alice, bob);

        BettingRound round = new BettingRound(50, false, Set.of("d"), Map.of(), Map.of("d", 50));
        round.removePlayer("d");
        round.reconcileHighestBetAfterRemoval(remaining);

        assertEquals(20, round.getCurrentHighestBet());
        assertFalse(round.getActedPlayerIdsSnapshot().contains("d"));
        assertFalse(round.getLastActedWagerByPlayerSnapshot().containsKey("d"));
        assertEquals(TurnOutcomeType.NEXT_PLAYER, round.nextProgression(remaining, false, GamePhase.FLOP));

        alice.doAction(PlayerAction.CALL, 20, 20);
        round.recordAppliedDecision(alice, GamePhase.FLOP, 20, 0);
        assertEquals(TurnOutcomeType.AUTO_ADVANCING, round.nextProgression(remaining, false, GamePhase.FLOP));
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

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("playerFacingRuleErrors")
    void pinsExactExceptionTypeAndCodeForAllPlayerFacingRuleErrors(
            String scenario,
            Class<? extends ActionFeedbackException> expectedType,
            String expectedCode,
            Player actor,
            PlayerDecision decision,
            List<Player> activePlayers,
            int highestBet,
            Map<String, Integer> lastActedWagerByPlayer) {
        BettingRound round = new BettingRound(highestBet, false, Set.of(), Map.of(), lastActedWagerByPlayer);
        ActionFeedbackException error = assertThrows(expectedType, () ->
                round.prepareDecision(actor, decision, activePlayers, GamePhase.FLOP, 20),
                scenario);
        assertEquals(expectedCode, error.actionErrorCode(), scenario);
    }

    private static Stream<Arguments> playerFacingRuleErrors() {
        Player eligibleActor = new Player("Actor", "a", 100);
        Player opponent = new Player("Opponent", "b", 100);
        List<Player> twoEligible = List.of(eligibleActor, opponent);

        Player foldedActor = new Player("Folded", "f", 100);
        foldedActor.doAction(PlayerAction.FOLD, 0, 0);

        Player allInActor = new Player("AllIn", "ai", 100);
        allInActor.doAction(PlayerAction.ALL_IN, 0, 0);

        Player allInOpponent = new Player("AllInOpp", "ao", 20);
        allInOpponent.doAction(PlayerAction.ALL_IN, 0, 0);

        return Stream.of(
                Arguments.of("null player", UnauthorisedActionException.class, "ACTION_NOT_AVAILABLE",
                        null, new PlayerDecision(PlayerAction.FOLD, 0, "a"), twoEligible, 0, Map.of()),
                Arguments.of("folded player cannot act", UnauthorisedActionException.class, "ACTION_NOT_AVAILABLE",
                        foldedActor, new PlayerDecision(PlayerAction.CHECK, 0, "f"),
                        List.of(foldedActor, opponent), 0, Map.of()),
                Arguments.of("all-in player cannot act", UnauthorisedActionException.class, "ACTION_NOT_AVAILABLE",
                        allInActor, new PlayerDecision(PlayerAction.CHECK, 0, "ai"),
                        List.of(allInActor, opponent), 0, Map.of()),
                Arguments.of("call with nothing owed", UnauthorisedActionException.class, "CALL_NOT_AVAILABLE",
                        eligibleActor, new PlayerDecision(PlayerAction.CALL, 0, "a"), twoEligible, 0, Map.of()),
                Arguments.of("raise with no active bet", UnauthorisedActionException.class, "RAISE_NOT_AVAILABLE",
                        eligibleActor, new PlayerDecision(PlayerAction.RAISE, 40, "a"), twoEligible, 0, Map.of()),
                Arguments.of("uncontestable bet", UnauthorisedActionException.class, "BET_NOT_AVAILABLE",
                        eligibleActor, new PlayerDecision(PlayerAction.BET, 20, "a"),
                        List.of(eligibleActor), 0, Map.of()),
                Arguments.of("uncontestable raise", UnauthorisedActionException.class, "RAISE_NOT_AVAILABLE",
                        eligibleActor, new PlayerDecision(PlayerAction.RAISE, 40, "a"),
                        List.of(eligibleActor, allInOpponent), 20, Map.of()),
                Arguments.of("uncontestable all-in exceeding highest bet", UnauthorisedActionException.class, "RAISE_NOT_AVAILABLE",
                        eligibleActor, new PlayerDecision(PlayerAction.ALL_IN, 0, "a"),
                        List.of(eligibleActor, allInOpponent), 20, Map.of()),
                Arguments.of("raise not reopened", UnauthorisedActionException.class, "RAISE_NOT_REOPENED",
                        eligibleActor, new PlayerDecision(PlayerAction.RAISE, 50, "a"),
                        twoEligible, 25, Map.of("a", 20)),
                Arguments.of("all-in not reopened", UnauthorisedActionException.class, "RAISE_NOT_REOPENED",
                        eligibleActor, new PlayerDecision(PlayerAction.ALL_IN, 0, "a"),
                        twoEligible, 25, Map.of("a", 20)),
                Arguments.of("raise amount zero", BadRequestException.class, "RAISE_AMOUNT_REQUIRED",
                        eligibleActor, new PlayerDecision(PlayerAction.RAISE, 0, "a"), twoEligible, 20, Map.of()),
                Arguments.of("raise exceeds chips", BadRequestException.class, "INSUFFICIENT_CHIPS",
                        eligibleActor, new PlayerDecision(PlayerAction.RAISE, 101, "a"), twoEligible, 20, Map.of()),
                Arguments.of("raise total not above highest bet", BadRequestException.class, "MIN_RAISE",
                        eligibleActor, new PlayerDecision(PlayerAction.RAISE, 10, "a"), twoEligible, 20, Map.of()),
                Arguments.of("raise increment below last full raise", BadRequestException.class, "MIN_RAISE",
                        eligibleActor, new PlayerDecision(PlayerAction.RAISE, 30, "a"), twoEligible, 20, Map.of()),
                Arguments.of("bet when active bet exists", UnauthorisedActionException.class, "BET_NOT_AVAILABLE",
                        eligibleActor, new PlayerDecision(PlayerAction.BET, 20, "a"), twoEligible, 20, Map.of()),
                Arguments.of("bet amount zero", BadRequestException.class, "BET_AMOUNT_REQUIRED",
                        eligibleActor, new PlayerDecision(PlayerAction.BET, 0, "a"), twoEligible, 0, Map.of()),
                Arguments.of("bet exceeds chips", BadRequestException.class, "INSUFFICIENT_CHIPS",
                        eligibleActor, new PlayerDecision(PlayerAction.BET, 101, "a"), twoEligible, 0, Map.of()),
                Arguments.of("bet below big blind", BadRequestException.class, "MIN_BET",
                        eligibleActor, new PlayerDecision(PlayerAction.BET, 10, "a"), twoEligible, 0, Map.of()),
                Arguments.of("check with active bet owed", UnauthorisedActionException.class, "CHECK_NOT_AVAILABLE",
                        eligibleActor, new PlayerDecision(PlayerAction.CHECK, 0, "a"), twoEligible, 20, Map.of())
        );
    }

    private static void assertDecisionError(
            String code,
            Player actor,
            PlayerAction action,
            int amount,
            List<Player> players,
            int highestBet,
            Map<String, Integer> lastActedWagerByPlayer) {
        BettingRound round = new BettingRound(highestBet, false, Set.of(), Map.of(), lastActedWagerByPlayer);
        ActionFeedbackException error = assertThrows(ActionFeedbackException.class, () ->
                round.prepareDecision(actor, new PlayerDecision(action, amount, "a"), players, GamePhase.FLOP, 20));
        assertEquals(code, error.actionErrorCode());
    }

    @Test
    void fullRaiseHistoryIsScopedToTheStreet() {
        Player actor = new Player("Actor", "a", 200);
        BettingRound round = new BettingRound(50, true, Set.of(), Map.of(GamePhase.FLOP.name(), 50), Map.of());

        assertEquals(100, round.getMinimumRaiseAmount(actor, GamePhase.FLOP, 20));
        assertEquals(70, round.getMinimumRaiseAmount(actor, GamePhase.TURN, 20));

        BettingRound recordedRound = new BettingRound(20, true, Set.of(), Map.of(), Map.of());
        actor.doAction(PlayerAction.RAISE, 70, 20);
        recordedRound.recordAppliedDecision(actor, GamePhase.FLOP, 20, 20);
        assertEquals(70, recordedRound.getCurrentHighestBet());
        assertEquals(50, recordedRound.getLastFullRaiseByStreetSnapshot().get(GamePhase.FLOP.name()));
        assertFalse(recordedRound.getLastFullRaiseByStreetSnapshot().containsKey(GamePhase.TURN.name()));
    }

    @Test
    void forcedBlindsPreferSettlingOverRunout() {
        Player activeOne = new Player("One", "1", 100);
        Player activeTwo = new Player("Two", "2", 100);
        Player allIn = new Player("AllIn", "3", 20);
        allIn.doAction(PlayerAction.ALL_IN, 0, 0);

        BettingRound round = new BettingRound();
        assertEquals(BettingRound.ForcedBlindProgression.RECONCILE,
                round.afterForcedBlinds(List.of(activeOne), true));
        assertEquals(BettingRound.ForcedBlindProgression.AUTO_RUNOUT,
                round.afterForcedBlinds(List.of(activeOne, allIn), false));
        assertEquals(BettingRound.ForcedBlindProgression.SELECT_ACTOR,
                round.afterForcedBlinds(List.of(activeOne, activeTwo), false));
    }

    @Test
    void roundProgressionDistinguishesRiverCompletionAndOpenAction() {
        Player alice = new Player("Alice", "a", 100);
        Player bob = new Player("Bob", "b", 100);
        List<Player> players = List.of(alice, bob);

        BettingRound completedRound = new BettingRound(0, true, Set.of("a", "b"), Map.of(), Map.of());
        assertEquals(TurnOutcomeType.SHOWDOWN, completedRound.nextProgression(players, false, GamePhase.RIVER));
        assertEquals(TurnOutcomeType.PHASE_ADVANCED, completedRound.nextProgression(players, false, GamePhase.FLOP));
        assertEquals(TurnOutcomeType.SHOWDOWN, completedRound.nextProgression(players, true, GamePhase.FLOP));

        BettingRound openRound = new BettingRound(0, false, Set.of("a"), Map.of(), Map.of());
        assertEquals(TurnOutcomeType.NEXT_PLAYER, openRound.nextProgression(players, false, GamePhase.FLOP));
    }

    @Test
    void foldedPlayersDoNotPreventRemainingActorsFromCompletingAction() {
        Player actor = new Player("Actor", "a", 100);
        Player opponent = new Player("Opponent", "b", 100);
        Player folded = new Player("Folded", "f", 100);
        folded.doAction(PlayerAction.FOLD, 0, 0);

        BettingRound round = new BettingRound(0, true, Set.of("a", "b"), Map.of(), Map.of());
        assertEquals(TurnOutcomeType.PHASE_ADVANCED,
                round.nextProgression(List.of(actor, opponent, folded), false, GamePhase.FLOP));

        BettingRound onlyFoldedActed = new BettingRound(0, false, Set.of("f"), Map.of(), Map.of());
        assertEquals(TurnOutcomeType.NEXT_PLAYER,
                onlyFoldedActed.nextProgression(List.of(actor, opponent, folded), false, GamePhase.FLOP));
    }

    @Test
    void resetsRoundAndHandStateCleanly() {
        Player blindPoster = new Player("Blind", "b", 100);
        blindPoster.doAction(PlayerAction.CALL, 20, 20);
        BettingRound round = new BettingRound(20, true, Set.of("a"), Map.of("FLOP", 40), Map.of("a", 20));

        round.resetForNewRound(List.of(blindPoster));
        assertEquals(0, blindPoster.getCurrentBet());
        assertEquals(0, round.getCurrentHighestBet());
        assertFalse(round.hasAllPlayersHadInitialTurn());
        assertTrue(round.getActedPlayerIdsSnapshot().isEmpty());
        assertEquals(Map.of(), round.getLastActedWagerByPlayerSnapshot());

        round.resetForNewHand();
        assertFalse(round.hasAllPlayersHadInitialTurn());
        assertTrue(round.getLastFullRaiseByStreetSnapshot().isEmpty());
    }
}
