package com.pokergame.exception;

/**
 * Exception thrown when a resource is not found.
 */

public class ResourceNotFoundException extends PokerException {
    /**
     * Creates an exception for a requested resource that does not exist.
     *
     * @param message client-safe description of the missing resource
     */
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
