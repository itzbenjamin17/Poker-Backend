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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import com.pokergame.persistence.config.PersistenceException;

/**
 * Saves a poker game's history to disk using an encrypted Write-Ahead Log (WAL), with one file per room.
 * This lets us exactly restore a game after a server crash.
 * <p>
 * Each save is encrypted independently. This way, if the server dies right in the middle of writing a file,
 * we can tell the difference between an interrupted save and a corrupted file. We also tie each save to its specific
 * room, so nobody can copy a save file from one room to another.
 * </p>
 * <p>
 * If anything goes wrong (like a disk error), the store stops working and marks itself unhealthy. If we kept playing 
 * the game in memory without being able to save, a server crash would make everyone lose their chips, and we wouldn't 
 * be able to get them back.
 * </p>
 */
public final class EncryptedWalStore {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(EncryptedWalStore.class);
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
     * Creates a regular store for saving games in production.
     *
     * @param directory the folder to save files in
     * @param keyring   the encryption keys to use
     * @throws PersistenceException if we can't create or write to the folder
     */
    public EncryptedWalStore(Path directory, EncryptionKeyring keyring) {
        this(directory, keyring, WalFaultInjector.NONE);
    }

    /**
     * Creates a store that lets us intentionally cause crashes during testing.
     *
     * @param directory     the folder to save files in
     * @param keyring       the encryption keys to use
     * @param faultInjector a tool to simulate crashes for our tests
     * @throws PersistenceException if we can't create or write to the folder
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
     * Writes a "prepare" marker to the log before we update the game's state. 
     * If the server crashes before we can write the final "commit", we'll just ignore this prepare when we restart.
     *
     * @param roomId the room we are updating
     * @return an ID needed to save the final game state
     * @throws PersistenceException if we can't save to the file or there's an error
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
     * Writes the final game state to the log. This only works if it exactly matches the "prepare" marker we just made.
     * This rule stops old, delayed saves from accidentally overwriting newer saves.
     *
     * @param transaction the ID from the "prepare" step
     * @param payload     the game state data to save
     * @throws PersistenceException if we have trouble writing or encrypting the data
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
     * Reads the file to find the latest valid game state.
     * It's okay if the very last save got cut off during a crash, but any other file issues will cause this to fail safely.
     *
     * @param roomId the room to restore
     * @return the latest saved game data, or empty if there's no saved data
     * @throws PersistenceException if the save file looks corrupted or tampered with
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
     * Restores all rooms by reading their save files in alphabetical order.
     * This ensures the server starts up fully before players can join.
     *
     * @return a map of room IDs to their saved game data
     * @throws PersistenceException if we can't read the files or restore a room
     */
    public Map<String, byte[]> recoverAll() {
        requireHealthy();
        Map<String, byte[]> recovered = new HashMap<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".wal"))
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                String fileName = path.getFileName().toString();
                String roomId = fileName.substring(0, fileName.length() - 4);
                Optional<byte[]> latest = recoverLatest(roomId);
                if (latest.isPresent()) {
                    recovered.put(roomId, latest.get());
                } else {
                    logger.info("Cleaning up uncommitted WAL file for room {}", roomId);
                    delete(roomId);
                }
            }
            return Map.copyOf(recovered);
        } catch (IOException e) {
            PersistenceException failure = new PersistenceException("Cannot enumerate persistence WALs", e);
            markUnhealthy(failure);
            throw failure;
        }
    }

    /**
     * Cleans up a room's save file by deleting all the old history and just keeping the newest game state.
     * This is done safely so a crash won't lose data. It also encrypts the file with the newest key.
     *
     * @param roomId        the room to clean up
     * @param latestPayload the latest game data to keep
     * @throws PersistenceException if we can't safely swap out the old file
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
     * Deletes a room's save file. We only do this after the game is fully over.
     * Doing it in this order ensures a crash won't bring a dead room back to life.
     *
     * @param roomId the room to delete
     * @throws PersistenceException if we fail to delete the file
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
     * Checks if the storage system is working properly.
     *
     * @return true if there are no errors, false if something broke
     */
    public boolean isHealthy() {
        return healthy.get();
    }

    /**
     * Gets a safe error message without showing any sensitive game data or keys.
     *
     * @return the last error message, or null if everything is fine
     */
    public String lastError() {
        return lastError;
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
     *
     * @param roomId the room to check
     * @return the file size in bytes, or a huge number if we can't read it
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
     * Finds out the next available number in the save sequence for this room.
     * We read this from the file instead of just relying on memory, in case of a crash.
     *
     * @param roomId the room to check
     * @return the next number in the sequence
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
     * Encrypts one piece of data and safely writes it to the file.
     *
     * @param roomId       the room this save belongs to
     * @param sequence     the number in the sequence
     * @param transactionId an ID linking a prepare and commit
     * @param type         whether this is a prepare or a commit
     * @param payload      the game data to save
     * @throws PersistenceException if we can't save it to disk
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
     * Encrypts the data and packages it with some plain-text info (like the key ID).
     * The plain-text info is protected so we know if it was tampered with.
     *
     * @param roomId       the room this save belongs to
     * @param sequence     the number in the sequence
     * @param transactionId an ID linking a prepare and commit
     * @param type         whether this is a prepare or a commit
     * @param payload      the unencrypted game data
     * @return the fully encrypted and packaged data ready to save
     * @throws PersistenceException if something goes wrong with encryption
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
     * Reads through the entire save file, checking for tampering and pulling out the latest valid game state.
     *
     * @param path           the path to the file
     * @param expectedRoomId the room we expect this file to be for
     * @return the latest valid game state and the next number in the sequence
     * @throws PersistenceException if the file is out of order or tampered with
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
            return new Recovery(latest, previousSequence + 1);
        } catch (IOException e) {
            throw new PersistenceException("Cannot read WAL for room " + expectedRoomId, e);
        }
    }

    /**
     * Decrypts a piece of data from the file and makes sure it wasn't tampered with.
     * We also check that it belongs to the right room.
     *
     * @param frame          the encrypted data
     * @param expectedRoomId the room this data should belong to
     * @return the decrypted data
     * @throws PersistenceException if the data is tampered with or we can't find the key
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
     * Bundles together the plain-text information that we want to protect from tampering.
     *
     * @param roomId       the room ID
     * @param sequence     the number in the sequence
     * @param transactionId the ID linking a prepare and commit
     * @param type         whether this is a prepare or a commit
     * @param keyId        the ID of the encryption key used
     * @return the bundled data
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
     * Writes a brand new save file to a temporary location. We do this before swapping it
     * in, so we don't accidentally delete the old one before the new one is completely ready.
     *
     * @param path    where to put the temporary file
     * @param roomId  the room this save belongs to
     * @param payload the latest game data
     * @throws IOException if we can't write the file
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
     * Safely swaps out the old save file for a new one in one single move.
     *
     * @param source the new file
     * @param target the old file to replace
     * @throws IOException if we can't move the file
     * @throws PersistenceException if the computer doesn't support swapping files safely
     */
    private static void replaceAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            throw new PersistenceException("Atomic WAL replacement is not supported by this filesystem", e);
        }
    }

    /**
     * Makes sure changes to the folder itself (like adding or removing files) are saved to the disk immediately.
     *
     * @throws IOException if we can't update the folder on the disk
     */
    private void forceDirectory() throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (java.nio.file.AccessDeniedException e) {
            // Windows doesn't let us sync directories the same way Linux does. That's fine for local testing.
            if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
                throw e;
            }
        }
    }

    /**
     * Writes text to the file, making sure it isn't too long.
     *
     * @param output where to write the text
     * @param value  the text to write
     * @throws IOException if we can't write to the file
     * @throws PersistenceException if the text is too long
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
     * Reads text from the file safely, making sure it doesn't try to read past the end.
     *
     * @param input the data to read from
     * @return the text
     * @throws PersistenceException if the text is cut off
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
     * Figures out the exact file path for a room's save file.
     *
     * @param roomId the room's ID
     * @return the full file path
     */
    private Path walPath(String roomId) {
        return directory.resolve(roomId + ".wal");
    }

    /**
     * Makes sure the room ID doesn't contain any weird characters that might trick the computer
     * into saving the file somewhere else.
     *
     * @param roomId the room's ID
     * @throws PersistenceException if the ID has bad characters
     */
    private static void validateRoomId(String roomId) {
        if (roomId == null || !roomId.matches("[A-Za-z0-9._-]+")) {
            throw new PersistenceException("Invalid room ID for WAL path");
        }
    }

    /**
     * Checks if the storage system is healthy. If there's an error, it refuses to do any more saving
     * until the server is restarted.
     *
     * @throws PersistenceException if the storage broke earlier
     */
    private void requireHealthy() {
        if (!healthy.get()) {
            throw new PersistenceException("Persistence is unhealthy; restart and recover before further mutations");
        }
    }

    /**
     * Remembers that the storage system broke, so we stop trying to save games until we restart.
     *
     * @param failure what went wrong
     */
    private void markUnhealthy(RuntimeException failure) {
        healthy.set(false);
        lastError = failure.getClass().getSimpleName() + ": " + failure.getMessage();
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

        /**
         * Reads the number from the file and turns it back into the type of piece.
         *
         * @param code the number from the file
         * @return the matching piece type
         * @throws PersistenceException if the number is unknown
         */
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
     */
    private record Recovery(byte[] latestPayload, long nextSequence) {
    }
}
