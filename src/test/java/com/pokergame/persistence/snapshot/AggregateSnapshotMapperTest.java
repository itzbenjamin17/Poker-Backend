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
import com.pokergame.util.RoomCoordinator;
import com.pokergame.service.GameStateService;
import com.pokergame.util.LockLease;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import com.pokergame.config.RoomTimingProperties;
import com.pokergame.enums.HandRank;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.persistence.config.PersistenceException;
import com.pokergame.service.RoomService;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HashMap;
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
import tools.jackson.databind.node.ObjectNode;
import java.io.UncheckedIOException;

/** Tests aggregate snapshot mapper behavior. */
@Tag("unit")
class AggregateSnapshotMapperTest {

    /**
     * Protects the contract that active game round trip preserves exact next cards and authoritative state.
     */
    @Test
    void activeGameRoundTripPreservesExactNextCardsAndAuthoritativeState() {
        Room room = new Room("room-1", "Night Game", "Alice", 6, 5, 10, 1000, "secret");
        room.addPlayer("Alice");
        room.addPlayer("Bob");
        room.setGameStarted(true);
        Game game = new Game("room-1", List.of(
                new Player("Alice", "alice-id", 1000),
                new Player("Bob", "bob-id", 1000)), 6, 5, 10);
        assertFalse(game.resetForNewHand());
        game.dealHoleCards();
        game.postBlinds();

        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build());
        RecoveredAggregate recovered = mapper.deserialize(mapper.serialize(mapper.captureStateImage(room, game)));

        assertEquals(room.getRoomName(), recovered.room().getRoomName());
        assertEquals(room.getHostName(), recovered.room().getHostName());
        assertTrue(recovered.room().checkPassword("secret"));
        assertEquals(game.getPot(), recovered.game().getPot());
        assertEquals(game.getCurrentPlayer().getPlayerId(), recovered.game().getCurrentPlayer().getPlayerId());
        assertEquals(game.getPlayers().getFirst().getHoleCards(),
                recovered.game().getPlayers().getFirst().getHoleCards());

