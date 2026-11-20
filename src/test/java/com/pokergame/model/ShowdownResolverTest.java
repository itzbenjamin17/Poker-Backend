package com.pokergame.model;

import com.pokergame.enums.HandRank;
import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;
import com.pokergame.model.PotCalculator.PotAllocation;
import com.pokergame.model.PotCalculator.PotAllocations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the package-private ShowdownResolver collaborator. */
@Tag("unit")
@DisplayName("ShowdownResolver collaborator")
class ShowdownResolverTest {

    private static final String GAME_ID = "showdown-test";

    private static final List<Card> FLUSH_HAND = List.of(
            new Card(Rank.TWO, Suit.SPADES),
            new Card(Rank.FIVE, Suit.SPADES),
            new Card(Rank.SEVEN, Suit.SPADES),
            new Card(Rank.JACK, Suit.SPADES),
            new Card(Rank.ACE, Suit.SPADES));

    private static final List<Card> STRAIGHT_HAND = List.of(
            new Card(Rank.FOUR, Suit.SPADES),
            new Card(Rank.FIVE, Suit.HEARTS),
            new Card(Rank.SIX, Suit.DIAMONDS),
            new Card(Rank.SEVEN, Suit.CLUBS),
            new Card(Rank.EIGHT, Suit.SPADES));

    private static final List<Card> PAIR_ACES_HAND = List.of(
            new Card(Rank.TWO, Suit.SPADES),
            new Card(Rank.FIVE, Suit.CLUBS),
            new Card(Rank.SEVEN, Suit.DIAMONDS),
            new Card(Rank.ACE, Suit.HEARTS),
            new Card(Rank.ACE, Suit.SPADES));

    private static final List<Card> PAIR_TWOS_HAND = List.of(
            new Card(Rank.TWO, Suit.SPADES),
            new Card(Rank.TWO, Suit.HEARTS),
            new Card(Rank.FIVE, Suit.CLUBS),
            new Card(Rank.SEVEN, Suit.DIAMONDS),
            new Card(Rank.KING, Suit.CLUBS));

    private static Player player(String id, int chips, HandRank rank) {
        return player(id, chips, rank, defaultBestHandFor(rank));
    }

    private static Player player(String id, int chips, HandRank rank, List<Card> bestHand) {
        Player p = new Player(id, id, chips);
        p.setHandRank(rank);
        p.setBestHand(bestHand);
        return p;
    }

    private static List<Card> defaultBestHandFor(HandRank rank) {
        return switch (rank) {
            case FLUSH -> FLUSH_HAND;
            case STRAIGHT -> STRAIGHT_HAND;
            case ONE_PAIR -> PAIR_ACES_HAND;
            default -> List.of();
        };
    }

    @Test
    void determineWinners_EmptyList_ReturnsEmpty() {
        assertTrue(ShowdownResolver.determineWinners(List.of()).isEmpty());
    }

    @Test
    void determineWinners_HigherRankWins() {
        Player a = player("a", 0, HandRank.ONE_PAIR);
        Player b = player("b", 0, HandRank.FLUSH);

        assertEquals(List.of(b), ShowdownResolver.determineWinners(List.of(a, b)));
    }

    @Test
    void determineWinners_SameRankTie_ReturnsBoth() {
        Player a = player("a", 0, HandRank.FLUSH);
        Player b = player("b", 0, HandRank.FLUSH);

        assertEquals(2, ShowdownResolver.determineWinners(List.of(a, b)).size());
    }

    @Test
    void determineWinners_SameRankKicker_BetterHandWins() {
        Player a = player("a", 0, HandRank.ONE_PAIR, PAIR_ACES_HAND);
        Player b = player("b", 0, HandRank.ONE_PAIR, PAIR_TWOS_HAND);

        assertEquals(List.of(a), ShowdownResolver.determineWinners(List.of(a, b)));
    }

