package com.pokergame.persistence.snapshot;

import tools.jackson.databind.json.JsonMapper;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.model.HandSettlementResult;
import com.pokergame.dto.internal.PlayerDecision;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.PlayerAction;
import com.pokergame.service.HandEvaluatorService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Tests aggregate snapshot mapper behavior. */
class AggregateSnapshotMapperTest {

    /**
     * Protects the contract that active game round trip preserves exact next cards and authoritative state.
     */
    @Test
    void activeGameRoundTripPreservesExactNextCardsAndAuthoritativeState() {
        HandEvaluatorService evaluator = new HandEvaluatorService();
        Room room = new Room("room-1", "Night Game", "Alice", 6, 5, 10, 1000, "secret");
        room.addPlayer("Alice");
        room.addPlayer("Bob");
        room.setGameStarted(true);
        Game game = new Game("room-1", List.of(
                new Player("Alice", "alice-id", 1000),
                new Player("Bob", "bob-id", 1000)), 5, 10, evaluator);
        assertFalse(game.resetForNewHand());
        game.dealHoleCards();
        game.postBlinds();

        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build(), evaluator);
        RecoveredAggregate recovered = mapper.deserialize(mapper.serialize(room, "Bob", game));

        assertEquals(room.getRoomName(), recovered.room().getRoomName());
        assertEquals("Bob", recovered.currentHost());
        assertTrue(recovered.room().checkPassword("secret"));
        assertEquals(game.getPot(), recovered.game().getPot());
        assertEquals(game.getCurrentPlayer().getPlayerId(), recovered.game().getCurrentPlayer().getPlayerId());
        assertEquals(game.getPlayers().getFirst().getHoleCards(),
                recovered.game().getPlayers().getFirst().getHoleCards());

