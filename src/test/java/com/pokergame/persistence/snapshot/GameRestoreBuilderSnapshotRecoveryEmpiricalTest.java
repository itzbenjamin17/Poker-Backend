package com.pokergame.persistence.snapshot;

import com.pokergame.dto.internal.PlayerDecision;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.enums.*;
import com.pokergame.exception.BadRequestException;
import com.pokergame.model.*;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.persistence.wal.EncryptionKeyring;
import com.pokergame.persistence.wal.WalTransaction;
import com.pokergame.service.HandEvaluatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Empirical challenger test suite for Milestone 2: GameRestoreBuilder Extraction.
 *
 * Verifies:
 * 1. Snapshot serialization and recovery round-trips via AggregateSnapshotMapper.
 * 2. WAL snapshotting and deserialization flows across ALL game phases (PRE_FLOP, FLOP, TURN, RIVER, SHOWDOWN).
 * 3. Ready gate countdown state restoration across COUNTING_DOWN, WAITING_FOR_PLAYERS, and CLOSED.
 * 4. Post-recovery game continuity and state invariance.
 */
@Tag("unit")
@DisplayName("GameRestoreBuilder Snapshot Recovery Empirical Challenge")
class GameRestoreBuilderSnapshotRecoveryEmpiricalTest {

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
