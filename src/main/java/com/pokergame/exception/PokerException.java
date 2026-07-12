package com.pokergame.exception;

/**
 * Base exception for all poker game exceptions.
 */

@SuppressWarnings("unused")
public abstract class PokerException extends RuntimeException {
    /**
     * Creates a poker-domain exception with a failure message.
     *
     * @param message failure description
     */
    public PokerException(String message) {
        super(message);
    }

    /**
     * Creates a poker-domain exception with its underlying cause.
     *
     * @param message failure description
     * @param cause underlying failure
     */
    public PokerException(String message, Throwable cause) {
        super(message, cause);
    }
}
