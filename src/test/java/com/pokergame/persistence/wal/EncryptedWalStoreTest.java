package com.pokergame.persistence.wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import com.pokergame.persistence.config.PersistenceException;
import java.util.Arrays;

/** Tests encrypted WAL store behavior. */
class EncryptedWalStoreTest {

    @TempDir
    Path directory;

    /**
     * Protects the contract that committed payload recovers without appearing as plaintext.
     */
    @Test
    void committedPayloadRecoversWithoutAppearingAsPlaintext() throws Exception {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring);
        byte[] snapshot = "password=very-secret;holeCards=AS,KH".getBytes(StandardCharsets.UTF_8);

        WalTransaction transaction = store.prepare("room-1");
        store.commit(transaction, snapshot);

        assertArrayEquals(snapshot, store.recoverLatest("room-1").orElseThrow());
        String walBytes = Files.readString(directory.resolve("room-1.wal"), StandardCharsets.ISO_8859_1);
        assertFalse(walBytes.contains("very-secret"));
        assertFalse(walBytes.contains("holeCards"));
    }

    /**
     * Protects the contract that uncommitted prepare does not replace last committed state.
     */
    @Test
    void uncommittedPrepareDoesNotReplaceLastCommittedState() {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring);

        WalTransaction committed = store.prepare("room-2");
        store.commit(committed, "first".getBytes(StandardCharsets.UTF_8));
        store.prepare("room-2");

        assertEquals("first", new String(store.recoverLatest("room-2").orElseThrow(), StandardCharsets.UTF_8));
    }

    /**
     * Protects the contract that torn final frame is ignored but committed prefix tampering fails closed.
     */
    @Test
    void tornFinalFrameIsIgnoredButCommittedPrefixTamperingFailsClosed() throws Exception {
        EncryptionKeyring keyring = keyring("current", (byte) 1);
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring);
        WalTransaction transaction = store.prepare("room-3");
        store.commit(transaction, "safe".getBytes(StandardCharsets.UTF_8));
        Path wal = directory.resolve("room-3.wal");
        byte[] committed = Files.readAllBytes(wal);
        store.prepare("room-3");
        byte[] withFinalPrepare = Files.readAllBytes(wal);
        Files.write(wal, Arrays.copyOf(withFinalPrepare, withFinalPrepare.length - 5));
        assertEquals("safe", new String(store.recoverLatest("room-3").orElseThrow(), StandardCharsets.UTF_8));

        Files.write(wal, committed);
        byte[] corrupt = committed.clone();
        corrupt[corrupt.length / 2] ^= 1;
        Files.write(wal, corrupt);
        EncryptedWalStore recovering = new EncryptedWalStore(directory, keyring);
        assertThrows(PersistenceException.class, () -> recovering.recoverLatest("room-3"));
        assertFalse(recovering.isHealthy());
    }

    /**
     * Protects the contract that when a WAL file ends with 1 to 7 bytes of a partial header,
     * readWal / recoverLatest truncates the partial header to the valid offset and recovers the last committed state,
     * subsequent prepare() and commit() writes succeed, and a fresh store recovers the new commit.
     */
    @Test
    void partialFrameHeaderIsTruncatedSoSubsequentWritesDoNotCorruptWal() throws Exception {
        for (int headerBytes = 1; headerBytes < 8; headerBytes++) {
            String roomId = "room-partial-hdr-" + headerBytes;
            EncryptionKeyring keys = keyring("current", (byte) (10 + headerBytes));
            EncryptedWalStore store = new EncryptedWalStore(directory, keys);
            WalTransaction tx1 = store.prepare(roomId);
            byte[] payload1 = ("payload-init-" + headerBytes).getBytes(StandardCharsets.UTF_8);
            store.commit(tx1, payload1);

            Path walPath = directory.resolve(roomId + ".wal");
            long validLength = Files.size(walPath);

            // Append headerBytes (1 to 7 bytes) simulating crash during header write
            byte[] partialHeader = new byte[headerBytes];
            for (int i = 0; i < headerBytes; i++) {
                partialHeader[i] = (byte) (0x50 + i);
            }
            Files.write(walPath, partialHeader, StandardOpenOption.APPEND);
            assertEquals(validLength + headerBytes, Files.size(walPath));

            // recoverLatest discovers partial header and truncates file back to validLength
            EncryptedWalStore recoveringStore = new EncryptedWalStore(directory, keys);
            Optional<byte[]> recovered = recoveringStore.recoverLatest(roomId);
            assertTrue(recovered.isPresent());
            assertArrayEquals(payload1, recovered.get());
            assertEquals(validLength, Files.size(walPath), "Partial header must be truncated on disk to validLength");

            // Subsequent prepare() and commit() writes succeed
            EncryptedWalStore writerStore = new EncryptedWalStore(directory, keys);
            WalTransaction tx2 = writerStore.prepare(roomId);
            byte[] payload2 = ("payload-subsequent-" + headerBytes).getBytes(StandardCharsets.UTF_8);
            writerStore.commit(tx2, payload2);

            // Fresh store recovers new commit with zero corruption or exceptions
            EncryptedWalStore freshStore = new EncryptedWalStore(directory, keys);
            Optional<byte[]> latest = freshStore.recoverLatest(roomId);
            assertTrue(latest.isPresent());
            assertArrayEquals(payload2, latest.get());
        }
    }

    /**
     * Protects the contract that copied record and missing key both fail closed.
     */
    @Test
    void copiedRecordAndMissingKeyBothFailClosed() throws Exception {
        EncryptedWalStore oldStore = new EncryptedWalStore(directory, keyring("old", (byte) 2));
        WalTransaction transaction = oldStore.prepare("room-a");
        oldStore.commit(transaction, "state".getBytes(StandardCharsets.UTF_8));
        Files.copy(directory.resolve("room-a.wal"), directory.resolve("room-b.wal"),
                StandardCopyOption.REPLACE_EXISTING);

        assertThrows(PersistenceException.class, () -> oldStore.recoverLatest("room-b"));
        EncryptedWalStore missingKey = new EncryptedWalStore(directory, keyring("new", (byte) 3));
        assertThrows(PersistenceException.class, () -> missingKey.recoverLatest("room-a"));
    }

    /**
     * A missing historical key is an operator configuration error: preserve the WAL,
     * fail startup, and allow recovery once the operator restores the keyring.
     */
    @Test
    void recoverAllFailsClosedWithoutQuarantiningWalWhenHistoricalKeyIsMissing() throws Exception {
        EncryptedWalStore writer = new EncryptedWalStore(directory, keyring("old", (byte) 2));
        writer.commit(writer.prepare("room-old-key"), "accepted-state".getBytes(StandardCharsets.UTF_8));
        Path wal = directory.resolve("room-old-key.wal");
        byte[] original = Files.readAllBytes(wal);

        EncryptedWalStore missingKey = new EncryptedWalStore(directory, keyring("current", (byte) 3));
        assertThrows(PersistenceException.class, missingKey::recoverAll);
        assertFalse(missingKey.isHealthy());
        assertArrayEquals(original, Files.readAllBytes(wal));
        assertFalse(Files.exists(directory.resolve("room-old-key.wal.corrupted")));

        EncryptedWalStore corrected = new EncryptedWalStore(directory, keyring("old", (byte) 2));
        assertArrayEquals("accepted-state".getBytes(StandardCharsets.UTF_8),
                corrected.recoverAll().get("room-old-key"));
    }

    /**
     * Protects the contract that rotation compacts under current key so old key can be retired.
     */
    @Test
    void rotationCompactsUnderCurrentKeySoOldKeyCanBeRetired() {
        EncryptionKeyring oldKeys = keyring("old", (byte) 4);
        EncryptedWalStore oldStore = new EncryptedWalStore(directory, oldKeys);
        WalTransaction transaction = oldStore.prepare("room-rotate");
        oldStore.commit(transaction, "rotated-state".getBytes(StandardCharsets.UTF_8));

        byte[] old = filledKey((byte) 4);
        byte[] current = filledKey((byte) 5);
        EncryptionKeyring rotating = new EncryptionKeyring("current", Map.of(
                "old", new SecretKeySpec(old, "AES"),
                "current", new SecretKeySpec(current, "AES")));
        EncryptedWalStore rotatingStore = new EncryptedWalStore(directory, rotating);
        byte[] latest = rotatingStore.recoverLatest("room-rotate").orElseThrow();
        rotatingStore.compact("room-rotate", latest);

        EncryptedWalStore currentOnly = new EncryptedWalStore(directory, keyring("current", (byte) 5));
        assertEquals("rotated-state",
                new String(currentOnly.recoverLatest("room-rotate").orElseThrow(), StandardCharsets.UTF_8));
        currentOnly.delete("room-rotate");
        assertTrue(currentOnly.recoverLatest("room-rotate").isEmpty());
    }

    /**
     * Protects the contract that commit failure marks store unhealthy and rejects later mutations.
     */
    @Test
    void commitFailureMarksStoreUnhealthyAndRejectsLaterMutations() {
        AtomicReference<WalFaultPoint> failure = new AtomicReference<>();
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring("current", (byte) 6),
                (point, roomId) -> {
                    if (point == failure.get()) throw new PersistenceException("injected " + point);
                });
        WalTransaction transaction = store.prepare("room-fail");
        failure.set(WalFaultPoint.BEFORE_COMMIT_WRITE);

        assertThrows(PersistenceException.class,
                () -> store.commit(transaction, "lost".getBytes(StandardCharsets.UTF_8)));
        assertFalse(store.isHealthy());
        failure.set(null);
        assertThrows(PersistenceException.class, () -> store.prepare("other-room"));
    }

    /**
     * Protects the contract that compaction and delete failures mark their stores unhealthy.
     */
    @Test
    void compactionAndDeleteFailuresMarkTheirStoresUnhealthy() {
        AtomicReference<WalFaultPoint> compactionFailure = new AtomicReference<>();
        EncryptedWalStore compactingStore = new EncryptedWalStore(directory.resolve("compact"),
                keyring("current", (byte) 8),
                (point, roomId) -> {
                    if (point == compactionFailure.get()) throw new PersistenceException("injected " + point);
                });
        WalTransaction compactingTransaction = compactingStore.prepare("room-compact-fail");
        byte[] snapshot = "durable".getBytes(StandardCharsets.UTF_8);
        compactingStore.commit(compactingTransaction, snapshot);
        compactionFailure.set(WalFaultPoint.BEFORE_COMPACTION_REPLACE);

        assertThrows(PersistenceException.class,
                () -> compactingStore.compact("room-compact-fail", snapshot));
        assertFalse(compactingStore.isHealthy());

        AtomicReference<WalFaultPoint> deleteFailure = new AtomicReference<>();
        EncryptedWalStore deletingStore = new EncryptedWalStore(directory.resolve("delete"),
                keyring("current", (byte) 9),
                (point, roomId) -> {
                    if (point == deleteFailure.get()) throw new PersistenceException("injected " + point);
                });
        WalTransaction deletingTransaction = deletingStore.prepare("room-delete-fail");
        deletingStore.commit(deletingTransaction, snapshot);
        deleteFailure.set(WalFaultPoint.BEFORE_DELETE);

        assertThrows(PersistenceException.class, () -> deletingStore.delete("room-delete-fail"));
        assertFalse(deletingStore.isHealthy());
    }

    /** A repair replaces an interrupted tail so the next restart sees all subsequent saves. */
    @Test
    void recoveredTornTailRequiresCheckpointBeforeFurtherAppends() throws Exception {
        EncryptionKeyring keys = keyring("current", (byte) 11);
        EncryptedWalStore first = new EncryptedWalStore(directory, keys);
        first.commit(first.prepare("room-torn"), new byte[]{1});
        first.prepare("room-torn");
        Path wal = directory.resolve("room-torn.wal");
        byte[] bytes = Files.readAllBytes(wal);
        Files.write(wal, Arrays.copyOf(bytes, bytes.length - 5));
        EncryptedWalStore second = new EncryptedWalStore(directory, keys);
        assertArrayEquals(new byte[]{1}, second.recoverLatest("room-torn").orElseThrow());
        assertTrue(second.requiresCheckpoint("room-torn"));
        assertThrows(PersistenceException.class, () -> second.prepare("room-torn"));

        second.checkpoint("room-torn", new byte[]{2});
        second.commit(second.prepare("room-torn"), new byte[]{3});
        EncryptedWalStore third = new EncryptedWalStore(directory, keys);
        assertArrayEquals(new byte[]{3}, third.recoverLatest("room-torn").orElseThrow());
        assertFalse(third.requiresCheckpoint("room-torn"));
    }

    /** One room's repair cannot declare other unsaved rooms healthy. */
    @Test
    void checkpointOnlyClearsItsOwnRoomFailure() {
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring("current", (byte) 12));
        store.recordWriteFailure("room-A", new PersistenceException("unavailable"));
        store.recordWriteFailure("room-B", new PersistenceException("unavailable"));
        store.checkpoint("room-A", new byte[]{1});
        assertFalse(store.isHealthy());
        assertEquals(1, store.unsavedRoomCount());
        assertFalse(store.requiresCheckpoint("room-A"));
        assertTrue(store.requiresCheckpoint("room-B"));
        store.checkpoint("room-B", new byte[]{2});
        assertTrue(store.isHealthy());
        assertNull(store.lastError());
    }

    /**
     * Protects the contract that crash recovery truncates torn trailing frames to valid offset,
     * preventing subsequent appends from splicing onto corrupt bytes.
     */
    @Test
    void tornTrailingFrameIsTruncatedSoSubsequentWritesDoNotCorruptWal() throws Exception {
        EncryptionKeyring keys = keyring("current", (byte) 13);
        EncryptedWalStore store = new EncryptedWalStore(directory, keys);
        WalTransaction tx1 = store.prepare("room-b10-2");
        byte[] payload1 = "initial-payload".getBytes(StandardCharsets.UTF_8);
        store.commit(tx1, payload1);

        Path walPath = directory.resolve("room-b10-2.wal");
        long validLength = Files.size(walPath);

        // Append torn trailing frame: valid header (MAGIC + frameLength) followed by partial body bytes
        ByteBuffer tornFrame = ByteBuffer.allocate(24);
        tornFrame.putInt(0x504B574C); // MAGIC
        tornFrame.putInt(64);         // frameLength (64 bytes expected)
        tornFrame.put(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}); // only 16 body bytes
        Files.write(walPath, tornFrame.array(), StandardOpenOption.APPEND);
        assertEquals(validLength + 24, Files.size(walPath));

        // Call recoverLatest() which discovers the torn trailing frame and truncates the file on disk
        EncryptedWalStore recoveringStore = new EncryptedWalStore(directory, keys);
        Optional<byte[]> recovered = recoveringStore.recoverLatest("room-b10-2");
        assertTrue(recovered.isPresent());
        assertArrayEquals(payload1, recovered.get());
        assertEquals(validLength, Files.size(walPath), "Torn trailing frame must be truncated on disk to validLength");

        // Perform a subsequent prepare() and commit() on a writer store
        EncryptedWalStore writerStore = new EncryptedWalStore(directory, keys);
        WalTransaction tx2 = writerStore.prepare("room-b10-2");
        byte[] payload2 = "subsequent-payload".getBytes(StandardCharsets.UTF_8);
        writerStore.commit(tx2, payload2);

        // In a fresh EncryptedWalStore instance, call recoverLatest() and assert it cleanly decodes the new payload
        EncryptedWalStore freshStore = new EncryptedWalStore(directory, keys);
        Optional<byte[]> latest = freshStore.recoverLatest("room-b10-2");
        assertTrue(latest.isPresent());
        assertArrayEquals(payload2, latest.get());
    }

    /**
     * Protects the contract that orphaned .compacting files left from prior crashes
     * are cleaned up when opening and recovering WAL files.
     */
    @Test
    void orphanedCompactingFilesAreCleanedUpOnStoreInitializationAndRecovery() throws Exception {
        EncryptionKeyring keys = keyring("current", (byte) 30);
        EncryptedWalStore store = new EncryptedWalStore(directory, keys);
        WalTransaction tx = store.prepare("room-orphan");
        byte[] payload = "orphan-payload".getBytes(StandardCharsets.UTF_8);
        store.commit(tx, payload);

        Path orphanedCompacting = directory.resolve("room-orphan.wal.compacting");
        Path otherCompacting = directory.resolve("other.wal.compacting");
        Files.write(orphanedCompacting, new byte[]{1, 2, 3, 4, 5});
        Files.write(otherCompacting, new byte[]{6, 7, 8, 9, 10});

        assertTrue(Files.exists(orphanedCompacting));
        assertTrue(Files.exists(otherCompacting));

        // Initializing a new store cleans up orphaned .compacting files
        EncryptedWalStore newStore = new EncryptedWalStore(directory, keys);
        assertFalse(Files.exists(orphanedCompacting));
        assertFalse(Files.exists(otherCompacting));

        // Compacting files created before recoverAll are cleaned up
        Files.write(orphanedCompacting, new byte[]{1, 2, 3});
        Map<String, byte[]> all = newStore.recoverAll();
        assertTrue(all.containsKey("room-orphan"));
        assertArrayEquals(payload, all.get("room-orphan"));
        assertFalse(Files.exists(orphanedCompacting));

        // Compacting files created before recoverLatest are cleaned up
        Files.write(orphanedCompacting, new byte[]{4, 5, 6});
        Optional<byte[]> latest = newStore.recoverLatest("room-orphan");
        assertTrue(latest.isPresent());
        assertArrayEquals(payload, latest.get());
        assertFalse(Files.exists(orphanedCompacting));
    }

    /**
     * Protects the contract that quarantining a corrupted WAL file moves the file to .wal.corrupted,
     * clears room tracking, ensures recoverLatest() returns Optional.empty(), and restores store health
     * so that other rooms can continue committing transactions without interruption.
     */
    @Test
    void quarantineCorruptedWalMovesFileAndPreservesStoreHealthForOtherRooms() throws Exception {
        EncryptionKeyring keys = keyring("current", (byte) 42);
        EncryptedWalStore store = new EncryptedWalStore(directory, keys);

        // 1. Setup healthy room and corrupted room
        WalTransaction txHealthy = store.prepare("room-healthy");
        byte[] healthyPayload1 = "healthy-1".getBytes(StandardCharsets.UTF_8);
        store.commit(txHealthy, healthyPayload1);

        WalTransaction txCorrupted = store.prepare("room-corrupt");
        byte[] corruptPayload = "corrupt-payload".getBytes(StandardCharsets.UTF_8);
        store.commit(txCorrupted, corruptPayload);

        // 2. Corrupt the WAL bytes of room-corrupt
        Path corruptWalPath = directory.resolve("room-corrupt.wal");
        byte[] walBytes = Files.readAllBytes(corruptWalPath);
        walBytes[walBytes.length / 2] ^= 0x5A; // tamper with ciphertext
        Files.write(corruptWalPath, walBytes);

        // 3. Attempting to recover room-corrupt fails with PersistenceException
        assertThrows(PersistenceException.class, () -> store.recoverLatest("room-corrupt"));
        assertFalse(store.isHealthy(), "Store should be degraded while room-corrupt is in failedRooms");

        // Attempting to append to healthy room fails while persistence is degraded
        assertThrows(PersistenceException.class, () -> store.prepare("room-healthy"));

        // 4. Quarantine the corrupted room
        Path expectedQuarantinePath = directory.resolve("room-corrupt.wal.corrupted");
        assertFalse(Files.exists(expectedQuarantinePath));

        Path actualQuarantinePath = store.quarantine("room-corrupt", "Ciphertext tamper detected during recovery");
        assertEquals(expectedQuarantinePath, actualQuarantinePath);
        assertTrue(Files.exists(expectedQuarantinePath), "Quarantined file must exist on disk with .wal.corrupted suffix");
        assertFalse(Files.exists(corruptWalPath), "Original .wal file must no longer exist at active WAL path");

        // 5. Verify store health is restored and internal tracking cleaned up
        assertTrue(store.isHealthy(), "Quarantining corrupted room must restore store health for remaining rooms");
        assertFalse(store.requiresCheckpoint("room-corrupt"), "Quarantined room must not require checkpoint");
        assertNull(store.lastError(), "Last error must be cleared when no other rooms are failed");

        // 6. recoverLatest for quarantined room returns Optional.empty()
        Optional<byte[]> recoveredCorrupt = store.recoverLatest("room-corrupt");
        assertTrue(recoveredCorrupt.isEmpty(), "recoverLatest on quarantined room must return Optional.empty()");

        // 7. Other rooms can continue committing transactions
        WalTransaction txHealthy2 = store.prepare("room-healthy");
        byte[] healthyPayload2 = "healthy-2".getBytes(StandardCharsets.UTF_8);
        store.commit(txHealthy2, healthyPayload2);

        Optional<byte[]> recoveredHealthy = store.recoverLatest("room-healthy");
        assertTrue(recoveredHealthy.isPresent());
        assertArrayEquals(healthyPayload2, recoveredHealthy.get());
    }

    /**
     * Protects the contract that quarantining when the WAL file does not exist on disk
     * cleans up internal tracking maps without throwing or creating ghost files.
     */
    @Test
    void quarantineWhenWalDoesNotExistCleansUpRoomTrackingSafely() {
        EncryptionKeyring keys = keyring("current", (byte) 43);
        EncryptedWalStore store = new EncryptedWalStore(directory, keys);

        // Record a failure for a room that has no file on disk
        store.recordWriteFailure("room-missing", new PersistenceException("Simulated write glitch"));
        assertFalse(store.isHealthy());
        assertTrue(store.requiresCheckpoint("room-missing"));

        Path quarantinePath = store.quarantine("room-missing", "Clean up non-existent WAL error");
        assertEquals(directory.resolve("room-missing.wal.corrupted"), quarantinePath);
        assertFalse(Files.exists(quarantinePath), "No corrupted file should be created if original did not exist");
        assertTrue(store.isHealthy(), "Store should be healthy after quarantine cleans up tracking");
        assertFalse(store.requiresCheckpoint("room-missing"));
        assertTrue(store.recoverLatest("room-missing").isEmpty());
    }

    /**
     * Protects the contract that quarantine validates room IDs and rejects path traversal.
     */
    @Test
    void quarantineRejectsInvalidRoomIdWithPersistenceException() {
        EncryptionKeyring keys = keyring("current", (byte) 44);
        EncryptedWalStore store = new EncryptedWalStore(directory, keys);

        assertThrows(PersistenceException.class, () -> store.quarantine("../../evil-path", "traversal"));
        assertThrows(PersistenceException.class, () -> store.quarantine(null, "null"));
        assertThrows(PersistenceException.class, () -> store.quarantine("bad room spaces", "spaces"));
    }

    /**
     * Protects the contract that quarantined files are excluded from active WAL count and recoverAll().
     */
    @Test
    void quarantineExcludesQuarantinedFileFromWalCountAndRecoverAll() throws Exception {
        EncryptionKeyring keys = keyring("current", (byte) 45);
        EncryptedWalStore store = new EncryptedWalStore(directory, keys);

        WalTransaction tx1 = store.prepare("room-active");
        byte[] payloadActive = "active-data".getBytes(StandardCharsets.UTF_8);
        store.commit(tx1, payloadActive);

        WalTransaction tx2 = store.prepare("room-to-quarantine");
        byte[] payloadQuarantine = "quarantine-data".getBytes(StandardCharsets.UTF_8);
        store.commit(tx2, payloadQuarantine);

        assertEquals(2, store.walCount());

        // Quarantine room-to-quarantine using alias method
        Path quarantined = store.quarantineCorruptedWal("room-to-quarantine", "Administrative isolation");
        assertTrue(Files.exists(quarantined));

        // walCount only counts active .wal files
        assertEquals(1, store.walCount());

        // recoverAll only loads active .wal files
        Map<String, byte[]> all = store.recoverAll();
        assertEquals(1, all.size());
        assertTrue(all.containsKey("room-active"));
        assertArrayEquals(payloadActive, all.get("room-active"));
        assertFalse(all.containsKey("room-to-quarantine"));
    }

    /**
     * Protects the contract that recoverAll() quarantines corrupted WAL files on disk
     * and returns valid rooms without throwing.
     */
    @Test
    void recoverAllQuarantinesCorruptedWalFilesAndRecoversRemainingValidRooms() throws Exception {
        EncryptionKeyring keys = keyring("current", (byte) 46);
        EncryptedWalStore store = new EncryptedWalStore(directory, keys);

        // 1. Prepare and commit a valid room
        WalTransaction txValid = store.prepare("room-valid");
        byte[] validPayload = "valid-room-data".getBytes(StandardCharsets.UTF_8);
        store.commit(txValid, validPayload);

        // 2. Prepare and commit a room, then corrupt its WAL file on disk
        WalTransaction txCorrupt = store.prepare("room-corrupt");
        byte[] corruptPayload = "corrupt-room-data".getBytes(StandardCharsets.UTF_8);
        store.commit(txCorrupt, corruptPayload);

        Path corruptWalPath = directory.resolve("room-corrupt.wal");
        byte[] walBytes = Files.readAllBytes(corruptWalPath);
        walBytes[walBytes.length / 2] ^= 0x5A; // tamper ciphertext
        Files.write(corruptWalPath, walBytes);

        // 3. recoverAll() should not throw, should quarantine room-corrupt, and should recover room-valid
        Map<String, byte[]> recovered = assertDoesNotThrow(store::recoverAll);

        assertEquals(1, recovered.size());
        assertTrue(recovered.containsKey("room-valid"));
        assertArrayEquals(validPayload, recovered.get("room-valid"));
        assertFalse(recovered.containsKey("room-corrupt"));

        // Verify corrupted file was moved to .wal.corrupted
        Path expectedQuarantinePath = directory.resolve("room-corrupt.wal.corrupted");
        assertTrue(Files.exists(expectedQuarantinePath), "Corrupted WAL must be moved to .wal.corrupted");
        assertFalse(Files.exists(corruptWalPath), "Original corrupted .wal must no longer exist");

        // Verify store remains healthy
        assertTrue(store.isHealthy(), "Store should be healthy after recoverAll isolates corrupted WAL");
        assertNull(store.lastError(), "Store lastError should be null");
    }

    /**
     * Supports the test scenario for keyring.
     * @param id id supplied to the fixture
     * @param value value supplied to the fixture
     * @return keyring containing the requested deterministic key
     */
    private static EncryptionKeyring keyring(String id, byte value) {
        return new EncryptionKeyring(id, Map.of(id, new SecretKeySpec(filledKey(value), "AES")));
    }

    /**
     * Supports the test scenario for filled key.
     * @param value value supplied to the fixture
     * @return deterministic 256-bit key material
     */
    private static byte[] filledKey(byte value) {
        byte[] key = new byte[32];
        Arrays.fill(key, value);
        return key;
    }
}
