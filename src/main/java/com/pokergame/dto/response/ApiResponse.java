package com.pokergame.dto.response;

/**
 * Generic API response wrapper for REST endpoints.
 * Provides consistent response structure across all API calls.
 *
 * @param message The message to be returned.
 * @param data    The data to be returned.
 * @param <T>     the type of data being returned
 */

public record ApiResponse<T>(
        String message,
        T data) {
    /**
     * Creates a successful response with data.
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(message, data);
    }

    /**
     * Creates a successful response without data.
     */
    public static <T> ApiResponse<T> success(String message) {
        return new ApiResponse<>(message, null);
    }
}
