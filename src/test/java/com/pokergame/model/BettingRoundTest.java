package com.pokergame.model;

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
}
