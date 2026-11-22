package com.pokergame.persistence.wal;

import static com.pokergame.testutil.TestRoomLocks.withRoomLock;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pokergame.persistence.PersistenceException;
import com.pokergame.util.LockLease;
import com.pokergame.util.RoomCoordinator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests encrypted WAL store behavior. */
@Tag("unit")
class EncryptedWalStoreTest {

    private static final int FRAME_HEADER_BYTES = 8;
    private static final int GCM_TAG_BYTES = 16;

    @TempDir
    Path directory;

    private final RoomCoordinator roomCoordinator = new RoomCoordinator();

    /**
     * Protects the contract that committed payload recovers without appearing as plaintext.
     */
    @Test
    void committedPayloadRecoversWithoutAppearingAsPlaintext() throws Exception {
        EncryptionKeyring keyring = new EncryptionKeyring("current", Map.of(
                "current", new SecretKeySpec(new byte[32], "AES")));
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring, roomCoordinator);
        byte[] snapshot = "password=very-secret;holeCards=AS,KH".getBytes(StandardCharsets.UTF_8);

        withRoomLock(roomCoordinator, "room-1", () -> {
            WalTransaction transaction = store.prepare("room-1");
            store.commit(transaction, snapshot);
            assertArrayEquals(snapshot, store.recoverLatest("room-1").orElseThrow());
            return null;
        });

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
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring, roomCoordinator);

        withRoomLock(roomCoordinator, "room-2", () -> {
            WalTransaction committed = store.prepare("room-2");
            store.commit(committed, "first".getBytes(StandardCharsets.UTF_8));
            store.prepare("room-2");
            assertEquals("first", new String(store.recoverLatest("room-2").orElseThrow(), StandardCharsets.UTF_8));
            return null;
        });
    }

    /**
     * Protects the contract that torn final frame is ignored but committed prefix tampering fails closed.
     */
    @Test
    void tornFinalFrameIsIgnoredButCommittedPrefixTamperingFailsClosed() throws Exception {
        EncryptionKeyring keyring = keyring("current", (byte) 1);
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring, roomCoordinator);
        Path wal = directory.resolve("room-3.wal");
        byte[] committed;
        byte[] withFinalPrepare;
        try (LockLease _ = roomCoordinator.acquire("room-3")) {
            WalTransaction transaction = store.prepare("room-3");
            store.commit(transaction, "safe".getBytes(StandardCharsets.UTF_8));
            committed = Files.readAllBytes(wal);
            store.prepare("room-3");
            withFinalPrepare = Files.readAllBytes(wal);
            Files.write(wal, Arrays.copyOf(withFinalPrepare, withFinalPrepare.length - 5));
            assertEquals("safe", new String(store.recoverLatest("room-3").orElseThrow(), StandardCharsets.UTF_8));
        }

        Files.write(wal, committed);
        byte[] corrupt = committed.clone();
        corrupt[corrupt.length / 2] ^= 1;
        Files.write(wal, corrupt);
        EncryptedWalStore recovering = new EncryptedWalStore(directory, keyring, roomCoordinator);
        withRoomLock(roomCoordinator, "room-3", () -> {
            assertThrows(PersistenceException.class, () -> recovering.recoverLatest("room-3"));
            return null;
        });
        assertFalse(recovering.isHealthy());
    }

    /** Exercises every incomplete header length, including a missing record length after the magic. */
    @Test
    void partialFrameHeaderIsTruncatedSoSubsequentWritesDoNotCorruptWal() throws Exception {
        assertTornCommitsRecoverAndAllowLaterAppends("partial-header", List.of(1, 2, 3, 4, 5, 6, 7));
    }

    /** A WAL copied under another room's name is rejected because each record is bound to its own room. */
    @Test
    void walCopiedToAnotherRoomFailsClosed() throws Exception {
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring("old", (byte) 2), roomCoordinator);
        withRoomLock(roomCoordinator, "room-a", () -> {
            store.commit(store.prepare("room-a"), "state".getBytes(StandardCharsets.UTF_8));
            return null;
        });
        Files.copy(directory.resolve("room-a.wal"), directory.resolve("room-b.wal"),
                StandardCopyOption.REPLACE_EXISTING);

        withRoomLock(roomCoordinator, "room-b", () -> {
            PersistenceException failure =
                    assertThrows(PersistenceException.class, () -> store.recoverLatest("room-b"));
            assertEquals("WAL record belongs to a different room", failure.getMessage());
            return null;
        });
        assertFalse(store.isHealthy("room-b"));
    }

    /** A record written under a key the keyring no longer holds fails with the dedicated missing-key signal. */
    @Test
    void recoverLatestWithMissingKeyThrowsMissingPersistenceKeyException() throws Exception {
        EncryptedWalStore writer = new EncryptedWalStore(directory, keyring("old", (byte) 2), roomCoordinator);
        withRoomLock(roomCoordinator, "room-a", () -> {
            writer.commit(writer.prepare("room-a"), "state".getBytes(StandardCharsets.UTF_8));
            return null;
        });
        EncryptedWalStore missingKey = new EncryptedWalStore(directory, keyring("new", (byte) 3), roomCoordinator);

        withRoomLock(roomCoordinator, "room-a", () -> {
            MissingPersistenceKeyException failure =
                    assertThrows(MissingPersistenceKeyException.class, () -> missingKey.recoverLatest("room-a"));
            assertEquals("Required persistence key is unavailable: old", failure.getMessage());
            return null;
        });
    }

    /**
     * A missing historical key is an operator configuration error: preserve the WAL,
     * fail startup, and allow recovery once the operator restores the keyring.
     */
    @Test
    void recoverAllFailsClosedWithoutQuarantiningWalWhenHistoricalKeyIsMissing() throws Exception {
        EncryptedWalStore writer = new EncryptedWalStore(directory, keyring("old", (byte) 2), roomCoordinator);
        withRoomLock(roomCoordinator, "room-old-key", () -> {
            writer.commit(writer.prepare("room-old-key"), "accepted-state".getBytes(StandardCharsets.UTF_8));
            return null;
        });
        Path wal = directory.resolve("room-old-key.wal");
        byte[] original = Files.readAllBytes(wal);

        EncryptedWalStore missingKey = new EncryptedWalStore(directory, keyring("current", (byte) 3), roomCoordinator);
        assertThrows(MissingPersistenceKeyException.class, missingKey::recoverAll);
        assertFalse(missingKey.isHealthy());
        assertArrayEquals(original, Files.readAllBytes(wal));
        assertFalse(Files.exists(directory.resolve("room-old-key.wal.corrupted")));

        EncryptedWalStore corrected = new EncryptedWalStore(directory, keyring("old", (byte) 2), roomCoordinator);
        assertArrayEquals("accepted-state".getBytes(StandardCharsets.UTF_8),
                corrected.recoverAll().get("room-old-key"));
    }

    /**
     * Protects the contract that rotation compacts under current key so old key can be retired.
     */
    @Test
    void rotationCompactsUnderCurrentKeySoOldKeyCanBeRetired() {
        EncryptionKeyring oldKeys = keyring("old", (byte) 4);
        EncryptedWalStore oldStore = new EncryptedWalStore(directory, oldKeys, roomCoordinator);
        withRoomLock(roomCoordinator, "room-rotate", () -> {
            WalTransaction transaction = oldStore.prepare("room-rotate");
            oldStore.commit(transaction, "rotated-state".getBytes(StandardCharsets.UTF_8));
            return null;
        });

        byte[] old = filledKey((byte) 4);
        byte[] current = filledKey((byte) 5);
        EncryptionKeyring rotating = new EncryptionKeyring("current", Map.of(
                "old", new SecretKeySpec(old, "AES"),
                "current", new SecretKeySpec(current, "AES")));
        EncryptedWalStore rotatingStore = new EncryptedWalStore(directory, rotating, roomCoordinator);
        withRoomLock(roomCoordinator, "room-rotate", () -> {
            byte[] latest = rotatingStore.recoverLatest("room-rotate").orElseThrow();
            rotatingStore.compact("room-rotate", latest);
            return null;
        });

        EncryptedWalStore currentOnly = new EncryptedWalStore(directory, keyring("current", (byte) 5), roomCoordinator);
        withRoomLock(roomCoordinator, "room-rotate", () -> {
            assertEquals("rotated-state",
                    new String(currentOnly.recoverLatest("room-rotate").orElseThrow(), StandardCharsets.UTF_8));
            currentOnly.delete("room-rotate");
            assertTrue(currentOnly.recoverLatest("room-rotate").isEmpty());
            return null;
        });
    }

    /**
     * Protects the contract that commit failure marks only the failed room unhealthy while other rooms can still append.
     */
    @Test
    void commitFailureMarksFailedRoomUnhealthyWhileOtherRoomsCanStillAppend() {
        AtomicReference<WalFaultPoint> failure = new AtomicReference<>();
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring("current", (byte) 6),
                (point, roomId) -> {
                    if (point == failure.get()) throw new PersistenceException("injected " + point);
                }, roomCoordinator);
        withRoomLock(roomCoordinator, "room-fail", () -> {
            WalTransaction transaction = store.prepare("room-fail");
            failure.set(WalFaultPoint.BEFORE_COMMIT_WRITE);

            assertThrows(PersistenceException.class,
                    () -> store.commit(transaction, "lost".getBytes(StandardCharsets.UTF_8)));
            return null;
        });
        assertFalse(store.isHealthy());
        assertFalse(store.isHealthy("room-fail"));
        assertTrue(store.isHealthy("other-room"));
        failure.set(null);
        withRoomLock(roomCoordinator, "room-fail", () -> {
            assertThrows(PersistenceException.class, () -> store.prepare("room-fail"));
            assertThrows(PersistenceException.class,
                    () -> store.compact("room-fail", "lost".getBytes(StandardCharsets.UTF_8)));
            return null;
        });

        withRoomLock(roomCoordinator, "other-room", () -> {
            WalTransaction otherTx = store.prepare("other-room");
            byte[] otherPayload = "other-room-ok".getBytes(StandardCharsets.UTF_8);
            store.commit(otherTx, otherPayload);
            assertArrayEquals(otherPayload, store.recoverLatest("other-room").orElseThrow());
            return null;
        });
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
                }, roomCoordinator);
        byte[] snapshot = "durable".getBytes(StandardCharsets.UTF_8);
        withRoomLock(roomCoordinator, "room-compact-fail", () -> {
            WalTransaction compactingTransaction = compactingStore.prepare("room-compact-fail");
            compactingStore.commit(compactingTransaction, snapshot);
            compactionFailure.set(WalFaultPoint.BEFORE_COMPACTION_REPLACE);

            assertThrows(PersistenceException.class,
                    () -> compactingStore.compact("room-compact-fail", snapshot));
            return null;
        });
        assertFalse(compactingStore.isHealthy());

        AtomicReference<WalFaultPoint> deleteFailure = new AtomicReference<>();
        EncryptedWalStore deletingStore = new EncryptedWalStore(directory.resolve("delete"),
                keyring("current", (byte) 9),
                (point, roomId) -> {
                    if (point == deleteFailure.get()) throw new PersistenceException("injected " + point);
                }, roomCoordinator);
        withRoomLock(roomCoordinator, "room-delete-fail", () -> {
            WalTransaction deletingTransaction = deletingStore.prepare("room-delete-fail");
            deletingStore.commit(deletingTransaction, snapshot);
            deleteFailure.set(WalFaultPoint.BEFORE_DELETE);

            assertThrows(PersistenceException.class, () -> deletingStore.delete("room-delete-fail"));
            return null;
        });
        assertFalse(deletingStore.isHealthy());
    }

    /** A repair replaces an interrupted tail so the next restart sees all subsequent saves. */
    @Test
    void recoveredTornTailRequiresCheckpointBeforeFurtherAppends() throws Exception {
        EncryptionKeyring keys = keyring("current", (byte) 11);
        EncryptedWalStore first = new EncryptedWalStore(directory, keys, roomCoordinator);
        withRoomLock(roomCoordinator, "room-torn", () -> {
            first.commit(first.prepare("room-torn"), new byte[]{1});
            first.prepare("room-torn");
            return null;
        });
        Path wal = directory.resolve("room-torn.wal");
        byte[] bytes = Files.readAllBytes(wal);
        Files.write(wal, Arrays.copyOf(bytes, bytes.length - 5));
        EncryptedWalStore second = new EncryptedWalStore(directory, keys, roomCoordinator);
        withRoomLock(roomCoordinator, "room-torn", () -> {
            assertArrayEquals(new byte[]{1}, second.recoverLatest("room-torn").orElseThrow());
            assertTrue(second.requiresCheckpoint("room-torn"));
            assertThrows(PersistenceException.class, () -> second.prepare("room-torn"));

            second.checkpoint("room-torn", new byte[]{2});
            second.commit(second.prepare("room-torn"), new byte[]{3});
            return null;
        });
        EncryptedWalStore third = new EncryptedWalStore(directory, keys, roomCoordinator);
        withRoomLock(roomCoordinator, "room-torn", () -> {
            assertArrayEquals(new byte[]{3}, third.recoverLatest("room-torn").orElseThrow());
            assertFalse(third.requiresCheckpoint("room-torn"));
            return null;
        });
    }

    /** Recovery of a room that never committed returns nothing, repairs a torn tail and demands a checkpoint. */
    @Test
    void recoverLatestOfNeverCommittedWalWithTornTailReturnsEmptyAfterTruncatingTail() throws Exception {
        EncryptedWalStore writer = new EncryptedWalStore(directory, keyring("current", (byte) 14), roomCoordinator);
        Path wal = directory.resolve("room-never-committed.wal");
        withRoomLock(roomCoordinator, "room-never-committed", () -> writer.prepare("room-never-committed"));
        long completeLength = Files.size(wal);
        Files.write(wal, new byte[]{1, 2, 3}, StandardOpenOption.APPEND);

        EncryptedWalStore recovering = new EncryptedWalStore(directory, keyring("current", (byte) 14), roomCoordinator);
        withRoomLock(roomCoordinator, "room-never-committed", () -> {
            assertTrue(recovering.recoverLatest("room-never-committed").isEmpty());
            assertTrue(recovering.requiresCheckpoint("room-never-committed"));
            return null;
        });
        assertEquals(completeLength, Files.size(wal), "Recovery removes the torn bytes");
    }

    /** A complete prepare-only WAL recovers as empty without any repair or checkpoint requirement. */
    @Test
    void recoverLatestOfNeverCommittedCompleteWalLeavesFileAndCheckpointStateAlone() throws Exception {
        EncryptedWalStore writer = new EncryptedWalStore(directory, keyring("current", (byte) 15), roomCoordinator);
        Path wal = directory.resolve("room-prepare-only.wal");
        withRoomLock(roomCoordinator, "room-prepare-only", () -> writer.prepare("room-prepare-only"));
        byte[] before = Files.readAllBytes(wal);

        EncryptedWalStore recovering = new EncryptedWalStore(directory, keyring("current", (byte) 15), roomCoordinator);
        withRoomLock(roomCoordinator, "room-prepare-only", () -> {
            assertTrue(recovering.recoverLatest("room-prepare-only").isEmpty());
            assertFalse(recovering.requiresCheckpoint("room-prepare-only"));
            return null;
        });
        assertArrayEquals(before, Files.readAllBytes(wal));
    }

    /** A cold store that meets a torn tail while appending refuses without touching the file. */
    @Test
    void appendingToColdStoreWithTornTailRequiresCheckpointAndLeavesFileUntouched() throws Exception {
        EncryptionKeyring keys = keyring("current", (byte) 18);
        EncryptedWalStore writer = new EncryptedWalStore(directory, keys, roomCoordinator);
        Path wal = directory.resolve("room-cold-torn.wal");
        withRoomLock(roomCoordinator, "room-cold-torn", () -> {
            writer.commit(writer.prepare("room-cold-torn"), new byte[]{1});
            return null;
        });
        Files.write(wal, new byte[]{9, 9, 9}, StandardOpenOption.APPEND);
        byte[] before = Files.readAllBytes(wal);

        EncryptedWalStore cold = new EncryptedWalStore(directory, keys, roomCoordinator);
        withRoomLock(roomCoordinator, "room-cold-torn", () -> {
            PersistenceException ex = assertThrows(PersistenceException.class, () -> cold.prepare("room-cold-torn"));
            assertTrue(ex.getMessage().contains("full checkpoint"));
            assertTrue(cold.requiresCheckpoint("room-cold-torn"));
            return null;
        });
        assertArrayEquals(before, Files.readAllBytes(wal));
    }

    /** A torn tail does not get repaired in a file that fails validation; recoverAll quarantines it intact. */
    @Test
    void recoverAllQuarantinesUnpairedCommitWithTornTailWithoutTruncatingIt() throws Exception {
        EncryptionKeyring keys = keyring("current", (byte) 19);
        EncryptedWalStore writer = new EncryptedWalStore(directory, keys, roomCoordinator);
        Path wal = directory.resolve("room-unpaired.wal");
        withRoomLock(roomCoordinator, "room-unpaired", () -> {
            WalTransaction prepared = writer.prepare("room-unpaired");
            WalTransaction forged = new WalTransaction("room-unpaired", UUID.randomUUID(),
                    prepared.prepareSequence());
            writer.commit(forged, new byte[]{1});
            return null;
        });
        Files.write(wal, new byte[]{9, 9, 9}, StandardOpenOption.APPEND);
        byte[] before = Files.readAllBytes(wal);

        EncryptedWalStore recovering = new EncryptedWalStore(directory, keys, roomCoordinator);
        assertTrue(recovering.recoverAll().isEmpty());

        assertFalse(Files.exists(wal));
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("room-unpaired.wal.corrupted")));
    }

    /** The side-effect-free committed-state check distinguishes committed, prepare-only and missing WALs. */
    @Test
    void hasCommittedStateReportsCommittedStateWithoutTouchingFilesOrTracking() throws Exception {
        EncryptionKeyring keys = keyring("current", (byte) 16);
        EncryptedWalStore writer = new EncryptedWalStore(directory, keys, roomCoordinator);
        Path committedWal = directory.resolve("room-has-state.wal");
        Path prepareOnlyWal = directory.resolve("room-no-state.wal");
        withRoomLock(roomCoordinator, "room-has-state", () -> {
            writer.commit(writer.prepare("room-has-state"), new byte[]{1});
            writer.prepare("room-has-state");
            return null;
        });
        withRoomLock(roomCoordinator, "room-no-state", () -> writer.prepare("room-no-state"));
        // Torn tails on both files: a recovery would truncate them and require a checkpoint.
        Files.write(committedWal, new byte[]{9, 9, 9}, StandardOpenOption.APPEND);
        Files.write(prepareOnlyWal, new byte[]{9, 9, 9}, StandardOpenOption.APPEND);
        Path compacting = directory.resolve("room-has-state.wal.compacting");
        byte[] committedBytes = Files.readAllBytes(committedWal);
        byte[] prepareOnlyBytes = Files.readAllBytes(prepareOnlyWal);

        EncryptedWalStore checking = new EncryptedWalStore(directory, keys, roomCoordinator);
        // Written after construction, which deletes orphaned compaction files.
        Files.write(compacting, new byte[]{4, 5, 6});
        withRoomLock(roomCoordinator, "room-has-state", () -> {
            assertTrue(checking.hasCommittedState("room-has-state"));
            assertFalse(checking.requiresCheckpoint("room-has-state"));
            return null;
        });
        withRoomLock(roomCoordinator, "room-no-state", () -> {
            assertFalse(checking.hasCommittedState("room-no-state"));
            assertFalse(checking.requiresCheckpoint("room-no-state"));
            return null;
        });
        withRoomLock(roomCoordinator, "room-missing", () -> {
            assertFalse(checking.hasCommittedState("room-missing"));
            return null;
        });

        assertArrayEquals(committedBytes, Files.readAllBytes(committedWal), "No torn-tail truncation");
        assertArrayEquals(prepareOnlyBytes, Files.readAllBytes(prepareOnlyWal), "No torn-tail truncation");
        assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(compacting), "No compaction file deletion");
        assertFalse(Files.exists(directory.resolve("room-missing.wal")));
        assertTrue(checking.isHealthy());
    }

    /** A tampered file makes the check fail without marking the store unhealthy, unlike a real recovery. */
    @Test
    void hasCommittedStateFailsOnCorruptWalWithoutRecordingAFailure() throws Exception {
        EncryptionKeyring keys = keyring("current", (byte) 17);
        EncryptedWalStore store = new EncryptedWalStore(directory, keys, roomCoordinator);
        Path wal = directory.resolve("room-corrupt-check.wal");
        withRoomLock(roomCoordinator, "room-corrupt-check", () -> {
            store.commit(store.prepare("room-corrupt-check"), new byte[]{1});
            return null;
        });
        byte[] corrupt = Files.readAllBytes(wal);
        corrupt[corrupt.length / 2] ^= 1;
        Files.write(wal, corrupt);

        withRoomLock(roomCoordinator, "room-corrupt-check", () -> {
            assertThrows(PersistenceException.class, () -> store.hasCommittedState("room-corrupt-check"));
            return null;
        });
        assertTrue(store.isHealthy());
        assertArrayEquals(corrupt, Files.readAllBytes(wal));
    }

    /** One room's repair cannot declare other unsaved rooms healthy. */
    @Test
    void checkpointOnlyClearsItsOwnRoomFailure() {
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring("current", (byte) 12), roomCoordinator);
        withRoomLock(roomCoordinator, "room-A", () -> {
            store.recordWriteFailure("room-A", new PersistenceException("unavailable"));
            return null;
        });
        withRoomLock(roomCoordinator, "room-B", () -> {
            store.recordWriteFailure("room-B", new PersistenceException("unavailable"));
            return null;
        });
        withRoomLock(roomCoordinator, "room-A", () -> {
            store.checkpoint("room-A", new byte[]{1});
            assertFalse(store.requiresCheckpoint("room-A"));
            return null;
        });
        assertFalse(store.isHealthy());
        assertEquals(1, store.unsavedRoomCount());
        withRoomLock(roomCoordinator, "room-B", () -> {
            assertTrue(store.requiresCheckpoint("room-B"));
            store.checkpoint("room-B", new byte[]{2});
            return null;
        });
        assertTrue(store.isHealthy());
        assertNull(store.lastError());
    }

    /**
     * Pins the on-disk format: a checkpoint writes sequences 1 and 2, appends then continue at 3, and each frame's
     * ciphertext is authenticated with the documented associated data, checked here with an independent decryptor.
     */
    @Test
    void checkpointedWalKeepsDocumentedFrameLayoutAndContinuesAtSequenceThree() throws Exception {
        EncryptionKeyring keys = keyring("current", (byte) 13);
        EncryptedWalStore store = new EncryptedWalStore(directory, keys, roomCoordinator);
        byte[] checkpointed = "checkpointed".getBytes(StandardCharsets.UTF_8);
        withRoomLock(roomCoordinator, "room-fmt", () -> {
            store.checkpoint("room-fmt", checkpointed);
            assertEquals(3L, store.prepare("room-fmt").prepareSequence());
            return null;
        });
        Path wal = directory.resolve("room-fmt.wal");

        ByteBuffer file = ByteBuffer.wrap(Files.readAllBytes(wal));
        long[] expectedSequences = {1, 2, 3};
        byte[][] expectedPayloads = {new byte[0], checkpointed, new byte[0]};
        for (int i = 0; i < expectedSequences.length; i++) {
            assertEquals(0x504B574C, file.getInt());
            int frameLength = file.getInt();
            ByteBuffer frame = file.slice(file.position(), frameLength);
            file.position(file.position() + frameLength);

            long sequence = frame.getLong();
            long high = frame.getLong();
            long low = frame.getLong();
            byte type = frame.get();
            byte[] room = new byte[Short.toUnsignedInt(frame.getShort())];
            frame.get(room);
            byte[] keyId = new byte[Short.toUnsignedInt(frame.getShort())];
            frame.get(keyId);
            byte[] nonce = new byte[12];
            frame.get(nonce);
            byte[] ciphertext = new byte[frame.getInt()];
            frame.get(ciphertext);
            assertFalse(frame.hasRemaining());
            assertEquals(expectedSequences[i], sequence);
            assertEquals(i == 1 ? 2 : 1, type);

            ByteBuffer aad = ByteBuffer.allocate(8 + 8 + 8 + 1 + 2 + room.length + 2 + keyId.length);
            aad.putLong(sequence).putLong(high).putLong(low).put(type)
                    .putShort((short) room.length).put(room).putShort((short) keyId.length).put(keyId);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(filledKey((byte) 13), "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad.array());
            assertArrayEquals(expectedPayloads[i], cipher.doFinal(ciphertext));
        }
        assertFalse(file.hasRemaining());
    }

    /** Exercises interrupted bodies after a complete header, through metadata and ciphertext. */
    @Test
    void tornTrailingFrameIsTruncatedSoSubsequentWritesDoNotCorruptWal() throws Exception {
        assertTornCommitsRecoverAndAllowLaterAppends("partial-body", List.of(8, 18, 24, 43, 88));
    }

    /** Repeatedly interrupts a real commit frame after its complete prepare, then reopens the repaired file. */
    private void assertTornCommitsRecoverAndAllowLaterAppends(String roomId, List<Integer> framePrefixes)
            throws Exception {
        EncryptionKeyring keys = keyring("current", (byte) 13);
        Path wal = directory.resolve(roomId + ".wal");
        EncryptedWalStore writer = new EncryptedWalStore(directory, keys, roomCoordinator);
        byte[] accepted = {0};
        try (LockLease _ = roomCoordinator.acquire(roomId)) {
            writer.commit(writer.prepare(roomId), accepted);
        }

        for (int framePrefix : framePrefixes) {
            long prepareEnd;
            try (LockLease _ = roomCoordinator.acquire(roomId)) {
                WalTransaction interrupted = writer.prepare(roomId);
                prepareEnd = Files.size(wal);
                writer.commit(interrupted, new byte[128]);
                assertTrue(Files.size(wal) > prepareEnd + framePrefix);
            }
            try (FileChannel channel = FileChannel.open(wal, StandardOpenOption.WRITE)) {
                channel.truncate(prepareEnd + framePrefix);
            }

            EncryptedWalStore recovery = new EncryptedWalStore(directory, keys, roomCoordinator);
            try (LockLease _ = roomCoordinator.acquire(roomId)) {
                assertArrayEquals(accepted, recovery.recoverLatest(roomId).orElseThrow());
            }
            assertEquals(prepareEnd, Files.size(wal), "Torn commit prefix: " + framePrefix);

            writer = new EncryptedWalStore(directory, keys, roomCoordinator);
            accepted = new byte[]{(byte) framePrefix};
            try (LockLease _ = roomCoordinator.acquire(roomId)) {
                writer.commit(writer.prepare(roomId), accepted);
            }
        }

        EncryptedWalStore fresh = new EncryptedWalStore(directory, keys, roomCoordinator);
        try (LockLease _ = roomCoordinator.acquire(roomId)) {
            assertArrayEquals(accepted, fresh.recoverLatest(roomId).orElseThrow());
        }
        assertTrue(fresh.isHealthy());
        assertNull(fresh.lastError());
    }

    /**
     * Protects the contract that orphaned .compacting files left from prior crashes
     * are cleaned up when opening and recovering WAL files.
     */
    @Test
    void orphanedCompactingFilesAreCleanedUpOnStoreInitializationAndRecovery() throws Exception {
        EncryptionKeyring keys = keyring("current", (byte) 30);
        EncryptedWalStore store = new EncryptedWalStore(directory, keys, roomCoordinator);
        byte[] payload = "orphan-payload".getBytes(StandardCharsets.UTF_8);
        withRoomLock(roomCoordinator, "room-orphan", () -> {
            WalTransaction tx = store.prepare("room-orphan");
            store.commit(tx, payload);
            return null;
        });

        Path orphanedCompacting = directory.resolve("room-orphan.wal.compacting");
        Path otherCompacting = directory.resolve("other.wal.compacting");
        Files.write(orphanedCompacting, new byte[]{1, 2, 3, 4, 5});
        Files.write(otherCompacting, new byte[]{6, 7, 8, 9, 10});

        assertTrue(Files.exists(orphanedCompacting));
        assertTrue(Files.exists(otherCompacting));

        // Initializing a new store cleans up orphaned .compacting files
        EncryptedWalStore newStore = new EncryptedWalStore(directory, keys, roomCoordinator);
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
        Optional<byte[]> latest = withRoomLock(roomCoordinator, "room-orphan",
                () -> newStore.recoverLatest("room-orphan"));
        assertTrue(latest.isPresent());
        assertArrayEquals(payload, latest.get());
        assertFalse(Files.exists(orphanedCompacting));
    }

    /** A tampered room degrades only itself: it refuses appends while other rooms keep writing. */
    @Test
    void tamperedRoomRefusesAppendsWhileOtherRoomsKeepWriting() throws Exception {
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring("current", (byte) 42), roomCoordinator);
        commitHealthyAndTamperedRooms(store);

        withRoomLock(roomCoordinator, "room-corrupt", () -> {
            assertThrows(PersistenceException.class, () -> store.recoverLatest("room-corrupt"));
            return null;
        });
        assertFalse(store.isHealthy(), "Store should be degraded while room-corrupt is in failedRooms");
        assertFalse(store.isHealthy("room-corrupt"));
        assertTrue(store.isHealthy("room-healthy"));
        withRoomLock(roomCoordinator, "room-corrupt", () -> {
            assertThrows(PersistenceException.class, () -> store.prepare("room-corrupt"));
            return null;
        });

        byte[] duringDegradation = "healthy-during-degradation".getBytes(StandardCharsets.UTF_8);
        Optional<byte[]> latest = withRoomLock(roomCoordinator, "room-healthy", () -> {
            store.commit(store.prepare("room-healthy"), duringDegradation);
            return store.recoverLatest("room-healthy");
        });
        assertArrayEquals(duringDegradation, latest.orElseThrow());
    }

    /** Quarantine moves the corrupted file aside, clears the room's tracking, and restores store health. */
    @Test
    void quarantineMovesFileClearsTrackingAndRestoresStoreHealth() throws Exception {
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring("current", (byte) 42), roomCoordinator);
        commitHealthyAndTamperedRooms(store);
        withRoomLock(roomCoordinator, "room-corrupt", () -> {
            assertThrows(PersistenceException.class, () -> store.recoverLatest("room-corrupt"));
            return null;
        });
        Path corruptWalPath = directory.resolve("room-corrupt.wal");
        Path expectedQuarantinePath = directory.resolve("room-corrupt.wal.corrupted");
        assertFalse(Files.exists(expectedQuarantinePath));

        Path actualQuarantinePath = withRoomLock(roomCoordinator, "room-corrupt",
                () -> store.quarantine("room-corrupt", "Ciphertext tamper detected during recovery"));

        assertEquals(expectedQuarantinePath, actualQuarantinePath);
        assertTrue(Files.exists(expectedQuarantinePath), "Quarantined file must exist with .wal.corrupted suffix");
        assertFalse(Files.exists(corruptWalPath), "Original .wal file must no longer exist at active WAL path");
        assertTrue(store.isHealthy(), "Quarantining corrupted room must restore store health for remaining rooms");
        assertNull(store.lastError(), "Last error must be cleared when no other rooms are failed");
        withRoomLock(roomCoordinator, "room-corrupt", () -> {
            assertFalse(store.requiresCheckpoint("room-corrupt"), "Quarantined room must not require checkpoint");
            assertTrue(store.recoverLatest("room-corrupt").isEmpty());
            return null;
        });
    }

    /** After another room is quarantined, healthy rooms continue to commit and recover. */
    @Test
    void healthyRoomKeepsCommittingAfterAnotherRoomIsQuarantined() throws Exception {
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring("current", (byte) 42), roomCoordinator);
        commitHealthyAndTamperedRooms(store);
        withRoomLock(roomCoordinator, "room-corrupt", () -> {
            assertThrows(PersistenceException.class, () -> store.recoverLatest("room-corrupt"));
            store.quarantine("room-corrupt", "Ciphertext tamper detected during recovery");
            return null;
        });

        byte[] payload = "healthy-2".getBytes(StandardCharsets.UTF_8);
        Optional<byte[]> recovered = withRoomLock(roomCoordinator, "room-healthy", () -> {
            store.commit(store.prepare("room-healthy"), payload);
            return store.recoverLatest("room-healthy");
        });

        assertArrayEquals(payload, recovered.orElseThrow());
    }

    /** Commits one payload in each of {@code room-healthy} and {@code room-corrupt}, then flips a byte of the latter. */
    private void commitHealthyAndTamperedRooms(EncryptedWalStore store) throws Exception {
        withRoomLock(roomCoordinator, "room-healthy", () -> {
            store.commit(store.prepare("room-healthy"), "healthy-1".getBytes(StandardCharsets.UTF_8));
            return null;
        });
        withRoomLock(roomCoordinator, "room-corrupt", () -> {
            store.commit(store.prepare("room-corrupt"), "corrupt-payload".getBytes(StandardCharsets.UTF_8));
            return null;
        });
        Path corruptWalPath = directory.resolve("room-corrupt.wal");
        byte[] walBytes = Files.readAllBytes(corruptWalPath);
        walBytes[walBytes.length / 2] ^= 0x5A;
        Files.write(corruptWalPath, walBytes);
    }

    /**
     * Protects the contract that quarantining when the WAL file does not exist on disk
     * cleans up internal tracking maps without throwing or creating ghost files.
     */
    @Test
    void quarantineWhenWalDoesNotExistCleansUpRoomTrackingSafely() {
        EncryptionKeyring keys = keyring("current", (byte) 43);
        EncryptedWalStore store = new EncryptedWalStore(directory, keys, roomCoordinator);

        // Record a failure for a room that has no file on disk
        withRoomLock(roomCoordinator, "room-missing", () -> {
            store.recordWriteFailure("room-missing", new PersistenceException("Simulated write glitch"));
            assertFalse(store.isHealthy());
            assertTrue(store.requiresCheckpoint("room-missing"));

            Path quarantinePath = store.quarantine("room-missing", "Clean up non-existent WAL error");
            assertEquals(directory.resolve("room-missing.wal.corrupted"), quarantinePath);
            assertFalse(Files.exists(quarantinePath), "No corrupted file should be created if original did not exist");
            assertTrue(store.isHealthy(), "Store should be healthy after quarantine cleans up tracking");
            assertFalse(store.requiresCheckpoint("room-missing"));
            assertTrue(store.recoverLatest("room-missing").isEmpty());
            return null;
        });
    }

    /**
     * Protects the contract that quarantine validates room IDs and rejects path traversal.
     */
    @Test
    void quarantineRejectsInvalidRoomIdWithPersistenceException() {
        EncryptionKeyring keys = keyring("current", (byte) 44);
        EncryptedWalStore store = new EncryptedWalStore(directory, keys, roomCoordinator);

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
        EncryptedWalStore store = new EncryptedWalStore(directory, keys, roomCoordinator);

        byte[] payloadActive = "active-data".getBytes(StandardCharsets.UTF_8);
        withRoomLock(roomCoordinator, "room-active", () -> {
            WalTransaction tx1 = store.prepare("room-active");
            store.commit(tx1, payloadActive);
            return null;
        });

        withRoomLock(roomCoordinator, "room-to-quarantine", () -> {
            WalTransaction tx2 = store.prepare("room-to-quarantine");
            byte[] payloadQuarantine = "quarantine-data".getBytes(StandardCharsets.UTF_8);
            store.commit(tx2, payloadQuarantine);
            return null;
        });

        assertEquals(2, store.walCount());

        // Quarantine room-to-quarantine
        Path quarantined = withRoomLock(roomCoordinator, "room-to-quarantine",
                () -> store.quarantine("room-to-quarantine", "Administrative isolation"));
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

    /** Keeps frame-header corruption and authenticated-body corruption in the same recovery scan. */
    @Test
    void recoverAllQuarantinesCorruptedWalFilesAndRecoversRemainingValidRooms() throws Exception {
        EncryptionKeyring keys = keyring("current", (byte) 46);
        EncryptedWalStore store = new EncryptedWalStore(directory, keys, roomCoordinator);

        // 1. Prepare and commit a valid room
        byte[] validPayload = "valid-room-data".getBytes(StandardCharsets.UTF_8);
        withRoomLock(roomCoordinator, "room-valid", () -> {
            WalTransaction txValid = store.prepare("room-valid");
            store.commit(txValid, validPayload);
            return null;
        });

        // 2. Prepare and commit a room, then corrupt its WAL file on disk
        withRoomLock(roomCoordinator, "room-corrupt", () -> {
            WalTransaction txCorrupt = store.prepare("room-corrupt");
            byte[] corruptPayload = "corrupt-room-data".getBytes(StandardCharsets.UTF_8);
            store.commit(txCorrupt, corruptPayload);
            return null;
        });

        Path corruptWalPath = directory.resolve("room-corrupt.wal");
        byte[] walBytes = Files.readAllBytes(corruptWalPath);
        walBytes[walBytes.length / 2] ^= 0x5A; // tamper ciphertext
        Files.write(corruptWalPath, walBytes);

        Path badHeader = directory.resolve("room-bad-header.wal");
        Files.write(badHeader, ByteBuffer.allocate(32).putInt(0xDEADBEEF).putInt(24).array());

        Map<String, byte[]> recovered = assertDoesNotThrow(store::recoverAll);

        assertEquals(1, recovered.size());
        assertTrue(recovered.containsKey("room-valid"));
        assertArrayEquals(validPayload, recovered.get("room-valid"));
        assertFalse(recovered.containsKey("room-corrupt"));

        // Verify corrupted file was moved to .wal.corrupted
        Path expectedQuarantinePath = directory.resolve("room-corrupt.wal.corrupted");
        assertTrue(Files.exists(expectedQuarantinePath), "Corrupted WAL must be moved to .wal.corrupted");
        assertFalse(Files.exists(corruptWalPath), "Original corrupted .wal must no longer exist");
        assertTrue(Files.exists(directory.resolve("room-bad-header.wal.corrupted")));
        assertFalse(Files.exists(badHeader));
        assertTrue(withRoomLock(roomCoordinator, "room-bad-header",
                () -> store.recoverLatest("room-bad-header")).isEmpty());

        // Verify store remains healthy
        assertTrue(store.isHealthy(), "Store should be healthy after recoverAll isolates corrupted WAL");
        assertNull(store.lastError(), "Store lastError should be null");
    }

    /**
     * Protects the contract that quarantining one room does not clear lastError() while another
     * room still has a write failure or store-wide recovery has failed.
     */
    @Test
    void quarantineDoesNotClearLastErrorWhenAnotherRoomStillHasWriteFailureOrRecoveryFailed() throws Exception {
        EncryptionKeyring keys = keyring("current", (byte) 48);
        EncryptedWalStore store = new EncryptedWalStore(directory.resolve("multi-fail"), keys, roomCoordinator);

        withRoomLock(roomCoordinator, "room-still-failed", () -> {
            store.recordWriteFailure("room-still-failed", new PersistenceException("disk full"));
            return null;
        });
        withRoomLock(roomCoordinator, "room-quarantined", () -> {
            store.recordWriteFailure("room-quarantined", new PersistenceException("corrupt wal"));
            store.quarantine("room-quarantined", "quarantined");
            return null;
        });

        assertFalse(store.isHealthy());
        assertEquals(1, store.unsavedRoomCount());
        assertNotNull(store.lastError(), "lastError() must remain non-null while another room has a write failure");

        Path keyDir = directory.resolve("recovery-fail");
        EncryptedWalStore writer = new EncryptedWalStore(keyDir, keyring("old", (byte) 49), roomCoordinator);
        withRoomLock(roomCoordinator, "room-missing-key", () -> {
            writer.commit(writer.prepare("room-missing-key"), "state".getBytes(StandardCharsets.UTF_8));
            return null;
        });

        EncryptedWalStore missingKeyStore = new EncryptedWalStore(keyDir, keyring("current", (byte) 50), roomCoordinator);
        assertThrows(MissingPersistenceKeyException.class, missingKeyStore::recoverAll);
        assertFalse(missingKeyStore.isHealthy());
        assertNotNull(missingKeyStore.lastError());

        withRoomLock(roomCoordinator, "room-missing-key", () -> {
            missingKeyStore.quarantine("room-missing-key", "quarantine after missing key");
            return null;
        });
        assertFalse(missingKeyStore.isHealthy());
        assertNotNull(missingKeyStore.lastError(), "lastError() must remain non-null when recoveryFailed is true");
    }

    /**
     * Protects the contract that independent rooms can prepare, commit, checkpoint, and recover
     * concurrently without cross-room interference when each room is driven under its own lock.
     */
    @Test
    void concurrentDistinctRoomsPersistAndRecoverIndependently() throws Exception {
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring("current", (byte) 47), roomCoordinator);
        int roomCount = 6;
        CountDownLatch ready = new CountDownLatch(roomCount);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(roomCount)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < roomCount; i++) {
                String roomId = "concurrent-room-" + i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try (LockLease _ = roomCoordinator.acquire(roomId)) {
                        byte[] first = (roomId + "-v1").getBytes(StandardCharsets.UTF_8);
                        store.commit(store.prepare(roomId), first);
                        byte[] second = (roomId + "-v2").getBytes(StandardCharsets.UTF_8);
                        store.checkpoint(roomId, second);
                        byte[] third = (roomId + "-v3").getBytes(StandardCharsets.UTF_8);
                        store.commit(store.prepare(roomId), third);
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }

        EncryptedWalStore recovered = new EncryptedWalStore(directory, keyring("current", (byte) 47), roomCoordinator);
        Map<String, byte[]> all = recovered.recoverAll();
        assertEquals(roomCount, all.size());
        for (int i = 0; i < roomCount; i++) {
            String roomId = "concurrent-room-" + i;
            assertEquals(roomId + "-v3", new String(all.get(roomId), StandardCharsets.UTF_8));
        }
        assertTrue(recovered.isHealthy());
    }

    /**
     * Protects the contract that delete works even when the target room or another room is in failedRooms,
     * and clears failed/checkpoint tracking for the deleted room on success.
     */
    @Test
    void deleteSucceedsForFailedRoomOrWhileAnotherRoomIsFailedAndClearsTracking() {
        AtomicReference<String> failRoom = new AtomicReference<>();
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring("current", (byte) 51),
                (point, roomId) -> {
                    if (roomId.equals(failRoom.get()) && point == WalFaultPoint.BEFORE_COMMIT_WRITE) {
                        throw new PersistenceException("injected commit outage");
                    }
                }, roomCoordinator);

        withRoomLock(roomCoordinator, "room-a", () -> {
            store.commit(store.prepare("room-a"), "a-v1".getBytes(StandardCharsets.UTF_8));
            return null;
        });
        withRoomLock(roomCoordinator, "room-b", () -> {
            store.commit(store.prepare("room-b"), "b-v1".getBytes(StandardCharsets.UTF_8));
            return null;
        });

        withRoomLock(roomCoordinator, "room-a", () -> {
            WalTransaction failedTx = store.prepare("room-a");
            failRoom.set("room-a");
            assertThrows(PersistenceException.class,
                    () -> store.commit(failedTx, "a-v2".getBytes(StandardCharsets.UTF_8)));
            failRoom.set(null);
            return null;
        });

        assertFalse(store.isHealthy());
        assertFalse(store.isHealthy("room-a"));
        assertTrue(store.isHealthy("room-b"));
        assertEquals(1, store.unsavedRoomCount());
        withRoomLock(roomCoordinator, "room-a", () -> {
            assertTrue(store.requiresCheckpoint("room-a"));
            return null;
        });

        // Deleting a different healthy room succeeds while room-a remains failed
        withRoomLock(roomCoordinator, "room-b", () -> {
            store.delete("room-b");
            assertTrue(store.recoverLatest("room-b").isEmpty());
            return null;
        });
        assertEquals(1, store.unsavedRoomCount());
        assertFalse(store.isHealthy());

        // Deleting the failed room itself succeeds and clears its failed/checkpoint tracking
        withRoomLock(roomCoordinator, "room-a", () -> {
            store.delete("room-a");
            assertTrue(store.recoverLatest("room-a").isEmpty());
            assertFalse(store.requiresCheckpoint("room-a"));
            return null;
        });
        assertTrue(store.isHealthy("room-a"));
        assertEquals(0, store.unsavedRoomCount());
        assertTrue(store.isHealthy());
        assertNull(store.lastError());
    }

    /**
     * Protects the contract that a transient delete failure on an uncommitted PREPARE-only WAL
     * during recoverAll() logs and clears tracking without quarantining or aborting startup.
     */
    @Test
    void recoverAllContinuesWithoutQuarantiningOrWedgingWhenUncommittedWalDeleteFails() {
        EncryptionKeyring keys = keyring("current", (byte) 52);
        EncryptedWalStore writer = new EncryptedWalStore(directory, keys, roomCoordinator);
        withRoomLock(roomCoordinator, "room-uncommitted", () -> writer.prepare("room-uncommitted"));
        byte[] validPayload = "valid-state".getBytes(StandardCharsets.UTF_8);
        withRoomLock(roomCoordinator, "room-valid", () -> {
            writer.commit(writer.prepare("room-valid"), validPayload);
            return null;
        });

        EncryptedWalStore recovering = new EncryptedWalStore(directory, keys, (point, roomId) -> {
            if (point == WalFaultPoint.BEFORE_DELETE && "room-uncommitted".equals(roomId)) {
                throw new PersistenceException("injected delete failure");
            }
        }, roomCoordinator);

        Map<String, byte[]> recovered = assertDoesNotThrow(recovering::recoverAll);

        assertEquals(1, recovered.size());
        assertArrayEquals(validPayload, recovered.get("room-valid"));
        assertTrue(recovering.isHealthy());
        assertTrue(recovering.isHealthy("room-uncommitted"));
        assertEquals(0, recovering.unsavedRoomCount());
        assertFalse(withRoomLock(roomCoordinator, "room-uncommitted",
                () -> recovering.requiresCheckpoint("room-uncommitted")));
        assertNull(recovering.lastError());
        assertTrue(Files.exists(directory.resolve("room-uncommitted.wal")));
        assertFalse(Files.exists(directory.resolve("room-uncommitted.wal.corrupted")));
    }

    /**
     * Protects the contract that all per-room EncryptedWalStore operations require the caller
     * to hold the RoomCoordinator lock for the target room, while health checks stay lock-free.
     */
    @Test
    void perRoomOperationsWithoutRoomCoordinatorLockThrowIllegalStateException() {
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring("current", (byte) 53), roomCoordinator);
        String roomId = "room-lock-required";
        WalTransaction tx = withRoomLock(roomCoordinator, roomId, () -> store.prepare(roomId));
        byte[] payload = "state".getBytes(StandardCharsets.UTF_8);

        assertPerRoomOperationsThrowIllegalState(store, roomId, tx, payload);

        // Holding a different room's lock must also reject operations for roomId
        withRoomLock(roomCoordinator, "different-room", () -> {
            assertPerRoomOperationsThrowIllegalState(store, roomId, tx, payload);
            return null;
        });

        // Health queries remain lock-free status checks
        assertTrue(store.isHealthy());
        assertTrue(store.isHealthy(roomId));
    }

    private static void assertPerRoomOperationsThrowIllegalState(
            EncryptedWalStore store, String roomId, WalTransaction tx, byte[] payload) {
        assertThrows(IllegalStateException.class, () -> store.prepare(roomId));
        assertThrows(IllegalStateException.class, () -> store.commit(tx, payload));
        assertThrows(IllegalStateException.class, () -> store.recoverLatest(roomId));
        assertThrows(IllegalStateException.class, () -> store.hasCommittedState(roomId));
        assertThrows(IllegalStateException.class, () -> store.compact(roomId, payload));
        assertThrows(IllegalStateException.class, () -> store.checkpoint(roomId, payload));
        assertThrows(IllegalStateException.class, () -> store.delete(roomId));
        assertThrows(IllegalStateException.class, () -> store.quarantine(roomId, "reason"));
        assertThrows(IllegalStateException.class, () -> store.clearRoomTracking(roomId));
        assertThrows(IllegalStateException.class, () -> store.requiresCheckpoint(roomId));
        assertThrows(IllegalStateException.class, () -> store.walSize(roomId));
        assertThrows(IllegalStateException.class,
                () -> store.recordWriteFailure(roomId, new PersistenceException("failure")));
    }

    @Test
    void flippingFinalByteInsideGcmTagFailsAuthentication() throws Exception {
        byte[] wal = commitAndReadWal("room-tag-tamper", "tag-payload");
        wal[wal.length - 1] ^= 1;

        assertTamperedWalFailsClosed("room-tag-tamper", wal, "WAL authentication or structure validation failed");
    }

    @Test
    void flippingCiphertextPayloadByteFailsAuthentication() throws Exception {
        byte[] wal = commitAndReadWal("room-body-tamper", "ciphertext-payload");
        wal[wal.length - GCM_TAG_BYTES - 1] ^= 1;

        assertTamperedWalFailsClosed("room-body-tamper", wal, "WAL authentication or structure validation failed");
    }

    @Test
    void flippingAuthenticatedSequenceByteFailsAuthentication() throws Exception {
        byte[] wal = commitAndReadWal("room-aad-tamper", "aad-payload");
        int commitFrameStart = frameStarts(wal).get(1);
        wal[commitFrameStart + FRAME_HEADER_BYTES + Long.BYTES - 1] ^= 1;

        assertTamperedWalFailsClosed("room-aad-tamper", wal, "WAL authentication or structure validation failed");
    }

    @Test
    void flippingFrameMagicIsReportedAsCorruptFrameHeader() throws Exception {
        byte[] wal = commitAndReadWal("room-magic-tamper", "magic-payload");
        wal[0] ^= 1;

        assertTamperedWalFailsClosed("room-magic-tamper", wal, "Corrupt WAL frame header for room room-magic-tamper");
    }

    @Test
    void walWithMissingTransactionFramesIsRejectedAsGapped() throws Exception {
        byte[] wal = commitThreeTransactions("room-gapped");
        List<byte[]> frames = frames(wal);

        byte[] gapped = concatenate(frames.get(0), frames.get(1), frames.get(4), frames.get(5));

        assertTamperedWalFailsClosed("room-gapped", gapped, "WAL sequence is missing or reordered for room room-gapped");
    }

    @Test
    void walWithReorderedTransactionsIsRejected() throws Exception {
        byte[] wal = commitThreeTransactions("room-reordered");
        List<byte[]> frames = frames(wal);

        byte[] reordered = concatenate(frames.get(2), frames.get(3), frames.get(0), frames.get(1));

        assertTamperedWalFailsClosed("room-reordered", reordered,
                "WAL sequence is missing or reordered for room room-reordered");
    }

    @Test
    void commitOfSupersededTransactionIsRejectedAndDegradesRoom() {
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring("current", (byte) 60), roomCoordinator);

        withRoomLock(roomCoordinator, "room-stale", () -> {
            WalTransaction stale = store.prepare("room-stale");
            store.prepare("room-stale");

            PersistenceException failure = assertThrows(PersistenceException.class,
                    () -> store.commit(stale, new byte[]{1}));
            assertEquals("Transaction is no longer the active room transaction", failure.getMessage());
            return null;
        });

        assertFalse(store.isHealthy("room-stale"));
    }

    @Test
    void committingTheSameTransactionTwiceIsRejected() {
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring("current", (byte) 60), roomCoordinator);

        withRoomLock(roomCoordinator, "room-double-commit", () -> {
            WalTransaction transaction = store.prepare("room-double-commit");
            store.commit(transaction, new byte[]{1});

            PersistenceException failure = assertThrows(PersistenceException.class,
                    () -> store.commit(transaction, new byte[]{2}));
            assertEquals("Transaction is no longer the active room transaction", failure.getMessage());
            return null;
        });
    }

    @Test
    void flushFailureAfterWriteDegradesRoomUntilCheckpointRepairsIt() {
        AtomicReference<WalFaultPoint> failure = new AtomicReference<>();
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring("current", (byte) 61),
                (point, roomId) -> {
                    if (point == failure.get()) throw new PersistenceException("injected " + point);
                }, roomCoordinator);

        withRoomLock(roomCoordinator, "room-flush", () -> {
            WalTransaction transaction = store.prepare("room-flush");
            failure.set(WalFaultPoint.BEFORE_FLUSH);

            PersistenceException thrown = assertThrows(PersistenceException.class,
                    () -> store.commit(transaction, new byte[]{1}));
            assertEquals("injected BEFORE_FLUSH", thrown.getMessage());
            failure.set(null);

            assertFalse(store.isHealthy("room-flush"));
            assertTrue(store.requiresCheckpoint("room-flush"));
            store.checkpoint("room-flush", new byte[]{2});
            assertTrue(store.isHealthy("room-flush"));
            assertFalse(store.requiresCheckpoint("room-flush"));
            assertArrayEquals(new byte[]{2}, store.recoverLatest("room-flush").orElseThrow());
            return null;
        });
    }

    @Test
    void deleteFailsClosedAndPreservesWalAfterStartupRecoveryFailed() throws Exception {
        EncryptedWalStore writer = new EncryptedWalStore(directory, keyring("old", (byte) 62), roomCoordinator);
        withRoomLock(roomCoordinator, "room-locked-out", () -> {
            writer.commit(writer.prepare("room-locked-out"), new byte[]{1});
            return null;
        });
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring("current", (byte) 63), roomCoordinator);
        assertThrows(MissingPersistenceKeyException.class, store::recoverAll);

        withRoomLock(roomCoordinator, "room-locked-out", () -> {
            PersistenceException failure =
                    assertThrows(PersistenceException.class, () -> store.delete("room-locked-out"));
            assertEquals("Persistence recovery failed", failure.getMessage());
            return null;
        });

        assertTrue(Files.exists(directory.resolve("room-locked-out.wal")));
    }

    @Test
    void recoverAllFailsClosedWhenStoreIsAlreadyUnhealthy() {
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring("current", (byte) 64), roomCoordinator);
        withRoomLock(roomCoordinator, "room-unsaved", () -> {
            store.recordWriteFailure("room-unsaved", new PersistenceException("disk full"));
            return null;
        });

        PersistenceException failure = assertThrows(PersistenceException.class, store::recoverAll);

        assertEquals("Persistence needs a full checkpoint before further appends", failure.getMessage());
    }

    @Test
    void walSizeTracksTheFileOnDisk() {
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring("current", (byte) 65), roomCoordinator);
        Path wal = directory.resolve("room-size.wal");

        withRoomLock(roomCoordinator, "room-size", () -> {
            assertEquals(0L, store.walSize("room-size"));

            WalTransaction transaction = store.prepare("room-size");
            long afterPrepare = store.walSize("room-size");
            assertTrue(afterPrepare > 0);
            assertEquals(sizeOf(wal), afterPrepare);

            store.commit(transaction, new byte[100]);
            long afterCommit = store.walSize("room-size");
            assertTrue(afterCommit > afterPrepare + 100);
            assertEquals(sizeOf(wal), afterCommit);

            store.compact("room-size", new byte[1]);
            assertTrue(store.walSize("room-size") < afterCommit);
            assertEquals(sizeOf(wal), store.walSize("room-size"));

            store.delete("room-size");
            assertEquals(0L, store.walSize("room-size"));
            return null;
        });
    }

    @Test
    void clearRoomTrackingForgetsCachedSequenceFailureAndCheckpointRequirement() {
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring("current", (byte) 66), roomCoordinator);
        Path wal = directory.resolve("room-forgotten.wal");

        withRoomLock(roomCoordinator, "room-forgotten", () -> {
            store.checkpoint("room-forgotten", new byte[]{1});
            deleteFile(wal);
            store.recordWriteFailure("room-forgotten", new PersistenceException("unavailable"));
            assertFalse(store.isHealthy("room-forgotten"));
            assertTrue(store.requiresCheckpoint("room-forgotten"));

            store.clearRoomTracking("room-forgotten");

            assertTrue(store.isHealthy("room-forgotten"));
            assertFalse(store.requiresCheckpoint("room-forgotten"));
            assertEquals(1L, store.prepare("room-forgotten").prepareSequence(),
                    "The cached post-checkpoint sequence is forgotten, so numbering restarts for the new file");
            return null;
        });
    }

    private byte[] commitAndReadWal(String roomId, String payload) throws Exception {
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring("current", (byte) 67), roomCoordinator);
        withRoomLock(roomCoordinator, roomId, () -> {
            store.commit(store.prepare(roomId), payload.getBytes(StandardCharsets.UTF_8));
            return null;
        });
        return Files.readAllBytes(directory.resolve(roomId + ".wal"));
    }

    private byte[] commitThreeTransactions(String roomId) throws Exception {
        EncryptedWalStore store = new EncryptedWalStore(directory, keyring("current", (byte) 67), roomCoordinator);
        withRoomLock(roomCoordinator, roomId, () -> {
            for (byte value = 1; value <= 3; value++) {
                store.commit(store.prepare(roomId), new byte[]{value});
            }
            return null;
        });
        return Files.readAllBytes(directory.resolve(roomId + ".wal"));
    }

    /** Writes {@code wal} as the room's file and asserts that a fresh store refuses it with {@code message}. */
    private void assertTamperedWalFailsClosed(String roomId, byte[] wal, String message) throws Exception {
        Files.write(directory.resolve(roomId + ".wal"), wal);
        EncryptedWalStore recovering = new EncryptedWalStore(directory, keyring("current", (byte) 67), roomCoordinator);

        withRoomLock(roomCoordinator, roomId, () -> {
            PersistenceException failure =
                    assertThrows(PersistenceException.class, () -> recovering.recoverLatest(roomId));
            assertEquals(message, failure.getMessage());
            return null;
        });

        assertFalse(recovering.isHealthy(roomId));
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteFile(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Integer> frameStarts(byte[] wal) {
        List<Integer> starts = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(wal);
        while (buffer.hasRemaining()) {
            starts.add(buffer.position());
            buffer.getInt();
            int bodyLength = buffer.getInt();
            buffer.position(buffer.position() + bodyLength);
        }
        return starts;
    }

    private static List<byte[]> frames(byte[] wal) {
        List<Integer> starts = frameStarts(wal);
        List<byte[]> frames = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : wal.length;
            frames.add(Arrays.copyOfRange(wal, starts.get(i), end));
        }
        return frames;
    }

    private static byte[] concatenate(byte[]... parts) {
        int length = Arrays.stream(parts).mapToInt(part -> part.length).sum();
        ByteBuffer buffer = ByteBuffer.allocate(length);
        for (byte[] part : parts) {
            buffer.put(part);
        }
        return buffer.array();
    }

    private static EncryptionKeyring keyring(String id, byte value) {
        return new EncryptionKeyring(id, Map.of(id, new SecretKeySpec(filledKey(value), "AES")));
    }

    private static byte[] filledKey(byte value) {
        byte[] key = new byte[32];
        Arrays.fill(key, value);
        return key;
    }
}
