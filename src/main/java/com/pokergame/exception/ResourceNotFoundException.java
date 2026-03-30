package com.pokergame.exception;

/**
 * Exception thrown when a resource is not found.
 */

public class ResourceNotFoundException extends PokerException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
