package com.pokergame.exception;

/**
 * Exception thrown when a bad request is made.
 */

public class BadRequestException extends PokerException {
    /**
     * Creates a client-input exception.
     *
     * @param message client-safe description of the invalid request
     */
    public BadRequestException(String message) {
        super(message);
    }
}
