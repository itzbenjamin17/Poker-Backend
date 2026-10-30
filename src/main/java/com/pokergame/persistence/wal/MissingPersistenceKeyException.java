package com.pokergame.persistence.wal;

import com.pokergame.persistence.config.PersistenceException;

/**
 * Signals an incomplete operator keyring, rather than corruption of a room's WAL.
 * Recovery must fail without quarantining the encrypted data so the key can be restored.
 */
public final class MissingPersistenceKeyException extends PersistenceException {
    /**
     * Identifies the missing key without exposing any key material.
     *
     * @param keyId identifier required by the encrypted record
     */
    public MissingPersistenceKeyException(String keyId) {
        super("Required persistence key is unavailable: " + keyId);
    }
}
