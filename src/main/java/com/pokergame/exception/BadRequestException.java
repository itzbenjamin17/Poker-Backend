package com.pokergame.exception;

/**
 * Exception thrown when a bad request is made.
 */

public class BadRequestException extends ActionFeedbackException {
    /**
     * Creates a client-input exception.
     *
     * @param message client-safe description of the invalid request
     */
    public BadRequestException(String message) {
        super(message);
    }

    /** Creates a client-input failure with stable action feedback. */
    public BadRequestException(String message, String actionErrorCode, String actionErrorMessage) {
        super(message, actionErrorCode, actionErrorMessage);
    }
}
