package com.pokergame.model;

import com.pokergame.enums.HandRank;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ReadyCountdownStartOutcome;
import com.pokergame.enums.ReadyCountdownTimeoutOutcome;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.UnauthorisedActionException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ReadyGateTest {

    private static Player player(String id, String name, int chips) {
        return player(id, name, chips, false, false, false);
    }

    private static Player player(
            String id, String name, int chips, boolean out, boolean disconnected, boolean ready) {
        return Player.restoreBuilder()
                .playerId(id)
                .name(name)
                .chips(chips)
                .currentBet(0)
                .holeCards(List.of())
                .bestHand(List.of())
                .handRank(HandRank.NO_HAND)
                .hasFolded(false)
                .allIn(false)
                .out(out)
                .disconnected(disconnected)
                .readyForNextHand(ready)
                .build();
    }

    @Nested
    class EligibilityAndReadinessEvaluationTests {

        @Test
        void getReadyEligibleCount_AllActiveWithChips_ReturnsAll() {
            ReadyGate gate = new ReadyGate(List.of(
                    player("p1", "Alice", 1000),
                    player("p2", "Bob", 500),
                    player("p3", "Charlie", 1)));
            assertEquals(3, gate.getReadyEligibleCount());
        }

        @Test
        void getReadyEligibleCount_FiltersOutDisqualifiedPlayers() {
            List<Player> players = List.of(
                    player("p1", "Alice", 1000, false, false, true),
                    player("p2", "Bob", 1000, true, false, true),
                    player("p3", "Charlie", 0, false, false, true),
                    player("p4", "Eve", 1000, false, true, true),
                    player("p5", "Frank", 200, false, false, true));
            ReadyGate gate = new ReadyGate(players);

            assertEquals(2, gate.getReadyEligibleCount());
            assertTrue(gate.areAllEligiblePlayersReady());
        }

        @Test
        void areAllEligiblePlayersReady_FewerThanTwoEligible_ReturnsFalse() {
            assertFalse(new ReadyGate(List.of()).areAllEligiblePlayersReady());
            assertFalse(new ReadyGate(List.of(player("p1", "Solo", 1000, false, false, true)))
                    .areAllEligiblePlayersReady());
            assertFalse(new ReadyGate(List.of(
                    player("p1", "Alice", 1000, false, false, true),
                    player("p2", "Bob", 0, false, false, true)))
                    .areAllEligiblePlayersReady());
            assertFalse(new ReadyGate(List.of(
                    player("p1", "Alice", 1000, false, false, true),
                    player("p2", "Bob", 1000, false, true, true)))
                    .areAllEligiblePlayersReady());
            assertFalse(new ReadyGate(List.of(
                    player("p1", "Alice", 1000, false, false, true),
                    player("p2", "Bob", 1000, true, false, true)))
                    .areAllEligiblePlayersReady());
        }

        @Test
        void areAllEligiblePlayersReady_ThreeEligibleChecksAllReadyFlags() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "Bob", 1000, false, false, true);
            Player p3 = player("p3", "Charlie", 1000, false, false, false);
            ReadyGate gate = new ReadyGate(List.of(p1, p2, p3));

            assertFalse(gate.areAllEligiblePlayersReady());
            p3.setReadyForNextHand(true);
            assertTrue(gate.areAllEligiblePlayersReady());
        }

        @Test
        void dynamicEligibilityMutationsUpdateReadinessImmediately() {
            Player alice = player("p1", "Alice", 1000, false, false, true);
            Player bob = player("p2", "Bob", 1000, false, false, true);
            ReadyGate gate = new ReadyGate(List.of(alice, bob));
            assertTrue(gate.areAllEligiblePlayersReady());

            bob.setDisconnected(true);
            assertFalse(gate.areAllEligiblePlayersReady());
            bob.setDisconnected(false);
            assertTrue(gate.areAllEligiblePlayersReady());

            alice.doAction(PlayerAction.BET, 1000);
            assertFalse(gate.areAllEligiblePlayersReady());
            alice.addChips(500);
            assertTrue(gate.areAllEligiblePlayersReady());

            alice.setIsOut();
            assertFalse(gate.areAllEligiblePlayersReady());

            Player carol = player("p3", "Carol", 1000, false, true, true);
            ReadyGate threeWay = new ReadyGate(List.of(bob, carol));
            assertFalse(threeWay.areAllEligiblePlayersReady());
            carol.setReadyForNextHand(false);
            carol.setDisconnected(false);
            assertFalse(threeWay.areAllEligiblePlayersReady());
        }
    }

    @Nested
    class RestoreAndManualTransitionsTests {

        @ParameterizedTest
        @EnumSource(ReadyGateState.class)
        void restoreConstructor_ExplicitStatePreserved(ReadyGateState explicitState) {
            ReadyGate gate = new ReadyGate(List.of(), explicitState, 50_000L);
            assertEquals(explicitState, gate.getState());
            assertEquals(explicitState == ReadyGateState.COUNTING_DOWN, gate.isCountdownActive());
            assertEquals(50_000L, gate.getCountdownDeadlineEpochMs());
        }

        @Test
        void restoreConstructor_NullStateThrows() {
            assertThrows(NullPointerException.class, () -> new ReadyGate(List.of(), null, 50_000L));
        }

        @Test
        void closeCountdownAndWaitForPlayersResetStateProperly() {
            Player alice = player("p1", "Alice", 1000, false, false, true);
            Player bob = player("p2", "Bob", 1000, false, false, false);
            ReadyGate gate = new ReadyGate(List.of(alice, bob), ReadyGateState.COUNTING_DOWN, 99_999L);

            gate.waitForPlayers();
            assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, gate.getState());
            assertFalse(gate.isCountdownActive());
            assertNull(gate.getCountdownDeadlineEpochMs());
            assertFalse(alice.isReadyForNextHand());

            alice.setReadyForNextHand(true);
            gate.closeCountdown();
            assertEquals(ReadyGateState.CLOSED, gate.getState());
            assertFalse(gate.isCountdownActive());
            assertNull(gate.getCountdownDeadlineEpochMs());
            assertTrue(alice.isReadyForNextHand());
        }

        @Test
        void waitForPlayersHandlesEmptyRosterGracefully() {
            ReadyGate gate = new ReadyGate(List.of());
            assertDoesNotThrow(gate::waitForPlayers);
            assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, gate.getState());
        }
    }

    @Nested
    class SetPlayerReadyValidationTests {

        @Test
        void setPlayerReady_ValidatesCountdownActiveAndPlayerEligibility() {
            Player alice = player("p1", "Alice", 1000);
            Player bob = player("p2", "Bob", 0);
            Player disconnected = player("p3", "Carol", 1000, false, true, false);
            Player out = player("p4", "Dave", 1000, true, false, false);
            List<Player> roster = List.of(alice, bob, disconnected, out);

            ReadyGate closedGate = new ReadyGate(roster);
            assertThrows(UnauthorisedActionException.class, () -> closedGate.setPlayerReady("Alice", true));

            ReadyGate activeGate = new ReadyGate(roster, ReadyGateState.COUNTING_DOWN, 50_000L);
            activeGate.setPlayerReady("Alice", true);
            assertTrue(alice.isReadyForNextHand());
            activeGate.setPlayerReady("Alice", false);
            assertFalse(alice.isReadyForNextHand());

            assertThrows(UnauthorisedActionException.class, () -> activeGate.setPlayerReady("Unknown", true));
            assertThrows(UnauthorisedActionException.class, () -> activeGate.setPlayerReady("Dave", true));
            assertThrows(UnauthorisedActionException.class, () -> activeGate.setPlayerReady("Carol", true));
            assertThrows(UnauthorisedActionException.class, () -> activeGate.setPlayerReady("Carol", false));
            assertThrows(UnauthorisedActionException.class, () -> activeGate.setPlayerReady("Bob", true));
            assertThrows(UnauthorisedActionException.class, () -> activeGate.setPlayerReady("Bob", false));
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("playerFacingReadyRuleErrors")
        void setPlayerReady_PinsExactExceptionTypeAndMessageForAllRuleErrors(
                String scenario,
                ReadyGateState gateState,
                String playerName,
                boolean ready,
                String expectedMessage) {
            List<Player> roster = List.of(
                    player("p1", "Alice", 1000),
                    player("p2", "Bob", 0),
                    player("p3", "Carol", 1000, false, true, false),
                    player("p4", "Dave", 1000, true, false, false));
            ReadyGate gate = new ReadyGate(
                    roster,
                    gateState,
                    gateState == ReadyGateState.COUNTING_DOWN ? 50_000L : null);

            UnauthorisedActionException error = assertThrows(
                    UnauthorisedActionException.class,
                    () -> gate.setPlayerReady(playerName, ready),
                    scenario);
            assertEquals(expectedMessage, error.getMessage(), scenario);
            assertNull(error.actionErrorCode(), scenario);
        }

        private static Stream<Arguments> playerFacingReadyRuleErrors() {
            return Stream.of(
                    Arguments.of("closed gate ready=true", ReadyGateState.CLOSED, "Alice", true,
                            "Ready countdown is not active."),
                    Arguments.of("closed gate ready=false", ReadyGateState.CLOSED, "Alice", false,
                            "Ready countdown is not active."),
                    Arguments.of("waiting gate ready=true", ReadyGateState.WAITING_FOR_PLAYERS, "Alice", true,
                            "Ready countdown is not active."),
                    Arguments.of("unknown player", ReadyGateState.COUNTING_DOWN, "Unknown", true,
                            "You are no longer part of this game."),
                    Arguments.of("out player ready=true", ReadyGateState.COUNTING_DOWN, "Dave", true,
                            "You are out of this game."),
                    Arguments.of("out player ready=false", ReadyGateState.COUNTING_DOWN, "Dave", false,
                            "You are out of this game."),
                    Arguments.of("disconnected player ready=true", ReadyGateState.COUNTING_DOWN, "Carol", true,
                            "Reconnect before marking ready."),
                    Arguments.of("zero chips player ready=true", ReadyGateState.COUNTING_DOWN, "Bob", true,
                            "Players without chips cannot mark ready."),
                    Arguments.of("disconnected player ready=false", ReadyGateState.COUNTING_DOWN, "Carol", false,
                            "You are not eligible to change readiness."),
                    Arguments.of("zero chips player ready=false", ReadyGateState.COUNTING_DOWN, "Bob", false,
                            "You are not eligible to change readiness.")
            );
        }
    }

    @Nested
    class CountdownStateMachineTransitionTests {

        @Test
        void openOrWait_FewerThanTwoEligible_TransitionsToWaitingAndClearsTimer() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "Bob", 0);
            GameScheduling scheduling = new GameScheduling();
            scheduling.scheduleTask(ScheduledGameTask.READY_TIMEOUT, 40_000L);
            ReadyGate gate = new ReadyGate(List.of(p1, p2));

            ReadyCountdownStartOutcome outcome = gate.openOrWait(50_000L, scheduling);

            assertEquals(ReadyCountdownStartOutcome.WAITING_FOR_PLAYERS, outcome);
            assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, gate.getState());
            assertFalse(gate.isCountdownActive());
            assertNull(gate.getCountdownDeadlineEpochMs());
            assertFalse(p1.isReadyForNextHand());
            assertNull(scheduling.getScheduledTime(ScheduledGameTask.READY_TIMEOUT));
        }

        @Test
        void openOrWait_TwoEligible_OpensCountdownResetsFlagsAndSchedulesTimeout() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "Bob", 1000, false, false, true);
            GameScheduling scheduling = new GameScheduling();
            ReadyGate gate = new ReadyGate(List.of(p1, p2));

            ReadyCountdownStartOutcome outcome = gate.openOrWait(50_000L, scheduling);

            assertEquals(ReadyCountdownStartOutcome.OPENED, outcome);
            assertEquals(ReadyGateState.COUNTING_DOWN, gate.getState());
            assertTrue(gate.isCountdownActive());
            assertEquals(50_000L, gate.getCountdownDeadlineEpochMs());
            assertEquals(50_000L, scheduling.getScheduledTime(ScheduledGameTask.READY_TIMEOUT));
            assertFalse(p1.isReadyForNextHand());
            assertFalse(p2.isReadyForNextHand());
        }

        @Test
        void expireCountdown_StaleOrInactiveScenarios_ReturnIgnoreStale() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "Bob", 1000);
            GameScheduling scheduling = new GameScheduling();

            ReadyGate closed = new ReadyGate(List.of(p1, p2));
            assertEquals(ReadyCountdownTimeoutOutcome.IGNORE_STALE,
                    closed.expireCountdown(false, 1000L, scheduling));

            ReadyGate active = new ReadyGate(List.of(p1, p2), ReadyGateState.COUNTING_DOWN, 1000L);
            assertEquals(ReadyCountdownTimeoutOutcome.IGNORE_STALE,
                    active.expireCountdown(true, 1000L, scheduling));
            assertEquals(ReadyCountdownTimeoutOutcome.IGNORE_STALE,
                    active.expireCountdown(false, 2000L, scheduling));
        }

        @Test
        void expireCountdown_FewerThanTwoEligible_TransitionsToWaitingAndClearsTimer() {
            Player p1 = player("p1", "Alice", 1000, false, false, false);
            Player p2 = player("p2", "Bob", 0);
            GameScheduling scheduling = new GameScheduling();
            scheduling.scheduleTask(ScheduledGameTask.READY_TIMEOUT, 1000L);
            ReadyGate gate = new ReadyGate(List.of(p1, p2), ReadyGateState.COUNTING_DOWN, 1000L);

            ReadyCountdownTimeoutOutcome outcome = gate.expireCountdown(false, 1000L, scheduling);

            assertEquals(ReadyCountdownTimeoutOutcome.WAITING_FOR_PLAYERS, outcome);
            assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, gate.getState());
            assertFalse(gate.isCountdownActive());
            assertNull(gate.getCountdownDeadlineEpochMs());
            assertNull(scheduling.getScheduledTime(ScheduledGameTask.READY_TIMEOUT));
        }

        @Test
        void expireCountdown_ValidTimeout_ForcesReadyOnlyForEligiblePlayers() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "Bob", 800);
            Player pOut = player("p3", "Charlie", 0, true, false, false);
            GameScheduling scheduling = new GameScheduling();
            ReadyGate gate = new ReadyGate(List.of(p1, p2, pOut), ReadyGateState.COUNTING_DOWN, 1000L);

            ReadyCountdownTimeoutOutcome outcome = gate.expireCountdown(false, 1000L, scheduling);

            assertEquals(ReadyCountdownTimeoutOutcome.FORCED_READY, outcome);
            assertTrue(p1.isReadyForNextHand());
            assertTrue(p2.isReadyForNextHand());
            assertFalse(pOut.isReadyForNextHand());
            assertTrue(gate.areAllEligiblePlayersReady());
        }

        @Test
        void repairRecoveredGate_HandlesInactiveValidWaitingAndCountdownRepair() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "Bob", 1000);
            GameScheduling scheduling = new GameScheduling();

            ReadyGate closed = new ReadyGate(List.of(p1, p2));
            assertFalse(closed.repairRecoveredGate(5000L, scheduling));

            ReadyGate missingTimerOnly = new ReadyGate(
                    List.of(p1, p2), ReadyGateState.COUNTING_DOWN, 4000L);
            assertFalse(missingTimerOnly.repairRecoveredGate(5000L, scheduling));
            assertEquals(4000L, scheduling.getScheduledTime(ScheduledGameTask.READY_TIMEOUT));

            GameScheduling emptySched = new GameScheduling();
            ReadyGate needsWaiting = new ReadyGate(
                    List.of(p1, player("p3", "Zero", 0)), ReadyGateState.COUNTING_DOWN, null);
            assertTrue(needsWaiting.repairRecoveredGate(5000L, emptySched));
            assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, needsWaiting.getState());
            assertNull(emptySched.getScheduledTime(ScheduledGameTask.READY_TIMEOUT));

            ReadyGate needsDeadline = new ReadyGate(
                    List.of(p1, p2), ReadyGateState.COUNTING_DOWN, null);
            assertTrue(needsDeadline.repairRecoveredGate(5000L, emptySched));
            assertEquals(ReadyGateState.COUNTING_DOWN, needsDeadline.getState());
            assertEquals(5000L, needsDeadline.getCountdownDeadlineEpochMs());
            assertEquals(5000L, emptySched.getScheduledTime(ScheduledGameTask.READY_TIMEOUT));
        }
    }

    @Nested
    class AdversarialEligibilityAndForceReadyFuzzTests {

        @Test
        void massiveTable20PlayersAndEmptyIneligibleTablesWorkCleanly() {
            List<Player> twentyPlayers = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                twentyPlayers.add(player("p" + i, "Player" + i, i < 10 ? 1000 : 0, i % 4 == 0, i % 5 == 0, false));
            }
            ReadyGate gate = new ReadyGate(twentyPlayers, ReadyGateState.COUNTING_DOWN, 5000L);
            assertEquals(ReadyCountdownTimeoutOutcome.FORCED_READY,
                    gate.expireCountdown(false, 5000L, new GameScheduling()));
            for (Player p : twentyPlayers) {
                boolean eligible = !p.isOut() && !p.isDisconnected() && p.getChips() > 0;
                assertEquals(eligible, p.isReadyForNextHand());
            }

            List<Player> allIneligible = List.of(
                    player("a", "A", 0),
                    player("b", "B", 1000, true, false, false),
                    player("c", "C", 1000, false, true, false));
            ReadyGate blockedGate = new ReadyGate(allIneligible);
            assertEquals(0, blockedGate.getReadyEligibleCount());
            assertFalse(blockedGate.areAllEligiblePlayersReady());
        }

        @Test
        void forceReadyOnTimeoutDoesNotAlterIneligiblePlayersAcrossStatePermutations() {
            Random rng = new Random(42);
            for (int run = 0; run < 1000; run++) {
                int playerCount = rng.nextInt(1, 10);
                List<Player> roster = new ArrayList<>();
                int expectedEligibleCount = 0;
                boolean allExpectedReady = true;

                for (int p = 0; p < playerCount; p++) {
                    int chips = rng.nextBoolean() ? rng.nextInt(1, 10000) : 0;
                    boolean isOut = rng.nextInt(10) < 2;
                    boolean isDc = rng.nextInt(10) < 2;
                    boolean isReady = rng.nextBoolean();

                    roster.add(player("p_" + p, "Player" + p, chips, isOut, isDc, isReady));

                    boolean eligible = !isOut && !isDc && chips > 0;
                    if (eligible) {
                        expectedEligibleCount++;
                        if (!isReady) {
                            allExpectedReady = false;
                        }
                    }
                }

                ReadyGate gate = new ReadyGate(roster, ReadyGateState.COUNTING_DOWN, 10_000L);
                assertEquals(expectedEligibleCount, gate.getReadyEligibleCount());
                assertEquals(expectedEligibleCount >= 2 && allExpectedReady, gate.areAllEligiblePlayersReady());

                ReadyCountdownTimeoutOutcome timeoutOutcome =
                        gate.expireCountdown(false, 10_000L, new GameScheduling());
                if (expectedEligibleCount < 2) {
                    assertEquals(ReadyCountdownTimeoutOutcome.WAITING_FOR_PLAYERS, timeoutOutcome);
                    assertFalse(gate.areAllEligiblePlayersReady());
                } else {
                    assertEquals(ReadyCountdownTimeoutOutcome.FORCED_READY, timeoutOutcome);
                    assertTrue(gate.areAllEligiblePlayersReady());
                    for (Player pl : roster) {
                        boolean eligible = !pl.isOut() && !pl.isDisconnected() && pl.getChips() > 0;
                        if (eligible) {
                            assertTrue(pl.isReadyForNextHand());
                        }
                    }
                }
            }
        }
    }
}
