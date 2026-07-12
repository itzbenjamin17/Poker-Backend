package com.pokergame.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EncryptedWalStoreTest {

    @TempDir
    Path directory;

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

    @Test
    void arbitraryShortTailIsCorruptionRatherThanAProvableTornFrame() throws Exception {
        EncryptionKeyring keyring = keyring("current", (byte) 7);
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring);
        WalTransaction transaction = store.prepare("room-tail");
        store.commit(transaction, "safe".getBytes(StandardCharsets.UTF_8));
        Files.write(directory.resolve("room-tail.wal"), new byte[] { 1, 2, 3 },
                java.nio.file.StandardOpenOption.APPEND);

        assertThrows(PersistenceException.class, () -> store.recoverLatest("room-tail"));
        assertFalse(store.isHealthy());
    }

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

    private static EncryptionKeyring keyring(String id, byte value) {
        return new EncryptionKeyring(id, Map.of(id, new SecretKeySpec(filledKey(value), "AES")));
    }

    private static byte[] filledKey(byte value) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, value);
        return key;
    }
}
