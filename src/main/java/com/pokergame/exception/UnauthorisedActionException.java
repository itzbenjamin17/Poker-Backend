package com.pokergame.exception;

/**
 * Exception thrown when an unauthorized action is attempted.
 */

public class UnauthorisedActionException extends PokerException {
    /**
     * Creates an exception for an action the current player may not perform.
     *
     * @param message client-safe authorization failure description
     */
    public UnauthorisedActionException(String message) {
        super(message);
    }
}
