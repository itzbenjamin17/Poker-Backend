package com.pokergame.persistence;

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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Stores one authenticated, append-only WAL per room for exact local recovery.
 * <p>
 * Records are framed and encrypted independently with AES-256-GCM so recovery can
 * distinguish an incomplete final write from corruption in committed history. Room
 * identity, sequencing, transaction identity, record type, and key ID are bound as
 * associated data to prevent valid ciphertext from being copied or reordered.
 * </p>
 * <p>
 * Any integrity or write failure makes the store unhealthy. Continuing to mutate
 * memory after durability becomes uncertain would let clients observe state that a
 * restart cannot reproduce.
 * </p>
 */
public final class EncryptedWalStore {
    private static final int MAGIC = 0x504B574C; // PKWL
    private static final short FORMAT_VERSION = 1;
    private static final short SCHEMA_VERSION = 1;
    private static final int NONCE_LENGTH = 12;
    private static final int MAX_FRAME_LENGTH = 64 * 1024 * 1024;

    private final Path directory;
    private final EncryptionKeyring keyring;
    private final WalFaultInjector faultInjector;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, ReentrantLock> roomLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> nextSequences = new ConcurrentHashMap<>();
    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private volatile String lastError;

    /**
     * Creates a production store without test fault injection.
     *
     * @param directory durable WAL directory
     * @param keyring   current and historical encryption keys
     * @throws PersistenceException if the directory cannot be initialized safely
     */
    public EncryptedWalStore(Path directory, EncryptionKeyring keyring) {
        this(directory, keyring, WalFaultInjector.NONE);
    }

    /**
     * Creates a store with explicit fault injection so crash boundaries can be
     * verified deterministically.
     *
     * @param directory     durable WAL directory
     * @param keyring       current and historical encryption keys
     * @param faultInjector test-controlled durability fault source
     * @throws PersistenceException if the directory cannot be initialized safely
     */
    public EncryptedWalStore(Path directory, EncryptionKeyring keyring, WalFaultInjector faultInjector) {
        this.directory = directory.toAbsolutePath().normalize();
        this.keyring = keyring;
        this.faultInjector = faultInjector;
        try {
            Files.createDirectories(this.directory);
            if (!Files.isDirectory(this.directory) || !Files.isWritable(this.directory)) {
                throw new PersistenceException("Persistence directory is not writable");
            }
        } catch (IOException e) {
            throw new PersistenceException("Cannot initialize persistence directory", e);
        }
    }

