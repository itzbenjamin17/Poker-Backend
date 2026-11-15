package com.pokergame.model;

import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.HandRank;
import com.pokergame.service.HandEvaluatorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GameEligibility}.
 */
@Tag("unit")
@DisplayName("Game eligibility rules")
class GameEligibilityTest {

    private static Player player(String id, String name, int chips, boolean out, boolean disconnected) {
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
                .readyForNextHand(false)
                .build();
    }

    private static Game gameWith(List<Player> players, GamePhase phase, boolean settled, boolean gameOver) {
        return Game.restoreBuilder()
                .gameId("elig-game")
                .players(players)
                .activePlayerIds(players.stream().map(Player::getPlayerId).toList())
                .remainingDeck(List.of())
                .communityCards(List.of())
                .pot(0)
                .dealerAnchorPlayerId(players.isEmpty() ? null : players.getFirst().getPlayerId())
                .smallBlindPlayerId(players.isEmpty() ? null : players.getFirst().getPlayerId())
                .bigBlindPlayerId(players.size() > 1 ? players.get(1).getPlayerId() : players.isEmpty() ? null : players.getFirst().getPlayerId())
                .currentPlayerId(players.isEmpty() ? null : players.getFirst().getPlayerId())
                .currentHighestBet(0)
                .preHandCarryOver(0)
                .currentPhase(phase)
                .gameOver(gameOver)
                .smallBlind(10)
                .bigBlind(20)
                .handContributions(Map.of())
                .lastSettlementResult(settled ? new HandSettlementResult(Map.of("p1", 100), Map.of(), Set.of("p1"), 0, true) : null)
                .actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of())
                .handEvaluator(new HandEvaluatorService())
                .build();
    }

    @Test
    @DisplayName("isContender excludes eliminated players and zero-chip players after showdown settles")
    void isContenderRespectsOutStatusAndSettledZeroChipExclusion() {
        Player outPlayer = player("p1", "Out", 500, true, false);
        Player bustedConnected = player("p2", "Busted", 0, false, false);
        Player alive = player("p3", "Alive", 1000, false, false);

        Game midHand = gameWith(List.of(outPlayer, bustedConnected, alive), GamePhase.TURN, false, false);
        assertFalse(GameEligibility.isContender(midHand, outPlayer));
        assertTrue(GameEligibility.isContender(midHand, bustedConnected));
        assertTrue(GameEligibility.isContender(midHand, alive));
        assertEquals(2, GameEligibility.countContenders(midHand));

        Game settledShowdown = gameWith(List.of(outPlayer, bustedConnected, alive), GamePhase.SHOWDOWN, true, false);
        assertFalse(GameEligibility.isContender(settledShowdown, bustedConnected));
        assertTrue(GameEligibility.isContender(settledShowdown, alive));
        assertEquals(1, GameEligibility.countContenders(settledShowdown));
    }

    @Test
    @DisplayName("canClaim and canPlayerClaimWin permit only the sole connected contender")
    void disconnectClaimRequiresSoleConnectedContenderWithDisconnectedOpponent() {
        Player alice = player("p1", "Alice", 1000, false, false);
        Player bob = player("p2", "Bob", 1000, false, true);
        Player carol = player("p3", "Carol", 1000, false, true);

        Game claimable = gameWith(List.of(alice, bob, carol), GamePhase.FLOP, false, false);
        assertTrue(GameEligibility.canClaim(claimable));
        assertTrue(GameEligibility.canPlayerClaimWin(claimable, "Alice"));
        assertFalse(GameEligibility.canPlayerClaimWin(claimable, "Bob"));
        assertFalse(GameEligibility.canPlayerClaimWin(claimable, "Unknown"));
        assertEquals(List.of("Bob", "Carol"), GameEligibility.disconnectedContenderNamesExcept(claimable, "Alice"));

        Player dan = player("p4", "Dan", 1000, false, false);
        Game twoConnected = gameWith(List.of(alice, bob, dan), GamePhase.FLOP, false, false);
        assertFalse(GameEligibility.canClaim(twoConnected));
        assertFalse(GameEligibility.canPlayerClaimWin(twoConnected, "Alice"));

        Game allConnected = gameWith(List.of(alice, dan), GamePhase.PRE_FLOP, false, false);
        assertFalse(GameEligibility.canClaim(allConnected));
    }

    @Test
    @DisplayName("canClaim returns false once the game is over or settled and decided")
    void canClaimStopsWhenGameOverOrSettledAndDecided() {
        Player alice = player("p1", "Alice", 1000, false, false);
        Player bob = player("p2", "Bob", 0, false, true);

        Game gameOver = gameWith(List.of(alice, bob), GamePhase.TURN, false, true);
        assertFalse(GameEligibility.canClaim(gameOver));

        // Unsettled showdown with a disconnected 0-chip all-in player still counts 2 contenders
        Game unsettledShowdown = gameWith(List.of(alice, bob), GamePhase.SHOWDOWN, false, false);
        assertTrue(GameEligibility.canClaim(unsettledShowdown));

        // Once settled, 0-chip Bob is no longer a contender and game is decided
        Game settledDecided = gameWith(List.of(alice, bob), GamePhase.SHOWDOWN, true, false);
        settledDecided.setGameEndResult(new GameEndResponse(
                "GAME_END", "elig-game", "Alice", 1000, false, "Complete", null));
        assertFalse(GameEligibility.canClaim(settledDecided));
    }

    @Test
    @DisplayName("isGameDecided handles null, empty, single chip holder, and multiple chip holders")
    void isGameDecidedEvaluatesRemainingChipHolders() {
        assertTrue(GameEligibility.isGameDecided(null));
        assertTrue(GameEligibility.isGameDecided(List.of()));

        Player p1 = player("p1", "Alice", 1000, false, false);
        Player p2OutWithChips = player("p2", "Bob", 500, true, false);
        Player p3Broke = player("p3", "Carol", 0, false, false);
        assertTrue(GameEligibility.isGameDecided(List.of(p1, p2OutWithChips, p3Broke)));

        Player p4 = player("p4", "Dan", 10, false, false);
        assertFalse(GameEligibility.isGameDecided(List.of(p1, p4)));
    }

    @Test
    @DisplayName("resolveWinner returns eligible preferred winner or chip leader with earliest-seat tiebreak")
    void resolveWinnerSelectsEligiblePreferredWinnerOrChipLeader() {
        Player alice = player("p1", "Alice", 1000, true, false);
        Player bob = player("p2", "Bob", 1000, false, false);
        Player carol = player("p3", "Carol", 1000, false, false);
        Game tiedGame = gameWith(List.of(alice, bob, carol), GamePhase.SHOWDOWN, true, true);

        assertSame(bob, GameEligibility.resolveWinner(tiedGame, null));
        assertSame(carol, GameEligibility.resolveWinner(tiedGame, carol));

        Player richCarol = player("p3", "Carol", 2000, false, false);
        Game chipLeadGame = gameWith(List.of(alice, bob, richCarol), GamePhase.SHOWDOWN, true, true);
        assertSame(richCarol, GameEligibility.resolveWinner(chipLeadGame, null));
    }

    @Test
    @DisplayName("resolveWinner throws IllegalStateException when every player is out or roster is empty")
    void resolveWinnerThrowsWhenAllOutOrEmptyRoster() {
        Player allOutDc = player("p1", "Alice", 0, true, true);
        Player allOutConnected = player("p2", "Bob", 0, true, false);
        Game allOutGame = gameWith(List.of(allOutDc, allOutConnected), GamePhase.SHOWDOWN, true, true);
        assertThrows(IllegalStateException.class, () -> GameEligibility.resolveWinner(allOutGame, null));

        Game emptyGame = Game.restoreBuilder().gameId("empty").players(List.of()).activePlayerIds(List.of())
                .remainingDeck(List.of())
                .handEvaluator(new HandEvaluatorService()).build();
        assertThrows(IllegalStateException.class, () -> GameEligibility.resolveWinner(emptyGame, null));
    }

    @Test
    @DisplayName("resolveWinner throws IllegalStateException when preferred winner is out, off-roster, or roster is empty")
    void resolveWinnerThrowsWhenPreferredWinnerIsOutOrOffRoster() {
        Player aliceOut = player("p1", "Alice", 1000, true, false);
        Player bob = player("p2", "Bob", 1000, false, false);
        Game game = gameWith(List.of(aliceOut, bob), GamePhase.SHOWDOWN, true, true);

        assertThrows(IllegalStateException.class, () -> GameEligibility.resolveWinner(game, aliceOut));

        Player outsider = player("p9", "Outsider", 5000, false, false);
        assertThrows(IllegalStateException.class, () -> GameEligibility.resolveWinner(game, outsider));

        Game emptyGame = Game.restoreBuilder().gameId("empty").players(List.of()).activePlayerIds(List.of())
                .remainingDeck(List.of())
                .handEvaluator(new HandEvaluatorService()).build();
        assertThrows(IllegalStateException.class, () -> GameEligibility.resolveWinner(emptyGame, bob));
    }
}
