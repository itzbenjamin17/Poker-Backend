package com.pokergame.exception;

/**
 * Exception thrown when a request conflicts with the current resource state,
 * such as requesting a game review while the game is still active.
 */
public class ConflictException extends PokerException {

    /**
     * Creates a conflict exception with a failure message.
     *
     * @param message failure description
     */
    public ConflictException(String message) {
        super(message);
    }

    /**
     * Creates a conflict exception with its underlying cause.
     *
     * @param message failure description
     * @param cause   underlying failure
     */
    public ConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