    /**
     * Flushes a prepare marker before business state changes. An unmatched prepare
     * is intentionally ignored during recovery, proving that a command was never
     * acknowledged as committed.
     *
     * @param roomId room whose mutation is about to begin
     * @return identity required to commit the matching state image
     * @throws PersistenceException if the store is unhealthy or prepare cannot be flushed
     */
    public WalTransaction prepare(String roomId) {
        requireHealthy();
        validateRoomId(roomId);
        ReentrantLock lock = roomLocks.computeIfAbsent(roomId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            long sequence = nextSequence(roomId);
            UUID transactionId = UUID.randomUUID();
            append(roomId, sequence, transactionId, RecordType.PREPARE, new byte[0]);
            nextSequences.put(roomId, sequence + 1);
            return new WalTransaction(roomId, transactionId, sequence);
        } catch (RuntimeException e) {
            markUnhealthy(e);
            throw e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Flushes the post-mutation state image only when it immediately follows the
     * supplied prepare record. This adjacency rule prevents stale transactions from
     * committing over newer room state.
     *
     * @param transaction matching prepare identity
     * @param payload     immutable aggregate state image
     * @throws PersistenceException if ordering, encryption, or durable flush fails
     */
    public void commit(WalTransaction transaction, byte[] payload) {
        requireHealthy();
        ReentrantLock lock = roomLocks.computeIfAbsent(transaction.roomId(), ignored -> new ReentrantLock());
        lock.lock();
        try {
            long expected = transaction.prepareSequence() + 1;
            long sequence = nextSequence(transaction.roomId());
            if (sequence != expected) {
                throw new PersistenceException("Transaction is no longer the active room transaction");
            }
            append(transaction.roomId(), sequence, transaction.transactionId(), RecordType.COMMIT, payload.clone());
            nextSequences.put(transaction.roomId(), sequence + 1);
        } catch (RuntimeException e) {
            markUnhealthy(e);
            throw e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Recovers the latest fully committed image while tolerating only an incomplete
     * final frame with a valid header. Every other structural or authentication
     * anomaly fails closed.
     *
     * @param roomId room to recover
     * @return latest committed image, or empty when no committed state exists
     * @throws PersistenceException if the WAL cannot be trusted
     */
    public Optional<byte[]> recoverLatest(String roomId) {
        validateRoomId(roomId);
        Path path = walPath(roomId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            Recovery recovery = readWal(path, roomId);
            nextSequences.put(roomId, recovery.nextSequence());
            return Optional.ofNullable(recovery.latestPayload()).map(byte[]::clone);
        } catch (RuntimeException e) {
            markUnhealthy(e);
            throw e;
        }
    }

    /**
     * Recovers every room in deterministic filename order so startup either builds a
     * complete registry or fails before traffic is accepted.
     *
     * @return committed images indexed by room ID
     * @throws PersistenceException if enumeration or any room recovery fails
     */
    public Map<String, byte[]> recoverAll() {
        requireHealthy();
        Map<String, byte[]> recovered = new HashMap<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".wal"))
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                String fileName = path.getFileName().toString();
                String roomId = fileName.substring(0, fileName.length() - 4);
                recoverLatest(roomId).ifPresent(payload -> recovered.put(roomId, payload));
            }
            return Map.copyOf(recovered);
        } catch (IOException e) {
            PersistenceException failure = new PersistenceException("Cannot enumerate persistence WALs", e);
            markUnhealthy(failure);
            throw failure;
        }
    }