        game.dealFlop();
        recovered.game().dealFlop();
        assertEquals(game.getCommunityCards(), recovered.game().getCommunityCards());
        assertEquals(game.getPreHandCarryOver(), recovered.game().getPreHandCarryOver());
        assertEquals(game.getHandSeatOrderSnapshot(), recovered.game().getHandSeatOrderSnapshot());
    }

    @Test
    void settlementAndSchemaTwoRoundTripAndRejectsVersionOne() {
        HandEvaluatorService evaluator = new HandEvaluatorService();
        Room room = new Room("room-2", "Recovery", "Alice", 4, 5, 10, 1000, "secret");
        room.addPlayer("Alice");
        room.addPlayer("Bob");
        room.setGameStarted(true);
        List<Player> players = List.of(new Player("Alice", "a", 1030), new Player("Bob", "b", 970));
        Game game = Game.restoreBuilder().gameId("room-2").players(players).activePlayerIds(List.of("a", "b"))
                .remainingDeck(List.of()).communityCards(List.of()).pot(3).dealerPosition(0).smallBlindPosition(0)
                .bigBlindPosition(1).currentPlayerPosition(0).currentHighestBet(0).currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false).smallBlind(5).bigBlind(10).handContributions(Map.of()).preHandCarryOver(3)
                .lastFullRaiseByStreet(Map.of("PRE_FLOP", 10)).lastActedWagerByPlayer(Map.of("a", 20))
                .handSeatOrder(List.of("a", "b")).dealerAnchorPlayerId("a").smallBlindPlayerId("a")
                .bigBlindPlayerId("b").lastSettlementResult(new HandSettlementResult(Map.of("a", 50), Map.of("b", 2),
                        Set.of("a"), 3, true))
                .readyCountdownActive(false).everyoneHasHadInitialTurn(true).actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of()).handEvaluator(evaluator).build();
        game.markGameOver();
        game.completeGameSettlement(players.getFirst());
        assertEquals(1033, players.getFirst().getChips());
        assertEquals(0, game.getPot());
        assertTrue(game.isTerminalCarryOverSettled());
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build(), evaluator);

        byte[] snapshot = mapper.serialize(room, "Alice", game);
        Game restored = mapper.deserialize(snapshot).game();
        assertEquals(3, restored.getPreHandCarryOver());
        assertEquals(game.getLastSettlementResult(), restored.getLastSettlementResult());
        assertEquals(Map.of("PRE_FLOP", 10), restored.getLastFullRaiseByStreetSnapshot());
        assertEquals(List.of("a", "b"), restored.getHandSeatOrderSnapshot());
        assertTrue(restored.isTerminalCarryOverSettled());
        restored.completeGameSettlement(restored.getPlayers().getFirst());
        assertEquals(1033, restored.getPlayers().getFirst().getChips(),
                "A recovered terminal marker prevents a second carry-over payout");
        assertEquals(0, restored.getPot());
        int aliceBeforeRetry = restored.getPlayers().getFirst().getChips();
        int bobBeforeRetry = restored.getPlayers().get(1).getChips();
        assertEquals(List.of("a"), restored.conductShowdown().stream().map(Player::getPlayerId).toList());
        assertEquals(aliceBeforeRetry, restored.getPlayers().getFirst().getChips());
        assertEquals(bobBeforeRetry, restored.getPlayers().get(1).getChips());

        String versionOne = new String(snapshot, StandardCharsets.UTF_8).replace("\"schemaVersion\":2", "\"schemaVersion\":1");
        assertNotEquals(new String(snapshot, StandardCharsets.UTF_8), versionOne);
        assertThrows(com.pokergame.persistence.config.PersistenceException.class,
                () -> mapper.deserialize(versionOne.getBytes(StandardCharsets.UTF_8)));

        Map<String, Integer> mutableAwards = new java.util.HashMap<>(Map.of("a", 1));
        HandSettlementResult immutable = new HandSettlementResult(mutableAwards, Map.of(), Set.of("a"), 0, true);
        mutableAwards.put("b", 2);
        assertEquals(Map.of("a", 1), immutable.contestedAwards());
        assertThrows(UnsupportedOperationException.class, () -> immutable.contestedAwards().put("b", 2));
    }

    @Test
    void midStreetRoundTripPreservesRaiseMinimumAndReopeningLegality() {
        HandEvaluatorService evaluator = new HandEvaluatorService();
        Room room = new Room("room-3", "Betting", "A", 6, 5, 10, 1000, null);
        room.addPlayer("A"); room.addPlayer("B"); room.addPlayer("Short1"); room.addPlayer("Short2");
        room.setGameStarted(true);
        List<Player> players = List.of(bettingPlayer("A", "a", 900, 100, false),
                bettingPlayer("B", "b", 900, 100, false), bettingPlayer("Short1", "s1", 0, 150, true),
                bettingPlayer("Short2", "s2", 50, 150, false));
        Game game = Game.restoreBuilder().gameId("room-3").players(players)
                .activePlayerIds(players.stream().map(Player::getPlayerId).toList()).remainingDeck(List.of())
                .communityCards(List.of()).pot(500).dealerPosition(0).smallBlindPosition(0).bigBlindPosition(1)
                .currentPlayerPosition(0).currentHighestBet(150).currentPhase(GamePhase.PRE_FLOP).gameOver(false)
                .smallBlind(5).bigBlind(10).handContributions(Map.of("a", 100, "b", 100, "s1", 150, "s2", 150))
                .preHandCarryOver(0).lastFullRaiseByStreet(Map.of("PRE_FLOP", 100))
                .lastActedWagerByPlayer(Map.of("a", 100)).handSeatOrder(List.of("a", "b", "s1", "s2"))
                .dealerAnchorPlayerId("a").smallBlindPlayerId("a").bigBlindPlayerId("b")
                .readyCountdownActive(false).everyoneHasHadInitialTurn(true).actedPlayersInRound(Set.of("a"))
                .scheduledTaskDeadlines(Map.of()).handEvaluator(evaluator).build();
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build(), evaluator);

        Game restored = mapper.deserialize(mapper.serialize(room, "A", game)).game();
        Player initialA = restored.getPlayers().getFirst();
        assertEquals(150, restored.getMinimumRaiseAmount(initialA));
        assertFalse(restored.isRaiseOpenTo(initialA));
        assertThrows(com.pokergame.exception.UnauthorisedActionException.class,
                () -> restored.processPlayerDecision(initialA, new PlayerDecision(PlayerAction.RAISE, 150, "a")));
        Player restoredShort = restored.getPlayers().get(3);
        restored.processPlayerDecision(restoredShort, new PlayerDecision(PlayerAction.ALL_IN, 0, "s2"));
        Game reopened = mapper.deserialize(mapper.serialize(room, "A", restored)).game();
        Player reopenedA = reopened.getPlayers().getFirst();
        assertEquals(200, reopened.getMinimumRaiseAmount(reopenedA));
        assertTrue(reopened.isRaiseOpenTo(reopenedA));
        reopened.processPlayerDecision(reopenedA, new PlayerDecision(PlayerAction.RAISE, 200, "a"));
        assertEquals(300, reopened.getCurrentHighestBet());
    }

    private Player bettingPlayer(String name, String id, int chips, int currentBet, boolean allIn) {
        return Player.restoreBuilder().name(name).playerId(id).chips(chips).currentBet(currentBet)
                .holeCards(List.of()).bestHand(List.of()).handRank(com.pokergame.enums.HandRank.NO_HAND)
                .hasFolded(false).allIn(allIn).out(false).disconnected(false).readyForNextHand(false).build();
    }
}
