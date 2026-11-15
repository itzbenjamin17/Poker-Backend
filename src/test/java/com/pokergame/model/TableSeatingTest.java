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

    private static int indexOfId(List<Player> active, String playerId) {
        for (int i = 0; i < active.size(); i++) {
            if (active.get(i).getPlayerId().equals(playerId)) {
                return i;
            }
        }
        return -1;
    }

    private static int dealerIndex(TableSeating seating, List<Player> active) {
        return indexOfId(active, seating.getDealerPlayerId());
    }

    private static int sbIndex(TableSeating seating, List<Player> active) {
        return indexOfId(active, seating.getSmallBlindPlayerId(active));
    }

    private static int bbIndex(TableSeating seating, List<Player> active) {
        return indexOfId(active, seating.getBigBlindPlayerId(active));
    }

    private static int curIndex(TableSeating seating, List<Player> active) {
        return indexOfId(active, seating.getCurrentPlayerId());
    }

    private static void removeFromTable(List<Player> table, List<Player> active, TableSeating seating, Player player) {
        String playerId = player == null ? null : player.getPlayerId();
        if (playerId != null) {
            table.removeIf(p -> p.getPlayerId().equals(playerId));
            active.removeIf(p -> p.getPlayerId().equals(playerId));
        }
        seating.removePlayer(active, playerId);
    }

    @Nested
    class HeadsUpBlindsTests {

        @Test
        void initialHeadsUp_DealerAtSeatZero_DealerIsSmallBlindAndActsFirst() {
            List<Player> players = createPlayerList(2);
            TableSeating seating = new TableSeating(players, players);

            assertEquals("p0", seating.getDealerPlayerId());
            assertEquals("p0", seating.getSmallBlindPlayerId(players));
            assertEquals("p1", seating.getBigBlindPlayerId(players));
            assertEquals("p0", seating.getCurrentPlayerId());
            assertEquals("p0", seating.getCurrentPlayer(players).getPlayerId());
        }

        @Test
        void advancePositions_HeadsUp_AlternatesDealerSmallBlindAndFirstActor() {
            List<Player> players = createPlayerList(2);
            TableSeating seating = new TableSeating(players, players);

            seating.advancePositions(players);
            assertEquals("p1", seating.getDealerPlayerId());
            assertEquals("p1", seating.getSmallBlindPlayerId(players));
            assertEquals("p0", seating.getBigBlindPlayerId(players));
            assertEquals("p1", seating.getCurrentPlayerId());

            seating.advancePositions(players);
            assertEquals("p0", seating.getDealerPlayerId());
            assertEquals("p0", seating.getSmallBlindPlayerId(players));
            assertEquals("p1", seating.getBigBlindPlayerId(players));
            assertEquals("p0", seating.getCurrentPlayerId());
        }

        @Test
        void advancePositions_TransitionFromThreePlayersToHeadsUp_AppliesHeadsUpRules() {
            List<Player> all = createPlayerList(3);
            TableSeating seating = new TableSeating(all, all);

            List<Player> active = new ArrayList<>(List.of(all.get(0), all.get(2)));
            seating.advancePositions(active);

            assertEquals("p2", seating.getDealerPlayerId());
            assertEquals("p2", seating.getSmallBlindPlayerId(active));
            assertEquals("p0", seating.getBigBlindPlayerId(active));
            assertEquals("p2", seating.getCurrentPlayerId());
        }

        @Test
        void advancePositions_ThreeToHeadsUpWhenDealerEliminated_AssignsNextClockwiseAsDealerAndSmallBlind() {
            List<Player> all = createPlayerList(3);
            TableSeating seating = new TableSeating(all, all);

            all.get(0).setIsOut();
            List<Player> active = new ArrayList<>(List.of(all.get(1), all.get(2)));
            seating.advancePositions(active);

            assertEquals("p1", seating.getDealerPlayerId());
            assertEquals("p1", seating.getSmallBlindPlayerId(active));
            assertEquals("p2", seating.getBigBlindPlayerId(active));
            assertEquals("p1", seating.getCurrentPlayer(active).getPlayerId());
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
                    seatOrderOf(players), players.get(priorDealer).getPlayerId(), null, null, null, players);

            seating.advancePositions(players);

            assertEquals(targetDealer, dealerIndex(seating, players));
            assertEquals(expectedSb, sbIndex(seating, players));
            assertEquals(expectedBb, bbIndex(seating, players));
            assertEquals(expectedCurrent, curIndex(seating, players));
            assertEquals(players.get(targetDealer).getPlayerId(), seating.getDealerPlayerId());
        }

        @Test
        void assignBlindsForHand_OverwritesStaleBlindsFromDealerAnchor() {
            List<Player> players = createPlayerList(3);
            TableSeating seating = new TableSeating(
                    seatOrderOf(players), "p0", "stale-sb", "stale-bb", "p0", players);

            seating.assignBlindsForHand(players);

            assertEquals("p1", seating.getSmallBlindPlayerId(players));
            assertEquals("p2", seating.getBigBlindPlayerId(players));
        }

        @Test
        void deriveBlinds_DepartedDealerInHandSeatOrder_AssignsClockwiseActiveSeats() {
            List<Player> all = createPlayerList(4);
            List<Player> active = List.of(all.get(0), all.get(1), all.get(3));
            TableSeating seating = new TableSeating(
                    seatOrderOf(all), "p2", null, null, "p3", all);

            assertEquals("p3", seating.getSmallBlindPlayerId(active));
            assertEquals("p0", seating.getBigBlindPlayerId(active));

            List<Player> twoActive = List.of(all.get(0), all.get(3));
            TableSeating threeToTwo = new TableSeating(
                    List.of("p0", "p2", "p3"), "p2", null, null, "p3", List.of(all.get(0), all.get(2), all.get(3)));

            assertEquals("p3", threeToTwo.getSmallBlindPlayerId(twoActive));
            assertEquals("p0", threeToTwo.getBigBlindPlayerId(twoActive));
        }

        @Test
        void deriveBlinds_UnresolvableDealer_ReturnsNullInsteadOfSeatZero() {
            List<Player> active = createPlayerList(3);
            TableSeating nullDealer = new TableSeating(
                    seatOrderOf(active), null, null, null, "p0", active);
            assertNull(nullDealer.getSmallBlindPlayerId(active));
            assertNull(nullDealer.getBigBlindPlayerId(active));

            TableSeating unknownDealer = new TableSeating(
                    seatOrderOf(active), "unknown", "stale-sb", "stale-bb", "p0", active);
            unknownDealer.assignBlindsForHand(active);
            assertNull(unknownDealer.getSmallBlindPlayerId(active));
            assertNull(unknownDealer.getBigBlindPlayerId(active));
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
                    seatOrderOf(all), "p0", null, null, "p0", all);
            seating.advancePositions(active);

            assertEquals("p3", seating.getDealerPlayerId());

            seating.advancePositions(active);
            assertEquals("p0", seating.getDealerPlayerId());
        }

        @Test
        void advancePositions_DepartedDealerAnchorAndFallbacks() {
            List<Player> all = createPlayerList(4);
            List<Player> activeTailDeparted = List.of(all.get(0), all.get(1), all.get(2));
            TableSeating tailDeparted = new TableSeating(
                    seatOrderOf(all), "p3", null, null, "p0", all);

            tailDeparted.advancePositions(activeTailDeparted);
            assertEquals("p0", tailDeparted.getDealerPlayerId());

            TableSeating missingAnchor = new TableSeating(
                    seatOrderOf(all), "unknown", null, null, "p0", all);
            missingAnchor.advancePositions(activeTailDeparted);
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
                    seatOrderOf(all), "p0", "p1", "p2", "p3", all);
            standard.moveToFirstPostFlopActor(all);
            assertEquals("p3", standard.getCurrentPlayerId());

            List<Player> dealerDepartedActive = List.of(all.get(1), all.get(2), all.get(3));
            standard.moveToFirstPostFlopActor(dealerDepartedActive);
            assertEquals("p3", standard.getCurrentPlayerId());
            assertEquals("p3", standard.getCurrentPlayer(dealerDepartedActive).getPlayerId());

            List<Player> disjointOrderActive = createPlayerList(2);
            TableSeating disjoint = new TableSeating(
                    List.of("x1", "x2", "x3"), "missing", null, null, "p1", disjointOrderActive);
            disjoint.moveToFirstPostFlopActor(disjointOrderActive);
            assertEquals("p0", disjoint.getCurrentPlayerId());

            disjoint.moveToFirstPostFlopActor(List.of());
            assertNull(disjoint.getCurrentPlayerId());
        }

        @Test
        void moveToFirstPostFlopActor_FallbackPrefersFirstActionableOverGetFirst() {
            List<Player> active = createPlayerList(3);
            active.get(0).doAction(PlayerAction.FOLD, 0, 0);
            TableSeating disjoint = new TableSeating(
                    List.of("x1", "x2"), "missing", null, null, "p0", active);

            disjoint.moveToFirstPostFlopActor(active);
            assertEquals("p1", disjoint.getCurrentPlayerId());
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
                    seatOrderOf(players), "p0", "p1", "p2", "p0", players);

            seating.nextPlayer(players);
            assertEquals("p3", seating.getCurrentPlayerId());

            seating.nextPlayer(players);
            assertEquals("p0", seating.getCurrentPlayerId());

            players.get(3).doAction(PlayerAction.FOLD, 0, 0);
            seating.nextPlayer(players);
            assertEquals("p0", seating.getCurrentPlayerId());

            players.get(0).doAction(PlayerAction.FOLD, 0, 0);
            seating.nextPlayer(players);
            assertEquals("p0", seating.getCurrentPlayerId());
        }

        @Test
        void nextPlayer_UnknownCurrentIdAndEmptyListsHandledSafely() {
            List<Player> active = createPlayerList(3);
            TableSeating unknownCur = new TableSeating(
                    seatOrderOf(active), "p0", null, null, "unknown", active);
            unknownCur.nextPlayer(active);
            assertTrue(curIndex(unknownCur, active) >= 0 && curIndex(unknownCur, active) < 3);

            TableSeating nullCur = new TableSeating(
                    seatOrderOf(active), "p0", null, null, null, active);
            nullCur.nextPlayer(active);
            assertTrue(curIndex(nullCur, active) >= 0 && curIndex(nullCur, active) < 3);

            nullCur.nextPlayer(List.of());
            assertThrows(IllegalStateException.class, () -> nullCur.getCurrentPlayer(List.of()));
        }

        @Test
        void nextPlayer_DepartedOrUnresolvableCurrentId_DoesNotSkipActionableSeatZero() {
            List<Player> all = createPlayerList(4);
            List<Player> tailDepartedActive = List.of(all.get(0), all.get(1), all.get(2));
            TableSeating tailDeparted = new TableSeating(
                    seatOrderOf(all), "p0", null, null, "p3", all);

            tailDeparted.nextPlayer(tailDepartedActive);
            assertEquals("p0", tailDeparted.getCurrentPlayerId());

            List<Player> midDepartedActive = new ArrayList<>(List.of(all.get(0), all.get(2), all.get(3)));
            midDepartedActive.get(1).doAction(PlayerAction.FOLD, 0, 0);
            TableSeating midDeparted = new TableSeating(
                    seatOrderOf(all), "p0", null, null, "p1", all);

            midDeparted.nextPlayer(midDepartedActive);
            assertEquals("p3", midDeparted.getCurrentPlayerId());

            List<Player> active = createPlayerList(3);
            TableSeating unknownCur = new TableSeating(
                    seatOrderOf(active), "p0", null, null, "unknown", active);
            unknownCur.nextPlayer(active);
            assertEquals("p0", unknownCur.getCurrentPlayerId());

            TableSeating nullCur = new TableSeating(
                    seatOrderOf(active), "p0", null, null, null, active);
            nullCur.nextPlayer(active);
            assertEquals("p0", nullCur.getCurrentPlayerId());

            active.get(0).doAction(PlayerAction.FOLD, 0, 0);
            TableSeating foldedSeatZero = new TableSeating(
                    seatOrderOf(active), "p0", null, null, "unknown", active);
            foldedSeatZero.nextPlayer(active);
            assertEquals("p1", foldedSeatZero.getCurrentPlayerId());
        }

        @Test
        void removePlayer_ReconcilesCurrentActorDealerAnchorAndBlindIdsWithoutMutatingLists() {
            List<Player> all = createPlayerList(4);
            List<Player> table = new ArrayList<>(all);
            List<Player> active = new ArrayList<>(all);
            TableSeating seating = new TableSeating(
                    seatOrderOf(all), "p1", "p2", "p3", "p2", table);
            seating.assignBlindsForHand(active);

            seating.removePlayer(active, "p0");
            assertEquals(4, active.size(), "TableSeating.removePlayer must not mutate activePlayers");

            removeFromTable(table, active, seating, all.get(0));
            assertEquals("p2", seating.getCurrentPlayer(active).getPlayerId());
            assertEquals("p1", seating.getDealerPlayerId());

            all.get(3).doAction(PlayerAction.FOLD, 0, 0);
            removeFromTable(table, active, seating, all.get(2));
            assertEquals("p1", seating.getCurrentPlayer(active).getPlayerId());
            assertEquals("p2", seating.getSmallBlindPlayerId(active));
            assertEquals("p3", seating.getBigBlindPlayerId(active));

            removeFromTable(table, active, seating, null);
            removeFromTable(table, active, seating, createPlayer("missing", "Missing", 100));
            assertEquals(2, active.size());

            removeFromTable(table, active, seating, all.get(1));
            removeFromTable(table, active, seating, all.get(3));
            assertTrue(active.isEmpty());
            assertNull(seating.getCurrentPlayerId());
        }

        @Test
        void nextPlayer_AndRemovePlayer_SkipOutPlayers() {
            List<Player> players = createPlayerList(4);
            players.get(1).setIsOut();
            TableSeating seating = new TableSeating(
                    seatOrderOf(players), "p0", "p1", "p2", "p0", players);

            seating.nextPlayer(players);
            assertEquals("p2", seating.getCurrentPlayerId());
            assertEquals("p2", seating.getCurrentPlayer(players).getPlayerId());

            List<Player> table = createPlayerList(3);
            List<Player> active = new ArrayList<>(table);
            active.get(1).setIsOut();
            TableSeating removalSeating = new TableSeating(
                    seatOrderOf(table), "p0", null, null, "p0", table);

            // Removing current actor p0 with p1 (isOut) next clockwise must skip p1 to p2.
            removeFromTable(table, active, removalSeating, table.get(0));
            assertEquals("p2", removalSeating.getCurrentPlayerId());
            assertEquals("p2", removalSeating.getCurrentPlayer(active).getPlayerId());
        }

        @Test
        void getters_DoNotMutateStoredIds() {
            List<Player> players = createPlayerList(3);
            TableSeating seating = new TableSeating(
                    seatOrderOf(players), "p0", null, null, "unknown", players);

            assertEquals("p0", seating.getCurrentPlayer(players).getPlayerId());
            assertEquals("p1", seating.getSmallBlindPlayerId(players));
            assertEquals("p2", seating.getBigBlindPlayerId(players));
            assertEquals("p0", seating.getDealerPlayerId());
            assertEquals("unknown", seating.getCurrentPlayerId());

            assertEquals("p0", seating.getCurrentPlayer(players).getPlayerId());
            assertEquals("p1", seating.getSmallBlindPlayerId(players));
            assertEquals("p2", seating.getBigBlindPlayerId(players));
            assertEquals("p0", seating.getDealerPlayerId());
            assertEquals("unknown", seating.getCurrentPlayerId());
        }

        @Test
        void removePlayer_WithNullBlindIds_DerivesBlindIdsWithinActivePlayers() {
            List<Player> table = createPlayerList(3);
            List<Player> active = new ArrayList<>(table);
            TableSeating seating = new TableSeating(
                    seatOrderOf(table), "p0", null, null, "p0", table);

            removeFromTable(table, active, seating, table.get(1));
            assertEquals(2, active.size());
            assertTrue(sbIndex(seating, active) >= 0 && sbIndex(seating, active) < active.size());
            assertTrue(bbIndex(seating, active) >= 0 && bbIndex(seating, active) < active.size());
        }

        @Test
        void removePlayer_MidHandDealerAndBlindDepartures_PreserveIdsAndCurrentActor() {
            List<Player> table = createPlayerList(4);
            List<Player> active = new ArrayList<>(table);
            Player p0 = table.get(0);
            Player p1 = table.get(1);
            Player p2 = table.get(2);
            TableSeating seating = new TableSeating(active, table);
            seating.assignBlindsForHand(active);

            assertEquals("p0", seating.getDealerPlayerId());
            assertEquals("p1", seating.getSmallBlindPlayerId(active));
            assertEquals("p2", seating.getBigBlindPlayerId(active));
            assertEquals("p3", seating.getCurrentPlayer(active).getPlayerId());

            removeFromTable(table, active, seating, p1); // SB departs
            assertEquals("p1", seating.getSmallBlindPlayerId(active));
            assertEquals("p2", seating.getBigBlindPlayerId(active));
            assertEquals("p3", seating.getCurrentPlayer(active).getPlayerId());

            removeFromTable(table, active, seating, p2); // BB departs
            assertEquals("p2", seating.getBigBlindPlayerId(active));
            assertEquals("p3", seating.getCurrentPlayer(active).getPlayerId());

            removeFromTable(table, active, seating, p0); // Dealer departs
            assertEquals("p0", seating.getDealerPlayerId());
            assertEquals("p3", seating.getCurrentPlayer(active).getPlayerId());
        }

        @Test
        void removePlayer_CurrentActorAtTailDepartsWhenSeatZeroIsOut_WrapsToFirstActionable() {
            List<Player> table = createPlayerList(4);
            table.get(0).setIsOut();
            List<Player> active = new ArrayList<>(table);
            TableSeating seating = new TableSeating(
                    seatOrderOf(table), "p0", "p1", "p2", "p3", table);

            removeFromTable(table, active, seating, table.get(3));
            assertEquals("p1", seating.getCurrentPlayer(active).getPlayerId());
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
                            seatOrderOf(players), players.get(priorDealer).getPlayerId(), null, null, null, players);
                    seating.advancePositions(players);

                    int dealer = dealerIndex(seating, players);
                    int sb = sbIndex(seating, players);
                    int bb = bbIndex(seating, players);
                    int cur = curIndex(seating, players);

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
                int dealer = dealerIndex(seating, active);
                int sb = sbIndex(seating, active);
                int bb = bbIndex(seating, active);
                int cur = curIndex(seating, active);

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
                                seatOrderOf(players), "p0", null, null, "p" + current, players);
                        seating.nextPlayer(players);
                        int next = curIndex(seating, players);
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
                        seatOrderOf(allPlayers), initialAnchor, null, null, null, allPlayers);

                int handCount = 0;
                while (active.size() > 1 && handCount < 100) {
                    handCount++;
                    seating.advancePositions(active);

                    int size = active.size();
                    int dealer = dealerIndex(seating, active);
                    int sb = sbIndex(seating, active);
                    int bb = bbIndex(seating, active);
                    int cur = curIndex(seating, active);

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
