package com.pokergame.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.pokergame.dto.response.ErrorResponse;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Exception handler for all exceptions in the application.
 */

@ControllerAdvice
public class ControllerExceptionHandler {
    private static final Logger logger = LoggerFactory.getLogger(ControllerExceptionHandler.class);

    /**
     * Maps missing resources to HTTP 404.
     *
     * @param ex missing-resource failure
     * @return structured not-found response
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFoundException(ResourceNotFoundException ex) {
        logger.warn("Resource not found: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage());
        // returns 404 Not Found
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * Maps domain request failures to HTTP 400.
     *
     * @param ex invalid-request failure
     * @return structured bad-request response
     */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequestException(BadRequestException ex) {
        logger.warn("Bad request: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "Bad Request", ex.getMessage());
        // returns 400 Bad Request
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Maps invalid arguments raised outside the domain hierarchy to HTTP 400.
     *
     * @param ex invalid-argument failure
     * @return structured bad-request response
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException ex) {
        logger.warn("Illegal argument: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "Bad Request", ex.getMessage());
        // returns 400 Bad Request
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Maps forbidden poker actions to HTTP 403.
     *
     * @param ex authorization failure
     * @return structured forbidden response
     */
    @ExceptionHandler(UnauthorisedActionException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorizedActionException(UnauthorisedActionException ex) {
        logger.warn("Unauthorized action: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(HttpStatus.FORBIDDEN.value(), "Forbidden", ex.getMessage());
        // returns 403 Forbidden
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    /**
     * Maps general security failures to HTTP 403 while preserving the exception's
     * client-facing message.
     *
     * @param ex security failure
     * @return structured forbidden response
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ErrorResponse> handleSecurityException(SecurityException ex) {
        logger.warn("Security exception: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(HttpStatus.FORBIDDEN.value(), "Forbidden", ex.getMessage());
        // returns 403 Forbidden
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    /**
     * Maps application rate-limit failures to HTTP 429.
     *
     * @param ex rate-limit failure
     * @return structured too-many-requests response
     */
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ErrorResponse> handleTooManyRequestsException(TooManyRequestsException ex) {
        logger.warn("Rate limit exceeded: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(HttpStatus.TOO_MANY_REQUESTS.value(), "Too Many Requests", ex.getMessage());
        // returns 429 Too Many Requests
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(error);
    }

    /**
     * Returns the first Bean Validation message for an invalid request body.
     *
     * @param ex request validation failure
     * @return structured bad-request response
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .orElse("Invalid request payload");

        logger.warn("Validation error: {}", message);
        ErrorResponse error = new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "Bad Request", message);
        // returns 400 Bad Request
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Maps malformed JSON to a stable client-facing HTTP 400 response.
     *
     * @param ex request-body decoding failure
     * @return structured bad-request response
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(HttpMessageNotReadableException ex) {
        logger.warn("Malformed JSON request: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "Bad Request", "Malformed JSON request payload");
        // returns 400 Bad Request
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Hides unexpected implementation details behind a generic HTTP 500 response.
     *
     * @param ex unexpected application failure
     * @return structured internal-server-error response
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception ex) {
        logger.error("Unexpected error", ex);
        ErrorResponse error = new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error",
                "An unexpected error occurred");
        // returns 500 Internal Server Error
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

}
