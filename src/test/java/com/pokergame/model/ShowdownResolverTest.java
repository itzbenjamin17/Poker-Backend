package com.pokergame.model;

import com.pokergame.enums.HandRank;
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
        a.setBestHand(List.of(new Card(com.pokergame.enums.Rank.ACE, com.pokergame.enums.Suit.HEARTS)));
        b.setBestHand(List.of(new Card(com.pokergame.enums.Rank.TWO, com.pokergame.enums.Suit.SPADES)));
        when(evaluator.isBetterHandOfSameRank(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0) == a.getBestHand());

        assertEquals(List.of(a), ShowdownResolver.determineWinners(evaluator, List.of(a, b)));
    }

    @Test
    void distribute_SingleWinner_TakesWholePot() {
        Player a = player("a", 0, HandRank.FLUSH);
        Player b = player("b", 0, HandRank.ONE_PAIR);
        PotComputationResult computation = new PotComputationResult(
                List.of(new PotAllocation(200, List.of(a, b))), Map.of(), 0);

        ShowdownResolver.Distribution d = ShowdownResolver.distribute(GAME_ID, evaluator, computation, List.of(a, b));

        assertEquals(200, a.getChips());
        assertEquals(0, b.getChips());
        assertEquals(0, d.remainder());
        assertEquals(List.of(a), d.winners());
        assertEquals(200, d.settlement().contestedAwards().get("a"));
        assertTrue(d.settlement().settled());
    }

    @Test
    void distribute_OddChipSplit_KeepsRemainderInPot() {
        when(evaluator.isBetterHandOfSameRank(any(), any(), any())).thenReturn(false);
        Player a = player("a", 0, HandRank.FLUSH);
        Player b = player("b", 0, HandRank.FLUSH);
        PotComputationResult computation = new PotComputationResult(
                List.of(new PotAllocation(101, List.of(a, b))), Map.of(), 0);

        ShowdownResolver.Distribution d = ShowdownResolver.distribute(GAME_ID, evaluator, computation, List.of(a, b));

        assertEquals(50, a.getChips());
        assertEquals(50, b.getChips());
        assertEquals(1, d.remainder());
        assertEquals(1, d.settlement().remainder());
    }

    @Test
    void distribute_RefundsUncalledToActivePlayerOnly() {
        Player a = player("a", 0, HandRank.FLUSH);
        Player folded = Player.restoreBuilder().name("f").playerId("f").holeCards(List.of()).bestHand(List.of()).chips(0).hasFolded(true).handRank(HandRank.NO_HAND).build();
        PotComputationResult computation = new PotComputationResult(
                List.of(new PotAllocation(100, List.of(a))), Map.of("a", 40, "f", 30), 70);

        ShowdownResolver.Distribution d = ShowdownResolver.distribute(GAME_ID, evaluator, computation, List.of(a, folded));

        assertEquals(140, a.getChips());
        assertEquals(0, folded.getChips());
        assertEquals(Map.of("a", 40), d.settlement().refunds());
    }

    @Test
    void distribute_AllocationWithNoEligiblePlayers_GoesToRemainder() {
        PotComputationResult computation = new PotComputationResult(
                List.of(new PotAllocation(75, List.of())), Map.of(), 0);

        ShowdownResolver.Distribution d = ShowdownResolver.distribute(GAME_ID, evaluator, computation, List.of());

        assertEquals(75, d.remainder());
        assertTrue(d.winners().isEmpty());
    }
}
