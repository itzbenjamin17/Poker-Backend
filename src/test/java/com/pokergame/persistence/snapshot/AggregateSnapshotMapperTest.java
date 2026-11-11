package com.pokergame.persistence.snapshot;

import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.persistence.wal.EncryptionKeyring;
import com.pokergame.persistence.wal.WalTransaction;
import java.nio.file.Path;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

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
import com.pokergame.service.RoomCoordinator;
import com.pokergame.service.GameStateService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;

import static com.pokergame.util.ConcurrentTestSupport.awaitBlockedOn;
import static com.pokergame.util.ConcurrentTestSupport.stopExecutor;
import static org.junit.jupiter.api.Assertions.*;
import com.pokergame.enums.HandRank;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.persistence.config.PersistenceException;
import com.pokergame.service.RoomService;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.UncheckedIOException;

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

    /** A restart image captured during a chip transfer must include both sides of the transfer. */
    @Test
    void concurrentStateImageCaptureAndMutation_RestoresCoherentState() throws Exception {
        HandEvaluatorService evaluator = new HandEvaluatorService();
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build(), evaluator);

        String roomId = "concurrent-capture";
        Room room = new Room(roomId, "Concurrent Capture Room", "Alice", 6, 10, 20, 1000, null);
        room.addPlayer("Alice");
        room.addPlayer("Bob");
        Player alice = new Player("Alice", "p1", 1000);
        Player bob = new Player("Bob", "p2", 1000);
        Game game = new Game(roomId, List.of(alice, bob), 10, 20, evaluator);
        game.dealHoleCards();
        game.postBlinds();
        RoomCoordinator coordinator = new RoomCoordinator();

        AtomicReference<Thread> reader = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<byte[]> capture;
            try (var ignored = coordinator.acquire(roomId)) {
                alice.setChips(alice.getChips() - 10);
                capture = executor.submit(() -> {
                    reader.set(Thread.currentThread());
                    return coordinator.coordinate(roomId, () -> mapper.serialize(room, "Alice", game));
                });
                awaitBlockedOn(reader, coordinator.getLockRegistry());
                bob.setChips(bob.getChips() + 10);
                game.incrementStateRevision();
            }

            RecoveredAggregate recovered = mapper.deserialize(capture.get(5, TimeUnit.SECONDS));
            assertEquals(List.of("Alice", "Bob"), recovered.room().getPlayers());
            Game restored = recovered.game();
            assertEquals(List.of(alice.getChips(), bob.getChips()),
                    restored.getPlayers().stream().map(Player::getChips).toList());
            assertEquals(2000, restored.getPot() + restored.getPlayers().stream().mapToInt(Player::getChips).sum());
            assertEquals(game.getStateRevision(), restored.getStateRevision());
            assertEquals(game.getRemainingDeckSnapshot(), restored.getRemainingDeckSnapshot());
        } finally {
            stopExecutor(executor);
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

    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final long JS_MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final String TERMINAL_ROOM_ID = "room-terminal";

    private static AggregateSnapshotMapper newMapper() {
        return new AggregateSnapshotMapper(JSON, new HandEvaluatorService());
    }

    /**
     * Classpath location of the hand-written schema-4 terminal snapshot fixture. The fixture is an
     * independent oracle (ADR-0001): it is NOT produced by {@link AggregateSnapshotMapper#captureStateImage}, so a
     * symmetric field rename in the writer and reader cannot silently keep the corruption matrix green.
     */
    private static final String TERMINAL_FIXTURE = "/persistence/schema4-terminal-snapshot.json";

    /** Loads and parses the checked-in valid terminal snapshot fixture as a fresh mutable tree. */
    private static ObjectNode validTerminalSnapshotTree() {
        try (var in = AggregateSnapshotMapperTest.class.getResourceAsStream(TERMINAL_FIXTURE)) {
            assertNotNull(in, "Missing terminal snapshot fixture on the classpath: " + TERMINAL_FIXTURE);
            return (ObjectNode) JSON.readTree(in.readAllBytes());
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Guards that the mapper's writer still emits exactly the field names the independent fixture declares.
     * If writer and reader were renamed together, the mapper would round-trip against itself but its freshly
     * serialized output would no longer match the frozen fixture's field names, failing here.
     */
    @Test
    @DisplayName("The mapper's serialized output carries the same field names as the independent fixture")
    void captureStateImage_FieldNames_MatchIndependentFixture() {
        HandEvaluatorService evaluator = new HandEvaluatorService();
        Room room = new Room(TERMINAL_ROOM_ID, "Final Table", "Alice", 6, 5, 10, 1000, null);
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
                                true, null, List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES)),
                                null, false),
                        new PublicPlayerState("b", "Bob", 0, 0, "OUT", false, false, false, false, true,
                                null, List.of(), false, null, null, null, true)),
                null, null, List.of("Alice"), null, false, null, false, null, false, null,
                true, null, List.of(), null, null, 1, ReadyGateState.CLOSED, 123456789L, 42);
        GameEndResponse terminalResult = new GameEndResponse("GAME_END", TERMINAL_ROOM_ID, "Alice", 2000, false,
                "Alice wins the game with 2000 chips!", finalState);

        Game game = Game.restoreBuilder().gameId(TERMINAL_ROOM_ID).players(players).activePlayerIds(List.of("a", "b"))
                .remainingDeck(List.of()).communityCards(List.of(new Card(Rank.TEN, Suit.CLUBS))).pot(0)
                .dealerPosition(0).smallBlindPosition(0).bigBlindPosition(1).currentPlayerPosition(0)
                .currentHighestBet(0).currentPhase(GamePhase.SHOWDOWN).gameOver(true).smallBlind(5).bigBlind(10)
                .handContributions(Map.of()).preHandCarryOver(0).lastFullRaiseByStreet(Map.of())
                .lastActedWagerByPlayer(Map.of()).handSeatOrder(List.of("a", "b")).dealerAnchorPlayerId("a")
                .smallBlindPlayerId("a").bigBlindPlayerId("b").readyCountdownActive(false)
                .everyoneHasHadInitialTurn(true).actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of(ScheduledGameTask.CLEANUP, 999999L))
                .scheduledTaskGenerations(Map.of(ScheduledGameTask.CLEANUP, 1L))
                .handNumber(1).readyGateState(ReadyGateState.CLOSED).forfeitPending(false).stateRevision(42)
                .retainedTerminalResult(terminalResult).handEvaluator(evaluator).build();

        ObjectNode serialized = JSON.valueToTree(
                new AggregateSnapshotMapper(JSON, evaluator).captureStateImage(room, "Alice", game));

        assertEquals(fieldNameShape(validTerminalSnapshotTree()), fieldNameShape(serialized),
                "Mapper writer field names drifted from the independent terminal-snapshot fixture");
    }

    /**
     * Collects every JSON field path in the tree (object keys recursively, with array elements folded onto a
     * shared [*] path) so two trees with identical structure but different scalar values compare equal here,
     * while any renamed/added/removed field is detected.
     */
    private static Set<String> fieldNameShape(JsonNode node) {
        Set<String> paths = new java.util.TreeSet<>();
        collectFieldPaths("", node, paths);
        return paths;
    }

    private static void collectFieldPaths(String prefix, JsonNode node, Set<String> paths) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                String path = prefix + "/" + entry.getKey();
                paths.add(path);
                collectFieldPaths(path, entry.getValue(), paths);
            });
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                collectFieldPaths(prefix + "[*]", element, paths);
            }
        }
    }

    private static ObjectNode finalStateOf(ObjectNode terminal) {
        return (ObjectNode) terminal.get("finalState");
    }

    private static ArrayNode playersOf(ObjectNode terminal) {
        return (ArrayNode) finalStateOf(terminal).get("players");
    }

    private static ObjectNode playerOf(ObjectNode terminal, int index) {
        return (ObjectNode) playersOf(terminal).get(index);
    }

    private static ObjectNode card(String rank, String suit) {
        ObjectNode card = JSON.createObjectNode();
        card.put("rank", rank);
        card.put("suit", suit);
        return card;
    }

    /** Builds a JSON array of cards; a {@code null} element becomes a JSON null entry. */
    private static ArrayNode cardArray(ObjectNode... cards) {
        ArrayNode array = JSON.createArrayNode();
        for (ObjectNode card : cards) {
            if (card == null) {
                array.addNull();
            } else {
                array.add(card);
            }
        }
        return array;
    }

    private static Arguments rejected(String name, Consumer<ObjectNode> corruption, String messageSuffix) {
        return Arguments.of(name, corruption, "Retained terminal result " + messageSuffix);
    }

    private static void abandonTerminalResult(ObjectNode terminal) {
        terminal.put("abandoned", true);
        terminal.put("reason", "INACTIVITY");
        terminal.putNull("winner");
        terminal.putNull("winnerChips");
        finalStateOf(terminal).putArray("winners");
        playerOf(terminal, 0).put("isWinner", false);
    }

    static Stream<Arguments> corruptTerminalResults() {
        return Stream.of(
                // Envelope
                rejected("wrong type", t -> t.put("type", "OTHER"), "has invalid type"),
                rejected("null type", t -> t.putNull("type"), "has invalid type"),
                rejected("gameId mismatch", t -> t.put("gameId", "wrong-game-id"),
                        "gameId does not match game identity"),
                rejected("null gameId", t -> t.putNull("gameId"), "gameId does not match game identity"),
                rejected("empty winner", t -> t.put("winner", ""), "missing winner name"),
                rejected("blank winner", t -> t.put("winner", "   "), "missing winner name"),
                rejected("null winner", t -> t.putNull("winner"), "missing winner name"),
                rejected("null winner chips", t -> t.putNull("winnerChips"), "missing winner chips"),
                rejected("missing winner chips", t -> t.remove("winnerChips"), "missing winner chips"),
                rejected("abandonment with winner", t -> {
                    abandonTerminalResult(t);
                    t.put("winner", "Alice");
                }, "abandonment contains a winner"),
                rejected("abandonment with winner chips", t -> {
                    abandonTerminalResult(t);
                    t.put("winnerChips", 2000);
                }, "abandonment contains a winner"),
                rejected("abandonment with winner roster", t -> {
                    abandonTerminalResult(t);
                    finalStateOf(t).putArray("winners").add("Alice");
                }, "winner roster is incoherent"),
                rejected("abandonment with champion", t -> {
                    abandonTerminalResult(t);
                    playerOf(t, 0).put("isWinner", true);
                }, "champion does not match frozen players"),
                rejected("negative winner chips", t -> t.put("winnerChips", -1),
                        "has invalid negative winner chips"),
                rejected("empty message", t -> t.put("message", ""), "missing message"),
                rejected("blank message", t -> t.put("message", "  "), "missing message"),
                rejected("null message", t -> t.putNull("message"), "missing message"),
                rejected("missing final state", t -> t.putNull("finalState"), "missing final state projection"),

                // Final state capacity, revision and roster
                rejected("maxPlayers below 2", t -> finalStateOf(t).put("maxPlayers", 1),
                        "final state has invalid max players capacity"),
                rejected("maxPlayers above 10", t -> finalStateOf(t).put("maxPlayers", 11),
                        "final state has invalid max players capacity"),
                rejected("negative state revision", t -> finalStateOf(t).put("stateRevision", -1),
                        "has invalid negative state revision"),
                rejected("state revision above JS safe integer",
                        t -> finalStateOf(t).put("stateRevision", JS_MAX_SAFE_INTEGER + 1),
                        "has invalid negative state revision"),
                rejected("negative hand number", t -> finalStateOf(t).put("handNumber", -1),
                        "has invalid negative state revision"),
                rejected("hand number above JS safe integer",
                        t -> finalStateOf(t).put("handNumber", JS_MAX_SAFE_INTEGER + 1),
                        "has invalid negative state revision"),
                rejected("negative server time", t -> finalStateOf(t).put("serverTimeEpochMs", -1),
                        "has invalid negative state revision"),
                rejected("server time above JS safe integer",
                        t -> finalStateOf(t).put("serverTimeEpochMs", JS_MAX_SAFE_INTEGER + 1),
                        "has invalid negative state revision"),
                rejected("null players", t -> finalStateOf(t).putNull("players"),
                        "missing final players projection"),
                rejected("empty players", t -> finalStateOf(t).putArray("players"),
                        "missing final players projection"),
                rejected("more players than capacity", t -> {
                    finalStateOf(t).put("maxPlayers", 2);
                    ObjectNode third = playerOf(t, 1).deepCopy();
                    third.put("id", "c");
                    third.put("name", "Carol");
                    playersOf(t).add(third);
                }, "exceeds player capacity"),

                // Final state null fields
                rejected("null phase", t -> finalStateOf(t).putNull("phase"), "has incomplete final state"),
                rejected("null ready gate", t -> finalStateOf(t).putNull("readyGateState"),
                        "has incomplete final state"),
                rejected("null pots", t -> finalStateOf(t).putNull("pots"), "has incomplete final state"),
                rejected("null community cards", t -> finalStateOf(t).putNull("communityCards"),
                        "has incomplete final state"),
                rejected("null winners", t -> finalStateOf(t).putNull("winners"), "has incomplete final state"),
                rejected("null legal actions", t -> finalStateOf(t).putNull("legalActions"),
                        "has incomplete final state"),

                // Final state bounds
                rejected("negative pot", t -> finalStateOf(t).put("pot", -1), "has invalid final state bounds"),
                rejected("negative current bet", t -> finalStateOf(t).put("currentBet", -1),
                        "has invalid final state bounds"),
                rejected("negative uncalled amount", t -> finalStateOf(t).put("uncalledAmount", -1),
                        "has invalid final state bounds"),
                rejected("negative carry-over amount", t -> finalStateOf(t).put("carryOverAmount", -1),
                        "has invalid final state bounds"),
                rejected("negative minimum bet", t -> finalStateOf(t).put("minimumBetAmount", -1),
                        "has invalid final state bounds"),
                rejected("negative minimum raise", t -> finalStateOf(t).put("minimumRaiseAmount", -1),
                        "has invalid final state bounds"),
                rejected("negative side pot", t -> finalStateOf(t).putArray("pots").add(-1),
                        "has invalid final state bounds"),
                rejected("null side pot", t -> finalStateOf(t).putArray("pots").addNull(),
                        "has invalid final state bounds"),
                rejected("six community cards", t -> finalStateOf(t).set("communityCards", cardArray(
                        card("TWO", "CLUBS"), card("THREE", "CLUBS"), card("FOUR", "CLUBS"),
                        card("FIVE", "CLUBS"), card("SIX", "CLUBS"), card("SEVEN", "CLUBS"))),
                        "has invalid final state bounds"),

                // Live-game controls that must be cleared on a finished game
                rejected("current player name still set", t -> finalStateOf(t).put("currentPlayerName", "Alice"),
                        "contains live-game controls"),
                rejected("current player id still set", t -> finalStateOf(t).put("currentPlayerId", "a"),
                        "contains live-game controls"),
                rejected("legal actions still present", t -> finalStateOf(t).putArray("legalActions").add("FOLD"),
                        "contains live-game controls"),
                rejected("ready countdown active", t -> finalStateOf(t).put("isReadyCountdownActive", true),
                        "contains live-game controls"),
                rejected("null ready countdown flag", t -> finalStateOf(t).putNull("isReadyCountdownActive"),
                        "contains live-game controls"),
                rejected("ready countdown deadline still set",
                        t -> finalStateOf(t).put("readyCountdownDeadlineEpochMs", 1000L),
                        "contains live-game controls"),
                rejected("auto advancing", t -> finalStateOf(t).put("isAutoAdvancing", true),
                        "contains live-game controls"),
                rejected("null auto advancing flag", t -> finalStateOf(t).putNull("isAutoAdvancing"),
                        "contains live-game controls"),
                rejected("claim win available", t -> finalStateOf(t).put("claimWinAvailable", true),
                        "contains live-game controls"),
                rejected("null claim win flag", t -> finalStateOf(t).putNull("claimWinAvailable"),
                        "contains live-game controls"),
                rejected("claim win player name still set", t -> finalStateOf(t).put("claimWinPlayerName", "Alice"),
                        "contains live-game controls"),
                rejected("winnings per player still set", t -> finalStateOf(t).put("winningsPerPlayer", 5),
                        "contains live-game controls"),
                rejected("ready gate not closed", t -> finalStateOf(t).put("readyGateState", "WAITING_FOR_PLAYERS"),
                        "contains live-game controls"),

                // Community cards and winner roster
                rejected("duplicate community cards", t -> finalStateOf(t).set("communityCards",
                        cardArray(card("TEN", "CLUBS"), card("TEN", "CLUBS"))), "has invalid community cards"),
                rejected("community card with null rank", t -> finalStateOf(t).set("communityCards",
                        cardArray(card(null, "CLUBS"))), "has invalid community cards"),
                rejected("community card with null suit", t -> finalStateOf(t).set("communityCards",
                        cardArray(card("TEN", null))), "has invalid community cards"),
                rejected("null community card", t -> finalStateOf(t).set("communityCards", cardArray((ObjectNode) null)),
                        "has invalid community cards"),
                rejected("empty winner roster", t -> finalStateOf(t).putArray("winners"),
                        "winner roster is incoherent"),
                rejected("two winners in roster", t -> finalStateOf(t).putArray("winners").add("Alice").add("Bob"),
                        "winner roster is incoherent"),
                rejected("roster winner differs from winner", t -> finalStateOf(t).putArray("winners").add("Bob"),
                        "winner roster is incoherent"),

                // Player identity and fields
                rejected("null player entry", t -> playersOf(t).set(1, NullNode.getInstance()),
                        "has invalid player identity or fields"),
                rejected("null player id", t -> playerOf(t, 1).putNull("id"),
                        "has invalid player identity or fields"),
                rejected("blank player id", t -> playerOf(t, 1).put("id", " "),
                        "has invalid player identity or fields"),
                rejected("null player name", t -> playerOf(t, 1).putNull("name"),
                        "has invalid player identity or fields"),
                rejected("blank player name", t -> playerOf(t, 1).put("name", ""),
                        "has invalid player identity or fields"),
                rejected("negative player chips", t -> playerOf(t, 0).put("chips", -1),
                        "has invalid player identity or fields"),
                rejected("negative player bet", t -> playerOf(t, 1).put("currentBet", -1),
                        "has invalid player identity or fields"),
                rejected("null player status", t -> playerOf(t, 1).putNull("status"),
                        "has invalid player identity or fields"),
                rejected("unknown player status", t -> playerOf(t, 0).put("status", "UNKNOWN"),
                        "has invalid player identity or fields"),
                rejected("duplicate player id", t -> playerOf(t, 1).put("id", "a"),
                        "has invalid player identity or fields"),
                rejected("duplicate player name", t -> playerOf(t, 1).put("name", "Alice"),
                        "has invalid player identity or fields"),
                rejected("current player flag still set", t -> playerOf(t, 0).put("isCurrentPlayer", true),
                        "contains live player fields"),
                rejected("chips won still set", t -> playerOf(t, 1).put("chipsWon", 10),
                        "contains live player fields"),

                // Player cards
                rejected("duplicate best-hand cards", t -> playerOf(t, 1).set("bestHand",
                        cardArray(card("TWO", "CLUBS"), card("TWO", "CLUBS"))), "has invalid best-hand cards"),
                rejected("best hand with six cards", t -> playerOf(t, 1).set("bestHand", cardArray(
                        card("TWO", "CLUBS"), card("THREE", "CLUBS"), card("FOUR", "CLUBS"),
                        card("FIVE", "CLUBS"), card("SIX", "CLUBS"), card("SEVEN", "CLUBS"))),
                        "has invalid best-hand cards"),
                rejected("best hand with null rank", t -> playerOf(t, 1).set("bestHand",
                        cardArray(card(null, "CLUBS"))), "has invalid best-hand cards"),
                rejected("single hole card", t -> playerOf(t, 1).set("holeCards",
                        cardArray(card("TWO", "HEARTS"))), "has invalid hole cards"),
                rejected("three hole cards", t -> playerOf(t, 1).set("holeCards", cardArray(
                        card("TWO", "HEARTS"), card("THREE", "HEARTS"), card("FOUR", "HEARTS"))),
                        "has invalid hole cards"),
                rejected("hole card with null suit", t -> playerOf(t, 1).set("holeCards",
                        cardArray(card("TWO", "HEARTS"), card("THREE", null))), "has invalid hole cards"),
                rejected("null hole card", t -> playerOf(t, 1).set("holeCards",
                        cardArray(card("TWO", "HEARTS"), null)), "has invalid hole cards"),
                rejected("same hole card twice in one hand", t -> playerOf(t, 1).set("holeCards",
                        cardArray(card("TWO", "HEARTS"), card("TWO", "HEARTS"))), "has invalid hole cards"),
                rejected("hole card duplicates community card", t -> playerOf(t, 1).set("holeCards",
                        cardArray(card("TEN", "CLUBS"), card("THREE", "DIAMONDS"))), "contains duplicate hole cards"),
                rejected("hole cards duplicate another player's", t -> playerOf(t, 1).set("holeCards",
                        cardArray(card("ACE", "SPADES"), card("KING", "SPADES"))), "contains duplicate hole cards"),

                // Champion flags, deadlines and chip balance
                rejected("null champion flag", t -> playerOf(t, 1).putNull("isWinner"),
                        "has invalid champion flags"),
                rejected("null champion flag on winner", t -> playerOf(t, 0).putNull("isWinner"),
                        "has invalid champion flags"),
                rejected("negative disconnect deadline", t -> playerOf(t, 1).put("disconnectDeadlineEpochMs", -1L),
                        "has invalid player deadline"),
                rejected("disconnect deadline above JS safe integer",
                        t -> playerOf(t, 1).put("disconnectDeadlineEpochMs", JS_MAX_SAFE_INTEGER + 1),
                        "has invalid player deadline"),
                rejected("no champion flagged", t -> playerOf(t, 0).put("isWinner", false),
                        "champion does not match frozen players"),
                rejected("two champions flagged", t -> playerOf(t, 1).put("isWinner", true),
                        "champion does not match frozen players"),
                rejected("champion is not the winner", t -> {
                    playerOf(t, 0).put("isWinner", false);
                    playerOf(t, 1).put("isWinner", true);
                }, "champion does not match frozen players"),
                rejected("champion chips below winner chips", t -> playerOf(t, 0).put("chips", 1999),
                        "champion does not match frozen players"),
                rejected("champion chips above winner chips", t -> playerOf(t, 0).put("chips", 2001),
                        "champion does not match frozen players"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("corruptTerminalResults")
    @DisplayName("deserialize rejects a corrupt retained terminal result with the specific message")
    void deserialize_CorruptRetainedTerminalResult_IsRejectedWithSpecificMessage(String name,
            Consumer<ObjectNode> corruption, String expectedMessage) {
        ObjectNode snapshot = validTerminalSnapshotTree();
        corruption.accept((ObjectNode) snapshot.at("/game/retainedTerminalResult"));

        PersistenceException ex = assertThrows(PersistenceException.class,
                () -> newMapper().deserialize(JSON.writeValueAsBytes(snapshot)));

        assertEquals(expectedMessage, ex.getMessage(), name);
    }

    @Test
    @DisplayName("The unmodified terminal-result fixture restores, and empty hole cards are accepted")
    void deserialize_ValidRetainedTerminalResult_Restores() {
        ObjectNode snapshot = validTerminalSnapshotTree();
        assertEquals("Alice", newMapper().deserialize(JSON.writeValueAsBytes(snapshot))
                .game().getRetainedTerminalResult().winner());

        playerOf((ObjectNode) snapshot.at("/game/retainedTerminalResult"), 1).set("holeCards", cardArray());
        RecoveredAggregate recovered = newMapper().deserialize(JSON.writeValueAsBytes(snapshot));
        assertEquals(List.of(), recovered.game().getRetainedTerminalResult().finalState().players().get(1).holeCards());
    }

    @Test
    void abandonedGameRoundTripPreservesOutcomeWithoutWinner() {
        HandEvaluatorService evaluator = new HandEvaluatorService();
        Room room = new Room("room-abandoned", "Night Game", "Alice", 6, 5, 10, 1000, null);
        room.addPlayer("Alice");
        room.addPlayer("Bob");
        room.setGameStarted(true);
        Game game = new Game(room.getRoomId(), List.of(
                new Player("Alice", "alice-id", 1000),
                new Player("Bob", "bob-id", 1000)), 5, 10, evaluator);
        game.resetForNewHand();
        game.dealHoleCards();
        game.postBlinds();
        game.markGameOver();
        game.closeReadyCountdown();
        var projections = new GameStateService(null, null);
        GameEndResponse result = projections.captureAbandonment(room.getRoomId(), game, room.getMaxPlayers());
        game.setRetainedTerminalResult(result);
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(JSON, evaluator);

        GameEndResponse restored = mapper.deserialize(mapper.serialize(room, "Alice", game))
                .game().getRetainedTerminalResult();

        assertEquals(result, restored);
        assertTrue(restored.abandoned());
        assertNull(restored.winner());
        assertNull(restored.winnerChips());
        assertTrue(restored.finalState().winners().isEmpty());
    }

    static Stream<Arguments> corruptGames() {
        return Stream.of(
                Arguments.of("game id differs from room id", (Consumer<ObjectNode>) g -> g.put("gameId", "other-room"),
                        "Game identity does not match room identity in snapshot"),
                Arguments.of("null game id", (Consumer<ObjectNode>) g -> g.putNull("gameId"),
                        "Game identity does not match room identity in snapshot"),
                Arguments.of("negative pot", (Consumer<ObjectNode>) g -> g.put("pot", -1),
                        "Invalid negative pot in snapshot"),
                Arguments.of("negative state revision", (Consumer<ObjectNode>) g -> g.put("stateRevision", -1),
                        "Invalid negative public state revision in snapshot"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("corruptGames")
    @DisplayName("deserialize rejects a corrupt game aggregate with the specific message")
    void deserialize_CorruptGame_IsRejectedWithSpecificMessage(String name, Consumer<ObjectNode> corruption,
            String expectedMessage) {
        ObjectNode snapshot = validTerminalSnapshotTree();
        corruption.accept((ObjectNode) snapshot.get("game"));

        PersistenceException ex = assertThrows(PersistenceException.class,
                () -> newMapper().deserialize(JSON.writeValueAsBytes(snapshot)));

        assertEquals(expectedMessage, ex.getMessage(), name);
    }

    private static AggregateStateImage.RoomState roomState(String roomId, int maxPlayers, int smallBlind,
            int bigBlind, int buyIn) {
        return new AggregateStateImage.RoomState(roomId, "Room", "Host", maxPlayers, smallBlind, bigBlind, buyIn,
                null, null, Map.of(), false);
    }

    private static AggregateStateImage lobbyImage(int schemaVersion, AggregateStateImage.RoomState room) {
        return new AggregateStateImage(schemaVersion, false, room, "Host", null);
    }

    static Stream<Arguments> invalidImages() {
        int current = AggregateStateImage.CURRENT_SCHEMA_VERSION;
        String unsupported = "Unsupported in-memory aggregate image";
        String missingRoom = "Missing required room state in aggregate snapshot";
        String badParameters = "Invalid room parameters in snapshot";
        return Stream.of(
                Arguments.of("null image", null, unsupported),
                Arguments.of("older schema version", lobbyImage(current - 1, roomState("r", 6, 5, 10, 100)),
                        unsupported),
                Arguments.of("newer schema version", lobbyImage(current + 1, roomState("r", 6, 5, 10, 100)),
                        unsupported),
                Arguments.of("missing room", lobbyImage(current, null), missingRoom),
                Arguments.of("null room id", lobbyImage(current, roomState(null, 6, 5, 10, 100)), missingRoom),
                Arguments.of("empty room id", lobbyImage(current, roomState("", 6, 5, 10, 100)), missingRoom),
                Arguments.of("blank room id", lobbyImage(current, roomState("   ", 6, 5, 10, 100)), missingRoom),
                Arguments.of("maxPlayers below 2", lobbyImage(current, roomState("r", 1, 5, 10, 100)), badParameters),
                Arguments.of("maxPlayers above 10", lobbyImage(current, roomState("r", 11, 5, 10, 100)),
                        badParameters),
                Arguments.of("small blind below 1", lobbyImage(current, roomState("r", 6, 0, 10, 100)),
                        badParameters),
                Arguments.of("big blind below twice the small blind",
                        lobbyImage(current, roomState("r", 6, 5, 9, 100)), badParameters),
                Arguments.of("buy-in below the big blind", lobbyImage(current, roomState("r", 6, 5, 10, 9)),
                        badParameters));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("invalidImages")
    @DisplayName("restoreStateImage rejects an invalid image with the specific message")
    void restoreStateImage_InvalidImage_IsRejectedWithSpecificMessage(String name, AggregateStateImage image,
            String expectedMessage) {
        PersistenceException ex = assertThrows(PersistenceException.class,
                () -> newMapper().restoreStateImage(image));

        assertEquals(expectedMessage, ex.getMessage(), name);
    }

    @Test
    @DisplayName("restoreStateImage accepts the boundary room parameters")
    void restoreStateImage_BoundaryRoomParameters_AreAccepted() {
        AggregateSnapshotMapper mapper = newMapper();
        int current = AggregateStateImage.CURRENT_SCHEMA_VERSION;

        Room minimal = mapper.restoreStateImage(lobbyImage(current, roomState("r", 2, 1, 2, 2))).room();
        Room maximal = mapper.restoreStateImage(lobbyImage(current, roomState("r", 10, 5, 10, 10))).room();

        assertEquals(2, minimal.getMaxPlayers());
        assertEquals(10, maximal.getMaxPlayers());
    }

    @Test
    @DisplayName("A deleted image restores to a deleted marker without room or game, even from a tombstone")
    void restoreStateImage_DeletedImage_ReturnsDeletedMarker() {
        AggregateSnapshotMapper mapper = newMapper();

        RecoveredAggregate fromImage = mapper.restoreStateImage(
                new AggregateStateImage(AggregateStateImage.CURRENT_SCHEMA_VERSION, true, null, null, null));
        RecoveredAggregate fromTombstone = mapper.deserialize(mapper.serializeDeletion("gone-room"));

        for (RecoveredAggregate recovered : List.of(fromImage, fromTombstone)) {
            assertTrue(recovered.deleted());
            assertNull(recovered.room());
            assertNull(recovered.game());
            assertNull(recovered.currentHost());
        }
    }

    private static Map<String, LocalDateTime> joinTimes(String... members) {
        Map<String, LocalDateTime> joined = new LinkedHashMap<>();
        for (String member : members) {
            joined.put(member, LocalDateTime.of(2024, 1, 1, 12, 0));
        }
        return joined;
    }

    static Stream<Arguments> hostReconciliations() {
        return Stream.of(
                Arguments.of("current host is in the room", "Bob", "Alice", joinTimes("Alice", "Bob"), "Bob"),
                Arguments.of("current host left, original host is in the room", "Zed", "Alice",
                        joinTimes("Carol", "Alice"), "Alice"),
                Arguments.of("no current host, original host is in the room", null, "Alice",
                        joinTimes("Carol", "Alice"), "Alice"),
                Arguments.of("neither host is in the room, first member is used", "Zed", "Yan",
                        joinTimes("Carol", "Dave"), "Carol"),
                Arguments.of("no current host and original host left, first member is used", null, "Yan",
                        joinTimes("Carol", "Dave"),
                        "Carol"),
                Arguments.of("empty room has no host", "Zed", "Yan", joinTimes(), null),
                Arguments.of("no join times has no host", "Zed", "Yan", null, null));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("hostReconciliations")
    @DisplayName("restoreStateImage reconciles the host against actual room membership")
    void restoreStateImage_HostReconciliation_FallsBackThroughMembership(String name, String currentHost,
            String originalHost, Map<String, LocalDateTime> members, String expectedHost) {
        AggregateStateImage.RoomState room = new AggregateStateImage.RoomState("r", "Room", originalHost, 6, 5, 10,
                100, null, null, members, false);
        AggregateStateImage image = new AggregateStateImage(AggregateStateImage.CURRENT_SCHEMA_VERSION, false, room,
                currentHost, null);

        RecoveredAggregate recovered = newMapper().restoreStateImage(image);

        assertEquals(expectedHost, recovered.currentHost(), name);
        assertFalse(recovered.deleted());
        assertNull(recovered.game());
    }

    @Test
    @DisplayName("captureStateImage rejects a null room with a PersistenceException")
    void captureStateImage_NullRoom_IsRejected() {
        PersistenceException ex = assertThrows(PersistenceException.class,
                () -> newMapper().captureStateImage(null, "Host", null));

        assertEquals("Room cannot be null for aggregate state capture", ex.getMessage());
    }

    @Test
    @DisplayName("serialize rejects a null room and a null state image")
    void serialize_NullInputs_AreRejected() {
        AggregateSnapshotMapper mapper = newMapper();

        PersistenceException nullImage = assertThrows(PersistenceException.class,
                () -> mapper.serialize((AggregateStateImage) null));
        PersistenceException nullRoom = assertThrows(PersistenceException.class,
                () -> mapper.serialize(null, "Host", null));

        assertEquals("State image cannot be null for serialization", nullImage.getMessage());
        assertEquals("Room cannot be null for aggregate state capture", nullRoom.getMessage());
    }

    @Test
    @DisplayName("deserialize wraps undecodable bytes in a PersistenceException")
    void deserialize_GarbageBytes_AreRejected() {
        PersistenceException ex = assertThrows(PersistenceException.class,
                () -> newMapper().deserialize("this is not json".getBytes(StandardCharsets.UTF_8)));

        assertEquals("Cannot decode aggregate snapshot", ex.getMessage());
    }

    @Test
    @DisplayName("restoreRoom and toState(Room) return null for null input and restore a state without join times")
    void restoreRoom_NullAndMissingJoinTimes_AreHandled() {
        AggregateSnapshotMapper mapper = newMapper();

        assertNull(mapper.restoreRoom(null));
        assertNull(mapper.toState((Room) null, "Host"));

        Room restored = mapper.restoreRoom(new AggregateStateImage.RoomState("r", "Room", "Host", 6, 5, 10, 100,
                null, null, null, false));
        assertEquals("r", restored.getRoomId());
        assertTrue(restored.getPlayersWithJoinTimeSnapshot().isEmpty());

        Room live = new Room("live", "Live", "Host", 4, 5, 10, 100, null);
        live.addPlayer("Host");
        AggregateStateImage.RoomState captured = mapper.toState(live, "Host");
        assertEquals("live", captured.roomId());
        assertEquals(List.of("Host"), List.copyOf(captured.playersWithJoinTime().keySet()));
    }

    @Nested
    class SnapshotRecoveryScenarios {


        private static final String ROOM_ID = "empirical-room-m2";
        private HandEvaluatorService handEvaluator;
        private AggregateSnapshotMapper snapshotMapper;

        @BeforeEach
        void setUp() {
            handEvaluator = new HandEvaluatorService();
            JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();
            snapshotMapper = new AggregateSnapshotMapper(jsonMapper, handEvaluator);
        }

        private Player createPlayer(String id, String name, int chips) {
            return new Player(name, id, chips);
        }

        private Room createRoom(String roomId, List<Player> players) {
            Room room = new Room(roomId, "Empirical Table", players.getFirst().getName(), 6, 10, 20, 1000, "secret-pass");
            for (Player p : players) {
                room.addPlayer(p.getName());
            }
            room.setGameStarted(true);
            return room;
        }

        // =========================================================================
        // 1. Snapshot and WAL Recovery Across All 5 Game Phases
        // =========================================================================

        @Test
        @DisplayName("Phase 1: PRE_FLOP snapshot serialization and WAL recovery round-trip preserves state & action continuity")
        void testPhase_PreFlop_WalRecoveryAndActionContinuity(@TempDir Path tempDir) {
            Player p1 = createPlayer("p1", "Alice", 1000);
            Player p2 = createPlayer("p2", "Bob", 1000);
            Player p3 = createPlayer("p3", "Charlie", 1000);
            List<Player> players = List.of(p1, p2, p3);
            Room room = createRoom(ROOM_ID, players);

            Game game = new Game(ROOM_ID, players, 10, 20, handEvaluator);
            assertFalse(game.resetForNewHand());
            game.dealHoleCards();
            game.postBlinds();

            assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
            assertEquals(30, game.getPot());
            assertEquals(20, game.getCurrentHighestBet());

            // Snapshot via mapper
            byte[] snapshotBytes = snapshotMapper.serialize(room, "Alice", game);

            // Store into real encrypted WAL
            EncryptionKeyring keyring = new EncryptionKeyring("k1", Map.of("k1", new SecretKeySpec(new byte[32], "AES")));
            EncryptedWalStore walStore = new EncryptedWalStore(tempDir, keyring);
            WalTransaction tx = walStore.prepare(ROOM_ID);
            walStore.commit(tx, snapshotBytes);

            // Recover from WAL
            Map<String, byte[]> recoveredMap = walStore.recoverAll();
            assertTrue(recoveredMap.containsKey(ROOM_ID));
            RecoveredAggregate recovered = snapshotMapper.deserialize(recoveredMap.get(ROOM_ID));
            Game restored = recovered.game();

            assertNotNull(restored);
            assertEquals(ROOM_ID, restored.getGameId());
            assertEquals(GamePhase.PRE_FLOP, restored.getCurrentPhase());
            assertEquals(30, restored.getPot());
            assertEquals(20, restored.getCurrentHighestBet());
            assertEquals(3, restored.getPlayers().size());
            assertEquals(3, restored.getActivePlayers().size());
            assertEquals(game.getCurrentPlayer().getPlayerId(), restored.getCurrentPlayer().getPlayerId());
            assertEquals(game.getRemainingDeckSnapshot().size(), restored.getRemainingDeckSnapshot().size());
            assertEquals(game.getHandSeatOrderSnapshot(), restored.getHandSeatOrderSnapshot());
            assertEquals(game.getDealerAnchorPlayerId(), restored.getDealerAnchorPlayerId());

            // Verify hole cards match
            for (int i = 0; i < 3; i++) {
                assertEquals(game.getPlayers().get(i).getHoleCards(), restored.getPlayers().get(i).getHoleCards());
            }

            // Action continuity: Current player calls on restored instance
            Player current = restored.getCurrentPlayer();
            restored.processPlayerDecision(current, new PlayerDecision(PlayerAction.CALL, 0, current.getPlayerId()));
            assertEquals(50, restored.getPot(), "Pot must increase to 50 following call on restored game");
        }

        @Test
        @DisplayName("Phase 2: FLOP snapshot serialization and WAL recovery round-trip preserves 3 community cards")
        void testPhase_Flop_WalRecoveryAndProgression(@TempDir Path tempDir) {
            Player p1 = createPlayer("p1", "Alice", 1000);
            Player p2 = createPlayer("p2", "Bob", 1000);
            List<Player> players = List.of(p1, p2);
            Room room = createRoom(ROOM_ID, players);

            Game game = new Game(ROOM_ID, players, 10, 20, handEvaluator);
            assertFalse(game.resetForNewHand());
            game.dealHoleCards();
            game.postBlinds();
            game.dealFlop();

            assertEquals(GamePhase.FLOP, game.getCurrentPhase());
            assertEquals(3, game.getCommunityCards().size());

            byte[] snapshotBytes = snapshotMapper.serialize(room, "Alice", game);

            // WAL round-trip
            EncryptionKeyring keyring = new EncryptionKeyring("k1", Map.of("k1", new SecretKeySpec(new byte[32], "AES")));
            EncryptedWalStore walStore = new EncryptedWalStore(tempDir, keyring);
            WalTransaction tx = walStore.prepare(ROOM_ID);
            walStore.commit(tx, snapshotBytes);

            RecoveredAggregate recovered = snapshotMapper.deserialize(walStore.recoverAll().get(ROOM_ID));
            Game restored = recovered.game();

            assertEquals(GamePhase.FLOP, restored.getCurrentPhase());
            assertEquals(3, restored.getCommunityCards().size());
            assertEquals(game.getCommunityCards(), restored.getCommunityCards());
            assertEquals(game.getRemainingDeckSnapshot().size(), restored.getRemainingDeckSnapshot().size());

            // Advance to Turn on restored instance
            restored.dealTurn();
            assertEquals(GamePhase.TURN, restored.getCurrentPhase());
            assertEquals(4, restored.getCommunityCards().size());
        }

        @Test
        @DisplayName("Phase 3: TURN snapshot serialization and WAL recovery round-trip preserves 4 community cards")
        void testPhase_Turn_WalRecoveryAndProgression(@TempDir Path tempDir) {
            Player p1 = createPlayer("p1", "Alice", 1000);
            Player p2 = createPlayer("p2", "Bob", 1000);
            List<Player> players = List.of(p1, p2);
            Room room = createRoom(ROOM_ID, players);

            Game game = new Game(ROOM_ID, players, 10, 20, handEvaluator);
            assertFalse(game.resetForNewHand());
            game.dealHoleCards();
            game.postBlinds();
            game.dealFlop();
            game.dealTurn();

            assertEquals(GamePhase.TURN, game.getCurrentPhase());
            assertEquals(4, game.getCommunityCards().size());

            byte[] snapshotBytes = snapshotMapper.serialize(room, "Alice", game);

            // WAL round-trip
            EncryptionKeyring keyring = new EncryptionKeyring("k1", Map.of("k1", new SecretKeySpec(new byte[32], "AES")));
            EncryptedWalStore walStore = new EncryptedWalStore(tempDir, keyring);
            WalTransaction tx = walStore.prepare(ROOM_ID);
            walStore.commit(tx, snapshotBytes);

            RecoveredAggregate recovered = snapshotMapper.deserialize(walStore.recoverAll().get(ROOM_ID));
            Game restored = recovered.game();

            assertEquals(GamePhase.TURN, restored.getCurrentPhase());
            assertEquals(4, restored.getCommunityCards().size());
            assertEquals(game.getCommunityCards(), restored.getCommunityCards());

            // Advance to River on restored instance
            restored.dealRiver();
            assertEquals(GamePhase.RIVER, restored.getCurrentPhase());
            assertEquals(5, restored.getCommunityCards().size());
        }

        @Test
        @DisplayName("Phase 4: RIVER snapshot serialization and WAL recovery round-trip preserves 5 community cards")
        void testPhase_River_WalRecoveryAndShowdown(@TempDir Path tempDir) {
            Player p1 = createPlayer("p1", "Alice", 1000);
            Player p2 = createPlayer("p2", "Bob", 1000);
            List<Player> players = List.of(p1, p2);
            Room room = createRoom(ROOM_ID, players);

            Game game = new Game(ROOM_ID, players, 10, 20, handEvaluator);
            assertFalse(game.resetForNewHand());
            game.dealHoleCards();
            game.postBlinds();
            game.dealFlop();
            game.dealTurn();
            game.dealRiver();

            assertEquals(GamePhase.RIVER, game.getCurrentPhase());
            assertEquals(5, game.getCommunityCards().size());

            byte[] snapshotBytes = snapshotMapper.serialize(room, "Alice", game);

            // WAL round-trip
            EncryptionKeyring keyring = new EncryptionKeyring("k1", Map.of("k1", new SecretKeySpec(new byte[32], "AES")));
            EncryptedWalStore walStore = new EncryptedWalStore(tempDir, keyring);
            WalTransaction tx = walStore.prepare(ROOM_ID);
            walStore.commit(tx, snapshotBytes);

            RecoveredAggregate recovered = snapshotMapper.deserialize(walStore.recoverAll().get(ROOM_ID));
            Game restored = recovered.game();

            assertEquals(GamePhase.RIVER, restored.getCurrentPhase());
            assertEquals(5, restored.getCommunityCards().size());
            assertEquals(game.getCommunityCards(), restored.getCommunityCards());

            // Conduct Showdown on restored instance
            assertDoesNotThrow(restored::conductShowdown);
            assertNotNull(restored.getLastSettlementResult());
            assertTrue(restored.getLastSettlementResult().settled());
        }

        @Test
        @DisplayName("Phase 5: SHOWDOWN snapshot serialization and WAL recovery preserves HandSettlementResult & resetForNewHand")
        void testPhase_Showdown_WalRecoveryAndResetForNewHand(@TempDir Path tempDir) {
            Player p1 = createPlayer("p1", "Alice", 1000);
            Player p2 = createPlayer("p2", "Bob", 1000);
            List<Player> players = List.of(p1, p2);
            Room room = createRoom(ROOM_ID, players);

            Game game = new Game(ROOM_ID, players, 10, 20, handEvaluator);
            assertFalse(game.resetForNewHand());
            game.dealHoleCards();
            game.postBlinds();
            game.dealFlop();
            game.dealTurn();
            game.dealRiver();
            game.conductShowdown();

            assertNotNull(game.getLastSettlementResult());
            HandSettlementResult originalSettlement = game.getLastSettlementResult();

            byte[] snapshotBytes = snapshotMapper.serialize(room, "Alice", game);

            // WAL round-trip
            EncryptionKeyring keyring = new EncryptionKeyring("k1", Map.of("k1", new SecretKeySpec(new byte[32], "AES")));
            EncryptedWalStore walStore = new EncryptedWalStore(tempDir, keyring);
            WalTransaction tx = walStore.prepare(ROOM_ID);
            walStore.commit(tx, snapshotBytes);

            RecoveredAggregate recovered = snapshotMapper.deserialize(walStore.recoverAll().get(ROOM_ID));
            Game restored = recovered.game();

            assertNotNull(restored.getLastSettlementResult());
            HandSettlementResult restoredSettlement = restored.getLastSettlementResult();

            assertEquals(originalSettlement.contestedAwards(), restoredSettlement.contestedAwards());
            assertEquals(originalSettlement.refunds(), restoredSettlement.refunds());
            assertEquals(originalSettlement.winnerIds(), restoredSettlement.winnerIds());
            assertEquals(originalSettlement.remainder(), restoredSettlement.remainder());
            assertEquals(originalSettlement.settled(), restoredSettlement.settled());

            // Perform resetForNewHand on restored game
            assertDoesNotThrow(restored::resetForNewHand);
            assertEquals(GamePhase.PRE_FLOP, restored.getCurrentPhase());
            assertTrue(restored.getCommunityCards().isEmpty());
        }

        // =========================================================================
        // 2. Ready Gate Countdown State Restoration
        // =========================================================================

        @Test
        @DisplayName("ReadyGateState COUNTING_DOWN: Restores active flag, deadline, and enum state")
        void testReadyGateState_CountingDown() {
            Player p1 = createPlayer("p1", "Alice", 1000);
            Player p2 = createPlayer("p2", "Bob", 1000);
            List<Player> players = List.of(p1, p2);
            Room room = createRoom(ROOM_ID, players);
            long deadline = System.currentTimeMillis() + 10000L;

            Game game = Game.restoreBuilder()
                    .gameId(ROOM_ID)
                    .players(players)
                    .activePlayerIds(List.of("p1", "p2"))
                    .smallBlind(10)
                    .bigBlind(20)
                    .currentPhase(GamePhase.PRE_FLOP)
                    .readyCountdownActive(true)
                    .readyCountdownDeadlineEpochMs(deadline)
                    .readyGateState(ReadyGateState.COUNTING_DOWN)
                    .handEvaluator(handEvaluator)
                    .build();

            assertEquals(ReadyGateState.COUNTING_DOWN, game.getReadyGateState());
            assertTrue(game.isReadyCountdownActive());
            assertEquals(deadline, game.getReadyCountdownDeadlineEpochMs());

            byte[] bytes = snapshotMapper.serialize(room, "Alice", game);
            Game restored = snapshotMapper.deserialize(bytes).game();

            assertEquals(ReadyGateState.COUNTING_DOWN, restored.getReadyGateState());
            assertTrue(restored.isReadyCountdownActive());
            assertEquals(deadline, restored.getReadyCountdownDeadlineEpochMs());
        }

        @Test
        @DisplayName("ReadyGateState WAITING_FOR_PLAYERS: Restores inactive flag, null deadline, and enum state")
        void testReadyGateState_WaitingForPlayers() {
            Player p1 = createPlayer("p1", "Alice", 1000);
            Player p2 = createPlayer("p2", "Bob", 1000);
            List<Player> players = List.of(p1, p2);
            Room room = createRoom(ROOM_ID, players);

            Game game = Game.restoreBuilder()
                    .gameId(ROOM_ID)
                    .players(players)
                    .activePlayerIds(List.of("p1", "p2"))
                    .smallBlind(10)
                    .bigBlind(20)
                    .currentPhase(GamePhase.PRE_FLOP)
                    .readyCountdownActive(false)
                    .readyCountdownDeadlineEpochMs(null)
                    .readyGateState(ReadyGateState.WAITING_FOR_PLAYERS)
                    .handEvaluator(handEvaluator)
                    .build();

            assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, game.getReadyGateState());
            assertFalse(game.isReadyCountdownActive());
            assertNull(game.getReadyCountdownDeadlineEpochMs());

            byte[] bytes = snapshotMapper.serialize(room, "Alice", game);
            Game restored = snapshotMapper.deserialize(bytes).game();

            assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, restored.getReadyGateState());
            assertFalse(restored.isReadyCountdownActive());
            assertNull(restored.getReadyCountdownDeadlineEpochMs());
        }

        @Test
        @DisplayName("ReadyGateState CLOSED: Restores inactive flag and CLOSED enum state")
        void testReadyGateState_Closed() {
            Player p1 = createPlayer("p1", "Alice", 1000);
            Player p2 = createPlayer("p2", "Bob", 1000);
            List<Player> players = List.of(p1, p2);
            Room room = createRoom(ROOM_ID, players);

            Game game = Game.restoreBuilder()
                    .gameId(ROOM_ID)
                    .players(players)
                    .activePlayerIds(List.of("p1", "p2"))
                    .smallBlind(10)
                    .bigBlind(20)
                    .currentPhase(GamePhase.PRE_FLOP)
                    .readyCountdownActive(false)
                    .readyCountdownDeadlineEpochMs(null)
                    .readyGateState(ReadyGateState.CLOSED)
                    .handEvaluator(handEvaluator)
                    .build();

            assertEquals(ReadyGateState.CLOSED, game.getReadyGateState());
            assertFalse(game.isReadyCountdownActive());

            byte[] bytes = snapshotMapper.serialize(room, "Alice", game);
            Game restored = snapshotMapper.deserialize(bytes).game();

            assertEquals(ReadyGateState.CLOSED, restored.getReadyGateState());
            assertFalse(restored.isReadyCountdownActive());
        }

        @Test
        @DisplayName("ReadyGateState Legacy Fallbacks: Null gate state correctly falls back to COUNTING_DOWN or CLOSED")
        void testReadyGateState_LegacyFallbacks() {
            Player p1 = createPlayer("p1", "Alice", 1000);
            Player p2 = createPlayer("p2", "Bob", 1000);
            List<Player> players = List.of(p1, p2);

            // Fallback with readyCountdownActive = true -> COUNTING_DOWN
            Game activeCountdownGame = Game.restoreBuilder()
                    .gameId(ROOM_ID)
                    .players(players)
                    .activePlayerIds(List.of("p1", "p2"))
                    .smallBlind(10)
                    .bigBlind(20)
                    .readyCountdownActive(true)
                    .readyGateState(null)
                    .handEvaluator(handEvaluator)
                    .build();
            assertEquals(ReadyGateState.COUNTING_DOWN, activeCountdownGame.getReadyGateState());

            // Fallback with readyCountdownActive = false -> CLOSED
            Game inactiveCountdownGame = Game.restoreBuilder()
                    .gameId(ROOM_ID)
                    .players(players)
                    .activePlayerIds(List.of("p1", "p2"))
                    .smallBlind(10)
                    .bigBlind(20)
                    .readyCountdownActive(false)
                    .readyGateState(null)
                    .handEvaluator(handEvaluator)
                    .build();
            assertEquals(ReadyGateState.CLOSED, inactiveCountdownGame.getReadyGateState());
        }

        // =========================================================================
        // 3. Defensive Isolation & Scheduled Tasks
        // =========================================================================

        @Test
        @DisplayName("Scheduled Tasks & Defensive Isolation: Validates snapshot round-trip of task deadlines & generations")
        void testScheduledTasksAndGenerationsRoundTrip() {
            Player p1 = createPlayer("p1", "Alice", 1000);
            Player p2 = createPlayer("p2", "Bob", 1000);
            List<Player> players = List.of(p1, p2);
            Room room = createRoom(ROOM_ID, players);

            Map<ScheduledGameTask, Long> deadlines = new EnumMap<>(ScheduledGameTask.class);
            deadlines.put(ScheduledGameTask.AUTO_ADVANCE, 1700000010000L);
            deadlines.put(ScheduledGameTask.READY_TIMEOUT, 1700000020000L);

            Map<ScheduledGameTask, Long> generations = new EnumMap<>(ScheduledGameTask.class);
            generations.put(ScheduledGameTask.AUTO_ADVANCE, 3L);
            generations.put(ScheduledGameTask.READY_TIMEOUT, 7L);

            Game game = Game.restoreBuilder()
                    .gameId(ROOM_ID)
                    .players(players)
                    .activePlayerIds(List.of("p1", "p2"))
                    .smallBlind(10)
                    .bigBlind(20)
                    .currentPhase(GamePhase.PRE_FLOP)
                    .scheduledTaskDeadlines(deadlines)
                    .scheduledTaskGenerations(generations)
                    .stateRevision(42L)
                    .handNumber(5L)
                    .handEvaluator(handEvaluator)
                    .build();

            assertEquals(42L, game.getStateRevision());
            assertEquals(5L, game.getHandNumber());
            assertEquals(deadlines, game.getScheduledTaskDeadlinesSnapshot());
            assertEquals(generations, game.getScheduledTaskGenerationsSnapshot());

            byte[] bytes = snapshotMapper.serialize(room, "Alice", game);
            Game restored = snapshotMapper.deserialize(bytes).game();

            assertEquals(42L, restored.getStateRevision());
            assertEquals(5L, restored.getHandNumber());
            assertEquals(deadlines, restored.getScheduledTaskDeadlinesSnapshot());
            assertEquals(generations, restored.getScheduledTaskGenerationsSnapshot());
        }
    }
}
