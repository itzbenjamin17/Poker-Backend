package com.pokergame.persistence.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.HandRank;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.Rank;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.enums.Suit;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Card;
import com.pokergame.model.Game;
import com.pokergame.model.HandPayout;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.PersistenceException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Unit tests for {@link RoomSnapshotMapper} snapshot capture, JSON serialization, and restoration. */
@Tag("unit")
class RoomSnapshotMapperTest {

    private static final PasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder(4);
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final String FINISHED_ROOM_ID = "room-finished";
    private static final String FINISHED_FIXTURE = "/persistence/finished-game-snapshot.json";
    private static final long FIXED_EPOCH_MS = 1_700_000_000_000L;

    private static final List<Card> DETERMINISTIC_DECK = List.of(
            new Card(Rank.TWO, Suit.CLUBS),
            new Card(Rank.THREE, Suit.DIAMONDS),
            new Card(Rank.FOUR, Suit.HEARTS),
            new Card(Rank.FIVE, Suit.SPADES),
            new Card(Rank.SIX, Suit.CLUBS));

    private static RoomSnapshotMapper newMapper() {
        return new RoomSnapshotMapper(JSON);
    }

    @Test
    void activeGameRoundTripPreservesExactNextCardsAndGameState() {
        Room room = new Room("room-1", "Night Game", "Alice", 6, 5, 10, 1000,
                PASSWORD_ENCODER.encode("secret"));
        room.addPlayer("Alice");
        room.addPlayer("Bob");
        room.setGameStarted(true);

        Player alice = Player.restoreBuilder()
                .name("Alice").playerId("alice-id").chips(995).currentBet(5)
                .holeCards(List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES)))
                .bestHand(List.of()).handRank(HandRank.NO_HAND)
                .hasFolded(false).allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
        Player bob = Player.restoreBuilder()
                .name("Bob").playerId("bob-id").chips(990).currentBet(10)
                .holeCards(List.of(new Card(Rank.QUEEN, Suit.HEARTS), new Card(Rank.JACK, Suit.HEARTS)))
                .bestHand(List.of()).handRank(HandRank.NO_HAND)
                .hasFolded(false).allIn(false).out(false).disconnected(false).readyForNextHand(false).build();

        Game game = Game.restoreBuilder()
                .gameId("room-1")
                .maxPlayers(6)
                .players(List.of(alice, bob))
                .activePlayerIds(List.of("alice-id", "bob-id"))
                .remainingDeck(DETERMINISTIC_DECK)
                .communityCards(List.of())
                .pot(15)
                .currentPlayerId("alice-id")
                .currentHighestBet(10)
                .currentPhase(GamePhase.PRE_FLOP)
                .gameOver(false)
                .smallBlind(5)
                .bigBlind(10)
                .handContributions(Map.of("alice-id", 5, "bob-id", 10))
                .handSeatOrder(List.of("alice-id", "bob-id"))
                .dealerPlayerId("alice-id")
                .smallBlindPlayerId("alice-id")
                .bigBlindPlayerId("bob-id")
                .build();

        RoomSnapshotMapper mapper = newMapper();
        RecoveredRoom recovered = mapper.deserialize(mapper.serialize(mapper.captureSnapshot(room, game)));

        assertEquals(room.getRoomName(), recovered.room().getRoomName());
        assertEquals(room.getHostName(), recovered.room().getHostName());
        assertTrue(recovered.room().checkPassword("secret", PASSWORD_ENCODER));
        assertEquals(game.getPot(), recovered.game().getPot());
        assertEquals(game.getCurrentPlayer().getPlayerId(), recovered.game().getCurrentPlayer().getPlayerId());
        assertEquals(game.getPlayers().getFirst().getHoleCards(),
                recovered.game().getPlayers().getFirst().getHoleCards());

        game.dealFlop();
        recovered.game().dealFlop();
        assertEquals(game.getCommunityCards(), recovered.game().getCommunityCards());
        assertEquals(game.toSnapshot().carryOverPot(), recovered.game().toSnapshot().carryOverPot());
        assertEquals(game.toSnapshot().handSeatOrder(), recovered.game().toSnapshot().handSeatOrder());
    }

    @ParameterizedTest
    @MethodSource("inactivityDeadlines")
    void restoreSnapshotPreservesTheAcceptedInactivityDeadline(Long deadline) {
        Room room = new Room("room-1", "Night Game", "Alice", 6, 5, 10, 1000, null);
        room.addPlayer("Alice");
        room.addPlayer("Bob");
        room.setGameStarted(true);
        room.setInactivityDeadlineEpochMs(deadline);
        Game game = new Game("room-1", List.of(
                new Player("Alice", "alice-id", 1000),
                new Player("Bob", "bob-id", 1000)), 6, 5, 10);
        RoomSnapshotMapper mapper = newMapper();

        RecoveredRoom restored = mapper.restoreSnapshot(mapper.captureSnapshot(room, game));

        assertEquals(room.getRoomId(), restored.room().getRoomId());
        assertEquals(deadline, restored.room().getInactivityDeadlineEpochMs());
    }

    private static Stream<Long> inactivityDeadlines() {
        return Stream.of(null, 1L, 4_102_444_800_000L);
    }

    @Test
    void settledGameEndRoundTripPreservesFinalPotSettlementAndRevision() {
        Room room = new Room("room-2", "Recovery", "Alice", 4, 5, 10, 1000,
                PASSWORD_ENCODER.encode("secret"));
        room.addPlayer("Alice");
        room.addPlayer("Bob");
        room.setGameStarted(true);
        List<Player> players = List.of(new Player("Alice", "a", 1030), new Player("Bob", "b", 970));
        Game game = Game.restoreBuilder().gameId("room-2").maxPlayers(4).players(players)
                .activePlayerIds(List.of("a", "b"))
                .remainingDeck(List.of()).communityCards(List.of()).pot(3).currentPlayerId("a").currentHighestBet(0)
                .currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false).smallBlind(5).bigBlind(10).handContributions(Map.of()).carryOverPot(3)
                .lastFullRaise(10).previousBets(Map.of("a", 20))
                .handSeatOrder(List.of("a", "b")).dealerPlayerId("a").smallBlindPlayerId("a")
                .bigBlindPlayerId("b").lastHandPayout(new HandPayout(Map.of("a", 50), Map.of("b", 2),
                        Set.of("a"), 3, true))
                .allPlayersHadInitialTurn(true).actedPlayersInRound(Set.of())
                .scheduledTimes(Map.of(ScheduledGameTask.CLEANUP, 123L))
                .timerVersions(Map.of(ScheduledGameTask.CLEANUP, 9L))
                .handNumber(7).readyGateState(ReadyGateState.WAITING_FOR_PLAYERS)
                .pausedForDisconnectedSurvivor(true).stateRevision(19)
                .build();
        game.markGameOver();
        game.settleGameEnd(players.getFirst(), Set.of());
        assertEquals(1033, players.getFirst().getChips());
        assertEquals(0, game.getPot());
        assertTrue(game.isFinalPotSettled());
        RoomSnapshotMapper mapper = newMapper();

        byte[] snapshot = mapper.serialize(mapper.captureSnapshot(room, game));
        Game restored = mapper.deserialize(snapshot).game();
        assertEquals(3, restored.toSnapshot().carryOverPot());
        assertEquals(game.getLastHandPayout(), restored.getLastHandPayout());
        assertEquals(10, restored.toSnapshot().lastFullRaise());
        assertEquals(List.of("a", "b"), restored.toSnapshot().handSeatOrder());
        assertTrue(restored.isFinalPotSettled());
        assertEquals(7, restored.getHandNumber());
        assertEquals(ReadyGateState.CLOSED, restored.getReadyGateState());
        assertEquals(9L, restored.getTimerVersion(ScheduledGameTask.CLEANUP));
        assertTrue(restored.isPausedForDisconnectedSurvivor());
        assertEquals(19, restored.getStateRevision(), "Persisted same-hand public revision must survive restart");
        String json = new String(snapshot, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"stateRevision\":19"));
        assertFalse(json.contains("schemaVersion"));
    }

    @Test
    void restoredSettledGameRemainsIdempotentAcrossSettleGameEndAndConductShowdown() {
        Room room = new Room("room-2", "Recovery", "Alice", 4, 5, 10, 1000, null);
        room.addPlayer("Alice");
        room.addPlayer("Bob");
        room.setGameStarted(true);
        List<Player> players = List.of(new Player("Alice", "a", 1030), new Player("Bob", "b", 970));
        Game game = Game.restoreBuilder().gameId("room-2").maxPlayers(4).players(players)
                .activePlayerIds(List.of("a", "b"))
                .remainingDeck(List.of()).communityCards(List.of()).pot(3).currentPlayerId("a").currentHighestBet(0)
                .currentPhase(GamePhase.SHOWDOWN)
                .gameOver(false).smallBlind(5).bigBlind(10).handContributions(Map.of()).carryOverPot(3)
                .lastHandPayout(new HandPayout(Map.of("a", 50), Map.of("b", 2), Set.of("a"), 3, true))
                .build();
        game.settleGameEnd(players.getFirst(), Set.of());

        RoomSnapshotMapper mapper = newMapper();
        Game restored = mapper.deserialize(mapper.serialize(mapper.captureSnapshot(room, game))).game();

        restored.settleGameEnd(restored.getPlayers().getFirst(), Set.of());
        assertEquals(1033, restored.getPlayers().getFirst().getChips(),
                "A recovered settled marker prevents a second carry-over payout");
        assertEquals(0, restored.getPot());

        int aliceBeforeRetry = restored.getPlayers().getFirst().getChips();
        int bobBeforeRetry = restored.getPlayers().get(1).getChips();
        restored.conductShowdown();
        assertEquals(Set.of("a"), restored.getLastHandPayout().winnerIds());
        assertEquals(aliceBeforeRetry, restored.getPlayers().getFirst().getChips());
        assertEquals(bobBeforeRetry, restored.getPlayers().get(1).getChips());
    }

    @Test
    void handPayoutDefensivelyCopiesAwardMaps() {
        Map<String, Integer> mutableAwards = new HashMap<>(Map.of("a", 1));
        HandPayout immutable = new HandPayout(mutableAwards, Map.of(), Set.of("a"), 0, true);
        mutableAwards.put("b", 2);

        assertEquals(Map.of("a", 1), immutable.potWinnings());
        assertThrows(UnsupportedOperationException.class, () -> immutable.potWinnings().put("b", 2));
    }

    @Test
    void pausedForDisconnectedSurvivorRoundTripPreservesOutDepartedPlayerAndRoomMembership() {
        Room room = new Room("room-paused-survivor", "Paused Room", "Survivor", 6, 5, 10, 1000, null);
        room.addPlayer("Survivor");
        room.setGameStarted(true);

        Player departing = Player.restoreBuilder()
                .name("Departing").playerId("d").chips(950).currentBet(0)
                .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND)
                .hasFolded(false).allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
        Player survivor = Player.restoreBuilder()
                .name("Survivor").playerId("s").chips(1050).currentBet(0)
                .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND)
                .hasFolded(false).allIn(false).out(false).disconnected(true)
                .disconnectDeadlineEpochMs(FIXED_EPOCH_MS + 30_000L).readyForNextHand(false).build();

        Game game = Game.restoreBuilder()
                .gameId("room-paused-survivor")
                .maxPlayers(6)
                .players(List.of(departing, survivor))
                .activePlayerIds(List.of("d", "s"))
                .remainingDeck(List.of())
                .currentPhase(GamePhase.SHOWDOWN)
                .smallBlind(5)
                .bigBlind(10)
                .build();
        game.pauseForDisconnectedSurvivor(List.of(departing));

        RoomSnapshotMapper mapper = newMapper();
        RecoveredRoom recovered = mapper.deserialize(mapper.serialize(mapper.captureSnapshot(room, game)));

        assertTrue(recovered.game().isPausedForDisconnectedSurvivor());
        assertTrue(recovered.game().getPlayers().stream()
                .anyMatch(player -> "Departing".equals(player.getName()) && player.isOut()));
        assertFalse(recovered.room().hasPlayer("Departing"));
        assertTrue(recovered.room().hasPlayer("Survivor"));
    }

    @Test
    void retainedFinishedGameResultRoundTripPreservesFullResponse() {
        Room room = buildFinishedFixtureRoom();
        PublicGameStateResponse finalResult = buildFinishedFixtureResponse();
        Game game = buildFinishedFixtureGame(finalResult);
        RoomSnapshotMapper mapper = newMapper();

        byte[] snapshot = mapper.serialize(mapper.captureSnapshot(room, game));
        String json = new String(snapshot, StandardCharsets.UTF_8);
        assertFalse(json.contains("schemaVersion"));
        assertTrue(json.contains("\"gameEndResult\""));
        assertTrue(json.contains("\"winner\":\"Alice\""));
        assertTrue(json.contains("\"winnerChips\":2000"));
        assertTrue(json.contains("\"inactivityDeadlineEpochMs\":null"));
        assertTrue(json.contains("\"abandoned\":null"));
        assertTrue(json.contains("\"reason\":null"));

        Game restored = mapper.deserialize(snapshot).game();
        assertNotNull(restored.getGameEndResult());
        PublicGameStateResponse restoredResult = restored.getGameEndResult();
        assertThat(restoredResult).isEqualTo(finalResult);
        assertThat(restored.getLastHandPayout()).isEqualTo(game.getLastHandPayout());
        assertThat(restored.getPlayers().getFirst().getDisconnectDeadlineEpochMs())
                .isEqualTo(1_700_000_015_000L);
    }

    @Test
    void midStreetRoundTripPreservesRaiseMinimumAndReopeningLegality() {
        Room room = new Room("room-3", "Betting", "A", 6, 5, 10, 1000, null);
        room.addPlayer("A");
        room.addPlayer("B");
        room.addPlayer("Short1");
        room.addPlayer("Short2");
        room.setGameStarted(true);
        List<Player> players = List.of(bettingPlayer("A", "a", 900, 100, false),
                bettingPlayer("B", "b", 900, 100, false), bettingPlayer("Short1", "s1", 0, 150, true),
                bettingPlayer("Short2", "s2", 50, 150, false));
        Game game = Game.restoreBuilder().gameId("room-3").players(players)
                .activePlayerIds(players.stream().map(Player::getPlayerId).toList()).remainingDeck(List.of())
                .communityCards(List.of()).pot(500).currentPlayerId("a")
                .currentHighestBet(150).currentPhase(GamePhase.PRE_FLOP).gameOver(false)
                .smallBlind(5).bigBlind(10).handContributions(Map.of("a", 100, "b", 100, "s1", 150, "s2", 150))
                .carryOverPot(0).lastFullRaise(100)
                .previousBets(Map.of("a", 100)).handSeatOrder(List.of("a", "b", "s1", "s2"))
                .dealerPlayerId("a").smallBlindPlayerId("a").bigBlindPlayerId("b")
                .allPlayersHadInitialTurn(true).actedPlayersInRound(Set.of("a"))
                .scheduledTimes(Map.of()).build();
        RoomSnapshotMapper mapper = newMapper();

        Game restored = mapper.deserialize(mapper.serialize(mapper.captureSnapshot(room, game))).game();
        Player initialA = restored.getPlayers().getFirst();
        assertEquals(150, restored.getMinimumRaiseAmount(initialA));
        assertFalse(restored.getLegalActionsFor(initialA).contains(PlayerAction.RAISE));
        assertThrows(UnauthorisedActionException.class,
                () -> restored.processPlayerDecision(initialA, PlayerAction.RAISE, 150));
        Player restoredShort = restored.getPlayers().get(3);
        restored.processPlayerDecision(restoredShort, PlayerAction.ALL_IN, 0);
        restored.processPlayerDecision(restored.getPlayers().get(1), PlayerAction.CALL, 0);
        Game reopened = mapper.deserialize(mapper.serialize(mapper.captureSnapshot(room, restored))).game();
        Player reopenedA = reopened.getPlayers().getFirst();
        assertEquals(200, reopened.getMinimumRaiseAmount(reopenedA));
        assertTrue(reopened.getLegalActionsFor(reopenedA).contains(PlayerAction.RAISE));
        reopened.processPlayerDecision(reopenedA, PlayerAction.RAISE, 200);
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

        RoomSnapshotMapper mapper = newMapper();
        byte[] snapshot = mapper.serialize(mapper.captureSnapshot(room, game));

        RecoveredRoom recovered = mapper.deserialize(snapshot);
        assertNotNull(recovered.room());
        assertNotNull(recovered.game());
        assertTrue(recovered.room().isGameStarted());
        assertTrue(recovered.room().hasPlayer("Alice"), "Alice from game must be in room");
        assertFalse(recovered.room().hasPlayer("Bob"), "A retained OUT roster row must not recreate Room membership");
        assertFalse(recovered.room().hasPlayer("DepartedHost"), "A departed original host must not recreate Room membership");
        assertTrue(recovered.room().hasPlayer("BustedSpectator"), "Busted spectators who stayed remain Room members");
        assertEquals("Alice", recovered.room().getHostName(), "Current host remains an actual Room member");
    }

    @Test
    void deserializeRejectsUnknownProperty() {
        RoomSnapshotMapper mapper = newMapper();
        ObjectNode snapshot = validFinishedSnapshotTree();
        snapshot.put("unexpectedField", "stale");

        PersistenceException ex = assertThrows(
                PersistenceException.class,
                () -> mapper.deserialize(JSON.writeValueAsBytes(snapshot)));
        assertEquals("Cannot decode aggregate snapshot", ex.getMessage());
    }

    @Test
    void deserializeRejectsMissingCreatorProperty() {
        RoomSnapshotMapper mapper = newMapper();
        ObjectNode snapshot = validFinishedSnapshotTree();
        ((ObjectNode) snapshot.get("room")).remove("inactivityDeadlineEpochMs");

        PersistenceException ex = assertThrows(
                PersistenceException.class,
                () -> mapper.deserialize(JSON.writeValueAsBytes(snapshot)));
        assertEquals("Cannot decode aggregate snapshot", ex.getMessage());
    }

    @Test
    void deserializeRejectsNullRoomPlayerElement() {
        RoomSnapshotMapper mapper = newMapper();
        ObjectNode snapshot = validFinishedSnapshotTree();
        ((ObjectNode) snapshot.get("room")).putArray("players").add("Alice").addNull();

        PersistenceException ex = assertThrows(
                PersistenceException.class,
                () -> mapper.deserialize(JSON.writeValueAsBytes(snapshot)));
        assertEquals("Cannot decode aggregate snapshot", ex.getMessage());
    }

    @Test
    void lobbyRoundTripAcceptsNullInactivityDeadline() {
        Room room = new Room("room-lobby-null", "Lobby", "Alice", 6, 5, 10, 1000, null);
        room.addPlayer("Alice");
        RoomSnapshotMapper mapper = newMapper();

        byte[] snapshot = mapper.serialize(mapper.captureSnapshot(room, null));
        String json = new String(snapshot, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"inactivityDeadlineEpochMs\":null"));
        assertFalse(json.contains("schemaVersion"));

        RecoveredRoom recovered = mapper.deserialize(snapshot);
        assertFalse(recovered.deleted());
        assertNull(recovered.game());
        assertNull(recovered.room().getInactivityDeadlineEpochMs());
        assertEquals("Alice", recovered.room().getHostName());
    }

    private static Player bettingPlayer(String name, String id, int chips, int currentBet, boolean allIn) {
        return Player.restoreBuilder().name(name).playerId(id).chips(chips).currentBet(currentBet)
                .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND)
                .hasFolded(false).allIn(allIn).out(false).disconnected(false).readyForNextHand(false).build();
    }

    @Test
    @DisplayName("captureSnapshot copies state into an immutable snapshot detached from live mutations")
    void captureSnapshot_DetachesSnapshotFromLiveMutation() {
        RoomSnapshotMapper mapper = newMapper();

        Room room = new Room("room-order", "Order Room", "Alice", 6, 10, 20, 1000, null);
        room.addPlayer("Alice");
        room.addPlayer("Bob");

        Player p1 = new Player("Alice", "p1", 1000);
        Player p2 = new Player("Bob", "p2", 1000);
        Game game = new Game("room-order", List.of(p1, p2), 6, 10, 20);

        RoomSnapshot capturedSnapshot = mapper.captureSnapshot(room, game);
        assertNotNull(capturedSnapshot);
        assertNotNull(capturedSnapshot.room());
        assertNotNull(capturedSnapshot.game());

        byte[] jsonBytes = mapper.serialize(capturedSnapshot);
        assertNotNull(jsonBytes);

        p1.doAction(PlayerAction.BET, 500);
        RecoveredRoom recovered = mapper.deserialize(jsonBytes);
        assertEquals(1000, recovered.game().getPlayers().get(0).getChips(),
                "Captured snapshot must be independent from post-capture live game mutations");
    }

    @Test
    @DisplayName("toGameState returns null for a lobby without a game, and toPlayerState captures a fresh player")
    void toGameState_NullGameAndFreshPlayer_AreHandled() {
        RoomSnapshotMapper mapper = newMapper();

        assertNull(mapper.toGameState(null));

        Player normalPlayer = new Player("PlayerOne", "p-one", 100);
        RoomSnapshot.PlayerState playerState = mapper.toPlayerState(normalPlayer);
        assertNotNull(playerState);
        assertEquals("PlayerOne", playerState.name());
        assertEquals("p-one", playerState.playerId());
        assertEquals(List.of(), playerState.holeCards());
        assertEquals(List.of(), playerState.bestHand());
    }

    @Test
    @DisplayName("Snapshot serializes passwordHash and never cleartext, and deserialization restores passwordHash")
    void passwordSecurity_SnapshotSerializesPasswordHashAndNeverPlaintext() {
        RoomSnapshotMapper mapper = newMapper();

        String rawSecret = "VerySecretPassphrase123!";
        Room room = new Room("pw-room", "Protected Room", "Host", 4, 10, 20, 500,
                PASSWORD_ENCODER.encode(rawSecret));
        room.addPlayer("Host");

        byte[] snapshot = mapper.serialize(mapper.captureSnapshot(room, null));
        String json = new String(snapshot, StandardCharsets.UTF_8);

        assertFalse(json.contains(rawSecret), "Cleartext password must NEVER appear in serialized snapshot JSON");
        assertNotNull(room.getPasswordHash());
        assertTrue(json.contains(room.getPasswordHash()), "Snapshot must serialize the BCrypt passwordHash");

        RecoveredRoom recovered = mapper.deserialize(snapshot);
        assertNotNull(recovered.room());
        Room restoredRoom = recovered.room();

        assertTrue(restoredRoom.hasPassword());
        assertEquals(room.getPasswordHash(), restoredRoom.getPasswordHash());
        assertTrue(restoredRoom.checkPassword(rawSecret, PASSWORD_ENCODER));
        assertFalse(restoredRoom.checkPassword("WrongPass", PASSWORD_ENCODER));
    }

    @Test
    @DisplayName("Public room snapshot serializes null password and deserialization restores room with no password")
    void passwordSecurity_PublicRoomSnapshotSerializesNullPassword() {
        RoomSnapshotMapper mapper = newMapper();

        Room publicRoom = new Room("pub-room", "Public Room", "Host", 4, 10, 20, 500, null);
        publicRoom.addPlayer("Host");

        byte[] snapshot = mapper.serialize(mapper.captureSnapshot(publicRoom, null));
        RecoveredRoom recovered = mapper.deserialize(snapshot);

        assertNotNull(recovered.room());
        Room restored = recovered.room();
        assertFalse(restored.hasPassword());
        assertNull(restored.getPasswordHash());
        assertTrue(restored.checkPassword("any", PASSWORD_ENCODER));
        assertTrue(restored.checkPassword(null, PASSWORD_ENCODER));
    }

    @Test
    @DisplayName("Snapshot and restoreRoom preserve playerMembershipIds so original JWT membership remains valid")
    void snapshotAndRestoreRoom_PreservesPlayerMembershipIds() {
        RoomSnapshotMapper mapper = newMapper();

        Room room = new Room("mem-room", "Membership Room", "Alice", 4, 10, 20, 500, null);
        String aliceMemId = room.addPlayer("Alice");
        String bobMemId = room.addPlayer("Bob");

        byte[] snapshot = mapper.serialize(mapper.captureSnapshot(room, null));
        RecoveredRoom recovered = mapper.deserialize(snapshot);

        assertNotNull(recovered.room());
        assertEquals(aliceMemId, recovered.room().getMembershipId("Alice"),
                "Alice membershipId must survive WAL snapshot round-trip");
        assertEquals(bobMemId, recovered.room().getMembershipId("Bob"),
                "Bob membershipId must survive WAL snapshot round-trip");
    }

    private static ObjectNode validFinishedSnapshotTree() {
        try (var in = RoomSnapshotMapperTest.class.getResourceAsStream(FINISHED_FIXTURE)) {
            assertNotNull(in, "Missing finished-game snapshot fixture on the classpath: " + FINISHED_FIXTURE);
            return (ObjectNode) JSON.readTree(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Room buildFinishedFixtureRoom() {
        Room room = new Room(FINISHED_ROOM_ID, "Final Table", "Alice", 6, 5, 10, 1000, null);
        room.addPlayer("Alice");
        room.addPlayer("Bob");
        room.setGameStarted(true);
        room.setFinished(true);
        return room;
    }

    private static PublicGameStateResponse buildFinishedFixtureResponse() {
        return new PublicGameStateResponse(
                6, 0, List.of(2000), 0, GamePhase.SHOWDOWN, 0,
                List.of(new Card(Rank.TEN, Suit.CLUBS)),
                List.of(
                        new PublicGameStateResponse.PlayerState(
                                "a", "Alice", 2000, 0, "ACTIVE", false, false, false, true, false,
                                HandRank.HIGH_CARD, List.of(new Card(Rank.ACE, Suit.SPADES)),
                                true, 2000,
                                List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES)),
                                1_700_000_015_000L, false),
                        new PublicGameStateResponse.PlayerState(
                                "b", "Bob", 0, 0, "OUT", false, false, false, false, true,
                                null, List.of(), false, 0, null, null, true)),
                null, null, List.of("Alice"), 2000, false, null, false, null, false, null,
                true, null, List.of(), null, null, 1, ReadyGateState.CLOSED, 123456789L, 42,
                null, null, PublicGameStateResponse.GameEnd.won(
                        "Alice", 2000, false, "Alice wins the game with 2000 chips!"));
    }

    private static Game buildFinishedFixtureGame(PublicGameStateResponse finalResult) {
        List<Player> players = List.of(
                Player.restoreBuilder().name("Alice").playerId("a").chips(2000).currentBet(0)
                        .holeCards(List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES)))
                        .bestHand(List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES)))
                        .handRank(HandRank.HIGH_CARD)
                        .hasFolded(false).allIn(false).out(false).disconnected(false)
                        .disconnectDeadlineEpochMs(1_700_000_015_000L).readyForNextHand(false).build(),
                Player.restoreBuilder().name("Bob").playerId("b").chips(0).currentBet(0)
                        .holeCards(List.of(new Card(Rank.TWO, Suit.HEARTS), new Card(Rank.THREE, Suit.DIAMONDS)))
                        .bestHand(List.of())
                        .handRank(HandRank.NO_HAND)
                        .hasFolded(false).allIn(false).out(true).disconnected(false).readyForNextHand(false).build());

        return Game.restoreBuilder().gameId(FINISHED_ROOM_ID).maxPlayers(6).players(players)
                .activePlayerIds(List.of("a", "b"))
                .remainingDeck(List.of()).communityCards(List.of(new Card(Rank.TEN, Suit.CLUBS))).pot(0)
                .currentPlayerId("a")
                .currentHighestBet(0).currentPhase(GamePhase.SHOWDOWN).gameOver(true).smallBlind(5).bigBlind(10)
                .handContributions(Map.of()).carryOverPot(0).lastFullRaise(0)
                .previousBets(Map.of()).handSeatOrder(List.of("a", "b")).dealerPlayerId("a")
                .smallBlindPlayerId("a").bigBlindPlayerId("b")
                .lastHandPayout(new HandPayout(Map.of("a", 2000), Map.of("a", 10), Set.of("a"), 0, true))
                .allPlayersHadInitialTurn(true).actedPlayersInRound(Set.of())
                .scheduledTimes(Map.of(ScheduledGameTask.CLEANUP, 999999L))
                .timerVersions(Map.of(ScheduledGameTask.CLEANUP, 1L))
                .handNumber(1).readyGateState(ReadyGateState.CLOSED).pausedForDisconnectedSurvivor(false)
                .stateRevision(42)
                .gameEndResult(finalResult).build();
    }

    @Test
    @DisplayName("The mapper's serialized output carries the same field names as the independent fixture")
    void captureSnapshot_FieldNames_MatchIndependentFixture() {
        Room room = buildFinishedFixtureRoom();
        PublicGameStateResponse finalResult = buildFinishedFixtureResponse();
        Game game = buildFinishedFixtureGame(finalResult);

        ObjectNode serialized = JSON.valueToTree(newMapper().captureSnapshot(room, game));

        assertEquals(fieldNameShape(validFinishedSnapshotTree()), fieldNameShape(serialized),
                "Mapper writer field names drifted from the independent finished-game snapshot fixture");
    }

    private static Set<String> fieldNameShape(JsonNode node) {
        Set<String> paths = new TreeSet<>();
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

    private static ObjectNode playerOf(ObjectNode gameEndNode, int index) {
        return (ObjectNode) ((ArrayNode) gameEndNode.get("players")).get(index);
    }

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
    @DisplayName("The unmodified finished-game result fixture restores, and empty hole cards are accepted")
    void deserialize_ValidRetainedFinishedResult_Restores() {
        ObjectNode snapshot = validFinishedSnapshotTree();
        RecoveredRoom restoredFromFixture = newMapper().deserialize(JSON.writeValueAsBytes(snapshot));
        assertThat(restoredFromFixture.game().getGameEndResult()).isEqualTo(buildFinishedFixtureResponse());

        playerOf((ObjectNode) snapshot.at("/game/gameEndResult"), 1).set("holeCards", cardArray());
        RecoveredRoom recovered = newMapper().deserialize(JSON.writeValueAsBytes(snapshot));
        assertEquals(List.of(), recovered.game().getGameEndResult().players().get(1).holeCards());
    }

    @Test
    @DisplayName("deserialize rejects a snapshot with a null card element")
    void deserialize_NullCardElement_IsRejected() {
        ObjectNode snapshot = validFinishedSnapshotTree();
        ((ObjectNode) snapshot.get("game")).set("communityCards", cardArray(new ObjectNode[]{null}));

        PersistenceException ex = assertThrows(PersistenceException.class,
                () -> newMapper().deserialize(JSON.writeValueAsBytes(snapshot)));

        assertEquals("Null card in aggregate snapshot", ex.getMessage());
    }

    @Test
    void abandonedGameRoundTripPreservesOutcomeWithoutWinner() {
        Room room = new Room("room-abandoned", "Night Game", "Alice", 6, 5, 10, 1000, null);
        room.addPlayer("Alice");
        room.addPlayer("Bob");
        room.setGameStarted(true);

        Player alice = Player.restoreBuilder().name("Alice").playerId("alice-id").chips(995).currentBet(5)
                .holeCards(List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES)))
                .bestHand(List.of()).handRank(HandRank.NO_HAND)
                .hasFolded(false).allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
        Player bob = Player.restoreBuilder().name("Bob").playerId("bob-id").chips(990).currentBet(10)
                .holeCards(List.of(new Card(Rank.QUEEN, Suit.HEARTS), new Card(Rank.JACK, Suit.HEARTS)))
                .bestHand(List.of()).handRank(HandRank.NO_HAND)
                .hasFolded(false).allIn(false).out(false).disconnected(false).readyForNextHand(false).build();

        PublicGameStateResponse abandonedResult = new PublicGameStateResponse(
                6, 15, List.of(15), 0, GamePhase.PRE_FLOP, 10, List.of(),
                List.of(
                        new PublicGameStateResponse.PlayerState(
                                "alice-id", "Alice", 995, 5, "ACTIVE", false, true, false, true, false,
                                null, List.of(), false, null, null, null, false),
                        new PublicGameStateResponse.PlayerState(
                                "bob-id", "Bob", 990, 10, "ACTIVE", false, false, false, false, true,
                                null, List.of(), false, null, null, null, false)),
                null, null, List.of(), null, false, null, false, null, false, null, false, null,
                List.of(), null, null, 1L, ReadyGateState.CLOSED, FIXED_EPOCH_MS, 3L, null, null,
                PublicGameStateResponse.GameEnd.abandoned(
                        "INACTIVITY", "Game closed due to 15 minutes of inactivity."));

        Game game = Game.restoreBuilder()
                .gameId(room.getRoomId())
                .maxPlayers(6)
                .players(List.of(alice, bob))
                .activePlayerIds(List.of("alice-id", "bob-id"))
                .remainingDeck(List.of())
                .pot(15)
                .currentHighestBet(10)
                .currentPhase(GamePhase.PRE_FLOP)
                .gameOver(true)
                .smallBlind(5)
                .bigBlind(10)
                .handNumber(1L)
                .stateRevision(3L)
                .gameEndResult(abandonedResult)
                .build();

        RoomSnapshotMapper mapper = newMapper();
        PublicGameStateResponse restored = mapper.deserialize(mapper.serialize(mapper.captureSnapshot(room, game)))
                .game().getGameEndResult();

        assertThat(restored).isEqualTo(abandonedResult);
        assertNotNull(restored.gameEnd());
        assertTrue(restored.gameEnd().abandoned());
        assertNull(restored.gameEnd().winner());
        assertNull(restored.gameEnd().winnerChips());
        assertTrue(restored.winners().isEmpty());
    }

    static Stream<Arguments> corruptGames() {
        return Stream.of(
                Arguments.of("game id differs from room id", (Consumer<ObjectNode>) g -> g.put("gameId", "other-room"),
                        "Game identity does not match room identity in snapshot"),
                Arguments.of("null game id", (Consumer<ObjectNode>) g -> g.putNull("gameId"),
                        "Game identity does not match room identity in snapshot"),
                Arguments.of("negative pot", (Consumer<ObjectNode>) g -> g.put("pot", -1),
                        "Invalid negative pot in snapshot"),
                Arguments.of("null readyGateState", (Consumer<ObjectNode>) g -> g.putNull("readyGateState"),
                        "Missing readyGateState in snapshot"),
                Arguments.of("stale readyCountdownActive property",
                        (Consumer<ObjectNode>) g -> g.put("readyCountdownActive", false),
                        "Cannot decode aggregate snapshot"),
                Arguments.of("missing readyGateState property",
                        (Consumer<ObjectNode>) g -> g.remove("readyGateState"),
                        "Cannot decode aggregate snapshot"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("corruptGames")
    @DisplayName("deserialize rejects a corrupt game aggregate with the specific message")
    void deserialize_CorruptGame_IsRejectedWithSpecificMessage(String name, Consumer<ObjectNode> corruption,
            String expectedMessage) {
        ObjectNode snapshot = validFinishedSnapshotTree();
        corruption.accept((ObjectNode) snapshot.get("game"));

        PersistenceException ex = assertThrows(PersistenceException.class,
                () -> newMapper().deserialize(JSON.writeValueAsBytes(snapshot)));

        assertEquals(expectedMessage, ex.getMessage(), name);
    }

    private static RoomSnapshot.RoomState roomState(String roomId, int maxPlayers, int smallBlind,
            int bigBlind, int buyIn) {
        return new RoomSnapshot.RoomState(roomId, "Room", "Host", maxPlayers, smallBlind, bigBlind, buyIn,
                null, LocalDateTime.of(2024, 1, 1, 12, 0), List.of(), Map.of(), false, null);
    }

    private static RoomSnapshot lobbySnapshot(RoomSnapshot.RoomState room) {
        return new RoomSnapshot(false, room, null);
    }

    static Stream<Arguments> invalidSnapshots() {
        String unsupported = "Unsupported in-memory aggregate image";
        String missingRoom = "Missing required room state in aggregate snapshot";
        String badParameters = "Invalid room parameters in snapshot";
        return Stream.of(
                Arguments.of("null snapshot", null, unsupported),
                Arguments.of("missing room", lobbySnapshot(null), missingRoom),
                Arguments.of("null room id", lobbySnapshot(roomState(null, 6, 5, 10, 100)), missingRoom),
                Arguments.of("empty room id", lobbySnapshot(roomState("", 6, 5, 10, 100)), missingRoom),
                Arguments.of("blank room id", lobbySnapshot(roomState("   ", 6, 5, 10, 100)), missingRoom),
                Arguments.of("maxPlayers below 2", lobbySnapshot(roomState("r", 1, 5, 10, 100)), badParameters),
                Arguments.of("maxPlayers above 10", lobbySnapshot(roomState("r", 11, 5, 10, 100)),
                        badParameters),
                Arguments.of("small blind below 1", lobbySnapshot(roomState("r", 6, 0, 10, 100)),
                        badParameters),
                Arguments.of("big blind below twice the small blind",
                        lobbySnapshot(roomState("r", 6, 5, 9, 100)), badParameters),
                Arguments.of("buy-in below the big blind", lobbySnapshot(roomState("r", 6, 5, 10, 9)),
                        badParameters));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("invalidSnapshots")
    @DisplayName("restoreSnapshot rejects an invalid snapshot with the specific message")
    void restoreSnapshot_InvalidSnapshot_IsRejectedWithSpecificMessage(String name, RoomSnapshot snapshot,
            String expectedMessage) {
        PersistenceException ex = assertThrows(PersistenceException.class,
                () -> newMapper().restoreSnapshot(snapshot));

        assertEquals(expectedMessage, ex.getMessage(), name);
    }

    @Test
    @DisplayName("restoreSnapshot accepts the boundary room parameters")
    void restoreSnapshot_BoundaryRoomParameters_AreAccepted() {
        RoomSnapshotMapper mapper = newMapper();

        Room minimal = mapper.restoreSnapshot(lobbySnapshot(roomState("r", 2, 1, 2, 2))).room();
        Room maximal = mapper.restoreSnapshot(lobbySnapshot(roomState("r", 10, 5, 10, 10))).room();

        assertEquals(2, minimal.getMaxPlayers());
        assertEquals(10, maximal.getMaxPlayers());
    }

    @Test
    @DisplayName("A deleted snapshot restores to a deleted marker without room or game, even from a tombstone")
    void restoreSnapshot_DeletedSnapshot_ReturnsDeletedMarker() {
        RoomSnapshotMapper mapper = newMapper();

        RecoveredRoom fromSnapshot = mapper.restoreSnapshot(
                new RoomSnapshot(true, null, null));
        RecoveredRoom fromTombstone = mapper.deserialize(mapper.serializeDeletion("gone-room"));

        for (RecoveredRoom recovered : List.of(fromSnapshot, fromTombstone)) {
            assertTrue(recovered.deleted());
            assertNull(recovered.room());
            assertNull(recovered.game());
        }
    }

    static Stream<Arguments> hostReconciliations() {
        return Stream.of(
                Arguments.of("the host is in the room", "Bob", List.of("Alice", "Bob"), "Bob"),
                Arguments.of("the host left, first member is used", "Zed", List.of("Carol", "Alice"), "Carol"),
                Arguments.of("no host, first member is used", null, List.of("Carol", "Dave"), "Carol"),
                Arguments.of("empty room keeps the stored host", "Yan", List.of(), "Yan"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("hostReconciliations")
    @DisplayName("restoreSnapshot reconciles the host against actual room membership")
    void restoreSnapshot_HostReconciliation_FallsBackThroughMembership(
            String name, String hostName, List<String> members, String expectedHost) {
        RoomSnapshot.RoomState room = new RoomSnapshot.RoomState("r", "Room", hostName, 6, 5, 10,
                100, null, LocalDateTime.of(2024, 1, 1, 12, 0), members, Map.of(), false, null);
        RoomSnapshot snapshot = new RoomSnapshot(false, room, null);

        RecoveredRoom recovered = newMapper().restoreSnapshot(snapshot);

        assertEquals(expectedHost, recovered.room().getHostName(), name);
        assertFalse(recovered.deleted());
        assertNull(recovered.game());
    }

    @Test
    @DisplayName("captureSnapshot rejects a null room with a PersistenceException")
    void captureSnapshot_NullRoom_IsRejected() {
        PersistenceException ex = assertThrows(PersistenceException.class,
                () -> newMapper().captureSnapshot(null, null));

        assertEquals("Room cannot be null for aggregate state capture", ex.getMessage());
    }

    @Test
    @DisplayName("serialize rejects a null room and a null snapshot")
    void serialize_NullInputs_AreRejected() {
        RoomSnapshotMapper mapper = newMapper();

        PersistenceException nullSnapshot = assertThrows(PersistenceException.class,
                () -> mapper.serialize((RoomSnapshot) null));
        PersistenceException nullRoom = assertThrows(PersistenceException.class,
                () -> mapper.serialize(mapper.captureSnapshot(null, null)));

        assertEquals("State image cannot be null for serialization", nullSnapshot.getMessage());
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
    @DisplayName("restoreRoom restores a state without players, and capture keeps the host")
    void restoreRoom_MissingPlayers_AreHandled() {
        RoomSnapshotMapper mapper = newMapper();

        Room restored = mapper.restoreRoom(new RoomSnapshot.RoomState("r", "Room", "Host", 6, 5, 10, 100,
                null, LocalDateTime.of(2024, 1, 1, 12, 0), null, Map.of(), false, null));
        assertEquals("r", restored.getRoomId());
        assertTrue(restored.getPlayers().isEmpty());

        Room live = new Room("live", "Live", "Host", 4, 5, 10, 100, null);
        live.addPlayer("Host");
        RoomSnapshot.RoomState captured = mapper.captureSnapshot(live, null).room();
        assertEquals("live", captured.roomId());
        assertEquals(List.of("Host"), captured.players());
    }

    @Nested
    class SnapshotRecoveryScenarios {

        private static final String ROOM_ID = "empirical-room-m2";
        private RoomSnapshotMapper snapshotMapper;

        @BeforeEach
        void setUp() {
            snapshotMapper = newMapper();
        }

        private Player createPlayer(String id, String name, int chips, int currentBet) {
            return Player.restoreBuilder()
                    .playerId(id)
                    .name(name)
                    .chips(chips)
                    .currentBet(currentBet)
                    .holeCards(List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES)))
                    .bestHand(List.of())
                    .handRank(HandRank.NO_HAND)
                    .hasFolded(false)
                    .allIn(false)
                    .out(false)
                    .disconnected(false)
                    .readyForNextHand(false)
                    .build();
        }

        private Room createRoom(String roomId, List<Player> players) {
            Room room = new Room(roomId, "Empirical Table", players.getFirst().getName(), 6, 10, 20, 1000,
                    PASSWORD_ENCODER.encode("secret-pass"));
            for (Player p : players) {
                room.addPlayer(p.getName());
            }
            room.setGameStarted(true);
            return room;
        }

        @Test
        @DisplayName("Phase 1: PRE_FLOP snapshot round-trip preserves state & action continuity")
        void testPhase_PreFlop_RoundTripAndActionContinuity() {
            Player p1 = createPlayer("p1", "Alice", 1000, 0);
            Player p2 = createPlayer("p2", "Bob", 990, 10);
            Player p3 = createPlayer("p3", "Charlie", 980, 20);
            List<Player> players = List.of(p1, p2, p3);
            Room room = createRoom(ROOM_ID, players);

            Game game = Game.restoreBuilder()
                    .gameId(ROOM_ID)
                    .maxPlayers(6)
                    .players(players)
                    .activePlayerIds(List.of("p1", "p2", "p3"))
                    .remainingDeck(DETERMINISTIC_DECK)
                    .communityCards(List.of())
                    .pot(30)
                    .currentPlayerId("p1")
                    .currentHighestBet(20)
                    .currentPhase(GamePhase.PRE_FLOP)
                    .smallBlind(10)
                    .bigBlind(20)
                    .handContributions(Map.of("p2", 10, "p3", 20))
                    .handSeatOrder(List.of("p1", "p2", "p3"))
                    .dealerPlayerId("p1")
                    .smallBlindPlayerId("p2")
                    .bigBlindPlayerId("p3")
                    .build();

            byte[] snapshotBytes = snapshotMapper.serialize(snapshotMapper.captureSnapshot(room, game));
            RecoveredRoom recovered = snapshotMapper.deserialize(snapshotBytes);
            Game restored = recovered.game();

            assertNotNull(restored);
            assertEquals(ROOM_ID, restored.getGameId());
            assertEquals(GamePhase.PRE_FLOP, restored.getCurrentPhase());
            assertEquals(30, restored.getPot());
            assertEquals(20, restored.getCurrentHighestBet());
            assertEquals(3, restored.getPlayers().size());
            assertEquals(3, restored.getActivePlayers().size());
            assertEquals(game.getCurrentPlayer().getPlayerId(), restored.getCurrentPlayer().getPlayerId());
            assertEquals(game.toSnapshot().remainingDeck(), restored.toSnapshot().remainingDeck());
            assertEquals(game.toSnapshot().handSeatOrder(), restored.toSnapshot().handSeatOrder());
            assertEquals(game.toSnapshot().dealerPlayerId(), restored.toSnapshot().dealerPlayerId());

            for (int i = 0; i < 3; i++) {
                assertEquals(game.getPlayers().get(i).getHoleCards(), restored.getPlayers().get(i).getHoleCards());
            }

            Player current = restored.getCurrentPlayer();
            restored.processPlayerDecision(current, PlayerAction.CALL, 0);
            assertEquals(50, restored.getPot(), "Pot must increase to 50 following call on restored game");
        }

        @Test
        @DisplayName("Phase 2: FLOP snapshot round-trip preserves 3 community cards")
        void testPhase_Flop_RoundTripAndProgression() {
            Player p1 = createPlayer("p1", "Alice", 980, 0);
            Player p2 = createPlayer("p2", "Bob", 980, 0);
            List<Player> players = List.of(p1, p2);
            Room room = createRoom(ROOM_ID, players);
            List<Card> flopCards = DETERMINISTIC_DECK.subList(0, 3);

            Game game = Game.restoreBuilder()
                    .gameId(ROOM_ID)
                    .maxPlayers(6)
                    .players(players)
                    .activePlayerIds(List.of("p1", "p2"))
                    .remainingDeck(DETERMINISTIC_DECK.subList(3, 5))
                    .communityCards(flopCards)
                    .pot(40)
                    .currentPlayerId("p1")
                    .currentHighestBet(0)
                    .currentPhase(GamePhase.FLOP)
                    .smallBlind(10)
                    .bigBlind(20)
                    .handContributions(Map.of("p1", 20, "p2", 20))
                    .handSeatOrder(List.of("p1", "p2"))
                    .dealerPlayerId("p1")
                    .smallBlindPlayerId("p1")
                    .bigBlindPlayerId("p2")
                    .build();

            byte[] snapshotBytes = snapshotMapper.serialize(snapshotMapper.captureSnapshot(room, game));
            Game restored = snapshotMapper.deserialize(snapshotBytes).game();

            assertEquals(GamePhase.FLOP, restored.getCurrentPhase());
            assertEquals(3, restored.getCommunityCards().size());
            assertEquals(flopCards, restored.getCommunityCards());
            assertEquals(2, restored.toSnapshot().remainingDeck().size());

            restored.dealTurn();
            assertEquals(GamePhase.TURN, restored.getCurrentPhase());
            assertEquals(4, restored.getCommunityCards().size());
        }

        @Test
        @DisplayName("Phase 3: TURN snapshot round-trip preserves 4 community cards")
        void testPhase_Turn_RoundTripAndProgression() {
            Player p1 = createPlayer("p1", "Alice", 980, 0);
            Player p2 = createPlayer("p2", "Bob", 980, 0);
            List<Player> players = List.of(p1, p2);
            Room room = createRoom(ROOM_ID, players);
            List<Card> turnCards = DETERMINISTIC_DECK.subList(0, 4);

            Game game = Game.restoreBuilder()
                    .gameId(ROOM_ID)
                    .maxPlayers(6)
                    .players(players)
                    .activePlayerIds(List.of("p1", "p2"))
                    .remainingDeck(DETERMINISTIC_DECK.subList(4, 5))
                    .communityCards(turnCards)
                    .pot(40)
                    .currentPlayerId("p1")
                    .currentHighestBet(0)
                    .currentPhase(GamePhase.TURN)
                    .smallBlind(10)
                    .bigBlind(20)
                    .handContributions(Map.of("p1", 20, "p2", 20))
                    .handSeatOrder(List.of("p1", "p2"))
                    .dealerPlayerId("p1")
                    .smallBlindPlayerId("p1")
                    .bigBlindPlayerId("p2")
                    .build();

            byte[] snapshotBytes = snapshotMapper.serialize(snapshotMapper.captureSnapshot(room, game));
            Game restored = snapshotMapper.deserialize(snapshotBytes).game();

            assertEquals(GamePhase.TURN, restored.getCurrentPhase());
            assertEquals(4, restored.getCommunityCards().size());
            assertEquals(turnCards, restored.getCommunityCards());

            restored.dealRiver();
            assertEquals(GamePhase.RIVER, restored.getCurrentPhase());
            assertEquals(5, restored.getCommunityCards().size());
        }

        @Test
        @DisplayName("Phase 4: RIVER snapshot round-trip preserves 5 community cards")
        void testPhase_River_RoundTripAndShowdown() {
            Player p1 = createPlayer("p1", "Alice", 980, 0);
            Player p2 = createPlayer("p2", "Bob", 980, 0);
            List<Player> players = List.of(p1, p2);
            Room room = createRoom(ROOM_ID, players);

            Game game = Game.restoreBuilder()
                    .gameId(ROOM_ID)
                    .maxPlayers(6)
                    .players(players)
                    .activePlayerIds(List.of("p1", "p2"))
                    .remainingDeck(List.of())
                    .communityCards(DETERMINISTIC_DECK)
                    .pot(40)
                    .currentPlayerId("p1")
                    .currentHighestBet(0)
                    .currentPhase(GamePhase.RIVER)
                    .smallBlind(10)
                    .bigBlind(20)
                    .handContributions(Map.of("p1", 20, "p2", 20))
                    .handSeatOrder(List.of("p1", "p2"))
                    .dealerPlayerId("p1")
                    .smallBlindPlayerId("p1")
                    .bigBlindPlayerId("p2")
                    .build();

            byte[] snapshotBytes = snapshotMapper.serialize(snapshotMapper.captureSnapshot(room, game));
            Game restored = snapshotMapper.deserialize(snapshotBytes).game();

            assertEquals(GamePhase.RIVER, restored.getCurrentPhase());
            assertEquals(5, restored.getCommunityCards().size());
            assertEquals(DETERMINISTIC_DECK, restored.getCommunityCards());

            assertDoesNotThrow(restored::conductShowdown);
            assertNotNull(restored.getLastHandPayout());
            assertTrue(restored.isHandSettled());
        }

        @Test
        @DisplayName("Phase 5: SHOWDOWN snapshot round-trip preserves HandPayout & resetForNewHand")
        void testPhase_Showdown_RoundTripAndResetForNewHand() {
            Player p1 = createPlayer("p1", "Alice", 1020, 0);
            Player p2 = createPlayer("p2", "Bob", 980, 0);
            List<Player> players = List.of(p1, p2);
            Room room = createRoom(ROOM_ID, players);
            HandPayout originalSettlement = new HandPayout(Map.of("p1", 40), Map.of(), Set.of("p1"), 0, true);

            Game game = Game.restoreBuilder()
                    .gameId(ROOM_ID)
                    .maxPlayers(6)
                    .players(players)
                    .activePlayerIds(List.of("p1", "p2"))
                    .remainingDeck(List.of())
                    .communityCards(DETERMINISTIC_DECK)
                    .pot(0)
                    .currentPlayerId("p1")
                    .currentHighestBet(0)
                    .currentPhase(GamePhase.SHOWDOWN)
                    .smallBlind(10)
                    .bigBlind(20)
                    .handSeatOrder(List.of("p1", "p2"))
                    .dealerPlayerId("p1")
                    .smallBlindPlayerId("p1")
                    .bigBlindPlayerId("p2")
                    .lastHandPayout(originalSettlement)
                    .build();

            byte[] snapshotBytes = snapshotMapper.serialize(snapshotMapper.captureSnapshot(room, game));
            Game restored = snapshotMapper.deserialize(snapshotBytes).game();

            assertNotNull(restored.getLastHandPayout());
            assertEquals(originalSettlement, restored.getLastHandPayout());

            assertDoesNotThrow(restored::resetForNewHand);
            assertEquals(GamePhase.PRE_FLOP, restored.getCurrentPhase());
            assertTrue(restored.getCommunityCards().isEmpty());
        }

        @Test
        @DisplayName("Settlement round-trip keeps whether the hand went to a contested showdown")
        void testSettlementRoundTrip_PreservesContestedShowdownVersusUncontestedFoldWin() {
            Player p1 = createPlayer("p1", "Alice", 1020, 0);
            Player p2 = createPlayer("p2", "Bob", 980, 0);
            List<Player> contestedPlayers = List.of(p1, p2);
            Room contestedRoom = createRoom(ROOM_ID, contestedPlayers);
            Game contested = Game.restoreBuilder()
                    .gameId(ROOM_ID)
                    .maxPlayers(6)
                    .players(contestedPlayers)
                    .activePlayerIds(List.of("p1", "p2"))
                    .remainingDeck(List.of())
                    .communityCards(DETERMINISTIC_DECK)
                    .currentPhase(GamePhase.SHOWDOWN)
                    .smallBlind(10)
                    .bigBlind(20)
                    .lastHandPayout(new HandPayout(Map.of("p1", 40), Map.of(), Set.of("p1"), 0, true))
                    .build();
            assertTrue(contested.wasContestedShowdown());

            Game restoredContested = snapshotMapper.deserialize(
                    snapshotMapper.serialize(snapshotMapper.captureSnapshot(contestedRoom, contested))).game();

            assertTrue(restoredContested.isHandSettled());
            assertTrue(restoredContested.wasContestedShowdown());

            Player f1 = createPlayer("p1", "Alice", 1010, 0);
            Player f2 = createPlayer("p2", "Bob", 990, 0);
            List<Player> foldPlayers = List.of(f1, f2);
            Room foldRoom = createRoom(ROOM_ID, foldPlayers);
            Game foldWin = Game.restoreBuilder()
                    .gameId(ROOM_ID)
                    .maxPlayers(6)
                    .players(foldPlayers)
                    .activePlayerIds(List.of("p1", "p2"))
                    .remainingDeck(List.of())
                    .currentPhase(GamePhase.SHOWDOWN)
                    .smallBlind(10)
                    .bigBlind(20)
                    .lastHandPayout(new HandPayout(Map.of("p1", 20), Map.of("p1", 10), Set.of("p1"), 0, false))
                    .build();
            assertTrue(foldWin.isHandSettled());
            assertFalse(foldWin.wasContestedShowdown());

            Game restoredFoldWin = snapshotMapper.deserialize(
                    snapshotMapper.serialize(snapshotMapper.captureSnapshot(foldRoom, foldWin))).game();

            assertTrue(restoredFoldWin.isHandSettled());
            assertFalse(restoredFoldWin.wasContestedShowdown());
        }

        @Test
        @DisplayName("ReadyGateState COUNTING_DOWN: Restores active flag, deadline, and enum state")
        void testReadyGateState_CountingDown() {
            Player p1 = createPlayer("p1", "Alice", 1000, 0);
            Player p2 = createPlayer("p2", "Bob", 1000, 0);
            List<Player> players = List.of(p1, p2);
            Room room = createRoom(ROOM_ID, players);
            long deadline = FIXED_EPOCH_MS + 10_000L;

            Game game = Game.restoreBuilder()
                    .gameId(ROOM_ID)
                    .players(players)
                    .activePlayerIds(List.of("p1", "p2"))
                    .remainingDeck(List.of())
                    .smallBlind(10)
                    .bigBlind(20)
                    .currentPhase(GamePhase.PRE_FLOP)
                    .readyCountdownDeadlineEpochMs(deadline)
                    .readyGateState(ReadyGateState.COUNTING_DOWN)
                    .build();

            assertEquals(ReadyGateState.COUNTING_DOWN, game.getReadyGateState());
            assertTrue(game.isReadyCountdownActive());
            assertEquals(deadline, game.getReadyCountdownDeadlineEpochMs());

            byte[] bytes = snapshotMapper.serialize(snapshotMapper.captureSnapshot(room, game));
            Game restored = snapshotMapper.deserialize(bytes).game();

            assertEquals(ReadyGateState.COUNTING_DOWN, restored.getReadyGateState());
            assertTrue(restored.isReadyCountdownActive());
            assertEquals(deadline, restored.getReadyCountdownDeadlineEpochMs());
        }

        @Test
        @DisplayName("ReadyGateState WAITING_FOR_PLAYERS: Restores inactive flag, null deadline, and enum state")
        void testReadyGateState_WaitingForPlayers() {
            Player p1 = createPlayer("p1", "Alice", 1000, 0);
            Player p2 = createPlayer("p2", "Bob", 1000, 0);
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
                    .readyCountdownDeadlineEpochMs(null)
                    .readyGateState(ReadyGateState.WAITING_FOR_PLAYERS)
                    .build();

            assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, game.getReadyGateState());
            assertFalse(game.isReadyCountdownActive());
            assertNull(game.getReadyCountdownDeadlineEpochMs());

            byte[] bytes = snapshotMapper.serialize(snapshotMapper.captureSnapshot(room, game));
            Game restored = snapshotMapper.deserialize(bytes).game();

            assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, restored.getReadyGateState());
            assertFalse(restored.isReadyCountdownActive());
            assertNull(restored.getReadyCountdownDeadlineEpochMs());
        }

        @Test
        @DisplayName("ReadyGateState CLOSED: Restores inactive flag and CLOSED enum state")
        void testReadyGateState_Closed() {
            Player p1 = createPlayer("p1", "Alice", 1000, 0);
            Player p2 = createPlayer("p2", "Bob", 1000, 0);
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
                    .readyCountdownDeadlineEpochMs(null)
                    .readyGateState(ReadyGateState.CLOSED)
                    .build();

            assertEquals(ReadyGateState.CLOSED, game.getReadyGateState());
            assertFalse(game.isReadyCountdownActive());

            byte[] bytes = snapshotMapper.serialize(snapshotMapper.captureSnapshot(room, game));
            Game restored = snapshotMapper.deserialize(bytes).game();

            assertEquals(ReadyGateState.CLOSED, restored.getReadyGateState());
            assertFalse(restored.isReadyCountdownActive());
        }

        @Test
        @DisplayName("Scheduled Tasks & Defensive Isolation: Validates snapshot round-trip of task deadlines & timer versions")
        void testScheduledTasksAndTimerVersionsRoundTrip() {
            Player p1 = createPlayer("p1", "Alice", 1000, 0);
            Player p2 = createPlayer("p2", "Bob", 1000, 0);
            List<Player> players = List.of(p1, p2);
            Room room = createRoom(ROOM_ID, players);

            Map<ScheduledGameTask, Long> deadlines = new EnumMap<>(ScheduledGameTask.class);
            deadlines.put(ScheduledGameTask.AUTO_ADVANCE, 1700000010000L);
            deadlines.put(ScheduledGameTask.READY_TIMEOUT, 1700000020000L);

            Map<ScheduledGameTask, Long> timerVersions = new EnumMap<>(ScheduledGameTask.class);
            timerVersions.put(ScheduledGameTask.AUTO_ADVANCE, 3L);
            timerVersions.put(ScheduledGameTask.READY_TIMEOUT, 7L);

            Game game = Game.restoreBuilder()
                    .gameId(ROOM_ID)
                    .players(players)
                    .activePlayerIds(List.of("p1", "p2"))
                    .remainingDeck(List.of())
                    .smallBlind(10)
                    .bigBlind(20)
                    .currentPhase(GamePhase.PRE_FLOP)
                    .scheduledTimes(deadlines)
                    .timerVersions(timerVersions)
                    .stateRevision(42L)
                    .handNumber(5L)
                    .build();

            assertEquals(42L, game.getStateRevision());
            assertEquals(5L, game.getHandNumber());
            assertEquals(deadlines, game.toSnapshot().scheduledTimes());
            assertEquals(timerVersions, game.toSnapshot().timerVersions());

            byte[] bytes = snapshotMapper.serialize(snapshotMapper.captureSnapshot(room, game));
            Game restored = snapshotMapper.deserialize(bytes).game();

            assertEquals(42L, restored.getStateRevision());
            assertEquals(5L, restored.getHandNumber());
            assertEquals(deadlines, restored.toSnapshot().scheduledTimes());
            assertEquals(timerVersions, restored.toSnapshot().timerVersions());
        }
    }

    @Test
    void repeatedSeatingGetterCallsDoNotMutateCapturedSnapshot() {
        RoomSnapshotMapper mapper = newMapper();
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
                .dealerPlayerId("p1")
                .smallBlindPlayerId(null)
                .bigBlindPlayerId(null)
                .currentPlayerId("p2")
                .currentPhase(GamePhase.PRE_FLOP)
                .build();

        RoomSnapshot beforeSnapshot = mapper.captureSnapshot(room, game);
        byte[] beforeBytes = mapper.serialize(mapper.captureSnapshot(room, game));

        for (int i = 0; i < 3; i++) {
            assertEquals("p2", game.getCurrentPlayer().getPlayerId());
            assertEquals("p2", game.getSmallBlindPlayerId());
            assertEquals("p3", game.getBigBlindPlayerId());
        }

        RoomSnapshot afterSnapshot = mapper.captureSnapshot(room, game);
        byte[] afterBytes = mapper.serialize(mapper.captureSnapshot(room, game));

        assertEquals(beforeSnapshot, afterSnapshot);
        assertArrayEquals(beforeBytes, afterBytes);
    }
}
