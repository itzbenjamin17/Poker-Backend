package com.pokergame.persistence.snapshot;

import tools.jackson.databind.json.JsonMapper;
import com.pokergame.model.Card;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.model.HandSettlementResult;
import com.pokergame.dto.internal.PlayerDecision;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.enums.Suit;
import com.pokergame.enums.Rank;
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
    void settlementAndSchemaFourRoundTripAndRejectsVersionThreeAndTwo() {
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
                .scheduledTaskDeadlines(Map.of(ScheduledGameTask.CLEANUP, 123L))
                .scheduledTaskGenerations(Map.of(ScheduledGameTask.CLEANUP, 9L))
                .handNumber(7).readyGateState(ReadyGateState.WAITING_FOR_PLAYERS).forfeitPending(true).stateRevision(19)
                .handEvaluator(evaluator).build();
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
        assertEquals(7, restored.getHandNumber());
        assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, restored.getReadyGateState());
        assertEquals(9L, restored.getScheduledTaskGeneration(ScheduledGameTask.CLEANUP));
        assertTrue(restored.isForfeitPending());
        assertEquals(19, restored.getStateRevision(), "Persisted same-hand public revision must survive restart");
        assertTrue(new String(snapshot, StandardCharsets.UTF_8).contains("\"stateRevision\":19"));
        assertTrue(new String(snapshot, StandardCharsets.UTF_8).contains("\"schemaVersion\":4"));
        restored.completeGameSettlement(restored.getPlayers().getFirst());
        assertEquals(1033, restored.getPlayers().getFirst().getChips(),
                "A recovered terminal marker prevents a second carry-over payout");
        assertEquals(0, restored.getPot());
        int aliceBeforeRetry = restored.getPlayers().getFirst().getChips();
        int bobBeforeRetry = restored.getPlayers().get(1).getChips();
        assertEquals(List.of("a"), restored.conductShowdown().stream().map(Player::getPlayerId).toList());
        assertEquals(aliceBeforeRetry, restored.getPlayers().getFirst().getChips());
        assertEquals(bobBeforeRetry, restored.getPlayers().get(1).getChips());

        String versionThree = new String(snapshot, StandardCharsets.UTF_8).replace("\"schemaVersion\":4", "\"schemaVersion\":3");
        assertNotEquals(new String(snapshot, StandardCharsets.UTF_8), versionThree);
        assertThrows(com.pokergame.persistence.config.PersistenceException.class,
                () -> mapper.deserialize(versionThree.getBytes(StandardCharsets.UTF_8)));

        String versionTwo = new String(snapshot, StandardCharsets.UTF_8).replace("\"schemaVersion\":4", "\"schemaVersion\":2");
        assertNotEquals(new String(snapshot, StandardCharsets.UTF_8), versionTwo);
        assertThrows(com.pokergame.persistence.config.PersistenceException.class,
                () -> mapper.deserialize(versionTwo.getBytes(StandardCharsets.UTF_8)));
        String invalidRevision = new String(snapshot, StandardCharsets.UTF_8).replace("\"stateRevision\":19", "\"stateRevision\":-1");
        assertThrows(com.pokergame.persistence.config.PersistenceException.class,
                () -> mapper.deserialize(invalidRevision.getBytes(StandardCharsets.UTF_8)));

        Map<String, Integer> mutableAwards = new java.util.HashMap<>(Map.of("a", 1));
        HandSettlementResult immutable = new HandSettlementResult(mutableAwards, Map.of(), Set.of("a"), 0, true);
        mutableAwards.put("b", 2);
        assertEquals(Map.of("a", 1), immutable.contestedAwards());
        assertThrows(UnsupportedOperationException.class, () -> immutable.contestedAwards().put("b", 2));
    }

    @Test
    void retainedTerminalResultRoundTripAndValidation() {
        HandEvaluatorService evaluator = new HandEvaluatorService();
        Room room = new Room("room-terminal", "Final Table", "Alice", 6, 5, 10, 1000, null);
        room.addPlayer("Alice");
        room.addPlayer("Bob");
        room.setGameStarted(true);
        room.setTerminal(true);

        List<Player> players = List.of(
                Player.restoreBuilder().name("Alice").playerId("a").chips(2000).currentBet(0)
                        .holeCards(List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES)))
                        .bestHand(List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES)))
                        .handRank(com.pokergame.enums.HandRank.HIGH_CARD)
                        .hasFolded(false).allIn(false).out(false).disconnected(false).readyForNextHand(false).build(),
                Player.restoreBuilder().name("Bob").playerId("b").chips(0).currentBet(0)
                        .holeCards(List.of(new Card(Rank.TWO, Suit.HEARTS), new Card(Rank.THREE, Suit.DIAMONDS)))
                        .bestHand(List.of())
                        .handRank(com.pokergame.enums.HandRank.NO_HAND)
                        .hasFolded(false).allIn(false).out(true).disconnected(false).readyForNextHand(false).build());

        PublicGameStateResponse finalState = new PublicGameStateResponse(
                6, 0, List.of(), 0, GamePhase.SHOWDOWN, 0,
                List.of(new Card(Rank.TEN, Suit.CLUBS)),
                List.of(
                        new PublicPlayerState("a", "Alice", 2000, 0, "Winner", false, false, false, true, false,
                                com.pokergame.enums.HandRank.HIGH_CARD, List.of(new Card(Rank.ACE, Suit.SPADES)),
                                true, null, List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES)), null, false),
                        new PublicPlayerState("b", "Bob", 0, 0, "Out", false, false, false, false, true,
                                null, List.of(), false, null, null, null, false)),
                null, null, List.of("Alice"), null, false, null, false, null, false, null,
                true, null, List.of(), null, null, 1, ReadyGateState.CLOSED, 123456789L, 42);

        GameEndResponse terminalResult = new GameEndResponse("GAME_END", "room-terminal", "Alice", 2000, false,
                "🏆 Alice wins the game with 2000 chips!", finalState);

        Game game = Game.restoreBuilder().gameId("room-terminal").players(players).activePlayerIds(List.of("a", "b"))
                .remainingDeck(List.of()).communityCards(List.of(new Card(Rank.TEN, Suit.CLUBS))).pot(0)
                .dealerPosition(0).smallBlindPosition(0).bigBlindPosition(1).currentPlayerPosition(0).currentHighestBet(0)
                .currentPhase(GamePhase.SHOWDOWN).gameOver(true).smallBlind(5).bigBlind(10).handContributions(Map.of())
                .preHandCarryOver(0).lastFullRaiseByStreet(Map.of()).lastActedWagerByPlayer(Map.of())
                .handSeatOrder(List.of("a", "b")).dealerAnchorPlayerId("a").smallBlindPlayerId("a").bigBlindPlayerId("b")
                .readyCountdownActive(false).everyoneHasHadInitialTurn(true).actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of(ScheduledGameTask.CLEANUP, 999999L))
                .scheduledTaskGenerations(Map.of(ScheduledGameTask.CLEANUP, 1L))
                .handNumber(1).readyGateState(ReadyGateState.CLOSED).forfeitPending(false).stateRevision(42)
                .retainedTerminalResult(terminalResult)
                .handEvaluator(evaluator).build();

        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build(), evaluator);

        byte[] snapshot = mapper.serialize(room, "Alice", game);
        String json = new String(snapshot, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schemaVersion\":4"));
        assertTrue(json.contains("\"retainedTerminalResult\""));
        assertTrue(json.contains("\"winner\":\"Alice\""));
        assertTrue(json.contains("\"winnerChips\":2000"));

        Game restored = mapper.deserialize(snapshot).game();
        assertNotNull(restored.getRetainedTerminalResult());
        GameEndResponse restoredResult = restored.getRetainedTerminalResult();
        assertEquals("GAME_END", restoredResult.type());
        assertEquals("room-terminal", restoredResult.gameId());
        assertEquals("Alice", restoredResult.winner());
        assertEquals(2000, restoredResult.winnerChips());
        assertFalse(restoredResult.isForfeit());
        assertEquals("🏆 Alice wins the game with 2000 chips!", restoredResult.message());
        assertNotNull(restoredResult.finalState());
        assertEquals(42, restoredResult.finalState().stateRevision());
        assertEquals(2, restoredResult.finalState().players().size());
        assertEquals("Alice", restoredResult.finalState().players().get(0).name());
        assertEquals("Bob", restoredResult.finalState().players().get(1).name());

        // Validate corrupt terminal results
        String wrongGameId = json.replace("\"retainedTerminalResult\":{\"type\":\"GAME_END\",\"gameId\":\"room-terminal\"",
                "\"retainedTerminalResult\":{\"type\":\"GAME_END\",\"gameId\":\"wrong-game-id\"");
        assertThrows(com.pokergame.persistence.config.PersistenceException.class,
                () -> mapper.deserialize(wrongGameId.getBytes(StandardCharsets.UTF_8)));

        String negativeChips = json.replace("\"winnerChips\":2000", "\"winnerChips\":-1");
        assertThrows(com.pokergame.persistence.config.PersistenceException.class,
                () -> mapper.deserialize(negativeChips.getBytes(StandardCharsets.UTF_8)));

        String missingWinner = json.replace("\"winner\":\"Alice\"", "\"winner\":\"\"");
        assertThrows(com.pokergame.persistence.config.PersistenceException.class,
                () -> mapper.deserialize(missingWinner.getBytes(StandardCharsets.UTF_8)));
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

    @Test
    void activeGameSnapshotPreservesRoomMembershipSeparatelyFromRetainedGameRows() {
        HandEvaluatorService evaluator = new HandEvaluatorService();
        Room room = new Room("room-divergent", "Divergent", "DepartedHost", 6, 5, 10, 1000, null);
        room.addPlayer("Alice");
        room.addPlayer("BustedSpectator");
        room.setGameStarted(true);

        List<Player> gamePlayers = List.of(
                new Player("Alice", "alice-id", 1000),
                Player.restoreBuilder().name("Bob").playerId("bob-id").chips(1000).out(true)
                        .holeCards(List.of()).bestHand(List.of()).build(),
                Player.restoreBuilder().name("DepartedHost").playerId("departed-host-id").chips(0).out(true)
                        .holeCards(List.of()).bestHand(List.of()).build(),
                new Player("BustedSpectator", "spectator-id", 0));
        Game game = new Game("room-divergent", gamePlayers, 5, 10, evaluator);

        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build(), evaluator);
        byte[] snapshot = mapper.serialize(room, null, game);

        RecoveredAggregate recovered = mapper.deserialize(snapshot);
        assertNotNull(recovered.room());
        assertNotNull(recovered.game());
        assertTrue(recovered.room().isGameStarted());
        assertTrue(recovered.room().hasPlayer("Alice"), "Alice from game must be in room");
        assertFalse(recovered.room().hasPlayer("Bob"), "A retained OUT roster row must not recreate Room membership");
        assertFalse(recovered.room().hasPlayer("DepartedHost"), "A departed original host must not recreate Room membership");
        assertTrue(recovered.room().hasPlayer("BustedSpectator"), "Busted spectators who stayed remain Room members");
        assertNotNull(recovered.room().getJoinedAt("Alice"), "Alice must have join record");
        assertNull(recovered.room().getJoinedAt("Bob"));
        assertNotNull(recovered.room().getJoinedAt("BustedSpectator"));
        assertEquals("Alice", recovered.currentHost(), "Current host remains an actual Room member");
    }

    @Test
    void rejectsUnsupportedAggregateSnapshotSchema() {
        HandEvaluatorService evaluator = new HandEvaluatorService();
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build(), evaluator);
        String legacyJson = "{\"schemaVersion\":2,\"deleted\":false,\"room\":{\"roomId\":\"r1\"}}";
        com.pokergame.persistence.config.PersistenceException ex = assertThrows(
                com.pokergame.persistence.config.PersistenceException.class,
                () -> mapper.deserialize(legacyJson.getBytes(StandardCharsets.UTF_8)));
        assertTrue(ex.getMessage().contains("Unsupported aggregate snapshot schema: 2"));
    }

    private Player bettingPlayer(String name, String id, int chips, int currentBet, boolean allIn) {
        return Player.restoreBuilder().name(name).playerId(id).chips(chips).currentBet(currentBet)
                .holeCards(List.of()).bestHand(List.of()).handRank(com.pokergame.enums.HandRank.NO_HAND)
                .hasFolded(false).allIn(allIn).out(false).disconnected(false).readyForNextHand(false).build();
    }
}
