package com.pokergame.persistence.config;

/**
 * An error thrown when something goes wrong with saving or loading poker games.
 * <p>
 * Think of this as the "Check Engine" light for the autosave system. It tells the 
 * rest of the application that game data couldn't be safely saved or read, 
 * without exposing the messy details of exactly how the storage or encryption failed.
 * </p>
 */
public class PersistenceException extends RuntimeException {
    /**
     * Creates a new error with a simple message describing what went wrong.
     *
     * @param message a simple explanation of the error
     */
    public PersistenceException(String message) {
        super(message);
    }

    /**
     * Creates a new error that wraps the original, low-level technical problem.
     *
     * @param message a simple explanation of the error
     * @param cause   the actual technical error that happened behind the scenes
     */
    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
