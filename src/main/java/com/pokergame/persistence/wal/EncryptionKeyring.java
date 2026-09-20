package com.pokergame.persistence.wal;

import javax.crypto.SecretKey;
import java.util.Map;
import com.pokergame.persistence.config.PersistenceException;

/**
 * Holds the encryption keys for the save files. 
 * We keep old keys around so we can still read older save files, but use a new key for any new saves.
 */
public final class EncryptionKeyring {
    private final String currentKeyId;
    private final Map<String, SecretKey> keys;

    /**
     * Creates a new keyring and checks that all keys are valid AES-256 keys.
     * We do this at startup so we don't accidentally start the server with broken keys.
     *
     * @param currentKeyId the ID of the key to use for new saves
     * @param keys         all the old and new keys we have, by their IDs
     * @throws PersistenceException if a key is missing or not the right length
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
     * Gets the ID of the newest key. We save this ID in the file so we know which key to use to read it later.
     *
     * @return the ID of the newest key
     */
    public String currentKeyId() {
        return currentKeyId;
    }

    /**
     * Gets the key we should use right now to encrypt any new game saves.
     *
     * @return the current encryption key
     */
    public SecretKey currentKey() {
        return key(currentKeyId);
    }

    /**
     * Looks up a specific key by its ID. If we don't have the key, we fail safely instead of trying to guess.
     *
     * @param keyId the ID of the key we need
     * @return the matching key
     * @throws PersistenceException if we don't have a key with that ID
     */
    public SecretKey key(String keyId) {
        SecretKey key = keys.get(keyId);
        if (key == null) {
            throw new PersistenceException("Required persistence key is unavailable: " + keyId);
        }
        return key;
    }
}
