package com.pokergame.exception;

/**
 * Base exception for all poker game exceptions.
 */

@SuppressWarnings("unused")
public abstract class PokerException extends RuntimeException {
    public PokerException(String message) {
        super(message);
    }

    public PokerException(String message, Throwable cause) {
        super(message, cause);
    }
}
