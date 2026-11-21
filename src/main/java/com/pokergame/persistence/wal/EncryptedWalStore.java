package com.pokergame.persistence.wal;

import static java.util.Objects.requireNonNull;

import com.pokergame.persistence.PersistenceException;
import com.pokergame.util.LockLease;
import com.pokergame.util.RoomCoordinator;
import com.pokergame.util.VisibleForTesting;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
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
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BYTES = 16;
    private static final int GCM_TAG_BITS = GCM_TAG_BYTES * Byte.SIZE;
    private static final int FRAME_HEADER_BYTES = 8; // magic + body length
    // Associated-data layout: sequence, transaction id (two longs), record type, then two length-prefixed strings.
    private static final int AAD_FIXED_BYTES = Long.BYTES + 2 * Long.BYTES + Byte.BYTES;
    private static final int AAD_LENGTH_PREFIX_BYTES = Short.BYTES;
    private static final long FIRST_SEQUENCE = 1L;
    // A fresh WAL written by a checkpoint holds exactly one prepare (1) and one commit (2), so appends resume at 3.
    private static final long CHECKPOINT_PREPARE_SEQUENCE = FIRST_SEQUENCE;
    private static final long CHECKPOINT_COMMIT_SEQUENCE = FIRST_SEQUENCE + 1;
    private static final long SEQUENCE_AFTER_CHECKPOINT = CHECKPOINT_COMMIT_SEQUENCE + 1;
    private static final String WAL_SUFFIX = ".wal";
    private static final String COMPACTING_SUFFIX = ".wal.compacting";
    private static final String CORRUPTED_SUFFIX = ".wal.corrupted";
    private static final Pattern VALID_ROOM_ID = Pattern.compile("[A-Za-z0-9._-]+");
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

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
    @VisibleForTesting
    public EncryptedWalStore(Path directory, EncryptionKeyring keyring, WalFaultInjector faultInjector,
            RoomCoordinator roomCoordinator) {
        requireNonNull(directory, "directory must not be null");
        requireNonNull(keyring, "keyring must not be null");
        requireNonNull(faultInjector, "faultInjector must not be null");
        requireNonNull(roomCoordinator, "roomCoordinator must not be null");
        this.directory = directory.toAbsolutePath().normalize();
        this.keyring = keyring;
        this.faultInjector = faultInjector;
        this.roomCoordinator = roomCoordinator;
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
     * Appends an empty {@code PREPARE} record to the room's WAL while the caller holds the room
     * lock; an unpaired prepare is ignored on startup recovery.
     *
     * @param roomId the room being updated
     * @return the prepared transaction to pass to {@link #commit}
     * @throws PersistenceException if the room is unhealthy, needs a checkpoint, or the write fails
     */
    public WalTransaction prepare(String roomId) {
        requireHealthyLockedRoom(roomId);
        if (requiresCheckpoint(roomId)) {
            throw new PersistenceException("WAL needs a full checkpoint before appending");
        }
        return runOrRecordFailure(roomId, "Cannot write WAL for room " + roomId, () -> {
            long sequence = nextSequence(roomId);
            UUID transactionId = UUID.randomUUID();
            append(roomId, sequence, transactionId, RecordType.PREPARE, new byte[0]);
            nextSequences.put(roomId, sequence + 1);
            return new WalTransaction(roomId, transactionId, sequence);
        });
    }

    /**
     * Appends the encrypted {@code COMMIT} record matching {@code transaction} while the caller
     * holds the room lock, rejecting stale or out-of-order transactions.
     *
     * @param transaction the prepared transaction returned by {@link #prepare}
     * @param payload     the serialized {@code RoomSnapshot} bytes to encrypt and save
     * @throws PersistenceException if the transaction is stale, the room is unhealthy, or the write fails
     */
    public void commit(WalTransaction transaction, byte[] payload) {
        requireNonNull(transaction, "transaction must not be null");
        String roomId = transaction.roomId();
        requireHealthyLockedRoom(roomId);
        runOrRecordFailure(roomId, "Cannot write WAL for room " + roomId, () -> {
            long expected = transaction.prepareSequence() + 1;
            long sequence = nextSequence(roomId);
            if (sequence != expected) {
                throw new PersistenceException("Transaction is no longer the active room transaction");
            }
            append(roomId, sequence, transaction.transactionId(), RecordType.COMMIT, payload);
            nextSequences.put(roomId, sequence + 1);
        });
    }

    /**
     * Reads the latest committed payload for {@code roomId}, truncating any crash-cut trailing
     * bytes (marking the room for a checkpoint) and removing leftover {@code .compacting} files.
     * Caller must hold the room lock.
     *
     * @param roomId the room to restore
     * @return the latest committed snapshot bytes, or empty if no committed state exists
     * @throws PersistenceException if the WAL is corrupted, tampered with, or its key is missing
     */
    public Optional<byte[]> recoverLatest(String roomId) {
        requireLockedRoom(roomId);
        deleteCompactingFileQuietly(roomId);
        Path path = walPath(roomId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        return runOrRecordFailure(roomId, "Cannot read WAL for room " + roomId, () -> {
            WalReadResult result = readWal(path, roomId);
            repairIncompleteTail(path, roomId, result);
            nextSequences.put(roomId, result.nextSequence());
            return Optional.ofNullable(result.latestPayload());
        });
    }

    /**
     * Returns whether {@code roomId}'s WAL contains at least one committed record, without
     * modifying the file or store tracking. Caller must hold the room lock.
     *
     * @param roomId the room to check
     * @return {@code true} if a committed record exists
     * @throws PersistenceException if the WAL is corrupted, tampered with, or its key is missing
     */
    public boolean hasCommittedState(String roomId) {
        requireLockedRoom(roomId);
        Path path = walPath(roomId);
        return Files.exists(path) && readWal(path, roomId).latestPayload() != null;
    }

    /**
     * Recovers the latest committed payload from every {@code .wal} file in alphabetical order,
     * acquiring each room's lock in turn. Corrupted files are quarantined ({@code .wal.corrupted})
     * and uncommitted files are deleted so healthy rooms still recover.
     *
     * @return room IDs mapped to their latest committed bytes
     * @throws PersistenceException if directory listing fails, a decryption key is missing, or the store is unhealthy
     */
    public Map<String, byte[]> recoverAll() {
        if (!isHealthy()) {
            throw new PersistenceException("Persistence needs a full checkpoint before further appends");
        }
        cleanOrphanedCompactingFiles();
        Map<String, byte[]> recovered = new HashMap<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(EncryptedWalStore::isWalFile)
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                String fileName = path.getFileName().toString();
                String roomId = fileName.substring(0, fileName.length() - WAL_SUFFIX.length());
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
     * Atomically replaces a healthy room's WAL with a fresh 2-frame file holding {@code latestPayload}
     * encrypted under the current key. Caller must hold the room lock.
     *
     * @param roomId        the room to compact
     * @param latestPayload the latest snapshot bytes to keep
     * @throws PersistenceException if the room is unhealthy or atomic replacement fails
     */
    public void compact(String roomId, byte[] latestPayload) {
        requireHealthyLockedRoom(roomId);
        replaceWalWithCurrentState(roomId, latestPayload);
    }

    /**
     * Atomically replaces {@code roomId}'s WAL with a fresh 2-frame file, even if the room is
     * unhealthy, clearing that room's failure and checkpoint flags on success. Caller must hold
     * the room lock.
     *
     * @param roomId        the room to checkpoint
     * @param latestPayload the latest snapshot or deletion tombstone bytes
     * @throws PersistenceException if atomic replacement fails
     */
    public void checkpoint(String roomId, byte[] latestPayload) {
        requireLockedRoom(roomId);
        replaceWalWithCurrentState(roomId, latestPayload);
    }

    /**
     * Deletes {@code roomId}'s WAL (and any {@code .compacting} file) and clears its tracking,
     * even when write failures exist. Caller must hold the room lock.
     *
     * @param roomId the room to delete
     * @throws PersistenceException if startup recovery failed or deletion fails
     */
    public void delete(String roomId) {
        requireLockedRoom(roomId);
        if (recoveryFailed.get()) {
            throw new PersistenceException("Persistence recovery failed");
        }
        runOrRecordFailure(roomId, "Cannot delete WAL for room " + roomId, () -> {
            faultInjector.check(WalFaultPoint.BEFORE_DELETE, roomId);
            Files.deleteIfExists(walPath(roomId));
            Files.deleteIfExists(compactingPath(roomId));
            forceDirectory();
            clearRoomTracking(roomId);
            logger.debug("WAL deleted room={}", roomId);
        });
    }

    /**
     * Renames a corrupted WAL to {@code <roomId>.wal.corrupted} and clears the room's tracking so
     * other rooms are unaffected. Caller must hold the room lock.
     *
     * @param roomId the room to quarantine
     * @param reason diagnostic reason logged for the quarantine
     * @return the quarantined file path
     * @throws PersistenceException if {@code roomId} is invalid or moving the file fails
     */
    public Path quarantine(String roomId, String reason) {
        requireLockedRoom(roomId);
        Path wal = walPath(roomId);
        Path quarantinePath = corruptedPath(roomId);
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
     * Removes in-memory sequence, checkpoint, and failure tracking for an unloaded room.
     * Caller must hold the room lock.
     *
     * @param roomId the room whose tracking should be cleared
     */
    public void clearRoomTracking(String roomId) {
        requireLockedRoom(roomId);
        nextSequences.remove(roomId);
        checkpointRooms.remove(roomId);
        failedRooms.remove(roomId);
    }

    /**
     * Returns {@code true} only when startup recovery succeeded and no room has an active write failure.
     *
     * @return overall store health
     */
    public boolean isHealthy() {
        return !recoveryFailed.get() && failedRooms.isEmpty();
    }

    /**
     * Returns {@code true} when startup recovery succeeded and {@code roomId} has no active write failure.
     *
     * @param roomId the room to check
     * @return per-room persistence health
     */
    public boolean isHealthy(String roomId) {
        return !recoveryFailed.get() && roomId != null && !failedRooms.contains(roomId);
    }

    /**
     * Returns whether {@code roomId} must run a full checkpoint before further appends.
     * Caller must hold the room lock.
     *
     * @param roomId the room to check
     * @return {@code true} if a checkpoint is required
     */
    public boolean requiresCheckpoint(String roomId) {
        requireLockedRoom(roomId);
        return checkpointRooms.contains(roomId);
    }

    /** Returns the number of rooms whose recovery protection is currently degraded. */
    public int unsavedRoomCount() {
        return failedRooms.size();
    }

    /**
     * Returns the simple class name of the last storage error (never keys or game data), or
     * {@code null} when healthy.
     *
     * @return sanitized last error name, or {@code null}
     */
    public String lastError() {
        return isHealthy() ? null : lastError;
    }

    /**
     * Returns the number of active {@code .wal} files, or {@code -1} if the directory cannot be read.
     *
     * @return active WAL count, or {@code -1} on I/O failure
     */
    public long walCount() {
        try (var paths = Files.list(directory)) {
            return paths.filter(EncryptedWalStore::isWalFile).count();
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Returns {@code roomId}'s WAL size in bytes, {@code 0} if absent, or {@link Long#MAX_VALUE}
     * if unreadable (so compaction triggers). Caller must hold the room lock.
     *
     * @param roomId the room to check
     * @return WAL size in bytes
     */
    public long walSize(String roomId) {
        requireLockedRoom(roomId);
        try {
            Path path = walPath(roomId);
            return Files.exists(path) ? Files.size(path) : 0;
        } catch (IOException e) {
            // An unreadable file must look "too big" so the size check forces a compaction that replaces it,
            // whereas a bogus size of 0 or -1 would make callers believe the WAL is small and healthy.
            return Long.MAX_VALUE;
        }
    }

    /**
     * Marks {@code roomId} unhealthy and requiring a full checkpoint before further appends.
     * Caller must hold the room lock.
     *
     * @param roomId  the affected room
     * @param failure the storage failure to record
     */
    public void recordWriteFailure(String roomId, RuntimeException failure) {
        requireLockedRoom(roomId);
        lastError = failure.getClass().getSimpleName();
        checkpointRooms.add(roomId);
        failedRooms.add(roomId);
    }

    private void replaceWalWithCurrentState(String roomId, byte[] latestPayload) {
        long startMs = System.currentTimeMillis();
        long bytesBefore = walSize(roomId);
        runOrRecordFailure(roomId, "Cannot compact WAL for room " + roomId, () -> {
            try {
                Path replacement = compactingPath(roomId);
                Files.deleteIfExists(replacement);
                writeFreshWal(replacement, roomId, latestPayload);
                faultInjector.check(WalFaultPoint.BEFORE_COMPACTION_REPLACE, roomId);
                replaceAtomically(replacement, walPath(roomId));
                forceDirectory();
                nextSequences.put(roomId, SEQUENCE_AFTER_CHECKPOINT);
                checkpointRooms.remove(roomId);
                failedRooms.remove(roomId);
                long bytesAfter = walSize(roomId);
                long durationMs = System.currentTimeMillis() - startMs;
                logger.info("WAL compacted room={} bytesBefore={} bytesAfter={} ms={}", roomId, bytesBefore, bytesAfter, durationMs);
            } catch (IOException | RuntimeException e) {
                deleteCompactingFileQuietly(roomId);
                throw e;
            }
        });
    }

    private long nextSequence(String roomId) {
        Long cached = nextSequences.get(roomId);
        if (cached != null) {
            return cached;
        }
        Path path = walPath(roomId);
        long calculated = FIRST_SEQUENCE;
        if (Files.exists(path)) {
            WalReadResult recovered = readWal(path, roomId);
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
        Path path = walPath(roomId);
        boolean createsWal = !Files.exists(path);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            writeFully(channel, frame);
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
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keyring.currentKey(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad);
            byte[] ciphertext = cipher.doFinal(payload);

            int bodyLength = aad.length + NONCE_LENGTH + Integer.BYTES + ciphertext.length;
            return ByteBuffer.allocate(FRAME_HEADER_BYTES + bodyLength)
                    .putInt(MAGIC)
                    .putInt(bodyLength)
                    .put(aad)
                    .put(nonce)
                    .putInt(ciphertext.length)
                    .put(ciphertext)
                    .array();
        } catch (GeneralSecurityException e) {
            throw new PersistenceException("Cannot encrypt WAL record", e);
        }
    }

    private WalReadResult readWal(Path path, String expectedRoomId) {
        try {
            byte[] file = Files.readAllBytes(path);
            ByteBuffer bytes = ByteBuffer.wrap(file);
            long previousSequence = 0;
            boolean incompleteTail = false;
            long validLength = file.length;
            byte[] latest = null;
            WalRecord pending = null;
            while (bytes.hasRemaining()) {
                if (bytes.remaining() < FRAME_HEADER_BYTES) {
                    incompleteTail = true;
                    validLength = bytes.position();
                    break;
                }
                int magic = bytes.getInt();
                int frameLength = bytes.getInt();
                if (magic != MAGIC || frameLength <= 0 || frameLength > MAX_FRAME_LENGTH) {
                    throw new PersistenceException("Corrupt WAL frame header for room " + expectedRoomId);
                }
                if (bytes.remaining() < frameLength) {
                    incompleteTail = true;
                    validLength = (long) bytes.position() - FRAME_HEADER_BYTES;
                    break; // The save file was cut off right at the end (like from a power outage), which is okay. We just ignore this last partial save.
                }
                ByteBuffer frame = bytes.slice(bytes.position(), frameLength);
                bytes.position(bytes.position() + frameLength);
                WalRecord record = decodeFrame(frame, expectedRoomId);
                if (record.sequence() != previousSequence + 1) {
                    throw new PersistenceException("WAL sequence is missing or reordered for room " + expectedRoomId);
                }
                previousSequence = record.sequence();
                if (record.type() == RecordType.PREPARE) {
                    pending = record;
                } else if (pending == null || !pending.transactionId().equals(record.transactionId())
                        || record.sequence() != pending.sequence() + 1) {
                    throw new PersistenceException("Committed WAL transaction has no matching prepare");
                } else {
                    latest = record.payload();
                    pending = null;
                }
            }
            return new WalReadResult(latest, previousSequence + 1, incompleteTail, validLength);
        } catch (IOException e) {
            throw new PersistenceException("Cannot read WAL for room " + expectedRoomId, e);
        }
    }

    private void repairIncompleteTail(Path path, String roomId, WalReadResult result) {
        if (!result.incompleteTail()) return;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.truncate(result.validLength());
            channel.force(true);
        } catch (IOException e) {
            throw new PersistenceException("Cannot repair WAL for room " + roomId, e);
        }
        logger.warn("Truncated incomplete trailing WAL bytes for room {} to validLength {}",
                roomId, result.validLength());
        checkpointRooms.add(roomId);
    }

    private WalRecord decodeFrame(ByteBuffer frame, String expectedRoomId) {
        try {
            long sequence = frame.getLong();
            UUID transactionId = new UUID(frame.getLong(), frame.getLong());
            RecordType type = RecordType.from(frame.get());
            String roomId = readUtf8(frame);
            String keyId = readUtf8(frame);
            int aadLength = frame.position();
            if (!expectedRoomId.equals(roomId)) {
                throw new PersistenceException("WAL record belongs to a different room");
            }
            byte[] nonce = new byte[NONCE_LENGTH];
            frame.get(nonce);
            int ciphertextLength = frame.getInt();
            if (ciphertextLength < GCM_TAG_BYTES || ciphertextLength != frame.remaining()) {
                throw new PersistenceException("Invalid encrypted WAL payload length");
            }
            byte[] ciphertext = new byte[ciphertextLength];
            frame.get(ciphertext);
            SecretKey key = keyring.key(keyId);
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(frame.slice(0, aadLength));
            return new WalRecord(sequence, transactionId, type, cipher.doFinal(ciphertext));
        } catch (PersistenceException e) {
            throw e;
        } catch (Exception e) {
            throw new PersistenceException("WAL authentication or structure validation failed", e);
        }
    }

    private static byte[] associatedData(
            String roomId, long sequence, UUID transactionId, RecordType type, String keyId) {
        byte[] room = utf8Bytes(roomId);
        byte[] key = utf8Bytes(keyId);
        return ByteBuffer.allocate(AAD_FIXED_BYTES + AAD_LENGTH_PREFIX_BYTES + room.length
                        + AAD_LENGTH_PREFIX_BYTES + key.length)
                .putLong(sequence)
                .putLong(transactionId.getMostSignificantBits())
                .putLong(transactionId.getLeastSignificantBits())
                .put(type.code)
                .putShort((short) room.length)
                .put(room)
                .putShort((short) key.length)
                .put(key)
                .array();
    }

    private void writeFreshWal(Path path, String roomId, byte[] payload) throws IOException {
        UUID transactionId = UUID.randomUUID();
        byte[] prepare = encodeFrame(roomId, CHECKPOINT_PREPARE_SEQUENCE, transactionId, RecordType.PREPARE, new byte[0]);
        byte[] commit = encodeFrame(roomId, CHECKPOINT_COMMIT_SEQUENCE, transactionId, RecordType.COMMIT, payload);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            faultInjector.check(WalFaultPoint.BEFORE_PREPARE_WRITE, roomId);
            writeFully(channel, prepare);
            faultInjector.check(WalFaultPoint.BEFORE_COMMIT_WRITE, roomId);
            writeFully(channel, commit);
            faultInjector.check(WalFaultPoint.BEFORE_FLUSH, roomId);
            channel.force(true);
        }
    }

    private static void writeFully(FileChannel channel, byte[] frame) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(frame);
        while (bytes.hasRemaining()) {
            channel.write(bytes);
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
            if (!IS_WINDOWS) {
                throw e;
            }
        }
    }

    private static byte[] utf8Bytes(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Short.MAX_VALUE) {
            throw new PersistenceException("WAL metadata value is too long");
        }
        return bytes;
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

    private static boolean isWalFile(Path path) {
        return path.getFileName().toString().endsWith(WAL_SUFFIX);
    }

    private Path walPath(String roomId) {
        return directory.resolve(roomId + WAL_SUFFIX);
    }

    private Path compactingPath(String roomId) {
        return directory.resolve(roomId + COMPACTING_SUFFIX);
    }

    private Path corruptedPath(String roomId) {
        return directory.resolve(roomId + CORRUPTED_SUFFIX);
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
            if (Files.deleteIfExists(compactingPath(roomId))) {
                logger.info("Cleaned up compaction file for room {}", roomId);
            }
        } catch (IOException ignored) {
        }
    }

    private static void validateRoomId(String roomId) {
        if (roomId == null || !VALID_ROOM_ID.matcher(roomId).matches()) {
            throw new PersistenceException("Invalid room ID for WAL path");
        }
    }

    private void requireLockedRoom(String roomId) {
        validateRoomId(roomId);
        roomCoordinator.assertHeldByCurrentThread(roomId);
    }

    private void requireHealthyLockedRoom(String roomId) {
        requireLockedRoom(roomId);
        if (!isHealthy(roomId)) {
            throw new PersistenceException("Persistence needs a full checkpoint before further appends");
        }
    }

    private void runOrRecordFailure(String roomId, String ioFailureMessage, IoRunnable action) {
        runOrRecordFailure(roomId, ioFailureMessage, () -> {
            action.run();
            return null;
        });
    }

    private <T> T runOrRecordFailure(String roomId, String ioFailureMessage, IoAction<T> action) {
        try {
            return action.run();
        } catch (IOException e) {
            PersistenceException failure = new PersistenceException(ioFailureMessage, e);
            recordWriteFailure(roomId, failure);
            throw failure;
        } catch (RuntimeException e) {
            recordWriteFailure(roomId, e);
            throw e;
        }
    }

    @FunctionalInterface
    private interface IoRunnable {
        void run() throws IOException;
    }

    @FunctionalInterface
    private interface IoAction<T> {
        T run() throws IOException;
    }

    private enum RecordType {
        PREPARE((byte) 1), COMMIT((byte) 2);
        private final byte code;

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

    private record WalRecord(long sequence, UUID transactionId, RecordType type, byte[] payload) {
    }

    private record WalReadResult(byte[] latestPayload, long nextSequence, boolean incompleteTail, long validLength) {
    }
}
