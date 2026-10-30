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
        Files.write(wal, java.util.Arrays.copyOf(withFinalPrepare, withFinalPrepare.length - 5));
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
        Files.write(wal, java.util.Arrays.copyOf(bytes, bytes.length - 5));
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
        java.util.Arrays.fill(key, value);
        return key;
    }
}
