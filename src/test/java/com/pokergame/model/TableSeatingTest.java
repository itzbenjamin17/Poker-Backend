package com.pokergame.model;

import com.pokergame.enums.HandRank;
import com.pokergame.enums.PlayerAction;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TableSeatingTest {

    private static Player createPlayer(String id, String name, int chips) {
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
                .out(false)
                .disconnected(false)
                .readyForNextHand(false)
                .build();
    }

    private static List<Player> createPlayerList(int count) {
        List<Player> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(createPlayer("p" + i, "Player" + i, 1000));
        }
        return list;
    }

    private static List<String> seatOrderOf(List<Player> players) {
        return players.stream().map(Player::getPlayerId).toList();
    }

    @Nested
    class HeadsUpBlindsTests {

        @Test
        void initialHeadsUp_DealerAtSeatZero_DealerIsSmallBlindAndActsFirst() {
            List<Player> players = createPlayerList(2);
            TableSeating seating = new TableSeating(players, players);

            assertEquals(0, seating.getDealerPosition());
            assertEquals(0, seating.getSmallBlindPosition());
            assertEquals(1, seating.getBigBlindPosition());
            assertEquals(0, seating.getCurrentPlayerPosition());
            assertEquals("p0", seating.getDealerPlayerId());
        }

        @Test
        void advancePositions_HeadsUp_AlternatesDealerSmallBlindAndFirstActor() {
            List<Player> players = createPlayerList(2);
            TableSeating seating = new TableSeating(players, players);

            seating.advancePositions(players);
            assertEquals(1, seating.getDealerPosition());
            assertEquals(1, seating.getSmallBlindPosition());
            assertEquals(0, seating.getBigBlindPosition());
            assertEquals(1, seating.getCurrentPlayerPosition());
            assertEquals("p1", seating.getDealerPlayerId());

            seating.advancePositions(players);
            assertEquals(0, seating.getDealerPosition());
            assertEquals(0, seating.getSmallBlindPosition());
            assertEquals(1, seating.getBigBlindPosition());
            assertEquals(0, seating.getCurrentPlayerPosition());
            assertEquals("p0", seating.getDealerPlayerId());
        }

        @Test
        void advancePositions_TransitionFromThreePlayersToHeadsUp_AppliesHeadsUpRules() {
            List<Player> all = createPlayerList(3);
            TableSeating seating = new TableSeating(all, all);

            List<Player> active = new ArrayList<>(List.of(all.get(0), all.get(2)));
            seating.advancePositions(active);

            assertEquals(1, seating.getDealerPosition());
            assertEquals(1, seating.getSmallBlindPosition());
            assertEquals(0, seating.getBigBlindPosition());
            assertEquals(1, seating.getCurrentPlayerPosition());
            assertEquals("p2", seating.getDealerPlayerId());
        }
    }

    @Nested
    class MultiWayAndNormalizedBlindsTests {

        @ParameterizedTest
        @CsvSource({
                "2, 0, 0, 1, 0",
                "2, 1, 1, 0, 1",
                "3, 0, 1, 2, 0",
                "3, 1, 2, 0, 1",
                "3, 2, 0, 1, 2",
                "4, 0, 1, 2, 3",
                "6, 5, 0, 1, 2"
        })
        void advancePositions_MultiWayAndHeadsUp_SetsExpectedBlindsAndFirstActor(
                int tableSize, int targetDealer, int expectedSb, int expectedBb, int expectedCurrent) {
            List<Player> players = createPlayerList(tableSize);
            int priorDealer = Math.floorMod(targetDealer - 1, tableSize);
            TableSeating seating = new TableSeating(
                    priorDealer, 0, 0, 0, seatOrderOf(players),
                    players.get(priorDealer).getPlayerId(), null, null, players);

            seating.advancePositions(players);

            assertEquals(targetDealer, seating.getDealerPosition());
            assertEquals(expectedSb, seating.getSmallBlindPosition());
            assertEquals(expectedBb, seating.getBigBlindPosition());
            assertEquals(expectedCurrent, seating.getCurrentPlayerPosition());
            assertEquals(players.get(targetDealer).getPlayerId(), seating.getDealerPlayerId());
        }

        @Test
        void assignBlindsForHand_NormalizesOversizedAndNegativeBlindIndices() {
            List<Player> players = createPlayerList(3);
            TableSeating seating = new TableSeating(
                    0, 7, -1, 0, seatOrderOf(players), "p0", null, null, players);

            seating.assignBlindsForHand(players);

            assertEquals(1, seating.getSmallBlindPosition());
            assertEquals(2, seating.getBigBlindPosition());
            assertEquals("p1", seating.getSmallBlindPlayerId(players));
            assertEquals("p2", seating.getBigBlindPlayerId(players));
        }
    }

    @Nested
    class PositionRotationAndDealerAnchorTests {

        @Test
        void advancePositions_SkipsEliminatedPlayersAndWrapsAcrossSeatOrder() {
            List<Player> all = createPlayerList(4);
            all.get(1).setIsOut();
            all.get(2).setIsOut();
            List<Player> active = List.of(all.get(0), all.get(3));

            TableSeating seating = new TableSeating(
                    0, 0, 1, 0, seatOrderOf(all), "p0", null, null, all);
            seating.advancePositions(active);

            assertEquals(1, seating.getDealerPosition());
            assertEquals("p3", seating.getDealerPlayerId());

            seating.advancePositions(active);
            assertEquals(0, seating.getDealerPosition());
            assertEquals("p0", seating.getDealerPlayerId());
        }

        @Test
        void advancePositions_DepartedDealerAnchorAndFallbacks() {
            List<Player> all = createPlayerList(4);
            List<Player> activeTailDeparted = List.of(all.get(0), all.get(1), all.get(2));
            TableSeating tailDeparted = new TableSeating(
                    2, 0, 1, 0, seatOrderOf(all), "p3", null, null, all);

            tailDeparted.advancePositions(activeTailDeparted);
            assertEquals(0, tailDeparted.getDealerPosition());
            assertEquals("p0", tailDeparted.getDealerPlayerId());

            TableSeating missingAnchor = new TableSeating(
                    0, 0, 1, 0, seatOrderOf(all), "unknown", null, null, all);
            missingAnchor.advancePositions(activeTailDeparted);
            assertEquals(0, missingAnchor.getDealerPosition());
            assertEquals("p0", missingAnchor.getDealerPlayerId());

            assertDoesNotThrow(() -> missingAnchor.advancePositions(List.of()));
            assertDoesNotThrow(() -> missingAnchor.advancePositions(null));
        }

        @Test
        void moveToFirstPostFlopActor_SelectsClockwiseAfterDealerAnchorEvenIfDealerOrSbDeparted() {
            List<Player> all = createPlayerList(4);
            all.get(1).doAction(PlayerAction.FOLD, 0, 0);
            all.get(2).doAction(PlayerAction.ALL_IN, 0, 0);

            TableSeating standard = new TableSeating(
                    0, 1, 2, 3, seatOrderOf(all), "p0", "p1", "p2", all);
            standard.moveToFirstPostFlopActor(all);
            assertEquals(3, standard.getCurrentPlayerPosition());

            List<Player> dealerDepartedActive = List.of(all.get(1), all.get(2), all.get(3));
            standard.moveToFirstPostFlopActor(dealerDepartedActive);
            assertEquals(2, standard.getCurrentPlayerPosition());
            assertEquals("p3", standard.getCurrentPlayer(dealerDepartedActive).getPlayerId());

            List<Player> disjointOrderActive = createPlayerList(2);
            TableSeating disjoint = new TableSeating(
                    1, 0, 1, 1, List.of("x1", "x2", "x3"), "missing", null, null, disjointOrderActive);
            disjoint.moveToFirstPostFlopActor(disjointOrderActive);
            assertEquals(0, disjoint.getCurrentPlayerPosition());

            disjoint.moveToFirstPostFlopActor(List.of());
            assertEquals(0, disjoint.getCurrentPlayerPosition());
        }
    }

    @Nested
    class NextPlayerAndRemovalReconciliationTests {

        @Test
        void nextPlayer_SkipsFoldedAllInWrapsAndHandlesAllNonActionable() {
            List<Player> players = createPlayerList(4);
            players.get(1).doAction(PlayerAction.FOLD, 0, 0);
            players.get(2).doAction(PlayerAction.ALL_IN, 0, 0);
            TableSeating seating = new TableSeating(
                    0, 1, 2, 0, seatOrderOf(players), "p0", "p1", "p2", players);

            seating.nextPlayer(players);
            assertEquals(3, seating.getCurrentPlayerPosition());

            seating.nextPlayer(players);
            assertEquals(0, seating.getCurrentPlayerPosition());

            players.get(3).doAction(PlayerAction.FOLD, 0, 0);
            seating.nextPlayer(players);
            assertEquals(0, seating.getCurrentPlayerPosition());

            players.get(0).doAction(PlayerAction.FOLD, 0, 0);
            seating.nextPlayer(players);
            assertEquals(0, seating.getCurrentPlayerPosition());
        }

        @Test
        void nextPlayer_ExtremeIndicesAndEmptyListsHandledSafely() {
            List<Player> active = createPlayerList(3);
            TableSeating negativeCur = new TableSeating(
                    0, 1, 2, -100, seatOrderOf(active), "p0", null, null, active);
            negativeCur.nextPlayer(active);
            assertTrue(negativeCur.getCurrentPlayerPosition() >= 0 && negativeCur.getCurrentPlayerPosition() < 3);

            TableSeating hugeCur = new TableSeating(
                    0, 1, 2, 10000, seatOrderOf(active), "p0", null, null, active);
            hugeCur.nextPlayer(active);
            assertTrue(hugeCur.getCurrentPlayerPosition() >= 0 && hugeCur.getCurrentPlayerPosition() < 3);

            hugeCur.nextPlayer(List.of());
            assertEquals(0, hugeCur.getCurrentPlayerPosition());
            assertThrows(IllegalStateException.class, () -> hugeCur.getCurrentPlayer(List.of()));
        }

        @Test
        void removePlayer_ReconcilesCurrentActorDealerAnchorAndBlindIds() {
            List<Player> all = createPlayerList(4);
            List<Player> table = new ArrayList<>(all);
            List<Player> active = new ArrayList<>(all);
            TableSeating seating = new TableSeating(
                    1, 2, 3, 2, seatOrderOf(all), "p1", "p2", "p3", table);
            seating.assignBlindsForHand(active);

            seating.removePlayer(table, active, all.get(0));
            assertEquals("p2", seating.getCurrentPlayer(active).getPlayerId());
            assertEquals("p1", active.get(seating.getDealerPosition()).getPlayerId());

            all.get(3).doAction(PlayerAction.FOLD, 0, 0);
            seating.removePlayer(table, active, all.get(2));
            assertEquals("p1", seating.getCurrentPlayer(active).getPlayerId());
            assertEquals("p2", seating.getSmallBlindPlayerId(active));
            assertEquals("p3", seating.getBigBlindPlayerId(active));

            seating.removePlayer(table, active, null);
            seating.removePlayer(table, active, createPlayer("missing", "Missing", 100));
            assertEquals(2, active.size());

            seating.removePlayer(table, active, all.get(1));
            seating.removePlayer(table, active, all.get(3));
            assertTrue(active.isEmpty());
            assertEquals(0, seating.getDealerPosition());
            assertEquals(0, seating.getSmallBlindPosition());
            assertEquals(0, seating.getBigBlindPosition());
            assertEquals(0, seating.getCurrentPlayerPosition());
        }
    }

    @Nested
    class ExhaustiveAndMonteCarloInvariantsTests {

        @Test
        void exhaustiveTableSizes2To10_AllDealerPositions_InvariantsHold() {
            for (int size = 2; size <= 10; size++) {
                List<Player> players = createPlayerList(size);
                for (int targetDealer = 0; targetDealer < size; targetDealer++) {
                    int priorDealer = Math.floorMod(targetDealer - 1, size);
                    TableSeating seating = new TableSeating(
                            priorDealer, 0, 0, 0, seatOrderOf(players),
                            players.get(priorDealer).getPlayerId(), null, null, players);
                    seating.advancePositions(players);

                    int dealer = seating.getDealerPosition();
                    int sb = seating.getSmallBlindPosition();
                    int bb = seating.getBigBlindPosition();
                    int cur = seating.getCurrentPlayerPosition();

                    assertEquals(targetDealer, dealer);
                    if (size == 2) {
                        assertEquals(dealer, sb);
                        assertEquals((dealer + 1) % 2, bb);
                        assertEquals(dealer, cur);
                    } else {
                        assertEquals((dealer + 1) % size, sb);
                        assertEquals((sb + 1) % size, bb);
                        assertEquals((bb + 1) % size, cur);
                    }
                }
            }
        }

        @ParameterizedTest
        @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 10})
        void cyclicAdvancement_EveryPlayerReceivesButtonOncePerCycle(int tableSize) {
            List<Player> players = createPlayerList(tableSize);
            TableSeating seating = new TableSeating(players, players);
            Set<String> seenDealers = new HashSet<>();

            for (int hand = 0; hand < tableSize; hand++) {
                seenDealers.add(seating.getDealerPlayerId());
                seating.advancePositions(players);
            }
            assertEquals(tableSize, seenDealers.size());
        }

        @Test
        void successiveEliminations_From10DownTo1_InvariantsPreservedAtEveryStep() {
            List<Player> all = createPlayerList(10);
            List<Player> active = new ArrayList<>(all);
            TableSeating seating = new TableSeating(all, all);

            while (active.size() > 1) {
                Player eliminated = active.remove(active.size() / 2);
                eliminated.setIsOut();
                seating.advancePositions(active);

                int size = active.size();
                int dealer = seating.getDealerPosition();
                int sb = seating.getSmallBlindPosition();
                int bb = seating.getBigBlindPosition();
                int cur = seating.getCurrentPlayerPosition();

                assertTrue(dealer >= 0 && dealer < size);
                assertEquals(active.get(dealer).getPlayerId(), seating.getDealerPlayerId());
                if (size == 2) {
                    assertEquals(dealer, sb);
                    assertEquals((dealer + 1) % 2, bb);
                    assertEquals(dealer, cur);
                } else {
                    assertEquals((dealer + 1) % size, sb);
                    assertEquals((sb + 1) % size, bb);
                    assertEquals((bb + 1) % size, cur);
                }
            }
        }

        @Test
        void nextPlayer_ExhaustiveFoldedAllInBitmasks_NeverHangsAndFindsNextActionable() {
            for (int tableSize = 2; tableSize <= 6; tableSize++) {
                int totalStates = (int) Math.pow(3, tableSize);
                int sampleStep = tableSize <= 4 ? 1 : 3;

                for (int stateMask = 0; stateMask < totalStates; stateMask += sampleStep) {
                    int temp = stateMask;
                    List<Player> players = new ArrayList<>();
                    boolean[] actionable = new boolean[tableSize];

                    for (int i = 0; i < tableSize; i++) {
                        int mode = temp % 3;
                        temp /= 3;
                        Player p = createPlayer("p" + i, "Player_" + i, mode == 2 ? 0 : 1000);
                        if (mode == 1) {
                            p.doAction(PlayerAction.FOLD, 0, 0);
                        } else if (mode == 2) {
                            p.doAction(PlayerAction.ALL_IN, 0, 0);
                        } else {
                            actionable[i] = true;
                        }
                        players.add(p);
                    }

                    for (int current = 0; current < tableSize; current++) {
                        TableSeating seating = new TableSeating(
                                0, 0, Math.min(1, tableSize - 1), current,
                                seatOrderOf(players), "p0", null, null, players);
                        seating.nextPlayer(players);
                        int next = seating.getCurrentPlayerPosition();
                        assertTrue(next >= 0 && next < tableSize);

                        boolean hasOtherActionable = false;
                        for (int i = 0; i < tableSize; i++) {
                            if (i != current && actionable[i]) {
                                hasOtherActionable = true;
                                break;
                            }
                        }

                        if (hasOtherActionable) {
                            int expected = (current + 1) % tableSize;
                            while (!actionable[expected]) {
                                expected = (expected + 1) % tableSize;
                            }
                            assertEquals(expected, next);
                        } else {
                            assertTrue(next == current || !actionable[next]);
                        }
                    }
                }
            }
        }

        @Test
        void monteCarloRandomizedTournaments_AllInvariantsHoldThroughEveryHand() {
            Random random = new Random(42_1337);
            for (int tourney = 1; tourney <= 200; tourney++) {
                List<Player> allPlayers = createPlayerList(10);
                List<Player> active = new ArrayList<>(allPlayers);
                String initialAnchor = "p" + random.nextInt(10);
                TableSeating seating = new TableSeating(
                        0, 1, 2, 3, seatOrderOf(allPlayers), initialAnchor, null, null, allPlayers);

                int handCount = 0;
                while (active.size() > 1 && handCount < 100) {
                    handCount++;
                    seating.advancePositions(active);

                    int size = active.size();
                    int dealer = seating.getDealerPosition();
                    int sb = seating.getSmallBlindPosition();
                    int bb = seating.getBigBlindPosition();
                    int cur = seating.getCurrentPlayerPosition();

                    assertTrue(dealer >= 0 && dealer < size);
                    assertEquals(active.get(dealer).getPlayerId(), seating.getDealerPlayerId());

                    if (size == 2) {
                        assertEquals(dealer, sb);
                        assertEquals((dealer + 1) % 2, bb);
                        assertEquals(dealer, cur);
                    } else {
                        assertEquals((dealer + 1) % size, sb);
                        assertEquals((sb + 1) % size, bb);
                        assertEquals((bb + 1) % size, cur);
                    }

                    if (random.nextDouble() < 0.35 && active.size() > 2) {
                        Player eliminated = active.remove(random.nextInt(active.size()));
                        eliminated.setIsOut();
                    } else if (active.size() == 2 && random.nextDouble() < 0.15) {
                        Player loser = active.remove(random.nextInt(2));
                        loser.setIsOut();
                    }
                }
                assertTrue(active.size() >= 1);
            }
        }
    }
}
