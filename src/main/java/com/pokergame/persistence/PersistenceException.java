package com.pokergame.persistence;

/**
 * Thrown when saving or loading poker games fails, or when the persistence settings are invalid.
 * It hides the details of exactly how the storage or encryption failed from the rest of the application.
 */
public class PersistenceException extends RuntimeException {

    /**
     * Creates an error with a message describing what went wrong.
     *
     * @param message what went wrong
     */
    public PersistenceException(String message) {
        super(message);
    }

    /**
     * Creates an error that wraps the original, lower-level problem.
     *
     * @param message what went wrong
     * @param cause   the underlying error
     */
    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
