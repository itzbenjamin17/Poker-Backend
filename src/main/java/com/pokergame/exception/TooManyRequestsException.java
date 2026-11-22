package com.pokergame.exception;

/**
 * Exception thrown when a user exceeds the allowed rate limit.
 */
public class TooManyRequestsException extends PokerException {
    // Seconds until a retry can succeed, or 0 when unknown (no Retry-After header is sent).
    private final long retryAfterSeconds;

    /**
     * Creates an exception for a rejected rate-limited request.
     *
     * @param message client-safe rate-limit description
     */
    public TooManyRequestsException(String message) {
        this(message, 0);
    }

    /**
     * Creates an exception for a rejected rate-limited request with a known wait.
     *
     * @param message client-safe rate-limit description
     * @param retryAfterSeconds seconds until a retry can succeed; {@code 0} when unknown
     */
    public TooManyRequestsException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
