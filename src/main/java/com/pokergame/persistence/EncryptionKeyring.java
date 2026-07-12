package com.pokergame.persistence;

import javax.crypto.SecretKey;
import java.util.Map;

/**
 * Separates the current write key from historical read keys so operators can
 * rotate encryption without making existing WALs unreadable.
 */
public final class EncryptionKeyring {
    private final String currentKeyId;
    private final Map<String, SecretKey> keys;

    /**
     * Builds an immutable keyring and rejects non-AES-256 material at startup so a
     * deployment cannot begin accepting traffic with an unusable recovery set.
     *
     * @param currentKeyId identifier used for new WAL records
     * @param keys         current and historical keys indexed by persisted ID
     * @throws PersistenceException if the current key is absent or any key is not 256-bit
     */
    public EncryptionKeyring(String currentKeyId, Map<String, SecretKey> keys) {
        if (currentKeyId == null || currentKeyId.isBlank()) {
            throw new PersistenceException("A current persistence key ID is required");
        }
        this.keys = Map.copyOf(keys);
        this.currentKeyId = currentKeyId;
        SecretKey current = key(currentKeyId);
        if (current.getEncoded() == null || current.getEncoded().length != 32) {
            throw new PersistenceException("Persistence keys must be 256-bit AES keys");
        }
        for (Map.Entry<String, SecretKey> entry : this.keys.entrySet()) {
            if (entry.getValue().getEncoded() == null || entry.getValue().getEncoded().length != 32) {
                throw new PersistenceException("Persistence key " + entry.getKey() + " is not 256-bit");
            }
        }
    }

    /**
     * Returns the identifier embedded in new records so readers can select the key
     * without trial decryption.
     *
     * @return current write-key identifier
     */
    public String currentKeyId() {
        return currentKeyId;
    }

    /**
     * Returns the only key permitted for new records; historical keys remain
     * read-only to make rotation direction explicit.
     *
     * @return current AES-256 write key
     */
    public SecretKey currentKey() {
        return key(currentKeyId);
    }

    /**
     * Resolves the exact key named by authenticated WAL metadata. Missing keys fail
     * closed because guessing or skipping a record could recover stale state.
     *
     * @param keyId persisted key identifier
     * @return matching AES-256 key
     * @throws PersistenceException if the key is unavailable
     */
    public SecretKey key(String keyId) {
        SecretKey key = keys.get(keyId);
        if (key == null) {
            throw new PersistenceException("Required persistence key is unavailable: " + keyId);
        }
        return key;
    }
}
