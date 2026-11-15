package com.pokergame.model;

import com.pokergame.enums.HandRank;
import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;
import com.pokergame.model.PotCalculator.PotAllocation;
import com.pokergame.model.PotCalculator.PotComputationResult;
import com.pokergame.service.HandEvaluatorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for the package-private ShowdownResolver collaborator. */
@Tag("unit")
@DisplayName("ShowdownResolver collaborator")
class ShowdownResolverTest {

    private static final String GAME_ID = "showdown-test";

    private final HandEvaluatorService evaluator = mock(HandEvaluatorService.class);

    private static Player player(String id, int chips, HandRank rank) {
        Player p = new Player(id, id, chips);
        p.setHandRank(rank);
        p.setBestHand(List.of());
        return p;
    }

    @Test
    void determineWinners_EmptyList_ReturnsEmpty() {
        assertTrue(ShowdownResolver.determineWinners(evaluator, List.of()).isEmpty());
    }

    @Test
    void determineWinners_HigherRankWins() {
        Player a = player("a", 0, HandRank.ONE_PAIR);
        Player b = player("b", 0, HandRank.FLUSH);

        assertEquals(List.of(b), ShowdownResolver.determineWinners(evaluator, List.of(a, b)));
    }

    @Test
    void determineWinners_SameRankTie_ReturnsBoth() {
        when(evaluator.isBetterHandOfSameRank(any(), any(), any())).thenReturn(false);
        Player a = player("a", 0, HandRank.FLUSH);
        Player b = player("b", 0, HandRank.FLUSH);

        assertEquals(2, ShowdownResolver.determineWinners(evaluator, List.of(a, b)).size());
    }

    @Test
    void determineWinners_SameRankKicker_BetterHandWins() {
        Player a = player("a", 0, HandRank.ONE_PAIR);
        Player b = player("b", 0, HandRank.ONE_PAIR);
        a.setBestHand(List.of(new Card(Rank.ACE, Suit.HEARTS)));
        b.setBestHand(List.of(new Card(Rank.TWO, Suit.SPADES)));
        when(evaluator.isBetterHandOfSameRank(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0) == a.getBestHand());

        assertEquals(List.of(a), ShowdownResolver.determineWinners(evaluator, List.of(a, b)));
    }

    @Test
    void distribute_SingleWinner_TakesWholePot() {
        Player a = player("a", 0, HandRank.FLUSH);
        Player b = player("b", 0, HandRank.ONE_PAIR);
        PotComputationResult computation = new PotComputationResult(
                List.of(new PotAllocation(200, List.of(a, b))), Map.of(), 0);

        ShowdownResolver.Distribution d = ShowdownResolver.distribute(
                GAME_ID, evaluator, computation, List.of(a, b), List.of("a", "b"), "a");

        assertEquals(200, a.getChips());
        assertEquals(0, b.getChips());
        assertEquals(0, d.remainder());
        assertEquals(List.of(a), d.winners());
        assertEquals(200, d.settlement().contestedAwards().get("a"));
        assertTrue(d.settlement().settled());
    }

    @Test
    void distribute_OddChipSplit_AwardsOddChipsClockwiseFromDealer() {
        when(evaluator.isBetterHandOfSameRank(any(), any(), any())).thenReturn(false);
        Player a = player("a", 0, HandRank.FLUSH);
        Player b = player("b", 0, HandRank.FLUSH);
        PotComputationResult computation = new PotComputationResult(
                List.of(new PotAllocation(101, List.of(a, b))), Map.of(), 0);

        ShowdownResolver.Distribution d = ShowdownResolver.distribute(
                GAME_ID, evaluator, computation, List.of(a, b), List.of("a", "b"), "a");

        assertEquals(50, a.getChips());
        assertEquals(51, b.getChips());
        assertEquals(0, d.remainder());
        assertEquals(0, d.settlement().remainder());
        assertEquals(Map.of("a", 50, "b", 51), d.settlement().contestedAwards());
    }

    @Test
    void distribute_ThreeWaySplitWithTwoOddChips_AwardsFirstTwoClockwiseFromDealer() {
        when(evaluator.isBetterHandOfSameRank(any(), any(), any())).thenReturn(false);
        Player a = player("a", 0, HandRank.STRAIGHT);
        Player b = player("b", 0, HandRank.STRAIGHT);
        Player c = player("c", 0, HandRank.STRAIGHT);
        PotComputationResult computation = new PotComputationResult(
                List.of(new PotAllocation(101, List.of(a, b, c))), Map.of(), 0);

        ShowdownResolver.Distribution d = ShowdownResolver.distribute(
                GAME_ID, evaluator, computation, List.of(a, b, c), List.of("a", "b", "c"), "b");

        assertEquals(34, c.getChips());
        assertEquals(34, a.getChips());
        assertEquals(33, b.getChips());
        assertEquals(0, d.remainder());
        assertEquals(0, d.settlement().remainder());
        assertEquals(Map.of("a", 34, "b", 33, "c", 34), d.settlement().contestedAwards());
    }