        game.dealFlop();
        recovered.game().dealFlop();
        assertEquals(game.getCommunityCards(), recovered.game().getCommunityCards());
        assertEquals(game.getCarryOverPot(), recovered.game().getCarryOverPot());
        assertEquals(game.getHandSeatOrderSnapshot(), recovered.game().getHandSeatOrderSnapshot());
    }

    @ParameterizedTest
    @MethodSource("inactivityDeadlines")
    void restoreStateImagePreservesTheAcceptedInactivityDeadline(Long deadline) {
        Room room = new Room("room-1", "Night Game", "Alice", 6, 5, 10, 1000, null);
        room.addPlayer("Alice");
        room.addPlayer("Bob");
        room.setGameStarted(true);
        room.setInactivityDeadlineEpochMs(deadline);
        Game game = new Game("room-1", List.of(
                new Player("Alice", "alice-id", 1000),
                new Player("Bob", "bob-id", 1000)), 6, 5, 10);
        AggregateSnapshotMapper mapper = newMapper();

        RecoveredAggregate restored = mapper.restoreStateImage(mapper.captureStateImage(room, game));

        assertEquals(room.getRoomId(), restored.room().getRoomId());
        assertEquals(deadline, restored.room().getInactivityDeadlineEpochMs());
    }

    private static Stream<Long> inactivityDeadlines() {
        return Stream.of(null, 1L, 4_102_444_800_000L);
    }

    @Test
    void settlementAndTerminalRoundTripPreservesState() {
        Room room = new Room("room-2", "Recovery", "Alice", 4, 5, 10, 1000, "secret");
        room.addPlayer("Alice");
        room.addPlayer("Bob");
        room.setGameStarted(true);
        List<Player> players = List.of(new Player("Alice", "a", 1030), new Player("Bob", "b", 970));
        Game game = Game.restoreBuilder().gameId("room-2").players(players).activePlayerIds(List.of("a", "b"))
                .remainingDeck(List.of()).communityCards(List.of()).pot(3).currentPlayerId("a").currentHighestBet(0)
                .currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false).smallBlind(5).bigBlind(10).handContributions(Map.of()).preHandCarryOver(3)
                .lastFullRaiseByStreet(Map.of("PRE_FLOP", 10)).lastActedWagerByPlayer(Map.of("a", 20))
                .handSeatOrder(List.of("a", "b")).dealerAnchorPlayerId("a").smallBlindPlayerId("a")
                .bigBlindPlayerId("b").lastSettlementResult(new HandSettlementResult(Map.of("a", 50), Map.of("b", 2),
                        Set.of("a"), 3, true))
                .readyCountdownActive(false).everyoneHasHadInitialTurn(true).actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of(ScheduledGameTask.CLEANUP, 123L))
                .scheduledTaskGenerations(Map.of(ScheduledGameTask.CLEANUP, 9L))
                .handNumber(7).readyGateState(ReadyGateState.WAITING_FOR_PLAYERS).forfeitPending(true).stateRevision(19)
                .build();
        game.markGameOver();
        game.settleGameEnd(players.getFirst(), Set.of());
        assertEquals(1033, players.getFirst().getChips());
        assertEquals(0, game.getPot());
        assertTrue(game.isFinalPotSettled());
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build());

        byte[] snapshot = mapper.serialize(mapper.captureStateImage(room, game));
        Game restored = mapper.deserialize(snapshot).game();
        assertEquals(3, restored.getCarryOverPot());
        assertEquals(game.getLastSettlementResult(), restored.getLastSettlementResult());
        assertEquals(Map.of("PRE_FLOP", 10), restored.getLastFullRaiseByStreetSnapshot());
        assertEquals(List.of("a", "b"), restored.getHandSeatOrderSnapshot());
        assertTrue(restored.isFinalPotSettled());
        assertEquals(7, restored.getHandNumber());
        assertEquals(ReadyGateState.CLOSED, restored.getReadyGateState());
        assertEquals(9L, restored.getScheduledTaskGeneration(ScheduledGameTask.CLEANUP));
        assertTrue(restored.isForfeitPending());
        assertEquals(19, restored.getStateRevision(), "Persisted same-hand public revision must survive restart");
        String json = new String(snapshot, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"stateRevision\":19"));
        assertFalse(json.contains("schemaVersion"));
        restored.settleGameEnd(restored.getPlayers().getFirst(), Set.of());
        assertEquals(1033, restored.getPlayers().getFirst().getChips(),
                "A recovered terminal marker prevents a second carry-over payout");
        assertEquals(0, restored.getPot());
        int aliceBeforeRetry = restored.getPlayers().getFirst().getChips();
        int bobBeforeRetry = restored.getPlayers().get(1).getChips();
        assertEquals(List.of("a"), restored.conductShowdown().stream().map(Player::getPlayerId).toList());
        assertEquals(aliceBeforeRetry, restored.getPlayers().getFirst().getChips());
        assertEquals(bobBeforeRetry, restored.getPlayers().get(1).getChips());

        Map<String, Integer> mutableAwards = new HashMap<>(Map.of("a", 1));
        HandSettlementResult immutable = new HandSettlementResult(mutableAwards, Map.of(), Set.of("a"), 0, true);
        mutableAwards.put("b", 2);
        assertEquals(Map.of("a", 1), immutable.contestedAwards());
        assertThrows(UnsupportedOperationException.class, () -> immutable.contestedAwards().put("b", 2));
    }

    @Test
    void retainedTerminalResultRoundTrip() {
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
                "ðŸ† Alice wins the game with 2000 chips!", finalState);

        Game game = Game.restoreBuilder().gameId("room-terminal").players(players).activePlayerIds(List.of("a", "b"))
                .remainingDeck(List.of()).communityCards(List.of(new Card(Rank.TEN, Suit.CLUBS))).pot(0)
                .currentPlayerId("a").currentHighestBet(0)
                .currentPhase(GamePhase.SHOWDOWN).gameOver(true).smallBlind(5).bigBlind(10).handContributions(Map.of())
                .preHandCarryOver(0).lastFullRaiseByStreet(Map.of()).lastActedWagerByPlayer(Map.of())
                .handSeatOrder(List.of("a", "b")).dealerAnchorPlayerId("a").smallBlindPlayerId("a").bigBlindPlayerId("b")
                .readyCountdownActive(false).everyoneHasHadInitialTurn(true).actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of(ScheduledGameTask.CLEANUP, 999999L))
                .scheduledTaskGenerations(Map.of(ScheduledGameTask.CLEANUP, 1L))
                .handNumber(1).readyGateState(ReadyGateState.CLOSED).forfeitPending(false).stateRevision(42)
                .retainedTerminalResult(terminalResult)
                .build();

        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build());

        byte[] snapshot = mapper.serialize(mapper.captureStateImage(room, game));
        String json = new String(snapshot, StandardCharsets.UTF_8);
        assertFalse(json.contains("schemaVersion"));
        assertTrue(json.contains("\"retainedTerminalResult\""));
        assertTrue(json.contains("\"winner\":\"Alice\""));
        assertTrue(json.contains("\"winnerChips\":2000"));
        assertTrue(json.contains("\"inactivityDeadlineEpochMs\":null"));
        assertTrue(json.contains("\"abandoned\":null"));
        assertTrue(json.contains("\"reason\":null"));

        Game restored = mapper.deserialize(snapshot).game();
        assertNotNull(restored.getGameEndResult());
        GameEndResponse restoredResult = restored.getGameEndResult();
        assertEquals("GAME_END", restoredResult.type());
        assertEquals("room-terminal", restoredResult.gameId());
        assertEquals("Alice", restoredResult.winner());
        assertEquals(2000, restoredResult.winnerChips());
        assertFalse(restoredResult.isForfeit());
        assertEquals("ðŸ† Alice wins the game with 2000 chips!", restoredResult.message());
        assertNotNull(restoredResult.finalState());
        assertEquals(42, restoredResult.finalState().stateRevision());
        assertEquals(2, restoredResult.finalState().players().size());
        assertEquals("Alice", restoredResult.finalState().players().get(0).name());
        assertEquals("Bob", restoredResult.finalState().players().get(1).name());
    }

    @Test
    void midStreetRoundTripPreservesRaiseMinimumAndReopeningLegality() {
        Room room = new Room("room-3", "Betting", "A", 6, 5, 10, 1000, null);
        room.addPlayer("A"); room.addPlayer("B"); room.addPlayer("Short1"); room.addPlayer("Short2");
        room.setGameStarted(true);
        List<Player> players = List.of(bettingPlayer("A", "a", 900, 100, false),
                bettingPlayer("B", "b", 900, 100, false), bettingPlayer("Short1", "s1", 0, 150, true),
                bettingPlayer("Short2", "s2", 50, 150, false));
        Game game = Game.restoreBuilder().gameId("room-3").players(players)
                .activePlayerIds(players.stream().map(Player::getPlayerId).toList()).remainingDeck(List.of())
                .communityCards(List.of()).pot(500).currentPlayerId("a")
                .currentHighestBet(150).currentPhase(GamePhase.PRE_FLOP).gameOver(false)
                .smallBlind(5).bigBlind(10).handContributions(Map.of("a", 100, "b", 100, "s1", 150, "s2", 150))
                .preHandCarryOver(0).lastFullRaiseByStreet(Map.of("PRE_FLOP", 100))
                .lastActedWagerByPlayer(Map.of("a", 100)).handSeatOrder(List.of("a", "b", "s1", "s2"))
                .dealerAnchorPlayerId("a").smallBlindPlayerId("a").bigBlindPlayerId("b")
                .readyCountdownActive(false).everyoneHasHadInitialTurn(true).actedPlayersInRound(Set.of("a"))
                .scheduledTaskDeadlines(Map.of()).build();
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build());

        Game restored = mapper.deserialize(mapper.serialize(mapper.captureStateImage(room, game))).game();
        Player initialA = restored.getPlayers().getFirst();
        assertEquals(150, restored.getMinimumRaiseAmount(initialA));
        assertFalse(restored.getLegalActionsFor(initialA).contains(PlayerAction.RAISE));
        assertThrows(UnauthorisedActionException.class,
                () -> restored.processPlayerDecision(initialA, new PlayerDecision(PlayerAction.RAISE, 150, "a")));
        Player restoredShort = restored.getPlayers().get(3);
        restored.processPlayerDecision(restoredShort, new PlayerDecision(PlayerAction.ALL_IN, 0, "s2"));
        restored.processPlayerDecision(restored.getPlayers().get(1), new PlayerDecision(PlayerAction.CALL, 0, "b"));
        Game reopened = mapper.deserialize(mapper.serialize(mapper.captureStateImage(room, restored))).game();
        Player reopenedA = reopened.getPlayers().getFirst();
        assertEquals(200, reopened.getMinimumRaiseAmount(reopenedA));
        assertTrue(reopened.getLegalActionsFor(reopenedA).contains(PlayerAction.RAISE));
        reopened.processPlayerDecision(reopenedA, new PlayerDecision(PlayerAction.RAISE, 200, "a"));
        assertEquals(300, reopened.getCurrentHighestBet());
    }

    @Test
    void activeGameSnapshotPreservesRoomMembershipSeparatelyFromRetainedGameRows() {
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
        Game game = new Game("room-divergent", gamePlayers, 6, 5, 10);

        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build());
        byte[] snapshot = mapper.serialize(mapper.captureStateImage(room, game));

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
        assertEquals("Alice", recovered.room().getHostName(), "Current host remains an actual Room member");
    }

    @Test
    void deserializeRejectsUnknownProperty() {
        AggregateSnapshotMapper mapper = newMapper();
        ObjectNode snapshot = validTerminalSnapshotTree();
        snapshot.put("unexpectedField", "stale");

        PersistenceException ex = assertThrows(
                PersistenceException.class,
                () -> mapper.deserialize(JSON.writeValueAsBytes(snapshot)));
        assertEquals("Cannot decode aggregate snapshot", ex.getMessage());
    }

    @Test
    void deserializeRejectsMissingCreatorProperty() {
        AggregateSnapshotMapper mapper = newMapper();
        ObjectNode snapshot = validTerminalSnapshotTree();
        ((ObjectNode) snapshot.get("room")).remove("inactivityDeadlineEpochMs");

        PersistenceException ex = assertThrows(
                PersistenceException.class,
                () -> mapper.deserialize(JSON.writeValueAsBytes(snapshot)));
        assertEquals("Cannot decode aggregate snapshot", ex.getMessage());
    }

    @Test
    void lobbyRoundTripAcceptsNullInactivityDeadline() {
        Room room = new Room("room-lobby-null", "Lobby", "Alice", 6, 5, 10, 1000, null);
        room.addPlayer("Alice");
        AggregateSnapshotMapper mapper = newMapper();

        byte[] snapshot = mapper.serialize(mapper.captureStateImage(room, null));
        String json = new String(snapshot, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"inactivityDeadlineEpochMs\":null"));
        assertFalse(json.contains("schemaVersion"));

        RecoveredAggregate recovered = mapper.deserialize(snapshot);
        assertFalse(recovered.deleted());
        assertNull(recovered.game());
        assertNull(recovered.room().getInactivityDeadlineEpochMs());
        assertEquals("Alice", recovered.room().getHostName());
    }

    private Player bettingPlayer(String name, String id, int chips, int currentBet, boolean allIn) {
        return Player.restoreBuilder().name(name).playerId(id).chips(chips).currentBet(currentBet)
                .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND)
                .hasFolded(false).allIn(allIn).out(false).disconnected(false).readyForNextHand(false).build();
    }

    @Test
    @DisplayName("captureStateImage copies state into an immutable snapshot detached from live mutations")
    void captureStateImage_DetachesSnapshotFromLiveMutation() {
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build());

        Room room = new Room("room-order", "Order Room", "Alice", 6, 10, 20, 1000, null);
        room.addPlayer("Alice");
        room.addPlayer("Bob");

        Player p1 = new Player("Alice", "p1", 1000);
        Player p2 = new Player("Bob", "p2", 1000);
        Game game = new Game("room-order", List.of(p1, p2), 6, 10, 20);

        // Capture and serialize state image before mutating live game
        AggregateStateImage stateImage = mapper.captureStateImage(room, game);
        assertNotNull(stateImage);
        assertNotNull(stateImage.room());
        assertNotNull(stateImage.game());

        byte[] jsonBytes = mapper.serialize(stateImage);
        assertNotNull(jsonBytes);

        // Mutating live game after capture does not alter captured image or serialization
        p1.payChips(0, 500);
        RecoveredAggregate recovered = mapper.deserialize(jsonBytes);
        assertEquals(1000, recovered.game().getPlayers().get(0).getChips(),
                "Captured snapshot must be independent from post-capture live game mutations");
    }

    /** A restart image captured during a chip transfer must include both sides of the transfer. */
    @Test
    void concurrentStateImageCaptureAndMutation_RestoresCoherentState() throws Exception {
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build());

        String roomId = "concurrent-capture";
        Room room = new Room(roomId, "Concurrent Capture Room", "Alice", 6, 10, 20, 1000, null);
        room.addPlayer("Alice");
        room.addPlayer("Bob");
        Player alice = new Player("Alice", "p1", 1000);
        Player bob = new Player("Bob", "p2", 1000);
        Game game = new Game(roomId, List.of(alice, bob), 6, 10, 20);
        game.dealHoleCards();
        game.postBlinds();
        RoomCoordinator coordinator = new RoomCoordinator();

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<byte[]> capture;
            try (LockLease _ = coordinator.acquire(roomId)) {
                alice.payChips(0, 10);
                capture = executor.submit(() -> {
                    try (LockLease _ = coordinator.acquire(roomId)) {
                        return mapper.serialize(mapper.captureStateImage(room, game));
                    }
                });
                Awaitility.await().until(() -> coordinator.lockReservationCount(roomId) >= 2);
                bob.addChips(10);
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
        }
    }

    @Test
    @DisplayName("toState handles null game, null player, and null player elements safely")
    void toState_NullAndEdgeCases_HandledSafely() {
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build());

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
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build());

        String rawSecret = "VerySecretPassphrase123!";
        Room room = new Room("pw-room", "Protected Room", "Host", 4, 10, 20, 500, rawSecret);
        room.addPlayer("Host");

        byte[] snapshot = mapper.serialize(mapper.captureStateImage(room, null));
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
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build());

        Room publicRoom = new Room("pub-room", "Public Room", "Host", 4, 10, 20, 500, null);
        publicRoom.addPlayer("Host");

        byte[] snapshot = mapper.serialize(mapper.captureStateImage(publicRoom, null));
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
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build());

        Room room = new Room("mem-room", "Membership Room", "Alice", 4, 10, 20, 500, null);
        String aliceMemId = room.addPlayer("Alice");
        String bobMemId = room.addPlayer("Bob");

        byte[] snapshot = mapper.serialize(mapper.captureStateImage(room, null));
        RecoveredAggregate recovered = mapper.deserialize(snapshot);

        assertNotNull(recovered.room());
        assertEquals(aliceMemId, recovered.room().getMembershipId("Alice"),
                "Alice membershipId must survive WAL snapshot round-trip");
        assertEquals(bobMemId, recovered.room().getMembershipId("Bob"),
                "Bob membershipId must survive WAL snapshot round-trip");
    }

    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final String TERMINAL_ROOM_ID = "room-terminal";

    private static AggregateSnapshotMapper newMapper() {
        return new AggregateSnapshotMapper(JSON);
    }

    /**
     * Classpath location of the hand-written terminal snapshot fixture. The fixture is an
     * independent oracle (ADR-0001): it is NOT produced by {@link AggregateSnapshotMapper#captureStateImage}, so a
     * symmetric field rename in the writer and reader cannot silently keep the shape check green.
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
                .currentPlayerId("a")
                .currentHighestBet(0).currentPhase(GamePhase.SHOWDOWN).gameOver(true).smallBlind(5).bigBlind(10)
                .handContributions(Map.of()).preHandCarryOver(0).lastFullRaiseByStreet(Map.of())
                .lastActedWagerByPlayer(Map.of()).handSeatOrder(List.of("a", "b")).dealerAnchorPlayerId("a")
                .smallBlindPlayerId("a").bigBlindPlayerId("b").readyCountdownActive(false)
                .everyoneHasHadInitialTurn(true).actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of(ScheduledGameTask.CLEANUP, 999999L))
                .scheduledTaskGenerations(Map.of(ScheduledGameTask.CLEANUP, 1L))
                .handNumber(1).readyGateState(ReadyGateState.CLOSED).forfeitPending(false).stateRevision(42)
                .retainedTerminalResult(terminalResult).build();

        ObjectNode serialized = JSON.valueToTree(
                new AggregateSnapshotMapper(JSON).captureStateImage(room, game));

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

    private static ObjectNode playerOf(ObjectNode terminal, int index) {
        return (ObjectNode) ((ArrayNode) terminal.get("finalState").get("players")).get(index);
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

    @Test
    @DisplayName("The unmodified terminal-result fixture restores, and empty hole cards are accepted")
    void deserialize_ValidRetainedTerminalResult_Restores() {
        ObjectNode snapshot = validTerminalSnapshotTree();
        assertEquals("Alice", newMapper().deserialize(JSON.writeValueAsBytes(snapshot))
                .game().getGameEndResult().winner());

        playerOf((ObjectNode) snapshot.at("/game/retainedTerminalResult"), 1).set("holeCards", cardArray());
        RecoveredAggregate recovered = newMapper().deserialize(JSON.writeValueAsBytes(snapshot));
        assertEquals(List.of(), recovered.game().getGameEndResult().finalState().players().get(1).holeCards());
    }

    @Test
    void abandonedGameRoundTripPreservesOutcomeWithoutWinner() {
        Room room = new Room("room-abandoned", "Night Game", "Alice", 6, 5, 10, 1000, null);
        room.addPlayer("Alice");
        room.addPlayer("Bob");
        room.setGameStarted(true);
        Game game = new Game(room.getRoomId(), List.of(
                new Player("Alice", "alice-id", 1000),
                new Player("Bob", "bob-id", 1000)), 6, 5, 10);
        game.resetForNewHand();
        game.dealHoleCards();
        game.postBlinds();
        game.markGameOver();
        game.closeReadyCountdown();
        var projections = new GameStateService(
                Mockito.mock(RoomService.class),
                Mockito.mock(SimpMessagingTemplate.class),
                Clock.systemUTC());
        GameEndResponse result = projections.buildAbandonmentResponse(game);
        game.setGameEndResult(result);
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(JSON);

        GameEndResponse restored = mapper.deserialize(mapper.serialize(mapper.captureStateImage(room, game)))
                .game().getGameEndResult();

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
                        "Invalid negative pot in snapshot"));
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
                null, null, Map.of(), Map.of(), false, null);
    }

    private static AggregateStateImage lobbyImage(AggregateStateImage.RoomState room) {
        return new AggregateStateImage(false, room, null);
    }

    static Stream<Arguments> invalidImages() {
        String unsupported = "Unsupported in-memory aggregate image";
        String missingRoom = "Missing required room state in aggregate snapshot";
        String badParameters = "Invalid room parameters in snapshot";
        return Stream.of(
                Arguments.of("null image", null, unsupported),
                Arguments.of("missing room", lobbyImage(null), missingRoom),
                Arguments.of("null room id", lobbyImage(roomState(null, 6, 5, 10, 100)), missingRoom),
                Arguments.of("empty room id", lobbyImage(roomState("", 6, 5, 10, 100)), missingRoom),
                Arguments.of("blank room id", lobbyImage(roomState("   ", 6, 5, 10, 100)), missingRoom),
                Arguments.of("maxPlayers below 2", lobbyImage(roomState("r", 1, 5, 10, 100)), badParameters),
                Arguments.of("maxPlayers above 10", lobbyImage(roomState("r", 11, 5, 10, 100)),
                        badParameters),
                Arguments.of("small blind below 1", lobbyImage(roomState("r", 6, 0, 10, 100)),
                        badParameters),
                Arguments.of("big blind below twice the small blind",
                        lobbyImage(roomState("r", 6, 5, 9, 100)), badParameters),
                Arguments.of("buy-in below the big blind", lobbyImage(roomState("r", 6, 5, 10, 9)),
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

        Room minimal = mapper.restoreStateImage(lobbyImage(roomState("r", 2, 1, 2, 2))).room();
        Room maximal = mapper.restoreStateImage(lobbyImage(roomState("r", 10, 5, 10, 10))).room();

        assertEquals(2, minimal.getMaxPlayers());
        assertEquals(10, maximal.getMaxPlayers());
    }

    @Test
    @DisplayName("A deleted image restores to a deleted marker without room or game, even from a tombstone")
    void restoreStateImage_DeletedImage_ReturnsDeletedMarker() {
        AggregateSnapshotMapper mapper = newMapper();

        RecoveredAggregate fromImage = mapper.restoreStateImage(
                new AggregateStateImage(true, null, null));
        RecoveredAggregate fromTombstone = mapper.deserialize(mapper.serializeDeletion("gone-room"));

        for (RecoveredAggregate recovered : List.of(fromImage, fromTombstone)) {
            assertTrue(recovered.deleted());
            assertNull(recovered.room());
            assertNull(recovered.game());
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
                Arguments.of("the host is in the room", "Bob", joinTimes("Alice", "Bob"), "Bob"),
                Arguments.of("the host left, first member is used", "Zed", joinTimes("Carol", "Alice"), "Carol"),
                Arguments.of("no host, first member is used", null, joinTimes("Carol", "Dave"), "Carol"),
                Arguments.of("empty room keeps the stored host", "Yan", joinTimes(), "Yan"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("hostReconciliations")
    @DisplayName("restoreStateImage reconciles the host against actual room membership")
    void restoreStateImage_HostReconciliation_FallsBackThroughMembership(String name, String hostName, Map<String, LocalDateTime> members, String expectedHost) {
        AggregateStateImage.RoomState room = new AggregateStateImage.RoomState("r", "Room", hostName, 6, 5, 10,
                100, null, null, members, Map.of(), false, null);
        AggregateStateImage image = new AggregateStateImage(false, room, null);

        RecoveredAggregate recovered = newMapper().restoreStateImage(image);

        assertEquals(expectedHost, recovered.room().getHostName(), name);
        assertFalse(recovered.deleted());
        assertNull(recovered.game());
    }

    @Test
    @DisplayName("captureStateImage rejects a null room with a PersistenceException")
    void captureStateImage_NullRoom_IsRejected() {
        PersistenceException ex = assertThrows(PersistenceException.class,
                () -> newMapper().captureStateImage(null, null));

        assertEquals("Room cannot be null for aggregate state capture", ex.getMessage());
    }

    @Test
    @DisplayName("serialize rejects a null room and a null state image")
    void serialize_NullInputs_AreRejected() {
        AggregateSnapshotMapper mapper = newMapper();

        PersistenceException nullImage = assertThrows(PersistenceException.class,
                () -> mapper.serialize((AggregateStateImage) null));
        PersistenceException nullRoom = assertThrows(PersistenceException.class,
                () -> mapper.serialize(mapper.captureStateImage(null, null)));

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
    @DisplayName("restoreRoom returns null for null input, restores a state without join times, and capture keeps the host")
    void restoreRoom_NullAndMissingJoinTimes_AreHandled() {
        AggregateSnapshotMapper mapper = newMapper();

        assertNull(mapper.restoreRoom(null));

        Room restored = mapper.restoreRoom(new AggregateStateImage.RoomState("r", "Room", "Host", 6, 5, 10, 100,
                null, null, null, Map.of(), false, null));
        assertEquals("r", restored.getRoomId());
        assertTrue(restored.getPlayersWithJoinTimeSnapshot().isEmpty());

        Room live = new Room("live", "Live", "Host", 4, 5, 10, 100, null);
        live.addPlayer("Host");
        AggregateStateImage.RoomState captured = mapper.captureStateImage(live, null).room();
        assertEquals("live", captured.roomId());
        assertEquals(List.of("Host"), List.copyOf(captured.playersWithJoinTime().keySet()));
    }

    @Nested
    class SnapshotRecoveryScenarios {


        private static final String ROOM_ID = "empirical-room-m2";
        private AggregateSnapshotMapper snapshotMapper;

        @BeforeEach
        void setUp() {
            JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();
            snapshotMapper = new AggregateSnapshotMapper(jsonMapper);
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

            Game game = new Game(ROOM_ID, players, 6, 10, 20);
            assertFalse(game.resetForNewHand());
            game.dealHoleCards();
            game.postBlinds();

            assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
            assertEquals(30, game.getPot());
            assertEquals(20, game.getCurrentHighestBet());

            // Snapshot via mapper
            byte[] snapshotBytes = snapshotMapper.serialize(snapshotMapper.captureStateImage(room, game));

            // Store into real encrypted WAL
            RoomCoordinator coordinator = new RoomCoordinator();
            EncryptionKeyring keyring = new EncryptionKeyring("k1", Map.of("k1", new SecretKeySpec(new byte[32], "AES")));
            EncryptedWalStore walStore = new EncryptedWalStore(tempDir, keyring, coordinator);
            try (LockLease _ = coordinator.acquire(ROOM_ID)) {
                WalTransaction tx = walStore.prepare(ROOM_ID);
                walStore.commit(tx, snapshotBytes);
            }

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
            assertEquals(game.getDealerPlayerId(), restored.getDealerPlayerId());

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

            Game game = new Game(ROOM_ID, players, 6, 10, 20);
            assertFalse(game.resetForNewHand());
            game.dealHoleCards();
            game.postBlinds();
            game.dealFlop();

            assertEquals(GamePhase.FLOP, game.getCurrentPhase());
            assertEquals(3, game.getCommunityCards().size());

            byte[] snapshotBytes = snapshotMapper.serialize(snapshotMapper.captureStateImage(room, game));

            // WAL round-trip
            RoomCoordinator coordinator = new RoomCoordinator();
            EncryptionKeyring keyring = new EncryptionKeyring("k1", Map.of("k1", new SecretKeySpec(new byte[32], "AES")));
            EncryptedWalStore walStore = new EncryptedWalStore(tempDir, keyring, coordinator);
            try (LockLease _ = coordinator.acquire(ROOM_ID)) {
                WalTransaction tx = walStore.prepare(ROOM_ID);
                walStore.commit(tx, snapshotBytes);
            }

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

            Game game = new Game(ROOM_ID, players, 6, 10, 20);
            assertFalse(game.resetForNewHand());
            game.dealHoleCards();
            game.postBlinds();
            game.dealFlop();
            game.dealTurn();

            assertEquals(GamePhase.TURN, game.getCurrentPhase());
            assertEquals(4, game.getCommunityCards().size());

            byte[] snapshotBytes = snapshotMapper.serialize(snapshotMapper.captureStateImage(room, game));

            // WAL round-trip
            RoomCoordinator coordinator = new RoomCoordinator();
            EncryptionKeyring keyring = new EncryptionKeyring("k1", Map.of("k1", new SecretKeySpec(new byte[32], "AES")));
            EncryptedWalStore walStore = new EncryptedWalStore(tempDir, keyring, coordinator);
            try (LockLease _ = coordinator.acquire(ROOM_ID)) {
                WalTransaction tx = walStore.prepare(ROOM_ID);
                walStore.commit(tx, snapshotBytes);
            }

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

            Game game = new Game(ROOM_ID, players, 6, 10, 20);
            assertFalse(game.resetForNewHand());
            game.dealHoleCards();
            game.postBlinds();
            game.dealFlop();
            game.dealTurn();
            game.dealRiver();

            assertEquals(GamePhase.RIVER, game.getCurrentPhase());
            assertEquals(5, game.getCommunityCards().size());

            byte[] snapshotBytes = snapshotMapper.serialize(snapshotMapper.captureStateImage(room, game));

            // WAL round-trip
            RoomCoordinator coordinator = new RoomCoordinator();
            EncryptionKeyring keyring = new EncryptionKeyring("k1", Map.of("k1", new SecretKeySpec(new byte[32], "AES")));
            EncryptedWalStore walStore = new EncryptedWalStore(tempDir, keyring, coordinator);
            try (LockLease _ = coordinator.acquire(ROOM_ID)) {
                WalTransaction tx = walStore.prepare(ROOM_ID);
                walStore.commit(tx, snapshotBytes);
            }

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

            Game game = new Game(ROOM_ID, players, 6, 10, 20);
            assertFalse(game.resetForNewHand());
            game.dealHoleCards();
            game.postBlinds();
            game.dealFlop();
            game.dealTurn();
            game.dealRiver();
            game.conductShowdown();

            assertNotNull(game.getLastSettlementResult());
            HandSettlementResult originalSettlement = game.getLastSettlementResult();

            byte[] snapshotBytes = snapshotMapper.serialize(snapshotMapper.captureStateImage(room, game));

            // WAL round-trip
            RoomCoordinator coordinator = new RoomCoordinator();
            EncryptionKeyring keyring = new EncryptionKeyring("k1", Map.of("k1", new SecretKeySpec(new byte[32], "AES")));
            EncryptedWalStore walStore = new EncryptedWalStore(tempDir, keyring, coordinator);
            try (LockLease _ = coordinator.acquire(ROOM_ID)) {
                WalTransaction tx = walStore.prepare(ROOM_ID);
                walStore.commit(tx, snapshotBytes);
            }

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
                    .remainingDeck(List.of())
                    .smallBlind(10)
                    .bigBlind(20)
                    .currentPhase(GamePhase.PRE_FLOP)
                    .readyCountdownActive(true)
                    .readyCountdownDeadlineEpochMs(deadline)
                    .readyGateState(ReadyGateState.COUNTING_DOWN)
                    
                    .build();

            assertEquals(ReadyGateState.COUNTING_DOWN, game.getReadyGateState());
            assertTrue(game.isReadyCountdownActive());
            assertEquals(deadline, game.getReadyCountdownDeadlineEpochMs());

            byte[] bytes = snapshotMapper.serialize(snapshotMapper.captureStateImage(room, game));
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
                    .remainingDeck(List.of())
                    .smallBlind(10)
                    .bigBlind(20)
                    .currentPhase(GamePhase.PRE_FLOP)
                    .readyCountdownActive(false)
                    .readyCountdownDeadlineEpochMs(null)
                    .readyGateState(ReadyGateState.WAITING_FOR_PLAYERS)
                    
                    .build();

            assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, game.getReadyGateState());
            assertFalse(game.isReadyCountdownActive());
            assertNull(game.getReadyCountdownDeadlineEpochMs());

            byte[] bytes = snapshotMapper.serialize(snapshotMapper.captureStateImage(room, game));
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
                    .remainingDeck(List.of())
                    .smallBlind(10)
                    .bigBlind(20)
                    .currentPhase(GamePhase.PRE_FLOP)
                    .readyCountdownActive(false)
                    .readyCountdownDeadlineEpochMs(null)
                    .readyGateState(ReadyGateState.CLOSED)
                    
                    .build();

            assertEquals(ReadyGateState.CLOSED, game.getReadyGateState());
            assertFalse(game.isReadyCountdownActive());

            byte[] bytes = snapshotMapper.serialize(snapshotMapper.captureStateImage(room, game));
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
                    .remainingDeck(List.of())
                    .smallBlind(10)
                    .bigBlind(20)
                    .readyCountdownActive(true)
                    .readyGateState(null)
                    
                    .build();
            assertEquals(ReadyGateState.COUNTING_DOWN, activeCountdownGame.getReadyGateState());

            // Fallback with readyCountdownActive = false -> CLOSED
            Game inactiveCountdownGame = Game.restoreBuilder()
                    .gameId(ROOM_ID)
                    .players(players)
                    .activePlayerIds(List.of("p1", "p2"))
                    .remainingDeck(List.of())
                    .smallBlind(10)
                    .bigBlind(20)
                    .readyCountdownActive(false)
                    .readyGateState(null)
                    
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
                    .remainingDeck(List.of())
                    .smallBlind(10)
                    .bigBlind(20)
                    .currentPhase(GamePhase.PRE_FLOP)
                    .scheduledTaskDeadlines(deadlines)
                    .scheduledTaskGenerations(generations)
                    .stateRevision(42L)
                    .handNumber(5L)
                    
                    .build();

            assertEquals(42L, game.getStateRevision());
            assertEquals(5L, game.getHandNumber());
            assertEquals(deadlines, game.getScheduledTaskDeadlinesSnapshot());
            assertEquals(generations, game.getScheduledTaskGenerationsSnapshot());

            byte[] bytes = snapshotMapper.serialize(snapshotMapper.captureStateImage(room, game));
            Game restored = snapshotMapper.deserialize(bytes).game();

            assertEquals(42L, restored.getStateRevision());
            assertEquals(5L, restored.getHandNumber());
            assertEquals(deadlines, restored.getScheduledTaskDeadlinesSnapshot());
            assertEquals(generations, restored.getScheduledTaskGenerationsSnapshot());
        }
    }

    @Test
    void queuedSnapshotSeesCompleteMutationAndPriorSnapshotRemainsUnchanged() throws Exception {
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build());
        Player alice = new Player("Alice", "p1", 1000);
        Player bob = new Player("Bob", "p2", 1000);
        Player departing = new Player("Charlie", "p3", 1000);
        Game game = new Game("snapshot-room", List.of(alice, bob, departing), 6, 10, 20);
        AggregateStateImage.GameState before = mapper.toState(game);
        RoomCoordinator coordinator = new RoomCoordinator();

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<AggregateStateImage.GameState> capture;
            try (LockLease _ = coordinator.acquire("snapshot-room")) {
                alice.setDisconnected(true);
                capture = executor.submit(() -> {
                    try (LockLease _ = coordinator.acquire("snapshot-room")) {
                        return mapper.toState(game);
                    }
                });
                Awaitility.await().until(() -> coordinator.lockReservationCount("snapshot-room") >= 2);

                bob.setReadyForNextHand(true);
                game.removePlayer(departing);
                game.incrementStateRevision();
            }

            AggregateStateImage.GameState after = capture.get(5, TimeUnit.SECONDS);
            assertEquals(before.stateRevision() + 1, after.stateRevision());
            assertEquals(List.of("p1", "p2"), after.activePlayerIds());
            assertEquals(after.activePlayerIds(),
                    after.players().stream().map(AggregateStateImage.PlayerState::playerId).toList());
            assertTrue(after.players().get(0).disconnected());
            assertTrue(after.players().get(1).readyForNextHand());

            assertEquals(List.of("p1", "p2", "p3"), before.activePlayerIds());
            assertEquals(before.activePlayerIds(),
                    before.players().stream().map(AggregateStateImage.PlayerState::playerId).toList());
            assertFalse(before.players().get(0).disconnected());
            assertFalse(before.players().get(1).readyForNextHand());
            assertThrows(UnsupportedOperationException.class, () -> after.players().clear());
        }
    }

    @Test
    void repeatedSeatingGetterCallsDoNotMutateSnapshotStateImage() {
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build());
        Room room = new Room("room-pure-getters", "Pure Getters", "Alice", 6, 5, 10, 1000, null);
        room.addPlayer("Alice");
        room.addPlayer("Bob");
        room.addPlayer("Charlie");
        room.setGameStarted(true);

        List<Player> players = List.of(
                new Player("Alice", "p1", 1000),
                new Player("Bob", "p2", 1000),
                new Player("Charlie", "p3", 1000));
        Game game = Game.restoreBuilder()
                .gameId("room-pure-getters")
                .players(players)
                .activePlayerIds(List.of("p1", "p2", "p3"))
                .remainingDeck(List.of())
                .smallBlind(5)
                .bigBlind(10)
                .handSeatOrder(List.of("p1", "p2", "p3"))
                .dealerAnchorPlayerId("p1")
                .smallBlindPlayerId(null)
                .bigBlindPlayerId(null)
                .currentPlayerId("p2")
                .currentPhase(GamePhase.PRE_FLOP)
                
                .build();

        AggregateStateImage beforeImage = mapper.captureStateImage(room, game);
        byte[] beforeBytes = mapper.serialize(mapper.captureStateImage(room, game));

        for (int i = 0; i < 3; i++) {
            assertEquals("p2", game.getCurrentPlayer().getPlayerId());
            assertEquals("p2", game.getSmallBlindPlayerId());
            assertEquals("p3", game.getBigBlindPlayerId());
        }

        AggregateStateImage afterImage = mapper.captureStateImage(room, game);
        byte[] afterBytes = mapper.serialize(mapper.captureStateImage(room, game));

        assertEquals(beforeImage, afterImage);
        assertArrayEquals(beforeBytes, afterBytes);
    }
}
