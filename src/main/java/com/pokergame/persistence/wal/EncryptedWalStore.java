package com.pokergame.persistence.wal;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import com.pokergame.persistence.config.PersistenceException;
import com.pokergame.util.LockLease;
import com.pokergame.util.RoomCoordinator;
import java.nio.file.AccessDeniedException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Saves a poker game's history to disk using an encrypted Write-Ahead Log (WAL), with one file per room.
 * This lets us exactly restore a game after a server crash.
 * <p>
 * Each save is encrypted independently. This way, if the server dies right in the middle of writing a file,
 * we can tell the difference between an interrupted save and a corrupted file. We also tie each save to its specific
 * room, so nobody can copy a save file from one room to another.
 * </p>
 * <p>
 * A failed write disables ordinary appends for that room until a current full checkpoint repairs
 * or deletes the affected WAL; unaffected rooms continue operating normally. Services can continue
 * in memory while recovery protection for the affected room is degraded.
 * </p>
 * <p>
 * Callers must hold the room lock via {@link RoomCoordinator} for per-room operations.
 * </p>
 */
public final class EncryptedWalStore {
    private static final Logger logger = LoggerFactory.getLogger(EncryptedWalStore.class);
    private static final int MAGIC = 0x504B574C; // PKWL
    private static final int NONCE_LENGTH = 12;
    private static final int MAX_FRAME_LENGTH = 64 * 1024 * 1024;

    private final Path directory;
    private final EncryptionKeyring keyring;
    private final WalFaultInjector faultInjector;
    private final RoomCoordinator roomCoordinator;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, Long> nextSequences = new ConcurrentHashMap<>();
    private final Set<String> failedRooms = ConcurrentHashMap.newKeySet();
    private final Set<String> checkpointRooms = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean recoveryFailed = new AtomicBoolean(false);
    private volatile String lastError;

    /**
     * Creates a regular store for saving games in production.
     *
     * @param directory       the folder to save files in
     * @param keyring         the encryption keys to use
     * @param roomCoordinator coordinator managing per-room concurrency locks
     * @throws PersistenceException if we can't create or write to the folder
     */
    public EncryptedWalStore(Path directory, EncryptionKeyring keyring, RoomCoordinator roomCoordinator) {
        this(directory, keyring, WalFaultInjector.NONE, roomCoordinator);
    }

    /**
     * Creates a store that lets us intentionally cause crashes during testing.
     *
     * @param directory       the folder to save files in
     * @param keyring         the encryption keys to use
     * @param faultInjector   a tool to simulate crashes for our tests
     * @param roomCoordinator coordinator managing per-room concurrency locks
     * @throws PersistenceException if we can't create or write to the folder
     */
    public EncryptedWalStore(Path directory, EncryptionKeyring keyring, WalFaultInjector faultInjector,
            RoomCoordinator roomCoordinator) {
        this.directory = directory.toAbsolutePath().normalize();
        this.keyring = keyring;
        this.faultInjector = faultInjector;
        this.roomCoordinator = Objects.requireNonNull(roomCoordinator, "roomCoordinator must not be null");
        try {
            Files.createDirectories(this.directory);
            if (!Files.isDirectory(this.directory) || !Files.isWritable(this.directory)) {
                throw new PersistenceException("Persistence directory is not writable");
            }
            cleanOrphanedCompactingFiles();
        } catch (IOException e) {
            throw new PersistenceException("Cannot initialize persistence directory", e);
        }
    }

    /**
     * Writes a "prepare" marker to the log before we update the game's state.
     * If the server crashes before we can write the final "commit", we'll just ignore this prepare when we restart.
     * Caller must hold the room lock for {@code roomId}.
     *
     * @param roomId the room we are updating
     * @return an ID needed to save the final game state
     * @throws PersistenceException if we can't save to the file or there's an error
     */
    public WalTransaction prepare(String roomId) {
        validateRoomId(roomId);
        roomCoordinator.assertHeldByCurrentThread(roomId);
        requireHealthy(roomId);
        if (requiresCheckpoint(roomId)) {
            throw new PersistenceException("WAL needs a full checkpoint before appending");
        }
        try {
            long sequence = nextSequence(roomId);
            UUID transactionId = UUID.randomUUID();
            append(roomId, sequence, transactionId, RecordType.PREPARE, new byte[0]);
            nextSequences.put(roomId, sequence + 1);
            return new WalTransaction(roomId, transactionId, sequence);
        } catch (RuntimeException e) {
            recordWriteFailure(roomId, e);
            throw e;
        }
    }