    /**
     * Replaces historical records with one prepare/commit pair encrypted by the
     * current key. Atomic replacement plus directory sync makes old-key retirement
     * safe after all WALs are compacted successfully.
     *
     * @param roomId       room whose WAL should be compacted
     * @param latestPayload latest committed aggregate image
     * @throws PersistenceException if replacement cannot be made crash-safe
     */
    public void compact(String roomId, byte[] latestPayload) {
        requireHealthy();
        ReentrantLock lock = roomLocks.computeIfAbsent(roomId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            Path replacement = directory.resolve(roomId + ".wal.compacting");
            Files.deleteIfExists(replacement);
            writeFreshWal(replacement, roomId, latestPayload);
            faultInjector.check(WalFaultPoint.BEFORE_COMPACTION_REPLACE, roomId);
            replaceAtomically(replacement, walPath(roomId));
            forceDirectory();
            nextSequences.put(roomId, 3L);
        } catch (IOException e) {
            PersistenceException failure = new PersistenceException("Cannot compact WAL for room " + roomId, e);
            markUnhealthy(failure);
            throw failure;
        } catch (RuntimeException e) {
            markUnhealthy(e);
            throw e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes WAL artifacts only after a deletion tombstone has already committed.
     * This ordering prevents a crash during cleanup from resurrecting a finished
     * room.
     *
     * @param roomId room whose durable lifecycle has ended
     * @throws PersistenceException if deletion or directory synchronization fails
     */
    public void delete(String roomId) {
        requireHealthy();
        ReentrantLock lock = roomLocks.computeIfAbsent(roomId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            faultInjector.check(WalFaultPoint.BEFORE_DELETE, roomId);
            Files.deleteIfExists(walPath(roomId));
            Files.deleteIfExists(directory.resolve(roomId + ".wal.compacting"));
            forceDirectory();
            nextSequences.remove(roomId);
        } catch (IOException e) {
            PersistenceException failure = new PersistenceException("Cannot delete WAL for room " + roomId, e);
            markUnhealthy(failure);
            throw failure;
        } catch (RuntimeException e) {
            markUnhealthy(e);
            throw e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Indicates whether storage can still uphold the acknowledged-state guarantee.
     *
     * @return {@code true} until the first integrity or write failure
     */
    public boolean isHealthy() {
        return healthy.get();
    }

    /**
     * Provides a sanitized diagnostic for health reporting without exposing keys or
     * decrypted poker state.
     *
     * @return last storage error, or {@code null} when none has occurred
     */
    public String lastError() {
        return lastError;
    }

    /**
     * Counts active WAL files for operational health details. A sentinel is returned
     * instead of changing health from a diagnostic-only read.
     *
     * @return WAL count, or {@code -1} when the directory cannot be listed
     */
    public long walCount() {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".wal")).count();
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Returns WAL size for compaction policy. An unreadable size is treated as
     * maximally large so the next maintenance attempt surfaces the real failure.
     *
     * @param roomId room whose WAL size is needed
     * @return WAL bytes, zero if absent, or {@link Long#MAX_VALUE} on read failure
     */
    public long walSize(String roomId) {
        try {
            Path path = walPath(roomId);
            return Files.exists(path) ? Files.size(path) : 0;
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Derives sequence state from disk on first access so restart never relies on an
     * in-memory counter that disappeared with the process.
     *
     * @param roomId room whose next sequence is required
     * @return next contiguous WAL sequence
     */
    private long nextSequence(String roomId) {
        Long cached = nextSequences.get(roomId);
        if (cached != null) {
            return cached;
        }
        Path path = walPath(roomId);
        long calculated = Files.exists(path) ? readWal(path, roomId).nextSequence() : 1L;
        nextSequences.put(roomId, calculated);
        return calculated;
    }

    /**
     * Appends and forces exactly one framed record. The directory is also forced when
     * the WAL is first created because file data durability alone does not guarantee
     * that the new directory entry survives power loss.
     *
     * @param roomId       owning room
     * @param sequence     contiguous record sequence
     * @param transactionId prepare/commit transaction identity
     * @param type         prepare or commit marker
     * @param payload      plaintext payload encrypted into the record
     * @throws PersistenceException if encoding or durable write fails
     */
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

    /**
     * Encrypts one self-describing frame with a fresh GCM nonce. Metadata needed to
     * select a key remains readable but is authenticated as associated data, so it
     * cannot be altered without detection.
     *
     * @param roomId       owning room
     * @param sequence     contiguous record sequence
     * @param transactionId prepare/commit transaction identity
     * @param type         record type
     * @param payload      plaintext state bytes
     * @return complete framed record ready for append
     * @throws PersistenceException if cryptography or frame encoding fails
     */
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
                body.writeShort(FORMAT_VERSION);
                body.writeShort(SCHEMA_VERSION);
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

    /**
     * Validates framing, contiguous ordering, authentication, and prepare/commit
     * pairing before selecting a state image. An incomplete final body is safe to
     * ignore only after its magic and declared frame length have been validated.
     *
     * @param path           WAL path
     * @param expectedRoomId room identity derived from the filename
     * @return latest committed payload and next contiguous sequence
     * @throws PersistenceException if committed history is missing, reordered, or corrupt
     */
    private Recovery readWal(Path path, String expectedRoomId) {
        try {
            byte[] file = Files.readAllBytes(path);
            ByteBuffer bytes = ByteBuffer.wrap(file);
            List<WalRecord> records = new ArrayList<>();
            long previousSequence = 0;
            while (bytes.hasRemaining()) {
                if (bytes.remaining() < 8) {
                    throw new PersistenceException("Unverifiable trailing bytes in WAL for room " + expectedRoomId);
                }
                int magic = bytes.getInt();
                int frameLength = bytes.getInt();
                if (magic != MAGIC || frameLength <= 0 || frameLength > MAX_FRAME_LENGTH) {
                    throw new PersistenceException("Corrupt WAL frame header for room " + expectedRoomId);
                }
                if (bytes.remaining() < frameLength) {
                    break; // provably torn final frame body
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
            return new Recovery(latest, previousSequence + 1);
        } catch (IOException e) {
            throw new PersistenceException("Cannot read WAL for room " + expectedRoomId, e);
        }
    }

    /**
     * Authenticates one frame against its filename-derived room identity. Binding the
     * expected room prevents a valid encrypted record from being copied into another
     * room's WAL.
     *
     * @param frame          encoded frame body
     * @param expectedRoomId room identity derived from the containing WAL
     * @return authenticated decrypted record
     * @throws PersistenceException if schema, structure, key lookup, or authentication fails
     */
    private WalRecord decodeFrame(ByteBuffer frame, String expectedRoomId) {
        try {
            short format = frame.getShort();
            short schema = frame.getShort();
            if (format != FORMAT_VERSION || schema != SCHEMA_VERSION) {
                throw new PersistenceException("Unsupported WAL format or schema version");
            }
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

    /**
     * Canonically encodes nonsecret metadata that must be tamper-evident even though
     * recovery needs to read it before decrypting the payload.
     *
     * @param roomId       owning room
     * @param sequence     record sequence
     * @param transactionId transaction identity
     * @param type         record type
     * @param keyId        key selector
     * @return canonical AES-GCM associated data
     */
    private byte[] associatedData(String roomId, long sequence, UUID transactionId, RecordType type, String keyId) {
        byte[] room = roomId.getBytes(StandardCharsets.UTF_8);
        byte[] key = keyId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer aad = ByteBuffer.allocate(2 + 2 + 8 + 16 + 1 + 2 + room.length + 2 + key.length);
        aad.putShort(FORMAT_VERSION).putShort(SCHEMA_VERSION).putLong(sequence)
                .putLong(transactionId.getMostSignificantBits()).putLong(transactionId.getLeastSignificantBits())
                .put(type.code).putShort((short) room.length).put(room).putShort((short) key.length).put(key);
        return aad.array();
    }

    /**
     * Writes compaction output to a new file before replacement so the original WAL
     * remains recoverable until the replacement is complete.
     *
     * @param path    temporary replacement path
     * @param roomId owning room
     * @param payload latest committed image
     * @throws IOException if the temporary file cannot be written and forced
     */
    private void writeFreshWal(Path path, String roomId, byte[] payload) throws IOException {
        UUID transactionId = UUID.randomUUID();
        byte[] prepare = encodeFrame(roomId, 1, transactionId, RecordType.PREPARE, new byte[0]);
        byte[] commit = encodeFrame(roomId, 2, transactionId, RecordType.COMMIT, payload);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            for (byte[] frame : List.of(prepare, commit)) {
                ByteBuffer bytes = ByteBuffer.wrap(frame);
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
            }
            channel.force(true);
        }
    }

    /**
     * Requires true atomic replacement because a copy-and-delete fallback creates a
     * power-loss window with neither a trustworthy old nor new WAL.
     *
     * @param source fully forced replacement file
     * @param target active WAL path
     * @throws IOException if the filesystem cannot replace the WAL
     * @throws PersistenceException if atomic moves are unsupported
     */
    private static void replaceAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            throw new PersistenceException("Atomic WAL replacement is not supported by this filesystem", e);
        }
    }

    /**
     * Forces directory metadata after create, replace, and delete operations. Windows
     * does not expose directory handles through {@link FileChannel}, so local Windows
     * development retains atomic filesystem semantics while the production Linux
     * deployment receives the stronger power-loss guarantee.
     *
     * @throws IOException if directory metadata cannot be forced on a supported platform
     */
    private void forceDirectory() throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (java.nio.file.AccessDeniedException e) {
            // Windows does not expose directory handles through FileChannel. The
            // production Linux filesystem is fsynced; Windows still retains the
            // atomic replace guarantee used by local development and tests.
            if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
                throw e;
            }
        }
    }

    /**
     * Uses bounded length-prefixed UTF-8 so corrupt metadata cannot force unbounded
     * allocation during recovery.
     *
     * @param output frame output
     * @param value  metadata value
     * @throws IOException if the frame cannot be written
     * @throws PersistenceException if the value exceeds the format limit
     */
    private static void writeUtf8(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Short.MAX_VALUE) {
            throw new PersistenceException("WAL metadata value is too long");
        }
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    /**
     * Rejects lengths larger than the remaining frame instead of allowing buffer
     * exceptions to obscure structural corruption.
     *
     * @param input authenticated frame metadata buffer
     * @return decoded UTF-8 value
     * @throws PersistenceException if the declared value is truncated
     */
    private static String readUtf8(ByteBuffer input) {
        int length = Short.toUnsignedInt(input.getShort());
        if (length > input.remaining()) {
            throw new PersistenceException("Truncated WAL metadata");
        }
        byte[] bytes = new byte[length];
        input.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Keeps all room WALs under the configured directory after room IDs have passed
     * the path-safe validation boundary.
     *
     * @param roomId validated room ID
     * @return room WAL path
     */
    private Path walPath(String roomId) {
        return directory.resolve(roomId + ".wal");
    }

    /**
     * Restricts room IDs to a filename-safe alphabet so an authenticated user value
     * cannot escape the configured persistence directory.
     *
     * @param roomId room ID used in a WAL filename
     * @throws PersistenceException if the ID is null or path-unsafe
     */
    private static void validateRoomId(String roomId) {
        if (roomId == null || !roomId.matches("[A-Za-z0-9._-]+")) {
            throw new PersistenceException("Invalid room ID for WAL path");
        }
    }

    /**
     * Rejects every later mutation after integrity becomes uncertain. A restart and
     * full recovery is required to re-establish the memory-to-disk guarantee.
     *
     * @throws PersistenceException if an earlier storage operation failed
     */
    private void requireHealthy() {
        if (!healthy.get()) {
            throw new PersistenceException("Persistence is unhealthy; restart and recover before further mutations");
        }
    }

    /**
     * Latches the first-class unhealthy state while retaining only an operator-safe
     * diagnostic rather than sensitive payload or key data.
     *
     * @param failure storage failure that invalidated continued operation
     */
    private void markUnhealthy(RuntimeException failure) {
        healthy.set(false);
        lastError = failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }

    /**
     * Distinguishes intent from acknowledgement so recovery applies only state images
     * whose commit immediately follows the matching prepare.
     */
    private enum RecordType {
        PREPARE((byte) 1), COMMIT((byte) 2);
        private final byte code;

        /**
         * Associates the stable on-disk byte with the logical record role.
         *
         * @param code persisted format code
         */
        RecordType(byte code) {
            this.code = code;
        }

        /**
         * Rejects unknown codes so newer or corrupt formats cannot be interpreted as
         * a different durability operation.
         *
         * @param code persisted record code
         * @return matching record type
         * @throws PersistenceException if the code is unknown
         */
        private static RecordType from(byte code) {
            for (RecordType value : values()) {
                if (value.code == code) return value;
            }
            throw new PersistenceException("Unknown WAL record type");
        }
    }

    /**
     * Carries one authenticated record through transaction-pair validation.
     *
     * @param sequence      contiguous WAL sequence
     * @param transactionId prepare/commit identity
     * @param type          record role
     * @param payload       decrypted payload
     */
    private record WalRecord(long sequence, UUID transactionId, RecordType type, byte[] payload) {
    }

    /**
     * Returns both recovered state and sequence continuity so the next append cannot
     * reuse an on-disk sequence after restart.
     *
     * @param latestPayload latest committed image, or {@code null}
     * @param nextSequence  next contiguous sequence
     */
    private record Recovery(byte[] latestPayload, long nextSequence) {
    }
}