    @Test
    void determineWinners_ThreePlayersOfSameRank_BestKeptAndEqualHandsShareInSeatOrder() {
        Player weakest = player("a", 0, HandRank.ONE_PAIR, PAIR_TWOS_HAND);
        Player best = player("b", 0, HandRank.ONE_PAIR, PAIR_ACES_HAND);
        Player alsoBest = player("c", 0, HandRank.ONE_PAIR, PAIR_ACES_HAND);

        assertEquals(List.of(best, alsoBest),
                ShowdownResolver.determineWinners(List.of(weakest, best, alsoBest)));
    }

    @Test
    void distribute_SingleWinner_TakesWholePot() {
        Player a = player("a", 0, HandRank.FLUSH);
        Player b = player("b", 0, HandRank.ONE_PAIR);
        PotAllocations computation = new PotAllocations(
                List.of(new PotAllocation(200, List.of(a, b))), Map.of(), 0);

        ShowdownResolver.Distribution d = ShowdownResolver.distribute(
                GAME_ID, computation, List.of(a, b), List.of("a", "b"), "a", true);

        assertEquals(200, a.getChips());
        assertEquals(0, b.getChips());
        assertEquals(0, d.remainder());
        assertEquals(List.of(a), d.winners());
        assertEquals(200, d.payout().potWinnings().get("a"));
        assertTrue(d.payout().wentToShowdown());
    }

    @Test
    void distribute_OddChipSplit_AwardsOddChipsClockwiseFromDealer() {
        Player a = player("a", 0, HandRank.FLUSH);
        Player b = player("b", 0, HandRank.FLUSH);
        PotAllocations computation = new PotAllocations(
                List.of(new PotAllocation(101, List.of(a, b))), Map.of(), 0);

        ShowdownResolver.Distribution d = ShowdownResolver.distribute(
                GAME_ID, computation, List.of(a, b), List.of("a", "b"), "a", true);

        assertEquals(50, a.getChips());
        assertEquals(51, b.getChips());
        assertEquals(0, d.remainder());
        assertEquals(0, d.payout().remainder());
        assertEquals(Map.of("a", 50, "b", 51), d.payout().potWinnings());
    }

    @Test
    void distribute_ThreeWaySplitWithTwoOddChips_AwardsFirstTwoClockwiseFromDealer() {
        Player a = player("a", 0, HandRank.STRAIGHT);
        Player b = player("b", 0, HandRank.STRAIGHT);
        Player c = player("c", 0, HandRank.STRAIGHT);
        PotAllocations computation = new PotAllocations(
                List.of(new PotAllocation(101, List.of(a, b, c))), Map.of(), 0);

        ShowdownResolver.Distribution d = ShowdownResolver.distribute(
                GAME_ID, computation, List.of(a, b, c), List.of("a", "b", "c"), "b", true);

        assertEquals(34, c.getChips());
        assertEquals(34, a.getChips());
        assertEquals(33, b.getChips());
        assertEquals(0, d.remainder());
        assertEquals(0, d.payout().remainder());
        assertEquals(Map.of("a", 34, "b", 33, "c", 34), d.payout().potWinnings());
    }

    @Test
    void distribute_EmptyHandSeatOrder_FallsBackToPlayersOrder() {
        Player a = player("a", 0, HandRank.FLUSH);
        Player b = player("b", 0, HandRank.FLUSH);
        Player c = player("c", 0, HandRank.FLUSH);
        PotAllocations computation = new PotAllocations(
                List.of(new PotAllocation(101, List.of(a, b, c))), Map.of(), 0);

        ShowdownResolver.Distribution d = ShowdownResolver.distribute(
                GAME_ID, computation, List.of(a, b, c), List.of(), "a", true);

        assertEquals(34, b.getChips());
        assertEquals(34, c.getChips());
        assertEquals(33, a.getChips());
        assertEquals(0, d.remainder());
    }

