package com.pokergame.persistence.wal;

import com.pokergame.persistence.PersistenceException;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds the encryption keys for the save files. 
 * We keep old keys around so we can still read older save files, but use a new key for any new saves.
 */
public final class EncryptionKeyring {
    private static final int AES_256_KEY_BYTES = 32;

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
        key(currentKeyId);
        for (Map.Entry<String, SecretKey> entry : this.keys.entrySet()) {
            byte[] encoded = entry.getValue().getEncoded();
            if (encoded == null || encoded.length != AES_256_KEY_BYTES) {
                throw new PersistenceException("Persistence key " + entry.getKey() + " is not 256-bit");
            }
        }
    }

    /**
     * Builds a keyring from Base64-encoded key text, as it appears in the application settings.
     * Decoding and the key-length check both happen here so a bad key stops the server at startup.
     *
     * @param currentKeyId the ID of the key to use for new saves
     * @param base64Keys   all the old and new keys as Base64 text, by their IDs
     * @return the keyring holding the decoded keys
     * @throws PersistenceException if a key is not valid Base64, is not 256-bit, or the current key is missing
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
            throw new MissingPersistenceKeyException(keyId);
        }
        return key;
    }
}
