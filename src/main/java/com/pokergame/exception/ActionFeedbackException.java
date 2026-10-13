package com.pokergame.exception;

/** Domain rejection that can supply stable, concise action feedback. */
public abstract class ActionFeedbackException extends PokerException {
    private final String actionErrorCode;
    private final String actionErrorMessage;

    /** Creates a domain rejection without action-specific feedback. */
    protected ActionFeedbackException(String message) {
        this(message, null, null);
    }

    /** Creates a domain rejection with a code and client display message. */
    protected ActionFeedbackException(String message, String actionErrorCode, String actionErrorMessage) {
        super(message);
        this.actionErrorCode = actionErrorCode;
        this.actionErrorMessage = actionErrorMessage;
    }

    /** Returns the stable rule code when this failure belongs to a poker action. */
    public String actionErrorCode() { return actionErrorCode; }

    /** Returns the concise client message when this failure belongs to a poker action. */
    public String actionErrorMessage() { return actionErrorMessage; }
}
