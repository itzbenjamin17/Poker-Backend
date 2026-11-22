package com.pokergame.persistence.wal;

import com.pokergame.persistence.PersistenceException;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds named AES-256 encryption keys for WAL records.
 * New saves encrypt with the current key while older keys remain available to decrypt prior saves.
 */
public final class EncryptionKeyring {
    private static final int AES_256_KEY_BYTES = 32;

    private final String currentKeyId;
    private final Map<String, SecretKey> keys;

    /**
     * Validates that {@code currentKeyId} exists and every key in {@code keys} is a 256-bit key.
     *
     * @param currentKeyId ID of the key used to encrypt new saves
     * @param keys         all available keys by ID
     * @throws PersistenceException if {@code currentKeyId} is blank, missing, or any key is not 256-bit
     */
    public EncryptionKeyring(String currentKeyId, Map<String, SecretKey> keys) {
        if (currentKeyId == null || currentKeyId.isBlank()) {
            throw new PersistenceException("A current persistence key ID is required");
        }
        this.keys = Map.copyOf(keys);
        this.currentKeyId = currentKeyId;
        key(currentKeyId);
        for (Map.Entry<String, SecretKey> entry : this.keys.entrySet()) {
            byte[] encoded = entry.getValue().getEncoded();
            if (encoded == null || encoded.length != AES_256_KEY_BYTES) {
                throw new PersistenceException("Persistence key " + entry.getKey() + " is not 256-bit");
            }
        }
    }

    /**
     * Builds a keyring from Base64-encoded AES-256 keys (for example, from application configuration).
     *
     * @param currentKeyId ID of the key used to encrypt new saves
     * @param base64Keys   Base64-encoded keys by ID
     * @return validated keyring
     * @throws PersistenceException if any key is invalid Base64, not 256-bit, or {@code currentKeyId} is missing
     */
    public static EncryptionKeyring fromBase64(String currentKeyId, Map<String, String> base64Keys) {
        Map<String, SecretKey> keys = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : base64Keys.entrySet()) {
            try {
                keys.put(entry.getKey(), new SecretKeySpec(Base64.getDecoder().decode(entry.getValue()), "AES"));
            } catch (IllegalArgumentException e) {
                throw new PersistenceException("Persistence key " + entry.getKey() + " is not valid Base64", e);
            }
        }
        return new EncryptionKeyring(currentKeyId, keys);
    }

    /**
     * Returns the ID of the key used for new saves (stored with each record for later decryption).
     *
     * @return current key ID
     */
    public String currentKeyId() {
        return currentKeyId;
    }

    /**
     * Returns the key used to encrypt new saves.
     *
     * @return current encryption key
     */
    public SecretKey currentKey() {
        return key(currentKeyId);
    }

    /**
     * Returns the key for {@code keyId}.
     *
     * @param keyId key ID to look up
     * @return matching key
     * @throws MissingPersistenceKeyException if no key exists for {@code keyId}
     */
    public SecretKey key(String keyId) {
        SecretKey key = keys.get(keyId);
        if (key == null) {
            throw new MissingPersistenceKeyException(keyId);
        }
        return key;
    }
}
