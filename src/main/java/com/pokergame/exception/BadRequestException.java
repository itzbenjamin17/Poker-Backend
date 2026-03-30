package com.pokergame.exception;

/**
 * Exception thrown when a bad request is made.
 */

public class BadRequestException extends PokerException {
    public BadRequestException(String message) {
        super(message);
    }
}
