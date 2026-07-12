package com.pokergame.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binds the operator-controlled durability policy under {@code poker.persistence}.
 * Defaults favor local development while production is expected to supply an
 * external durable directory and key material.
 */
@ConfigurationProperties(prefix = "poker.persistence")
public class PersistenceProperties {
    private boolean enabled;
    private Path directory = Path.of("data", "poker-wal");
    private String currentKeyId;
    private Map<String, String> keys = new LinkedHashMap<>();
    private int compactAfterRecords = 1000;
    private long compactAfterBytes = 16L * 1024 * 1024;

    /**
     * Indicates whether durability beans should exist at all, allowing tests and
     * development profiles to keep the original in-memory mode explicitly.
     *
     * @return whether encrypted persistence is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Keeps persistence opt-in so local/test profiles do not accidentally create a
     * second source of truth merely because this configuration class is scanned.
     *
     * @param enabled {@code true} to activate WAL persistence
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * The path is operator-controlled because WAL lifetime must follow the mounted
     * volume, not the replaceable application image.
     *
     * @return WAL directory
     */
    public Path getDirectory() {
        return directory;
    }

    /**
     * Separates application packaging from storage placement; production can mount a
     * durable volume while development keeps a repository-local default.
     *
     * @param directory WAL directory
     */
    public void setDirectory(Path directory) {
        this.directory = directory;
    }

    /**
     * A distinct current ID prevents map iteration order from silently choosing the
     * write key during rotation.
     *
     * @return current write-key identifier
     */
    public String getCurrentKeyId() {
        return currentKeyId;
    }

    /**
     * Rotation changes this selector only after the new key is present alongside any
     * historical read keys.
     *
     * @param currentKeyId current write-key identifier
     */
    public void setCurrentKeyId(String currentKeyId) {
        this.currentKeyId = currentKeyId;
    }

    /**
     * Historical entries remain necessary until compaction has rewritten every WAL;
     * dropping one early intentionally makes startup fail closed.
     *
     * @return configured key material
     */
    public Map<String, String> getKeys() {
        return keys;
    }

    /**
     * Binding accepts the complete rotation set so records can select keys by their
     * authenticated persisted IDs instead of trial decryption.
     *
     * @param keys Base64 AES keys indexed by key ID
     */
    public void setKeys(Map<String, String> keys) {
        this.keys = keys;
    }

    /**
     * The count threshold bounds recovery scanning for busy rooms even when each
     * encrypted snapshot is small.
     *
     * @return record-count compaction threshold
     */
    public int getCompactAfterRecords() {
        return compactAfterRecords;
    }

    /**
     * This policy remains separate from byte size because update frequency and state
     * size create independent operational costs.
     *
     * @param compactAfterRecords maximum records before compaction
     */
    public void setCompactAfterRecords(int compactAfterRecords) {
        this.compactAfterRecords = compactAfterRecords;
    }

    /**
     * The byte threshold bounds disk use for large games even before they accumulate
     * enough records to reach the count threshold.
     *
     * @return byte-size compaction threshold
     */
    public long getCompactAfterBytes() {
        return compactAfterBytes;
    }

    /**
     * This policy remains separate from record count so a few large state images can
     * trigger maintenance promptly.
     *
     * @param compactAfterBytes maximum WAL bytes before compaction
     */
    public void setCompactAfterBytes(long compactAfterBytes) {
        this.compactAfterBytes = compactAfterBytes;
    }
}
