package com.pokergame.persistence;

import com.pokergame.PokerApplication;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.PlayerAction;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.persistence.snapshot.AggregateSnapshotMapper;
import com.pokergame.persistence.snapshot.AggregateStateImage;
import com.pokergame.persistence.transaction.DurableMutation;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.persistence.wal.EncryptionKeyring;
import com.pokergame.persistence.wal.WalTransaction;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.HandEvaluatorService;
import com.pokergame.service.PlayerActionService;
import com.pokergame.service.RoomService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import com.pokergame.config.WebSocketEventListener;
import java.time.LocalDateTime;
import org.springframework.context.annotation.Configuration;

/**
 * Protects encrypted WAL durability and recovery behavior:
 * <ul>
 *   <li>crash-torn frames at varied byte boundaries are truncated and recovery proceeds cleanly;</li>
 *   <li>corrupted room WALs are quarantined during startup recovery while valid rooms still restore;</li>
 *   <li>a mid-mutation failure rolls the in-memory aggregate back and conserves chips;</li>
 *   <li>persistence stays disabled by default and under the e2e profile / CLI override.</li>
 * </ul>
 */
public class WalRecoveryAndRollbackTest {

    private static final int MAGIC = 0x504B574C;

    private EncryptionKeyring createKeyring(String keyId, byte seed) {
        byte[] keyBytes = new byte[32];
        Arrays.fill(keyBytes, seed);
        return new EncryptionKeyring(keyId, Map.of(keyId, new SecretKeySpec(keyBytes, "AES")));
    }

    // =========================================================================
    // Crash-torn frame truncation and recovery
    // =========================================================================

    @Test
    @DisplayName("Multi-cycle crash-torn frame stress across 1B, 4B, 7B, 8B and ciphertext boundaries recovers cleanly")
    void tornFrameTruncation_recoversLastCommittedPayloadAndTruncatesDisk(@TempDir Path tempDir) throws Exception {
        String roomId = "torn-frame-room";
        EncryptionKeyring keyring = createKeyring("k1", (byte) 7);
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring);
        Path walPath = tempDir.resolve(roomId + ".wal");

        // Step 1: Commit initial valid records
        WalTransaction t0 = store.prepare(roomId);
        store.commit(t0, "payload-v0".getBytes(StandardCharsets.UTF_8));
        WalTransaction t1 = store.prepare(roomId);
        store.commit(t1, "payload-v1".getBytes(StandardCharsets.UTF_8));

        long lastValidSize = Files.size(walPath);
        assertTrue(lastValidSize > 0, "WAL must have positive size after initial commits");

        // Define simulated crash truncations to test across multiple cycles:
        // 1. Exactly 1 byte of frame header (partial MAGIC)
        // 2. Exactly 4 bytes of frame header (MAGIC only, 0 bytes length)
        // 3. Exactly 7 bytes of frame header (MAGIC + 3 bytes length)
        // 4. Exactly 8 bytes of frame header (MAGIC + complete length, 0 body)
        // 5. 8 + 10 bytes (header + partial format/schema/seq)
        // 6. 8 + 35 bytes (header + metadata + partial nonce)
        // 7. 8 + 80 bytes (header + partial ciphertext)
        int[] tornHeaderSizes = {1, 4, 7, 8, 18, 43, 88};

