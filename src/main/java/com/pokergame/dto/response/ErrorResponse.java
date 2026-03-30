package com.pokergame.dto.response;

/**
 * Represents an error response.
 * 
 * @param status  The HTTP status code.
 * @param error   The error type.
 * @param message The error message.
 */

public record ErrorResponse(int status, String error, String message) {}
