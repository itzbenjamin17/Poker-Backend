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

    public EncryptedWalStore(Path directory, EncryptionKeyring keyring) {
        this(directory, keyring, WalFaultInjector.NONE);
    }

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

    public boolean isHealthy() {
        return healthy.get();
    }

    public String lastError() {
        return lastError;
    }

    public long walCount() {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".wal")).count();
        } catch (IOException e) {
            return -1;
        }
    }

    public long walSize(String roomId) {
        try {
            Path path = walPath(roomId);
            return Files.exists(path) ? Files.size(path) : 0;
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

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

    private byte[] associatedData(String roomId, long sequence, UUID transactionId, RecordType type, String keyId) {
        byte[] room = roomId.getBytes(StandardCharsets.UTF_8);
        byte[] key = keyId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer aad = ByteBuffer.allocate(2 + 2 + 8 + 16 + 1 + 2 + room.length + 2 + key.length);
        aad.putShort(FORMAT_VERSION).putShort(SCHEMA_VERSION).putLong(sequence)
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
                ByteBuffer bytes = ByteBuffer.wrap(frame);
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
            }
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
        } catch (java.nio.file.AccessDeniedException e) {
            // Windows does not expose directory handles through FileChannel. The
            // production Linux filesystem is fsynced; Windows still retains the
            // atomic replace guarantee used by local development and tests.
            if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
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

    private static void validateRoomId(String roomId) {
        if (roomId == null || !roomId.matches("[A-Za-z0-9._-]+")) {
            throw new PersistenceException("Invalid room ID for WAL path");
        }
    }

    private void requireHealthy() {
        if (!healthy.get()) {
            throw new PersistenceException("Persistence is unhealthy; restart and recover before further mutations");
        }
    }

    private void markUnhealthy(RuntimeException failure) {
        healthy.set(false);
        lastError = failure.getClass().getSimpleName() + ": " + failure.getMessage();
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

    private record Recovery(byte[] latestPayload, long nextSequence) {
    }
}