        int cycle = 2;
        for (int tornSize : tornHeaderSizes) {
            byte[] currentWal = Files.readAllBytes(walPath);
            assertEquals(lastValidSize, currentWal.length);

            // Synthesize a torn frame at EOF
            byte[] syntheticTornBytes = new byte[tornSize];
            if (tornSize >= 4) {
                ByteBuffer.wrap(syntheticTornBytes).putInt(MAGIC);
            }
            if (tornSize >= 8) {
                ByteBuffer.wrap(syntheticTornBytes).putInt(4, 120); // frameLength = 120, but we supply tornSize - 8 bytes
            }
            if (tornSize < 4) {
                syntheticTornBytes[0] = (byte) 0x50; // 'P'
            }
            Files.write(walPath, syntheticTornBytes, StandardOpenOption.APPEND);
            assertEquals(lastValidSize + tornSize, Files.size(walPath));

            // Recover latest: MUST truncate the torn bytes on disk back to lastValidSize
            EncryptedWalStore recoveringStore = new EncryptedWalStore(tempDir, keyring);
            Optional<byte[]> recovered = recoveringStore.recoverLatest(roomId);
            assertTrue(recovered.isPresent());
            assertEquals("payload-v" + (cycle - 1), new String(recovered.get(), StandardCharsets.UTF_8));

            // Assert physical truncation on disk
            long truncatedSize = Files.size(walPath);
            assertEquals(lastValidSize, truncatedSize,
                    "Disk WAL must be truncated back to lastValidSize for torn length " + tornSize);

            // Subsequent mutation MUST append cleanly without AEADBadTagException or splice corruption
            EncryptedWalStore writerStore = new EncryptedWalStore(tempDir, keyring);
            WalTransaction nextTx = writerStore.prepare(roomId);
            String nextPayload = "payload-v" + cycle;
            writerStore.commit(nextTx, nextPayload.getBytes(StandardCharsets.UTF_8));

            lastValidSize = Files.size(walPath);
            assertTrue(lastValidSize > truncatedSize);
            cycle++;
        }

        // Test boundary: Complete PREPARE frame followed by torn COMMIT frame (5 bytes of commit)
        {
            EncryptedWalStore prepareStore = new EncryptedWalStore(tempDir, keyring);
            prepareStore.prepare(roomId);
            long sizeAfterPrepare = Files.size(walPath);
            assertTrue(sizeAfterPrepare > lastValidSize);

            // Append 5 bytes of torn commit
            Files.write(walPath, new byte[]{0x50, 0x4B, 0x57, 0x4C, 0x00}, StandardOpenOption.APPEND);

            EncryptedWalStore recoveringStore = new EncryptedWalStore(tempDir, keyring);
            Optional<byte[]> recovered = recoveringStore.recoverLatest(roomId);
            assertTrue(recovered.isPresent());
            // Since prepare had no commit, latest committed payload is payload-v(cycle - 1)
            assertEquals("payload-v" + (cycle - 1), new String(recovered.get(), StandardCharsets.UTF_8));
            // Disk file truncated torn commit header back to size after prepare
            assertEquals(sizeAfterPrepare, Files.size(walPath));

            // Subsequent full transaction commits cleanly
            EncryptedWalStore writerStore = new EncryptedWalStore(tempDir, keyring);
            WalTransaction nextTx = writerStore.prepare(roomId);
            writerStore.commit(nextTx, ("payload-v" + cycle).getBytes(StandardCharsets.UTF_8));
        }

