package com.pokergame.exception;

/**
 * Exception thrown when an unauthorized action is attempted.
 */

public class UnauthorisedActionException extends ActionFeedbackException {
    /**
     * Creates an exception for an action the current player may not perform.
     *
     * @param message client-safe authorization failure description
     */
    public UnauthorisedActionException(String message) {
        super(message);
    }

    /** Creates a forbidden action with stable action feedback. */
    public UnauthorisedActionException(String message, String actionErrorCode, String actionErrorMessage) {
        super(message, actionErrorCode, actionErrorMessage);
    }
}
