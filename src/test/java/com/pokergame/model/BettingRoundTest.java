package com.pokergame.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pokergame.enums.GamePhase;
import com.pokergame.enums.PlayerAction;
import com.pokergame.exception.ActionFeedbackException;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.TurnOutcome.TurnOutcomeType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Unit tests for {@link BettingRound}. */
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
                shortRaiseRound.validateDecision(alice, PlayerAction.RAISE, 60,
                        players, 20));
        assertEquals("RAISE_NOT_REOPENED", error.actionErrorCode());

        BettingRound fullRaiseRound = new BettingRound(
                40, false, Set.of("a"), 0, Map.of("a", 20));
        assertTrue(fullRaiseRound.legalActionsFor(alice, players, players, seating, GamePhase.FLOP, 20)
                .contains(PlayerAction.RAISE));
        var validated = fullRaiseRound.validateDecision(alice, PlayerAction.RAISE, 60,
                players, 20);
        assertEquals(PlayerAction.RAISE, validated.action());
    }

    @Test
    void shortStackAllInAllowedWhenRaisesClosedAndDoesNotExceedHighestBet() {
        Player shortAlice = playerWhoPosted("Alice", "a", 30, 20);
        Player bob = new Player("Bob", "b", 100);
        List<Player> players = List.of(shortAlice, bob);
        TableSeating seating = new TableSeating(players, players);

        BettingRound shortRaiseRound = new BettingRound(
                30, false, Set.of("a"), 0, Map.of("a", 20));

        assertEquals(List.of(PlayerAction.FOLD, PlayerAction.ALL_IN),
                shortRaiseRound.legalActionsFor(shortAlice, players, players, seating, GamePhase.FLOP, 20));
        var allInResult = shortRaiseRound.validateDecision(shortAlice, PlayerAction.ALL_IN, 0, players, 20);
        assertEquals(PlayerAction.ALL_IN, allInResult.action());

        Player deeperAlice = playerWhoPosted("Alice", "a", 31, 20);
        List<Player> deeperPlayers = List.of(deeperAlice, bob);
        ActionFeedbackException error = assertThrows(ActionFeedbackException.class, () ->
                shortRaiseRound.validateDecision(deeperAlice, PlayerAction.ALL_IN, 0, deeperPlayers, 20));
        assertEquals("RAISE_NOT_REOPENED", error.actionErrorCode());
    }

    @Test
    void legalActionsReflectCallCheckContestableRaiseAndShowdownOrNonMemberGuards() {
        Player alice = new Player("Alice", "a", 100);
        Player bob = new Player("Bob", "b", 100);
        Player outsider = new Player("Outsider", "x", 100);
        List<Player> players = List.of(alice, bob);
        TableSeating seating = new TableSeating(players, players);

        BettingRound checkedPot = new BettingRound();
        assertEquals(List.of(PlayerAction.FOLD, PlayerAction.CHECK, PlayerAction.BET, PlayerAction.ALL_IN),
                checkedPot.legalActionsFor(alice, players, players, seating, GamePhase.FLOP, 20));
        assertEquals(List.of(),
                checkedPot.legalActionsFor(alice, players, players, seating, GamePhase.SHOWDOWN, 20));
        assertEquals(List.of(),
                checkedPot.legalActionsFor(outsider, players, players, seating, GamePhase.FLOP, 20));

        checkedPot.setHighestBet(20);
        assertEquals(List.of(PlayerAction.FOLD, PlayerAction.CALL, PlayerAction.RAISE, PlayerAction.ALL_IN),
                checkedPot.legalActionsFor(alice, players, players, seating, GamePhase.FLOP, 20));
        assertEquals(List.of(),
                checkedPot.legalActionsFor(bob, players, players, seating, GamePhase.FLOP, 20));
    }

    @Test
    void normalPartialCallComputesExactDifferenceAndShortCallIsConvertedToAllIn() {
        Player partialAlice = playerWhoPosted("Alice", "a", 100, 10);
        Player bob = new Player("Bob", "b", 100);
        BettingRound round = new BettingRound(30, false, Set.of(), 0, Map.of());

        var normalCall = round.validateDecision(partialAlice, PlayerAction.CALL, 0,
                List.of(partialAlice, bob), 20);
        assertEquals(PlayerAction.CALL, normalCall.action());
        assertEquals(20, normalCall.amount());
        assertNull(normalCall.playerActionNotice());

        Player shortAlice = new Player("Alice", "a", 10);
        var shortCall = round.validateDecision(shortAlice, PlayerAction.CALL, 0,
                List.of(shortAlice, bob), 20);
        assertEquals(PlayerAction.ALL_IN, shortCall.action());
        assertEquals(0, shortCall.amount());
        assertNotNull(shortCall.playerActionNotice());
        assertEquals(10, shortAlice.getChips());
    }

    @Test
    void allInAndFoldedPlayersDoNotBlockAutoAdvanceButAnOwedCallDoes() {
        Player alice = new Player("Alice", "a", 100);
        Player bob = new Player("Bob", "b", 20);
        bob.doAction(PlayerAction.ALL_IN, 0);
        Player carol = new Player("Carol", "c", 100);
        carol.doAction(PlayerAction.FOLD, 0);
        List<Player> players = List.of(alice, bob, carol);
        BettingRound round = new BettingRound(20, true, Set.of(), 0, Map.of());

        assertTrue(BettingRound.anyPlayerNeedsToCall(players, 20));
        assertEquals(TurnOutcomeType.NEXT_PLAYER, round.nextProgression(players, false, GamePhase.PRE_FLOP));
        assertEquals(TurnOutcomeType.NEXT_PLAYER, round.nextProgression(players, false, GamePhase.FLOP));

        alice.doAction(PlayerAction.CALL, 20);
        round.recordAppliedDecision(alice, 20, 0);

        assertFalse(BettingRound.anyPlayerNeedsToCall(players, 20));
        assertEquals(TurnOutcomeType.AUTO_ADVANCING, round.nextProgression(players, false, GamePhase.PRE_FLOP));
        assertEquals(TurnOutcomeType.AUTO_ADVANCING, round.nextProgression(players, false, GamePhase.FLOP));
    }

    @Test
    void removalCapsBetWhenAtMostOneActionableRemainsAndPreservesOrResetsOtherwise() {
        Player alice = new Player("Alice", "a", 100);
        Player bob = new Player("Bob", "b", 20);
        bob.doAction(PlayerAction.ALL_IN, 0);
        Player carol = new Player("Carol", "c", 100);

        BettingRound twoActionableRound = new BettingRound(50, false, Set.of("d"), 0, Map.of("d", 50));
        twoActionableRound.removePlayer("d");
        twoActionableRound.recalculateHighestBetAfterRemoval(List.of(alice, bob, carol));
        assertEquals(50, twoActionableRound.getCurrentHighestBet());

        List<Player> oneActionable = List.of(alice, bob);
        BettingRound round = new BettingRound(50, false, Set.of("d"), 0, Map.of("d", 50));
        round.removePlayer("d");
        round.recalculateHighestBetAfterRemoval(oneActionable);

        assertEquals(20, round.getCurrentHighestBet());
        assertFalse(round.getActedPlayerIdsSnapshot().contains("d"));
        assertFalse(round.getPreviousBetsSnapshot().containsKey("d"));
        assertEquals(TurnOutcomeType.NEXT_PLAYER, round.nextProgression(oneActionable, false, GamePhase.FLOP));

        alice.doAction(PlayerAction.CALL, 20);
        round.recordAppliedDecision(alice, 20, 0);
        assertEquals(TurnOutcomeType.AUTO_ADVANCING, round.nextProgression(oneActionable, false, GamePhase.FLOP));

        Player folded = new Player("Folded", "f", 100);
        folded.doAction(PlayerAction.FOLD, 0);
        round.recalculateHighestBetAfterRemoval(List.of(folded));
        assertEquals(0, round.getCurrentHighestBet());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("playerFacingRuleErrors")
    void pinsExactExceptionTypeAndCodeForAllPlayerFacingRuleErrors(
            String scenario,
            Class<? extends ActionFeedbackException> expectedType,
            String expectedCode,
            Player actor,
            PlayerAction action,
            int amount,
            List<Player> activePlayers,
            int highestBet,
            Map<String, Integer> previousBets) {
        BettingRound round = new BettingRound(highestBet, false, Set.of(), 0, previousBets);
        ActionFeedbackException error = assertThrows(expectedType, () ->
                round.validateDecision(actor, action, amount, activePlayers, 20),
                scenario);
        assertEquals(expectedCode, error.actionErrorCode(), scenario);
    }

    /** The concise message is what the player reads in the action dock, so it says what to do instead. */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("playerFacingRuleMessages")
    void explainsEachRejectedBettingDecisionInPlainWords(
            String scenario, PlayerAction action, int amount, int highestBet, String expectedMessage) {
        Player actor = new Player("Actor", "a", 100);
        Player opponent = new Player("Opponent", "b", 100);
        BettingRound round = new BettingRound(highestBet, false, Set.of(), 0, Map.of());
        ActionFeedbackException error = assertThrows(ActionFeedbackException.class, () ->
                round.validateDecision(actor, action, amount, List.of(actor, opponent), 20), scenario);
        assertEquals(expectedMessage, error.actionErrorMessage(), scenario);
    }

    private static Stream<Arguments> playerFacingRuleMessages() {
        return Stream.of(
                Arguments.of("check with a bet owed", PlayerAction.CHECK, 0, 20,
                        "You can't check: there's 20 to call. Call, raise or fold."),
                Arguments.of("call with nothing owed", PlayerAction.CALL, 0, 0,
                        "There's nothing to call. Check instead."),
                Arguments.of("bet into an active bet", PlayerAction.BET, 40, 20,
                        "There's already a bet of 20. Call or raise instead."),
                Arguments.of("raise with no bet to raise", PlayerAction.RAISE, 40, 0,
                        "There's no bet to raise. Bet instead."),
                Arguments.of("bet below the big blind", PlayerAction.BET, 10, 0,
                        "The minimum bet is 20 chips (you entered 10)."),
                Arguments.of("bet more than the stack", PlayerAction.BET, 101, 0,
                        "You only have 100 chips (you entered 101)."));
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
                        null, PlayerAction.FOLD, 0, twoEligible, 0, Map.of()),
                Arguments.of("folded player cannot act", UnauthorisedActionException.class, "ACTION_NOT_AVAILABLE",
                        foldedActor, PlayerAction.CHECK, 0,
                        List.of(foldedActor, opponent), 0, Map.of()),
                Arguments.of("all-in player cannot act", UnauthorisedActionException.class, "ACTION_NOT_AVAILABLE",
                        allInActor, PlayerAction.CHECK, 0,
                        List.of(allInActor, opponent), 0, Map.of()),
                Arguments.of("call with nothing owed", UnauthorisedActionException.class, "CALL_NOT_AVAILABLE",
                        eligibleActor, PlayerAction.CALL, 0, twoEligible, 0, Map.of()),
                Arguments.of("raise with no active bet", UnauthorisedActionException.class, "RAISE_NOT_AVAILABLE",
                        eligibleActor, PlayerAction.RAISE, 40, twoEligible, 0, Map.of()),
                Arguments.of("uncontestable bet", UnauthorisedActionException.class, "BET_NOT_AVAILABLE",
                        eligibleActor, PlayerAction.BET, 20,
                        List.of(eligibleActor), 0, Map.of()),
                Arguments.of("uncontestable raise", UnauthorisedActionException.class, "RAISE_NOT_AVAILABLE",
                        eligibleActor, PlayerAction.RAISE, 40,
                        List.of(eligibleActor, allInOpponent), 20, Map.of()),
                Arguments.of("uncontestable all-in exceeding highest bet", UnauthorisedActionException.class, "RAISE_NOT_AVAILABLE",
                        eligibleActor, PlayerAction.ALL_IN, 0,
                        List.of(eligibleActor, allInOpponent), 20, Map.of()),
                Arguments.of("raise not reopened", UnauthorisedActionException.class, "RAISE_NOT_REOPENED",
                        eligibleActor, PlayerAction.RAISE, 50,
                        twoEligible, 25, Map.of("a", 20)),
                Arguments.of("all-in not reopened", UnauthorisedActionException.class, "RAISE_NOT_REOPENED",
                        eligibleActor, PlayerAction.ALL_IN, 0,
                        twoEligible, 25, Map.of("a", 20)),
                Arguments.of("raise amount zero", BadRequestException.class, "RAISE_AMOUNT_REQUIRED",
                        eligibleActor, PlayerAction.RAISE, 0, twoEligible, 20, Map.of()),
                Arguments.of("raise exceeds chips", BadRequestException.class, "INSUFFICIENT_CHIPS",
                        eligibleActor, PlayerAction.RAISE, 101, twoEligible, 20, Map.of()),
                Arguments.of("raise total not above highest bet", BadRequestException.class, "MIN_RAISE",
                        eligibleActor, PlayerAction.RAISE, 10, twoEligible, 20, Map.of()),
                Arguments.of("raise increment below last full raise", BadRequestException.class, "MIN_RAISE",
                        eligibleActor, PlayerAction.RAISE, 30, twoEligible, 20, Map.of()),
                Arguments.of("bet when active bet exists", UnauthorisedActionException.class, "BET_NOT_AVAILABLE",
                        eligibleActor, PlayerAction.BET, 20, twoEligible, 20, Map.of()),
                Arguments.of("bet amount zero", BadRequestException.class, "BET_AMOUNT_REQUIRED",
                        eligibleActor, PlayerAction.BET, 0, twoEligible, 0, Map.of()),
                Arguments.of("bet exceeds chips", BadRequestException.class, "INSUFFICIENT_CHIPS",
                        eligibleActor, PlayerAction.BET, 101, twoEligible, 0, Map.of()),
                Arguments.of("bet below big blind", BadRequestException.class, "MIN_BET",
                        eligibleActor, PlayerAction.BET, 10, twoEligible, 0, Map.of()),
                Arguments.of("check with active bet owed", UnauthorisedActionException.class, "CHECK_NOT_AVAILABLE",
                        eligibleActor, PlayerAction.CHECK, 0, twoEligible, 20, Map.of()));
    }

    @Test
    void fullRaiseHistoryIsScopedToTheBettingRound() {
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
        assertEquals(20, recordedRound.getMinimumRaiseAmount(actor, 20));
    }

    @Test
    void preFlopProgressionPrefersShowdownOverAutoAdvance() {
        Player activeOne = new Player("One", "1", 100);
        Player activeTwo = new Player("Two", "2", 100);
        Player allIn = new Player("AllIn", "3", 20);
        allIn.doAction(PlayerAction.ALL_IN, 0);

        BettingRound round = new BettingRound();
        assertEquals(TurnOutcomeType.SHOWDOWN,
                round.nextProgression(List.of(activeOne), true, GamePhase.PRE_FLOP));
        assertEquals(TurnOutcomeType.AUTO_ADVANCING,
                round.nextProgression(List.of(activeOne, allIn), false, GamePhase.PRE_FLOP));
        assertEquals(TurnOutcomeType.NEXT_PLAYER,
                round.nextProgression(List.of(activeOne, activeTwo), false, GamePhase.PRE_FLOP));
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
        assertEquals(Map.of(), round.getPreviousBetsSnapshot());

        round.resetForNewHand();
        assertFalse(round.hasAllPlayersHadInitialTurn());
        assertEquals(0, round.getLastFullRaise());
    }

    private record Scenario(Player actor, List<Player> players, BettingRound round, List<PlayerAction> expectedLegal) {
    }

    private static BettingRound roundOnFlop(
            int highestBet, int lastFullRaise, Set<String> acted, Map<String, Integer> previousBets) {
        return new BettingRound(highestBet, false, acted, lastFullRaise, previousBets);
    }

    private static List<PlayerAction> legalOnFlop(BettingRound round, Player actor, List<Player> players) {
        return round.legalActionsFor(actor, players, players, new TableSeating(players, players), GamePhase.FLOP, 20);
    }

    private static BettingRound.ValidatedDecision validateOnFlop(
            BettingRound round, Player actor, List<Player> players, PlayerAction action, int amount) {
        return round.validateDecision(actor, action, amount,
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
                        PlayerAction.FOLD, PlayerAction.ALL_IN),
                scenario("stack equals call", exactStack, List.of(exactStack, b),
                        roundOnFlop(20, 0, Set.of(), Map.of()),
                        PlayerAction.FOLD, PlayerAction.ALL_IN),
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
            boolean legacyAllInCallBackstop = action == PlayerAction.CALL
                    && !legal.isEmpty()
                    && !legal.contains(PlayerAction.CHECK)
                    && state.actor().getChips() <= state.round().getCurrentHighestBet() - state.actor().getCurrentBet();
            boolean expectedAccepted = legal.contains(action) || legacyAllInCallBackstop;
            assertEquals(expectedAccepted, isAcceptedForSomeAmount(state, action),
                    name + ": " + action + " offered=" + legal.contains(action));
        }
    }

    private static boolean isAcceptedForSomeAmount(Scenario state, PlayerAction action) {
        boolean wagers = action == PlayerAction.BET || action == PlayerAction.RAISE;
        int maxAmount = wagers ? state.actor().getChips() : 0;
        for (int amount = wagers ? 1 : 0; amount <= maxAmount; amount++) {
            try {
                validateOnFlop(state.round(), state.actor(), state.players(), action, amount);
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
        assertEquals(PlayerAction.RAISE, validateOnFlop(round, actor, players, PlayerAction.RAISE, 40).action());
        assertEquals(40, validateOnFlop(round, actor, players, PlayerAction.RAISE, 40).amount());
        BadRequestException tooSmall = assertThrows(BadRequestException.class,
                () -> validateOnFlop(round, actor, players, PlayerAction.RAISE, 39));
        assertEquals("MIN_RAISE", tooSmall.actionErrorCode());
    }

    @Test
    void shortAllInsAreAcceptedEvenWhenTheyAreNotAFullRaiseOrBet() {
        Player belowMinRaise = new Player("Actor", "a", 90);
        List<Player> players = List.of(belowMinRaise, new Player("Opponent", "b", 100));
        BettingRound facingFifty = roundOnFlop(50, 50, Set.of(), Map.of());

        assertEquals(PlayerAction.RAISE, validateOnFlop(facingFifty, belowMinRaise, players, PlayerAction.RAISE, 90)
                .action());
        assertEquals(PlayerAction.ALL_IN, validateOnFlop(facingFifty, belowMinRaise, players, PlayerAction.ALL_IN, 0)
                .action());

        Player tinyStack = new Player("Tiny", "t", 15);
        List<Player> tinyTable = List.of(tinyStack, new Player("Opponent", "b", 100));
        BettingRound opening = new BettingRound();
        assertEquals(15, validateOnFlop(opening, tinyStack, tinyTable, PlayerAction.BET, 15).amount());
        BadRequestException belowBigBlind = assertThrows(BadRequestException.class,
                () -> validateOnFlop(opening, tinyStack, tinyTable, PlayerAction.BET, 10));
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

    @Test
    void shortAndExactStackLegalActionsOmitCallAndKeepAllInWithShortConversionNotice() {
        Player shortStack = playerWhoPosted("Short", "s", 10, 0);
        Player exactStack = playerWhoPosted("Exact", "e", 30, 0);
        Player deeper = playerWhoPosted("Deep", "d", 31, 0);
        List<Player> players = List.of(shortStack, exactStack, deeper);
        TableSeating seating = new TableSeating(players, players);
        BettingRound round = roundOnFlop(30, 0, Set.of(), Map.of());

        assertEquals(List.of(PlayerAction.FOLD, PlayerAction.ALL_IN),
                round.legalActionsFor(shortStack, players, players, seating, GamePhase.FLOP, 20));

        List<Player> exactFirst = List.of(exactStack, shortStack, deeper);
        TableSeating exactSeating = new TableSeating(exactFirst, exactFirst);
        assertEquals(List.of(PlayerAction.FOLD, PlayerAction.ALL_IN),
                round.legalActionsFor(exactStack, exactFirst, exactFirst, exactSeating, GamePhase.FLOP, 20));

        List<Player> deeperFirst = List.of(deeper, shortStack, exactStack);
        TableSeating deeperSeating = new TableSeating(deeperFirst, deeperFirst);
        assertEquals(List.of(PlayerAction.FOLD, PlayerAction.CALL, PlayerAction.RAISE, PlayerAction.ALL_IN),
                round.legalActionsFor(deeper, deeperFirst, deeperFirst, deeperSeating, GamePhase.FLOP, 20));

        var converted = round.validateDecision(shortStack, PlayerAction.CALL, 0, players, 20);
        assertEquals(PlayerAction.ALL_IN, converted.action());
        assertEquals("Not enough chips to call — you're all in.", converted.playerActionNotice());
    }
}