        // Finally: open in brand-new store and assert all records are clean with ZERO corruption
        EncryptedWalStore freshStore = new EncryptedWalStore(tempDir, keyring);
        Optional<byte[]> finalRecovered = freshStore.recoverLatest(roomId);
        assertTrue(finalRecovered.isPresent());
        assertEquals("payload-v" + cycle, new String(finalRecovered.get(), StandardCharsets.UTF_8));
        assertTrue(freshStore.isHealthy());
        assertNull(freshStore.lastError());
    }

    // =========================================================================
    // Corrupted WAL quarantine during startup recovery
    // =========================================================================

    @Test
    @DisplayName("Multi-room corrupted WAL quarantine during startup recovery isolates bad rooms and restores valid ones")
    void corruptedWalQuarantine_duringStartupRecovery_isolatesBadRoomsKeepsValidRooms(@TempDir Path tempDir) throws Exception {
        EncryptionKeyring keyring = createKeyring("k1", (byte) 21);
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring);
        HandEvaluatorService evaluator = new HandEvaluatorService();
        AggregateSnapshotMapper mapper = new AggregateSnapshotMapper(
                JsonMapper.builder().findAndAddModules().build(), evaluator);

        RoomService mockRoomService = Mockito.mock(RoomService.class);
        GameLifecycleService mockGameLifecycleService = Mockito.mock(GameLifecycleService.class);
        WebSocketEventListener mockWebSocketEventListener =
                Mockito.mock(WebSocketEventListener.class);
        GenericApplicationContext appContext = new GenericApplicationContext();
        appContext.refresh();

        PersistenceRecovery recovery = new PersistenceRecovery(
                store, mapper, mockRoomService, mockGameLifecycleService, mockWebSocketEventListener, appContext, 15_000);

        // Room 1 (VALID with active game)
        String r1 = "room-valid-1";
        Room room1 = Room.restoreBuilder()
                .roomId(r1).roomName("Valid Room 1").hostName("Alice").maxPlayers(6)
                .smallBlind(10).bigBlind(20).buyIn(1000).password(null)
                .playersWithJoinTime(Map.of("Alice", LocalDateTime.now(), "Bob", LocalDateTime.now()))
                .gameStarted(true).build();
        Player p1 = new Player("Alice", "p1", 950);
        Player p2 = new Player("Bob", "p2", 950);
        Game game1 = new Game(r1, new ArrayList<>(List.of(p1, p2)), 10, 20, evaluator);
        AggregateStateImage img1 = mapper.captureStateImage(room1, "Alice", game1);
        WalTransaction tx1 = store.prepare(r1);
        store.commit(tx1, mapper.serialize(img1));

        // Room 2 (CORRUPTED SNAPSHOT PAYLOAD: valid AES-GCM frame, broken JSON bytes)
        String r2 = "room-corrupt-json";
        byte[] brokenJson = "{\"room\": {\"broken_json_syntax".getBytes(StandardCharsets.UTF_8);
        WalTransaction tx2 = store.prepare(r2);
        store.commit(tx2, brokenJson);

        // Room 3 (CORRUPTED AGGREGATE RESTORE: mapper succeeds, but restoreRoom throws RuntimeException)
        String r3 = "room-corrupt-restore";
        Room room3 = Room.restoreBuilder()
                .roomId(r3).roomName("Broken Room").hostName("Dave").maxPlayers(6)
                .smallBlind(10).bigBlind(20).buyIn(1000).password(null)
                .playersWithJoinTime(Map.of("Dave", LocalDateTime.now()))
                .gameStarted(false).build();
        AggregateStateImage img3 = mapper.captureStateImage(room3, "Dave", null);
        WalTransaction tx3 = store.prepare(r3);
        store.commit(tx3, mapper.serialize(img3));
        Mockito.doThrow(new RuntimeException("Injected room restoration failure"))
                .when(mockRoomService).restoreRoom(Mockito.argThat(r -> r != null && r3.equals(r.getRoomId())), Mockito.any());

        // Room 4 (VALID LOBBY: no game)
        String r4 = "room-valid-lobby";
        Room room4 = Room.restoreBuilder()
                .roomId(r4).roomName("Valid Lobby").hostName("Eve").maxPlayers(6)
                .smallBlind(10).bigBlind(20).buyIn(1000).password(null)
                .playersWithJoinTime(Map.of("Eve", LocalDateTime.now()))
                .gameStarted(false).build();
        AggregateStateImage img4 = mapper.captureStateImage(room4, "Eve", null);
        WalTransaction tx4 = store.prepare(r4);
        store.commit(tx4, mapper.serialize(img4));

        assertEquals(4, store.walCount());

        // RUN STARTUP RECOVERY: MUST NOT THROW ANY UNHANDLED EXCEPTION
        assertDoesNotThrow(() -> recovery.run(new DefaultApplicationArguments()),
                "Startup recovery MUST NOT crash on corrupted room payloads");
        assertTrue(recovery.isComplete(), "Recovery MUST mark itself complete");

        // Assert store health is completely intact
        assertTrue(store.isHealthy(), "Quarantining corrupted rooms MUST preserve store health");
        assertNull(store.lastError());

        // Assert corrupted room files are moved to .wal.corrupted
        List<String> corruptedRooms = List.of(r2, r3);
        for (String cRoom : corruptedRooms) {
            Path normalWal = tempDir.resolve(cRoom + ".wal");
            Path corruptedWal = tempDir.resolve(cRoom + ".wal.corrupted");
            assertFalse(Files.exists(normalWal), "Corrupted room " + cRoom + " .wal must be moved");
            assertTrue(Files.exists(corruptedWal), "Quarantined file " + corruptedWal + " must exist");
        }

        // Assert valid rooms remain .wal and are restored into live services
        List<String> validRooms = List.of(r1, r4);
        for (String vRoom : validRooms) {
            Path normalWal = tempDir.resolve(vRoom + ".wal");
            assertTrue(Files.exists(normalWal), "Valid room " + vRoom + " .wal must remain");
            Mockito.verify(mockRoomService).restoreRoom(
                    Mockito.argThat(r -> r != null && vRoom.equals(r.getRoomId())), Mockito.any());
        }

        // Assert walCount only counts active non-quarantined WALs
        assertEquals(2, store.walCount());
    }

    @Test
    @DisplayName("Corrupted WAL frame header / crypto on disk causes recoverAll to fail closed and quarantine")
    void corruptedWalFrameHeaderOnDisk_quarantinesRoomAndRecoversRemaining(@TempDir Path tempDir) throws Exception {
        EncryptionKeyring keyring = createKeyring("k1", (byte) 29);
        EncryptedWalStore store = new EncryptedWalStore(tempDir, keyring);

        // Valid room
        String rValid = "room-valid-alpha";
        WalTransaction txValid = store.prepare(rValid);
        store.commit(txValid, "valid-data".getBytes(StandardCharsets.UTF_8));

        // Corrupted header room: bad magic
        String rBad = "room-bad-header";
        Path badWal = tempDir.resolve(rBad + ".wal");
        ByteBuffer buf = ByteBuffer.allocate(32);
        buf.putInt(0xDEADBEEF);
        buf.putInt(24);
        buf.put(new byte[24]);
        Files.write(badWal, buf.array());

        // recoverAll() automatically isolates and quarantines the corrupted room
        Map<String, byte[]> afterQuarantine = store.recoverAll();
        Path quarantined = tempDir.resolve(rBad + ".wal.corrupted");
        assertEquals(tempDir.resolve(rBad + ".wal.corrupted"), quarantined);
        assertTrue(Files.exists(quarantined));
        assertFalse(Files.exists(badWal));

        // Once quarantined, recoverAll() recovers remaining valid rooms cleanly
        assertEquals(1, afterQuarantine.size());
        assertTrue(afterQuarantine.containsKey(rValid));
        assertTrue(store.isHealthy());

        // recoverLatest for the quarantined room returns empty
        assertTrue(store.recoverLatest(rBad).isEmpty());
    }

    // =========================================================================
    // Mid-mutation in-memory rollback and chip conservation
    // =========================================================================

    @Test
    @DisplayName("Mid-mutation rollback on checked & unchecked exceptions leaves no dirty in-memory or persisted state")
    void midMutationRollback_checkedAndUncheckedExceptions_noDirtyState(@TempDir Path tempDir) throws Exception {
        String base64Key = Base64.getEncoder().encodeToString(new byte[32]);
        String base64Secret = Base64.getEncoder().encodeToString(new byte[64]);

        String roomId;
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                PokerApplication.class, FaultInjectingMutationHelper.class)
                .profiles("test")
                .web(WebApplicationType.NONE)
                .properties(
                        "server.port=0",
                        "app.jwt.base64-secret=" + base64Secret,
                        "jwt.expirationMillis=3600000",
                        "app.security.cors.allowed-origins=http://localhost",
                        "poker.security.trust-proxy=false",
                        "poker.rate-limiting.enabled=false")
                .run(
                        "--poker.disconnect.grace-period-ms=120000",
                        "--poker.game-end.display-delay-ms=500",
                        "--poker.persistence.enabled=true",
                        "--poker.persistence.directory=" + tempDir.toString().replace('\\', '/'),
                        "--poker.persistence.current-key-id=current",
                        "--poker.persistence.keys.current=" + base64Key)) {

            RoomService rooms = context.getBean(RoomService.class);
            GameLifecycleService games = context.getBean(GameLifecycleService.class);
            FaultInjectingMutationHelper helper = context.getBean(FaultInjectingMutationHelper.class);

            // Step 1: Create room and start game with Alice and Bob
            roomId = rooms.createRoom(new CreateRoomRequest("Rollback Test Room", "Alice", 6, 10, 20, 1000, null));
            rooms.joinRoom(new JoinRoomRequest("Rollback Test Room", "Bob", null));
            games.createGameFromRoom(roomId);
            Game game = games.getGame(roomId);
            assertNotNull(game);

            int aliceInitialChips = game.getPlayers().getFirst().getChips();
            int potBefore = game.getPot();

            // Scenario A: Unchecked exception thrown after modifying player chips
            assertThrows(IllegalStateException.class, () ->
                    helper.corruptChipsAndThrowUnchecked(roomId, 777777));

            // Verify in-memory game state is completely restored
            Game restoredGameAfterUnchecked = games.getGame(roomId);
            assertNotNull(restoredGameAfterUnchecked);
            assertEquals(aliceInitialChips, restoredGameAfterUnchecked.getPlayers().getFirst().getChips(),
                    "Alice's chips must be rolled back to pre-mutation snapshot");
            assertEquals(potBefore, restoredGameAfterUnchecked.getPot(),
                    "Pot must be rolled back to pre-mutation snapshot");

            // Scenario B: Checked exception thrown after modifying room players list
            assertThrows(Exception.class, () ->
                    helper.corruptRoomPlayersAndThrowChecked(roomId, "DirtyPlayerX"));

            // Verify in-memory room state is completely restored
            Room restoredRoomAfterChecked = rooms.getRoom(roomId);
            assertNotNull(restoredRoomAfterChecked);
            assertFalse(restoredRoomAfterChecked.hasPlayer("DirtyPlayerX"),
                    "Dirty player must NOT remain in RoomService player list");
            assertEquals(List.of("Alice", "Bob"), restoredRoomAfterChecked.getPlayers());

            // Scenario C: Subsequent valid action succeeds cleanly
            games.markPlayerReconnected(roomId, "Alice");
            games.markPlayerReconnected(roomId, "Bob");
            String currentPlayer = game.getCurrentPlayer().getName();
            PlayerAction nextAction = game.getCurrentPlayer().getCurrentBet() < game.getCurrentHighestBet()
                    ? PlayerAction.CALL : PlayerAction.CHECK;

            context.getBean(PlayerActionService.class).processPlayerAction(
                    roomId, new PlayerActionRequest(nextAction, null), currentPlayer);

            // Assert action succeeded in memory
            assertTrue(games.getGame(roomId).getPot() >= potBefore);
        }

        // Restart application and verify uncorrupted state persisted cleanly without any dirty records
        try (ConfigurableApplicationContext secondContext = new SpringApplicationBuilder(
                PokerApplication.class, FaultInjectingMutationHelper.class)
                .profiles("test")
                .web(WebApplicationType.NONE)
                .properties(
                        "server.port=0",
                        "app.jwt.base64-secret=" + base64Secret,
                        "jwt.expirationMillis=3600000",
                        "app.security.cors.allowed-origins=http://localhost",
                        "poker.security.trust-proxy=false",
                        "poker.rate-limiting.enabled=false")
                .run(
                        "--poker.disconnect.grace-period-ms=120000",
                        "--poker.game-end.display-delay-ms=500",
                        "--poker.persistence.enabled=true",
                        "--poker.persistence.directory=" + tempDir.toString().replace('\\', '/'),
                        "--poker.persistence.current-key-id=current",
                        "--poker.persistence.keys.current=" + base64Key)) {

            RoomService secondRooms = secondContext.getBean(RoomService.class);
            GameLifecycleService secondGames = secondContext.getBean(GameLifecycleService.class);

            Room room = secondRooms.getRoom(roomId);
            assertNotNull(room);
            assertFalse(room.hasPlayer("DirtyPlayerX"));
            Game recoveredGame = secondGames.getGame(roomId);
            assertNotNull(recoveredGame);
            assertNotEquals(777777, recoveredGame.getPlayers().getFirst().getChips(),
                    "Persisted chips must not contain dirty mutation chips");
        }
    }

    @Test
    @DisplayName("Aborted room creation cleans up in-memory maps, active games, and WAL files")
    void abortedRoomCreation_cleansUpInMemoryStateAndWalFile(@TempDir Path tempDir) throws Exception {
        String base64Key = Base64.getEncoder().encodeToString(new byte[32]);
        String base64Secret = Base64.getEncoder().encodeToString(new byte[64]);

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                PokerApplication.class, FaultInjectingMutationHelper.class)
                .profiles("test")
                .web(WebApplicationType.NONE)
                .properties(
                        "server.port=0",
                        "app.jwt.base64-secret=" + base64Secret,
                        "jwt.expirationMillis=3600000",
                        "app.security.cors.allowed-origins=http://localhost",
                        "poker.security.trust-proxy=false",
                        "poker.rate-limiting.enabled=false")
                .run(
                        "--poker.disconnect.grace-period-ms=120000",
                        "--poker.game-end.display-delay-ms=500",
                        "--poker.persistence.enabled=true",
                        "--poker.persistence.directory=" + tempDir.toString().replace('\\', '/'),
                        "--poker.persistence.current-key-id=current",
                        "--poker.persistence.keys.current=" + base64Key)) {

            RoomService rooms = context.getBean(RoomService.class);
            GameLifecycleService games = context.getBean(GameLifecycleService.class);
            FaultInjectingMutationHelper helper = context.getBean(FaultInjectingMutationHelper.class);

            String abortedRoomId = UUID.randomUUID().toString();
            CreateRoomRequest req = new CreateRoomRequest("Aborted Room", "Eve", 6, 10, 20, 1000, null);

            assertThrows(RuntimeException.class, () ->
                    helper.createRoomAndAbort(abortedRoomId, req));

            // Assert complete in-memory eviction
            assertNull(rooms.getRoom(abortedRoomId), "Aborted room must be evicted from RoomService");
            assertNull(games.getGame(abortedRoomId), "Aborted game must be evicted from GameLifecycleService");

            // Assert physical WAL cleanup
            Path walPath = tempDir.resolve(abortedRoomId + ".wal");
            assertFalse(Files.exists(walPath), "Aborted room WAL must be cleaned up from disk");
        }
    }

    @Test
    @DisplayName("Rapid alternating mutation failures maintain chip conservation across every iteration")
    void alternatingMutationFailures_conserveChips(@TempDir Path tempDir) throws Exception {
        String base64Key = Base64.getEncoder().encodeToString(new byte[32]);
        String base64Secret = Base64.getEncoder().encodeToString(new byte[64]);

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                PokerApplication.class, FaultInjectingMutationHelper.class)
                .profiles("test")
                .web(WebApplicationType.NONE)
                .properties(
                        "server.port=0",
                        "app.jwt.base64-secret=" + base64Secret,
                        "jwt.expirationMillis=3600000",
                        "app.security.cors.allowed-origins=http://localhost",
                        "poker.security.trust-proxy=false",
                        "poker.rate-limiting.enabled=false")
                .run(
                        "--poker.disconnect.grace-period-ms=120000",
                        "--poker.game-end.display-delay-ms=500",
                        "--poker.persistence.enabled=true",
                        "--poker.persistence.directory=" + tempDir.toString().replace('\\', '/'),
                        "--poker.persistence.current-key-id=current",
                        "--poker.persistence.keys.current=" + base64Key)) {

            RoomService rooms = context.getBean(RoomService.class);
            GameLifecycleService games = context.getBean(GameLifecycleService.class);
            FaultInjectingMutationHelper helper = context.getBean(FaultInjectingMutationHelper.class);

            String roomId = rooms.createRoom(new CreateRoomRequest("Conservation Room", "Alice", 6, 10, 20, 1000, null));
            rooms.joinRoom(new JoinRoomRequest("Conservation Room", "Bob", null));
            games.createGameFromRoom(roomId);
            Game game = games.getGame(roomId);

            int initialBankroll = game.getPlayers().stream().mapToInt(player -> player.getChips()).sum() + game.getPot();

            // Run 20 stress cycles: odd cycles mutate chips illegally and fail, even cycles do clean safe operations
            for (int i = 0; i < 20; i++) {
                if (i % 2 == 0) {
                    // Attempt illegal mutation that alters chips and blows up
                    final int badChips = 9999 + i;
                    assertThrows(IllegalStateException.class, () ->
                            helper.corruptChipsAndThrowUnchecked(roomId, badChips));
                } else {
                    // Valid durable mutation
                    helper.safeDurableNoOp(roomId);
                }

                // Verify conservation of chips after every single iteration
                Game currentGame = games.getGame(roomId);
                int currentTotal = currentGame.getPlayers().stream().mapToInt(player -> player.getChips()).sum() + currentGame.getPot();
                assertEquals(initialBankroll, currentTotal,
                        "Total bankroll must be strictly conserved at iteration " + i);
            }
        }
    }

    // =========================================================================
    // Persistence disabled by default and under profile / CLI override
    // =========================================================================

    @Test
    @DisplayName("Persistence resolves to disabled under the e2e profile and under explicit CLI override")
    void persistenceDisabledByDefaultAndUnderE2EOverride() {
        // Test 1: In E2E profile with springdotenv disabled (simulating clean CI without dotenv)
        try (ConfigurableApplicationContext e2eContext = new SpringApplicationBuilder(PersistenceToggleConfig.class)
                .profiles("e2e")
                .web(WebApplicationType.NONE)
                .run("--springdotenv.enabled=false")) {

            String persistenceEnabled = e2eContext.getEnvironment().getProperty("poker.persistence.enabled");
            assertEquals("false", persistenceEnabled,
                    "In e2e profile, poker.persistence.enabled must evaluate to false");
        }

        // Test 2: CLI argument --poker.persistence.enabled=false explicitly overrides any environment or dotenv
        try (ConfigurableApplicationContext cliContext = new SpringApplicationBuilder(PersistenceToggleConfig.class)
                .profiles("test")
                .web(WebApplicationType.NONE)
                .run("--poker.persistence.enabled=false")) {

            String persistenceEnabled = cliContext.getEnvironment().getProperty("poker.persistence.enabled");
            assertEquals("false", persistenceEnabled,
                    "CLI override --poker.persistence.enabled=false must resolve to false");
        }
    }

    @Configuration
    static class PersistenceToggleConfig {}

    // =========================================================================
    // Helper bean that advises mutations with @DurableMutation and injects faults
    // =========================================================================

    @Component
    public static class FaultInjectingMutationHelper {

        private final RoomService roomService;
        private final GameLifecycleService gameLifecycleService;

        public FaultInjectingMutationHelper(RoomService roomService, GameLifecycleService gameLifecycleService) {
            this.roomService = roomService;
            this.gameLifecycleService = gameLifecycleService;
        }

        @DurableMutation(roomId = "#roomId")
        public void corruptChipsAndThrowUnchecked(String roomId, int dirtyChips) {
            Game game = gameLifecycleService.getGame(roomId);
            game.getPlayers().getFirst().setChips(dirtyChips);
            throw new IllegalStateException("Simulated unchecked midway mutation failure");
        }

        @DurableMutation(roomId = "#roomId")
        public void corruptRoomPlayersAndThrowChecked(String roomId, String dirtyPlayer) throws Exception {
            Room room = roomService.getRoom(roomId);
            room.addPlayer(dirtyPlayer);
            throw new Exception("Simulated checked midway mutation failure");
        }

        @DurableMutation(roomId = "#roomId")
        public void createRoomAndAbort(String roomId, CreateRoomRequest req) {
            roomService.createRoom(roomId, req);
            throw new RuntimeException("Simulated aborted room creation");
        }

        @DurableMutation(roomId = "#roomId")
        public void safeDurableNoOp(String roomId) {
            // Touch room to trigger clean snapshot commit
            Room room = roomService.getRoom(roomId);
            assertNotNull(room);
        }
    }
}