    /**
     * Writes the final game state to the log. This only works if it exactly matches the "prepare" marker we just made.
     * This rule stops old, delayed saves from accidentally overwriting newer saves.
     * Caller must hold the room lock for {@code transaction.roomId()}.
     *
     * @param transaction the ID from the "prepare" step
     * @param payload     the game state data to save
     * @throws PersistenceException if we have trouble writing or encrypting the data
     */
    public void commit(WalTransaction transaction, byte[] payload) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        validateRoomId(transaction.roomId());
        roomCoordinator.assertHeldByCurrentThread(transaction.roomId());
        requireHealthy(transaction.roomId());
        try {
            long expected = transaction.prepareSequence() + 1;
            long sequence = nextSequence(transaction.roomId());
            if (sequence != expected) {
                throw new PersistenceException("Transaction is no longer the active room transaction");
            }
            append(transaction.roomId(), sequence, transaction.transactionId(), RecordType.COMMIT, payload.clone());
            nextSequences.put(transaction.roomId(), sequence + 1);
        } catch (RuntimeException e) {
            recordWriteFailure(transaction.roomId(), e);
            throw e;
        }
    }

    /**
     * Reads the file to find the latest valid game state.
     * It's okay if the very last save got cut off during a crash, but any other file issues will cause this to fail safely.
     * Caller must hold the room lock for {@code roomId}.
     *
     * @param roomId the room to restore
     * @return the latest saved game data, or empty if there's no saved data
     * @throws PersistenceException if the save file looks corrupted or tampered with
     */
    public Optional<byte[]> recoverLatest(String roomId) {
        validateRoomId(roomId);
        roomCoordinator.assertHeldByCurrentThread(roomId);
        deleteCompactingFileQuietly(roomId);
        Path path = walPath(roomId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            Recovery recovery = readWal(path, roomId);
            nextSequences.put(roomId, recovery.nextSequence());
            if (recovery.incompleteTail()) checkpointRooms.add(roomId);
            return Optional.ofNullable(recovery.latestPayload()).map(byte[]::clone);
        } catch (RuntimeException e) {
            recordWriteFailure(roomId, e);
            throw e;
        }
    }

    /**
     * Restores all rooms by reading their save files in alphabetical order, acquiring each room's
     * {@link RoomCoordinator} lock for its recovery or cleanup step.
     * This ensures the server starts up fully before players can join.
     *
     * @return a map of room IDs to their saved game data
     * @throws PersistenceException if store-level directory enumeration fails, a required decryption key
     *                              is missing, or the store is unhealthy;
     *                              per-room corrupted or unreadable WAL files are quarantined
     *                              ({@code <roomId>.wal.corrupted}) so remaining rooms can recover
     */
    public Map<String, byte[]> recoverAll() {
        if (!isHealthy()) {
            throw new PersistenceException("Persistence needs a full checkpoint before further appends");
        }
        cleanOrphanedCompactingFiles();
        Map<String, byte[]> recovered = new HashMap<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".wal"))
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                String fileName = path.getFileName().toString();
                String roomId = fileName.substring(0, fileName.length() - 4);
                try (LockLease _ = roomCoordinator.acquire(roomId)) {
                    Optional<byte[]> latest;
                    try {
                        latest = recoverLatest(roomId);
                    } catch (MissingPersistenceKeyException missingKey) {
                        recoveryFailed.set(true);
                        lastError = missingKey.getClass().getSimpleName();
                        throw missingKey;
                    } catch (RuntimeException e) {
                        logger.error("Corrupted WAL file detected during recoverAll for room {}: {}", roomId, e.getMessage());
                        quarantine(roomId, e.getMessage());
                        continue;
                    }
                    if (latest.isPresent()) {
                        recovered.put(roomId, latest.get());
                    } else {
                        logger.info("Cleaning up uncommitted WAL file for room {}", roomId);
                        try {
                            delete(roomId);
                        } catch (RuntimeException e) {
                            logger.warn("Failed to delete uncommitted WAL file for room {} during recovery; skipping",
                                    roomId, e);
                            clearRoomTracking(roomId);
                        }
                    }
                }
            }
            return Map.copyOf(recovered);
        } catch (IOException e) {
            PersistenceException failure = new PersistenceException("Cannot enumerate persistence WALs", e);
            recoveryFailed.set(true);
            lastError = failure.getClass().getSimpleName();
            throw failure;
        }
    }

    /**
     * Cleans up a room's save file by deleting all the old history and just keeping the newest game state.
     * This is done safely so a crash won't lose data. It also encrypts the file with the newest key.
     * Caller must hold the room lock for {@code roomId}.
     *
     * @param roomId        the room to clean up
     * @param latestPayload the latest game data to keep
     * @throws PersistenceException if we can't safely swap out the old file
     */
    public void compact(String roomId, byte[] latestPayload) {
        validateRoomId(roomId);
        roomCoordinator.assertHeldByCurrentThread(roomId);
        requireHealthy(roomId);
        checkpoint(roomId, latestPayload);
    }

    /**
     * Replaces an uncertain WAL with a fully flushed current state, including when
     * ordinary writes are disabled. Only this room's failure is cleared on success.
     * Caller must hold the room lock for {@code roomId}.
     *
     * @param roomId room whose current state is being saved
     * @param latestPayload complete accepted state image or deletion tombstone
     */
    public void checkpoint(String roomId, byte[] latestPayload) {
        validateRoomId(roomId);
        roomCoordinator.assertHeldByCurrentThread(roomId);
        long startMs = System.currentTimeMillis();
        long bytesBefore = walSize(roomId);
        try {
            Path replacement = directory.resolve(roomId + ".wal.compacting");
            Files.deleteIfExists(replacement);
            writeFreshWal(replacement, roomId, latestPayload);
            faultInjector.check(WalFaultPoint.BEFORE_COMPACTION_REPLACE, roomId);
            replaceAtomically(replacement, walPath(roomId));
            forceDirectory();
            nextSequences.put(roomId, 3L);
            checkpointRooms.remove(roomId);
            failedRooms.remove(roomId);
            long bytesAfter = walSize(roomId);
            long durationMs = System.currentTimeMillis() - startMs;
            logger.info("WAL compacted room={} bytesBefore={} bytesAfter={} ms={}", roomId, bytesBefore, bytesAfter, durationMs);
        } catch (IOException e) {
            deleteCompactingFileQuietly(roomId);
            PersistenceException failure = new PersistenceException("Cannot compact WAL for room " + roomId, e);
            recordWriteFailure(roomId, failure);
            throw failure;
        } catch (RuntimeException e) {
            deleteCompactingFileQuietly(roomId);
            recordWriteFailure(roomId, e);
            throw e;
        }
    }

    /**
     * Deletes a room's save file. We only do this after the game is fully over.
     * Doing it in this order ensures a crash won't bring a dead room back to life.
     * Allowed even when this or another room has an outstanding write failure, and clears
     * failure and checkpoint tracking for {@code roomId} on success.
     * Caller must hold the room lock for {@code roomId}.
     *
     * @param roomId the room to delete
     * @throws PersistenceException if we fail to delete the file or startup recovery failed
     */
    public void delete(String roomId) {
        validateRoomId(roomId);
        roomCoordinator.assertHeldByCurrentThread(roomId);
        if (recoveryFailed.get()) {
            throw new PersistenceException("Persistence recovery failed");
        }
        try {
            faultInjector.check(WalFaultPoint.BEFORE_DELETE, roomId);
            Files.deleteIfExists(walPath(roomId));
            Files.deleteIfExists(directory.resolve(roomId + ".wal.compacting"));
            forceDirectory();
            clearRoomTracking(roomId);
            logger.debug("WAL deleted room={}", roomId);
        } catch (IOException e) {
            PersistenceException failure = new PersistenceException("Cannot delete WAL for room " + roomId, e);
            recordWriteFailure(roomId, failure);
            throw failure;
        } catch (RuntimeException e) {
            recordWriteFailure(roomId, e);
            throw e;
        }
    }

    /**
     * Quarantines a corrupted WAL file for a specific room by moving it out of active recovery.
     * Internal tracking for the room is cleaned up so that the failure of this single room
     * does not prevent other rooms from operating or recovering.
     * Caller must hold the room lock for {@code roomId}.
     *
     * @param roomId the room whose corrupted WAL should be quarantined
     * @param reason diagnostic explanation for the quarantine
     * @return the path of the quarantined file
     * @throws PersistenceException if room ID validation fails or file move fails
     */
    public Path quarantine(String roomId, String reason) {
        validateRoomId(roomId);
        roomCoordinator.assertHeldByCurrentThread(roomId);
        Path wal = walPath(roomId);
        Path quarantinePath = directory.resolve(roomId + ".wal.corrupted");
        deleteCompactingFileQuietly(roomId);
        if (Files.exists(wal)) {
            try {
                try {
                    Files.move(wal, quarantinePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(wal, quarantinePath, StandardCopyOption.REPLACE_EXISTING);
                }
                forceDirectory();
            } catch (IOException e) {
                logger.error("Failed to move corrupted WAL for room {}: {}", roomId, e.getMessage(), e);
                throw new PersistenceException("Cannot quarantine corrupted WAL for room " + roomId, e);
            }
        }
        clearRoomTracking(roomId);
        logger.error("Quarantined corrupted WAL file for room {}: {}", roomId, reason);
        return quarantinePath;
    }

    /**
     * Clears in-memory sequence, checkpoint, and failure tracking for a room that is not
     * kept in active memory (for example after quarantine, deletion, or skipping an un-restored room
     * whose WAL delete failed during startup recovery).
     * Caller must hold the room lock for {@code roomId}.
     *
     * @param roomId the room whose tracking should be removed
     */
    public void clearRoomTracking(String roomId) {
        validateRoomId(roomId);
        roomCoordinator.assertHeldByCurrentThread(roomId);
        nextSequences.remove(roomId);
        checkpointRooms.remove(roomId);
        failedRooms.remove(roomId);
    }

    /**
     * Convenience alias for {@link #quarantine(String, String)}.
     * Caller must hold the room lock for {@code roomId}.
     *
     * @param roomId the room whose corrupted WAL should be quarantined
     * @param reason diagnostic explanation for the quarantine
     * @return the path of the quarantined file
     */
    public Path quarantineCorruptedWal(String roomId, String reason) {
        return quarantine(roomId, reason);
    }

    /**
     * Reports overall store health for the persistence health indicator.
     * Returns {@code true} only when startup recovery succeeded and no room has an outstanding write failure.
     *
     * @return {@code true} if startup recovery succeeded and no room has an outstanding write failure
     */
    public boolean isHealthy() {
        return !recoveryFailed.get() && failedRooms.isEmpty();
    }

    /**
     * Checks whether persistence is healthy for a specific room.
     * Returns {@code true} when startup recovery succeeded and {@code roomId} has no outstanding write failure,
     * independent of write failures in other rooms.
     *
     * @param roomId the room to check
     * @return {@code true} if ordinary persistence operations for {@code roomId} can proceed
     */
    public boolean isHealthy(String roomId) {
        return !recoveryFailed.get() && roomId != null && !failedRooms.contains(roomId);
    }

    /**
     * Returns whether a full image must replace this room's WAL before more appends.
     * Caller must hold the room lock for {@code roomId}.
     *
     * @param roomId the room to check
     * @return {@code true} if a full checkpoint is required before appending
     */
    public boolean requiresCheckpoint(String roomId) {
        roomCoordinator.assertHeldByCurrentThread(roomId);
        return checkpointRooms.contains(roomId);
    }

    /** Returns the number of rooms whose recovery protection is currently degraded. */
    public int unsavedRoomCount() {
        return failedRooms.size();
    }

    /**
     * Gets a safe error message without showing any sensitive game data or keys.
     *
     * @return the last error message, or null if everything is fine
     */
    public String lastError() {
        return isHealthy() ? null : lastError;
    }

    /**
     * Counts how many save files we have.
     *
     * @return the number of save files, or -1 if we can't read the folder
     */
    public long walCount() {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".wal")).count();
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Gets the size of a save file so we know when to clean it up.
     * Caller must hold the room lock for {@code roomId}.
     *
     * @param roomId the room to check
     * @return the file size in bytes, or a huge number if we can't read it
     */
    public long walSize(String roomId) {
        roomCoordinator.assertHeldByCurrentThread(roomId);
        try {
            Path path = walPath(roomId);
            return Files.exists(path) ? Files.size(path) : 0;
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    /** Reads the next sequence number from disk when not cached, in case of a prior crash. */
    private long nextSequence(String roomId) {
        Long cached = nextSequences.get(roomId);
        if (cached != null) {
            return cached;
        }
        Path path = walPath(roomId);
        long calculated = 1L;
        if (Files.exists(path)) {
            Recovery recovered = readWal(path, roomId);
            if (recovered.incompleteTail()) {
                checkpointRooms.add(roomId);
                throw new PersistenceException("WAL needs a full checkpoint before appending");
            }
            calculated = recovered.nextSequence();
        }
        nextSequences.put(roomId, calculated);
        return calculated;
    }

    private void append(String roomId, long sequence, UUID transactionId, RecordType type, byte[] payload) {
        faultInjector.check(type == RecordType.PREPARE ? WalFaultPoint.BEFORE_PREPARE_WRITE
                : WalFaultPoint.BEFORE_COMMIT_WRITE, roomId);
        byte[] frame = encodeFrame(roomId, sequence, transactionId, type, payload);
        boolean createsWal = !Files.exists(walPath(roomId));
        try (FileChannel channel = FileChannel.open(walPath(roomId), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer bytes = ByteBuffer.wrap(frame);
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            faultInjector.check(WalFaultPoint.BEFORE_FLUSH, roomId);
            channel.force(true);
            if (createsWal) {
                forceDirectory();
            }
        } catch (IOException e) {
            throw new PersistenceException("Cannot flush WAL for room " + roomId, e);
        }
    }

    private byte[] encodeFrame(String roomId, long sequence, UUID transactionId, RecordType type, byte[] payload) {
        try {
            String keyId = keyring.currentKeyId();
            byte[] nonce = new byte[NONCE_LENGTH];
            secureRandom.nextBytes(nonce);
            byte[] aad = associatedData(roomId, sequence, transactionId, type, keyId);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keyring.currentKey(), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad);
            byte[] ciphertext = cipher.doFinal(payload);

            ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
            try (DataOutputStream body = new DataOutputStream(bodyBytes)) {
                body.writeLong(sequence);
                body.writeLong(transactionId.getMostSignificantBits());
                body.writeLong(transactionId.getLeastSignificantBits());
                body.writeByte(type.code);
                writeUtf8(body, roomId);
                writeUtf8(body, keyId);
                body.write(nonce);
                body.writeInt(ciphertext.length);
                body.write(ciphertext);
            }
            byte[] encodedBody = bodyBytes.toByteArray();
            ByteBuffer frame = ByteBuffer.allocate(8 + encodedBody.length);
            frame.putInt(MAGIC).putInt(encodedBody.length).put(encodedBody);
            return frame.array();
        } catch (GeneralSecurityException | IOException e) {
            throw new PersistenceException("Cannot encrypt WAL record", e);
        }
    }

    private Recovery readWal(Path path, String expectedRoomId) {
        deleteCompactingFileQuietly(expectedRoomId);
        try {
            byte[] file = Files.readAllBytes(path);
            ByteBuffer bytes = ByteBuffer.wrap(file);
            List<WalRecord> records = new ArrayList<>();
            long previousSequence = 0;
            boolean incompleteTail = false;
            while (bytes.hasRemaining()) {
                if (bytes.remaining() < 8) {
                    incompleteTail = true;
                    long validLength = (long) bytes.position();
                    int partialHeaderLength = bytes.remaining();
                    truncateWalAt(path, validLength);
                    logger.warn("Truncated partial frame header of length {} at EOF for room {} to validLength {}",
                            partialHeaderLength, expectedRoomId, validLength);
                    break;
                }
                int magic = bytes.getInt();
                int frameLength = bytes.getInt();
                if (magic != MAGIC || frameLength <= 0 || frameLength > MAX_FRAME_LENGTH) {
                    throw new PersistenceException("Corrupt WAL frame header for room " + expectedRoomId);
                }
                if (bytes.remaining() < frameLength) {
                    incompleteTail = true;
                    long validLength = (long) bytes.position() - 8;
                    truncateWalAt(path, validLength);
                    logger.warn("Truncated torn trailing WAL frame for room {} to validLength {}", expectedRoomId, validLength);
                    break; // The save file was cut off right at the end (like from a power outage), which is okay. We just ignore this last partial save.
                }
                ByteBuffer frame = bytes.slice(bytes.position(), frameLength);
                bytes.position(bytes.position() + frameLength);
                WalRecord record = decodeFrame(frame, expectedRoomId);
                if (record.sequence() != previousSequence + 1) {
                    throw new PersistenceException("WAL sequence is missing or reordered for room " + expectedRoomId);
                }
                previousSequence = record.sequence();
                records.add(record);
            }

            byte[] latest = null;
            WalRecord pending = null;
            for (WalRecord record : records) {
                if (record.type() == RecordType.PREPARE) {
                    pending = record;
                } else {
                    if (pending == null || !pending.transactionId().equals(record.transactionId())
                            || record.sequence() != pending.sequence() + 1) {
                        throw new PersistenceException("Committed WAL transaction has no matching prepare");
                    }
                    latest = record.payload();
                    pending = null;
                }
            }
            return new Recovery(latest, previousSequence + 1, incompleteTail);
        } catch (IOException e) {
            throw new PersistenceException("Cannot read WAL for room " + expectedRoomId, e);
        }
    }

    private static void truncateWalAt(Path path, long validLength) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.truncate(validLength);
            channel.force(true);
        }
    }

    private WalRecord decodeFrame(ByteBuffer frame, String expectedRoomId) {
        try {
            long sequence = frame.getLong();
            UUID transactionId = new UUID(frame.getLong(), frame.getLong());
            RecordType type = RecordType.from(frame.get());
            String roomId = readUtf8(frame);
            String keyId = readUtf8(frame);
            if (!expectedRoomId.equals(roomId)) {
                throw new PersistenceException("WAL record belongs to a different room");
            }
            byte[] nonce = new byte[NONCE_LENGTH];
            frame.get(nonce);
            int ciphertextLength = frame.getInt();
            if (ciphertextLength < 16 || ciphertextLength != frame.remaining()) {
                throw new PersistenceException("Invalid encrypted WAL payload length");
            }
            byte[] ciphertext = new byte[ciphertextLength];
            frame.get(ciphertext);
            SecretKey key = keyring.key(keyId);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(associatedData(roomId, sequence, transactionId, type, keyId));
            return new WalRecord(sequence, transactionId, type, cipher.doFinal(ciphertext));
        } catch (PersistenceException e) {
            throw e;
        } catch (Exception e) {
            throw new PersistenceException("WAL authentication or structure validation failed", e);
        }
    }

    private byte[] associatedData(String roomId, long sequence, UUID transactionId, RecordType type, String keyId) {
        byte[] room = roomId.getBytes(StandardCharsets.UTF_8);
        byte[] key = keyId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer aad = ByteBuffer.allocate(8 + 16 + 1 + 2 + room.length + 2 + key.length);
        aad.putLong(sequence)
                .putLong(transactionId.getMostSignificantBits()).putLong(transactionId.getLeastSignificantBits())
                .put(type.code).putShort((short) room.length).put(room).putShort((short) key.length).put(key);
        return aad.array();
    }

    private void writeFreshWal(Path path, String roomId, byte[] payload) throws IOException {
        UUID transactionId = UUID.randomUUID();
        byte[] prepare = encodeFrame(roomId, 1, transactionId, RecordType.PREPARE, new byte[0]);
        byte[] commit = encodeFrame(roomId, 2, transactionId, RecordType.COMMIT, payload);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            for (byte[] frame : List.of(prepare, commit)) {
                faultInjector.check(frame == prepare ? WalFaultPoint.BEFORE_PREPARE_WRITE
                        : WalFaultPoint.BEFORE_COMMIT_WRITE, roomId);
                ByteBuffer bytes = ByteBuffer.wrap(frame);
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
            }
            faultInjector.check(WalFaultPoint.BEFORE_FLUSH, roomId);
            channel.force(true);
        }
    }

    private static void replaceAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            throw new PersistenceException("Atomic WAL replacement is not supported by this filesystem", e);
        }
    }

    private void forceDirectory() throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (AccessDeniedException e) {
            // Windows doesn't let us sync directories the same way Linux does. That's fine for local testing.
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                throw e;
            }
        }
    }

    private static void writeUtf8(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Short.MAX_VALUE) {
            throw new PersistenceException("WAL metadata value is too long");
        }
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static String readUtf8(ByteBuffer input) {
        int length = Short.toUnsignedInt(input.getShort());
        if (length > input.remaining()) {
            throw new PersistenceException("Truncated WAL metadata");
        }
        byte[] bytes = new byte[length];
        input.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private Path walPath(String roomId) {
        return directory.resolve(roomId + ".wal");
    }

    private void cleanOrphanedCompactingFiles() {
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".compacting")).toList()) {
                try {
                    Files.deleteIfExists(path);
                    logger.info("Cleaned up orphaned compaction file: {}", path.getFileName());
                } catch (IOException e) {
                    logger.warn("Could not delete orphaned compaction file: {}", path, e);
                }
            }
        } catch (IOException e) {
            logger.warn("Could not enumerate persistence directory to clean compaction files", e);
        }
    }

    private void deleteCompactingFileQuietly(String roomId) {
        try {
            Path compacting = directory.resolve(roomId + ".wal.compacting");
            if (Files.deleteIfExists(compacting)) {
                logger.info("Cleaned up compaction file for room {}", roomId);
            }
        } catch (IOException ignored) {
        }
    }

    /** Rejects characters that could cause path traversal in WAL filenames. */
    private static void validateRoomId(String roomId) {
        if (roomId == null || !roomId.matches("[A-Za-z0-9._-]+")) {
            throw new PersistenceException("Invalid room ID for WAL path");
        }
    }

    /** Rejects ordinary appends for a room until startup is healthy and the room has no outstanding write failure. */
    private void requireHealthy(String roomId) {
        if (!isHealthy(roomId)) {
            throw new PersistenceException("Persistence needs a full checkpoint before further appends");
        }
    }

    /**
     * Records a failed save without allowing a different room's successful retry to
     * clear it. Full checkpoint replacement is required before ordinary appends resume.
     * Caller must hold the room lock for {@code roomId}.
     *
     * @param roomId room whose current state has not been confirmed saved
     * @param failure what went wrong
     */
    public void recordWriteFailure(String roomId, RuntimeException failure) {
        roomCoordinator.assertHeldByCurrentThread(roomId);
        checkpointRooms.add(roomId);
        failedRooms.add(roomId);
        lastError = failure.getClass().getSimpleName();
    }

    /**
     * The two types of data pieces we save to the file. We always save a PREPARE first, and then a COMMIT.
     */
    private enum RecordType {
        PREPARE((byte) 1), COMMIT((byte) 2);
        private final byte code;

        /**
         * Gives the piece a number to save in the file.
         *
         * @param code the number for the file
         */
        RecordType(byte code) {
            this.code = code;
        }

        private static RecordType from(byte code) {
            for (RecordType value : values()) {
                if (value.code == code) return value;
            }
            throw new PersistenceException("Unknown WAL record type");
        }
    }

    /**
     * A temporary box to hold one piece of data that we read from the file.
     *
     * @param sequence      the number in the sequence
     * @param transactionId an ID linking a prepare and commit
     * @param type          whether this is a prepare or a commit
     * @param payload       the unencrypted game data
     */
    private record WalRecord(long sequence, UUID transactionId, RecordType type, byte[] payload) {
    }

    /**
     * The final result after reading through the whole save file.
     *
     * @param latestPayload the final valid game data, or null
     * @param nextSequence  the next number to use when saving new data
     * @param incompleteTail whether ignored partial bytes require replacement before appending
     */
    private record Recovery(byte[] latestPayload, long nextSequence, boolean incompleteTail) {
    }
}
