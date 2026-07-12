package com.pokergame.exception;

/**
 * Exception thrown when a user exceeds the allowed rate limit.
 */
public class TooManyRequestsException extends PokerException {
    /**
     * Creates an exception for a rejected rate-limited request.
     *
     * @param message client-safe rate-limit description
     */
    public TooManyRequestsException(String message) {
        super(message);
    }
}