    @Test
    void distribute_EmptyHandSeatOrder_FallsBackToPlayersOrder() {
        when(evaluator.isBetterHandOfSameRank(any(), any(), any())).thenReturn(false);
        Player a = player("a", 0, HandRank.FLUSH);
        Player b = player("b", 0, HandRank.FLUSH);
        Player c = player("c", 0, HandRank.FLUSH);
        PotComputationResult computation = new PotComputationResult(
                List.of(new PotAllocation(101, List.of(a, b, c))), Map.of(), 0);

        ShowdownResolver.Distribution d = ShowdownResolver.distribute(
                GAME_ID, evaluator, computation, List.of(a, b, c), List.of(), "a");

        assertEquals(34, b.getChips());
        assertEquals(34, c.getChips());
        assertEquals(33, a.getChips());
        assertEquals(0, d.remainder());
    }

    @Test
    void distribute_RefundsUncalledToActivePlayerOnly() {
        Player a = player("a", 0, HandRank.FLUSH);
        Player folded = Player.restoreBuilder().name("f").playerId("f").holeCards(List.of()).bestHand(List.of()).chips(0).hasFolded(true).handRank(HandRank.NO_HAND).build();
        PotComputationResult computation = new PotComputationResult(
                List.of(new PotAllocation(100, List.of(a))), Map.of("a", 40, "f", 30), 70);

        ShowdownResolver.Distribution d = ShowdownResolver.distribute(
                GAME_ID, evaluator, computation, List.of(a, folded), List.of("a", "f"), "a");

        assertEquals(140, a.getChips());
        assertEquals(0, folded.getChips());
        assertEquals(Map.of("a", 40), d.settlement().refunds());
    }

    @Test
    void distribute_AllocationWithNoEligiblePlayers_GoesToRemainder() {
        PotComputationResult computation = new PotComputationResult(
                List.of(new PotAllocation(75, List.of())), Map.of(), 0);

        ShowdownResolver.Distribution d = ShowdownResolver.distribute(
                GAME_ID, evaluator, computation, List.of(), List.of(), null);

        assertEquals(75, d.remainder());
        assertTrue(d.winners().isEmpty());
    }

    @Test
    void evaluateHandsStoresEachPlayersOwnEvaluation() {
        Player a = player("a", 100, HandRank.NO_HAND);
        Player b = player("b", 100, HandRank.NO_HAND);
        List<Card> board = List.of(new Card(Rank.ACE, Suit.HEARTS));
        List<Card> pair = List.of(new Card(Rank.ACE, Suit.SPADES));
        List<Card> highCard = List.of(new Card(Rank.KING, Suit.SPADES));
        when(evaluator.getBestHand(eq(board), any())).thenReturn(
                new HandEvaluationResult(pair, HandRank.ONE_PAIR),
                new HandEvaluationResult(highCard, HandRank.HIGH_CARD));

        ShowdownResolver.evaluateHands(evaluator, board, List.of(a, b));

        assertEquals(pair, a.getBestHand());
        assertEquals(HandRank.ONE_PAIR, a.getHandRank());
        assertEquals(highCard, b.getBestHand());
        assertEquals(HandRank.HIGH_CARD, b.getHandRank());
    }

    @Test
    void distributesThreeWayMainPotAndTwoWaySidePotWithAnUncalledRefund() {
        Player a = player("a", 0, HandRank.FLUSH);
        Player b = player("b", 0, HandRank.FLUSH);
        Player c = player("c", 0, HandRank.FLUSH);
        var pots = new PotComputationResult(List.of(
                new PotAllocation(91, List.of(a, b, c)),
                new PotAllocation(40, List.of(b, c))), Map.of("c", 12), 12);

        var distribution = ShowdownResolver.distribute(
                GAME_ID, evaluator, pots, List.of(a, b, c), List.of("a", "b", "c"), "a");

        assertEquals(List.of(a, b, c), distribution.winners());
        assertEquals(Map.of("a", 30, "b", 51, "c", 50), distribution.settlement().contestedAwards());
        assertEquals(Map.of("c", 12), distribution.settlement().refunds());
        assertEquals(62, c.getChips());
        assertEquals(0, distribution.remainder());
    }
}