    @Test
    void distribute_RefundsUncalledToActivePlayerOnly() {
        Player a = player("a", 0, HandRank.FLUSH);
        Player folded = Player.restoreBuilder().name("f").playerId("f").holeCards(List.of()).bestHand(List.of()).chips(0).hasFolded(true).handRank(HandRank.NO_HAND).build();
        Player out = Player.restoreBuilder().name("o").playerId("o").holeCards(List.of()).bestHand(List.of()).chips(0).out(true).handRank(HandRank.NO_HAND).build();
        PotAllocations computation = new PotAllocations(
                List.of(new PotAllocation(100, List.of(a))), Map.of("a", 40, "f", 30, "o", 20, "missing", 10), 100);

        ShowdownResolver.Distribution d = ShowdownResolver.distribute(
                GAME_ID, computation, List.of(a, folded, out), List.of("a", "f", "o"), "a", true);

        assertEquals(140, a.getChips());
        assertEquals(0, folded.getChips());
        assertEquals(0, out.getChips());
        assertEquals(Map.of("a", 40), d.payout().refunds());
    }

    @Test
    void distribute_AllocationWithNoEligiblePlayers_GoesToRemainder() {
        PotAllocations computation = new PotAllocations(
                List.of(new PotAllocation(75, List.of())), Map.of(), 0);

        ShowdownResolver.Distribution d = ShowdownResolver.distribute(
                GAME_ID, computation, List.of(), List.of(), null, true);

        assertEquals(75, d.remainder());
        assertTrue(d.winners().isEmpty());
    }

    @Test
    void evaluateHandsStoresEachPlayersOwnEvaluation() {
        Player a = player("a", 100, HandRank.NO_HAND);
        Player b = player("b", 100, HandRank.NO_HAND);
        a.setHoleCards(List.of(new Card(Rank.JACK, Suit.DIAMONDS), new Card(Rank.FOUR, Suit.DIAMONDS)));
        b.setHoleCards(List.of(new Card(Rank.KING, Suit.CLUBS), new Card(Rank.THREE, Suit.SPADES)));
        List<Card> board = List.of(
                new Card(Rank.TWO, Suit.HEARTS),
                new Card(Rank.FIVE, Suit.DIAMONDS),
                new Card(Rank.SEVEN, Suit.CLUBS),
                new Card(Rank.NINE, Suit.SPADES),
                new Card(Rank.JACK, Suit.HEARTS));

        ShowdownResolver.evaluateHands(board, List.of(a, b));

        assertEquals(HandRank.ONE_PAIR, a.getHandRank());
        assertEquals(List.of(
                new Card(Rank.FIVE, Suit.DIAMONDS),
                new Card(Rank.SEVEN, Suit.CLUBS),
                new Card(Rank.NINE, Suit.SPADES),
                new Card(Rank.JACK, Suit.DIAMONDS),
                new Card(Rank.JACK, Suit.HEARTS)), a.getBestHand());
        assertEquals(HandRank.HIGH_CARD, b.getHandRank());
        assertEquals(List.of(
                new Card(Rank.FIVE, Suit.DIAMONDS),
                new Card(Rank.SEVEN, Suit.CLUBS),
                new Card(Rank.NINE, Suit.SPADES),
                new Card(Rank.JACK, Suit.HEARTS),
                new Card(Rank.KING, Suit.CLUBS)), b.getBestHand());
    }

    @Test
    void distributesThreeWayMainPotAndTwoWaySidePotWithAnUncalledRefund() {
        Player a = player("a", 0, HandRank.FLUSH);
        Player b = player("b", 0, HandRank.FLUSH);
        Player c = player("c", 0, HandRank.FLUSH);
        var pots = new PotAllocations(List.of(
                new PotAllocation(91, List.of(a, b, c)),
                new PotAllocation(40, List.of(b, c))), Map.of("c", 12), 12);

        var distribution = ShowdownResolver.distribute(
                GAME_ID, pots, List.of(a, b, c), List.of("a", "b", "c"), "a", true);

        assertEquals(List.of(a, b, c), distribution.winners());
        assertEquals(Map.of("a", 30, "b", 51, "c", 50), distribution.payout().potWinnings());
        assertEquals(Map.of("c", 12), distribution.payout().refunds());
        assertEquals(62, c.getChips());
        assertEquals(0, distribution.remainder());
    }
}
