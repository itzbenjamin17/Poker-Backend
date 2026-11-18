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
                30, false, Set.of("a"), 0, Map.of("a", 20));
        assertFalse(shortRaiseRound.legalActionsFor(alice, players, players, seating, GamePhase.FLOP, 20)
                .contains(PlayerAction.RAISE));
        assertEquals(50, shortRaiseRound.getMinimumRaiseAmount(alice, 20));
        ActionFeedbackException error = assertThrows(ActionFeedbackException.class, () ->
                shortRaiseRound.prepareDecision(alice, new PlayerDecision(PlayerAction.RAISE, 60, "a"),
                        players, 20));
        assertEquals("RAISE_NOT_REOPENED", error.actionErrorCode());

        BettingRound fullRaiseRound = new BettingRound(
                40, false, Set.of("a"), 0, Map.of("a", 20));
        assertTrue(fullRaiseRound.legalActionsFor(alice, players, players, seating, GamePhase.FLOP, 20)
                .contains(PlayerAction.RAISE));
        var prepared = fullRaiseRound.prepareDecision(alice, new PlayerDecision(PlayerAction.RAISE, 60, "a"),
                players, 20);
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
                        players, 20));
        assertEquals("CHECK_NOT_AVAILABLE", error.actionErrorCode());
    }

    @Test
    void shortCallIsPreparedAsAllInWithoutMutatingPlayer() {
        Player alice = new Player("Alice", "a", 10);
        Player bob = new Player("Bob", "b", 100);
        BettingRound round = new BettingRound(20, false, Set.of(), 0, Map.of());

        var result = round.prepareDecision(alice, new PlayerDecision(PlayerAction.CALL, 0, "a"),
                List.of(alice, bob), 20);
        assertEquals(PlayerAction.ALL_IN, result.decision().action());
        assertEquals(0, result.amount());
        assertNotNull(result.conversionMessage());
        assertEquals(10, alice.getChips());
    }

    @Test
    void allInAndFoldedPlayersDoNotBlockRunoutButAnOwedCallDoes() {
        Player alice = new Player("Alice", "a", 100);
        Player bob = new Player("Bob", "b", 20);
        bob.doAction(PlayerAction.ALL_IN, 0);
        Player carol = new Player("Carol", "c", 100);
        carol.doAction(PlayerAction.FOLD, 0);
        List<Player> players = List.of(alice, bob, carol);
        BettingRound round = new BettingRound(20, true, Set.of(), 0, Map.of());

        assertTrue(BettingRound.hasActionablePlayerOwingCall(players, 20));
        assertEquals(BettingRound.ForcedBlindProgression.SELECT_ACTOR, round.afterForcedBlinds(players, false));
        assertEquals(TurnOutcomeType.NEXT_PLAYER, round.nextProgression(players, false, GamePhase.FLOP));

        alice.doAction(PlayerAction.CALL, 20);
        round.recordAppliedDecision(alice, 20, 0);

        assertFalse(BettingRound.hasActionablePlayerOwingCall(players, 20));
        assertEquals(BettingRound.ForcedBlindProgression.AUTO_RUNOUT, round.afterForcedBlinds(players, false));
        assertEquals(TurnOutcomeType.AUTO_ADVANCING, round.nextProgression(players, false, GamePhase.FLOP));
    }

    @Test
    void removalCapsWagerAndProgressionChoosesRunout() {
        Player alice = new Player("Alice", "a", 100);
        Player bob = new Player("Bob", "b", 20);
        bob.doAction(PlayerAction.ALL_IN, 0);
        List<Player> remaining = List.of(alice, bob);

        BettingRound round = new BettingRound(50, false, Set.of("d"), 0, Map.of("d", 50));
        round.removePlayer("d");
        round.reconcileHighestBetAfterRemoval(remaining);

        assertEquals(20, round.getCurrentHighestBet());
        assertFalse(round.getActedPlayerIdsSnapshot().contains("d"));
        assertFalse(round.getLastActedWagerByPlayerSnapshot().containsKey("d"));
        assertEquals(TurnOutcomeType.NEXT_PLAYER, round.nextProgression(remaining, false, GamePhase.FLOP));

        alice.doAction(PlayerAction.CALL, 20);
        round.recordAppliedDecision(alice, 20, 0);
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
        BettingRound round = new BettingRound(highestBet, false, Set.of(), 0, lastActedWagerByPlayer);
        ActionFeedbackException error = assertThrows(expectedType, () ->
                round.prepareDecision(actor, decision, activePlayers, 20),
                scenario);
        assertEquals(expectedCode, error.actionErrorCode(), scenario);
    }

    private static Stream<Arguments> playerFacingRuleErrors() {
        Player eligibleActor = new Player("Actor", "a", 100);
        Player opponent = new Player("Opponent", "b", 100);
        List<Player> twoEligible = List.of(eligibleActor, opponent);

        Player foldedActor = new Player("Folded", "f", 100);
        foldedActor.doAction(PlayerAction.FOLD, 0);

        Player allInActor = new Player("AllIn", "ai", 100);
        allInActor.doAction(PlayerAction.ALL_IN, 0);

        Player allInOpponent = new Player("AllInOpp", "ao", 20);
        allInOpponent.doAction(PlayerAction.ALL_IN, 0);

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
        BettingRound round = new BettingRound(highestBet, false, Set.of(), 0, lastActedWagerByPlayer);
        ActionFeedbackException error = assertThrows(ActionFeedbackException.class, () ->
                round.prepareDecision(actor, new PlayerDecision(action, amount, "a"), players, 20));
        assertEquals(code, error.actionErrorCode());
    }

    @Test
    void fullRaiseHistoryIsScopedToTheStreet() {
        Player actor = new Player("Actor", "a", 200);
        BettingRound round = new BettingRound(50, true, Set.of(), 50, Map.of());

        assertEquals(100, round.getMinimumRaiseAmount(actor, 20));
        round.resetForNewRound(List.of(actor));
        assertEquals(20, round.getMinimumRaiseAmount(actor, 20));

        BettingRound recordedRound = new BettingRound(20, true, Set.of(), 0, Map.of());
        actor.doAction(PlayerAction.RAISE, 70);
        recordedRound.recordAppliedDecision(actor, 20, 20);
        assertEquals(70, recordedRound.getCurrentHighestBet());
        assertEquals(50, recordedRound.getLastFullRaise());
        recordedRound.resetForNewRound(List.of(actor));
        assertEquals(0, recordedRound.getLastFullRaise());
        assertEquals(20, round.getMinimumRaiseAmount(actor, 20));
    }

    @Test
    void forcedBlindsPreferSettlingOverRunout() {
        Player activeOne = new Player("One", "1", 100);
        Player activeTwo = new Player("Two", "2", 100);
        Player allIn = new Player("AllIn", "3", 20);
        allIn.doAction(PlayerAction.ALL_IN, 0);

        BettingRound round = new BettingRound();
        assertEquals(BettingRound.ForcedBlindProgression.HAND_OVER,
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

        BettingRound completedRound = new BettingRound(0, true, Set.of("a", "b"), 0, Map.of());
        assertEquals(TurnOutcomeType.SHOWDOWN, completedRound.nextProgression(players, false, GamePhase.RIVER));
        assertEquals(TurnOutcomeType.PHASE_ADVANCED, completedRound.nextProgression(players, false, GamePhase.FLOP));
        assertEquals(TurnOutcomeType.SHOWDOWN, completedRound.nextProgression(players, true, GamePhase.FLOP));

        BettingRound openRound = new BettingRound(0, false, Set.of("a"), 0, Map.of());
        assertEquals(TurnOutcomeType.NEXT_PLAYER, openRound.nextProgression(players, false, GamePhase.FLOP));
    }

    @Test
    void initialTurnIsMarkedByAnExplicitStepAndProgressionOnlyReports() {
        Player alice = new Player("Alice", "a", 100);
        Player bob = new Player("Bob", "b", 100);
        List<Player> players = List.of(alice, bob);
        BettingRound round = new BettingRound(0, false, Set.of("a", "b"), 0, Map.of());

        round.markInitialTurnsIfAllActed(players);
        assertTrue(round.hasAllPlayersHadInitialTurn());
        assertEquals(TurnOutcomeType.PHASE_ADVANCED, round.nextProgression(players, false, GamePhase.FLOP));

        BettingRound pending = new BettingRound(0, false, Set.of("a"), 0, Map.of());
        pending.markInitialTurnsIfAllActed(players);
        assertFalse(pending.hasAllPlayersHadInitialTurn());
    }

    @Test
    void foldedPlayersDoNotPreventRemainingActorsFromCompletingAction() {
        Player actor = new Player("Actor", "a", 100);
        Player opponent = new Player("Opponent", "b", 100);
        Player folded = new Player("Folded", "f", 100);
        folded.doAction(PlayerAction.FOLD, 0);

        BettingRound round = new BettingRound(0, true, Set.of("a", "b"), 0, Map.of());
        assertEquals(TurnOutcomeType.PHASE_ADVANCED,
                round.nextProgression(List.of(actor, opponent, folded), false, GamePhase.FLOP));

        BettingRound onlyFoldedActed = new BettingRound(0, false, Set.of("f"), 0, Map.of());
        assertEquals(TurnOutcomeType.NEXT_PLAYER,
                onlyFoldedActed.nextProgression(List.of(actor, opponent, folded), false, GamePhase.FLOP));
    }

    @Test
    void resetsRoundAndHandStateCleanly() {
        Player blindPoster = new Player("Blind", "b", 100);
        blindPoster.doAction(PlayerAction.CALL, 20);
        BettingRound round = new BettingRound(20, true, Set.of("a"), 40, Map.of("a", 20));

        round.resetForNewRound(List.of(blindPoster));
        assertEquals(0, blindPoster.getCurrentBet());
        assertEquals(0, round.getCurrentHighestBet());
        assertFalse(round.hasAllPlayersHadInitialTurn());
        assertTrue(round.getActedPlayerIdsSnapshot().isEmpty());
        assertEquals(Map.of(), round.getLastActedWagerByPlayerSnapshot());

        round.resetForNewHand();
        assertFalse(round.hasAllPlayersHadInitialTurn());
        assertEquals(0, round.getLastFullRaise());
    }

    // ---- One rule set: legalActionsFor and prepareDecision must agree ----

    private record Scenario(Player actor, List<Player> players, BettingRound round, List<PlayerAction> expectedLegal) {
    }

    private static BettingRound roundOnFlop(
            int highestBet, int lastFullRaise, Set<String> acted, Map<String, Integer> lastActedWagers) {
        return new BettingRound(highestBet, false, acted, lastFullRaise, lastActedWagers);
    }

    private static List<PlayerAction> legalOnFlop(BettingRound round, Player actor, List<Player> players) {
        return round.legalActionsFor(actor, players, players, new TableSeating(players, players), GamePhase.FLOP, 20);
    }

    private static BettingRound.DecisionResult prepareOnFlop(
            BettingRound round, Player actor, List<Player> players, PlayerAction action, int amount) {
        return round.prepareDecision(actor, new PlayerDecision(action, amount, actor.getPlayerId()),
                players, 20);
    }

    private static Player playerWhoPosted(String name, String id, int chips, int posted) {
        Player player = new Player(name, id, chips);
        player.doAction(PlayerAction.BET, posted);
        return player;
    }

    private static Player allInPlayer(String name, String id, int chips) {
        Player player = new Player(name, id, chips);
        player.doAction(PlayerAction.ALL_IN, 0);
        return player;
    }

    private static Arguments scenario(
            String name, Player actor, List<Player> players, BettingRound round, PlayerAction... expectedLegal) {
        return Arguments.of(name, new Scenario(actor, players, round, List.of(expectedLegal)));
    }

    private static Stream<Arguments> pinnedBettingStates() {
        Player a = new Player("Alice", "a", 100);
        Player b = new Player("Bob", "b", 100);

        Player shortStack = new Player("Alice", "a", 10);
        Player exactStack = new Player("Alice", "a", 20);
        Player bigBlind = playerWhoPosted("Alice", "a", 100, 20);
        Player openedForTwenty = playerWhoPosted("Alice", "a", 100, 20);
        Player reopened = playerWhoPosted("Alice", "a", 100, 20);
        Player belowMinRaise = new Player("Alice", "a", 90);
        Player foldedActor = new Player("Alice", "a", 100);
        foldedActor.doAction(PlayerAction.FOLD, 0);

        return Stream.of(
                scenario("opening: nothing bet", a, List.of(a, b), new BettingRound(),
                        PlayerAction.FOLD, PlayerAction.CHECK, PlayerAction.BET, PlayerAction.ALL_IN),
                scenario("facing a bet", a, List.of(a, b), roundOnFlop(20, 0, Set.of(), Map.of()),
                        PlayerAction.FOLD, PlayerAction.CALL, PlayerAction.RAISE, PlayerAction.ALL_IN),
                scenario("call exceeds stack", shortStack, List.of(shortStack, b),
                        roundOnFlop(20, 0, Set.of(), Map.of()),
                        PlayerAction.FOLD, PlayerAction.CALL, PlayerAction.ALL_IN),
                scenario("stack equals call", exactStack, List.of(exactStack, b),
                        roundOnFlop(20, 0, Set.of(), Map.of()),
                        PlayerAction.FOLD, PlayerAction.CALL, PlayerAction.ALL_IN),
                scenario("big blind option", bigBlind, List.of(bigBlind, b),
                        roundOnFlop(20, 0, Set.of(), Map.of()),
                        PlayerAction.FOLD, PlayerAction.CHECK, PlayerAction.RAISE, PlayerAction.ALL_IN),
                scenario("short all-in raise leaves betting closed", openedForTwenty, List.of(openedForTwenty, b),
                        roundOnFlop(30, 0, Set.of("a"), Map.of("a", 20)),
                        PlayerAction.FOLD, PlayerAction.CALL),
                scenario("full raise reopens betting", reopened, List.of(reopened, b),
                        roundOnFlop(40, 0, Set.of("a"), Map.of("a", 20)),
                        PlayerAction.FOLD, PlayerAction.CALL, PlayerAction.RAISE, PlayerAction.ALL_IN),
                scenario("stack below the minimum raise", belowMinRaise, List.of(belowMinRaise, b),
                        roundOnFlop(50, 50, Set.of(), Map.of()),
                        PlayerAction.FOLD, PlayerAction.CALL, PlayerAction.RAISE, PlayerAction.ALL_IN),
                scenario("folded player", foldedActor, List.of(foldedActor, b), new BettingRound()));
    }

    private static Stream<Arguments> pinnedBettingStatesWithUncontestableOpponent() {
        Player callerOnly = new Player("Alice", "a", 100);
        Player checkerOnly = playerWhoPosted("Alice", "a", 100, 20);
        return Stream.of(
                scenario("only opponent is all-in, call owed", callerOnly,
                        List.of(callerOnly, allInPlayer("AllIn", "ai", 20)),
                        roundOnFlop(20, 0, Set.of(), Map.of()),
                        PlayerAction.FOLD, PlayerAction.CALL),
                scenario("only opponent is all-in, nothing owed", checkerOnly,
                        List.of(checkerOnly, allInPlayer("AllIn", "ai", 20)),
                        roundOnFlop(20, 0, Set.of(), Map.of()),
                        PlayerAction.FOLD, PlayerAction.CHECK));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource({"pinnedBettingStates", "pinnedBettingStatesWithUncontestableOpponent"})
    void everyOfferedActionIsAcceptedAndEveryOtherActionIsRejected(String name, Scenario state) {
        List<PlayerAction> legal = legalOnFlop(state.round(), state.actor(), state.players());
        assertEquals(state.expectedLegal(), legal, name);

        for (PlayerAction action : PlayerAction.values()) {
            assertEquals(legal.contains(action), isAcceptedForSomeAmount(state, action),
                    name + ": " + action + " offered=" + legal.contains(action));
        }
    }

    private static boolean isAcceptedForSomeAmount(Scenario state, PlayerAction action) {
        boolean wagers = action == PlayerAction.BET || action == PlayerAction.RAISE;
        int maxAmount = wagers ? state.actor().getChips() : 0;
        for (int amount = wagers ? 1 : 0; amount <= maxAmount; amount++) {
            try {
                prepareOnFlop(state.round(), state.actor(), state.players(), action, amount);
                return true;
            } catch (ActionFeedbackException rejected) {
                // try the next amount
            }
        }
        return false;
    }

    @Test
    void raiseIsAcceptedAtTheExactMinimumAndRejectedBelowIt() {
        Player actor = new Player("Actor", "a", 100);
        List<Player> players = List.of(actor, new Player("Opponent", "b", 100));
        BettingRound round = roundOnFlop(20, 0, Set.of(), Map.of());

        assertEquals(40, round.getMinimumRaiseAmount(actor, 20));
        assertEquals(PlayerAction.RAISE, prepareOnFlop(round, actor, players, PlayerAction.RAISE, 40).decision().action());
        assertEquals(40, prepareOnFlop(round, actor, players, PlayerAction.RAISE, 40).amount());
        BadRequestException tooSmall = assertThrows(BadRequestException.class,
                () -> prepareOnFlop(round, actor, players, PlayerAction.RAISE, 39));
        assertEquals("MIN_RAISE", tooSmall.actionErrorCode());
    }

    @Test
    void shortAllInsAreAcceptedEvenWhenTheyAreNotAFullRaiseOrBet() {
        Player belowMinRaise = new Player("Actor", "a", 90);
        List<Player> players = List.of(belowMinRaise, new Player("Opponent", "b", 100));
        BettingRound facingFifty = roundOnFlop(50, 50, Set.of(), Map.of());

        assertEquals(PlayerAction.RAISE, prepareOnFlop(facingFifty, belowMinRaise, players, PlayerAction.RAISE, 90)
                .decision().action());
        assertEquals(PlayerAction.ALL_IN, prepareOnFlop(facingFifty, belowMinRaise, players, PlayerAction.ALL_IN, 0)
                .decision().action());

        Player tinyStack = new Player("Tiny", "t", 15);
        List<Player> tinyTable = List.of(tinyStack, new Player("Opponent", "b", 100));
        BettingRound opening = new BettingRound();
        assertEquals(15, prepareOnFlop(opening, tinyStack, tinyTable, PlayerAction.BET, 15).amount());
        BadRequestException belowBigBlind = assertThrows(BadRequestException.class,
                () -> prepareOnFlop(opening, tinyStack, tinyTable, PlayerAction.BET, 10));
        assertEquals("MIN_BET", belowBigBlind.actionErrorCode());
    }

    @Test
    void shortRaiseKeepsTheMinimumRaiseButFullRaiseChangesItUntilTheStreetEnds() {
        Player raiser = new Player("Raiser", "a", 200);
        Player other = new Player("Other", "b", 200);
        List<Player> players = List.of(raiser, other);
        BettingRound round = roundOnFlop(20, 0, Set.of(), Map.of());

        raiser.doAction(PlayerAction.RAISE, 30);
        round.recordAppliedDecision(raiser, 20, 20);
        assertEquals(30, round.getCurrentHighestBet());
        assertEquals(50, round.getMinimumRaiseAmount(other, 20));

        raiser.doAction(PlayerAction.RAISE, 40);
        round.recordAppliedDecision(raiser, 20, 30);
        assertEquals(70, round.getCurrentHighestBet());
        assertEquals(110, round.getMinimumRaiseAmount(other, 20));

        round.resetForNewRound(players);
        assertEquals(20, round.getMinimumRaiseAmount(other, 20));
    }
}
