package com.pokergame.persistence;

import javax.crypto.SecretKey;
import java.util.Map;

public final class EncryptionKeyring {
    private final String currentKeyId;
    private final Map<String, SecretKey> keys;

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

    public String currentKeyId() {
        return currentKeyId;
    }

    public SecretKey currentKey() {
        return key(currentKeyId);
    }

    public SecretKey key(String keyId) {
        SecretKey key = keys.get(keyId);
        if (key == null) {
            throw new PersistenceException("Required persistence key is unavailable: " + keyId);
        }
        return key;
    }
}
