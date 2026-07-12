package com.pokergame.persistence;

/**
 * Signals that the application cannot guarantee durable poker state.
 * <p>
 * A dedicated unchecked type lets the persistence boundary fail commands closed
 * without leaking serializer, cryptography, or filesystem-specific exceptions into
 * service contracts.
 * </p>
 */
public class PersistenceException extends RuntimeException {
    /**
     * Creates a persistence failure with an operator-safe explanation.
     *
     * @param message failure description that must not contain decrypted state or key material
     */
    public PersistenceException(String message) {
        super(message);
    }

    /**
     * Wraps the technical cause while preserving a storage-domain failure contract.
     *
     * @param message operator-safe failure description
     * @param cause   underlying serialization, cryptography, or filesystem failure
     */
    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
