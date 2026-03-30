package com.pokergame.exception;

/**
 * Exception thrown when an unauthorized action is attempted.
 */

public class UnauthorisedActionException extends PokerException {
    public UnauthorisedActionException(String message) {
        super(message);
    }
}
