package com.pokergame.persistence.snapshot;

import org.junit.jupiter.api.DisplayName;

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
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import com.pokergame.enums.HandRank;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.persistence.config.PersistenceException;
import com.pokergame.service.RoomService;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;

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
        assertThrows(PersistenceException.class,
                () -> mapper.deserialize(versionThree.getBytes(StandardCharsets.UTF_8)));

        String versionTwo = new String(snapshot, StandardCharsets.UTF_8).replace("\"schemaVersion\":4", "\"schemaVersion\":2");
        assertNotEquals(new String(snapshot, StandardCharsets.UTF_8), versionTwo);
        assertThrows(PersistenceException.class,
                () -> mapper.deserialize(versionTwo.getBytes(StandardCharsets.UTF_8)));
        String invalidRevision = new String(snapshot, StandardCharsets.UTF_8).replace("\"stateRevision\":19", "\"stateRevision\":-1");
        assertThrows(PersistenceException.class,
                () -> mapper.deserialize(invalidRevision.getBytes(StandardCharsets.UTF_8)));

        Map<String, Integer> mutableAwards = new HashMap<>(Map.of("a", 1));
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
                        .handRank(HandRank.HIGH_CARD)
                        .hasFolded(false).allIn(false).out(false).disconnected(false).readyForNextHand(false).build(),
                Player.restoreBuilder().name("Bob").playerId("b").chips(0).currentBet(0)
                        .holeCards(List.of(new Card(Rank.TWO, Suit.HEARTS), new Card(Rank.THREE, Suit.DIAMONDS)))
                        .bestHand(List.of())
                        .handRank(HandRank.NO_HAND)
                        .hasFolded(false).allIn(false).out(true).disconnected(false).readyForNextHand(false).build());

        PublicGameStateResponse finalState = new PublicGameStateResponse(
                6, 0, List.of(), 0, GamePhase.SHOWDOWN, 0,
                List.of(new Card(Rank.TEN, Suit.CLUBS)),
                List.of(
                        new PublicPlayerState("a", "Alice", 2000, 0, "ACTIVE", false, false, false, true, false,
                                HandRank.HIGH_CARD, List.of(new Card(Rank.ACE, Suit.SPADES)),
                                true, null, List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES)), null, false),
                        new PublicPlayerState("b", "Bob", 0, 0, "OUT", false, false, false, false, true,
                                null, List.of(), false, null, null, null, true)),
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
        assertThrows(PersistenceException.class,
                () -> mapper.deserialize(wrongGameId.getBytes(StandardCharsets.UTF_8)));

        String negativeChips = json.replace("\"winnerChips\":2000", "\"winnerChips\":-1");
        assertThrows(PersistenceException.class,
                () -> mapper.deserialize(negativeChips.getBytes(StandardCharsets.UTF_8)));

        String missingWinner = json.replace("\"winner\":\"Alice\"", "\"winner\":\"\"");
        assertThrows(PersistenceException.class,
                () -> mapper.deserialize(missingWinner.getBytes(StandardCharsets.UTF_8)));

        String missingMessage = json.replace("\"message\":", "\"message\":\"\", \"oldMessage\":");
        assertThrows(PersistenceException.class,
                () -> mapper.deserialize(missingMessage.getBytes(StandardCharsets.UTF_8)));

        assertTerminalFinalStateCorruptionRejected(mapper, json, "\"maxPlayers\":6", "\"maxPlayers\":1");
        assertTerminalFinalStateCorruptionRejected(mapper, json, "\"maxPlayers\":6", "\"maxPlayers\":11");
        assertTerminalFinalStateCorruptionRejected(mapper, json, "\"name\":\"Alice\",\"chips\":2000", "\"name\":\"Alice\",\"chips\":-1");
        assertTerminalFinalStateCorruptionRejected(mapper, json, "\"winners\":[\"Alice\"]", "\"winners\":[]");
        assertTerminalFinalStateCorruptionRejected(mapper, json, "\"id\":\"b\",\"name\":\"Bob\"", "\"id\":\"a\",\"name\":\"Bob\"");
        assertTerminalFinalStateCorruptionRejected(mapper, json, "\"status\":\"ACTIVE\"", "\"status\":\"UNKNOWN\"");
        assertTerminalFinalStateCorruptionRejected(mapper, json, "\"currentPlayerName\":null", "\"currentPlayerName\":\"Alice\"");
        assertTerminalFinalStateCorruptionRejected(mapper, json, "\"readyCountdownDeadlineEpochMs\":null", "\"readyCountdownDeadlineEpochMs\":-1");
        assertTerminalFinalStateCorruptionRejected(mapper, json, "\"handNumber\":1", "\"handNumber\":-1");
        assertTerminalFinalStateCorruptionRejected(mapper, json, "\"holeCards\":null", "\"holeCards\":[{\"rank\":\"TWO\",\"suit\":\"HEARTS\"}]");
        assertTerminalFinalStateCorruptionRejected(mapper, json, "\"holeCards\":null", "\"holeCards\":[{\"rank\":\"TEN\",\"suit\":\"CLUBS\"},{\"rank\":\"THREE\",\"suit\":\"DIAMONDS\"}]");
        assertTerminalFinalStateCorruptionRejected(mapper, json, "\"isWinner\":true", "\"isWinner\":false");
        assertTerminalCorruptionRejected(mapper, json, "\"type\":\"GAME_END\"", "\"type\":\"OTHER\"");
    }

    private static void assertTerminalCorruptionRejected(AggregateSnapshotMapper mapper, String json,
            String target, String replacement) {
        int terminalStart = json.indexOf("\"retainedTerminalResult\":{");
        assertTrue(terminalStart >= 0);
        String prefix = json.substring(0, terminalStart);
        String terminal = json.substring(terminalStart);
        assertTrue(terminal.contains(target), "terminal fixture mutation target missing: " + target);
        String corrupted = prefix + terminal.replaceFirst(Pattern.quote(target),
                Matcher.quoteReplacement(replacement));
        assertTrue(corrupted.startsWith(prefix), "room and live game fixture must remain unchanged");
        assertThrows(PersistenceException.class,
                () -> mapper.deserialize(corrupted.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertTerminalFinalStateCorruptionRejected(AggregateSnapshotMapper mapper, String json,
            String target, String replacement) {
        int finalStateStart = json.indexOf("\"finalState\":{", json.indexOf("\"retainedTerminalResult\":{"));
        assertTrue(finalStateStart >= 0);
        String prefix = json.substring(0, finalStateStart);
        String finalState = json.substring(finalStateStart);
        assertTrue(finalState.contains(target), "nested final state mutation target missing: " + target);
        String corrupted = prefix + finalState.replaceFirst(Pattern.quote(target),
                Matcher.quoteReplacement(replacement));
        assertEquals(json.substring(0, finalStateStart), corrupted.substring(0, finalStateStart),
                "room, live game, and retained terminal envelope must remain unchanged");
        assertThrows(PersistenceException.class,
                () -> mapper.deserialize(corrupted.getBytes(StandardCharsets.UTF_8)));
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
        assertThrows(UnauthorisedActionException.class,
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
        PersistenceException ex = assertThrows(
                PersistenceException.class,
                () -> mapper.deserialize(legacyJson.getBytes(StandardCharsets.UTF_8)));
        assertTrue(ex.getMessage().contains("Unsupported aggregate snapshot schema: 2"));
    }

    private Player bettingPlayer(String name, String id, int chips, int currentBet, boolean allIn) {
        return Player.restoreBuilder().name(name).playerId(id).chips(chips).currentBet(currentBet)
                .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND)
                .hasFolded(false).allIn(allIn).out(false).disconnected(false).readyForNextHand(false).build();
    }

    @Test
    @DisplayName("captureStateImage acquires monitors in Room -> Game order and JSON serialization occurs outside monitors")
    void captureStateImage_AcquiresMonitorsInOrderAndEncodesOutsideMonitors() {
        HandEvaluatorService evaluator = new HandEvaluatorService();
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build(), evaluator);

        Room room = new Room("room-order", "Order Room", "Alice", 6, 10, 20, 1000, null);
        room.addPlayer("Alice");
        room.addPlayer("Bob");

        Player p1 = new Player("Alice", "p1", 1000);
        Player p2 = new Player("Bob", "p2", 1000);
        Game game = new Game("room-order", List.of(p1, p2), 10, 20, evaluator);

        // Capture state image
        AggregateStateImage stateImage = mapper.captureStateImage(room, "Alice", game);
        assertNotNull(stateImage);
        assertNotNull(stateImage.room());
        assertNotNull(stateImage.game());

        // Verify that JSON serialization occurs outside aggregate monitors
        assertFalse(Thread.holdsLock(room), "Room monitor must not be held after captureStateImage");
        assertFalse(Thread.holdsLock(game), "Game monitor must not be held after captureStateImage");

        byte[] jsonBytes = mapper.serialize(stateImage);
        assertNotNull(jsonBytes);
        assertFalse(Thread.holdsLock(room), "Room monitor must not be held during JSON serialization");
        assertFalse(Thread.holdsLock(game), "Game monitor must not be held during JSON serialization");

        // Mutating live game after capture does not alter captured image or serialization
        synchronized (game) {
            p1.setChips(500);
        }
        RecoveredAggregate recovered = mapper.deserialize(jsonBytes);
        assertEquals(1000, recovered.game().getPlayers().get(0).getChips(),
                "Captured snapshot must be independent from post-capture live game mutations");
    }

    @Test
    @DisplayName("Concurrent state-image capture and mutation restore coherent roster, board, chips and metadata")
    void concurrentStateImageCaptureAndMutation_RestoresCoherentState() throws Exception {
        HandEvaluatorService evaluator = new HandEvaluatorService();
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build(), evaluator);

        String roomId = "concurrent-capture";
        Room room = new Room(roomId, "Concurrent Capture Room", "Alice", 6, 10, 20, 1000, null);
        room.addPlayer("Alice");
        room.addPlayer("Bob");

        Player p1 = new Player("Alice", "p1", 1000);
        Player p2 = new Player("Bob", "p2", 1000);
        Game game = new Game(roomId, List.of(p1, p2), 10, 20, evaluator);
        game.dealHoleCards();
        game.postBlinds();

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> error = new AtomicReference<>();
        List<byte[]> capturedSnapshots = new CopyOnWriteArrayList<>();

        // Thread 1: Mutates game state under synchronized (game)
        Thread mutator = new Thread(() -> {
            int round = 0;
            while (running.get() && (round < 2000 || capturedSnapshots.isEmpty()) && error.get() == null) {
                synchronized (game) {
                    int c1 = p1.getChips();
                    int c2 = p2.getChips();
                    if (c1 >= 10) {
                        p1.setChips(c1 - 10);
                        p2.setChips(c2 + 10);
                    } else {
                        p1.setChips(c1 + 10);
                        p2.setChips(c2 - 10);
                    }
                }
                round++;
                Thread.yield();
            }
            running.set(false);
        });

        // Thread 2: Concurrently captures state
        Thread capturer = new Thread(() -> {
            while (running.get() && error.get() == null) {
                try {
                    byte[] snapshot = mapper.serialize(room, "Alice", game);
                    if (snapshot != null) {
                        capturedSnapshots.add(snapshot);
                    }
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                }
            }
        });

        capturer.start();
        mutator.start();
        mutator.join(5000);
        running.set(false);
        capturer.join(5000);

        assertNull(error.get(), "Capture failed concurrently: " + error.get());
        assertFalse(capturedSnapshots.isEmpty(), "At least one snapshot must have been captured");

        // Verify all captured snapshots deserialize into coherent states
        for (byte[] snapshot : capturedSnapshots) {
            RecoveredAggregate recovered = mapper.deserialize(snapshot);
            assertNotNull(recovered);
            assertNotNull(recovered.room());
            assertNotNull(recovered.game());
            Game restoredGame = recovered.game();
            assertEquals(2, restoredGame.getPlayers().size(), "Roster must maintain exact 2 players");
            assertEquals("Alice", restoredGame.getPlayers().get(0).getName());
            assertEquals("Bob", restoredGame.getPlayers().get(1).getName());

            // Chip conservation: chips in stacks + pot + current bets must sum to total buy-ins (2000)
            int totalChips = restoredGame.getPot() + restoredGame.getPlayers().stream().mapToInt(Player::getChips).sum();
            assertEquals(2000, totalChips, "Total chip accounting must be strictly conserved across concurrent capture");
        }
    }

    @Test
    @DisplayName("toState handles null game, null player, and null player elements safely")
    void toState_NullAndEdgeCases_HandledSafely() {
        HandEvaluatorService evaluator = new HandEvaluatorService();
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build(), evaluator);

        assertNull(mapper.toState((Game) null));
        assertNull(mapper.toState((Player) null));

        Player normalPlayer = new Player("PlayerOne", "p-one", 100);
        AggregateStateImage.PlayerState playerState = mapper.toState(normalPlayer);
        assertNotNull(playerState);
        assertEquals("PlayerOne", playerState.name());
        assertEquals("p-one", playerState.playerId());
        assertEquals(List.of(), playerState.holeCards());
        assertEquals(List.of(), playerState.bestHand());
    }

    @Test
    @DisplayName("toState under concurrent player additions and removals executes safely without ConcurrentModificationException")
    void toState_UnderConcurrentPlayerAddRemove_ExecutesSafelyWithoutConcurrentModificationException() throws Exception {
        HandEvaluatorService evaluator = new HandEvaluatorService();
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build(), evaluator);

        String roomId = "cme-stress-room";
        Player p1 = new Player("Alice", "p1", 1000);
        Player p2 = new Player("Bob", "p2", 1000);
        Game game = new Game(roomId, new ArrayList<>(List.of(p1, p2)), 10, 20, evaluator);

        int readerCount = 4;
        int mutatorCount = 2;
        int operationsPerMutator = 1000;
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicInteger snapshotsExtracted = new AtomicInteger();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(mutatorCount);

        ExecutorService executor = Executors.newFixedThreadPool(readerCount + mutatorCount);

        for (int i = 0; i < readerCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (running.get() && error.get() == null) {
                        AggregateStateImage.GameState state = mapper.toState(game);
                        if (state != null) {
                            snapshotsExtracted.incrementAndGet();
                            assertNotNull(state.players());
                            assertTrue(state.players().size() >= 2);
                        }
                    }
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                }
            });
        }

        for (int i = 0; i < mutatorCount; i++) {
            final int mutatorId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int op = 0; op < operationsPerMutator && running.get() && error.get() == null; op++) {
                        Player extra = new Player("Temp_" + mutatorId + "_" + op, "temp_" + mutatorId + "_" + op, 100);
                        synchronized (game) {
                            game.getPlayers().add(extra);
                            game.getPlayers().remove(extra);
                        }
                        if (op % 100 == 0) {
                            Thread.yield();
                        }
                    }
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Mutators did not complete in time");
        running.set(false);
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor did not terminate in time");

        assertNull(error.get(), "Concurrent toState execution threw an exception: " + error.get());
        assertTrue(snapshotsExtracted.get() > 0, "At least one snapshot state must have been extracted");
    }

    @Test
    @DisplayName("Snapshot serializes passwordHash and never cleartext, and deserialization restores passwordHash")
    void passwordSecurity_SnapshotSerializesPasswordHashAndNeverPlaintext() {
        HandEvaluatorService evaluator = new HandEvaluatorService();
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build(), evaluator);

        String rawSecret = "VerySecretPassphrase123!";
        Room room = new Room("pw-room", "Protected Room", "Host", 4, 10, 20, 500, rawSecret);
        room.addPlayer("Host");

        byte[] snapshot = mapper.serialize(room, "Host", null);
        String json = new String(snapshot, StandardCharsets.UTF_8);

        // Security check: raw password MUST NOT appear anywhere in the snapshot JSON
        assertFalse(json.contains(rawSecret), "Cleartext password must NEVER appear in serialized snapshot JSON");

        // Security check: passwordHash MUST appear in the snapshot JSON
        assertNotNull(room.getPasswordHash());
        assertTrue(json.contains(room.getPasswordHash()), "Snapshot must serialize the BCrypt passwordHash");

        // Reconstruct room from snapshot bytes
        RecoveredAggregate recovered = mapper.deserialize(snapshot);
        assertNotNull(recovered.room());
        Room restoredRoom = recovered.room();

        // Verifications on restored room
        assertTrue(restoredRoom.hasPassword());
        assertNull(restoredRoom.getPassword(), "restored Room getPassword must return null");
        assertEquals(room.getPasswordHash(), restoredRoom.getPasswordHash(), "restored Room must have exact passwordHash preserved");
        assertTrue(restoredRoom.checkPassword(rawSecret), "restored Room must match raw password against restored passwordHash");
        assertFalse(restoredRoom.checkPassword("WrongPass"), "restored Room must reject invalid password");
    }

    @Test
    @DisplayName("Public room snapshot serializes null password and deserialization restores room with no password")
    void passwordSecurity_PublicRoomSnapshotSerializesNullPassword() {
        HandEvaluatorService evaluator = new HandEvaluatorService();
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build(), evaluator);

        Room publicRoom = new Room("pub-room", "Public Room", "Host", 4, 10, 20, 500, null);
        publicRoom.addPlayer("Host");

        byte[] snapshot = mapper.serialize(publicRoom, "Host", null);
        RecoveredAggregate recovered = mapper.deserialize(snapshot);

        assertNotNull(recovered.room());
        Room restored = recovered.room();
        assertFalse(restored.hasPassword());
        assertNull(restored.getPasswordHash());
        assertNull(restored.getPassword());
        assertTrue(restored.checkPassword("any"));
        assertTrue(restored.checkPassword(null));
    }

    @Test
    @DisplayName("Snapshot and restoreRoom preserve playerMembershipIds so original JWT membership remains valid")
    void snapshotAndRestoreRoom_PreservesPlayerMembershipIds() {
        HandEvaluatorService evaluator = new HandEvaluatorService();
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build(), evaluator);

        Room room = new Room("mem-room", "Membership Room", "Alice", 4, 10, 20, 500, null);
        String aliceMemId = room.addPlayer("Alice");
        String bobMemId = room.addPlayer("Bob");

        byte[] snapshot = mapper.serialize(room, "Alice", null);
        RecoveredAggregate recovered = mapper.deserialize(snapshot);

        assertNotNull(recovered.room());
        assertEquals(aliceMemId, recovered.room().getMembershipId("Alice"),
                "Alice membershipId must survive WAL snapshot round-trip");
        assertEquals(bobMemId, recovered.room().getMembershipId("Bob"),
                "Bob membershipId must survive WAL snapshot round-trip");

        RoomService roomService = new RoomService(
                Mockito.mock(SimpMessagingTemplate.class));
        roomService.restoreRoom(recovered.room(), recovered.currentHost());

        assertEquals(aliceMemId, roomService.getRoom("mem-room").getMembershipId("Alice"));
        assertEquals(bobMemId, roomService.getRoom("mem-room").getMembershipId("Bob"));

        // Verify fallback preservation when a restored room lacks persisted membershipIds for an existing player
        Room legacyRestored = Room.restoreBuilder()
                .roomId("mem-room")
                .roomName("Membership Room")
                .hostName("Alice")
                .maxPlayers(4)
                .smallBlind(10)
                .bigBlind(20)
                .buyIn(500)
                .playersWithJoinTime(room.getPlayersWithJoinTimeSnapshot())
                .build();
        roomService.restoreRoom(legacyRestored, "Alice");
        assertEquals(aliceMemId, roomService.getRoom("mem-room").getMembershipId("Alice"),
                "Existing in-memory membershipId must be preserved if restored room lacks persisted membershipIds");
        assertEquals(bobMemId, roomService.getRoom("mem-room").getMembershipId("Bob"));
    }
}
