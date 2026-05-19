package com.pokergame.exception;

/**
 * Exception thrown when a user exceeds the allowed rate limit.
 */
public class TooManyRequestsException extends PokerException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}
